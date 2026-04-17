package com.example.gpspressurelogger.sensor

import com.example.gpspressurelogger.sensor.MovementDetector.Mode

/**
 * 新方式の状態管理を束ねる司令塔。
 *
 * LoggingService から専用 single-thread dispatcher 上で呼ばれ、加速度・歩数・GPS・base cycle の
 * 入力を一箇所で状態化する。
 */
class MotionStateManager(
    private val paramsProvider: MotionStateParamsProvider = StaticMotionStateParamsProvider()
) {
    private val kStatusDetector = KStatusDetector(paramsProvider)
    private val wStatusDetector = WStatusDetector(paramsProvider)
    private val gpsSamplingPolicy = GpsSamplingPolicy(paramsProvider)
    private val constantRegionTracker = ConstantRegionTracker(paramsProvider)
    private val finalContextResolver = FinalContextResolver()

    private var currentFinalMode = Mode.UNKNOWN

    fun addLinearAccelerationSample(ax: Float, ay: Float, az: Float, timestampMs: Long) {
        kStatusDetector.addSample(ax, ay, az, timestampMs)
    }

    fun addAccelerationNormSample(norm: Float, timestampMs: Long) {
        kStatusDetector.addNormSample(norm.coerceAtLeast(0f), timestampMs)
    }

    fun addStepDelta(delta: Int?, timestampMs: Long) {
        wStatusDetector.addStepDelta(delta, timestampMs)
    }

    fun addGpsPoint(point: MotionGpsPoint) {
        constantRegionTracker.addGpsPoint(point)
    }

    fun updateBaseCycle(timestampMs: Long): MotionStateSnapshot {
        val kSnapshot = kStatusDetector.update(timestampMs)
        val wSnapshot = wStatusDetector.update(timestampMs)
        val completedRegion = constantRegionTracker.update(
            timestampMs = timestampMs,
            kStatus = kSnapshot.status,
            wStatus = wSnapshot.status
        )
        val activeRegionEstimate = constantRegionTracker.latestActiveEstimate()
        val gpsDecision = gpsSamplingPolicy.update(kSnapshot.status, wSnapshot.status)
        currentFinalMode = finalContextResolver.resolve(
            kStatus = kSnapshot.status,
            wStatus = wSnapshot.status,
            activeRegion = constantRegionTracker.isActive(),
            activeRegionEstimate = activeRegionEstimate,
            completedRegion = completedRegion,
            previousMode = currentFinalMode
        )
        return MotionStateSnapshot(
            timestampMs = timestampMs,
            kStatus = kSnapshot,
            wStatus = wSnapshot,
            gpsSampling = gpsDecision,
            finalMode = currentFinalMode,
            activeConstantRegion = constantRegionTracker.isActive(),
            activeRegionEstimate = activeRegionEstimate,
            completedRegion = completedRegion
        )
    }

    fun currentMode(): Mode = currentFinalMode
}
