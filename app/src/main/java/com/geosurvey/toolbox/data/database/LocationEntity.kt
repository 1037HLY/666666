package com.geosurvey.toolbox.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val quality: String
)

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
