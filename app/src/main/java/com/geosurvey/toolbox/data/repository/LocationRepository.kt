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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.geosurvey.toolbox.data.database.LocationDao
import com.geosurvey.toolbox.data.database.LocationEntity
import com.geosurvey.toolbox.data.database.TrackEntity
import com.geosurvey.toolbox.data.database.SampleEntity
import com.geosurvey.toolbox.data.database.DrillSampleEntity
import com.geosurvey.toolbox.domain.model.Constellation
import com.geosurvey.toolbox.domain.model.LocationData
import com.geosurvey.toolbox.domain.model.LocationQuality
import com.geosurvey.toolbox.domain.model.SatelliteInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.math.*

// 数据存储扩展
private val Context.dataStore by preferencesDataStore("settings")

data class AttitudeData(
    val id: Long = 0,
    val strike: Float = 0f,
    val dip: Float = 0f,
    val dipDirection: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val time: Long = 0,
    val note: String = ""
)

// 样本数据类
data class SampleData(
    val id: Long = 0,
    val sampleId: String = "",
    val type: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val depth: Double = 0.0,
    val description: String = "",
    val time: Long = 0,
    val note: String = ""
)

// 钻孔样本数据类
data class DrillSampleData(
    val id: Long = 0,
    val holeId: String = "",
    val sampleId: String = "",
    val depthFrom: Double = 0.0,
    val depthTo: Double = 0.0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val rockType: String = "",
    val description: String = "",
    val time: Long = 0,
    val note: String = ""
)

