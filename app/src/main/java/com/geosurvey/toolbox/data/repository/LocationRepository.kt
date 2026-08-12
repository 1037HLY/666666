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

    // ... [保留之前的成员变量] ...

    // 产状测量相关
    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    
    private val _currentAttitude = MutableStateFlow<AttitudeData?>(null)
    val currentAttitude: StateFlow<AttitudeData?> = _currentAttitude.asStateFlow()
    
    private val _attitudeHistory = MutableStateFlow<List<AttitudeData>>(emptyList())
    val attitudeHistory: StateFlow<List<AttitudeData>> = _attitudeHistory.asStateFlow()
    
    private var sensorListener: SensorEventListener? = null
    private var currentStrike = 0f
    private var currentDip = 0f
    private var currentDipDirection = 0f
    
    // 卡尔曼滤波用于产状平滑
    private var filteredStrike = 0f
    private var filteredDip = 0f
    private var filteredDipDirection = 0f
    private var filterCount = 0

    // ... [保留之前的方法] ...

    // ============ 产状测量功能 ============
    
    /**
     * 启动产状测量传感器
     */
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
                    // 计算旋转矩阵
                    if (SensorManager.getRotationMatrix(rotationMatrix, null, accelData, magnetData)) {
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        
                        // 计算产状
                        // azimuth: 方位角 (0=北)
                        // pitch: 俯仰角 (手机倾斜)
                        // roll: 横滚角
                        val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                        val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                        val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                        
                        // 计算走向、倾向、倾角
                        // 当手机平放时，pitch和roll反映岩层面
                        val strike = calculateStrike(azimuth, pitch, roll)
                        val dip = calculateDip(pitch, roll)
                        val dipDirection = calculateDipDirection(azimuth, pitch, roll)
                        
                        // 卡尔曼滤波平滑
                        filterAttitude(strike, dip, dipDirection)
                        
                        // 更新当前产状
                        val currentLocation = _currentLocation.value
                        _currentAttitude.value = AttitudeData(
                            strike = filteredStrike,
                            dip = filteredDip,
                            dipDirection = filteredDipDirection,
                            latitude = currentLocation?.latitude ?: 0.0,
                            longitude = currentLocation?.longitude ?: 0.0,
                            altitude = currentLocation?.altitude ?: 0.0,
                            time = System.currentTimeMillis()
                        )
                        
                        currentStrike = filteredStrike
                        currentDip = filteredDip
                        currentDipDirection = filteredDipDirection
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
    
    /**
     * 停止产状测量
     */
    fun stopAttitudeMeasurement() {
        sensorListener?.let {
            sensorManager.unregisterListener(it)
        }
        sensorListener = null
        filterCount = 0
        Log.d(TAG, "停止产状测量")
    }
    
    /**
     * 计算走向
     */
    private fun calculateStrike(azimuth: Float, pitch: Float, roll: Float): Float {
        // 走向 = 倾向 + 90度 (或 -90度)
        // 简化计算：使用方位角和俯仰角
        var strike = when {
            pitch > 0 -> azimuth + 90
            else -> azimuth - 90
        }
        strike = (strike + 360) % 360
        return strike
    }
    
    /**
     * 计算倾角
     */
    private fun calculateDip(pitch: Float, roll: Float): Float {
        // 倾角 = arctan(sqrt(tan(pitch)^2 + tan(roll)^2))
        val pitchRad = Math.toRadians(pitch.toDouble())
        val rollRad = Math.toRadians(roll.toDouble())
        val dipRad = atan(sqrt(tan(pitchRad).pow(2) + tan(rollRad).pow(2)))
        return Math.toDegrees(dipRad).toFloat().coerceIn(0f, 90f)
    }
    
    /**
     * 计算倾向
     */
    private fun calculateDipDirection(azimuth: Float, pitch: Float, roll: Float): Float {
        // 倾向 = 方位角 + 基于倾斜方向的修正
        var dipDir = azimuth
        when {
            pitch > 0 && roll > 0 -> dipDir = azimuth + 45
            pitch > 0 && roll < 0 -> dipDir = azimuth - 45
            pitch < 0 && roll > 0 -> dipDir = azimuth + 135
            pitch < 0 && roll < 0 -> dipDir = azimuth - 135
        }
        return (dipDir + 360) % 360
    }
    
    /**
     * 产状数据卡尔曼滤波
     */
    private fun filterAttitude(strike: Float, dip: Float, dipDirection: Float) {
        if (filterCount == 0) {
            filteredStrike = strike
            filteredDip = dip
            filteredDipDirection = dipDirection
            filterCount = 1
            return
        }
        
        // 简单移动平均
        val alpha = 0.3f // 学习率
        
        // 处理方向数据的循环性
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
    
    /**
     * 记录产状数据
     */
    suspend fun saveAttitude(note: String = "") {
        val attitude = _currentAttitude.value
        if (attitude == null) {
            Log.w(TAG, "没有产状数据可记录")
            return
        }
        
        withContext(Dispatchers.IO) {
            try {
                val saved = attitude.copy(note = note)
                // 保存到数据库 (需要添加AttitudeDao)
                // 更新历史列表
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
    
    /**
     * 清除产状历史
     */
    suspend fun clearAttitudeHistory() {
        withContext(Dispatchers.IO) {
            _attitudeHistory.value = emptyList()
        }
    }

    // ... [保留之前的其他方法] ...
}
