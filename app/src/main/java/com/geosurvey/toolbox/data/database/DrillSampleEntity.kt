package com.geosurvey.toolbox.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "drill_samples")
data class DrillSampleEntity(
    @PrimaryKey(autoGenerate = true)
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
