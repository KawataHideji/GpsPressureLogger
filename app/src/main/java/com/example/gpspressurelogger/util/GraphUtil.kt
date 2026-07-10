package com.example.gpspressurelogger.util

import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.sensor.MovementDetector
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.min

/**
 * グラフ描画に関する共通ロジック (v5: ロジック一元化)
 */
object GraphUtil {

    const val COLOR_ALT   = 0xFFFFEB3B.toInt()
    const val COLOR_PRES  = 0xFF4CAF50.toInt()
    const val COLOR_QNH   = 0xFFFFFFFF.toInt()
    const val COLOR_STEPS = 0xFF3178FF.toInt()
    const val COLOR_MIDNIGHT_LINE = 0x779AA3AE.toInt()
    const val COLOR_MIDNIGHT_LABEL = 0xCCCDD3DC.toInt()
    const val MAX_ZOOM_OUT_FACTOR = 6

    const val PRES_TOP = 0.15f
    const val PRES_BOT = 0.55f
    const val ALT_TOP  = 0.60f
    const val ALT_BOT  = 0.85f
    const val STEPS_0   = 0.95f
    const val STEPS_10K = 0.20f
    const val STEPS_MAX_OVER = 0.30f
    
    const val STEPS_THRESHOLD = 10000f
    const val LABEL_COLLISION_THRESHOLD_PX = 30f
    const val STEPS_NO_RESET_START_MS: Long = Long.MIN_VALUE
    private const val GRAPH_INTERPOLATION_CONTEXT_MS = 30 * 60_000L

    data class ProcessedSeries(
        val times: LongArray,
        val alt: List<Float>,
        val pres: List<Float>,
        val qnh: List<Float>,
        val steps: List<Float>,
        val stepModes: List<MovementDetector.Mode>,
        val pMax: Float, val pMin: Float,
        val aMax: Float, val aMin: Float,
        val sMax: Float
    )

    data class LatestMetricValues(
        val altitude: Double?,
        val pressureRaw: Float?,
        val pressureQnh: Float?
    )

    /** 今日の歩数を算出（リセット耐性付き一元化ロジック） */
    fun calculateTodaySteps(entries: List<LogEntry>): Int {
        if (entries.isEmpty()) return 0
        val startTodayTs = GpsUtil.getLoggingStart(System.currentTimeMillis())
        val sorted = entries
            .asSequence()
            .filter { it.timestamp >= startTodayTs }
            .sortedBy { it.timestamp }
            .toList()
        return resolveStepDeltas(sorted).mapNotNull { it }.sum()
    }

    fun getProcessedSeries(
        entries: List<LogEntry>,
        motionSamples: List<MotionSample>,
        intervalMs: Long,
        lookbackMs: Long,
        windowEndMs: Long,
        stepsResetStartMs: Long = GpsUtil.getLoggingStart(windowEndMs)
    ): ProcessedSeries? = getProcessedSeriesForWindow(
        entries = entries,
        motionSamples = motionSamples,
        intervalMs = intervalMs,
        windowStartMs = windowEndMs - lookbackMs.coerceAtLeast(intervalMs),
        windowEndMs = windowEndMs,
        stepsResetStartMs = stepsResetStartMs
    )

