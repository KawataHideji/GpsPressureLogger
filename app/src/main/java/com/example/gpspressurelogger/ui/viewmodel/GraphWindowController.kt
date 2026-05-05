package com.example.gpspressurelogger.ui.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min

/**
 * ホーム画面グラフの表示時間窓を管理する。
 *
 * 通常は最新ログに追従し、ユーザーが過去へスワイプした時だけ手動表示へ切り替える。
 * ViewModel 本体に追従・クランプ条件を散らさないため、このクラスに閉じ込める。
 */
internal class GraphWindowController(
    initialWindowEndMs: Long,
    private val minVisibleLookbackMs: Long,
    private val maxZoomOutFactor: Int,
    private val manualFollowGuardMs: Long = DEFAULT_MANUAL_FOLLOW_GUARD_MS,
    private val latestFollowToleranceMs: Long = DEFAULT_LATEST_FOLLOW_TOLERANCE_MS
) {
    private val _windowEndMs = MutableStateFlow(initialWindowEndMs)
    val windowEndMs: StateFlow<Long> = _windowEndMs

    private var followsLatest = true
    private var followGuardUntilMs = 0L

    fun onLatestTimestamp(latestTimestampMs: Long?, nowMs: Long = System.currentTimeMillis()) {
        if (latestTimestampMs == null || !followsLatest || nowMs < followGuardUntilMs) return
        _windowEndMs.value = latestTimestampMs
    }

    fun shiftBy(
        deltaMs: Long,
        visibleLookbackMs: Long,
        oldestTimestampMs: Long,
        latestTimestampMs: Long,
        configuredLookbackMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val effectiveLookbackMs = visibleLookbackMs.coerceIn(
            minVisibleLookbackMs,
            configuredLookbackMs * maxZoomOutFactor
        )
        val minWindowEndMs = min(
            (oldestTimestampMs + effectiveLookbackMs).coerceAtLeast(oldestTimestampMs),
            latestTimestampMs
        )
        val requestedWindowEndMs = (_windowEndMs.value + deltaMs).coerceIn(minWindowEndMs, latestTimestampMs)
        val shouldKeepFollowing = requestedWindowEndMs >= latestTimestampMs - latestFollowToleranceMs
        _windowEndMs.value = if (shouldKeepFollowing) latestTimestampMs else requestedWindowEndMs
        followsLatest = shouldKeepFollowing
        followGuardUntilMs = if (shouldKeepFollowing) 0L else nowMs + manualFollowGuardMs
    }

    fun resetToLatest(latestTimestampMs: Long) {
        followGuardUntilMs = 0L
        followsLatest = true
        _windowEndMs.value = latestTimestampMs
    }

    private companion object {
        const val DEFAULT_MANUAL_FOLLOW_GUARD_MS = 3_000L
        const val DEFAULT_LATEST_FOLLOW_TOLERANCE_MS = 10_000L
    }
}
