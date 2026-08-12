package com.geosurvey.toolbox.domain.util

import com.geosurvey.toolbox.domain.model.LocationData
import com.geosurvey.toolbox.domain.model.LocationQuality
import kotlin.math.*

/**
 * 定位质量评估器
 */
object LocationQualityEvaluator {
    
    /**
     * 评估定位质量
     */
    fun evaluate(location: LocationData): LocationQuality {
        val satelliteCount = location.satelliteCount
        val hdop = location.hdop
        val accuracy = location.accuracy

        return when {
            satelliteCount >= 8 && hdop < 2f && accuracy < 5f -> LocationQuality.EXCELLENT
            satelliteCount >= 6 && hdop < 4f && accuracy < 10f -> LocationQuality.GOOD
            satelliteCount >= 4 && hdop < 8f && accuracy < 20f -> LocationQuality.FAIR
            satelliteCount >= 2 && hdop < 12f -> LocationQuality.POOR
            satelliteCount < 2 || hdop >= 12f -> LocationQuality.BAD
            else -> LocationQuality.UNKNOWN
        }
    }

    /**
     * 检查定位点是否有效
     */
    fun isValid(location: LocationData): Boolean {
        val quality = evaluate(location)
        return quality != LocationQuality.BAD && quality != LocationQuality.UNKNOWN
    }

    /**
     * 检查是否发生漂移
     */
    fun isDriftDetected(
        previousLocation: LocationData,
        currentLocation: LocationData,
        maxSpeedThreshold: Float = 100f
    ): Boolean {
        if (previousLocation.latitude == 0.0 && previousLocation.longitude == 0.0) {
            return false
        }

        val distance = calculateDistance(
            previousLocation.latitude,
            previousLocation.longitude,
            currentLocation.latitude,
            currentLocation.longitude
        )

        val timeDiff = (currentLocation.time - previousLocation.time) / 1000.0
        
        if (timeDiff <= 0) return false

        val speed = distance / timeDiff
        return speed > maxSpeedThreshold
    }

    /**
     * 计算两个坐标点之间的距离（Haversine公式）
     */
    private fun calculateDistance(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /**
     * 检查是否静止
     */
    fun isStationary(
        location: LocationData,
        previousLocation: LocationData,
        threshold: Double = 0.5
    ): Boolean {
        val distance = calculateDistance(
            previousLocation.latitude,
            previousLocation.longitude,
            location.latitude,
            location.longitude
        )
        return distance < threshold && location.speed < 0.5f
    }

    /**
     * 获取质量描述文本
     */
    fun getQualityDescription(quality: LocationQuality): String {
        return when (quality) {
            LocationQuality.EXCELLENT -> "优秀 🟢"
            LocationQuality.GOOD -> "良好 🟢"
            LocationQuality.FAIR -> "一般 🟡"
            LocationQuality.POOR -> "较差 🟠"
            LocationQuality.BAD -> "很差 🔴"
            LocationQuality.UNKNOWN -> "未知 ⚪"
        }
    }

    /**
     * 获取质量颜色
     */
    fun getQualityColor(quality: LocationQuality): Int {
        return when (quality) {
            LocationQuality.EXCELLENT -> 0xFF4CAF50.toInt()
            LocationQuality.GOOD -> 0xFF8BC34A.toInt()
            LocationQuality.FAIR -> 0xFFFFC107.toInt()
            LocationQuality.POOR -> 0xFFFF9800.toInt()
            LocationQuality.BAD -> 0xFFF44336.toInt()
            LocationQuality.UNKNOWN -> 0xFF9E9E9E.toInt()
        }
    }
}
