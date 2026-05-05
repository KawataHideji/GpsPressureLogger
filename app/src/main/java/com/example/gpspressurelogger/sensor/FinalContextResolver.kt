package com.example.gpspressurelogger.sensor

import com.example.gpspressurelogger.sensor.MovementDetector.Mode

/**
 * stK / w-status / 歩行速度比較 / 定速領域結果から、既存アプリの4状態へ変換する。
 */
class FinalContextResolver {
    fun resolve(
        stK: StKStatus,
        wStatus: WStatusSnapshot,
        walkingSpeed: WalkingSpeedSnapshot,
        activeRegion: Boolean,
        activeRegionEstimate: ConstantRegionResult?,
        completedRegion: ConstantRegionResult?,
        previousMode: Mode,
        params: MotionStateParams
    ): Mode = when {
        stK == StKStatus.STK4 && wStatus.status == WStatus.W2 -> Mode.VEHICLE
        wStatus.status == WStatus.W1 -> resolveWalkingMode(
            walkingSpeed.withConstantMoveFallback(
                completedRegion?.constantMoveSpeedKmh() ?: activeRegionEstimate?.constantMoveSpeedKmh()
            ),
            params
        )
        completedRegion?.kind == ConstantRegionKind.CONSTANT_MOVE -> Mode.VEHICLE
        activeRegionEstimate?.kind == ConstantRegionKind.CONSTANT_MOVE -> Mode.VEHICLE
        completedRegion?.kind == ConstantRegionKind.STAY && stK == StKStatus.STK1 -> Mode.DEVICE_STILL
        completedRegion?.kind == ConstantRegionKind.STAY -> Mode.STOPPED
        activeRegionEstimate?.kind == ConstantRegionKind.STAY && stK == StKStatus.STK1 -> Mode.DEVICE_STILL
        activeRegionEstimate?.kind == ConstantRegionKind.STAY -> Mode.STOPPED
        activeRegion && stK == StKStatus.STK1 -> Mode.DEVICE_STILL
        activeRegion -> Mode.STOPPED
        stK == StKStatus.STK1 -> Mode.DEVICE_STILL
        stK == StKStatus.STK2 -> Mode.STOPPED
        else -> previousMode
    }

    companion object {
        fun resolveWalkingMode(walkingSpeed: WalkingSpeedSnapshot, params: MotionStateParams): Mode {
            val gpsSpeed = walkingSpeed.gpsSpeedKmh
            val stepSpeed = walkingSpeed.stepSpeedKmh
            return when {
                gpsSpeed != null && gpsSpeed >= params.walkingVehicleSpeedThresholdKmh -> Mode.VEHICLE
                gpsSpeed != null &&
                    stepSpeed != null &&
                    kotlin.math.abs(gpsSpeed - stepSpeed) >= params.walkingGpsStepMismatchThresholdKmh -> Mode.VEHICLE
                else -> Mode.WALKING
            }
        }
    }

    private fun ConstantRegionResult.constantMoveSpeedKmh(): Double? =
        averageSpeedKmh.takeIf { kind == ConstantRegionKind.CONSTANT_MOVE && it.isFinite() }

    private fun WalkingSpeedSnapshot.withConstantMoveFallback(fallbackSpeedKmh: Double?): WalkingSpeedSnapshot {
        if (gpsSpeedKmh != null || fallbackSpeedKmh == null) return this
        val difference = stepSpeedKmh?.let { kotlin.math.abs(fallbackSpeedKmh - it) }
        return copy(gpsSpeedKmh = fallbackSpeedKmh, differenceKmh = difference)
    }
}
