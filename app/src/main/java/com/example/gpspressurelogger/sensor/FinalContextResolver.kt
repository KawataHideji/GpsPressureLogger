package com.example.gpspressurelogger.sensor

import com.example.gpspressurelogger.sensor.MovementDetector.Mode

/**
 * k-status / w-status / 定速領域結果から、既存アプリの4状態へ変換する。
 */
class FinalContextResolver {
    fun resolve(
        kStatus: KStatus,
        wStatus: WStatus,
        activeRegion: Boolean,
        activeRegionEstimate: ConstantRegionResult?,
        completedRegion: ConstantRegionResult?,
        previousMode: Mode
    ): Mode = when {
        kStatus == KStatus.K4 -> Mode.VEHICLE
        wStatus == WStatus.W1 -> Mode.WALKING
        completedRegion?.kind == ConstantRegionKind.CONSTANT_MOVE -> Mode.VEHICLE
        activeRegionEstimate?.kind == ConstantRegionKind.CONSTANT_MOVE -> Mode.VEHICLE
        completedRegion?.kind == ConstantRegionKind.STAY && kStatus == KStatus.K1 -> Mode.DEVICE_STILL
        completedRegion?.kind == ConstantRegionKind.STAY -> Mode.STOPPED
        activeRegionEstimate?.kind == ConstantRegionKind.STAY && kStatus == KStatus.K1 -> Mode.DEVICE_STILL
        activeRegionEstimate?.kind == ConstantRegionKind.STAY -> Mode.STOPPED
        activeRegion && kStatus == KStatus.K1 -> Mode.DEVICE_STILL
        activeRegion -> Mode.STOPPED
        kStatus == KStatus.K1 -> Mode.DEVICE_STILL
        kStatus == KStatus.K2_K3 -> Mode.STOPPED
        else -> previousMode
    }
}
