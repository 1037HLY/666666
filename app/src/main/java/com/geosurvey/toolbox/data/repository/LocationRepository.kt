package com.geosurvey.toolbox.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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

/**
 * 定位数据仓库
 */
class LocationRepository(
    private val context: Context,
    private val locationDao: LocationDao
) {
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

    /**
     * 开始定位
     */
    fun startLocationUpdates() {
        if (isRunning) return
        if (!hasLocationPermission()) return

        isRunning = true

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    handleNewLocation(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                createLocationRequest(),
                locationCallback!!,
                null
            )
        } catch (e: SecurityException) {
            isRunning = false
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
            } catch (e: Exception) {
                // ignore
            }
        }
        locationCallback = null
    }

    /**
     * 开始轨迹记录
     */
    fun startTracking() {
        _isTracking.value = true
    }

    /**
     * 停止轨迹记录
     */
    fun stopTracking() {
        _isTracking.value = false
        kalmanFilter.reset()
        previousLocation = null
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
            val rawLocation = createLocationData(location)
            val quality = LocationQualityEvaluator.evaluate(rawLocation)

            if (quality == LocationQuality.BAD || quality == LocationQuality.UNKNOWN) {
                return@launch
            }

            previousLocation?.let { prev ->
                if (LocationQualityEvaluator.isDriftDetected(prev, rawLocation)) {
                    return@launch
                }
            }

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

            val (filteredLat, filteredLng) = kalmanFilter.update(
                rawLocation.latitude,
                rawLocation.longitude
            )

            val filteredLocation = rawLocation.copy(
                latitude = filteredLat,
                longitude = filteredLng
            )

            _currentLocation.value = filteredLocation
            saveLocationToDatabase(filteredLocation)

            if (_isTracking.value && !isStationary) {
                saveTrackPoint(filteredLocation)
            }

            previousLocation = filteredLocation
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
    }

    /**
     * 保存轨迹点
     */
    private suspend fun saveTrackPoint(location: LocationData) {
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
    }

    /**
     * 创建位置请求
     */
    private fun createLocationRequest(): com.google.android.gms.location.LocationRequest {
        return com.google.android.gms.location.LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000
        )
            .setMinUpdateDistanceMeters(1.0f)
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
}
