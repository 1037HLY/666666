package com.geosurvey.toolbox.presentation.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geosurvey.toolbox.data.database.AppDatabase
import com.geosurvey.toolbox.data.database.TrackEntity
import com.geosurvey.toolbox.data.repository.LocationRepository
import com.geosurvey.toolbox.domain.model.LocationData
import com.geosurvey.toolbox.domain.model.LocationQuality
import com.geosurvey.toolbox.domain.model.SatelliteInfo
import com.geosurvey.toolbox.domain.util.LocationQualityEvaluator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 定位ViewModel
 */
class LocationViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "LocationViewModel"
    }

    private val database = AppDatabase.getDatabase(application)
    private val locationRepository = LocationRepository(application, database.locationDao())

    // UI状态
    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    // 定位数据
    val currentLocation: StateFlow<LocationData?> = locationRepository.currentLocation
    val satellites: StateFlow<List<SatelliteInfo>> = locationRepository.satellites
    val isTracking: StateFlow<Boolean> = locationRepository.isTracking
    val trackPoints = locationRepository.trackPoints
    val locationName = locationRepository.locationName // 新增：地点名称

    init {
        Log.d(TAG, "========== LocationViewModel initialized ==========")
        startLocation()
    }

    /**
     * 开始定位
     */
    fun startLocation() {
        Log.d(TAG, "startLocation() called")
        locationRepository.startLocationUpdates()
    }

    /**
     * 重新开始定位
     */
    fun restartLocation() {
        Log.d(TAG, "restartLocation() called")
        locationRepository.stopLocationUpdates()
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            locationRepository.startLocationUpdates()
            kotlinx.coroutines.delay(1000)
            locationRepository.forceLocationUpdate()
        }
    }

    /**
     * 开始轨迹记录
     */
    fun startTracking() {
        locationRepository.startTracking()
        _uiState.value = _uiState.value.copy(isRecording = true)
    }

    /**
     * 停止轨迹记录
     */
    fun stopTracking() {
        locationRepository.stopTracking()
        _uiState.value = _uiState.value.copy(isRecording = false)
    }

    /**
     * 获取轨迹点
     */
    fun loadTracks() {
        viewModelScope.launch {
            val tracks = locationRepository.getRecentTracks(100)
            _uiState.value = _uiState.value.copy(tracks = tracks)
        }
    }

    /**
     * 清理资源
     */
    override fun onCleared() {
        super.onCleared()
        locationRepository.cleanup()
    }
}

/**
 * 定位UI状态
 */
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
