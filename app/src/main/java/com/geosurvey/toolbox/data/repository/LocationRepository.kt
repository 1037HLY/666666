package com.geosurvey.toolbox.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.*

data class AttitudeData(
    val id: Long = 0,
    val strike: Float,
    val dip: Float,
    val dipDirection: Float,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val time: Long,
    val note: String = ""
)

class LocationRepository(
    private val context: Context,
    private val locationDao: LocationDao
) {
    companion object {
        private const val TAG = "LocationRepository"
        private const val STATIONARY_THRESHOLD = 2.0
        private const val MIN_RECORD_DISTANCE = 1.0
        private const val MIN_SATELLITES = 4
        private const val MIN_SNR = 15.0f
        private const val MAX_HISTORY_SIZE = 30
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    // ===== 定位状态流 =====
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

    // ===== 导航状态流 =====
    private val _navigationTarget = MutableStateFlow<TrackEntity?>(null)
    val navigationTarget: StateFlow<TrackEntity?> = _navigationTarget.asStateFlow()
    
    private val _navigationDistance = MutableStateFlow(0.0)
    val navigationDistance: StateFlow<Double> = _navigationDistance.asStateFlow()
    
    private val _navigationBearing = MutableStateFlow(0.0)
    val navigationBearing: StateFlow<Double> = _navigationBearing.asStateFlow()
    
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    // ===== 产状状态流 =====
    private val _currentAttitude = MutableStateFlow<AttitudeData?>(null)
    val currentAttitude: StateFlow<AttitudeData?> = _currentAttitude.asStateFlow()
    
    private val _attitudeHistory = MutableStateFlow<List<AttitudeData>>(emptyList())
    val attitudeHistory: StateFlow<List<AttitudeData>> = _attitudeHistory.asStateFlow()

    // ===== GNSS质量参数 =====
    private var satelliteCount = 0
    private var usedSatelliteCount = 0
    private var avgSnr = 0.0f
    private var gpsCount = 0
    private var glonassCount = 0
    private var galileoCount = 0
    private var beidouCount = 0

    // ===== 轨迹记录缓存 =====
    private var lastRecordedLocation: TrackEntity? = null
    private var lastValidLocation: LocationData? = null
    private var isMoving = false
    private var consecutiveStationaryCount = 0

    // ===== 产状测量 =====
    private var sensorListener: SensorEventListener? = null
    private val sensorHistory = mutableListOf<Triple<Float, Float, Float>>()

    // ===== 协程 =====
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isRunning = false
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

    // ============ GNSS 回调 ============
    private inner class GnssStatusCallback : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            analyzeGnssStatus(status)
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

    private fun analyzeGnssStatus(status: android.location.GnssStatus) {
        val satelliteList = mutableListOf<SatelliteInfo>()
        
        gpsCount = 0
        glonassCount = 0
        galileoCount = 0
        beidouCount = 0
        
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
        
        _satellites.value = satelliteList
        
        Log.d(TAG, "🛰️ 卫星: 总数=$satelliteCount, 锁定=$usedCount, SNR=${String.format("%.1f", avgSnr)}dB")
    }

    // ============ 定位控制 ============
    fun startLocationUpdates() {
        Log.d(TAG, "========== 开始定位 ==========")
        
        if (isRunning) return
        
        if (!hasLocationPermission()) {
            Log.e(TAG, "❌ 没有定位权限")
            return
        }

        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!isGpsEnabled) {
            Log.e(TAG, "❌ GPS未开启")
            return
        }

        isRunning = true

        registerGnssCallback()
        startGpsListener()
        getLastKnownLocation()
        
        Log.d(TAG, "✅ 定位已启动")
    }

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

    private fun startGpsListener() {
        gpsListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.d(TAG, "📍 GPS: lat=${location.latitude}, lng=${location.longitude}")
                handleNewLocation(location)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    0f,
                    gpsListener!!,
                    Looper.getMainLooper()
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    0f,
                    gpsListener!!
                )
            }
            Log.d(TAG, "✅ GPS监听已启动")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ GPS监听启动失败: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ GPS监听异常: ${e.message}")
        }
    }

    private fun getLastKnownLocation() {
        try {
            val gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (gpsLocation != null) {
                Log.d(TAG, "📍 最后GPS: lat=${gpsLocation.latitude}, lng=${gpsLocation.longitude}")
                handleNewLocation(gpsLocation)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "获取最后位置失败: ${e.message}")
        }
    }

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

    fun forceLocationUpdate() {
        Log.d(TAG, "手动触发位置更新")
        getLastKnownLocation()
    }

    fun cleanup() {
        stopLocationUpdates()
        stopAttitudeMeasurement()
        repositoryScope.coroutineContext.cancelChildren()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ============ 定位数据处理 ============
    private fun handleNewLocation(location: Location) {
        repositoryScope.launch {
            try {
                val lat = location.latitude
                val lng = location.longitude
                
                if (usedSatelliteCount < MIN_SATELLITES) {
                    Log.d(TAG, "⚠️ 卫星数不足: $usedSatelliteCount < $MIN_SATELLITES")
                    return@launch
                }
                
                if (avgSnr < MIN_SNR && usedSatelliteCount < 6) {
                    Log.d(TAG, "⚠️ 信噪比过低: ${String.format("%.1f", avgSnr)}dB")
                    return@launch
                }

                if (location.accuracy > 20) {
                    Log.d(TAG, "⚠️ 精度过低: ${location.accuracy}m > 20m")
                    return@launch
                }

                val locationData = LocationData(
                    latitude = lat,
                    longitude = lng,
                    altitude = location.altitude,
                    accuracy = location.accuracy,
                    speed = location.speed,
                    bearing = location.bearing,
                    time = location.time,
                    provider = location.provider ?: "gps",
                    satelliteCount = satelliteCount,
                    hdop = 0f,
                    pdop = 0f,
                    vdop = 0f,
                    snr = avgSnr,
                    quality = LocationQuality.GOOD
                )

                _currentLocation.value = locationData
                lastValidLocation = locationData

                updateNavigation(locationData)

                if (_isTracking.value) {
                    val shouldRecord = checkShouldRecord(locationData)
                    if (shouldRecord) {
                        saveTrackPoint(locationData)
                    }
                }

                fetchLocationName(lat, lng)

            } catch (e: Exception) {
                Log.e(TAG, "处理定位异常: ${e.message}")
            }
        }
    }

    // ============ 轨迹记录 ============
    fun startTracking() {
        _isTracking.value = true
        lastRecordedLocation = null
        lastValidLocation = null
        consecutiveStationaryCount = 0
        Log.d(TAG, "轨迹记录已开始")
    }

    fun stopTracking() {
        _isTracking.value = false
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

    private fun checkShouldRecord(current: LocationData): Boolean {
        val isCurrentlyStationary = current.speed < 0.3f
        
        if (isCurrentlyStationary) {
            consecutiveStationaryCount++
            if (consecutiveStationaryCount > 5) {
                isMoving = false
                Log.d(TAG, "🚶 静止状态检测到")
                return false
            }
            return false
        } else {
            consecutiveStationaryCount = 0
            isMoving = true
        }

        if (lastRecordedLocation != null) {
            val distance = haversine(
                lastRecordedLocation!!.latitude,
                lastRecordedLocation!!.longitude,
                current.latitude,
                current.longitude
            )
            
            if (distance < MIN_RECORD_DISTANCE) {
                Log.d(TAG, "⏭️ 移动距离不足: ${String.format("%.2f", distance)}m")
                return false
            }
        }

        Log.d(TAG, "✅ 记录轨迹点: lat=${current.latitude}, lng=${current.longitude}")
        return true
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

            lastRecordedLocation = track

            val currentTracks = _trackPoints.value.toMutableList()
            currentTracks.add(track)
            if (currentTracks.size > 5000) {
                currentTracks.removeAt(0)
            }
            _trackPoints.value = currentTracks
            
            Log.d(TAG, "📝 轨迹点已保存: 总数=${currentTracks.size}")
        } catch (e: Exception) {
            Log.e(TAG, "保存轨迹异常: ${e.message}")
        }
    }

    // ============ 导航功能 ============
    fun setNavigationTarget(track: TrackEntity?) {
        _navigationTarget.value = track
        _isNavigating.value = track != null
        if (track != null) {
            Log.d(TAG, "🧭 导航目标已设置")
        } else {
            Log.d(TAG, "🧭 导航已取消")
        }
    }

    private fun updateNavigation(currentLocation: LocationData) {
        val target = _navigationTarget.value
        if (target == null) {
            _navigationDistance.value = 0.0
            _navigationBearing.value = 0.0
            return
        }

        val distance = haversine(
            currentLocation.latitude, currentLocation.longitude,
            target.latitude, target.longitude
        )
        _navigationDistance.value = distance

        val lat1 = Math.toRadians(currentLocation.latitude)
        val lat2 = Math.toRadians(target.latitude)
        val lng1 = Math.toRadians(currentLocation.longitude)
        val lng2 = Math.toRadians(target.longitude)
        
        val y = sin(lng2 - lng1) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lng2 - lng1)
        val bearing = Math.toDegrees(atan2(y, x))
        _navigationBearing.value = (bearing + 360) % 360
    }

    // ============ 产状测量 - 完全重写 ============
    fun startAttitudeMeasurement() {
        Log.d(TAG, "启动精确产状测量 (传感器融合)")
        
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        
        if (accelerometer == null || magnetometer == null) {
            Log.e(TAG, "设备不支持必要的传感器")
            return
        }
        
        sensorHistory.clear()
        
        sensorListener = object : SensorEventListener {
            private val gravity = FloatArray(3)
            private val geomagnetic = FloatArray(3)
            private val rotationMatrix = FloatArray(9)
            private var hasGravity = false
            private var hasGeomagnetic = false
            private val alpha = 0.15f
            
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        if (hasGravity) {
                            gravity[0] = alpha * event.values[0] + (1 - alpha) * gravity[0]
                            gravity[1] = alpha * event.values[1] + (1 - alpha) * gravity[1]
                            gravity[2] = alpha * event.values[2] + (1 - alpha) * gravity[2]
                        } else {
                            gravity[0] = event.values[0]
                            gravity[1] = event.values[1]
                            gravity[2] = event.values[2]
                            hasGravity = true
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        if (hasGeomagnetic) {
                            geomagnetic[0] = alpha * event.values[0] + (1 - alpha) * geomagnetic[0]
                            geomagnetic[1] = alpha * event.values[1] + (1 - alpha) * geomagnetic[1]
                            geomagnetic[2] = alpha * event.values[2] + (1 - alpha) * geomagnetic[2]
                        } else {
                            geomagnetic[0] = event.values[0]
                            geomagnetic[1] = event.values[1]
                            geomagnetic[2] = event.values[2]
                            hasGeomagnetic = true
                        }
                    }
                }
                
                if (hasGravity && hasGeomagnetic) {
                    if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        
                        // 从旋转矩阵提取岩面产状
                        val normalX = rotationMatrix[6].toDouble()
                        val normalY = rotationMatrix[7].toDouble()
                        val normalZ = rotationMatrix[8].toDouble()
                        
                        // 计算倾角
                        val dipRad = asin(abs(normalZ))
                        val dip = Math.toDegrees(dipRad).toFloat()
                        
                        // 计算倾向
                        var dipDirection = Math.toDegrees(atan2(normalY, normalX)).toFloat()
                        dipDirection = (dipDirection + 360) % 360
                        
                        // 计算走向
                        var strike = (dipDirection + 90) % 360
                        
                        // 重力法辅助计算
                        val gx = gravity[0]
                        val gy = gravity[1]
                        val gz = gravity[2]
                        val gravMag = sqrt(gx * gx + gy * gy + gz * gz)
                        
                        if (gravMag > 0) {
                            val dipFromGravity = Math.toDegrees(acos(abs(gz) / gravMag)).toFloat()
                            val gravityDipDir = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat()
                            var gravityDipDirNormalized = (gravityDipDir + 360) % 360
                            
                            val finalDip = dip * 0.7f + dipFromGravity * 0.3f
                            val finalDipDir = dipDirection * 0.7f + gravityDipDirNormalized * 0.3f
                            val finalStrike = (finalDipDir + 90) % 360
                            
                            sensorHistory.add(Triple(finalStrike, finalDip, finalDipDir))
                            if (sensorHistory.size > MAX_HISTORY_SIZE) {
                                sensorHistory.removeAt(0)
                            }
                            
                            val (smoothedStrike, smoothedDip, smoothedDipDir) = smoothSensorData()
                            
                            val currentLoc = _currentLocation.value
                            _currentAttitude.value = AttitudeData(
                                strike = smoothedStrike,
                                dip = smoothedDip,
                                dipDirection = smoothedDipDir,
                                latitude = currentLoc?.latitude ?: 0.0,
                                longitude = currentLoc?.longitude ?: 0.0,
                                altitude = currentLoc?.altitude ?: 0.0,
                                time = System.currentTimeMillis()
                            )
                        }
                    }
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.d(TAG, "传感器精度变化: $accuracy")
            }
        }
        
        sensorListener?.let {
            sensorManager.registerListener(it, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
            sensorManager.registerListener(it, magnetometer, SensorManager.SENSOR_DELAY_FASTEST)
            if (gyroscope != null) {
                sensorManager.registerListener(it, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
            }
        }
        Log.d(TAG, "✅ 精确产状测量已启动")
    }

    private val orientation = FloatArray(3)

    private fun smoothSensorData(): Triple<Float, Float, Float> {
        if (sensorHistory.isEmpty()) {
            return Triple(0f, 0f, 0f)
        }
        
        var sumStrike = 0f
        var sumDip = 0f
        var sumDipDir = 0f
        var totalWeight = 0f
        
        sensorHistory.forEachIndexed { index, data ->
            val weight = (index + 1).toFloat().pow(1.5f) / sensorHistory.size
            sumStrike += data.first * weight
            sumDip += data.second * weight
            sumDipDir += data.third * weight
            totalWeight += weight
        }
        
        if (totalWeight > 0) {
            return Triple(
                (sumStrike / totalWeight + 360) % 360,
                sumDip / totalWeight,
                (sumDipDir / totalWeight + 360) % 360
            )
        }
        
        return Triple(
            sensorHistory.last().first,
            sensorHistory.last().second,
            sensorHistory.last().third
        )
    }

    fun stopAttitudeMeasurement() {
        sensorListener?.let {
            sensorManager.unregisterListener(it)
        }
        sensorListener = null
        sensorHistory.clear()
        Log.d(TAG, "停止产状测量")
    }

    suspend fun saveAttitude(note: String = "") {
        val attitude = _currentAttitude.value
        if (attitude == null) {
            Log.w(TAG, "没有产状数据可记录")
            return
        }
        
        withContext(Dispatchers.IO) {
            try {
                val saved = attitude.copy(note = note)
                val history = _attitudeHistory.value.toMutableList()
                history.add(saved)
                if (history.size > 1000) {
                    history.removeAt(0)
                }
                _attitudeHistory.value = history
                Log.d(TAG, "产状已记录: 倾向=${saved.dipDirection}°, 倾角=${saved.dip}°")
            } catch (e: Exception) {
                Log.e(TAG, "保存产状异常: ${e.message}")
            }
        }
    }

    suspend fun clearAttitudeHistory() {
        withContext(Dispatchers.IO) {
            _attitudeHistory.value = emptyList()
        }
    }

    // ============ 地址获取 ============
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

    // ============ 工具函数 ============
    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2.0)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
