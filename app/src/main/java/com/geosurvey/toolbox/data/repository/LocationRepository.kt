package com.geosurvey.toolbox.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 纯GPS定位仓库 - 直接使用Android GPS API
 * 不依赖Google Fused Location
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
        
        // 有效GPS判定标准
        private const val MIN_SATELLITES_FOR_FIX = 4
        private const val MIN_SNR_FOR_VALID = 20.0f
        private const val MAX_ACCURACY_FOR_VALID = 50.0f
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

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

    // GNSS质量参数 - 从卫星状态读取
    private var hdop = 0.0f
    private var vdop = 0.0f
    private var pdop = 0.0f
    private var satelliteCount = 0
    private var usedSatelliteCount = 0
    private var avgSnr = 0.0f
    private var maxSnr = 0.0f
    
    // 分系统统计
    private var gpsCount = 0
    private var glonassCount = 0
    private var galileoCount = 0
    private var beidouCount = 0

    // 位置缓存
    private var lastValidLocation: LocationData? = null
    private var previousLocation: LocationData? = null
    private var isMoving = false
    
    // 状态
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isRunning = false
    private var hasGpsFix = false
    private var firstValidFix = false
    private var gpsLockAttempts = 0
    
    // GPS监听器
    private var gpsListener: LocationListener? = null
    private var gnssCallback: GnssStatusCallback? = null

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
     * GNSS状态回调
     */
    private inner class GnssStatusCallback : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            analyzeGnssStatus(status)
        }

        override fun onFirstFix(ttffMillis: Int) {
            Log.d(TAG, "🎯🎯🎯 GPS首次定位成功! 耗时: ${ttffMillis}ms")
            hasGpsFix = true
            firstValidFix = true
        }

        override fun onStarted() {
            Log.d(TAG, "📡 GNSS定位引擎启动")
        }

        override fun onStopped() {
            Log.d(TAG, "📡 GNSS定位引擎停止")
        }
    }

    /**
     * 分析GNSS卫星状态 - 从原始数据读取HDOP/VDOP
     */
    private fun analyzeGnssStatus(status: android.location.GnssStatus) {
        val satelliteList = mutableListOf<SatelliteInfo>()
        
        gpsCount = 0
        glonassCount = 0
        galileoCount = 0
        beidouCount = 0
        
        var totalSnr = 0.0f
        var validSnrCount = 0
        var usedCount = 0
        var highSnrCount = 0
        var totalElevation = 0.0f
        var validElevationCount = 0
        
        for (i in 0 until status.satelliteCount) {
            val prn = status.getSvid(i)
            val snr = status.getCn0DbHz(i)
            val azimuth = status.getAzimuthDegrees(i)
            val elevation = status.getElevationDegrees(i)
            val usedInFix = status.usedInFix(i)
            
            if (usedInFix) {
                usedCount++
                totalElevation += elevation
                validElevationCount++
            }
            if (snr > 0) {
                totalSnr += snr
                validSnrCount++
                if (snr > MIN_SNR_FOR_VALID) highSnrCount++
            }
            
            val constellation = when (status.getConstellationType(i)) {
                GnssStatus.CONSTELLATION_GPS -> { gpsCount++; Constellation.GPS }
                GnssStatus.CONSTELLATION_GLONASS -> { glonassCount++; Constellation.GLONASS }
                GnssStatus.CONSTELLATION_GALILEO -> { galileoCount++; Constellation.GALILEO }
                GnssStatus.CONSTELLATION_BEIDOU -> { beidouCount++; Constellation.BEIDOU }
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
        
        // 估算HDOP/VDOP - 基于卫星分布
        // 真正的HDOP需要从GnssStatus中读取，但Android API没有直接提供
        // 这里使用卫星数量和仰角分布来估算
        if (usedCount >= 4 && validElevationCount > 0) {
            val avgElev = totalElevation / validElevationCount
            // 粗略估算: 卫星越多、仰角越高，HDOP越小
            hdop = (10.0f / usedCount) * (1.0f + (45.0f - avgElev) / 90.0f)
            hdop = hdop.coerceIn(0.5f, 20.0f)
            vdop = hdop * 0.8f
            pdop = sqrt((hdop * hdop + vdop * vdop).toDouble()).toFloat()
        } else {
            hdop = 0f
            vdop = 0f
            pdop = 0f
        }
        
        _satellites.value = satelliteList
        
        Log.d(TAG, "🛰️ 卫星状态: 总数=$satelliteCount, 锁定=$usedCount")
        Log.d(TAG, "   GPS=$gpsCount, GLONASS=$glonassCount, Galileo=$galileoCount, 北斗=$beidouCount")
        Log.d(TAG, "   SNR平均=${String.format("%.1f", avgSnr)}dB, 高信噪比=$highSnrCount")
        Log.d(TAG, "   HDOP=${String.format("%.1f", hdop)}, VDOP=${String.format("%.1f", vdop)}")
    }

    /**
     * 开始定位 - 纯GPS模式
     */
    fun startLocationUpdates() {
        Log.d(TAG, "========================================")
        Log.d(TAG, "🚀 启动纯GPS定位引擎")
        Log.d(TAG, "========================================")
        
        if (isRunning) {
            Log.d(TAG, "定位已在运行")
            return
        }
        
        if (!hasLocationPermission()) {
            Log.e(TAG, "❌ 没有定位权限")
            return
        }

        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        Log.d(TAG, "📡 GPS硬件状态: ${if (isGpsEnabled) "✅ 已开启" else "❌ 未开启"}")
        
        if (!isGpsEnabled) {
            Log.e(TAG, "❌❌❌ GPS未开启! 请在手机设置中打开位置服务")
            return
        }

        isRunning = true
        hasGpsFix = false
        firstValidFix = false
        gpsLockAttempts = 0
        lastValidLocation = null
        previousLocation = null

        // 1. 注册GNSS状态回调
        registerGnssCallback()

        // 2. 启动GPS监听 - 使用最短间隔
        startGpsListener()

        // 3. 尝试获取最后已知位置
        getLastKnownLocation()
        
        Log.d(TAG, "✅ GPS定位已启动，等待卫星锁定...")
        Log.d(TAG, "⏱️ 室外首次锁定通常需要30-60秒")
    }

    /**
     * 注册GNSS回调
     */
    private fun registerGnssCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                gnssCallback = GnssStatusCallback()
                locationManager.registerGnssStatusCallback(gnssCallback!!, null)
                Log.d(TAG, "✅ GNSS回调已注册")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 注册GNSS回调失败: ${e.message}")
            }
        }
    }

    /**
     * 启动GPS监听器
     */
    private fun startGpsListener() {
        gpsListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // 验证这是真实的GPS定位
                if (location.provider == LocationManager.GPS_PROVIDER) {
                    Log.d(TAG, "📍📍📍 GPS定位: lat=${location.latitude}, lng=${location.longitude}, acc=${location.accuracy}m")
                    
                    // 检查是否为有效定位
                    if (isValidGpsLocation(location)) {
                        if (!firstValidFix) {
                            firstValidFix = true
                            Log.d(TAG, "🎉🎉🎉 首次有效GPS定位!")
                        }
                        handleNewLocation(location)
                    } else {
                        Log.d(TAG, "⚠️ GPS定位无效: 精度=${location.accuracy}m, 卫星数=$usedSatelliteCount")
                    }
                }
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                Log.d(TAG, "GPS状态: $status")
            }

            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "GPS已启用")
            }

            override fun onProviderDisabled(provider: String) {
                Log.d(TAG, "GPS已禁用")
            }
        }

        try {
            // 使用最短更新间隔强制GPS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500,
                    0f,
                    gpsListener!!,
                    Looper.getMainLooper()
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500,
                    0f,
                    gpsListener!!
                )
            }
            Log.d(TAG, "✅ GPS监听已启动 (间隔500ms)")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ GPS监听启动失败: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ GPS监听异常: ${e.message}")
        }
    }

    /**
     * 验证GPS定位是否有效
     */
    private fun isValidGpsLocation(location: Location): Boolean {
        // 1. 精度检查
        if (location.accuracy > MAX_ACCURACY_FOR_VALID) {
            Log.d(TAG, "精度过低: ${location.accuracy}m > ${MAX_ACCURACY_FOR_VALID}m")
            return false
        }
        
        // 2. 卫星数检查
        if (usedSatelliteCount < MIN_SATELLITES_FOR_FIX) {
            Log.d(TAG, "卫星数不足: $usedSatelliteCount < $MIN_SATELLITES_FOR_FIX")
            return false
        }
        
        // 3. 信噪比检查
        if (avgSnr < MIN_SNR_FOR_VALID && usedSatelliteCount < 6) {
            Log.d(TAG, "信噪比过低: ${String.format("%.1f", avgSnr)}dB < ${MIN_SNR_FOR_VALID}dB")
            return false
        }
        
        // 4. 坐标合理性检查 - 在中国范围内
        val lat = location.latitude
        val lng = location.longitude
        if (lat in CHINA_LAT_MIN..CHINA_LAT_MAX && lng in CHINA_LNG_MIN..CHINA_LNG_MAX) {
            return true
        }
        
        Log.d(TAG, "坐标不在中国范围内: ($lat, $lng)")
        return false
    }

    /**
     * 获取最后一次已知位置
     */
    private fun getLastKnownLocation() {
        try {
            val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (gpsLocation != null && isValidGpsLocation(gpsLocation)) {
                Log.d(TAG, "📍 最后有效GPS: lat=${gpsLocation.latitude}, lng=${gpsLocation.longitude}")
                handleNewLocation(gpsLocation)
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
        
        gpsListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) { }
        }
        gpsListener = null
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                gnssCallback?.let {
                    locationManager.unregisterGnssStatusCallback(it)
                }
            } catch (e: Exception) { }
        }
        gnssCallback = null
        
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
     * 处理新的GPS定位
     */
    private fun handleNewLocation(location: Location) {
        repositoryScope.launch {
            try {
                val lat = location.latitude
                val lng = location.longitude
                
                Log.d(TAG, "处理GPS: lat=$lat, lng=$lng, acc=${location.accuracy}m")

                // 漂移检测
                previousLocation?.let { prev ->
                    val distance = calculateDistance(
                        prev.latitude, prev.longitude,
                        lat, lng
                    )
                    val timeDiff = (location.time - prev.time) / 1000.0
                    if (timeDiff > 0) {
                        val speedMs = distance / timeDiff
                        if (speedMs > 100.0) {
                            Log.w(TAG, "⚠️ 异常跳点: ${String.format("%.1f", speedMs)}m/s, 丢弃")
                            return@launch
                        }
                    }
                }

                // 判断运动状态
                isMoving = location.speed > 0.5f

                // Kalman滤波
                val (filteredLat, filteredLng) = kalmanFilterLat.update(lat, lng)

                // 创建定位数据
                val egmOffset = getEGMOffset(filteredLat, filteredLng)
                val quality = evaluateQuality()
                
                val locationData = LocationData(
                    latitude = filteredLat,
                    longitude = filteredLng,
                    altitude = location.altitude + egmOffset,
                    accuracy = location.accuracy,
                    speed = location.speed,
                    bearing = location.bearing,
                    time = location.time,
                    provider = "gps",
                    satelliteCount = satelliteCount,
                    hdop = hdop,
                    pdop = pdop,
                    vdop = vdop,
                    snr = avgSnr,
                    quality = quality
                )

                // 更新状态
                _currentLocation.value = locationData
                lastValidLocation = locationData
                previousLocation = locationData
                
                Log.d(TAG, "✅ 位置已更新: lat=${String.format("%.6f", filteredLat)}, lng=${String.format("%.6f", filteredLng)}")
                Log.d(TAG, "   质量: $quality, HDOP=${String.format("%.1f", hdop)}, 卫星=$usedSatelliteCount")

                // 获取地址
                fetchLocationName(filteredLat, filteredLng)
                
                // 保存数据
                saveLocationToDatabase(locationData)

                if (_isTracking.value && isMoving) {
                    saveTrackPoint(locationData)
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理GPS异常: ${e.message}")
            }
        }
    }

    /**
     * 评估定位质量
     */
    private fun evaluateQuality(): LocationQuality {
        return when {
            hdop < 1.0f && usedSatelliteCount >= 8 && avgSnr > 30 -> LocationQuality.EXCELLENT
            hdop < 2.0f && usedSatelliteCount >= 6 && avgSnr > 25 -> LocationQuality.GOOD
            hdop < 4.0f && usedSatelliteCount >= 4 && avgSnr > 20 -> LocationQuality.FAIR
            usedSatelliteCount >= 3 && avgSnr > 15 -> LocationQuality.POOR
            else -> LocationQuality.BAD
        }
    }

    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2.0)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun getEGMOffset(lat: Double, lng: Double): Double {
        return -30.0 + (lat - 20) * 0.5 + (lng - 100) * 0.2
    }

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
                    postalCode = address.postalCode ?: ""
                )
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
