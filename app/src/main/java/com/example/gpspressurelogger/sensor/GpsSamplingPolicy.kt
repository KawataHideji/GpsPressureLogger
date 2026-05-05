package com.example.gpspressurelogger.sensor

/**
 * stK と w-status から GPS 取得間隔を決める。
 */
class GpsSamplingPolicy(
    private val paramsProvider: MotionStateParamsProvider
) {
    private var stableCounter = 0L
    private var wasAccelerating = false
    private var lastDecisionWasStable = false

    fun update(
        stK: StKStatus,
        wStatus: WStatus,
        accelerationTriggered: Boolean = false
    ): GpsSamplingDecision {
        val params = paramsProvider.current()
        val accelerating = accelerationTriggered || stK == StKStatus.STK4
        return when {
            accelerating -> {
                val enteredAcceleration = accelerationTriggered || !wasAccelerating
                wasAccelerating = true
                lastDecisionWasStable = false
                stableCounter = 0
                GpsSamplingDecision(
                    intervalMs = params.gpsKMinMs,
                    immediate = enteredAcceleration,
                    stableCounter = stableCounter
                )
            }
            wStatus == WStatus.W1 -> {
                wasAccelerating = false
                lastDecisionWasStable = false
                stableCounter = 0
                GpsSamplingDecision(
                    intervalMs = params.gpsWalkIntervalMs,
                    immediate = false,
                    stableCounter = stableCounter
                )
            }
            else -> {
                wasAccelerating = false
                lastDecisionWasStable = true
                val interval = computeStableInterval(params)
                GpsSamplingDecision(
                    intervalMs = interval,
                    immediate = false,
                    stableCounter = stableCounter
                )
            }
        }
    }

    /**
     * 直前の安定系GPS要求が実際に採用可能な位置へ到達したかを反映する。
     * 欠損や低精度では伸長を進めず、次回は安定系の初期間隔からやり直す。
     */
    fun recordGpsResult(accepted: Boolean) {
        if (!lastDecisionWasStable) return
        stableCounter = if (accepted) {
            addStretchCounterSafely(stableCounter)
        } else {
            0L
        }
    }

    private fun computeStableInterval(params: MotionStateParams): Long {
        val stretched = addStretchSafely(
            initialMs = params.gpsStableInitialMs,
            stepMs = params.gpsStretchStepMs,
            count = stableCounter
        )
        return if (params.gpsStretchMaxMs == 0L) {
            stretched
        } else {
            stretched.coerceAtMost(params.gpsStretchMaxMs)
        }
    }

    private fun addStretchSafely(initialMs: Long, stepMs: Long, count: Long): Long {
        if (stepMs == 0L || count == 0L) return initialMs
        val maxSafeCount = (Long.MAX_VALUE - initialMs) / stepMs
        if (count >= maxSafeCount) return Long.MAX_VALUE
        return initialMs + stepMs * count
    }

    private fun addStretchCounterSafely(count: Long): Long =
        if (count == Long.MAX_VALUE) Long.MAX_VALUE else count + 1L
}
