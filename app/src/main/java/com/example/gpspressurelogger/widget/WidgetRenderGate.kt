package com.example.gpspressurelogger.widget

import android.util.Log

internal enum class WidgetUpdateReason {
    HOST,
    SERVICE,
    FORCED
}

internal object WidgetRenderGate {
    fun shouldRender(
        tag: String,
        reason: WidgetUpdateReason,
        intervalSec: Int,
        lastRenderMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (reason == WidgetUpdateReason.FORCED) return true
        if (reason == WidgetUpdateReason.HOST) return true
        val intervalMs = intervalSec.coerceAtLeast(1) * 1000L
        if (lastRenderMs <= 0L) return true
        val elapsedMs = nowMs - lastRenderMs
        if (elapsedMs >= intervalMs) return true
        val remainingMs = intervalMs - elapsedMs
        Log.d(tag, "Skip widget render: reason=$reason elapsedMs=$elapsedMs remainingMs=$remainingMs")
        return false
    }
}
