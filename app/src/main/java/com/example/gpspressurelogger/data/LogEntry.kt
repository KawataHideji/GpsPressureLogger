package com.example.gpspressurelogger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 1件の記録データ
 */
@Entity(
    tableName = "log_entries",
    indices = [Index(value = ["timestamp"], unique = true)]
)
data class LogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** UNIXタイムスタンプ（ミリ秒） */
    val timestamp: Long,
    /** 緯度 */
    val latitude: Double? = null,
    /** 経度 */
    val longitude: Double? = null,
    /** GPS由来の標高（メートル） */
    val altitudeGps: Double? = null,
    /** 気圧センサーの実測値（hPa） */
    val pressureRaw: Float? = null,
    /** 海面更正気圧 QNH（hPa）= 補正気圧 */
    val pressureQnh: Float? = null,
    /** GPS精度（メートル） */
    val gpsAccuracy: Float? = null,
    /** 前回計測から増えた歩数 */
    val stepsDelta: Int? = null,
    /** 旧スキーマ互換の累積歩数。新規記録では使わない */
    val legacyStepCount: Int? = null
) {
    val hasLocation: Boolean
        get() = latitude != null && longitude != null
}
