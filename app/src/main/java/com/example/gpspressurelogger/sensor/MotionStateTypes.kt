package com.example.gpspressurelogger.sensor

import com.example.gpspressurelogger.sensor.MovementDetector.Mode

enum class TrKStatus {
    OFF,
    ON
}

enum class StKStatus {
    STK1,
    STK2,
    STK4;

    fun toLegacyName(): String = when (this) {
        STK1 -> "K1"
        STK2 -> "K2_K3"
        STK4 -> "K4"
    }

    companion object {
        fun fromStored(value: String?): StKStatus? = when (value) {
            "STK1", "K1" -> STK1
            "STK2", "K2_K3" -> STK2
            "STK4", "K4" -> STK4
            else -> null
        }
    }
}

enum class AccelCoordinateSource {
    WORLD_ROTATION_VECTOR,
    WORLD_ACCEL_MAG,
    DEVICE,
    NORM_ONLY
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

enum class GpsAggregationMode {
    MOVING,
    DEVICE_STILL,
    STOPPED,
    UNKNOWN
}

data class TrKSnapshot(
    val timestampMs: Long,
    val status: TrKStatus,
    val rawStatus: TrKStatus,
    val avg: Float?,
    val scalarAvg: Float? = null,
    val directionalityRatio: Float? = null,
    val maxMagnitude: Float?,
    val horizontalMaxMagnitude: Float? = null,
    val source: AccelCoordinateSource,
    val confidence: Float
)

data class StKSnapshot(
    val timestampMs: Long,
    val status: StKStatus,
    val rawStatus: StKStatus,
    /** 平均水平加速度ベクトルの大きさ k = |average(h_i)|。 */
    val avg: Float?,
    /** stK2 判定用のスカラー平均ノルム = mean(|a_i|)。 */
    val scalarAvg: Float? = null,
    /** 射影列の標準偏差比 sigma / k。 */
    val directionalityRatio: Float? = null,
    val variance: Float?,
    val maxMagnitude: Float?,
    val source: AccelCoordinateSource,
    val confidence: Float
)

data class WStatusSnapshot(
    val status: WStatus,
    val stepDeltaWindow: Int
)

data class GpsSamplingDecision(
    val intervalMs: Long,
    val immediate: Boolean,
    val stableCounter: Long
)

data class WalkingSpeedSnapshot(
    val gpsSpeedKmh: Double?,
    val stepSpeedKmh: Double?,
    val differenceKmh: Double?
)

data class MotionGpsPoint(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Float? = null
)

/**
 * 状態管理に使用する GPS 点として有効かを検証する。
 *
 * 以下の点を除外する:
 * - 緯度・経度がともにゼロの欠損点
 * - accuracy が無効値（0 以下）の点
 * - 直前の受理点と同一タイムスタンプ（重複フィックス）または逆順の点
 * - 直前の受理点と緯度・経度が完全一致する点（ハードウェアキャッシュの再配信）
 *
 * gpsPool（LogEntry 記録用）には適用しない。GPS 取得タイミングの判定は
 * gpsPool 側に基づくため、この検証で GPS 取得間隔は変化しない。
 *
 * @param last 直前に受理した GPS 点。初回または前回がない場合は null。
 */
fun MotionGpsPoint.isValidNext(last: MotionGpsPoint?): Boolean {
    if (latitude == 0.0 && longitude == 0.0) return false
    if (accuracy != null && accuracy <= 0f) return false
    if (last != null) {
        if (timestampMs <= last.timestampMs) return false
        if (latitude == last.latitude && longitude == last.longitude) return false
    }
    return true
}

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
    val trK: TrKSnapshot,
    val stK: StKSnapshot,
    val wStatus: WStatusSnapshot,
    val walkingSpeed: WalkingSpeedSnapshot,
    val gpsSampling: GpsSamplingDecision,
    val gpsAggregationMode: GpsAggregationMode,
    val finalMode: Mode,
    val finalModeConfirmed: Boolean,
    val activeConstantRegion: Boolean,
    val activeRegionEstimate: ConstantRegionResult?,
    val completedRegion: ConstantRegionResult?
)
