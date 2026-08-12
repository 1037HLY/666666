package com.geosurvey.toolbox.domain.model

import java.util.Date

/**
 * 定位数据模型
 */
data class LocationData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val time: Long = System.currentTimeMillis(),
    val provider: String = "",
    val satelliteCount: Int = 0,
    val hdop: Float = 0f,
    val pdop: Float = 0f,
    val vdop: Float = 0f,
    val snr: Float = 0f,
    val quality: LocationQuality = LocationQuality.UNKNOWN
)

/**
 * 定位质量枚举
 */
enum class LocationQuality {
    EXCELLENT,   // 优秀：HDOP < 2, 卫星数 > 8
    GOOD,        // 良好：HDOP < 4, 卫星数 > 6
    FAIR,        // 一般：HDOP < 8, 卫星数 > 4
    POOR,        // 较差：HDOP < 12, 卫星数 > 2
    BAD,         // 很差：HDOP > 12, 卫星数 < 3
    UNKNOWN      // 未知
}

/**
 * 卫星信息
 */
data class SatelliteInfo(
    val prn: Int,
    val constellation: Constellation,
    val snr: Float,
    val azimuth: Float,
    val elevation: Float,
    val usedInFix: Boolean
)

/**
 * 卫星星座枚举
 */
enum class Constellation {
    GPS,
    GLONASS,
    GALILEO,
    BEIDOU,
    QZSS,
    UNKNOWN
}

/**
 * 轨迹点（用于数据库存储）
 */
data class TrackPoint(
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val accuracy: Float,
    val time: Long,
    val satelliteCount: Int,
    val hdop: Float,
    val pdop: Float,
    val provider: String
)
