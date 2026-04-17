package com.example.gpspressurelogger.sensor

import kotlin.math.sqrt

/**
 * TYPE_LINEAR_ACCELERATION の 1 秒窓から k-status を判定する。
 */
class KStatusDetector(
    private val paramsProvider: MotionStateParamsProvider
) {
    private data class Sample(
        val timestampMs: Long,
        val norm: Float
    )

    private val samples = ArrayDeque<Sample>()
    private var currentStatus = KStatus.K1
    private var pendingStatus: KStatus? = null
    private var pendingSinceMs = 0L

    fun addSample(ax: Float, ay: Float, az: Float, timestampMs: Long) {
        val norm = sqrt(ax * ax + ay * ay + az * az)
        addNormSample(norm, timestampMs)
    }

    fun addNormSample(norm: Float, timestampMs: Long) {
        samples.addLast(Sample(timestampMs, norm))
        trimSamples(timestampMs)
    }

    fun update(timestampMs: Long): KStatusSnapshot {
        trimSamples(timestampMs)
        val params = paramsProvider.current()
        if (samples.isEmpty()) {
            return KStatusSnapshot(
                status = currentStatus,
                rawStatus = currentStatus,
                avg = null,
                variance = null,
                confidence = 0f
            )
        }

        val values = samples.map { it.norm }
        val avg = values.average().toFloat()
        val variance = values.map { value ->
            val delta = value - avg
            delta * delta
        }.average().toFloat()
        val rawStatus = when {
            avg > params.k4AvgThreshold -> KStatus.K4
            variance > params.k2k3VarThreshold -> KStatus.K2_K3
            else -> KStatus.K1
        }
        applyHysteresis(rawStatus, timestampMs)
        return KStatusSnapshot(
            status = currentStatus,
            rawStatus = rawStatus,
            avg = avg,
            variance = variance,
            confidence = confidenceFor(rawStatus, avg, variance, params)
        )
    }

    private fun trimSamples(nowMs: Long) {
        val windowStart = nowMs - paramsProvider.current().kWindowMs
        while (samples.isNotEmpty() && samples.first().timestampMs < windowStart) {
            samples.removeFirst()
        }
    }

    private fun applyHysteresis(rawStatus: KStatus, timestampMs: Long) {
        if (rawStatus == currentStatus) {
            pendingStatus = null
            pendingSinceMs = 0L
            return
        }

        if (pendingStatus != rawStatus) {
            pendingStatus = rawStatus
            pendingSinceMs = timestampMs
        }

        val params = paramsProvider.current()
        val delayMs = if (priority(rawStatus) < priority(currentStatus)) {
            params.kOffDelayMs
        } else {
            params.kOnDelayMs
        }
        if (timestampMs - pendingSinceMs >= delayMs) {
            currentStatus = rawStatus
            pendingStatus = null
            pendingSinceMs = 0L
        }
    }

    private fun priority(status: KStatus): Int = when (status) {
        KStatus.K1 -> 1
        KStatus.K2_K3 -> 2
        KStatus.K4 -> 3
    }

    private fun confidenceFor(
        status: KStatus,
        avg: Float,
        variance: Float,
        params: MotionStateParams
    ): Float = when (status) {
        KStatus.K4 -> (avg / params.k4AvgThreshold).coerceIn(0f, 3f) / 3f
        KStatus.K2_K3 -> (variance / params.k2k3VarThreshold).coerceIn(0f, 3f) / 3f
        KStatus.K1 -> (1f - (avg / params.k4AvgThreshold).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
}
