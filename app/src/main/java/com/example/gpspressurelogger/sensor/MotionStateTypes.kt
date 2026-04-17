package com.example.gpspressurelogger.sensor

import com.example.gpspressurelogger.sensor.MovementDetector.Mode

enum class KStatus {
    K1,
    K2_K3,
    K4
}

enum class WStatus {
    W1,
    W2
}

enum class ConstantRegionKind {
    NONE,
    STAY,
    CONSTANT_MOVE
}

data class KStatusSnapshot(
    val status: KStatus,
    val rawStatus: KStatus,
    val avg: Float?,
    val variance: Float?,
    val confidence: Float
)

data class WStatusSnapshot(
    val status: WStatus,
    val stepDeltaWindow: Int
)

data class GpsSamplingDecision(
    val intervalMs: Long,
    val immediate: Boolean,
    val stableCounter: Int
)

data class MotionGpsPoint(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Float? = null
)

data class ConstantRegionResult(
    val kind: ConstantRegionKind,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val startPoint: MotionGpsPoint?,
    val endPoint: MotionGpsPoint?,
    val stayPoint: MotionGpsPoint?,
    val averageSpeedKmh: Double,
    val directionDeg: Double?
)

data class MotionStateSnapshot(
    val timestampMs: Long,
    val kStatus: KStatusSnapshot,
    val wStatus: WStatusSnapshot,
    val gpsSampling: GpsSamplingDecision,
    val finalMode: Mode,
    val activeConstantRegion: Boolean,
    val activeRegionEstimate: ConstantRegionResult?,
    val completedRegion: ConstantRegionResult?
)
