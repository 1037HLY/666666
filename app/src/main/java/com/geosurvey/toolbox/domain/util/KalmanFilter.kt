package com.geosurvey.toolbox.domain.util

/**
 * 1D Kalman滤波器
 */
class KalmanFilter(
    private val processNoise: Double = 0.01,
    private val measurementNoise: Double = 10.0,
    private val initialEstimate: Double = 0.0
) {
    private var estimate = initialEstimate
    private var errorCovariance = 1.0

    fun update(measurement: Double): Double {
        val predictedEstimate = estimate
        val predictedError = errorCovariance + processNoise

        val kalmanGain = predictedError / (predictedError + measurementNoise)
        estimate = predictedEstimate + kalmanGain * (measurement - predictedEstimate)
        errorCovariance = (1 - kalmanGain) * predictedError

        return estimate
    }

    fun getEstimate(): Double = estimate

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

    fun update(lat: Double, lng: Double): Pair<Double, Double> {
        return Pair(
            latFilter.update(lat),
            lngFilter.update(lng)
        )
    }

    fun reset(lat: Double = 0.0, lng: Double = 0.0) {
        latFilter.reset(lat)
        lngFilter.reset(lng)
    }
}
