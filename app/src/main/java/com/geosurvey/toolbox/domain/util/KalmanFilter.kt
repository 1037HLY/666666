package com.geosurvey.toolbox.domain.util

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 1D Kalman滤波器
 * 用于平滑定位数据
 */
class KalmanFilter(
    private val processNoise: Double = 0.01,  // 过程噪声
    private val measurementNoise: Double = 10.0, // 测量噪声
    private val initialEstimate: Double = 0.0
) {
    private var estimate = initialEstimate
    private var errorCovariance = 1.0

    /**
     * 更新滤波器
     * @param measurement 测量值
     * @return 滤波后的估计值
     */
    fun update(measurement: Double): Double {
        // 预测步骤
        val predictedEstimate = estimate
        val predictedError = errorCovariance + processNoise

        // 更新步骤
        val kalmanGain = predictedError / (predictedError + measurementNoise)
        estimate = predictedEstimate + kalmanGain * (measurement - predictedEstimate)
        errorCovariance = (1 - kalmanGain) * predictedError

        return estimate
    }

    /**
     * 获取当前估计值
     */
    fun getEstimate(): Double = estimate

    /**
     * 重置滤波器
     */
    fun reset(value: Double = 0.0) {
        estimate = value
        errorCovariance = 1.0
    }
}

/**
 * 2D Kalman滤波器（用于经纬度）
 */
class KalmanFilter2D(
    private val processNoise: Double = 0.01,
    private val measurementNoise: Double = 10.0
) {
    private val latFilter = KalmanFilter(processNoise, measurementNoise)
    private val lngFilter = KalmanFilter(processNoise, measurementNoise)

    /**
     * 更新滤波器
     * @param lat 纬度测量值
     * @param lng 经度测量值
     * @return Pair(滤波后的纬度, 滤波后的经度)
     */
    fun update(lat: Double, lng: Double): Pair<Double, Double> {
        return Pair(
            latFilter.update(lat),
            lngFilter.update(lng)
        )
    }

    /**
     * 重置滤波器
     */
    fun reset(lat: Double = 0.0, lng: Double = 0.0) {
        latFilter.reset(lat)
        lngFilter.reset(lng)
    }
}
