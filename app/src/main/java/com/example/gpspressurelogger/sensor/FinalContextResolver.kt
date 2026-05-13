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
            // GPS 速度を最終的に決めるための fallback 優先度:
            //   1. 厳密窓 (`walkingSpeedWindowMs`) の値があればそのまま
            //   2. なければ広い窓 (`walkingSpeedFallbackWindowMs`) の GPS 速度を使う
            //   3. それも無く CONSTANT_MOVE 領域があれば、その平均速度を使う
            //
            // ステップ 2 を入れた背景: 車・電車の高速移動中は GPS が断続的に欠落して
            // 9 秒窓に 1 点しか取れず、歩数センサーが車内振動を歩数として誤検知して
            // W1 が立ったまま WALKING に固定されるケースがあった。
            walkingSpeed
                .withGpsGapFallback()
                .withConstantMoveFallback(
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

    /**
     * 厳密窓で GPS 速度が取れていない場合に、より広い窓 (`walkingSpeedFallbackWindowMs`)
     * で求めた GPS 速度を `gpsSpeedKmh` に流し込む。
     *
     * これにより、高速移動中の GPS 欠落で「9 秒窓内に有効ペアが揃わない → WALKING
     * 固定」になっていた経路が、30 秒窓のペアで救済される。
     */
    private fun WalkingSpeedSnapshot.withGpsGapFallback(): WalkingSpeedSnapshot {
        if (gpsSpeedKmh != null || gpsFallbackSpeedKmh == null) return this
        val difference = stepSpeedKmh?.let { kotlin.math.abs(gpsFallbackSpeedKmh - it) }
        return copy(gpsSpeedKmh = gpsFallbackSpeedKmh, differenceKmh = difference)
    }
}