    fun getProcessedSeriesForWindow(
        entries: List<LogEntry>,
        motionSamples: List<MotionSample>,
        intervalMs: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        stepsResetStartMs: Long = GpsUtil.getLoggingStart(windowEndMs)
    ): ProcessedSeries? {
        if (entries.isEmpty()) return null

        val startMs = windowStartMs.coerceAtLeast(0L)
        val endMs = max(windowEndMs, startMs + intervalMs)
        val sourceStartMs = graphSourceStartMs(startMs)
        val sourceEntries = entries
            .asSequence()
            .filter { it.timestamp in sourceStartMs..endMs }
            .sortedBy { it.timestamp }
            .toList()
        val filtered = filterOutliers(sourceEntries)
        if (filtered.isEmpty()) return null

        val sourceMotionSamples = motionSamples
            .asSequence()
            .filter { it.timestamp in sourceStartMs..endMs }
            .sortedBy { it.timestamp }
            .toList()
        val targetTimes = buildTargetTimes(startMs, endMs, intervalMs)
        val stepVals = createStepSeries(filtered, targetTimes, stepsResetStartMs).toList()
        val stepModes = createStepModeSeries(targetTimes, sourceMotionSamples, filtered)
        val iAlt = createMetricSeries(filtered, targetTimes) { it.altitudeGps?.toFloat() }
        val iPres = createMetricSeries(filtered, targetTimes) { it.pressureRaw }
        val iQnh = createMetricSeries(filtered, targetTimes) { it.pressureQnh }

        // FloatArray のまま min/max 計算（ボクシングなし）、ProcessedSeries 構築時に一度だけ List<Float> に変換
        val altArr = movingAverage(iAlt, 40)
        val presArr = movingAverage(iPres, 40)
        val qnhArr = movingAverage(iQnh, 40)

        // min/max を単一パスで計算（結合リストも filter も不要）
        var pMax = Float.NEGATIVE_INFINITY
        var pMin = Float.POSITIVE_INFINITY
        for (v in presArr) { if (v.isFinite()) { if (v > pMax) pMax = v; if (v < pMin) pMin = v } }
        for (v in qnhArr) { if (v.isFinite()) { if (v > pMax) pMax = v; if (v < pMin) pMin = v } }
        if (!pMax.isFinite()) pMax = 1013f
        if (!pMin.isFinite()) pMin = 1000f

        var aMax = Float.NEGATIVE_INFINITY
        var aMin = Float.POSITIVE_INFINITY
        for (v in altArr) { if (v.isFinite()) { if (v > aMax) aMax = v; if (v < aMin) aMin = v } }
        if (!aMax.isFinite()) aMax = 100f
        if (!aMin.isFinite()) aMin = 0f

        var sMax = Float.NEGATIVE_INFINITY
        for (v in stepVals) { if (v.isFinite() && v > sMax) sMax = v }
        if (!sMax.isFinite()) sMax = 0f

        return ProcessedSeries(
            times = targetTimes,
            alt = altArr.asList(),   // ここで一度だけ List<Float> に変換
            pres = presArr.asList(),
            qnh = qnhArr.asList(),
            steps = stepVals,
            stepModes = stepModes,
            pMax = pMax,
            pMin = pMin,
            aMax = aMax,
            aMin = aMin,
            sMax = sMax
        )
    }

    fun graphSourceStartMs(windowStartMs: Long): Long =
        (windowStartMs.coerceAtLeast(0L) - GRAPH_INTERPOLATION_CONTEXT_MS).coerceAtLeast(0L)

    fun resolveLatestMetricValues(entriesAsc: List<LogEntry>, latest: LogEntry?): LatestMetricValues {
        val history = if (entriesAsc.isEmpty()) latest?.let(::listOf) ?: emptyList() else entriesAsc
        return LatestMetricValues(
            altitude = latest?.altitudeGps ?: history.asReversed().firstNotNullOfOrNull { it.altitudeGps },
            pressureRaw = latest?.pressureRaw ?: history.asReversed().firstNotNullOfOrNull { it.pressureRaw },
            pressureQnh = latest?.pressureQnh ?: history.asReversed().firstNotNullOfOrNull { it.pressureQnh }
        )
    }

    fun valueToYRatio(value: Float, min: Float, max: Float, topRate: Float, botRate: Float): Float {
        val diff = (max - min).coerceAtLeast(0.1f)
        return topRate + (max - value) / diff * (botRate - topRate)
    }

    fun stepsToYRatio(steps: Float, sMax: Float): Float {
        val sLimit = if (sMax > STEPS_THRESHOLD) sMax else STEPS_THRESHOLD
        val yTop = if (sMax > STEPS_THRESHOLD) STEPS_MAX_OVER else STEPS_10K
        return STEPS_0 + (steps / sLimit) * (yTop - STEPS_0)
    }

    fun getStepTicks(sMax: Float): List<Float> {
        val sLimit = if (sMax > STEPS_THRESHOLD) sMax else STEPS_THRESHOLD
        val ticks = mutableListOf<Float>()
        var current = 2500f
        while (current <= sLimit + 100f) {
            ticks.add(current); current += 2500f
        }
        return ticks
    }

    fun calculateVerticalOffset(y1: Float, y2: Float, threshold: Float = LABEL_COLLISION_THRESHOLD_PX): Pair<Float, Float> {
        val diff = abs(y1 - y2)
        if (diff >= threshold) return Pair(0f, 0f)
        val shift = (threshold - diff) / 2f
        return if (y1 < y2) Pair(-shift, shift) else Pair(shift, -shift)
    }

