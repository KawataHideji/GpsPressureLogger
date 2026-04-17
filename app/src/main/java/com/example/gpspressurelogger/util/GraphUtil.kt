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
    const val MAX_ZOOM_OUT_FACTOR = 2

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
        val sourceStartMs = (startMs - (endMs - startMs)).coerceAtLeast(0L)
        val filtered = filterOutliers(entries)
            .filter { it.timestamp in sourceStartMs..endMs }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
        if (filtered.isEmpty()) return null

        val targetTimes = buildTargetTimes(startMs, endMs, intervalMs)
        val stepVals = createStepSeries(filtered, targetTimes, stepsResetStartMs).toList()
        val stepModes = createStepModeSeries(targetTimes, motionSamples)
        val iAlt = createMetricSeries(filtered, targetTimes) { it.altitudeGps?.toFloat() }
        val iPres = createMetricSeries(filtered, targetTimes) { it.pressureRaw }
        val iQnh = createMetricSeries(filtered, targetTimes) { it.pressureQnh }

        val altVals = movingAverage(iAlt.toList(), 40)
        val presVals = movingAverage(iPres.toList(), 40)
        val qnhVals = movingAverage(iQnh.toList(), 40)

        val pAll = (presVals + qnhVals).filter { it.isFinite() }
        val altFinite = altVals.filter { it.isFinite() }
        val stepFinite = stepVals.filter { it.isFinite() }
        return ProcessedSeries(
            times = targetTimes,
            alt = altVals,
            pres = presVals,
            qnh = qnhVals,
            steps = stepVals,
            stepModes = stepModes,
            pMax = pAll.maxOrNull() ?: 1013f,
            pMin = pAll.minOrNull() ?: 1000f,
            aMax = altFinite.maxOrNull() ?: 100f,
            aMin = altFinite.minOrNull() ?: 0f,
            sMax = stepFinite.maxOrNull() ?: 0f
        )
    }

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

    private fun movingAverage(values: List<Float>, factor: Int): List<Float> {
        val n = values.size
        if (n < 3) return values
        val half = (n / factor).coerceIn(2, 100)
        return List(n) { i ->
            val from = maxOf(0, i - half); val to = minOf(n - 1, i + half)
            val finiteValues = values.subList(from, to + 1).filter { it.isFinite() }
            if (finiteValues.isEmpty()) Float.NaN else finiteValues.average().toFloat()
        }
    }

    private fun createMetricSeries(
        entries: List<LogEntry>,
        targetTimes: LongArray,
        selector: (LogEntry) -> Float?
    ): FloatArray {
        val points = entries.mapNotNull { entry ->
            selector(entry)?.let { entry.timestamp to it }
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
        val interpolator = com.example.gpspressurelogger.util.SplineInterpolator(
            points.map { it.first.toDouble() }.toDoubleArray(),
            points.map { it.second.toDouble() }.toDoubleArray()
        )
        return FloatArray(targetTimes.size) { index ->
            val target = targetTimes[index]
            when {
                target < points.first().first -> Float.NaN
                target > points.last().first -> points.last().second
                else -> interpolator.interpolate(target.toDouble()).toFloat()
            }
        }
    }

    private fun createStepModeSeries(
        targetTimes: LongArray,
        motionSamples: List<MotionSample>
    ): List<MovementDetector.Mode> {
        val timeline = GpsUtil.inferDisplayModes(motionSamples)
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
        var cumulativeSteps = 0f
        var currentDayStart = if (disableReset) Long.MIN_VALUE else GpsUtil.getLoggingStart(entries.first().timestamp)
        val rawTimes = entries.map { it.timestamp }.toLongArray()
        val rawSteps = FloatArray(entries.size)
        entries.forEachIndexed { index, entry ->
            if (!disableReset) {
                val dayStart = GpsUtil.getLoggingStart(entry.timestamp)
                if (dayStart != currentDayStart) {
                    currentDayStart = dayStart
                    cumulativeSteps = 0f
                }
            }
            cumulativeSteps += (resolvedStepDeltas[index] ?: 0).toFloat()
            rawSteps[index] = cumulativeSteps
        }
        val interpolator = SplineInterpolator(
            rawTimes.map { it.toDouble() }.toDoubleArray(),
            rawSteps.map { it.toDouble() }.toDoubleArray()
        )
        return FloatArray(targetTimes.size) { index ->
            val target = targetTimes[index]
            when {
                target < rawTimes.first() -> Float.NaN
                target > rawTimes.last() -> rawSteps.last()
                else -> interpolator.interpolate(target.toDouble()).toFloat()
            }
        }
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
