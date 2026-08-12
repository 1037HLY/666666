package com.geosurvey.toolbox.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
import com.geosurvey.toolbox.domain.util.LocationQualityEvaluator
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
 * 定位数据仓库 - 纯真实GPS版本（无模拟数据）
 */
class LocationRepository(
    private val context: Context,
    private val locationDao: LocationDao
) {
    companion object {
        private const val TAG = "LocationRepository"
    }

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val kalmanFilter = KalmanFilter2D(
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

    // 地点名称
    private val _locationName = MutableStateFlow<String>("正在获取地址...")
    val locationName: StateFlow<String> = _locationName.asStateFlow()

    // 详细地址信息
    private val _detailedAddress = MutableStateFlow<DetailedAddress?>(null)
    val detailedAddress: StateFlow<DetailedAddress?> = _detailedAddress.asStateFlow()

    private var previousLocation: LocationData? = null
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationCallback: LocationCallback? = null
    private var isRunning = false
    private var isStationary = false
    private var locationCount = 0

    // GNSS回调（Android 7.0+）
    private var gnssStatusCallback: android.location.GnssStatus.Callback? = null

    /**
     * 详细地址数据类
     */
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
     * 开始定位 - 高精度模式
     */
    fun startLocationUpdates() {
        Log.d(TAG, "========== 开始高精度GPS定位 ==========")
        
        if (isRunning) {
            Log.d(TAG, "定位已在运行中")
            return
        }
        
        if (!hasLocationPermission()) {
            Log.e(TAG, "❌ 没有定位权限")
            return
        }
        Log.d(TAG, "✅ 定位权限已授予")

        // 检查GPS是否开启
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        Log.d(TAG, "GPS状态: $isGpsEnabled, 网络定位: $isNetworkEnabled")
        
        if (!isGpsEnabled && !isNetworkEnabled) {
            Log.e(TAG, "❌ 定位服务未开启")
            return
        }

        isRunning = true
        locationCount = 0

        // 创建高精度LocationCallback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    locationCount++
                    Log.d(TAG, "📍 第${locationCount}次定位: lat=${location.latitude}, lng=${location.longitude}, acc=${location.accuracy}m, provider=${location.provider}")
                    
                    // 只接受真实GPS或网络定位
                    val provider = location.provider ?: ""
                    if (provider == "gps" || provider == "network" || provider == "fused") {
                        handleNewLocation(location)
                    } else {
                        Log.d(TAG, "⚠️ 忽略非真实定位源: $provider")
                    }
                }
            }

            override fun onLocationAvailability(availability: com.google.android.gms.location.LocationAvailability) {
                Log.d(TAG, "📡 定位可用性: ${availability.isLocationAvailable}")
            }
        }

        try {
            val request = createHighAccuracyLocationRequest()
            Log.d(TAG, "请求高精度定位更新")
            
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback!!,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                Log.d(TAG, "✅ 定位请求成功")
            }.addOnFailureListener { e ->
                Log.e(TAG, "❌ 定位请求失败: ${e.message}")
            }
            
            // 获取最后一次已知位置
            getLastKnownLocation()
            
            // 启动卫星状态监听
            registerGnssStatusCallback()
            
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ 安全异常: ${e.message}")
            isRunning = false
        } catch (e: Exception) {
            Log.e(TAG, "❌ 异常: ${e.message}")
            isRunning = false
        }
    }

    /**
     * 创建高精度定位请求
     */
    private fun createHighAccuracyLocationRequest(): LocationRequest {
        return LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000
        )
            .setMinUpdateDistanceMeters(0.5f)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdateDelayMillis(2000)
            .build()
    }

    /**
     * 注册GNSS状态回调
     */
    private fun registerGnssStatusCallback() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                val callback = object : android.location.GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                        updateSatelliteInfo(status)
                    }
                    
                    override fun onFirstFix(ttffMillis: Int) {
                        Log.d(TAG, "✅ 首次GPS定位成功! 耗时: ${ttffMillis}ms")
                    }
                    
                    override fun onStarted() {
                        Log.d(TAG, "✅ GNSS定位开始")
                    }
                    
                    override fun onStopped() {
                        Log.d(TAG, "⏹ GNSS定位停止")
                    }
                }
                gnssStatusCallback = callback
                locationManager.registerGnssStatusCallback(callback, null)
                Log.d(TAG, "✅ GNSS状态监听已注册")
            } catch (e: SecurityException) {
                Log.e(TAG, "❌ 注册GNSS回调失败: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 注册GNSS回调异常: ${e.message}")
            }
        }
    }

    /**
     * 更新卫星信息
     */
    private fun updateSatelliteInfo(status: android.location.GnssStatus) {
        val satelliteList = mutableListOf<SatelliteInfo>()
        var usedInFixCount = 0
        
        for (i in 0 until status.satelliteCount) {
            val prn = status.getSvid(i)
            val snr = status.getCn0DbHz(i)
            val azimuth = status.getAzimuthDegrees(i)
            val elevation = status.getElevationDegrees(i)
            val usedInFix = status.usedInFix(i)
            
            if (usedInFix) usedInFixCount++

            val constellation = when (status.getConstellationType(i)) {
                android.location.GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
                android.location.GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
                android.location.GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
                android.location.GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
                android.location.GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
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
        _satellites.value = satelliteList
        Log.d(TAG, "🛰️ 卫星数: ${satelliteList.size}, 已锁定: $usedInFixCount")
    }

    /**
     * 获取最后一次已知位置
     */
    private fun getLastKnownLocation() {
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            )
            
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    val location = locationManager.getLastKnownLocation(provider)
                    if (location != null) {
                        Log.d(TAG, "📍 最后已知位置($provider): lat=${location.latitude}, lng=${location.longitude}")
                        handleNewLocation(location)
                        break
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "获取最后位置异常: ${e.message}")
        }
    }

    /**
     * 停止定位
     */
    fun stopLocationUpdates() {
        if (!isRunning) return

        isRunning = false
        locationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
                Log.d(TAG, "定位已停止")
            } catch (e: Exception) {
                Log.e(TAG, "停止定位异常: ${e.message}")
            }
        }
        locationCallback = null
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                gnssStatusCallback?.let { callback ->
                    locationManager.unregisterGnssStatusCallback(callback)
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        gnssStatusCallback = null
    }

    /**
     * 开始轨迹记录
     */
    fun startTracking() {
        _isTracking.value = true
        Log.d(TAG, "轨迹记录已开始")
    }

    /**
     * 停止轨迹记录
     */
    fun stopTracking() {
        _isTracking.value = false
        kalmanFilter.reset()
        previousLocation = null
        Log.d(TAG, "轨迹记录已停止")
    }

    /**
     * 获取最近的轨迹点
     */
    suspend fun getRecentTracks(limit: Int = 100): List<TrackEntity> {
        return withContext(Dispatchers.IO) {
            locationDao.getRecentTracks()
        }
    }

    /**
     * 获取指定时间范围的轨迹
     */
    suspend fun getTracksBetween(startTime: Long, endTime: Long): List<TrackEntity> {
        return withContext(Dispatchers.IO) {
            locationDao.getTracksBetween(startTime, endTime)
        }
    }

    /**
     * 清除轨迹
     */
    suspend fun clearTracks() {
        withContext(Dispatchers.IO) {
            locationDao.deleteOldTracks(System.currentTimeMillis())
        }
    }

    /**
     * 处理新的定位数据 - 只使用真实GPS
     */
    private fun handleNewLocation(location: Location) {
        repositoryScope.launch {
            try {
                // 验证坐标是否为有效值
                if (location.latitude == 0.0 && location.longitude == 0.0) {
                    Log.d(TAG, "⚠️ 无效坐标 (0,0)，跳过")
                    return@launch
                }
                
                // 检查坐标是否在合理范围内
                if (location.latitude > 90 || location.latitude < -90 ||
                    location.longitude > 180 || location.longitude < -180) {
                    Log.d(TAG, "⚠️ 坐标超出合理范围，跳过")
                    return@launch
                }
                
                // 检查精度是否合理（精度大于100米可能是定位不准）
                if (location.accuracy > 100) {
                    Log.d(TAG, "⚠️ 精度过低 (${location.accuracy}m)，等待更精确的定位")
                    // 仍然显示，但标记为低精度
                }
                
                Log.d(TAG, "处理定位数据: lat=${location.latitude}, lng=${location.longitude}")
                
                val rawLocation = createLocationData(location)
                val quality = LocationQualityEvaluator.evaluate(rawLocation)
                Log.d(TAG, "定位质量: $quality")

                // 漂移检测
                previousLocation?.let { prev ->
                    if (LocationQualityEvaluator.isDriftDetected(prev, rawLocation)) {
                        Log.d(TAG, "检测到漂移，跳过此点")
                        return@launch
                    }
                }

                // 静止检测
                previousLocation?.let { prev ->
                    if (LocationQualityEvaluator.isStationary(rawLocation, prev)) {
                        isStationary = true
                        if (_isTracking.value) {
                            previousLocation = rawLocation
                            return@launch
                        }
                    } else {
                        isStationary = false
                    }
                }

                // 应用Kalman滤波
                val (filteredLat, filteredLng) = kalmanFilter.update(
                    rawLocation.latitude,
                    rawLocation.longitude
                )

                val filteredLocation = rawLocation.copy(
                    latitude = filteredLat,
                    longitude = filteredLng
                )

                // 更新当前定位
                _currentLocation.value = filteredLocation
                Log.d(TAG, "✅ 当前位置已更新: lat=$filteredLat, lng=$filteredLng")
                
                // 获取地点名称
                fetchLocationName(filteredLat, filteredLng)
                
                // 保存到数据库
                saveLocationToDatabase(filteredLocation)

                // 如果正在记录轨迹，保存轨迹点
                if (_isTracking.value && !isStationary) {
                    saveTrackPoint(filteredLocation)
                }

                previousLocation = filteredLocation
            } catch (e: Exception) {
                Log.e(TAG, "处理定位数据异常: ${e.message}")
            }
        }
    }

    /**
     * 获取地点名称
     */
    private fun fetchLocationName(lat: Double, lng: Double) {
        try {
            val geocoder = android.location.Geocoder(context, Locale.getDefault())
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
                    if (featureName.isNotEmpty()) {
                        append(featureName)
                    }
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

    /**
     * 创建LocationData对象
     */
    private fun createLocationData(location: Location): LocationData {
        return LocationData(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            accuracy = location.accuracy,
            speed = location.speed,
            bearing = location.bearing,
            time = location.time,
            provider = location.provider ?: "unknown",
            satelliteCount = _satellites.value.size,
            hdop = location.accuracy,
            pdop = location.accuracy * 1.5f,
            vdop = location.accuracy * 1.2f,
            snr = _satellites.value.maxOfOrNull { it.snr } ?: 0f,
            quality = LocationQuality.UNKNOWN
        )
    }

    /**
     * 保存定位数据到数据库
     */
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
            Log.e(TAG, "保存定位数据异常: ${e.message}")
        }
    }

    /**
     * 保存轨迹点
     */
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
            Log.e(TAG, "保存轨迹点异常: ${e.message}")
        }
    }

    /**
     * 检查定位权限
     */
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 清理资源
     */
    fun cleanup() {
        stopLocationUpdates()
        repositoryScope.coroutineContext.cancelChildren()
    }

    /**
     * 手动触发位置更新
     */
    fun forceLocationUpdate() {
        Log.d(TAG, "手动触发位置更新")
        getLastKnownLocation()
    }
}
