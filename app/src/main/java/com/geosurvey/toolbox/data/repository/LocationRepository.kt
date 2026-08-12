package com.geosurvey.toolbox.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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

/**
 * 定位仓库 - 完整功能
 * 1. 高斯-克吕格投影坐标计算
 * 2. HDOP/VDOP估算
 * 3. 方向计算
 */
class LocationRepository(
    private val context: Context,
    private val locationDao: LocationDao
) {
    companion object {
        private const val TAG = "LocationRepository"
        
        // 高斯-克吕格投影参数 (CGCS2000)
        private const val A = 6378137.0          // 长半轴
        private const val F = 1.0 / 298.257222101  // 扁率
        private const val E2 = 2.0 * F - F * F   // 第一偏心率的平方
        
        // 中央子午线 (3度带)
        private const val CENTRAL_MERIDIAN = 102.0  // 根据坐标自动计算
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

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

    // 高斯投影坐标 (米)
    private val _gkX = MutableStateFlow(0.0)
    val gkX: StateFlow<Double> = _gkX.asStateFlow()
    
    private val _gkY = MutableStateFlow(0.0)
    val gkY: StateFlow<Double> = _gkY.asStateFlow()
    
    private val _gkZone = MutableStateFlow(0)
    val gkZone: StateFlow<Int> = _gkZone.asStateFlow()

    // GNSS质量参数
    private var satelliteCount = 0
    private var usedSatelliteCount = 0
    private var avgSnr = 0.0f
    private var hdop = 0.0f
    private var vdop = 0.0f
    
    private var gpsCount = 0
    private var glonassCount = 0
    private var galileoCount = 0
    private var beidouCount = 0
    
    private var previousLocation: LocationData? = null
    
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

    /**
     * 高斯-克吕格投影转换
     * @param lat 纬度 (度)
     * @param lng 经度 (度)
     * @return Pair(x, y) 坐标 (米)
     */
    private fun gaussKrugerProjection(lat: Double, lng: Double): Pair<Double, Double> {
        // 计算3度带带号
        val zone = ((lng + 1.5) / 3).toInt()
        val centralMeridian = zone * 3.0
        
        // 角度转弧度
        val latRad = Math.toRadians(lat)
        val lngRad = Math.toRadians(lng)
        val centralMeridianRad = Math.toRadians(centralMeridian)
        
        val delta = lngRad - centralMeridianRad
        
        // 计算辅助量
        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val tanLat = tan(latRad)
        
        // 计算子午线弧长
        val e2 = E2
        val e4 = e2 * e2
        val e6 = e4 * e2
        val e8 = e6 * e2
        
        val a = A
        val a0 = a * (1 - e2 * (1.0/4 + e2 * (3.0/64 + e2 * (5.0/256 + e2 * 35.0/16384))))
        val a2 = -a * (e2 * (3.0/8 + e2 * (3.0/32 + e2 * (45.0/1024 + e2 * 175.0/16384))))
        val a4 = a * (e4 * (15.0/256 + e4 * (45.0/1024 + e4 * 525.0/16384)))
        val a6 = -a * (e6 * (35.0/3072 + e6 * 175.0/12288))
        val a8 = a * (e8 * 315.0/131072)
        
        val B = a0 * latRad + a2 * sin(2 * latRad) + a4 * sin(4 * latRad) + a6 * sin(6 * latRad) + a8 * sin(8 * latRad)
        
        // 计算子午线弧长
        val N = A / sqrt(1 - e2 * sinLat * sinLat)
        val t = tanLat
        val eta2 = e2 / (1 - e2) * cosLat * cosLat
        
        // 高斯-克吕格正算公式
        val x = B + N * t * (delta * delta / 2 + delta * delta * delta * delta / 24 * (5 - t * t + 9 * eta2 + 4 * eta2 * eta2) + 
                delta * delta * delta * delta * delta * delta / 720 * (61 - 58 * t * t + t * t * t * t))
        
        val y = N * (delta + delta * delta * delta / 6 * (1 - t * t + eta2) + 
                delta * delta * delta * delta * delta / 120 * (5 - 18 * t * t + t * t * t * t + 14 * eta2 - 58 * eta2 * t * t))
        
        // 添加带号
        return Pair(x, y + zone * 1000000.0 + 500000.0)
    }

    /**
     * 计算中央子午线
     */
    private fun getCentralMeridian(lng: Double): Double {
        val zone = ((lng + 1.5) / 3).toInt()
        return zone * 3.0
    }

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

    /**
     * 分析GNSS卫星状态 - 估算HDOP/VDOP
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
        
        // 估算HDOP和VDOP
        // 基于锁定卫星的数量和仰角分布
        if (usedCount >= 4 && validElevationCount > 0) {
            val avgElev = totalElevation / validElevationCount
            
            // 更好的卫星分布 = 更低的HDOP
            // 仰角越高，垂直精度越好
            val hdopFactor = 12.0f / usedCount
            val vdopFactor = 8.0f / usedCount * (1.0f + (45.0f - avgElev) / 90.0f)
            
            hdop = hdopFactor.coerceIn(0.5f, 20.0f)
            vdop = vdopFactor.coerceIn(0.5f, 20.0f)
        } else {
            hdop = 0f
            vdop = 0f
        }
        
        Log.d(TAG, "🛰️ 卫星: 总数=$satelliteCount, 锁定=$usedCount")
        Log.d(TAG, "   GPS=$gpsCount, GLONASS=$glonassCount, Galileo=$galileoCount, 北斗=$beidouCount")
        Log.d(TAG, "   SNR=${String.format("%.1f", avgSnr)}dB, HDOP=${String.format("%.1f", hdop)}, VDOP=${String.format("%.1f", vdop)}")
    }

    fun startLocationUpdates() {
        Log.d(TAG, "========== 开始定位 ==========")
        
        if (isRunning) {
            Log.d(TAG, "定位已在运行")
            return
        }
        
        if (!hasLocationPermission()) {
            Log.e(TAG, "❌ 没有定位权限")
            return
        }

        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        Log.d(TAG, "GPS状态: ${if (isGpsEnabled) "已开启" else "未开启"}")
        
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
                Log.d(TAG, "📍 GPS: lat=${location.latitude}, lng=${location.longitude}, acc=${location.accuracy}m")
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
     * 处理定位 - 计算高斯投影和HDOP
     */
    private fun handleNewLocation(location: Location) {
        repositoryScope.launch {
            try {
                val lat = location.latitude
                val lng = location.longitude
                
                Log.d(TAG, "=== 处理GPS坐标 ===")
                Log.d(TAG, "纬度: $lat, 经度: $lng")
                
                // 1. 计算高斯-克吕格投影坐标
                val (gkX, gkY) = gaussKrugerProjection(lat, lng)
                val zone = ((lng + 1.5) / 3).toInt()
                
                _gkX.value = gkX
                _gkY.value = gkY
                _gkZone.value = zone
                
                Log.d(TAG, "高斯投影: X=$gkX, Y=$gkY, 带号=$zone")
                
                // 2. 获取方向 (只有速度>0时才有意义)
                val bearing = if (location.speed > 0.5f) location.bearing else 0f

                // 3. 创建定位数据
                val locationData = LocationData(
                    latitude = lat,
                    longitude = lng,
                    altitude = location.altitude,
                    accuracy = location.accuracy,
                    speed = location.speed,
                    bearing = bearing,
                    time = location.time,
                    provider = location.provider ?: "gps",
                    satelliteCount = satelliteCount,
                    hdop = hdop,
                    pdop = hdop * 1.5f,  // PDOP ≈ HDOP * 1.5
                    vdop = vdop,
                    snr = avgSnr,
                    quality = evaluateQuality()
                )

                // 更新状态
                _currentLocation.value = locationData
                previousLocation = locationData
                
                Log.d(TAG, "✅ 坐标已更新: lat=$lat, lng=$lng")
                Log.d(TAG, "   HDOP=$hdop, VDOP=$vdop, 卫星=$usedSatelliteCount")

                // 获取地址
                fetchLocationName(lat, lng)
                
                // 保存数据
                saveLocationToDatabase(locationData)

                if (_isTracking.value) {
                    saveTrackPoint(locationData)
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理定位异常: ${e.message}")
            }
        }
    }

    /**
     * 评估定位质量
     */
    private fun evaluateQuality(): LocationQuality {
        return when {
            hdop < 1.0f && usedSatelliteCount >= 8 -> LocationQuality.EXCELLENT
            hdop < 2.0f && usedSatelliteCount >= 6 -> LocationQuality.GOOD
            hdop < 4.0f && usedSatelliteCount >= 4 -> LocationQuality.FAIR
            usedSatelliteCount >= 3 -> LocationQuality.POOR
            else -> LocationQuality.BAD
        }
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
