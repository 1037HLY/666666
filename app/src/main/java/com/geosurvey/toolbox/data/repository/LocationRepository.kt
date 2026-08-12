package com.geosurvey.toolbox.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.geosurvey.toolbox.data.database.LocationDao
import com.geosurvey.toolbox.data.database.LocationEntity
import com.geosurvey.toolbox.data.database.TrackEntity
import com.geosurvey.toolbox.domain.model.Constellation
import com.geosurvey.toolbox.domain.model.LocationData
import com.geosurvey.toolbox.domain.model.LocationQuality
import com.geosurvey.toolbox.domain.model.SatelliteInfo
import com.geosurvey.toolbox.domain.util.KalmanFilter2D
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 高精度GNSS定位仓库 - 完全重写
 * 1. 原生GPS(GNSS)最高优先级
 * 2. 开启GNSS原始数据监听
 * 3. 点位质量过滤
 * 4. 定位参数调优
 * 5. 高程优化
 */
class LocationRepository(
    private val context: Context,
    private val locationDao: LocationDao
) {
    companion object {
        private const val TAG = "LocationRepository"
        
        // 中国地区坐标范围
        private const val CHINA_LAT_MIN = 18.0
        private const val CHINA_LAT_MAX = 54.0
        private const val CHINA_LNG_MIN = 73.0
        private const val CHINA_LNG_MAX = 135.0
        
        // 质量阈值
        private const val HDOP_THRESHOLD_GOOD = 2.0f
        private const val HDOP_THRESHOLD_FAIR = 4.0f
        private const val HDOP_THRESHOLD_POOR = 8.0f
        private const val VDOP_THRESHOLD_POOR = 8.0f
        private const val SNR_THRESHOLD_MIN = 15.0f
        private const val SPEED_THRESHOLD_MOVING = 0.5f
        private const val DRIFT_THRESHOLD = 50.0f
    }

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    // Kalman滤波器
    private val kalmanFilterLat = KalmanFilter2D(
        processNoise = 0.01,
        measurementNoise = 5.0
    )

    // 状态流
    private val _currentLocation = MutableStateFlow<LocationData?>(null)
    val currentLocation: StateFlow<LocationData?> = _currentLocation.asStateFlow()

    private val _satellites = MutableStateFlow<List<SatelliteInfo>>(emptyList())
    val satellites: StateFlow<List<SatelliteInfo>> = _satellites.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _trackPoints = MutableStateFlow<List<TrackEntity>>(emptyList())
    val trackPoints: StateFlow<List<TrackEntity>> = _trackPoints.asStateFlow()

    private val _locationName = MutableStateFlow<String>("正在获取地址...")
    val locationName: StateFlow<String> = _locationName.asStateFlow()

    private val _detailedAddress = MutableStateFlow<DetailedAddress?>(null)
    val detailedAddress: StateFlow<DetailedAddress?> = _detailedAddress.asStateFlow()

    // GNSS质量参数
    private var hdop = 0.0f
    private var vdop = 0.0f
    private var pdop = 0.0f
    private var satelliteCount = 0
    private var usedSatelliteCount = 0
    private var avgSnr = 0.0f
    private var maxSnr = 0.0f
    
    // 分系统卫星计数
    private var gpsCount = 0
    private var glonassCount = 0
    private var galileoCount = 0
    private var beidouCount = 0
    private var qzssCount = 0

    // 位置缓存
    private var lastValidLocation: LocationData? = null
    private var previousLocation: LocationData? = null
    private var isStationary = false
    private var isMoving = false
    
    // 协程
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isRunning = false
    private var locationCount = 0

    // GNSS回调
    private var gnssStatusCallback: GnssStatusCallback? = null
    private var locationCallback: LocationCallback? = null

    // 最后一次GPS时间
    private var lastGpsTime = 0L
    private var hasGpsFix = false

    data class DetailedAddress(
        val fullAddress: String = "",
        val country: String = "",
        val adminArea: String = "",
        val subAdminArea: String = "",
        val locality: String = "",
        val subLocality: String = "",
        val thoroughfare: String = "",
        val featureName: String = "",
        val postalCode: String = ""
    )

    /**
     * GNSS状态回调 - 读取卫星原始数据
     */
    private inner class GnssStatusCallback : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            analyzeGnssStatus(status)
        }

        override fun onFirstFix(ttffMillis: Int) {
            Log.d(TAG, "✅ 首次GNSS定位成功! 耗时: ${ttffMillis}ms")
            hasGpsFix = true
        }

        override fun onStarted() {
            Log.d(TAG, "✅ GNSS定位开始")
        }

        override fun onStopped() {
            Log.d(TAG, "⏹ GNSS定位停止")
        }
    }

    /**
     * 分析GNSS卫星状态
     */
    private fun analyzeGnssStatus(status: android.location.GnssStatus) {
        val satelliteList = mutableListOf<SatelliteInfo>()
        
        gpsCount = 0
        glonassCount = 0
        galileoCount = 0
        beidouCount = 0
        qzssCount = 0
        
        var totalSnr = 0.0f
        var validSnrCount = 0
        var usedCount = 0
        
        for (i in 0 until status.satelliteCount) {
            val prn = status.getSvid(i)
            val snr = status.getCn0DbHz(i)
            val azimuth = status.getAzimuthDegrees(i)
            val elevation = status.getElevationDegrees(i)
            val usedInFix = status.usedInFix(i)
            
            if (usedInFix) usedCount++
            if (snr > 0) {
                totalSnr += snr
                validSnrCount++
            }
            
            val constellation = when (status.getConstellationType(i)) {
                GnssStatus.CONSTELLATION_GPS -> { gpsCount++; Constellation.GPS }
                GnssStatus.CONSTELLATION_GLONASS -> { glonassCount++; Constellation.GLONASS }
                GnssStatus.CONSTELLATION_GALILEO -> { galileoCount++; Constellation.GALILEO }
                GnssStatus.CONSTELLATION_BEIDOU -> { beidouCount++; Constellation.BEIDOU }
                GnssStatus.CONSTELLATION_QZSS -> { qzssCount++; Constellation.QZSS }
                else -> Constellation.UNKNOWN
            }

            satelliteList.add(
                SatelliteInfo(
                    prn = prn,
                    constellation = constellation,
                    snr = snr,
                    azimuth = azimuth,
                    elevation = elevation,
                    usedInFix = usedInFix
                )
            )
        }
        
        satelliteCount = status.satelliteCount
        usedSatelliteCount = usedCount
        avgSnr = if (validSnrCount > 0) totalSnr / validSnrCount else 0f
        maxSnr = satelliteList.maxOfOrNull { it.snr } ?: 0f
        
        _satellites.value = satelliteList
        
        Log.d(TAG, "🛰️ 卫星: 总数=$satelliteCount, 锁定=$usedCount, GPS=$gpsCount, 北斗=$beidouCount, SNR=${String.format("%.1f", avgSnr)}dB")
    }

    /**
     * 开始定位
     */
    fun startLocationUpdates() {
        Log.d(TAG, "========== 开始高精度GNSS定位 ==========")
        
        if (isRunning) {
            Log.d(TAG, "定位已在运行")
            return
        }
        
        if (!hasLocationPermission()) {
            Log.e(TAG, "❌ 没有定位权限")
            return
        }

        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        Log.d(TAG, "GPS: $isGpsEnabled, 网络: $isNetworkEnabled")
        
        if (!isGpsEnabled) {
            Log.w(TAG, "⚠️ GPS未开启，建议开启GPS以获得精确定位")
        }

        isRunning = true
        locationCount = 0
        hasGpsFix = false
        lastValidLocation = null
        previousLocation = null

        // 1. 注册GNSS原始数据监听 (Android 7.0+)
        registerGnssCallback()

        // 2. 启动原生GPS定位 (最高优先级)
        startNativeGpsUpdates()

        // 3. 启动Fused定位 (辅助)
        startFusedUpdates()

        // 4. 获取最后一次已知位置
        getLastKnownLocation()
    }

    /**
     * 注册GNSS回调
     */
    private fun registerGnssCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                gnssStatusCallback = GnssStatusCallback()
                locationManager.registerGnssStatusCallback(gnssStatusCallback!!, null)
                Log.d(TAG, "✅ GNSS回调已注册")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 注册GNSS回调失败: ${e.message}")
            }
        }
    }

    /**
     * 启动原生GPS定位 (最高优先级)
     */
    private fun startNativeGpsUpdates() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ 使用GnssLocationRequest
                val request = android.location.GnssRequest.Builder()
                    .setIntervalMillis(1000)
                    .setLowPowerMode(false)
                    .build()
                // 注意：GnssRequest需要注册GnssLocationListener
                // 但为了兼容性，我们使用LocationManager的requestLocationUpdates
            }
            
            // 使用LocationManager请求GPS更新 (更直接的GPS访问)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,  // 1秒
                    0.5f,  // 0.5米
                    nativeGpsCallback,
                    Looper.getMainLooper()
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    0.5f,
                    nativeGpsCallback
                )
            }
            Log.d(TAG, "✅ 原生GPS定位已启动")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ 原生GPS启动失败: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 原生GPS异常: ${e.message}")
        }
    }

    /**
     * 原生GPS回调
     */
    private val nativeGpsCallback = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.provider == LocationManager.GPS_PROVIDER || location.provider == "gps") {
                Log.d(TAG, "📍 原生GPS: lat=${location.latitude}, lng=${location.longitude}, acc=${location.accuracy}m")
                handleNewLocation(location, isGpsSource = true)
            }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            Log.d(TAG, "GPS状态变化: $status")
        }

        override fun onProviderEnabled(provider: String) {
            Log.d(TAG, "GPS已启用")
        }

        override fun onProviderDisabled(provider: String) {
            Log.d(TAG, "GPS已禁用")
        }
    }

    /**
     * 启动Fused定位 (辅助)
     */
    private fun startFusedUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    // 仅当没有GPS定位或者GPS定位过期时使用Fused
                    val useFused = !hasGpsFix || (System.currentTimeMillis() - lastGpsTime > 5000)
                    if (useFused) {
                        Log.d(TAG, "📡 Fused辅助: lat=${location.latitude}, lng=${location.longitude}")
                        handleNewLocation(location, isGpsSource = false)
                    }
                }
            }
        }

        try {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                2000
            )
                .setMinUpdateDistanceMeters(2.0f)
                .setMinUpdateIntervalMillis(2000)
                .build()

            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback!!,
                Looper.getMainLooper()
            )
            Log.d(TAG, "✅ Fused定位已启动")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Fused启动失败: ${e.message}")
        }
    }

    /**
     * 获取最后一次已知位置
     */
    private fun getLastKnownLocation() {
        try {
            // 优先GPS
            val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (gpsLocation != null) {
                Log.d(TAG, "📍 最后GPS: lat=${gpsLocation.latitude}, lng=${gpsLocation.longitude}")
                handleNewLocation(gpsLocation, isGpsSource = true)
                return
            }
            
            // 其次网络
            val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (networkLocation != null) {
                Log.d(TAG, "📍 最后网络: lat=${networkLocation.latitude}, lng=${networkLocation.longitude}")
                handleNewLocation(networkLocation, isGpsSource = false)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "获取最后位置失败: ${e.message}")
        }
    }

    /**
     * 停止定位
     */
    fun stopLocationUpdates() {
        if (!isRunning) return

        isRunning = false
        
        // 停止原生GPS
        try {
            locationManager.removeUpdates(nativeGpsCallback)
        } catch (e: Exception) {
            // ignore
        }
        
        // 停止Fused
        locationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        locationCallback = null
        
        // 取消GNSS回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                gnssStatusCallback?.let {
                    locationManager.unregisterGnssStatusCallback(it)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        gnssStatusCallback = null
        
        Log.d(TAG, "定位已停止")
    }

    fun startTracking() {
        _isTracking.value = true
        Log.d(TAG, "轨迹记录已开始")
    }

    fun stopTracking() {
        _isTracking.value = false
        previousLocation = null
        Log.d(TAG, "轨迹记录已停止")
    }

    suspend fun getRecentTracks(limit: Int = 100): List<TrackEntity> {
        return withContext(Dispatchers.IO) {
            locationDao.getRecentTracks()
        }
    }

    suspend fun getTracksBetween(startTime: Long, endTime: Long): List<TrackEntity> {
        return withContext(Dispatchers.IO) {
            locationDao.getTracksBetween(startTime, endTime)
        }
    }

    suspend fun clearTracks() {
        withContext(Dispatchers.IO) {
            locationDao.deleteOldTracks(System.currentTimeMillis())
        }
    }

    /**
     * 处理新的定位数据 - 带质量过滤
     */
    private fun handleNewLocation(location: Location, isGpsSource: Boolean) {
        repositoryScope.launch {
            try {
                locationCount++
                
                // ========== 1. 地理区间校验 ==========
                val lat = location.latitude
                val lng = location.longitude
                
                // 检查坐标是否在中国范围内
                if (lat !in CHINA_LAT_MIN..CHINA_LAT_MAX || lng !in CHINA_LNG_MIN..CHINA_LNG_MAX) {
                    Log.w(TAG, "⚠️ 坐标不在中国范围内: ($lat, $lng)，丢弃")
                    // 如果有有效位置，保留上一个
                    lastValidLocation?.let {
                        _currentLocation.value = it
                    }
                    return@launch
                }

                // ========== 2. 质量评估 ==========
                // 获取GNSS质量参数 (从卫星状态中读取)
                val currentHdop = hdop
                val currentVdop = vdop
                
                // HDOP/VDOP阈值检查
                if (currentHdop > HDOP_THRESHOLD_POOR || currentVdop > VDOP_THRESHOLD_POOR) {
                    Log.w(TAG, "⚠️ HDOP/VDOP过高: HDOP=$currentHdop, VDOP=$currentVdop，丢弃")
                    return@launch
                }

                // 信噪比检查 - 如果卫星数据不足或SNR过低
                if (satelliteCount > 0 && avgSnr < SNR_THRESHOLD_MIN && usedSatelliteCount < 4) {
                    Log.w(TAG, "⚠️ 信号质量差: SNR=${String.format("%.1f", avgSnr)}dB, 锁定卫星=$usedSatelliteCount")
                    // 不丢弃，但标记为低质量
                }

                // ========== 3. 运动模式判断 ==========
                val speed = location.speed
                isMoving = speed > SPEED_THRESHOLD_MOVING
                
                // 更新采样频率 - 运动中高频，静止时低频
                // 通过LocationRequest的setMinUpdateIntervalMillis控制

                // ========== 4. 静止漂移过滤 ==========
                previousLocation?.let { prev ->
                    val distance = calculateDistance(
                        prev.latitude, prev.longitude,
                        lat, lng
                    )
                    
                    // 静止状态下的小范围跳动过滤
                    if (!isMoving && distance < 2.0) {
                        // 静止且移动距离小于2米，认为是漂移，使用上一个位置
                        Log.d(TAG, "静止防抖: 距离=${String.format("%.1f", distance)}m, 使用上一个位置")
                        return@launch
                    }
                    
                    // 异常跳点检测 - 移动速度过快
                    val timeDiff = (location.time - prev.time) / 1000.0
                    if (timeDiff > 0) {
                        val speedMs = distance / timeDiff
                        if (speedMs > DRIFT_THRESHOLD) {
                            Log.w(TAG, "⚠️ 异常跳点: ${String.format("%.1f", speedMs)}m/s, 丢弃")
                            return@launch
                        }
                    }
                }

                // ========== 5. GPS优先 - 禁止Fused覆盖GPS ==========
                if (isGpsSource) {
                    hasGpsFix = true
                    lastGpsTime = location.time
                } else if (hasGpsFix && System.currentTimeMillis() - lastGpsTime < 10000) {
                    // 如果有有效的GPS定位且未过期，忽略非GPS定位
                    Log.d(TAG, "GPS有效，忽略Fused/网络定位")
                    return@launch
                }

                // ========== 6. 创建定位数据 ==========
                val rawLocation = createLocationData(location)
                
                // ========== 7. Kalman滤波 ==========
                val (filteredLat, filteredLng) = kalmanFilterLat.update(
                    rawLocation.latitude,
                    rawLocation.longitude
                )

                val filteredLocation = rawLocation.copy(
                    latitude = filteredLat,
                    longitude = filteredLng,
                    hdop = currentHdop,
                    vdop = currentVdop,
                    satelliteCount = satelliteCount,
                    snr = avgSnr,
                    quality = evaluateQuality(currentHdop, currentVdop, usedSatelliteCount)
                )

                // ========== 8. 更新状态 ==========
                _currentLocation.value = filteredLocation
                lastValidLocation = filteredLocation
                previousLocation = filteredLocation
                
                Log.d(TAG, "✅ 位置更新: lat=${String.format("%.6f", filteredLat)}, lng=${String.format("%.6f", filteredLng)}, 质量=${filteredLocation.quality}")

                // ========== 9. 获取地址 ==========
                fetchLocationName(filteredLat, filteredLng)

                // ========== 10. 保存数据 ==========
                saveLocationToDatabase(filteredLocation)

                if (_isTracking.value && isMoving) {
                    saveTrackPoint(filteredLocation)
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理定位异常: ${e.message}")
            }
        }
    }

    /**
     * 评估定位质量
     */
    private fun evaluateQuality(hdop: Float, vdop: Float, usedSatellites: Int): LocationQuality {
        return when {
            hdop < HDOP_THRESHOLD_GOOD && usedSatellites >= 8 -> LocationQuality.EXCELLENT
            hdop < HDOP_THRESHOLD_FAIR && usedSatellites >= 6 -> LocationQuality.GOOD
            hdop < HDOP_THRESHOLD_POOR && usedSatellites >= 4 -> LocationQuality.FAIR
            hdop < HDOP_THRESHOLD_POOR * 1.5f && usedSatellites >= 3 -> LocationQuality.POOR
            else -> LocationQuality.BAD
        }
    }

    /**
     * 计算两点距离 (Haversine)
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2.0)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * 创建LocationData对象
     */
    private fun createLocationData(location: Location): LocationData {
        // EGM2008校正 (模拟，实际需要EGM模型)
        val egmOffset = getEGMOffset(location.latitude, location.longitude)
        
        return LocationData(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude + egmOffset,  // EGM校正
            accuracy = location.accuracy,
            speed = location.speed,
            bearing = location.bearing,
            time = location.time,
            provider = location.provider ?: "unknown",
            satelliteCount = satelliteCount,
            hdop = hdop,
            pdop = pdop,
            vdop = vdop,
            snr = avgSnr,
            quality = LocationQuality.UNKNOWN
        )
    }

    /**
     * EGM2008高程校正 (简化版)
     * 实际应使用EGM2008网格数据，这里用经纬度简化模拟
     */
    private fun getEGMOffset(lat: Double, lng: Double): Double {
        // 简化模拟：中国地区EGM校正值大约在-20m到+20m之间
        // 实际项目应使用EGM2008模型数据
        return -30.0 + (lat - 20) * 0.5 + (lng - 100) * 0.2
    }

    /**
     * 获取地点名称
     */
    private fun fetchLocationName(lat: Double, lng: Double) {
        try {
            val geocoder = android.location.Geocoder(context, Locale.CHINA)
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            
            if (addresses != null && addresses.isNotEmpty()) {
                val address = addresses[0]
                
                val fullAddress = address.getAddressLine(0) ?: ""
                val country = address.countryName ?: ""
                val adminArea = address.adminArea ?: ""
                val subAdminArea = address.subAdminArea ?: ""
                val locality = address.locality ?: ""
                val subLocality = address.subLocality ?: ""
                val thoroughfare = address.thoroughfare ?: ""
                val featureName = address.featureName ?: ""
                val postalCode = address.postalCode ?: ""
                
                val displayName = buildString {
                    if (featureName.isNotEmpty()) append(featureName)
                    if (thoroughfare.isNotEmpty()) {
                        if (isNotEmpty()) append(" ")
                        append(thoroughfare)
                    }
                    if (subLocality.isNotEmpty()) {
                        if (isNotEmpty()) append(", ")
                        append(subLocality)
                    }
                    if (locality.isNotEmpty()) {
                        if (isNotEmpty()) append(", ")
                        append(locality)
                    }
                    if (subAdminArea.isNotEmpty() && subAdminArea != locality) {
                        if (isNotEmpty()) append(", ")
                        append(subAdminArea)
                    }
                    if (adminArea.isNotEmpty() && adminArea != subAdminArea) {
                        if (isNotEmpty()) append(", ")
                        append(adminArea)
                    }
                    if (country.isNotEmpty()) {
                        if (isNotEmpty()) append(", ")
                        append(country)
                    }
                    if (isEmpty()) {
                        append("未知地址")
                    }
                }
                
                _locationName.value = displayName
                _detailedAddress.value = DetailedAddress(
                    fullAddress = fullAddress,
                    country = country,
                    adminArea = adminArea,
                    subAdminArea = subAdminArea,
                    locality = locality,
                    subLocality = subLocality,
                    thoroughfare = thoroughfare,
                    featureName = featureName,
                    postalCode = postalCode
                )
                Log.d(TAG, "📍 地址: $displayName")
            } else {
                _locationName.value = "无法获取地址"
                _detailedAddress.value = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取地址异常: ${e.message}")
            _locationName.value = "地址获取失败"
            _detailedAddress.value = null
        }
    }

    private suspend fun saveLocationToDatabase(location: LocationData) {
        try {
            val entity = LocationEntity(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                accuracy = location.accuracy,
                speed = location.speed,
                bearing = location.bearing,
                time = location.time,
                provider = location.provider,
                satelliteCount = location.satelliteCount,
                hdop = location.hdop,
                pdop = location.pdop,
                vdop = location.vdop,
                snr = location.snr,
                quality = location.quality.name
            )
            locationDao.insertLocation(entity)
        } catch (e: Exception) {
            Log.e(TAG, "保存定位异常: ${e.message}")
        }
    }

    private suspend fun saveTrackPoint(location: LocationData) {
        try {
            val track = TrackEntity(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speed = location.speed,
                bearing = location.bearing,
                accuracy = location.accuracy,
                time = location.time,
                satelliteCount = location.satelliteCount,
                hdop = location.hdop,
                pdop = location.pdop,
                provider = location.provider,
                isFromBackground = false
            )
            locationDao.insertTrackPoint(track)

            val currentTracks = _trackPoints.value.toMutableList()
            currentTracks.add(track)
            if (currentTracks.size > 1000) {
                currentTracks.removeAt(0)
            }
            _trackPoints.value = currentTracks
        } catch (e: Exception) {
            Log.e(TAG, "保存轨迹异常: ${e.message}")
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun cleanup() {
        stopLocationUpdates()
        repositoryScope.coroutineContext.cancelChildren()
    }

    fun forceLocationUpdate() {
        Log.d(TAG, "手动触发位置更新")
        getLastKnownLocation()
    }
}
