package com.example.gpspressurelogger.util

/**
 * 記録まわりの時間・件数・閾値定数を一元管理する。
 * 実装側では、散在した直値ではなく本オブジェクトを参照する。
 */
object LoggingConfig {
    const val SLOT_INTERVAL_MS = 3_000L
    const val SLOT_INTERVAL_SECONDS = SLOT_INTERVAL_MS / 1000f
    const val PRESSURE_RECORD_INTERVAL_MS = 3 * 60_000L

    const val GPS_ACCURACY_THRESHOLD_M = 100f
    const val GPS_MIN_INTERVAL_MS = 1_000L
    const val GPS_DYNAMIC_INTERVAL_MIN_HOLD_MS = 30_000L
    // 加速度トリガー時は単発 getCurrentLocation ではなく、短時間だけ高頻度更新を走らせて良点を選ぶ。
    const val GPS_BURST_INTERVAL_MS = 500L
    const val GPS_BURST_MIN_INTERVAL_MS = 100L
    const val GPS_BURST_MAX_UPDATE_AGE_MS = 500L
    const val GPS_BURST_DURATION_MS = 10_000L
    const val GPS_BURST_MAX_UPDATES = 10
    const val GPS_BURST_GOOD_ACCURACY_M = 30f
    const val GPS_BURST_USABLE_ACCURACY_M = 80f
    const val GPS_STRETCH_ACCEPT_ACCURACY_M = 80f
    const val GPS_BOOTSTRAP_COOLDOWN_MS = 60_000L
    const val GPS_BOOTSTRAP_MAX_UPDATE_AGE_MS = 2 * 60_000L
    const val STATIONARY_GPS_REUSE_MAX_AGE_MS = 20 * 60_000L

    const val CSV_FLUSH_QUEUE_SIZE = 100
    const val CSV_MAX_QUEUE_SIZE = 1000
    const val CSV_MOTION_REWRITE_DEBOUNCE_MS = 60_000L
    const val CSV_MOTION_REWRITE_MAX_PENDING_SAMPLES = 500
}
