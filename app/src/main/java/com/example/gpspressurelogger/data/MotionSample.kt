package com.example.gpspressurelogger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 状態遷移（mode 確定 / stK / trK / W 変化、定速領域開始・終了）を記録する。
 *
 * 画面再現に必要十分な最小フィールド構成。診断値（kAvg, kVariance, trKAvg, ...）は
 * [com.example.gpspressurelogger.util.RawSensorWriter] が ON のときに別ファイル
 * （`raw_*.csv.gz`）に生センサー値とともに記録する設計。
 */
@Entity(
    tableName = "motion_samples",
    indices = [Index(value = ["timestamp"], unique = true)]
)
data class MotionSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    /** stK 4 状態 (STK1/STK2/STK4)。AC ラベル・provisional mode に必要。 */
    val stKStatus: String? = null,
    /** trK 状態 (TRK1/TRK4)。tON ラベルに必要。 */
    val trKStatus: String? = null,
    /** 歩行判定 (W1/W2)。provisional mode に必要。 */
    val wStatus: String? = null,
    /** 直近 W 窓内の歩数差分。provisional VEHICLE 判定に必要。 */
    val stepDeltaWindow: Int? = null,
    /** trK4 遷移で GPS 即時取得を要求した行。tON ラベル trigger に必要。 */
    val gpsImmediate: Boolean? = null,
    /** 確定済み display mode (DEVICE_STILL/STOPPED/WALKING/VEHICLE)。地図色分けの確定キー。 */
    val confirmedMode: String? = null,
    /** 定速領域種別 (STAY/CONSTANT_MOVE/NONE)。STAY/CMOV ラベルに必要。 */
    val constantRegionKind: String? = null,
    /** 定速領域の平均速度。GPS 欠落時の VEHICLE 判定 fallback に必要。 */
    val constantRegionSpeedKmh: Double? = null,
    /** STAY 中心点 lat。collapseConstantRegions で滞在マーカー位置に使う。 */
    val constantRegionStayLat: Double? = null,
    /** STAY 中心点 lon。同上。 */
    val constantRegionStayLon: Double? = null
)
