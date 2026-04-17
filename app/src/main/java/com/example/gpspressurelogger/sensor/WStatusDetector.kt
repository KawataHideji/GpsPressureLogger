package com.example.gpspressurelogger.sensor

/**
 * 判定窓内の歩数イベント有無から w-status を判定する。
 */
class WStatusDetector(
    private val paramsProvider: MotionStateParamsProvider
) {
    private data class StepSample(
        val timestampMs: Long,
        val delta: Int
    )

    private val samples = ArrayDeque<StepSample>()

    fun addStepDelta(delta: Int?, timestampMs: Long) {
        samples.addLast(StepSample(timestampMs, delta?.coerceAtLeast(0) ?: 0))
        trimSamples(timestampMs)
    }

    fun update(timestampMs: Long): WStatusSnapshot {
        trimSamples(timestampMs)
        val deltaSum = samples.sumOf { it.delta }
        val status = if (deltaSum > paramsProvider.current().wStepDeltaThreshold) {
            WStatus.W1
        } else {
            WStatus.W2
        }
        return WStatusSnapshot(status = status, stepDeltaWindow = deltaSum)
    }

    private fun trimSamples(nowMs: Long) {
        val windowStart = nowMs - paramsProvider.current().wWindowMs
        while (samples.isNotEmpty() && samples.first().timestampMs < windowStart) {
            samples.removeFirst()
        }
    }
}
