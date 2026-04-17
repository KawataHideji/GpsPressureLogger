package com.example.gpspressurelogger.sensor

/**
 * k-status と w-status から GPS 取得間隔を決める。
 */
class GpsSamplingPolicy(
    private val paramsProvider: MotionStateParamsProvider
) {
    private var stableCounter = 0

    fun update(kStatus: KStatus, wStatus: WStatus): GpsSamplingDecision {
        val params = paramsProvider.current()
        return when {
            kStatus == KStatus.K4 -> {
                stableCounter = 0
                GpsSamplingDecision(
                    intervalMs = params.gpsKMinMs,
                    immediate = true,
                    stableCounter = stableCounter
                )
            }
            wStatus == WStatus.W1 -> {
                stableCounter = 0
                GpsSamplingDecision(
                    intervalMs = params.gpsWalkIntervalMs,
                    immediate = false,
                    stableCounter = stableCounter
                )
            }
            else -> {
                val interval = (params.gpsKMinMs + params.gpsStretchStepMs * stableCounter)
                    .coerceAtMost(params.gpsStretchMaxMs)
                stableCounter += 1
                GpsSamplingDecision(
                    intervalMs = interval,
                    immediate = false,
                    stableCounter = stableCounter
                )
            }
        }
    }
}
