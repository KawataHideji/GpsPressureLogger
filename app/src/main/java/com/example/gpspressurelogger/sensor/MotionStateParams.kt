package com.example.gpspressurelogger.sensor

/**
 * ハイブリッド状態管理のパラメータを一元管理する。
 *
 * 状態管理部はしきい値や時間を直値で持たず、必ずこの現在値を参照する。
 *
 * @property baseCycleMs 状態管理を評価する基本周期。主記録スロットと同じ 3 秒を想定する。
 * @property trKWindowMs trK（加速度トリガー）の射影評価窓。短いほどGPS即時起動は速いが、端末操作にも反応しやすい。
 * @property trKDirectionWindowMs trK の基準方向 H を求める窓。射影評価窓以上にする。
 * @property trK1AvgThreshold trK1（trK静止）とみなす直近1秒射影平均の絶対値しきい値。
 * @property trKAvgThreshold trKをONにする直近1秒射影平均の絶対値の下限。下げるとGPS即時起動が増える。
 * @property trKAvgUpperThreshold trKをONにする直近1秒射影平均の絶対値の上限。強すぎる手持ち揺れを除外する。
 * @property trKRatioThreshold trKをONにする直近1秒窓の射影標準偏差比しきい値。小さいほど一定方向の水平加速度だけを拾う。
 * @property stK1AvgThreshold stK1（静止）とみなす3秒窓の平均水平加速度しきい値。
 * @property stK4AvgThreshold stK4（加速・減速・旋回）とみなす3秒窓の平均水平加速度しきい値。
 * @property stK4AvgUpperThreshold stK4（加速・減速・旋回）とみなす3秒窓の平均水平加速度上限。
 * @property stK4RatioThreshold stK4（加速・減速・旋回）とみなす3秒窓の射影標準偏差比しきい値。
 * @property stK2ScalarAvgThreshold stK2（往復振動）とみなすスカラー平均ノルムの下限。
 *   徒歩のフットストライクは個々のサンプルが大きいので scalarAvg が高くなる。
 * @property stK2VarianceThreshold stK2（揺れ・振動）とみなす3秒スロット全体のノルム分散しきい値。
 * @property wWindowMs 歩行イベント数を集計する窓。この窓内のイベント数と最終歩行イベント時刻の両方でW1/W2を判定する。
 * @property wStepDeltaThreshold 判定窓内で「歩行あり」とみなす最小イベント数。車内揺れ由来の単発検知を避けるため、既定は2歩相当。
 * @property stepResetHour StepManager 内部の日次累計を切り替える時刻。表示側の 03:00 区切りと合わせる。
 * @property walkingThresholdMs 最終歩行イベントからこの時間以内で、かつ判定窓内イベント数が足りる場合に歩行中W1とみなす。
 * @property gpsKMinMs trK ON / stK4 検知時の GPS 要求間隔。小さいほど発進・加減速を細かく追える。
 * @property gpsWalkIntervalMs w1 かつ stK4 でない徒歩時の GPS 要求間隔。徒歩軌跡を細かく残したい場合は短くする。
 * @property gpsStableInitialMs stK4 でも w1 でもない状態で、引き延ばしを開始する GPS 要求間隔。
 * @property gpsStretchStepMs stK4 でも w1 でもない状態が続くたび、GPS 間隔を段階的に伸ばす幅。
 * @property gpsStretchMaxMs stK4 でも w1 でもない状態で許す GPS 要求間隔の上限。0 なら上限なしで伸ばし続ける。
 * @property walkingSpeedWindowMs W1時に GPS 速度と歩数速度を比較する時間窓。
 * @property walkingVehicleSpeedThresholdKmh W1でもGPS速度がこの値以上なら高速移動へ倒す。
 * @property walkingStepLengthM 歩数から歩行速度を推定するための1歩あたり距離。
 * @property walkingGpsStepMismatchThresholdKmh GPS速度と歩数推定速度の差がこの値以上なら高速移動へ倒す。
 * @property staySpeedThresholdKmh 定速領域を stay と constant move に分ける速度しきい値。これ以下なら停止扱い。
 * @property constantRegionMinDurationMs 定速領域として確定判定する最小継続時間。短すぎる区間を直線近似しないための保険。
 * @property stayPointMaxRadiusM stay point として許す代表半径の目安。完全停止と通常停止を分ける場合は別パラメータ化する。
 * @property constantRegionOutlierMadMultiplier 定速領域の直線近似前に、重心からの距離が中央値より大きく外れた点を棄却する強さ。
 * @property constantRegionOutlierMinThresholdM 外れ値棄却の最低しきい値。点群が狭いときでも端末測位の通常揺れまで落としすぎないための下限。
 */
