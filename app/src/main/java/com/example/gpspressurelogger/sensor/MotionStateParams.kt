package com.example.gpspressurelogger.sensor

/**
 * ハイブリッド状態管理のパラメータを一元管理する。
 *
 * 状態管理部はしきい値や時間を直値で持たず、必ずこの現在値を参照する。
 *
 * @property baseCycleMs 状態管理を評価する基本周期。主記録スロットと同じ 3 秒を想定する。
 * @property kWindowMs k-status 判定に使う加速度サンプルの解析窓。短いほど反応は速いがノイズに弱い。
 * @property k4AvgThreshold 高加速 k4 とみなす平均加速度しきい値。下げると車両発進などを拾いやすくなるが、徒歩の大きな動きも拾いやすい。
 * @property k2k3VarThreshold 振動・揺れ k2/k3 とみなす分散しきい値。下げると細かな揺れを検知しやすくなるが、静止ノイズにも反応しやすい。
 * @property kOnDelayMs 新しい k-status 候補が続いたとき、確定へ進めるまで待つ時間。大きいほど誤検知に強く、反応は遅くなる。
 * @property kOffDelayMs 現在の k-status 条件から外れても状態を維持する時間。大きいほどチャタリングを抑え、戻りは遅くなる。
 * @property wWindowMs 歩行あり w1 / 歩行なし w2 を見る歩数判定窓。歩数センサー遅延を吸収したい場合は長めにする。
 * @property wStepDeltaThreshold 判定窓内で「歩行あり」とみなす最小歩数増分。0 なら 1 歩以上で w1 になる。
 * @property gpsKMinMs k4 検知時や引き延ばし開始時の GPS 要求間隔。小さいほど発進・加減速を細かく追える。
 * @property gpsWalkIntervalMs w1 かつ k4 でない徒歩時の GPS 要求間隔。徒歩軌跡を細かく残したい場合は短くする。
 * @property gpsStretchStepMs w2 かつ k4 でない安定状態で、GPS 間隔を段階的に伸ばす幅。
 * @property gpsStretchMaxMs w2 かつ k4 でない安定状態で許す GPS 要求間隔の上限。大きいほど省電力、定速判定の点数は減る。
 * @property staySpeedThresholdKmh 定速領域を stay と constant move に分ける速度しきい値。これ以下なら停止扱い。
 * @property constantRegionMinDurationMs 定速領域として確定判定する最小継続時間。短すぎる区間を直線近似しないための保険。
 * @property stayPointMaxRadiusM stay point として許す代表半径の目安。完全停止と通常停止を分ける場合は別パラメータ化する。
 * @property constantRegionOutlierMadMultiplier 定速領域の直線近似前に、重心からの距離が中央値より大きく外れた点を棄却する強さ。
 * @property constantRegionOutlierMinThresholdM 外れ値棄却の最低しきい値。点群が狭いときでも端末測位の通常揺れまで落としすぎないための下限。
 */
data class MotionStateParams(
    val baseCycleMs: Long = 3_000L,
    val kWindowMs: Long = 1_000L,
    val k4AvgThreshold: Float = 0.70f,
    val k2k3VarThreshold: Float = 0.01f,
    val kOnDelayMs: Long = 500L,
    val kOffDelayMs: Long = 1_000L,
    val wWindowMs: Long = 9_000L,
    val wStepDeltaThreshold: Int = 0,
    val gpsKMinMs: Long = 5_000L,
    val gpsWalkIntervalMs: Long = 5_000L,
    val gpsStretchStepMs: Long = 5_000L,
    val gpsStretchMaxMs: Long = 15_000L,
    val staySpeedThresholdKmh: Double = 2.0,
    val constantRegionMinDurationMs: Long = 15_000L,
    val stayPointMaxRadiusM: Double = 20.0,
    val constantRegionOutlierMadMultiplier: Double = 4.0,
    val constantRegionOutlierMinThresholdM: Double = 50.0
) {
    init {
        require(baseCycleMs > 0L) { "baseCycleMs must be positive" }
        require(kWindowMs > 0L) { "kWindowMs must be positive" }
        require(kOnDelayMs >= 0L) { "kOnDelayMs must be non-negative" }
        require(kOffDelayMs >= 0L) { "kOffDelayMs must be non-negative" }
        require(wWindowMs > 0L) { "wWindowMs must be positive" }
        require(gpsKMinMs > 0L) { "gpsKMinMs must be positive" }
        require(gpsWalkIntervalMs > 0L) { "gpsWalkIntervalMs must be positive" }
        require(gpsStretchStepMs >= 0L) { "gpsStretchStepMs must be non-negative" }
        require(gpsStretchMaxMs >= gpsKMinMs) { "gpsStretchMaxMs must be >= gpsKMinMs" }
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
