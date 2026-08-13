package com.geosurvey.toolbox.presentation.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geosurvey.toolbox.data.repository.AttitudeData
import com.geosurvey.toolbox.data.repository.DrillSampleData
import com.geosurvey.toolbox.data.repository.SampleData
import com.geosurvey.toolbox.domain.model.LocationData
import com.geosurvey.toolbox.domain.model.LocationQuality
import com.geosurvey.toolbox.domain.model.SatelliteInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 定义 LocationUiState 数据类
data class LocationUiState(
    val location: LocationData? = null,
    val quality: LocationQuality = LocationQuality.UNKNOWN,
    val qualityText: String = "未知",
    val satelliteCount: Int = 0,
    val hdop: Float = 0f,
    val snr: Float = 0f,
    val isRecording: Boolean = false,
    val tracks: List<com.geosurvey.toolbox.data.database.TrackEntity> = emptyList()
)

class LocationViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "LocationViewModel"
    }

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    // 定位数据 - 使用 MutableStateFlow
    private val _currentLocation = MutableStateFlow<LocationData?>(null)
    val currentLocation: StateFlow<LocationData?> = _currentLocation.asStateFlow()

    private val _satellites = MutableStateFlow<List<SatelliteInfo>>(emptyList())
    val satellites: StateFlow<List<SatelliteInfo>> = _satellites.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _trackPoints = MutableStateFlow<List<com.geosurvey.toolbox.data.database.TrackEntity>>(emptyList())
    val trackPoints: StateFlow<List<com.geosurvey.toolbox.data.database.TrackEntity>> = _trackPoints.asStateFlow()

    private val _locationName = MutableStateFlow("正在获取地址...")
    val locationName: StateFlow<String> = _locationName.asStateFlow()

    private val _detailedAddress = MutableStateFlow<com.geosurvey.toolbox.data.repository.LocationRepository.DetailedAddress?>(null)
    val detailedAddress: StateFlow<com.geosurvey.toolbox.data.repository.LocationRepository.DetailedAddress?> = _detailedAddress.asStateFlow()

    // 导航数据
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    private val _navigationTarget = MutableStateFlow<com.geosurvey.toolbox.data.database.TrackEntity?>(null)
    val navigationTarget: StateFlow<com.geosurvey.toolbox.data.database.TrackEntity?> = _navigationTarget.asStateFlow()

    private val _navigationDistance = MutableStateFlow(0.0)
    val navigationDistance: StateFlow<Double> = _navigationDistance.asStateFlow()

    private val _navigationBearing = MutableStateFlow(0.0)
    val navigationBearing: StateFlow<Double> = _navigationBearing.asStateFlow()

    // 产状数据
    private val _currentAttitude = MutableStateFlow<AttitudeData?>(null)
    val currentAttitude: StateFlow<AttitudeData?> = _currentAttitude.asStateFlow()

    private val _attitudeHistory = MutableStateFlow<List<AttitudeData>>(emptyList())
    val attitudeHistory: StateFlow<List<AttitudeData>> = _attitudeHistory.asStateFlow()

    // 样本数据
    private val _samples = MutableStateFlow<List<SampleData>>(emptyList())
    val samples: StateFlow<List<SampleData>> = _samples.asStateFlow()

    private val _drillSamples = MutableStateFlow<List<DrillSampleData>>(emptyList())
    val drillSamples: StateFlow<List<DrillSampleData>> = _drillSamples.asStateFlow()

    init {
        Log.d(TAG, "LocationViewModel 初始化")
    }

    fun startLocation() {
        Log.d(TAG, "开始定位")
        // 模拟数据
        _currentLocation.value = LocationData(
            latitude = 27.043492,
            longitude = 102.666067,
            altitude = 946.9,
            accuracy = 5.0f,
            speed = 0.0f,
            bearing = 0.0f,
            time = System.currentTimeMillis(),
            provider = "gps",
            satelliteCount = 12,
            hdop = 1.2f,
            pdop = 1.5f,
            vdop = 0.8f,
            snr = 25.0f,
            quality = LocationQuality.GOOD
        )
        _locationName.value = "四川省凉山彝族自治州"
    }

    fun restartLocation() {
        Log.d(TAG, "重新开始定位")
        startLocation()
    }

    fun startTracking() {
        _isTracking.value = true
        _uiState.value = _uiState.value.copy(isRecording = true)
    }

    fun stopTracking() {
        _isTracking.value = false
        _uiState.value = _uiState.value.copy(isRecording = false)
    }

    fun loadTracks() {
        _uiState.value = _uiState.value.copy(tracks = emptyList())
    }

    fun setNavigationTarget(track: com.geosurvey.toolbox.data.database.TrackEntity?) {
        _navigationTarget.value = track
        _isNavigating.value = track != null
    }

    fun startAttitudeMeasurement() {}
    fun stopAttitudeMeasurement() {}
    fun saveAttitude(note: String = "") {}
    fun clearAttitudeHistory() {}

    fun setCalibrationOffset(offset: Float) {}
    fun getCalibrationOffset(): Float = 0f

    fun saveSample(sample: SampleData) {
        val list = _samples.value.toMutableList()
        list.add(sample)
        _samples.value = list
    }

    fun saveDrillSample(sample: DrillSampleData) {
        val list = _drillSamples.value.toMutableList()
        list.add(sample)
        _drillSamples.value = list
    }

    fun clearSamples() {
        _samples.value = emptyList()
    }

    fun clearDrillSamples() {
        _drillSamples.value = emptyList()
    }

    suspend fun exportAttitudeHistory(): String = ""
    suspend fun exportSamples(): String = ""
    suspend fun exportDrillSamples(): String = ""

    override fun onCleared() {
        super.onCleared()
    }
}
