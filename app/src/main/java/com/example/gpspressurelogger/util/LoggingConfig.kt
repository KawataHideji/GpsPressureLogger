package com.example.gpspressurelogger.util

/**
 * 記録まわりの時間・件数・閾値定数を一元管理する。
 * 実装側では、散在した直値ではなく本オブジェクトを参照する。
 */
object LoggingConfig {
    const val SLOT_INTERVAL_MS = 3_000L
    const val SLOT_INTERVAL_SECONDS = SLOT_INTERVAL_MS / 1000f

    const val GPS_ACCURACY_THRESHOLD_M = 100f
    const val GPS_MIN_INTERVAL_MS = 1_000L
    const val GPS_DYNAMIC_INTERVAL_MIN_HOLD_MS = 30_000L
    const val GPS_BOOTSTRAP_COOLDOWN_MS = 60_000L
    const val GPS_BOOTSTRAP_MAX_UPDATE_AGE_MS = 2 * 60_000L
    const val STATIONARY_GPS_REUSE_MAX_AGE_MS = 20 * 60_000L

    const val CSV_FLUSH_QUEUE_SIZE = 100
    const val CSV_MAX_QUEUE_SIZE = 1000
}
