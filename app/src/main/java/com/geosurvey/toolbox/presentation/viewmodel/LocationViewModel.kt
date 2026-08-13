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

class LocationViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "LocationViewModel"
    }

    // 模拟数据 - 实际项目需要实现
    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    val currentLocation = MutableStateFlow<LocationData?>(null)
    val satellites = MutableStateFlow<List<SatelliteInfo>>(emptyList())
    val isTracking = MutableStateFlow(false)
    val trackPoints = MutableStateFlow<List<com.geosurvey.toolbox.data.database.TrackEntity>>(emptyList())
    val locationName = MutableStateFlow("正在获取地址...")
    val detailedAddress = MutableStateFlow<com.geosurvey.toolbox.data.repository.LocationRepository.DetailedAddress?>(null)

    val isNavigating = MutableStateFlow(false)
    val navigationTarget = MutableStateFlow<com.geosurvey.toolbox.data.database.TrackEntity?>(null)
    val navigationDistance = MutableStateFlow(0.0)
    val navigationBearing = MutableStateFlow(0.0)

    val currentAttitude = MutableStateFlow<AttitudeData?>(null)
    val attitudeHistory = MutableStateFlow<List<AttitudeData>>(emptyList())

    val samples = MutableStateFlow<List<SampleData>>(emptyList())
    val drillSamples = MutableStateFlow<List<DrillSampleData>>(emptyList())

    init {
        Log.d(TAG, "LocationViewModel 初始化")
    }

    fun startLocation() {
        Log.d(TAG, "开始定位")
    }

    fun restartLocation() {
        Log.d(TAG, "重新开始定位")
    }

    fun startTracking() {
        isTracking.value = true
        _uiState.value = _uiState.value.copy(isRecording = true)
    }

    fun stopTracking() {
        isTracking.value = false
        _uiState.value = _uiState.value.copy(isRecording = false)
    }

    fun loadTracks() {
        // 模拟加载
        _uiState.value = _uiState.value.copy(tracks = emptyList())
    }

    fun setNavigationTarget(track: com.geosurvey.toolbox.data.database.TrackEntity?) {
        navigationTarget.value = track
        isNavigating.value = track != null
    }

    fun startAttitudeMeasurement() {}
    fun stopAttitudeMeasurement() {}
    fun saveAttitude(note: String = "") {}
    fun clearAttitudeHistory() {}

    fun setCalibrationOffset(offset: Float) {}
    fun getCalibrationOffset(): Float = 0f

    fun saveSample(sample: SampleData) {
        val list = samples.value.toMutableList()
        list.add(sample)
        samples.value = list
    }

    fun saveDrillSample(sample: DrillSampleData) {
        val list = drillSamples.value.toMutableList()
        list.add(sample)
        drillSamples.value = list
    }

    fun clearSamples() {
        samples.value = emptyList()
    }

    fun clearDrillSamples() {
        drillSamples.value = emptyList()
    }

    suspend fun exportAttitudeHistory(): String = ""
    suspend fun exportSamples(): String = ""
    suspend fun exportDrillSamples(): String = ""

    override fun onCleared() {
        super.onCleared()
    }
}
