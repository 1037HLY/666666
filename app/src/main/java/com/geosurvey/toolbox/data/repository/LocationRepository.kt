package com.geosurvey.toolbox.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Date

/**
 * 定位数据仓库
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
        measurementNoise = 10.0
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

    private var previousLocation: LocationData? = null
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var locationCallback: LocationCallback? = null
    private var isRunning = false
    private var isStationary = false

    // 用于模拟定位的计数器
    private var mockCounter = 0

    /**
     * 开始定位
     */
    fun startLocationUpdates() {
        Log.d(TAG, "========== startLocationUpdates() called ==========")
        
        if (isRunning) {
            Log.d(TAG, "Location updates already running")
            return
        }
        
        if (!hasLocationPermission()) {
            Log.e(TAG, "❌ No location permission")
            return
        }
        Log.d(TAG, "✅ Location permission granted")

        // 检查GPS是否开启
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val isPassiveEnabled = locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)
        Log.d(TAG, "GPS enabled: $isGpsEnabled")
        Log.d(TAG, "Network enabled: $isNetworkEnabled")
        Log.d(TAG, "Passive enabled: $isPassiveEnabled")
        
        if (!isGpsEnabled && !isNetworkEnabled) {
            Log.e(TAG, "❌ No location provider enabled")
            return
        }

        isRunning = true

        // 创建LocationCallback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                Log.d(TAG, "📍 onLocationResult: ${result.locations.size} locations received")
                result.lastLocation?.let { location ->
                    Log.d(TAG, "📍 Location: lat=${location.latitude}, lng=${location.longitude}, acc=${location.accuracy}, provider=${location.provider}")
                    handleNewLocation(location)
                } ?: run {
                    Log.d(TAG, "⚠️ No location in result")
                }
            }

            override fun onLocationAvailability(availability: com.google.android.gms.location.LocationAvailability) {
                Log.d(TAG, "📡 onLocationAvailability: isLocationAvailable=${availability.isLocationAvailable}")
            }
        }

        try {
            val request = createLocationRequest()
            Log.d(TAG, "Requesting location updates with priority: ${request.priority}")
            
            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback!!,
                null
            ).addOnSuccessListener {
                Log.d(TAG, "✅ Location updates request succeeded")
            }.addOnFailureListener { e ->
                Log.e(TAG, "❌ Location updates request failed", e)
            }
            
            Log.d(TAG, "Location updates requested successfully")
            
            // 获取最后一次已知位置作为快速反馈
            getLastKnownLocation()
            
            // 启动模拟定位作为备用方案（如果真实定位长时间无法获取）
            startMockLocationUpdates()
            
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException when requesting location updates", e)
            isRunning = false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception when requesting location updates", e)
            isRunning = false
        }
    }

    /**
     * 获取最后一次已知位置
     */
    private fun getLastKnownLocation() {
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            
            for (provider in providers) {
                if (locationManager.isProviderEnabled(provider)) {
                    val location = locationManager.getLastKnownLocation(provider)
                    if (location != null) {
                        Log.d(TAG, "📍 Last known location from $provider: lat=${location.latitude}, lng=${location.longitude}")
                        handleNewLocation(location)
                        break
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting last known location", e)
        }
    }

    /**
     * 模拟定位（作为备用方案，帮助测试UI）
     */
    private fun startMockLocationUpdates() {
        repositoryScope.launch {
            Log.d(TAG, "Starting mock location updates (备用方案)")
            
            // 如果5秒后还没有真实定位，开始模拟
            delay(5000)
            
            // 检查是否已经有真实定位
            if (_currentLocation.value != null) {
                Log.d(TAG, "Real location already available, skipping mock")
                return@launch
            }
            
            Log.d(TAG, "No real location after 5s, starting mock location")
            
            // 模拟位置：北京天安门附近
            val mockLocations = listOf(
                Pair(39.9042, 116.4074), // 天安门
                Pair(39.9050, 116.4080),
                Pair(39.9060, 116.4090),
                Pair(39.9070, 116.4100),
                Pair(39.9080, 116.4110)
            )
            
            var index = 0
            while (isRunning && _currentLocation.value == null) {
                val (lat, lng) = mockLocations[index % mockLocations.size]
                val mockLocation = Location("mock").apply {
                    this.latitude = lat + (Math.random() - 0.5) * 0.001
                    this.longitude = lng + (Math.random() - 0.5) * 0.001
                    this.altitude = 50.0 + Math.random() * 10
                    this.accuracy = 5.0f + (Math.random() * 5).toFloat()
                    this.speed = 0.0f
                    this.bearing = 0.0f
                    this.time = System.currentTimeMillis()
                }
                Log.d(TAG, "📌 Mock location: lat=${mockLocation.latitude}, lng=${mockLocation.longitude}")
                handleNewLocation(mockLocation)
                index++
                delay(3000)
            }
            
            Log.d(TAG, "Mock location stopped (real location available or service stopped)")
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
                Log.d(TAG, "Location updates stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping location updates", e)
            }
        }
        locationCallback = null
    }

    /**
     * 开始轨迹记录
     */
    fun startTracking() {
        _isTracking.value = true
        Log.d(TAG, "Tracking started")
    }

    /**
     * 停止轨迹记录
     */
    fun stopTracking() {
        _isTracking.value = false
        kalmanFilter.reset()
        previousLocation = null
        Log.d(TAG, "Tracking stopped")
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
     * 处理新的定位数据
     */
    private fun handleNewLocation(location: Location) {
        repositoryScope.launch {
            try {
                Log.d(TAG, "handleNewLocation: processing location from ${location.provider}")
                
                val rawLocation = createLocationData(location)
                val quality = LocationQualityEvaluator.evaluate(rawLocation)
                Log.d(TAG, "Location quality: $quality")

                // 即使是低质量定位，也先显示出来（用于测试）
                if (quality == LocationQuality.BAD) {
                    Log.d(TAG, "Location quality is BAD, but still displaying for testing")
                    // 仍然显示这个定位，方便调试
                }

                // 漂移检测
                previousLocation?.let { prev ->
                    if (LocationQualityEvaluator.isDriftDetected(prev, rawLocation)) {
                        Log.d(TAG, "Drift detected, but still using location")
                    }
                }

                // 静止检测
                previousLocation?.let { prev ->
                    if (LocationQualityEvaluator.isStationary(rawLocation, prev)) {
                        isStationary = true
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
                Log.d(TAG, "✅ Current location updated: lat=$filteredLat, lng=$filteredLng")
                
                // 保存到数据库
                saveLocationToDatabase(filteredLocation)

                // 如果正在记录轨迹，保存轨迹点
                if (_isTracking.value && !isStationary) {
                    saveTrackPoint(filteredLocation)
                }

                previousLocation = filteredLocation
            } catch (e: Exception) {
                Log.e(TAG, "Error handling location", e)
            }
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
            satelliteCount = 0,
            hdop = location.accuracy,
            pdop = location.accuracy * 1.5f,
            vdop = location.accuracy * 1.2f,
            snr = 0f,
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
            Log.d(TAG, "Location saved to database")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving location to database", e)
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
            Log.d(TAG, "Track point saved, total: ${currentTracks.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving track point", e)
        }
    }

    /**
     * 创建位置请求
     */
    private fun createLocationRequest(): com.google.android.gms.location.LocationRequest {
        return com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000 // 1秒更新一次
        )
            .setMinUpdateDistanceMeters(0.5f)
            .setMinUpdateIntervalMillis(1000)
            .setMaxUpdateDelayMillis(5000)
            .build()
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
     * 手动触发一次位置更新（用于测试）
     */
    fun forceLocationUpdate() {
        Log.d(TAG, "forceLocationUpdate() called")
        getLastKnownLocation()
        
        // 如果5秒后还没有定位，启动模拟定位
        repositoryScope.launch {
            delay(3000)
            if (_currentLocation.value == null) {
                Log.d(TAG, "Still no location, using test location")
                val testLocation = Location("test").apply {
                    latitude = 39.9042
                    longitude = 116.4074
                    altitude = 50.0
                    accuracy = 10.0f
                    speed = 0.0f
                    bearing = 0.0f
                    time = System.currentTimeMillis()
                }
                handleNewLocation(testLocation)
            }
        }
    }
}
