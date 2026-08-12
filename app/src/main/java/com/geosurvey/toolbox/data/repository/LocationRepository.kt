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

// --- 产状数据类 ---
data class AttitudeData(
    val id: Long = 0,
    val strike: Float,      // 走向 (0-360)
    val dip: Float,         // 倾角 (0-90)
    val dipDirection: Float, // 倾向 (0-360)
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
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    // 传感器管理
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
    private var filteredStrike = 0f
    private var filteredDip = 0f
    private var filteredDipDirection = 0f
    private var filterCount = 0

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

    // ============ 产状测量 ============
    fun startAttitudeMeasurement() {
        Log.d(TAG, "开始产状测量")
        
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        
        if (accelerometer == null || magnetometer == null) {
            Log.e(TAG, "设备不支持必要的传感器")
            return
        }
        
        sensorListener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)
            private var accelData = FloatArray(3)
            private var magnetData = FloatArray(3)
            private var hasAccel = false
            private var hasMagnet = false
            
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        accelData = event.values.clone()
                        hasAccel = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magnetData = event.values.clone()
                        hasMagnet = true
                    }
                }
                
                if (hasAccel && hasMagnet) {
                    if (SensorManager.getRotationMatrix(rotationMatrix, null, accelData, magnetData)) {
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        
                        val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                        val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                        
                        val strike = calculateStrike(azimuth, pitch, roll)
                        val dip = calculateDip(pitch, roll)
                        val dipDirection = calculateDipDirection(azimuth, pitch, roll)
                        
                        filterAttitude(strike, dip, dipDirection)
                        
                        val currentLoc = _currentLocation.value
                        _currentAttitude.value = AttitudeData(
                            strike = filteredStrike,
                            dip = filteredDip,
                            dipDirection = filteredDipDirection,
                            latitude = currentLoc?.latitude ?: 0.0,
                            longitude = currentLoc?.longitude ?: 0.0,
                            altitude = currentLoc?.altitude ?: 0.0,
                            time = System.currentTimeMillis()
                        )
                    }
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        
        sensorListener?.let {
            sensorManager.registerListener(it, accelerometer, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(it, magnetometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopAttitudeMeasurement() {
        sensorListener?.let {
            sensorManager.unregisterListener(it)
        }
        sensorListener = null
        filterCount = 0
        Log.d(TAG, "停止产状测量")
    }

    private fun calculateStrike(azimuth: Float, pitch: Float, roll: Float): Float {
        var strike = when {
            pitch > 0 -> azimuth + 90
            else -> azimuth - 90
        }
        strike = (strike + 360) % 360
        return strike
    }

    private fun calculateDip(pitch: Float, roll: Float): Float {
        val pitchRad = Math.toRadians(pitch.toDouble())
        val rollRad = Math.toRadians(roll.toDouble())
        val dipRad = atan(sqrt(tan(pitchRad).pow(2) + tan(rollRad).pow(2)))
        return Math.toDegrees(dipRad).toFloat().coerceIn(0f, 90f)
    }

    private fun calculateDipDirection(azimuth: Float, pitch: Float, roll: Float): Float {
        var dipDir = azimuth
        when {
            pitch > 0 && roll > 0 -> dipDir = azimuth + 45
            pitch > 0 && roll < 0 -> dipDir = azimuth - 45
            pitch < 0 && roll > 0 -> dipDir = azimuth + 135
            pitch < 0 && roll < 0 -> dipDir = azimuth - 135
        }
        return (dipDir + 360) % 360
    }

    private fun filterAttitude(strike: Float, dip: Float, dipDirection: Float) {
        if (filterCount == 0) {
            filteredStrike = strike
            filteredDip = dip
            filteredDipDirection = dipDirection
            filterCount = 1
            return
        }
        
        val alpha = 0.3f
        
        var strikeDiff = strike - filteredStrike
        if (strikeDiff > 180) strikeDiff -= 360
        if (strikeDiff < -180) strikeDiff += 360
        
        filteredStrike = (filteredStrike + alpha * strikeDiff + 360) % 360
        filteredDip = filteredDip + alpha * (dip - filteredDip)
        
        var dipDirDiff = dipDirection - filteredDipDirection
        if (dipDirDiff > 180) dipDirDiff -= 360
        if (dipDirDiff < -180) dipDirDiff += 360
        filteredDipDirection = (filteredDipDirection + alpha * dipDirDiff + 360) % 360
        
        filterCount = if (filterCount < 100) filterCount + 1 else filterCount
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
                if (history.size > 100) {
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