data class MotionStateParams(
    val baseCycleMs: Long = 3_000L,
    val trKWindowMs: Long = 1_000L,
    val trKDirectionWindowMs: Long = 2_000L,
    val trK1AvgThreshold: Float = 0.015f,
    val trKAvgThreshold: Float = 0.05f,
    val trKAvgUpperThreshold: Float = 0.28f,
    val trKRatioThreshold: Float = 0.65f,
    val stK1AvgThreshold: Float = 0.015f,
    val stK4AvgThreshold: Float = 0.08f,
    val stK4AvgUpperThreshold: Float = 0.28f,
    val stK4RatioThreshold: Float = 0.75f,
    val stK2ScalarAvgThreshold: Float = 0.25f,
    val stK2VarianceThreshold: Float = 0.01f,
    val wWindowMs: Long = 9_000L,
    val wStepDeltaThreshold: Int = 2,
    val stepResetHour: Int = 3,
    val walkingThresholdMs: Long = 5_000L,
    val gpsKMinMs: Long = 2_000L,
    val gpsWalkIntervalMs: Long = 5_000L,
    val gpsStableInitialMs: Long = 5_000L,
    val gpsStretchStepMs: Long = 5_000L,
    val gpsStretchMaxMs: Long = 30_000L,
    val walkingSpeedWindowMs: Long = 9_000L,
    /**
     * W1 のとき、`walkingSpeedWindowMs` 内に GPS 点が揃わなかった場合に
     * VEHICLE 昇格判定で使う「広めの GPS 速度窓」。
     * 車内振動を歩数として誤検出した W1 が長時間 WALKING で固定されてしまう
     * のを防ぐ。30 秒以内のいずれかの GPS ペアから速度を推定する。
     */
    val walkingSpeedFallbackWindowMs: Long = 30_000L,
    val walkingVehicleSpeedThresholdKmh: Double = 10.0,
    val walkingStepLengthM: Double = 0.60,
    val walkingGpsStepMismatchThresholdKmh: Double = 5.0,
    val staySpeedThresholdKmh: Double = 2.0,
    val constantRegionMinDurationMs: Long = 15_000L,
    val stayPointMaxRadiusM: Double = 20.0,
    val constantRegionOutlierMadMultiplier: Double = 4.0,
    val constantRegionOutlierMinThresholdM: Double = 50.0
) {
    init {
        require(baseCycleMs > 0L) { "baseCycleMs must be positive" }
        require(trKWindowMs > 0L) { "trKWindowMs must be positive" }
        require(trKDirectionWindowMs >= trKWindowMs) { "trKDirectionWindowMs must be >= trKWindowMs" }
        require(trK1AvgThreshold >= 0f) { "trK1AvgThreshold must be non-negative" }
        require(trKAvgThreshold > trK1AvgThreshold) { "trKAvgThreshold must be > trK1AvgThreshold" }
        require(trKAvgThreshold > 0f) { "trKAvgThreshold must be positive" }
        require(trKAvgUpperThreshold > trKAvgThreshold) { "trKAvgUpperThreshold must be > trKAvgThreshold" }
        require(trKRatioThreshold >= 0f) { "trKRatioThreshold must be non-negative" }
        require(stK1AvgThreshold >= 0f) { "stK1AvgThreshold must be non-negative" }
        require(stK4AvgThreshold > 0f) { "stK4AvgThreshold must be positive" }
        require(stK4AvgUpperThreshold > stK4AvgThreshold) {
            "stK4AvgUpperThreshold must be > stK4AvgThreshold"
        }
        require(stK4RatioThreshold >= 0f) { "stK4RatioThreshold must be non-negative" }
        require(stK2ScalarAvgThreshold > 0f) { "stK2ScalarAvgThreshold must be positive" }
        require(stK2VarianceThreshold > 0f) { "stK2VarianceThreshold must be positive" }
        require(wWindowMs > 0L) { "wWindowMs must be positive" }
        require(wStepDeltaThreshold >= 0) { "wStepDeltaThreshold must be non-negative" }
        require(stepResetHour in 0..23) { "stepResetHour must be in 0..23" }
        require(walkingThresholdMs >= 0L) { "walkingThresholdMs must be non-negative" }
        require(gpsKMinMs > 0L) { "gpsKMinMs must be positive" }
        require(gpsWalkIntervalMs > 0L) { "gpsWalkIntervalMs must be positive" }
        require(gpsStableInitialMs > 0L) { "gpsStableInitialMs must be positive" }
        require(gpsStretchStepMs >= 0L) { "gpsStretchStepMs must be non-negative" }
        require(gpsStretchMaxMs == 0L || gpsStretchMaxMs >= gpsStableInitialMs) {
            "gpsStretchMaxMs must be 0 or >= gpsStableInitialMs"
        }
        require(walkingSpeedWindowMs > 0L) { "walkingSpeedWindowMs must be positive" }
        require(walkingSpeedFallbackWindowMs >= walkingSpeedWindowMs) {
            "walkingSpeedFallbackWindowMs must be >= walkingSpeedWindowMs"
        }
        require(walkingVehicleSpeedThresholdKmh >= 0.0) { "walkingVehicleSpeedThresholdKmh must be non-negative" }
        require(walkingStepLengthM > 0.0) { "walkingStepLengthM must be positive" }
        require(walkingGpsStepMismatchThresholdKmh >= 0.0) {
            "walkingGpsStepMismatchThresholdKmh must be non-negative"
        }
        require(staySpeedThresholdKmh >= 0.0) { "staySpeedThresholdKmh must be non-negative" }
        require(constantRegionMinDurationMs >= 0L) { "constantRegionMinDurationMs must be non-negative" }
        require(stayPointMaxRadiusM >= 0.0) { "stayPointMaxRadiusM must be non-negative" }
        require(constantRegionOutlierMadMultiplier >= 0.0) { "constantRegionOutlierMadMultiplier must be non-negative" }
        require(constantRegionOutlierMinThresholdM >= 0.0) { "constantRegionOutlierMinThresholdM must be non-negative" }
    }
}

fun interface MotionStateParamsProvider {
    fun current(): MotionStateParams
}

class StaticMotionStateParamsProvider(
    private val params: MotionStateParams = MotionStateParams()
) : MotionStateParamsProvider {
    override fun current(): MotionStateParams = params
}