class LocationRepository(
    private val context: Context,
    private val locationDao: LocationDao
) {
    companion object {
        private const val TAG = "LocationRepository"
        private const val MIN_SATELLITES = 4
        private const val MIN_SNR = 15.0f
        private const val MAX_HISTORY_SIZE = 20
        private val CALIBRATION_OFFSET_KEY = floatPreferencesKey("calibration_offset")
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    // ===== 状态流 =====
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

    // ===== 导航状态 =====
    private val _navigationTarget = MutableStateFlow<TrackEntity?>(null)
    val navigationTarget: StateFlow<TrackEntity?> = _navigationTarget.asStateFlow()
    
    private val _navigationDistance = MutableStateFlow(0.0)
    val navigationDistance: StateFlow<Double> = _navigationDistance.asStateFlow()
    
    private val _navigationBearing = MutableStateFlow(0.0)
    val navigationBearing: StateFlow<Double> = _navigationBearing.asStateFlow()
    
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    // ===== 产状状态 =====
    private val _currentAttitude = MutableStateFlow<AttitudeData?>(null)
    val currentAttitude: StateFlow<AttitudeData?> = _currentAttitude.asStateFlow()
    
    private val _attitudeHistory = MutableStateFlow<List<AttitudeData>>(emptyList())
    val attitudeHistory: StateFlow<List<AttitudeData>> = _attitudeHistory.asStateFlow()

    // ===== 样本状态 =====
    private val _samples = MutableStateFlow<List<SampleData>>(emptyList())
    val samples: StateFlow<List<SampleData>> = _samples.asStateFlow()
    
    private val _drillSamples = MutableStateFlow<List<DrillSampleData>>(emptyList())
    val drillSamples: StateFlow<List<DrillSampleData>> = _drillSamples.asStateFlow()

    // ===== 内部变量 =====
    private var satelliteCount = 0
    private var usedSatelliteCount = 0
    private var avgSnr = 0.0f
    
    private var lastRecordedLocation: TrackEntity? = null
    private var consecutiveStationaryCount = 0

    // ===== 产状测量 =====
    private var sensorListener: SensorEventListener? = null
    private val sensorHistory = mutableListOf<Triple<Float, Float, Float>>()
    private val orientation = FloatArray(3)
    
    // 手动校正偏移 - 从DataStore加载
    private var calibrationOffset = 0f

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

    init {
        // 加载保存的校正偏移
        repositoryScope.launch {
            loadCalibrationOffset()
        }
    }

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
        var usedCount = 0
        var totalSnr = 0.0f
        var validSnrCount = 0
        
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
                GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
                GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
                GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
                GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
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
                Log.d(TAG, "🚶 静止状态，不记录")
                return false
            }
            return false
        } else {
            consecutiveStationaryCount = 0
        }

        if (lastRecordedLocation != null) {
            val distance = haversine(
                lastRecordedLocation!!.latitude,
                lastRecordedLocation!!.longitude,
                current.latitude,
                current.longitude
            )
            
            if (distance < 1.0) {
                Log.d(TAG, "⏭️ 移动距离不足: ${String.format("%.2f", distance)}m")
                return false
            }
        }

        Log.d(TAG, "✅ 记录轨迹点")
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
        Log.d(TAG, "启动产状测量")
        
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        
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
            private val alpha = 0.2f
            
            override fun onSensorChanged(event: SensorEvent) {
                try {
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
                            
                            val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                            val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                            val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                            
                            val pitchRad = Math.toRadians(pitch.toDouble())
                            val rollRad = Math.toRadians(roll.toDouble())
                            val dipRad = atan(sqrt(tan(pitchRad).pow(2) + tan(rollRad).pow(2)))
                            val dip = Math.toDegrees(dipRad).toFloat().coerceIn(0f, 90f)
                            
                            var dipDirection = azimuth
                            dipDirection = (dipDirection + calibrationOffset + 360) % 360
                            
                            var strike = (dipDirection + 90) % 360
                            if (strike > 180) {
                                strike -= 180
                            }
                            
                            sensorHistory.add(Triple(strike, dip, dipDirection))
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
                } catch (e: Exception) {
                    Log.e(TAG, "传感器处理异常: ${e.message}")
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.d(TAG, "传感器精度变化: $accuracy")
            }
        }
        
        sensorListener?.let {
            sensorManager.registerListener(it, accelerometer, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(it, magnetometer, SensorManager.SENSOR_DELAY_UI)
        }
        Log.d(TAG, "✅ 产状测量已启动")
    }

    private fun smoothSensorData(): Triple<Float, Float, Float> {
        if (sensorHistory.isEmpty()) {
            return Triple(0f, 0f, 0f)
        }
        
        var sumStrike = 0f
        var sumDip = 0f
        var sumDipDir = 0f
        
        sensorHistory.forEach { data ->
            sumStrike += data.first
            sumDip += data.second
            sumDipDir += data.third
        }
        
        val size = sensorHistory.size
        return Triple(
            sumStrike / size,
            sumDip / size,
            (sumDipDir / size + 360) % 360
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
                if (history.size > 500) {
                    history.removeAt(0)
                }
                _attitudeHistory.value = history
                Log.d(TAG, "产状已记录: 倾向=${saved.dipDirection}°, 倾角=${saved.dip}°, 走向=${saved.strike}°")
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
    
    // ============ 产状校正 - 持久化 ============
    suspend fun loadCalibrationOffset() {
        try {
            context.dataStore.edit { preferences ->
                calibrationOffset = preferences[CALIBRATION_OFFSET_KEY] ?: 0f
            }
            Log.d(TAG, "加载校正偏移: $calibrationOffset")
        } catch (e: Exception) {
            Log.e(TAG, "加载校正偏移失败: ${e.message}")
        }
    }
    
    suspend fun saveCalibrationOffset(offset: Float) {
        try {
            context.dataStore.edit { preferences ->
                preferences[CALIBRATION_OFFSET_KEY] = offset
            }
            calibrationOffset = offset
            Log.d(TAG, "保存校正偏移: $offset")
        } catch (e: Exception) {
            Log.e(TAG, "保存校正偏移失败: ${e.message}")
        }
    }
    
    fun setCalibrationOffset(offset: Float) {
        calibrationOffset = offset
        repositoryScope.launch {
            saveCalibrationOffset(offset)
        }
    }
    
    fun getCalibrationOffset(): Float = calibrationOffset

    // ============ 样本记录 ============
    suspend fun saveSample(sample: SampleData) {
        withContext(Dispatchers.IO) {
            try {
                val history = _samples.value.toMutableList()
                history.add(sample)
                if (history.size > 500) {
                    history.removeAt(0)
                }
                _samples.value = history
                Log.d(TAG, "样本已保存: ${sample.sampleId}")
            } catch (e: Exception) {
                Log.e(TAG, "保存样本异常: ${e.message}")
            }
        }
    }

    suspend fun saveDrillSample(sample: DrillSampleData) {
        withContext(Dispatchers.IO) {
            try {
                val history = _drillSamples.value.toMutableList()
                history.add(sample)
                if (history.size > 500) {
                    history.removeAt(0)
                }
                _drillSamples.value = history
                Log.d(TAG, "钻孔样本已保存: ${sample.sampleId}")
            } catch (e: Exception) {
                Log.e(TAG, "保存钻孔样本异常: ${e.message}")
            }
        }
    }

    suspend fun clearSamples() {
        withContext(Dispatchers.IO) {
            _samples.value = emptyList()
        }
    }

    suspend fun clearDrillSamples() {
        withContext(Dispatchers.IO) {
            _drillSamples.value = emptyList()
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
