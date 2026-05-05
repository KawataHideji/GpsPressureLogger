package com.example.gpspressurelogger.sensor

import com.example.gpspressurelogger.sensor.MovementDetector.Mode

/**
 * 新方式の状態管理を束ねる司令塔。
 *
 * LoggingService から専用 single-thread dispatcher 上で呼ばれ、加速度・歩数・GPS・base cycle の
 * 入力を一箇所で状態化する。
 */
class MotionStateManager(
    private val paramsProvider: MotionStateParamsProvider = StaticMotionStateParamsProvider(),
    private val onStepCountUp: (delta: Int, total: Int, timestampMs: Long) -> Unit = { _, _, _ -> },
    private val onStepReset: () -> Unit = {},
    private val onTrKChanged: (TrKSnapshot) -> Unit = {}
) {
    private val stepManager = StepManager(
        paramsProvider = paramsProvider,
        onStepCountUp = { delta, total, timestampMs ->
            onStepCountUp(delta, total, timestampMs)
        },
        onTimeReset = onStepReset
    )
    private val accelManager = AccelManager(paramsProvider, onTrKChanged)

    private val gpsSamplingPolicy = GpsSamplingPolicy(paramsProvider)
    private val gpsSpeedTracker = GpsSpeedTracker(paramsProvider)
    private val constantRegionTracker = ConstantRegionTracker(paramsProvider)
    private val finalContextResolver = FinalContextResolver()

    private var currentFinalMode = Mode.UNKNOWN

    /** 状態管理に受理した直近の GPS 点。重複・欠損フィルタの基準として使用する。 */
    private var lastAcceptedGpsPoint: MotionGpsPoint? = null

    fun addLinearAccelerationSample(ax: Float, ay: Float, az: Float, timestampMs: Long) {
        accelManager.addLinearAccelerationSample(ax, ay, az, timestampMs)
    }

    fun updateRotationVector(values: FloatArray) {
        accelManager.updateRotationVector(values)
    }

    fun updateOrientationAcceleration(ax: Float, ay: Float, az: Float) {
        accelManager.updateOrientationAcceleration(ax, ay, az)
    }

    fun updateMagneticField(x: Float, y: Float, z: Float) {
        accelManager.updateMagneticField(x, y, z)
    }

    fun addAccelerationNormSample(norm: Float, timestampMs: Long) {
        accelManager.addAccelerationNormSample(norm, timestampMs)
    }

    fun addStepDetectorEvent(timestampMs: Long) {
        stepManager.addStepDetectorEvent(timestampMs)
    }

    fun addStepCounterTotal(total: Int, timestampMs: Long) {
        stepManager.addStepCounterTotal(total, timestampMs)
    }

    fun setStepDetectorAvailable(available: Boolean) {
        stepManager.setStepDetectorAvailable(available)
    }

    fun consumeStepDeltaForSlot(): Int = stepManager.pullSlotDelta()

    /**
     * GPS 点を状態管理に投入する。
     *
     * 欠損点・重複点・逆順点は [MotionGpsPoint.isValidNext] で除外する。
     * 除外しても gpsPool（LogEntry 記録・GPS 取得タイミング判定の元データ）には
     * 影響しないため、GPS 取得間隔は変化しない。
     */
    fun addGpsPoint(point: MotionGpsPoint) {
        if (!point.isValidNext(lastAcceptedGpsPoint)) return
        lastAcceptedGpsPoint = point
        constantRegionTracker.addGpsPoint(point)
        gpsSpeedTracker.addPoint(point)
    }

    fun recordGpsSamplingResult(accepted: Boolean) {
        gpsSamplingPolicy.recordGpsResult(accepted)
    }

    fun updateBaseCycle(timestampMs: Long): MotionStateSnapshot {
        val trKSnapshot = accelManager.snapshotTrK(timestampMs)
        val stKSnapshot = accelManager.consumeStKSnapshot(timestampMs)
        val wSnapshot = stepManager.getWStatusSnapshot(timestampMs)
        val completedRegion = constantRegionTracker.update(
            timestampMs = timestampMs,
            stK = stKSnapshot.status,
            wStatus = wSnapshot.status
        )
        val activeRegionEstimate = constantRegionTracker.latestActiveEstimate()
        val walkingSpeed = createWalkingSpeedSnapshot(timestampMs, wSnapshot)
        val accelerationTriggered = accelManager.consumeTrKImmediateForSlot()
        val policyGpsDecision = gpsSamplingPolicy.update(
            stK = stKSnapshot.status,
            wStatus = wSnapshot.status,
            accelerationTriggered = accelerationTriggered
        )
        val finalModeConfirmed = isFinalModeConfirmed(
            stK = stKSnapshot.status,
            wStatus = wSnapshot.status,
            completedRegion = completedRegion
        )
        currentFinalMode = finalContextResolver.resolve(
            stK = stKSnapshot.status,
            wStatus = wSnapshot,
            walkingSpeed = walkingSpeed,
            activeRegion = constantRegionTracker.isActive(),
            activeRegionEstimate = activeRegionEstimate,
            completedRegion = completedRegion,
            previousMode = currentFinalMode,
            params = paramsProvider.current()
        )
        val gpsAggregationMode = resolveGpsAggregationMode(
            stK = stKSnapshot.status,
            wStatus = wSnapshot.status,
            activeRegion = constantRegionTracker.isActive(),
            activeRegionEstimate = activeRegionEstimate,
            completedRegion = completedRegion
        )
        return MotionStateSnapshot(
            timestampMs = timestampMs,
            trK = trKSnapshot,
            stK = stKSnapshot,
            wStatus = wSnapshot,
            walkingSpeed = walkingSpeed,
            gpsSampling = policyGpsDecision,
            gpsAggregationMode = gpsAggregationMode,
            finalMode = currentFinalMode,
            finalModeConfirmed = finalModeConfirmed,
            activeConstantRegion = constantRegionTracker.isActive(),
            activeRegionEstimate = activeRegionEstimate,
            completedRegion = completedRegion
        )
    }

    fun currentMode(): Mode = currentFinalMode

    private fun createWalkingSpeedSnapshot(timestampMs: Long, wSnapshot: WStatusSnapshot): WalkingSpeedSnapshot {
        val params = paramsProvider.current()
        val gpsSpeed = gpsSpeedTracker.speedKmh(timestampMs)
        val stepSpeed = if (wSnapshot.status == WStatus.W1) {
            val windowSec = params.walkingSpeedWindowMs / 1000.0
            if (windowSec > 0.0) {
                wSnapshot.stepDeltaWindow * params.walkingStepLengthM / windowSec * 3.6
            } else {
                null
            }
        } else {
            null
        }
        return WalkingSpeedSnapshot(
            gpsSpeedKmh = gpsSpeed,
            stepSpeedKmh = stepSpeed,
            differenceKmh = if (gpsSpeed != null && stepSpeed != null) kotlin.math.abs(gpsSpeed - stepSpeed) else null
        )
    }

    private fun resolveGpsAggregationMode(
        stK: StKStatus,
        wStatus: WStatus,
        activeRegion: Boolean,
        activeRegionEstimate: ConstantRegionResult?,
        completedRegion: ConstantRegionResult?
    ): GpsAggregationMode = when {
        stK == StKStatus.STK4 -> GpsAggregationMode.MOVING
        wStatus == WStatus.W1 -> GpsAggregationMode.MOVING
        completedRegion?.kind == ConstantRegionKind.CONSTANT_MOVE -> GpsAggregationMode.MOVING
        activeRegionEstimate?.kind == ConstantRegionKind.CONSTANT_MOVE -> GpsAggregationMode.MOVING
        completedRegion?.kind == ConstantRegionKind.STAY && stK == StKStatus.STK1 -> GpsAggregationMode.DEVICE_STILL
        completedRegion?.kind == ConstantRegionKind.STAY -> GpsAggregationMode.STOPPED
        activeRegionEstimate?.kind == ConstantRegionKind.STAY && stK == StKStatus.STK1 -> GpsAggregationMode.DEVICE_STILL
        activeRegionEstimate?.kind == ConstantRegionKind.STAY -> GpsAggregationMode.STOPPED
        activeRegion && stK == StKStatus.STK1 -> GpsAggregationMode.DEVICE_STILL
        activeRegion -> GpsAggregationMode.STOPPED
        stK == StKStatus.STK1 -> GpsAggregationMode.DEVICE_STILL
        stK == StKStatus.STK2 -> GpsAggregationMode.STOPPED
        else -> GpsAggregationMode.UNKNOWN
    }

    private fun isFinalModeConfirmed(
        stK: StKStatus,
        wStatus: WStatus,
        completedRegion: ConstantRegionResult?
    ): Boolean =
        stK == StKStatus.STK4 ||
            wStatus == WStatus.W1 ||
            completedRegion?.kind == ConstantRegionKind.STAY ||
            completedRegion?.kind == ConstantRegionKind.CONSTANT_MOVE
}
