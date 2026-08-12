package com.geosurvey.toolbox.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

/**
 * 定位数据实体（Room数据库）
 */
@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val time: Long,
    val provider: String,
    val satelliteCount: Int,
    val hdop: Float,
    val pdop: Float,
    val vdop: Float,
    val snr: Float,
    val quality: String // LocationQuality枚举的名称
)

/**
 * 轨迹实体
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
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
    val provider: String,
    val isFromBackground: Boolean = false
)
