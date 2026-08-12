package com.geosurvey.toolbox.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 定位数据仓库
 * 负责管理所有定位相关的数据源和操作
 */
class LocationRepository(
    private val context: Context,
    private val locationDao: LocationDao
) {
    // FusedLocationProviderClient
    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    // LocationManager（用于GNSS信息）
    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    // Kalman滤波器
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

    // 上一次定位数据（用于漂移检测）
    private var previousLocation: LocationData? = null

    // 协程作用域
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // LocationCallback
    private var locationCallback: LocationCallback? = null

    // 是否正在运行
    private var isRunning = false

    // 静止状态
    private var isStationary = false

    /**
     * 开始定位
     */
    fun startLocationUpdates() {
        if (isRunning) return

        // 检查权限
        if (!hasLocationPermission()) {
            return
        }

        isRunning = true

        // 创建LocationCallback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    handleNewLocation(location)
                }
            }
        }

        // 请求位置更新
        try {
            fusedLocationClient.requestLocationUpdates(
                createLocationRequest(),
                locationCallback!!,
                null
            )

            // 同时监听GNSS状态
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    locationManager.registerGnssStatusCallback(gnssStatusCallback, null)
                } catch (e: SecurityException) {
                    // 忽略权限异常
                }
            }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            } catch (e: Exception) {
                // ignore
            }
        }
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
        // 重置滤波器
        kalmanFilter.reset()
        previousLocation = null
    }

    /**
     * 获取最新的轨迹点
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
            // 1. 创建LocationData对象
            val rawLocation = createLocationData(location)

            // 2. 质量评估
            val quality = LocationQualityEvaluator.evaluate(rawLocation)

            // 3. 过滤低质量定位点
            if (quality == LocationQuality.BAD || quality == LocationQuality.UNKNOWN) {
                return@launch
            }

            // 4. 漂移检测
            previousLocation?.let { prev ->
                if (LocationQualityEvaluator.isDriftDetected(prev, rawLocation)) {
                    // 检测到漂移，忽略此点
                    return@launch
                }
            }

            // 5. 静止检测
            previousLocation?.let { prev ->
                if (LocationQualityEvaluator.isStationary(rawLocation, prev)) {
                    isStationary = true
                    // 静止时，如果正在记录轨迹，不添加新点
                    if (_isTracking.value) {
                        previousLocation = rawLocation
                        return@launch
                    }
                } else {
                    isStationary = false
                }
            }

            // 6. Kalman滤波（经纬度）
            val (filteredLat, filteredLng) = kalmanFilter.update(
                rawLocation.latitude,
                rawLocation.longitude
            )

            // 7. 创建滤波后的定位数据
            val filteredLocation = rawLocation.copy(
                latitude = filteredLat,
                longitude = filteredLng
            )

            // 8. 更新当前定位
            _currentLocation.value = filteredLocation

            // 9. 保存到数据库（异步）
            saveLocationToDatabase(filteredLocation)

            // 10. 如果正在记录轨迹，保存轨迹点
            if (_isTracking.value && !isStationary) {
                saveTrackPoint(filteredLocation)
            }

            // 11. 更新前一个位置
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
            satelliteCount = getSatelliteCount(),
            hdop = getHdop(),
            pdop = getPdop(),
            vdop = getVdop(),
            snr = getMaxSnr(),
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

        // 更新轨迹点列表
        val currentTracks = _trackPoints.value.toMutableList()
        currentTracks.add(track)
        // 只保留最近的1000个点
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
            1000 // 1秒更新一次
        )
            .setMinUpdateDistanceMeters(1.0f) // 移动1米更新
            .setMinUpdateIntervalMillis(1000)
            .setMaxUpdateDelayMillis(5000)
            .build()
    }

    /**
     * GNSS状态回调（Android 7.0+）
     */
    private val gnssStatusCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                val satelliteList = mutableListOf<SatelliteInfo>()
                for (i in 0 until status.satelliteCount) {
                    val prn = status.getSvid(i)
                    val snr = status.getCn0DbHz(i)
                    val azimuth = status.getAzimuthDegrees(i)
                    val elevation = status.getElevationDegrees(i)
                    val usedInFix = status.usedInFix(i)

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
            }
        }
    } else {
        null
    }

    /**
     * 获取卫星数量
     */
    private fun getSatelliteCount(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val status = locationManager.getGnssStatus(null)
                status?.satelliteCount ?: 0
            } catch (e: Exception) {
                0
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val status = locationManager.getGpsStatus(null)
                status?.satellites?.size ?: 0
            } catch (e: Exception) {
                0
            }
        }
    }

    /**
     * 获取HDOP
     */
    private fun getHdop(): Float {
        // 简化实现，从location获取精度作为参考
        return _currentLocation.value?.accuracy ?: 0f
    }

    /**
     * 获取PDOP
     */
    private fun getPdop(): Float {
        // 简化实现
        return _currentLocation.value?.accuracy?.times(1.5f) ?: 0f
    }

    /**
     * 获取VDOP
     */
    private fun getVdop(): Float {
        // 简化实现
        return _currentLocation.value?.accuracy?.times(1.2f) ?: 0f
    }

    /**
     * 获取最大信噪比
     */
    private fun getMaxSnr(): Float {
        return _satellites.value.maxOfOrNull { it.snr } ?: 0f
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
        // 取消协程作用域
        repositoryScope.coroutineContext.cancelChildren()
    }
}
