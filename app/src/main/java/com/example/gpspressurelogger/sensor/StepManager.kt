package com.example.gpspressurelogger.sensor

import java.util.Calendar

/**
 * 歩数計測を専門に管理するクラス。
 * 生のセンサー累計値から、今日の歩数とスロット増分を算出する。
 *
 * Android の SensorEvent は LoggingService で受け、MotionStateManager の owner thread 上で
 * 累計値だけを本クラスへ投入する。これにより歩数状態の更新スレッドを 1 本に保つ。
 */
class StepManager(
    private val paramsProvider: MotionStateParamsProvider,
    private val onStepCountUp: (delta: Int, todayTotal: Int, timestampMs: Long) -> Unit,
    private val onTimeReset: () -> Unit
) {
    private data class WalkingEventSample(
        val timestampMs: Long,
        val count: Int
    )

    private var _todaySteps = 0
    private var lastStepTimestampMs: Long = 0L
    private var lastSensorTotal: Int? = null
    private var lastLoggingDateTag: String = ""
    private var slotDelta = 0
    private var useStepCounterForWalking = true
    private val walkingEventSamples = ArrayDeque<WalkingEventSample>()

    /**
     * 前回の呼び出し以降に蓄積された歩数増分を返し、内部カウンタをリセットする。
     */
    fun pullSlotDelta(): Int = synchronized(this) {
        val d = slotDelta
        slotDelta = 0
        d
    }

    /**
     * 低遅延な歩行検知（Sensor.TYPE_STEP_DETECTOR）を記録する。
     */
    fun addStepDetectorEvent(timestampMs: Long = System.currentTimeMillis()) = synchronized(this) {
        lastStepTimestampMs = timestampMs
        addWalkingEventSampleLocked(timestampMs, 1)
    }

    /**
     * TYPE_STEP_DETECTOR が使える端末では、歩行判定窓は detector だけで更新する。
     * detector が使えない端末では counter 増分を歩行判定の fallback として使う。
     */
    fun setStepDetectorAvailable(available: Boolean) = synchronized(this) {
        useStepCounterForWalking = !available
    }

    fun addStepCounterTotal(currentSensorTotal: Int, timestampMs: Long = System.currentTimeMillis()) {
        var reset = false
        var stepCallback: Triple<Int, Int, Long>? = null
        synchronized(this) {
            reset = checkDateResetLocked(timestampMs)
            val previous = lastSensorTotal
            lastSensorTotal = currentSensorTotal

            if (previous != null) {
                val delta = if (currentSensorTotal >= previous) {
                    currentSensorTotal - previous
                } else {
                    currentSensorTotal.coerceAtLeast(0)
                }

                if (delta > 0) {
                    _todaySteps += delta
                    slotDelta += delta
                    if (useStepCounterForWalking) {
                        lastStepTimestampMs = timestampMs
                        addWalkingEventSampleLocked(timestampMs, delta)
                    }
                    stepCallback = Triple(delta, _todaySteps, timestampMs)
                }
            }
        }
        if (reset) {
            onTimeReset()
        }
        stepCallback?.let { (delta, total, stepTimestampMs) ->
            onStepCountUp(delta, total, stepTimestampMs)
        }
    }

    /**
     * 最後の歩行イベントからの経過時間で W1 / W2 を判定する。
     * TYPE_STEP_DETECTOR が使える端末では detector のみを歩行窓に使い、
     * TYPE_STEP_COUNTER は正式な歩数保存だけを担う。
     */
    fun getWStatusSnapshot(timestampMs: Long): WStatusSnapshot = synchronized(this) {
        trimWalkingEventSamplesLocked(timestampMs)
        val params = paramsProvider.current()
        val eventCountWindow = walkingEventSamples.sumOf { it.count }
        val enoughWalkingEvents = eventCountWindow >= params.wStepDeltaThreshold
        val recentWalking = lastStepTimestampMs > 0L &&
            (timestampMs - lastStepTimestampMs) <= params.walkingThresholdMs
        val status = if (enoughWalkingEvents && recentWalking) {
            WStatus.W1
        } else {
            WStatus.W2
        }
        WStatusSnapshot(status = status, stepDeltaWindow = eventCountWindow)
    }

    /**
     * 現在時刻がリセット時間を跨いでいるか確認し、必要ならリセットする。
     */
    private fun checkDateResetLocked(now: Long): Boolean {
        val params = paramsProvider.current()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        
        if (hour < params.stepResetHour) {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        val dateTag = "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.DAY_OF_YEAR)}"

        var reset = false
        if (lastLoggingDateTag.isNotEmpty() && lastLoggingDateTag != dateTag) {
            _todaySteps = 0
            reset = true
        }
        lastLoggingDateTag = dateTag
        return reset
    }

    private fun addWalkingEventSampleLocked(timestampMs: Long, count: Int) {
        walkingEventSamples.addLast(WalkingEventSample(timestampMs, count.coerceAtLeast(0)))
        trimWalkingEventSamplesLocked(timestampMs)
    }

    private fun trimWalkingEventSamplesLocked(nowMs: Long) {
        val windowStart = nowMs - paramsProvider.current().wWindowMs
        while (walkingEventSamples.isNotEmpty() && walkingEventSamples.first().timestampMs < windowStart) {
            walkingEventSamples.removeFirst()
        }
    }
}
