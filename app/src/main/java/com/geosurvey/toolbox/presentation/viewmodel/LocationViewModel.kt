package com.geosurvey.toolbox.presentation.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geosurvey.toolbox.data.database.AppDatabase
import com.geosurvey.toolbox.data.database.TrackEntity
import com.geosurvey.toolbox.data.repository.AttitudeData
import com.geosurvey.toolbox.data.repository.DrillSampleData
import com.geosurvey.toolbox.data.repository.LocationRepository
import com.geosurvey.toolbox.data.repository.SampleData
import com.geosurvey.toolbox.domain.model.LocationData
import com.geosurvey.toolbox.domain.model.LocationQuality
import com.geosurvey.toolbox.domain.model.SatelliteInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LocationViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "LocationViewModel"
    }

    private val database = AppDatabase.getDatabase(application)
    private val locationRepository = LocationRepository(application, database.locationDao())

    // ===== UI状态 =====
    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    // ===== 定位数据 =====
    val currentLocation: StateFlow<LocationData?> = locationRepository.currentLocation
    val satellites: StateFlow<List<SatelliteInfo>> = locationRepository.satellites
    val isTracking: StateFlow<Boolean> = locationRepository.isTracking
    val trackPoints = locationRepository.trackPoints
    val locationName = locationRepository.locationName
    val detailedAddress = locationRepository.detailedAddress

    // ===== 导航数据 =====
    val isNavigating: StateFlow<Boolean> = locationRepository.isNavigating
    val navigationTarget: StateFlow<TrackEntity?> = locationRepository.navigationTarget
    val navigationDistance: StateFlow<Double> = locationRepository.navigationDistance
    val navigationBearing: StateFlow<Double> = locationRepository.navigationBearing

    // ===== 产状数据 =====
    val currentAttitude: StateFlow<AttitudeData?> = locationRepository.currentAttitude
    val attitudeHistory: StateFlow<List<AttitudeData>> = locationRepository.attitudeHistory

    // ===== 样本数据 =====
    val samples: StateFlow<List<SampleData>> = locationRepository.samples
    val drillSamples: StateFlow<List<DrillSampleData>> = locationRepository.drillSamples

    init {
        Log.d(TAG, "========== LocationViewModel 初始化 ==========")
        startLocation()
        startAttitudeMeasurement()
    }

    // ============ 定位控制 ============
    fun startLocation() {
        Log.d(TAG, "开始定位")
        locationRepository.startLocationUpdates()
    }

    fun restartLocation() {
        Log.d(TAG, "重新开始定位")
        locationRepository.stopLocationUpdates()
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            locationRepository.startLocationUpdates()
            kotlinx.coroutines.delay(1000)
            locationRepository.forceLocationUpdate()
        }
    }

    // ============ 轨迹控制 ============
    fun startTracking() {
        locationRepository.startTracking()
        _uiState.value = _uiState.value.copy(isRecording = true)
    }

    fun stopTracking() {
        locationRepository.stopTracking()
        _uiState.value = _uiState.value.copy(isRecording = false)
    }

    fun loadTracks() {
        viewModelScope.launch {
            val tracks = locationRepository.getRecentTracks(100)
            _uiState.value = _uiState.value.copy(tracks = tracks)
        }
    }

    // ============ 导航控制 ============
    fun setNavigationTarget(track: TrackEntity?) {
        locationRepository.setNavigationTarget(track)
    }

    // ============ 产状控制 ============
    fun startAttitudeMeasurement() {
        locationRepository.startAttitudeMeasurement()
    }

    fun stopAttitudeMeasurement() {
        locationRepository.stopAttitudeMeasurement()
    }

    fun saveAttitude(note: String = "") {
        viewModelScope.launch {
            locationRepository.saveAttitude(note)
        }
    }

    fun clearAttitudeHistory() {
        viewModelScope.launch {
            locationRepository.clearAttitudeHistory()
        }
    }

    // ============ 产状校正 ============
    fun setCalibrationOffset(offset: Float) {
        locationRepository.setCalibrationOffset(offset)
    }

    fun getCalibrationOffset(): Float {
        return locationRepository.getCalibrationOffset()
    }

    // ============ 样本记录 ============
    fun saveSample(sample: SampleData) {
        viewModelScope.launch {
            locationRepository.saveSample(sample)
        }
    }

    fun saveDrillSample(sample: DrillSampleData) {
        viewModelScope.launch {
            locationRepository.saveDrillSample(sample)
        }
    }

    fun clearSamples() {
        viewModelScope.launch {
            locationRepository.clearSamples()
        }
    }

    fun clearDrillSamples() {
        viewModelScope.launch {
            locationRepository.clearDrillSamples()
        }
    }

    // ============ 导出产状数据 ============
    suspend fun exportAttitudeHistory(): String {
        val history = attitudeHistory.value
        if (history.isEmpty()) return ""
        
        return buildString {
            append("序号,走向(°),倾角(°),倾向(°),纬度,经度,海拔(m),时间,备注\n")
            history.forEachIndexed { index, data ->
                append("${index + 1},")
                append("${String.format("%.1f", data.strike)},")
                append("${String.format("%.1f", data.dip)},")
                append("${String.format("%.1f", data.dipDirection)},")
                append("${String.format("%.6f", data.latitude)},")
                append("${String.format("%.6f", data.longitude)},")
                append("${String.format("%.1f", data.altitude)},")
                append("${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", data.time)},")
                append("${data.note}\n")
            }
        }
    }

    // ============ 导出样本数据 ============
    suspend fun exportSamples(): String {
        val list = samples.value
        if (list.isEmpty()) return ""
        
        return buildString {
            append("序号,样本编号,类型,纬度,经度,海拔(m),重量(kg),描述,时间,备注\n")
            list.forEachIndexed { index, data ->
                append("${index + 1},")
                append("${data.sampleId},")
                append("${data.type},")
                append("${String.format("%.6f", data.latitude)},")
                append("${String.format("%.6f", data.longitude)},")
                append("${String.format("%.1f", data.altitude)},")
                append("${String.format("%.2f", data.weight)},")
                append("${data.description},")
                append("${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", data.time)},")
                append("${data.note}\n")
            }
        }
    }

    suspend fun exportDrillSamples(): String {
        val list = drillSamples.value
        if (list.isEmpty()) return ""
        
        return buildString {
            append("序号,钻孔编号,样本编号,深度从(m),深度到(m),岩心长(m),采取率(%),样长(m),重量(kg),岩心直径(mm),纬度,经度,海拔(m),岩性,描述,时间,备注\n")
            list.forEachIndexed { index, data ->
                append("${index + 1},")
                append("${data.holeId},")
                append("${data.sampleId},")
                append("${String.format("%.2f", data.depthFrom)},")
                append("${String.format("%.2f", data.depthTo)},")
                append("${String.format("%.2f", data.coreLength)},")
                append("${String.format("%.1f", data.recoveryRate)},")
                append("${String.format("%.2f", data.sampleLength)},")
                append("${String.format("%.2f", data.weight)},")
                append("${String.format("%.1f", data.coreDiameter)},")
                append("${String.format("%.6f", data.latitude)},")
                append("${String.format("%.6f", data.longitude)},")
                append("${String.format("%.1f", data.altitude)},")
                append("${data.rockType},")
                append("${data.description},")
                append("${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", data.time)},")
                append("${data.note}\n")
            }
        }
    }

    // ============ 清理 ============
    override fun onCleared() {
        super.onCleared()
        locationRepository.cleanup()
    }
}

data class LocationUiState(
    val location: LocationData? = null,
    val quality: LocationQuality = LocationQuality.UNKNOWN,
    val qualityText: String = "未知",
    val satelliteCount: Int = 0,
    val hdop: Float = 0f,
    val snr: Float = 0f,
    val isRecording: Boolean = false,
    val tracks: List<TrackEntity> = emptyList()
)