    /** 表示窓に含まれるローカル日付の 0:00 線を返す。 */
    fun midnightTicks(windowStartMs: Long, windowEndMs: Long): List<Long> {
        if (windowEndMs <= windowStartMs) return emptyList()
        val ticks = mutableListOf<Long>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = windowStartMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis < windowStartMs) add(Calendar.DAY_OF_YEAR, 1)
        }
        while (cal.timeInMillis <= windowEndMs) {
            ticks += cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return ticks
    }

    private fun filterOutliers(entries: List<LogEntry>): List<LogEntry> {
        if (entries.size <= 2) return entries
        val result = mutableListOf<LogEntry>()
        for (i in entries.indices) {
            val cur = entries[i]
            val curPressure = cur.pressureRaw
            val prevPressure = if (i > 0) entries[i - 1].pressureRaw else curPressure
            val nextPressure = if (i < entries.size - 1) entries[i + 1].pressureRaw else curPressure
            val curAlt = cur.altitudeGps?.toFloat()
            val prevAlt = if (i > 0) entries[i - 1].altitudeGps?.toFloat() else curAlt
            val nextAlt = if (i < entries.size - 1) entries[i + 1].altitudeGps?.toFloat() else curAlt
            val pressureOutlier = curPressure != null && prevPressure != null && nextPressure != null &&
                abs(curPressure - prevPressure) > 10f && abs(curPressure - nextPressure) > 10f
            val altitudeOutlier = curAlt != null && prevAlt != null && nextAlt != null &&
                abs(curAlt - prevAlt) > 300f && abs(curAlt - nextAlt) > 300f
            if (!pressureOutlier && !altitudeOutlier) {
                result.add(cur)
            }
        }
        return result
    }

    /**
     * O(N) 移動平均（スライディングウィンドウ）。FloatArray を受け取り FloatArray を返す。
     * 旧実装は O(N × window) だったが、running sum で O(N) に改善。
     * min/max 計算でのボクシングを避けるため戻り値も FloatArray のまま維持する。
     */
    private fun movingAverage(values: FloatArray, factor: Int): FloatArray {
        val n = values.size
        if (n < 3) return values
        val half = (n / factor).coerceIn(2, 100)
        val result = FloatArray(n)
        var winSum = 0.0
        var winCount = 0
        var winFrom = 0
        var winTo = -1
        for (i in 0 until n) {
            val targetFrom = maxOf(0, i - half)
            val targetTo = minOf(n - 1, i + half)
            // ウィンドウ右端を拡張
            while (winTo < targetTo) {
                winTo++
                val v = values[winTo]
                if (v.isFinite()) { winSum += v; winCount++ }
            }
            // ウィンドウ左端を縮小
            while (winFrom < targetFrom) {
                val v = values[winFrom]
                if (v.isFinite()) { winSum -= v; winCount-- }
                winFrom++
            }
            result[i] = if (winCount > 0) (winSum / winCount).toFloat() else Float.NaN
        }
        return result
    }

    private fun createMetricSeries(
        entries: List<LogEntry>,
        targetTimes: LongArray,
        selector: (LogEntry) -> Float?
    ): FloatArray {
        val points = entries.mapNotNull { entry ->
            val value = selector(entry)
            if (value != null && value.isFinite()) entry.timestamp to value else null
        }
        if (points.isEmpty()) {
            return FloatArray(targetTimes.size) { Float.NaN }
        }
        if (points.size == 1) {
            val only = points.first()
            return FloatArray(targetTimes.size) { index ->
                if (targetTimes[index] < only.first) Float.NaN else only.second
            }
        }
        var nextIndex = 0
        return FloatArray(targetTimes.size) { targetIndex ->
            val target = targetTimes[targetIndex]
            while (nextIndex < points.size && points[nextIndex].first < target) {
                nextIndex += 1
            }
            when {
                nextIndex == 0 -> if (points.first().first == target) points.first().second else Float.NaN
                nextIndex >= points.size -> points.last().second
                points[nextIndex].first == target -> points[nextIndex].second
                else -> {
                    val (prevTime, prevValue) = points[nextIndex - 1]
                    val (nextTime, nextValue) = points[nextIndex]
                    val ratio = if (nextTime == prevTime) {
                        0f
                    } else {
                        ((target - prevTime).toFloat() / (nextTime - prevTime).toFloat()).coerceIn(0f, 1f)
                    }
                    prevValue + (nextValue - prevValue) * ratio
                }
            }
        }
    }

    private fun createStepModeSeries(
        targetTimes: LongArray,
        motionSamples: List<MotionSample>,
        entries: List<LogEntry>
    ): List<MovementDetector.Mode> {
        val timeline = GpsUtil.inferDisplayModes(motionSamples, entries)
        return GpsUtil.modesAt(targetTimes, timeline)
    }

    private fun buildTargetTimes(windowStartMs: Long, windowEndMs: Long, intervalMs: Long): LongArray {
        val span = (windowEndMs - windowStartMs).coerceAtLeast(intervalMs)
        val count = (span / intervalMs).toInt() + 1
        return LongArray(count) { index -> windowStartMs + index * intervalMs }
    }

    private fun createStepSeries(
        entries: List<LogEntry>,
        targetTimes: LongArray,
        stepsResetStartMs: Long
    ): FloatArray {
        if (entries.isEmpty()) return FloatArray(targetTimes.size) { Float.NaN }
        val resolvedStepDeltas = resolveStepDeltas(entries)
        val disableReset = stepsResetStartMs == STEPS_NO_RESET_START_MS

        // 日付境界をあらかじめ1回だけ計算する（Calendar インスタンスを大量生成しない）
        val boundaries: LongArray = if (disableReset) {
            LongArray(0)
        } else {
            val firstTs = min(entries.first().timestamp, if (targetTimes.isNotEmpty()) targetTimes[0] else entries.first().timestamp)
            val lastTs = max(entries.last().timestamp, if (targetTimes.isNotEmpty()) targetTimes[targetTimes.size - 1] else entries.last().timestamp)
            buildDayBoundaries(firstTs, lastTs)
        }

        var cumulativeSteps = 0f
        var currentDayStart = if (disableReset) Long.MIN_VALUE else loggingStartOf(entries.first().timestamp, boundaries)
        var entryIndex = 0
        var hasAcceptedEntry = false
        return FloatArray(targetTimes.size) { targetIndex ->
            val target = targetTimes[targetIndex]
            if (!disableReset) {
                val targetDayStart = loggingStartOf(target, boundaries)
                if (targetDayStart != currentDayStart) {
                    currentDayStart = targetDayStart
                    cumulativeSteps = 0f
                }
            }

            while (entryIndex < entries.size && entries[entryIndex].timestamp <= target) {
                val entry = entries[entryIndex]
                if (!disableReset) {
                    val entryDayStart = loggingStartOf(entry.timestamp, boundaries)
                    if (entryDayStart != currentDayStart) {
                        currentDayStart = entryDayStart
                        cumulativeSteps = 0f
                    }
                }
                cumulativeSteps += (resolvedStepDeltas[entryIndex] ?: 0).toFloat()
                hasAcceptedEntry = true
                entryIndex += 1
            }
            if (hasAcceptedEntry || target >= entries.first().timestamp) cumulativeSteps else Float.NaN
        }
    }

    /**
     * [startMs, endMs] を含む「ログ収集日の開始時刻」リストを LongArray で返す。
     * Calendar インスタンスは高々1個だけ生成する。
     */
    private fun buildDayBoundaries(startMs: Long, endMs: Long): LongArray {
        val list = mutableListOf<Long>()
        val cal = Calendar.getInstance()
        cal.timeInMillis = GpsUtil.getLoggingStart(startMs)
        while (cal.timeInMillis <= endMs + GpsUtil.DAY_MS) {
            list.add(cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return list.toLongArray()
    }

    /**
     * 事前計算された境界配列を使ってバイナリサーチで getLoggingStart 相当の値を返す。
     * Calendar を生成しない O(log N) 実装。
     */
    private fun loggingStartOf(timestamp: Long, boundaries: LongArray): Long {
        if (boundaries.isEmpty()) return GpsUtil.getLoggingStart(timestamp)
        var idx = boundaries.binarySearch(timestamp)
        if (idx < 0) idx = -idx - 2   // 挿入点 - 1 = フロア境界のインデックス
        return if (idx >= 0) boundaries[idx] else boundaries[0]
    }

    fun niceStep(range: Float, targetTicks: Int = 4): Float {
        if (range <= 0f) return 1f
        val raw = range / targetTicks
        val mag = 10f.pow(floor(log10(raw.toDouble())).toFloat())
        return when {
            raw / mag <= 1.5f -> mag; raw / mag <= 3.5f -> 2f * mag; raw / mag <= 7.5f -> 5f * mag; else -> 10f * mag
        }
    }

    fun resolveStepDeltas(entriesAsc: List<LogEntry>): List<Int?> {
        if (entriesAsc.isEmpty()) return emptyList()
        val resolved = ArrayList<Int?>(entriesAsc.size)
        var previousLegacyStepCount: Int? = null
        entriesAsc.forEach { entry ->
            val delta = when {
                entry.stepsDelta != null -> max(0, entry.stepsDelta)
                entry.legacyStepCount != null -> {
                    val current = entry.legacyStepCount
                    if (previousLegacyStepCount == null) {
                        0
                    } else {
                        max(0, current - previousLegacyStepCount!!)
                    }
                }
                else -> null
            }
            resolved += delta
            if (entry.legacyStepCount != null) {
                previousLegacyStepCount = entry.legacyStepCount
            }
        }
        return resolved
    }
}
