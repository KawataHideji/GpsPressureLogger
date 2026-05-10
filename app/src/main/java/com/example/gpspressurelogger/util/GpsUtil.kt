package com.example.gpspressurelogger.util

import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.sensor.ConstantRegionKind
import com.example.gpspressurelogger.sensor.FinalContextResolver
import com.example.gpspressurelogger.sensor.MovementDetector
import com.example.gpspressurelogger.sensor.MotionStateParams
import com.example.gpspressurelogger.sensor.StKStatus
import com.example.gpspressurelogger.sensor.TrKStatus
import com.example.gpspressurelogger.sensor.WalkingSpeedSnapshot
import java.util.Calendar
import kotlin.math.*

/**
 * GPS 座標ユーティリティ
 */
object GpsUtil {

    /** 1日のログの区切り時刻（時）。デフォルトは午前3時 */
    const val LOGGING_RESET_HOUR = 3
    const val DAY_MS = 24 * 3600_000L
    private const val MAX_REASONABLE_SPEED_KMH = 300.0
    private const val MAX_REASONABLE_ACCURACY_M = 100f
    private const val SINGLE_POINT_SPIKE_MAX_DURATION_MS = 5 * 60_000L
    private const val SINGLE_POINT_SPIKE_DEVIATION_SPEED_KMH = 100.0
    private const val TRANSIENT_DETOUR_MAX_DURATION_MS = 8 * 60_000L
    private const val TRANSIENT_DETOUR_MIN_NEIGHBOR_DISTANCE_M = 120.0
    private const val TRANSIENT_DETOUR_MIN_LATERAL_DISTANCE_M = 120.0
    private const val TRANSIENT_DETOUR_MIN_EXTRA_DISTANCE_M = 250.0
    private const val TRANSIENT_DETOUR_MIN_EXTRA_RATIO = 1.8
    private const val TRANSIENT_DETOUR_MIN_TURN_ANGLE_DEG = 120.0
    private const val CLUSTER_BOUNDARY_MAX_DURATION_MS = 2 * 60_000L
    private const val CLUSTER_BOUNDARY_MIN_DISTANCE_M = 1_000.0
    private const val CLUSTER_BOUNDARY_MIN_SPEED_KMH = 160.0
    private const val ISOLATED_CLUSTER_MAX_DURATION_MS = 4 * 60_000L
    private const val ISOLATED_CLUSTER_MAX_POINTS = 24
    private const val DISPLAY_SMOOTHING_WINDOW_RADIUS = 2
    private const val MAP_SPLINE_SAMPLES_PER_SEGMENT = 5
    private const val MAP_SPLINE_EDGE_LINEAR_SEGMENTS = 0
    private const val MAP_SPLINE_TENSION = 0.55
    private const val GPS_GAP_INTERPOLATION_MIN_MS = 60_000L
    private const val GPS_GAP_INTERPOLATION_MAX_MS = 10 * 60_000L
    private const val GPS_GAP_INTERPOLATION_INTERVAL_MS = 30_000L
    private const val GPS_FREEZE_SAME_POINT_RADIUS_M = 3.0
    private const val GPS_FREEZE_MIN_DURATION_MS = 2 * 60_000L
    private const val GPS_FREEZE_RECOVERY_MIN_JUMP_M = 200.0
    private const val GPS_FREEZE_RECOVERY_MIN_SPEED_KMH = 300.0
    private const val STOP_NORMALIZATION_MIN_DURATION_MS = 2 * 60_000L
    private const val STOP_NORMALIZATION_MIN_POINT_COUNT = 8
    private const val CLUSTER_HOP_DISTANCE_M = 180.0
    private const val CLUSTER_HOP_RETURN_DISTANCE_M = 90.0
    private const val CLUSTER_HOP_ANCHOR_WINDOW = 4
    private const val RETURN_BURST_ENTER_DISTANCE_M = 180.0
    private const val RETURN_BURST_RETURN_DISTANCE_M = 90.0
    private const val RETURN_BURST_PEAK_DISTANCE_M = 250.0
    private const val RETURN_BURST_MAX_POINTS = 30
    private const val RETURN_BURST_MAX_DURATION_MS = 8 * 60_000L
    private const val MODE_STEP_SMOOTH_WINDOW_COUNT = 3
    private const val STOP_MARKER_OUTER_RADIUS_PX = 10f
    private const val STOP_MARKER_MAX_EXTRA_RADIUS_PX = 3f
    private const val STOP_MARKER_POINTS_PER_STEP = 20
    private const val START_MARKER_OUTER_RADIUS_PX = 14f
    private const val START_MARKER_INNER_RADIUS_PX = 10f
    private const val MARKER_RING_STROKE_WIDTH_PX = 5f
    private const val CURRENT_MARKER_OUTER_RADIUS_PX = 14f
    private const val CURRENT_MARKER_INNER_RADIUS_PX = 10f
    private const val APP_MAP_TRACK_STROKE_WIDTH_DP = 4.0f
    private const val WIDGET_MAP_TRACK_STROKE_WIDTH_DP = 2.2f
    
    private const val APP_MAP_MARKER_SCALE = 1.25f
    private const val WIDGET_MARKER_SCALE = 1.0f
    private const val DIRECTION_ARROW_MIN_SPACING_M = 360.0
    private const val DIRECTION_ARROW_MIN_SEGMENT_M = 18.0
    private const val DIRECTION_ARROW_START_END_SKIP_M = 40.0
    private const val DIRECTION_ARROW_LOCAL_BEARING_WINDOW = 2
    private const val DIRECTION_ARROW_TEXT_SIZE_PX = 24f
    private const val DIRECTION_ARROW_BITMAP_PADDING_PX = 10f
    private const val DIRECTION_ARROW_OUTLINE_WIDTH_PX = 4f
    private const val APP_DIRECTION_ARROW_MIN_SPACING_DP = 36f
    private const val APP_DIRECTION_ARROW_MIN_SEGMENT_DP = 12f
    private const val APP_DIRECTION_ARROW_START_END_SKIP_DP = 18f
    private const val WIDGET_DIRECTION_ARROW_MIN_SPACING_DP = 44f
    private const val WIDGET_DIRECTION_ARROW_MIN_SEGMENT_DP = 12f
    private const val WIDGET_DIRECTION_ARROW_START_END_SKIP_DP = 18f
    private const val WIDGET_STOP_MARKER_OUTER_RADIUS_DP = 4f
    private const val WIDGET_STOP_MARKER_MAX_EXTRA_RADIUS_DP = 1.2f
    private const val WIDGET_START_MARKER_OUTER_RADIUS_DP = 5.5f
    private const val WIDGET_START_MARKER_INNER_RADIUS_DP = 4f
    private const val WIDGET_MARKER_RING_STROKE_WIDTH_DP = 2f
    private const val WIDGET_CURRENT_MARKER_OUTER_RADIUS_DP = 5.5f
    private const val WIDGET_CURRENT_MARKER_INNER_RADIUS_DP = 4f
    private const val WIDGET_DIRECTION_ARROW_TEXT_SIZE_DP = 8.5f
    private const val WIDGET_DIRECTION_ARROW_BITMAP_PADDING_DP = 3.5f
    private const val WIDGET_DIRECTION_ARROW_OUTLINE_WIDTH_DP = 1.4f
    private val DISPLAY_MOTION_PARAMS = MotionStateParams()

    const val MODE_COLOR_DEVICE_STILL = 0xFF000000.toInt()
    const val MODE_COLOR_STOPPED = 0xFF8E96A8.toInt()
    const val MODE_COLOR_WALKING = 0xFF3178FF.toInt()
    const val MODE_COLOR_VEHICLE = 0xFFFF4D4F.toInt()

    data class MarkerStyle(
        val outerRadiusPx: Float,
        val innerRadiusPx: Float,
        val strokeWidthPx: Float = 0f
    )

    data class DirectionArrowParams(
        val minSpacingM: Double = DIRECTION_ARROW_MIN_SPACING_M,
        val minSegmentM: Double = DIRECTION_ARROW_MIN_SEGMENT_M,
        val startEndSkipM: Double = DIRECTION_ARROW_START_END_SKIP_M,
        val localBearingWindow: Int = DIRECTION_ARROW_LOCAL_BEARING_WINDOW
    )

    data class ScreenDirectionArrowParams(
        val minSpacingPx: Float,
        val minSegmentPx: Float,
        val startEndSkipPx: Float,
        val localBearingWindow: Int = DIRECTION_ARROW_LOCAL_BEARING_WINDOW
    )

    data class DirectionArrowMarker(
        val lat: Double,
        val lon: Double,
        val angleDeg: Float,
        val displayMode: MovementDetector.Mode
    )

    data class DisplayModeSample(
        val timestamp: Long,
        val mode: MovementDetector.Mode
    )

    data class StopNormalizationParams(
        val minSegmentDurationMs: Long = STOP_NORMALIZATION_MIN_DURATION_MS,
        val minSegmentPointCount: Int = STOP_NORMALIZATION_MIN_POINT_COUNT,
        val clusterHopRadiusM: Double,
        val clusterHopMinPoints: Int,
        val clusterHopMaxPoints: Int,
        val clusterHopMaxDurationMs: Long,
        val clusterHopDistanceM: Double = CLUSTER_HOP_DISTANCE_M,
        val clusterHopReturnDistanceM: Double = CLUSTER_HOP_RETURN_DISTANCE_M,
        val clusterHopAnchorWindow: Int = CLUSTER_HOP_ANCHOR_WINDOW,
        val residualSigma: Double,
        val noiseRadiusM: Double,
        val hardRadiusM: Double,
        val burstHighM: Double,
        val burstLowM: Double,
        val burstReturnPoints: Int,
        val maxBurstPoints: Int,
        val radialSoftStartM: Double,
        val radialKeepRatio: Double,
        val radialHardCapM: Double
    )

    private data class DisplayPoint(
        val sourceIndex: Int,
        val timestamp: Long,
        val lat: Double,
        val lon: Double,
        val stepsDelta: Int,
        val displayMode: MovementDetector.Mode,
        val constantRegionKind: ConstantRegionKind? = null,
        val constantRegionStayLat: Double? = null,
        val constantRegionStayLon: Double? = null,
        val returnBurstFixed: Boolean = false,
        val clusterHopFixed: Boolean = false
    )

    private data class ModeState(
        val timestamp: Long,
        val confirmedMode: MovementDetector.Mode,
        val constantRegionKind: ConstantRegionKind?,
        val constantRegionStayLat: Double?,
        val constantRegionStayLon: Double?
    )

    private data class ReturnBurstResult(
        val points: List<DisplayPoint>
    )

    private data class ClusterHopResult(
        val points: List<DisplayPoint>
    )

    private data class BurstRepairResult(
        val xs: List<Double>,
        val ys: List<Double>
    )

    private data class RadialCompressionResult(
        val xs: List<Double>,
        val ys: List<Double>
    )

    private val STOP_NORMALIZATION_PARAMS = mapOf(
        MovementDetector.Mode.DEVICE_STILL to StopNormalizationParams(
            clusterHopRadiusM = 18.0,
            clusterHopMinPoints = 3,
            clusterHopMaxPoints = 30,
            clusterHopMaxDurationMs = 8 * 60_000L,
            residualSigma = 3.0,
            noiseRadiusM = 3.0,
            hardRadiusM = 14.0,
            burstHighM = 18.0,
            burstLowM = 8.0,
            burstReturnPoints = 2,
            maxBurstPoints = 12,
            radialSoftStartM = 2.0,
            radialKeepRatio = 0.28,
            radialHardCapM = 2.0
        ),
        MovementDetector.Mode.STOPPED to StopNormalizationParams(
            clusterHopRadiusM = 22.0,
            clusterHopMinPoints = 3,
            clusterHopMaxPoints = 24,
            clusterHopMaxDurationMs = 8 * 60_000L,
            residualSigma = 3.5,
            noiseRadiusM = 5.0,
            hardRadiusM = 20.0,
            burstHighM = 24.0,
            burstLowM = 10.0,
            burstReturnPoints = 2,
            maxBurstPoints = 10,
            radialSoftStartM = 10.0,
            radialKeepRatio = 0.45,
            radialHardCapM = 24.0
        )
    )

    enum class MarkerSurface {
        APP_MAP,
        WIDGET
    }

    /**
     * 指定された時刻を含む「ログ収集日（3時切り替え）」の開始時刻（3:00:00.000）を返す
     */
    fun getLoggingStart(timestamp: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            if (get(Calendar.HOUR_OF_DAY) < LOGGING_RESET_HOUR) add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, LOGGING_RESET_HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /** Haversine 公式による2点間距離（メートル） */
    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * asin(sqrt(a))
    }

    /** GPS 異常値フィルタ */
    fun filterOutliers(entries: List<LogEntry>): List<LogEntry> {
        if (entries.size <= 2) return entries
        val pass1 = mutableListOf<LogEntry>()
        for (e in entries) {
            val lat = e.latitude ?: continue
            val lon = e.longitude ?: continue
            if ((e.gpsAccuracy ?: 0f) > MAX_REASONABLE_ACCURACY_M) continue
            val prev = pass1.lastOrNull()
            if (prev != null) {
                val dt = (e.timestamp - prev.timestamp) / 1000.0
                val prevLat = prev.latitude ?: continue
                val prevLon = prev.longitude ?: continue
                if (
                    dt > 0 &&
                    (haversineM(prevLat, prevLon, lat, lon) / dt * 3.6) > MAX_REASONABLE_SPEED_KMH &&
                    !isGpsFreezeRecovery(pass1, e)
                ) {
                    continue
                }
            }
            pass1.add(e)
        }
        return removeIsolatedJumpClusters(
            removeTransientDetours(
                removeSinglePointSpikes(pass1)
            )
        )
    }

    private fun isGpsFreezeRecovery(previousEntries: List<LogEntry>, recovery: LogEntry): Boolean {
        if (previousEntries.size < 2) return false
        val recoveryLat = recovery.latitude ?: return false
        val recoveryLon = recovery.longitude ?: return false
        val lastFrozen = previousEntries.last()
        val lastFrozenLat = lastFrozen.latitude ?: return false
        val lastFrozenLon = lastFrozen.longitude ?: return false

        var startIndex = previousEntries.lastIndex
        while (startIndex > 0) {
            val candidate = previousEntries[startIndex - 1]
            val candidateLat = candidate.latitude ?: break
            val candidateLon = candidate.longitude ?: break
            if (haversineM(lastFrozenLat, lastFrozenLon, candidateLat, candidateLon) > GPS_FREEZE_SAME_POINT_RADIUS_M) {
                break
            }
            startIndex -= 1
        }

        val freezeDurationMs = lastFrozen.timestamp - previousEntries[startIndex].timestamp
        val jumpDistanceM = haversineM(lastFrozenLat, lastFrozenLon, recoveryLat, recoveryLon)
        val jumpDtSec = ((recovery.timestamp - lastFrozen.timestamp).coerceAtLeast(1L)) / 1000.0
        val jumpSpeedKmh = jumpDistanceM / jumpDtSec * 3.6
        return freezeDurationMs >= GPS_FREEZE_MIN_DURATION_MS &&
            jumpDistanceM >= GPS_FREEZE_RECOVERY_MIN_JUMP_M &&
            jumpSpeedKmh >= GPS_FREEZE_RECOVERY_MIN_SPEED_KMH
    }

    /**
     * 前後の点へすぐ戻る単発ジャンプを除去する。
     * 前後点が近いのに中央点だけ大きく外れている場合、その中央点をスパイクとして捨てる。
     */
    private fun removeSinglePointSpikes(entries: List<LogEntry>): List<LogEntry> {
        if (entries.size <= 2) return entries
        val result = mutableListOf<LogEntry>()
        result += entries.first()
        for (i in 1 until entries.lastIndex) {
            val prev = result.last()
            val current = entries[i]
            val next = entries[i + 1]
            if (isGpsFreezeRecovery(result, current) || !isSinglePointSpike(prev, current, next)) {
                result += current
            }
        }
        result += entries.last()
        return result
    }

    private fun isSinglePointSpike(prev: LogEntry, current: LogEntry, next: LogEntry): Boolean {
        val prevLat = prev.latitude ?: return false
        val prevLon = prev.longitude ?: return false
        val curLat = current.latitude ?: return false
        val curLon = current.longitude ?: return false
        val nextLat = next.latitude ?: return false
        val nextLon = next.longitude ?: return false

        val dtPrevMs = current.timestamp - prev.timestamp
        val dtNextMs = next.timestamp - current.timestamp
        if (dtPrevMs !in 1..SINGLE_POINT_SPIKE_MAX_DURATION_MS) return false
        if (dtNextMs !in 1..SINGLE_POINT_SPIKE_MAX_DURATION_MS) return false

        val dtTotalMs = next.timestamp - prev.timestamp
        if (dtTotalMs <= 0L) return false
        val fraction = ((current.timestamp - prev.timestamp).toDouble() / dtTotalMs.toDouble())
            .coerceIn(0.0, 1.0)
        val expectedLat = prevLat + (nextLat - prevLat) * fraction
        val expectedLon = prevLon + (nextLon - prevLon) * fraction
        val deviationM = haversineM(expectedLat, expectedLon, curLat, curLon)
        val deviationSpeedKmh = (2.0 * deviationM) / (dtTotalMs / 1000.0) * 3.6
        return deviationSpeedKmh >= SINGLE_POINT_SPIKE_DEVIATION_SPEED_KMH
    }

    /**
     * 高速移動中に一時的に大きく横へ飛んでから戻る点を除去する。
     * 前後点は進行しているが、中央点だけ進行線から大きく外れているケースを対象とする。
     */
    private fun removeTransientDetours(entries: List<LogEntry>): List<LogEntry> {
        if (entries.size <= 2) return entries
        val result = mutableListOf<LogEntry>()
        result += entries.first()
        for (i in 1 until entries.lastIndex) {
            val prev = result.last()
            val current = entries[i]
            val next = entries[i + 1]
            if (isGpsFreezeRecovery(result, current) || !isTransientDetour(prev, current, next)) {
                result += current
            }
        }
        result += entries.last()
        return result
    }

    private fun isTransientDetour(prev: LogEntry, current: LogEntry, next: LogEntry): Boolean {
        val prevLat = prev.latitude ?: return false
        val prevLon = prev.longitude ?: return false
        val curLat = current.latitude ?: return false
        val curLon = current.longitude ?: return false
        val nextLat = next.latitude ?: return false
        val nextLon = next.longitude ?: return false

        val dtPrevMs = current.timestamp - prev.timestamp
        val dtNextMs = next.timestamp - current.timestamp
        if (dtPrevMs !in 1..TRANSIENT_DETOUR_MAX_DURATION_MS) return false
        if (dtNextMs !in 1..TRANSIENT_DETOUR_MAX_DURATION_MS) return false

        val prevToNext = haversineM(prevLat, prevLon, nextLat, nextLon)
        if (prevToNext < TRANSIENT_DETOUR_MIN_NEIGHBOR_DISTANCE_M) return false

        val prevToCurrent = haversineM(prevLat, prevLon, curLat, curLon)
        val currentToNext = haversineM(curLat, curLon, nextLat, nextLon)
        val extraDistance = prevToCurrent + currentToNext - prevToNext
        if (extraDistance < TRANSIENT_DETOUR_MIN_EXTRA_DISTANCE_M) return false
        if (extraDistance < prevToNext * (TRANSIENT_DETOUR_MIN_EXTRA_RATIO - 1.0)) return false

        val lateralDistance = distancePointToSegmentM(
            pointLat = curLat,
            pointLon = curLon,
            startLat = prevLat,
            startLon = prevLon,
            endLat = nextLat,
            endLon = nextLon
        )
        if (lateralDistance < TRANSIENT_DETOUR_MIN_LATERAL_DISTANCE_M) return false

        val turnAngle = abs(turnAngleDeg(prevLat, prevLon, curLat, curLon, nextLat, nextLon))
        return turnAngle >= TRANSIENT_DETOUR_MIN_TURN_ANGLE_DEG
    }

    private fun distancePointToSegmentM(
        pointLat: Double,
        pointLon: Double,
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): Double {
        val refLat = Math.toRadians((startLat + endLat + pointLat) / 3.0)
        fun toLocalXY(lat: Double, lon: Double): Pair<Double, Double> {
            val x = Math.toRadians(lon) * cos(refLat) * 6_371_000.0
            val y = Math.toRadians(lat) * 6_371_000.0
            return x to y
        }

        val (px, py) = toLocalXY(pointLat, pointLon)
        val (ax, ay) = toLocalXY(startLat, startLon)
        val (bx, by) = toLocalXY(endLat, endLon)
        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay
        val lenSq = abx * abx + aby * aby
        if (lenSq <= 1e-6) return hypot(px - ax, py - ay)
        val t = ((apx * abx + apy * aby) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + abx * t
        val cy = ay + aby * t
        return hypot(px - cx, py - cy)
    }

    private fun turnAngleDeg(
        prevLat: Double,
        prevLon: Double,
        curLat: Double,
        curLon: Double,
        nextLat: Double,
        nextLon: Double
    ): Double {
        val refLat = Math.toRadians((prevLat + curLat + nextLat) / 3.0)
        fun toVector(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Pair<Double, Double> {
            val x = Math.toRadians(toLon - fromLon) * cos(refLat) * 6_371_000.0
            val y = Math.toRadians(toLat - fromLat) * 6_371_000.0
            return x to y
        }

        val (v1x, v1y) = toVector(prevLat, prevLon, curLat, curLon)
        val (v2x, v2y) = toVector(curLat, curLon, nextLat, nextLon)
        val len1 = hypot(v1x, v1y)
        val len2 = hypot(v2x, v2y)
        if (len1 <= 1e-6 || len2 <= 1e-6) return 0.0
        val dot = (v1x * v2x + v1y * v2y) / (len1 * len2)
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }

    /**
     * 短時間だけ別地点群へ飛んでから本流へ戻る誤クラスタを除去する。
     * 進入と退出の両方が大きなジャンプで、その間の点数と継続時間が短い場合に捨てる。
     */
    private fun removeIsolatedJumpClusters(entries: List<LogEntry>): List<LogEntry> {
        if (entries.size <= 3) return entries
        val keep = BooleanArray(entries.size) { true }
        var i = 0
        while (i < entries.lastIndex - 1) {
            if (!isSuspiciousBoundary(entries[i], entries[i + 1])) {
                i++
                continue
            }

            var endBoundary = -1
            for (j in (i + 1) until entries.lastIndex) {
                if (isSuspiciousBoundary(entries[j], entries[j + 1])) {
                    endBoundary = j
                    break
                }
            }
            if (endBoundary == -1) break

            val clusterStart = i + 1
            val clusterEnd = endBoundary
            val clusterPointCount = clusterEnd - clusterStart + 1
            val clusterDuration = entries[clusterEnd].timestamp - entries[clusterStart].timestamp

            if (
                clusterPointCount in 1..ISOLATED_CLUSTER_MAX_POINTS &&
                clusterDuration in 0..ISOLATED_CLUSTER_MAX_DURATION_MS
            ) {
                for (idx in clusterStart..clusterEnd) {
                    if (!isGpsFreezeRecovery(entries.subList(0, idx), entries[idx])) {
                        keep[idx] = false
                    }
                }
                i = endBoundary + 1
            } else {
                i++
            }
        }

        return entries.filterIndexed { index, _ -> keep[index] }
    }

    private fun isSuspiciousBoundary(from: LogEntry, to: LogEntry): Boolean {
        val fromLat = from.latitude ?: return false
        val fromLon = from.longitude ?: return false
        val toLat = to.latitude ?: return false
        val toLon = to.longitude ?: return false
        val dtMs = to.timestamp - from.timestamp
        if (dtMs !in 1..CLUSTER_BOUNDARY_MAX_DURATION_MS) return false
        val distance = haversineM(fromLat, fromLon, toLat, toLon)
        if (distance < CLUSTER_BOUNDARY_MIN_DISTANCE_M) return false
        val speedKmh = distance / (dtMs / 1000.0) * 3.6
        return speedKmh >= CLUSTER_BOUNDARY_MIN_SPEED_KMH
    }

    /**
     * 地図表示用の共通前処理。
     * 対象日で絞り込み、位置付きレコードのみを時系列順へ並べ、外れ値を除去する。
     */
    fun prepareMapEntries(
        entries: List<LogEntry>,
        dayStart: Long,
        dayEndExclusive: Long = dayStart + DAY_MS
    ): List<LogEntry> {
        val dayEntries = entries
            .asSequence()
            .filter { it.timestamp in dayStart until dayEndExclusive && it.hasLocation }
            .sortedBy { it.timestamp }
            .toList()
        return filterOutliers(dayEntries)
    }

    /**
     * 表示専用の停止補正。
     * 取得値 / Room 保存値は変更せず、MotionSample から再構成した表示モードを使って
     * viewer で試験した `復帰バースト -> 偽クラスタ滞在 -> 停止標準化` を表示系列だけへ適用する。
     */
    fun normalizeStopsForDisplay(
        entries: List<LogEntry>,
        motionSamples: List<MotionSample>
    ): List<LogEntry> {
        if (entries.isEmpty() || motionSamples.isEmpty()) return entries

        val modeStates = inferModeStates(motionSamples.sortedBy { it.timestamp }, entries)
        if (modeStates.isEmpty()) return entries

        val displayPoints = buildDisplayPoints(entries, modeStates)
        if (displayPoints.size < STOP_NORMALIZATION_MIN_POINT_COUNT) return entries

        val returnBurstRepair = detectReturnJumpBursts(displayPoints)
        val clusterHopRepair = detectClusterHopStays(returnBurstRepair.points)
        val sourcePoints = clusterHopRepair.points.map { it.copy() }
        val normalizedPoints = sourcePoints.toMutableList()

        var index = 0
        while (index < sourcePoints.size) {
            val mode = sourcePoints[index].displayMode
            val params = STOP_NORMALIZATION_PARAMS[mode]
            if (params == null || isPreFixedPoint(sourcePoints[index])) {
                index += 1
                continue
            }

            var segmentEnd = index + 1
            while (
                segmentEnd < sourcePoints.size &&
                sourcePoints[segmentEnd].displayMode == mode &&
                !isPreFixedPoint(sourcePoints[segmentEnd])
            ) {
                segmentEnd += 1
            }

            val segment = sourcePoints.subList(index, segmentEnd)
            val durationMs = segment.last().timestamp - segment.first().timestamp
            val qualifies =
                segment.size >= params.minSegmentPointCount &&
                    durationMs >= params.minSegmentDurationMs

            if (qualifies) {
                val windowCount = if (mode == MovementDetector.Mode.DEVICE_STILL) 5 else 3
                val correction = deviationSeriesStopCorrection(segment, windowCount, params)
                correction.points.forEachIndexed { offset, point ->
                    normalizedPoints[index + offset] = point
                }
                index = segmentEnd
            } else {
                index += 1
            }
        }

        val stayCollapsedPoints = collapseConstantRegions(normalizedPoints)
        val correctedEntries = entries.toMutableList()
        stayCollapsedPoints.forEach { point ->
            val original = correctedEntries[point.sourceIndex]
            correctedEntries[point.sourceIndex] = original.copy(
                latitude = point.lat,
                longitude = point.lon
            )
        }
        val keepSourceIndexes = stayCollapsedPoints.map { it.sourceIndex }.toHashSet()
        return correctedEntries.filterIndexed { index, _ -> index in keepSourceIndexes }
    }

    data class Bounds(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    fun calculateBounds(entries: List<LogEntry>): Bounds? {
        if (entries.isEmpty()) return null
        return Bounds(
            minLat = entries.minOf { it.latitude ?: Double.MAX_VALUE },
            maxLat = entries.maxOf { it.latitude ?: -Double.MAX_VALUE },
            minLon = entries.minOf { it.longitude ?: Double.MAX_VALUE },
            maxLon = entries.maxOf { it.longitude ?: -Double.MAX_VALUE }
        )
    }

    // ── 滞在集約 ──────────────────────────────────────────────

    data class TrackPoint(
        val lat: Double, val lon: Double,
        val timestamp: Long,
        val distRatio: Float,
        val displayMode: MovementDetector.Mode = MovementDetector.Mode.UNKNOWN,
        val isStop: Boolean = false, // DEVICE_STILL / STOPPED 領域を代表点へ畳んだ停止点
        val stopCount: Int = 0,
        val isGapBreak: Boolean = false
    )

    data class TrackSegment(
        val displayMode: MovementDetector.Mode,
        val points: List<TrackPoint>
    )

    /**
     * 頑健な滞在集約アルゴリズム (Time-based Density Clustering)
     * 50m以内に10分以上留まった点を「1つの滞在地点」として重心集約。
     */
    fun clusterStops(
        entries: List<LogEntry>,
        radiusM: Double = 50.0,
        minDurationMs: Long = 10 * 60_000L
    ): List<TrackPoint> {
        val locatedEntries = entries.filter { it.hasLocation }
        if (locatedEntries.isEmpty()) return emptyList()

        val cumDist = cumulativeDistances(locatedEntries)
        val totalDist = cumDist.last().coerceAtLeast(1.0)
        val result = mutableListOf<TrackPoint>()
        
        var i = 0
        while (i < locatedEntries.size) {
            var j = i + 1
            var lastValidJ = i
            
            // i番目の点を起点に、連続して radiusM 以内に収まる最大の区間 [i, j) を探す
            while (j < locatedEntries.size) {
                val iLat = locatedEntries[i].latitude ?: break
                val iLon = locatedEntries[i].longitude ?: break
                val jLat = locatedEntries[j].latitude ?: break
                val jLon = locatedEntries[j].longitude ?: break
                val d = haversineM(iLat, iLon, jLat, jLon)
                if (d <= radiusM) {
                    lastValidJ = j; j++
                } else {
                    // 1点だけ外れても、その次が戻ってくるなら許容する（GPSジャンプ対策）
                    val nextJ = j + 1
                    if (nextJ < locatedEntries.size) {
                        val nextLat = locatedEntries[nextJ].latitude ?: break
                        val nextLon = locatedEntries[nextJ].longitude ?: break
                        val d2 = haversineM(iLat, iLon, nextLat, nextLon)
                        if (d2 <= radiusM) { j = nextJ; continue }
                    }
                    break
                }
            }
            
            val duration = locatedEntries[lastValidJ].timestamp - locatedEntries[i].timestamp
            if (duration >= minDurationMs && (lastValidJ - i) >= 1) {
                // 滞在確定：重心を計算
                val cluster = locatedEntries.subList(i, lastValidJ + 1)
                result.add(TrackPoint(
                    lat = cluster.mapNotNull { it.latitude }.average(),
                    lon = cluster.mapNotNull { it.longitude }.average(),
                    timestamp = locatedEntries[i].timestamp,
                    distRatio = (cumDist[i] / totalDist).toFloat(),
                    isStop = true,
                    stopCount = cluster.size
                ))
                i = lastValidJ + 1
            } else {
                // 移動点：1点だけ採用して次へ
                result.add(TrackPoint(
                    locatedEntries[i].latitude ?: continue, locatedEntries[i].longitude ?: continue,
                    locatedEntries[i].timestamp, (cumDist[i] / totalDist).toFloat()
                ))
                i++
            }
        }
        return result
    }

    private fun markerScale(surface: MarkerSurface): Float = when (surface) {
        MarkerSurface.APP_MAP -> APP_MAP_MARKER_SCALE
        MarkerSurface.WIDGET -> WIDGET_MARKER_SCALE
    }

    private fun MarkerStyle.scaled(scale: Float): MarkerStyle = MarkerStyle(
        outerRadiusPx = outerRadiusPx * scale,
        innerRadiusPx = innerRadiusPx * scale,
        strokeWidthPx = strokeWidthPx * scale
    )

    private fun dpToPx(dp: Float, density: Float): Float = dp * density.coerceAtLeast(1f)

    fun stopMarkerStyle(stopCount: Int, surface: MarkerSurface): MarkerStyle {
        val extraRadius = (stopCount / STOP_MARKER_POINTS_PER_STEP)
            .coerceAtMost(STOP_MARKER_MAX_EXTRA_RADIUS_PX.toInt())
            .toFloat()
        val outer = STOP_MARKER_OUTER_RADIUS_PX + extraRadius
        return MarkerStyle(
            outerRadiusPx = outer,
            innerRadiusPx = (outer - 3f).coerceAtLeast(4f)
        ).scaled(markerScale(surface))
    }

    fun startMarkerStyle(surface: MarkerSurface): MarkerStyle = MarkerStyle(
        outerRadiusPx = START_MARKER_OUTER_RADIUS_PX,
        innerRadiusPx = START_MARKER_INNER_RADIUS_PX,
        strokeWidthPx = MARKER_RING_STROKE_WIDTH_PX
    ).scaled(markerScale(surface))

    fun currentMarkerStyle(surface: MarkerSurface): MarkerStyle = MarkerStyle(
        outerRadiusPx = CURRENT_MARKER_OUTER_RADIUS_PX,
        innerRadiusPx = CURRENT_MARKER_INNER_RADIUS_PX,
        strokeWidthPx = MARKER_RING_STROKE_WIDTH_PX
    ).scaled(markerScale(surface))

    fun directionArrowTextSize(surface: MarkerSurface): Float =
        DIRECTION_ARROW_TEXT_SIZE_PX * markerScale(surface)

    fun directionArrowBitmapPadding(surface: MarkerSurface): Float =
        DIRECTION_ARROW_BITMAP_PADDING_PX * markerScale(surface)

    fun directionArrowOutlineWidth(surface: MarkerSurface): Float =
        DIRECTION_ARROW_OUTLINE_WIDTH_PX * markerScale(surface)

    fun widgetDirectionArrowParams(density: Float): ScreenDirectionArrowParams = ScreenDirectionArrowParams(
        minSpacingPx = dpToPx(WIDGET_DIRECTION_ARROW_MIN_SPACING_DP, density),
        minSegmentPx = dpToPx(WIDGET_DIRECTION_ARROW_MIN_SEGMENT_DP, density),
        startEndSkipPx = dpToPx(WIDGET_DIRECTION_ARROW_START_END_SKIP_DP, density)
    )

    fun appDirectionArrowParams(density: Float): ScreenDirectionArrowParams = ScreenDirectionArrowParams(
        minSpacingPx = dpToPx(APP_DIRECTION_ARROW_MIN_SPACING_DP, density),
        minSegmentPx = dpToPx(APP_DIRECTION_ARROW_MIN_SEGMENT_DP, density),
        startEndSkipPx = dpToPx(APP_DIRECTION_ARROW_START_END_SKIP_DP, density)
    )

    fun widgetStopMarkerStyle(stopCount: Int, density: Float): MarkerStyle {
        val extraRadius = (stopCount / STOP_MARKER_POINTS_PER_STEP)
            .coerceAtMost(max(0, WIDGET_STOP_MARKER_MAX_EXTRA_RADIUS_DP.toInt()))
            .toFloat()
        val outerDp = WIDGET_STOP_MARKER_OUTER_RADIUS_DP + extraRadius
        return MarkerStyle(
            outerRadiusPx = dpToPx(outerDp, density),
            innerRadiusPx = dpToPx((outerDp - 1.2f).coerceAtLeast(2.4f), density)
        )
    }

    fun widgetStartMarkerStyle(density: Float): MarkerStyle = MarkerStyle(
        outerRadiusPx = dpToPx(WIDGET_START_MARKER_OUTER_RADIUS_DP, density),
        innerRadiusPx = dpToPx(WIDGET_START_MARKER_INNER_RADIUS_DP, density),
        strokeWidthPx = dpToPx(WIDGET_MARKER_RING_STROKE_WIDTH_DP, density)
    )

    fun widgetCurrentMarkerStyle(density: Float): MarkerStyle = MarkerStyle(
        outerRadiusPx = dpToPx(WIDGET_CURRENT_MARKER_OUTER_RADIUS_DP, density),
        innerRadiusPx = dpToPx(WIDGET_CURRENT_MARKER_INNER_RADIUS_DP, density),
        strokeWidthPx = dpToPx(WIDGET_MARKER_RING_STROKE_WIDTH_DP, density)
    )

    fun widgetDirectionArrowTextSize(density: Float): Float =
        dpToPx(WIDGET_DIRECTION_ARROW_TEXT_SIZE_DP, density)

    fun widgetDirectionArrowBitmapPadding(density: Float): Float =
        dpToPx(WIDGET_DIRECTION_ARROW_BITMAP_PADDING_DP, density)

    fun widgetDirectionArrowOutlineWidth(density: Float): Float =
        dpToPx(WIDGET_DIRECTION_ARROW_OUTLINE_WIDTH_DP, density)

    fun mapTrackStrokeWidthPx(surface: MarkerSurface, density: Float): Float {
        val widthDp = when (surface) {
            MarkerSurface.APP_MAP -> APP_MAP_TRACK_STROKE_WIDTH_DP
            MarkerSurface.WIDGET -> WIDGET_MAP_TRACK_STROKE_WIDTH_DP
        }
        return widthDp * density.coerceAtLeast(1f)
    }

    fun modeColor(mode: MovementDetector.Mode): Int = when (mode) {
        MovementDetector.Mode.DEVICE_STILL -> MODE_COLOR_DEVICE_STILL
        MovementDetector.Mode.STOPPED -> MODE_COLOR_STOPPED
        MovementDetector.Mode.WALKING -> MODE_COLOR_WALKING
        MovementDetector.Mode.VEHICLE -> MODE_COLOR_VEHICLE
        MovementDetector.Mode.UNKNOWN -> MODE_COLOR_VEHICLE
    }

    /**
     * 地図表示用の折れ線だけを軽く平準化する。
     * 停止点は固定し、連続した移動区間ごとに移動平均をかける。
     */
    fun smoothTrackForDisplay(
        track: List<TrackPoint>,
        windowRadius: Int = DISPLAY_SMOOTHING_WINDOW_RADIUS
    ): List<TrackPoint> {
        if (track.size < 5 || windowRadius <= 0) return track

        val smoothed = track.toMutableList()
        var segmentStart = 0
        while (segmentStart < track.size) {
            while (segmentStart < track.size && track[segmentStart].isStop) {
                segmentStart++
            }
            if (segmentStart >= track.size) break

            var segmentEndExclusive = segmentStart
            while (segmentEndExclusive < track.size && !track[segmentEndExclusive].isStop) {
                segmentEndExclusive++
            }

            val segment = track.subList(segmentStart, segmentEndExclusive)
            if (segment.size >= windowRadius * 2 + 1) {
                for (index in segment.indices) {
                    if (index < windowRadius || index >= segment.size - windowRadius) continue
                    val neighbors = segment.subList(index - windowRadius, index + windowRadius + 1)
                    smoothed[segmentStart + index] = segment[index].copy(
                        lat = neighbors.map { it.lat }.average(),
                        lon = neighbors.map { it.lon }.average()
                    )
                }
            }
            segmentStart = segmentEndExclusive
        }
        return smoothed
    }

    /**
     * viewer の GPS 平準化 ON と揃えるための、地図表示専用系列。
     * STAY 領域は記録済み stay point 1 点へ畳み、CONSTANT_MOVE は記録点をそのまま使う。
     * その後、STAY を境界にした移動チャンクをスプライン補間する。
     */
    fun buildDisplayPolyline(
        entries: List<LogEntry>,
        motionSamples: List<MotionSample>,
        windowRadius: Int = DISPLAY_SMOOTHING_WINDOW_RADIUS,
        splineSamplesPerSegment: Int = MAP_SPLINE_SAMPLES_PER_SEGMENT
    ): List<TrackPoint> {
        val locatedEntries = entries.filter { it.hasLocation }
        if (locatedEntries.isEmpty()) return emptyList()

        val modeStates = inferModeStates(motionSamples.sortedBy { it.timestamp }, entries)
        val baseTrack = if (modeStates.isNotEmpty()) {
            val displayPoints = buildDisplayPoints(locatedEntries, modeStates)
            displayPointsToTrack(collapseConstantRegions(displayPoints))
        } else {
            rawEntriesToTrack(locatedEntries)
        }
        val freezeAwareTrack = markGpsFreezeBreaks(baseTrack)
        val interpolatedTrack = interpolateGpsGapsForDisplay(freezeAwareTrack)
        val smoothedTrack = smoothPolylineTrack(interpolatedTrack, windowRadius)
        return splineMovingTrack(smoothedTrack, samplesPerSegment = splineSamplesPerSegment)
    }

    private fun markGpsFreezeBreaks(track: List<TrackPoint>): List<TrackPoint> {
        if (track.size < 3) return track
        val result = track.toMutableList()
        var index = 0
        while (index < result.lastIndex) {
            val start = result[index]
            var end = index + 1
            while (
                end < result.size &&
                haversineM(start.lat, start.lon, result[end].lat, result[end].lon) <= GPS_FREEZE_SAME_POINT_RADIUS_M
            ) {
                end += 1
            }

            val lastFrozenIndex = end - 1
            val recoveryIndex = end
            if (lastFrozenIndex > index && recoveryIndex < result.size) {
                val durationMs = result[lastFrozenIndex].timestamp - start.timestamp
                val lastFrozen = result[lastFrozenIndex]
                val recovery = result[recoveryIndex]
                val jumpDistanceM = haversineM(lastFrozen.lat, lastFrozen.lon, recovery.lat, recovery.lon)
                val jumpDtSec = ((recovery.timestamp - lastFrozen.timestamp).coerceAtLeast(1L)) / 1000.0
                val jumpSpeedKmh = jumpDistanceM / jumpDtSec * 3.6
                if (
                    durationMs >= GPS_FREEZE_MIN_DURATION_MS &&
                    jumpDistanceM >= GPS_FREEZE_RECOVERY_MIN_JUMP_M &&
                    jumpSpeedKmh >= GPS_FREEZE_RECOVERY_MIN_SPEED_KMH
                ) {
                    result[recoveryIndex] = recovery.copy(isGapBreak = true)
                }
            }
            index = end.coerceAtLeast(index + 1)
        }
        return result
    }

    private fun interpolateGpsGapsForDisplay(track: List<TrackPoint>): List<TrackPoint> {
        if (track.size < 2) return track
        val result = mutableListOf<TrackPoint>()
        for (index in 0 until track.lastIndex) {
            val start = track[index]
            val end = track[index + 1]
            result += start

            val gapMs = end.timestamp - start.timestamp
            if (
                gapMs !in GPS_GAP_INTERPOLATION_MIN_MS..GPS_GAP_INTERPOLATION_MAX_MS ||
                start.isStop ||
                end.isStop ||
                end.isGapBreak
            ) {
                continue
            }

            var ts = start.timestamp + GPS_GAP_INTERPOLATION_INTERVAL_MS
            while (ts < end.timestamp) {
                val ratio = (ts - start.timestamp).toDouble() / gapMs.toDouble()
                result += TrackPoint(
                    lat = start.lat + (end.lat - start.lat) * ratio,
                    lon = start.lon + (end.lon - start.lon) * ratio,
                    timestamp = ts,
                    distRatio = 0f,
                    displayMode = if (ratio < 0.5) start.displayMode else end.displayMode,
                    isStop = false
                )
                ts += GPS_GAP_INTERPOLATION_INTERVAL_MS
            }
        }
        result += track.last()
        return recalculateTrackDistanceRatio(result)
    }

    private fun recalculateTrackDistanceRatio(track: List<TrackPoint>): List<TrackPoint> {
        if (track.isEmpty()) return track
        val cumulativeDistances = mutableListOf(0.0)
        for (index in 1 until track.size) {
            cumulativeDistances += cumulativeDistances.last() +
                haversineM(track[index - 1].lat, track[index - 1].lon, track[index].lat, track[index].lon)
        }
        val totalDistance = cumulativeDistances.last().coerceAtLeast(1.0)
        return track.mapIndexed { index, point ->
            point.copy(distRatio = (cumulativeDistances[index] / totalDistance).toFloat())
        }
    }

    private fun rawEntriesToTrack(locatedEntries: List<LogEntry>): List<TrackPoint> {
        val modeTimeline = emptyList<DisplayModeSample>()
        var modeIndex = 0
        var currentMode = MovementDetector.Mode.UNKNOWN
        val cumulativeDistances = cumulativeDistances(locatedEntries)
        val totalDistance = cumulativeDistances.last().coerceAtLeast(1.0)
        return locatedEntries.mapIndexed { index, entry ->
            while (modeIndex < modeTimeline.size && modeTimeline[modeIndex].timestamp <= entry.timestamp) {
                currentMode = modeTimeline[modeIndex].mode
                modeIndex += 1
            }
            TrackPoint(
                lat = entry.latitude ?: 0.0,
                lon = entry.longitude ?: 0.0,
                timestamp = entry.timestamp,
                distRatio = (cumulativeDistances[index] / totalDistance).toFloat(),
                displayMode = currentMode
            )
        }
    }

    private fun displayPointsToTrack(points: List<DisplayPoint>): List<TrackPoint> {
        if (points.isEmpty()) return emptyList()
        val cumulativeDistances = mutableListOf(0.0)
        for (index in 1 until points.size) {
            cumulativeDistances += cumulativeDistances.last() +
                haversineM(points[index - 1].lat, points[index - 1].lon, points[index].lat, points[index].lon)
        }
        val totalDistance = cumulativeDistances.last().coerceAtLeast(1.0)
        return points.mapIndexed { index, point ->
            TrackPoint(
                lat = point.lat,
                lon = point.lon,
                timestamp = point.timestamp,
                distRatio = (cumulativeDistances[index] / totalDistance).toFloat(),
                displayMode = point.displayMode,
                isStop = isStationaryDisplayMode(point.displayMode) ||
                    point.constantRegionKind == ConstantRegionKind.STAY
            )
        }
    }

    private fun smoothPolylineTrack(baseTrack: List<TrackPoint>, windowRadius: Int): List<TrackPoint> {
        if (baseTrack.size < windowRadius * 2 + 1 || windowRadius <= 0) return baseTrack

        val smoothed = baseTrack.toMutableList()
        for (index in windowRadius until baseTrack.size - windowRadius) {
            val neighbors = baseTrack.subList(index - windowRadius, index + windowRadius + 1)
            val currentMode = baseTrack[index].displayMode
            if (
                baseTrack[index].isStop ||
                baseTrack[index].isGapBreak ||
                currentMode == MovementDetector.Mode.VEHICLE ||
                neighbors.any { it.isStop || it.isGapBreak || it.displayMode != currentMode }
            ) {
                continue
            }
            smoothed[index] = baseTrack[index].copy(
                lat = neighbors.map { it.lat }.average(),
                lon = neighbors.map { it.lon }.average()
            )
        }
        return smoothed
    }

    private data class LocalPointM(
        val x: Double,
        val y: Double
    )

    private fun splineMovingTrack(
        track: List<TrackPoint>,
        samplesPerSegment: Int = MAP_SPLINE_SAMPLES_PER_SEGMENT
    ): List<TrackPoint> {
        if (track.size < 3 || samplesPerSegment <= 0) return track

        val result = mutableListOf<TrackPoint>()
        var index = 0
        while (index < track.size) {
            if (track[index].isStop) {
                result += track[index]
                index += 1
                continue
            }
            val chunkMode = track[index].displayMode
            var endExclusive = index + 1
            while (
                endExclusive < track.size &&
                !track[endExclusive].isStop &&
                !track[endExclusive].isGapBreak &&
                track[endExclusive].displayMode == chunkMode
            ) {
                endExclusive += 1
            }

            val chunk = track.subList(index, endExclusive)
            result.addAll(
                splineTrackChunk(chunk, samplesPerSegment)
            )
            index = endExclusive
        }
        return result
    }

    private fun splineTrackChunk(
        chunk: List<TrackPoint>,
        samplesPerSegment: Int,
        edgeLinearSegments: Int = MAP_SPLINE_EDGE_LINEAR_SEGMENTS
    ): List<TrackPoint> {
        if (chunk.size < 3) return chunk

        val originLat = chunk.first().lat
        val originLon = chunk.first().lon
        val projected = chunk.map { projectToLocalMeters(it.lat, it.lon, originLat, originLon) }
        val result = mutableListOf<TrackPoint>()
        result += chunk.first()
        val segmentCount = chunk.size - 1
        val splineStartSegment = edgeLinearSegments.coerceAtLeast(0)
        val splineEndExclusive = (segmentCount - edgeLinearSegments.coerceAtLeast(0)).coerceAtLeast(splineStartSegment)

        for (index in 0 until chunk.lastIndex) {
            val p0 = projected[max(0, index - 1)]
            val p1 = projected[index]
            val p2 = projected[index + 1]
            val p3 = projected[min(chunk.lastIndex, index + 2)]
            val start = chunk[index]
            val end = chunk[index + 1]
            val useSpline = index in splineStartSegment until splineEndExclusive

            if (useSpline) {
                for (sample in 1..samplesPerSegment) {
                    val t = sample.toDouble() / (samplesPerSegment + 1)
                    val local = catmullRom(p0, p1, p2, p3, t, MAP_SPLINE_TENSION)
                    val (lat, lon) = unprojectFromLocalMeters(local, originLat, originLon)
                    result += start.copy(
                        lat = lat,
                        lon = lon,
                        timestamp = interpolateLong(start.timestamp, end.timestamp, t),
                        distRatio = interpolateFloat(start.distRatio, end.distRatio, t),
                        displayMode = start.displayMode,
                        isStop = false
                    )
                }
            }
            result += end
        }

        return result
    }

    private fun projectToLocalMeters(lat: Double, lon: Double, originLat: Double, originLon: Double): LocalPointM {
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLon = metersPerDegreeLat * cos(Math.toRadians(originLat)).coerceAtLeast(0.000001)
        return LocalPointM(
            x = (lon - originLon) * metersPerDegreeLon,
            y = (lat - originLat) * metersPerDegreeLat
        )
    }

    private fun unprojectFromLocalMeters(point: LocalPointM, originLat: Double, originLon: Double): Pair<Double, Double> {
        val metersPerDegreeLat = 111_320.0
        val metersPerDegreeLon = metersPerDegreeLat * cos(Math.toRadians(originLat)).coerceAtLeast(0.000001)
        return Pair(
            originLat + point.y / metersPerDegreeLat,
            originLon + point.x / metersPerDegreeLon
        )
    }

    private fun catmullRom(
        p0: LocalPointM,
        p1: LocalPointM,
        p2: LocalPointM,
        p3: LocalPointM,
        t: Double,
        tension: Double
    ): LocalPointM {
        val t2 = t * t
        val t3 = t2 * t
        val tangentScale = (1.0 - tension).coerceIn(0.0, 1.0)
        val m1x = tangentScale * (p2.x - p0.x)
        val m1y = tangentScale * (p2.y - p0.y)
        val m2x = tangentScale * (p3.x - p1.x)
        val m2y = tangentScale * (p3.y - p1.y)
        return LocalPointM(
            x =
                (2.0 * t3 - 3.0 * t2 + 1.0) * p1.x +
                    (t3 - 2.0 * t2 + t) * m1x +
                    (-2.0 * t3 + 3.0 * t2) * p2.x +
                    (t3 - t2) * m2x,
            y =
                (2.0 * t3 - 3.0 * t2 + 1.0) * p1.y +
                    (t3 - 2.0 * t2 + t) * m1y +
                    (-2.0 * t3 + 3.0 * t2) * p2.y +
                    (t3 - t2) * m2y
        )
    }

    private fun interpolateLong(start: Long, end: Long, t: Double): Long =
        (start + (end - start) * t).toLong()

    private fun interpolateFloat(start: Float, end: Float, t: Double): Float =
        (start + (end - start) * t).toFloat()

    fun splitTrackByMode(track: List<TrackPoint>): List<TrackSegment> {
        if (track.size < 2) return emptyList()
        val segments = mutableListOf<TrackSegment>()
        var currentMode = MovementDetector.Mode.UNKNOWN
        val currentPoints = mutableListOf<TrackPoint>()

        fun flushSegment() {
            if (currentPoints.size >= 2) {
                segments += TrackSegment(currentMode, currentPoints.toList())
            }
            currentPoints.clear()
        }

        for (index in 1 until track.size) {
            val point = track[index]
            val previous = track[index - 1]
            if (point.isGapBreak) {
                flushSegment()
                currentMode = point.displayMode
                currentPoints += point
                continue
            }
            if (previous.isStop || point.isStop) {
                flushSegment()
                if (!point.isStop) {
                    currentMode = point.displayMode
                    currentPoints += point
                }
                continue
            }
            if (currentPoints.isEmpty()) {
                currentMode = previous.displayMode
                currentPoints += previous
            }
            if (point.displayMode == currentMode) {
                currentPoints += point
            } else {
                if (currentPoints.size == 1) {
                    currentPoints += point
                }
                flushSegment()
                currentMode = point.displayMode
                currentPoints += previous
                currentPoints += point
            }
        }
        flushSegment()
        return segments
    }

    fun buildGpsGapBreakSegments(track: List<TrackPoint>): List<TrackSegment> {
        if (track.size < 2) return emptyList()
        val segments = mutableListOf<TrackSegment>()
        for (index in 1 until track.size) {
            val recovery = track[index]
            if (!recovery.isGapBreak) continue
            var startIndex = index - 1
            val frozenAnchor = track[startIndex]
            while (
                startIndex > 0 &&
                !track[startIndex - 1].isGapBreak &&
                haversineM(
                    frozenAnchor.lat,
                    frozenAnchor.lon,
                    track[startIndex - 1].lat,
                    track[startIndex - 1].lon
                ) <= GPS_FREEZE_SAME_POINT_RADIUS_M
            ) {
                startIndex -= 1
            }
            segments += TrackSegment(
                displayMode = recovery.displayMode,
                points = listOf(track[startIndex], recovery)
            )
        }
        return segments
    }

    fun computeDirectionArrowMarkers(
        track: List<TrackPoint>,
        params: DirectionArrowParams = DirectionArrowParams()
    ): List<DirectionArrowMarker> {
        val markers = mutableListOf<DirectionArrowMarker>()
        var index = 0
        while (index < track.size) {
            while (index < track.size && (track[index].isStop || track[index].isGapBreak)) index += 1
            val start = index
            while (index < track.size && !track[index].isStop && !track[index].isGapBreak) index += 1
            if (index - start >= 3) {
                markers += computeDirectionArrowMarkersForChunk(track.subList(start, index), params)
            }
        }
        return markers
    }

    fun computeDirectionArrowMarkersOnScreen(
        track: List<TrackPoint>,
        projectToScreen: (TrackPoint) -> Pair<Float, Float>,
        params: ScreenDirectionArrowParams
    ): List<DirectionArrowMarker> {
        val markers = mutableListOf<DirectionArrowMarker>()
        var index = 0
        while (index < track.size) {
            while (index < track.size && (track[index].isStop || track[index].isGapBreak)) index += 1
            val start = index
            while (index < track.size && !track[index].isStop && !track[index].isGapBreak) index += 1
            if (index - start >= 3) {
                markers += computeDirectionArrowMarkersForScreenChunk(
                    track = track.subList(start, index),
                    projectToScreen = projectToScreen,
                    params = params
                )
            }
        }
        return markers
    }

    private fun computeDirectionArrowMarkersForChunk(
        track: List<TrackPoint>,
        params: DirectionArrowParams
    ): List<DirectionArrowMarker> {
        if (track.size < 3) return emptyList()
        val cumulativeDistance = DoubleArray(track.size)
        for (index in 1 until track.size) {
            cumulativeDistance[index] = cumulativeDistance[index - 1] +
                haversineM(track[index - 1].lat, track[index - 1].lon, track[index].lat, track[index].lon)
        }
        val totalDistance = cumulativeDistance.last()
        if (totalDistance < params.startEndSkipM * 2.0) return emptyList()

        val markers = mutableListOf<DirectionArrowMarker>()
        var lastPlacedDistance = -params.minSpacingM
        for (index in 1 until track.lastIndex) {
            val distanceAlong = cumulativeDistance[index]
            if (distanceAlong < params.startEndSkipM) continue
            if ((totalDistance - distanceAlong) < params.startEndSkipM) continue
            if ((distanceAlong - lastPlacedDistance) < params.minSpacingM) continue

            val localAngles = mutableListOf<Double>()
            val localStart = max(0, index - params.localBearingWindow)
            val localEnd = min(track.lastIndex - 1, index + params.localBearingWindow)
            for (segmentIndex in localStart..localEnd) {
                val startPoint = track[segmentIndex]
                val endPoint = track[segmentIndex + 1]
                val segmentDistance = haversineM(startPoint.lat, startPoint.lon, endPoint.lat, endPoint.lon)
                if (segmentDistance < params.minSegmentM) continue
                val angle = bearingDegrees(startPoint.lat, startPoint.lon, endPoint.lat, endPoint.lon)
                localAngles += angle
            }
            val meanAngle = if (localAngles.isNotEmpty()) {
                circularMeanDegrees(localAngles)
            } else {
                val startPoint = track[localStart]
                val endPoint = track[min(track.lastIndex, index + params.localBearingWindow)]
                val tangentDistance = haversineM(startPoint.lat, startPoint.lon, endPoint.lat, endPoint.lon)
                if (tangentDistance < params.minSegmentM) continue
                bearingDegrees(startPoint.lat, startPoint.lon, endPoint.lat, endPoint.lon)
            }

            markers += DirectionArrowMarker(
                lat = track[index].lat,
                lon = track[index].lon,
                angleDeg = (meanAngle - 90.0).toFloat(),
                displayMode = track[index].displayMode
            )
            lastPlacedDistance = distanceAlong
        }
        return markers
    }

    private fun computeDirectionArrowMarkersForScreenChunk(
        track: List<TrackPoint>,
        projectToScreen: (TrackPoint) -> Pair<Float, Float>,
        params: ScreenDirectionArrowParams
    ): List<DirectionArrowMarker> {
        if (track.size < 3) return emptyList()
        val screenPoints = track.map(projectToScreen)
        val cumulativeDistance = FloatArray(track.size)
        for (index in 1 until track.size) {
            val (startX, startY) = screenPoints[index - 1]
            val (endX, endY) = screenPoints[index]
            cumulativeDistance[index] = cumulativeDistance[index - 1] +
                hypot(endX - startX, endY - startY)
        }
        val totalDistance = cumulativeDistance.last()
        if (totalDistance < params.startEndSkipPx * 2f) return emptyList()

        val markers = mutableListOf<DirectionArrowMarker>()
        var distanceAlong = params.startEndSkipPx
        val endDistance = totalDistance - params.startEndSkipPx
        while (distanceAlong <= endDistance) {
            val center = interpolateTrackAtScreenDistance(track, screenPoints, cumulativeDistance, distanceAlong)
                ?: run {
                    distanceAlong += params.minSpacingPx
                    continue
                }
            val before = interpolateTrackAtScreenDistance(
                track,
                screenPoints,
                cumulativeDistance,
                (distanceAlong - params.minSegmentPx).coerceAtLeast(0f)
            )
            val after = interpolateTrackAtScreenDistance(
                track,
                screenPoints,
                cumulativeDistance,
                (distanceAlong + params.minSegmentPx).coerceAtMost(totalDistance)
            )
            if (before == null || after == null) {
                distanceAlong += params.minSpacingPx
                continue
            }
            val tangentDistance = hypot(after.screenX - before.screenX, after.screenY - before.screenY)
            if (tangentDistance < params.minSegmentPx) {
                distanceAlong += params.minSpacingPx
                continue
            }
            val meanAngle = Math.toDegrees(
                atan2(
                    (after.screenY - before.screenY).toDouble(),
                    (after.screenX - before.screenX).toDouble()
                )
            )

            markers += DirectionArrowMarker(
                lat = center.lat,
                lon = center.lon,
                angleDeg = meanAngle.toFloat(),
                displayMode = center.displayMode
            )
            distanceAlong += params.minSpacingPx
        }
        return markers
    }

    private data class ScreenTrackSample(
        val lat: Double,
        val lon: Double,
        val screenX: Float,
        val screenY: Float,
        val displayMode: MovementDetector.Mode
    )

    private fun interpolateTrackAtScreenDistance(
        track: List<TrackPoint>,
        screenPoints: List<Pair<Float, Float>>,
        cumulativeDistance: FloatArray,
        targetDistance: Float
    ): ScreenTrackSample? {
        if (track.isEmpty()) return null
        if (targetDistance <= 0f) {
            val (x, y) = screenPoints.first()
            val point = track.first()
            return ScreenTrackSample(point.lat, point.lon, x, y, point.displayMode)
        }
        val totalDistance = cumulativeDistance.last()
        if (targetDistance >= totalDistance) {
            val (x, y) = screenPoints.last()
            val point = track.last()
            return ScreenTrackSample(point.lat, point.lon, x, y, point.displayMode)
        }

        var high = cumulativeDistance.binarySearch(targetDistance)
        if (high < 0) high = -high - 1
        if (high <= 0 || high >= track.size) return null

        val low = high - 1
        val segmentDistance = cumulativeDistance[high] - cumulativeDistance[low]
        if (segmentDistance <= 0f) return null
        val ratio = ((targetDistance - cumulativeDistance[low]) / segmentDistance).coerceIn(0f, 1f)
        val start = track[low]
        val end = track[high]
        val (startX, startY) = screenPoints[low]
        val (endX, endY) = screenPoints[high]
        return ScreenTrackSample(
            lat = start.lat + (end.lat - start.lat) * ratio,
            lon = start.lon + (end.lon - start.lon) * ratio,
            screenX = startX + (endX - startX) * ratio,
            screenY = startY + (endY - startY) * ratio,
            displayMode = if (ratio < 0.5f) start.displayMode else end.displayMode
        )
    }

    fun inferDisplayModes(
        samples: List<MotionSample>,
        entries: List<LogEntry> = emptyList()
    ): List<DisplayModeSample> =
        inferModeStates(samples.sortedBy { it.timestamp }, entries).map { DisplayModeSample(it.timestamp, it.confirmedMode) }

    fun modeAt(timestamp: Long, timeline: List<DisplayModeSample>): MovementDetector.Mode {
        if (timeline.isEmpty()) return MovementDetector.Mode.UNKNOWN
        var mode = MovementDetector.Mode.UNKNOWN
        for (state in timeline) {
            if (state.timestamp > timestamp) break
            mode = state.mode
        }
        return mode
    }

    fun modesAt(targetTimes: LongArray, timeline: List<DisplayModeSample>): List<MovementDetector.Mode> {
        if (timeline.isEmpty()) return List(targetTimes.size) { MovementDetector.Mode.UNKNOWN }
        val modes = ArrayList<MovementDetector.Mode>(targetTimes.size)
        var mode = MovementDetector.Mode.UNKNOWN
        var timelineIndex = 0
        targetTimes.forEach { target ->
            while (timelineIndex < timeline.size && timeline[timelineIndex].timestamp <= target) {
                mode = timeline[timelineIndex].mode
                timelineIndex += 1
            }
            modes += mode
        }
        return modes
    }

    private fun buildDisplayPoints(
        entries: List<LogEntry>,
        modeStates: List<ModeState>
    ): List<DisplayPoint> {
        val modeMap = modeStates.associateBy({ it.timestamp }, { it.confirmedMode })
        var lastMode = MovementDetector.Mode.UNKNOWN
        var lastConstantRegionKind: ConstantRegionKind? = null
        var lastConstantRegionStayLat: Double? = null
        var lastConstantRegionStayLon: Double? = null
        var modeIndex = 0
        val sortedStates = modeStates.sortedBy { it.timestamp }
        val points = mutableListOf<DisplayPoint>()
        entries.forEachIndexed { sourceIndex, entry ->
            val lat = entry.latitude ?: return@forEachIndexed
            val lon = entry.longitude ?: return@forEachIndexed
            while (modeIndex < sortedStates.size && sortedStates[modeIndex].timestamp <= entry.timestamp) {
                lastMode = sortedStates[modeIndex].confirmedMode
                lastConstantRegionKind = sortedStates[modeIndex].constantRegionKind
                lastConstantRegionStayLat = sortedStates[modeIndex].constantRegionStayLat
                lastConstantRegionStayLon = sortedStates[modeIndex].constantRegionStayLon
                modeIndex += 1
            }
            points += DisplayPoint(
                sourceIndex = sourceIndex,
                timestamp = entry.timestamp,
                lat = lat,
                lon = lon,
                stepsDelta = entry.stepsDelta ?: 0,
                displayMode = modeMap[entry.timestamp] ?: lastMode,
                constantRegionKind = lastConstantRegionKind,
                constantRegionStayLat = lastConstantRegionStayLat,
                constantRegionStayLon = lastConstantRegionStayLon
            )
        }
        return points
    }

    private fun inferModeStates(
        samples: List<MotionSample>,
        entries: List<LogEntry> = emptyList()
    ): List<ModeState> {
        if (samples.isEmpty()) return emptyList()
        // 旧形式（accelStddev3s 等で legacy mode 推定）の MotionSample は廃止された。
        // 新形式の MotionSample から確定 mode (confirmedMode) と provisional mode を組み立てる。
        return inferModeStatesFromConfirmedCache(samples, entries)
    }

    private fun inferModeStatesFromConfirmedCache(
        samples: List<MotionSample>,
        entries: List<LogEntry>
    ): List<ModeState> {
        val result = mutableListOf<ModeState>()
        val lastConfirmedIndex = samples.indexOfLast { parseConfirmedMode(it.confirmedMode) != null }
        val locatedEntries = entries
            .asSequence()
            .filter { it.hasLocation }
            .distinctBy { it.timestamp }
            .sortedBy { it.timestamp }
            .toList()

        samples.forEachIndexed { index, sample ->
            val constantRegionKind = parseConstantRegionKind(sample.constantRegionKind)
            parseConfirmedMode(sample.confirmedMode)?.let { confirmedMode ->
                result += modeStateFromSample(
                    sample = sample,
                    mode = displayModeFromConfirmedSample(
                        sample = sample,
                        confirmedMode = confirmedMode,
                        constantRegionKind = constantRegionKind,
                        locatedEntries = locatedEntries
                    ),
                    constantRegionKind = constantRegionKind
                )
                return@forEachIndexed
            }

            // 新しい motion 記録は変化点イベントだけを保存するため、confirmedMode が
            // 空でも stK / W / region から表示モードを前方補完できる状態点として扱う。
            provisionalModeFromNewState(
                sample = sample,
                locatedEntries = locatedEntries,
                constantRegionKind = constantRegionKind,
                allowOpenRegionFallback = index > lastConfirmedIndex
            )?.let { provisionalMode ->
                result += modeStateFromSample(
                    sample = sample,
                    mode = provisionalMode,
                    constantRegionKind = constantRegionKind
                )
            }
        }

        return result
    }

    private fun displayModeFromConfirmedSample(
        sample: MotionSample,
        confirmedMode: MovementDetector.Mode,
        constantRegionKind: ConstantRegionKind?,
        locatedEntries: List<LogEntry>
    ): MovementDetector.Mode {
        if (confirmedMode != MovementDetector.Mode.WALKING) return confirmedMode
        return resolveDisplayWalkingMode(sample, constantRegionKind, locatedEntries)
    }

    private fun resolveDisplayWalkingMode(
        sample: MotionSample,
        constantRegionKind: ConstantRegionKind?,
        locatedEntries: List<LogEntry>
    ): MovementDetector.Mode {
        val gpsSpeed = gpsSpeedKmhAt(sample.timestamp, locatedEntries, DISPLAY_MOTION_PARAMS.walkingSpeedWindowMs)
            ?: sample.constantRegionSpeedKmh.takeIf {
                constantRegionKind == ConstantRegionKind.CONSTANT_MOVE && it != null && it.isFinite()
            }
        val stepSpeed = displayStepSpeedKmh(sample)
        val walkingSpeed = WalkingSpeedSnapshot(
            gpsSpeedKmh = gpsSpeed,
            stepSpeedKmh = stepSpeed,
            differenceKmh = if (gpsSpeed != null && stepSpeed != null) abs(gpsSpeed - stepSpeed) else null
        )
        return FinalContextResolver.resolveWalkingMode(walkingSpeed, DISPLAY_MOTION_PARAMS)
    }

    private fun displayStepSpeedKmh(sample: MotionSample): Double? {
        val steps = sample.stepDeltaWindow?.coerceAtLeast(0) ?: return null
        val windowSec = DISPLAY_MOTION_PARAMS.walkingSpeedWindowMs / 1000.0
        if (windowSec <= 0.0) return null
        return steps * DISPLAY_MOTION_PARAMS.walkingStepLengthM / windowSec * 3.6
    }

    private fun gpsSpeedKmhAt(
        timestamp: Long,
        locatedEntries: List<LogEntry>,
        windowMs: Long
    ): Double? {
        if (locatedEntries.size < 2 || windowMs <= 0L) return null
        val startIndex = lowerBoundLocatedEntry(locatedEntries, timestamp - windowMs)
        val endExclusive = upperBoundLocatedEntry(locatedEntries, timestamp)
        if (endExclusive - startIndex < 2) return null

        val first = locatedEntries[startIndex]
        val last = locatedEntries[endExclusive - 1]
        val lat1 = first.latitude ?: return null
        val lon1 = first.longitude ?: return null
        val lat2 = last.latitude ?: return null
        val lon2 = last.longitude ?: return null
        val dtMs = last.timestamp - first.timestamp
        if (dtMs <= 0L) return null
        return haversineM(lat1, lon1, lat2, lon2) / (dtMs / 1000.0) * 3.6
    }

    private fun lowerBoundLocatedEntry(entries: List<LogEntry>, timestamp: Long): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].timestamp < timestamp) low = mid + 1 else high = mid
        }
        return low
    }

    private fun upperBoundLocatedEntry(entries: List<LogEntry>, timestamp: Long): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].timestamp <= timestamp) low = mid + 1 else high = mid
        }
        return low
    }

    private fun provisionalModeFromNewState(
        sample: MotionSample,
        locatedEntries: List<LogEntry>,
        constantRegionKind: ConstantRegionKind?,
        allowOpenRegionFallback: Boolean
    ): MovementDetector.Mode? {
        val stK = StKStatus.fromStored(sample.stKStatus)
        val wStatus = sample.wStatus
        return when {
            stK == StKStatus.STK4 -> MovementDetector.Mode.VEHICLE
            wStatus == "W1" -> resolveDisplayWalkingMode(sample, constantRegionKind, locatedEntries)
            constantRegionKind == ConstantRegionKind.CONSTANT_MOVE -> MovementDetector.Mode.VEHICLE
            constantRegionKind == ConstantRegionKind.STAY && stK == StKStatus.STK1 -> MovementDetector.Mode.DEVICE_STILL
            constantRegionKind == ConstantRegionKind.STAY -> MovementDetector.Mode.STOPPED
            allowOpenRegionFallback && stK != null && wStatus == "W2" -> {
                // 現在まで続く未確定区間だけの暫定表示。閉じた区間はバックフィル済みになる。
                if (stK == StKStatus.STK1) MovementDetector.Mode.DEVICE_STILL else MovementDetector.Mode.STOPPED
            }
            else -> null
        }
    }

    private fun modeStateFromSample(
        sample: MotionSample,
        mode: MovementDetector.Mode,
        constantRegionKind: ConstantRegionKind?
    ): ModeState =
        ModeState(
            timestamp = sample.timestamp,
            confirmedMode = mode,
            constantRegionKind = constantRegionKind,
            constantRegionStayLat = sample.constantRegionStayLat,
            constantRegionStayLon = sample.constantRegionStayLon
        )

    private fun parseConfirmedMode(value: String?): MovementDetector.Mode? =
        value?.let {
            runCatching { MovementDetector.Mode.valueOf(it) }.getOrNull()
        }

    private fun parseConstantRegionKind(value: String?): ConstantRegionKind? =
        value?.let {
            runCatching { ConstantRegionKind.valueOf(it) }.getOrNull()
        }?.takeUnless { it == ConstantRegionKind.NONE }

    private fun isStationaryDisplayMode(mode: MovementDetector.Mode): Boolean =
        mode == MovementDetector.Mode.DEVICE_STILL || mode == MovementDetector.Mode.STOPPED

    private fun collapseConstantRegions(points: List<DisplayPoint>): List<DisplayPoint> {
        if (points.isEmpty()) return emptyList()
        val collapsed = mutableListOf<DisplayPoint>()
        var index = 0
        while (index < points.size) {
            val point = points[index]
            val kind = point.constantRegionKind
            if (isStationaryDisplayMode(point.displayMode)) {
                var endExclusive = index + 1
                while (
                    endExclusive < points.size &&
                    isStationaryDisplayMode(points[endExclusive].displayMode)
                ) {
                    endExclusive += 1
                }
                val segment = points.subList(index, endExclusive)
                val keepPoint = segment.last()
                val stayLat = keepPoint.constantRegionStayLat ?: segment.map { it.lat }.average()
                val stayLon = keepPoint.constantRegionStayLon ?: segment.map { it.lon }.average()
                val collapsedMode =
                    if (segment.all { it.displayMode == MovementDetector.Mode.DEVICE_STILL }) {
                        MovementDetector.Mode.DEVICE_STILL
                    } else {
                        MovementDetector.Mode.STOPPED
                    }
                collapsed += keepPoint.copy(
                    lat = stayLat,
                    lon = stayLon,
                    displayMode = collapsedMode
                )
                index = endExclusive
                continue
            }

            if (kind == null || kind == ConstantRegionKind.NONE) {
                collapsed += point
                index += 1
                continue
            }

            var endExclusive = index + 1
            while (
                endExclusive < points.size &&
                points[endExclusive].constantRegionKind == kind
            ) {
                endExclusive += 1
            }

            val segment = points.subList(index, endExclusive)
            when (kind) {
                ConstantRegionKind.STAY -> {
                    val keepPoint = segment.last()
                    val stayLat = keepPoint.constantRegionStayLat ?: segment.map { it.lat }.average()
                    val stayLon = keepPoint.constantRegionStayLon ?: segment.map { it.lon }.average()
                    collapsed += keepPoint.copy(lat = stayLat, lon = stayLon)
                }
                ConstantRegionKind.CONSTANT_MOVE -> {
                    collapsed.addAll(segment)
                }
                else -> {
                    collapsed.addAll(segment)
                }
            }
            index = endExclusive
        }
        return collapsed
    }

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val lambda1 = Math.toRadians(lon1)
        val lambda2 = Math.toRadians(lon2)
        val y = sin(lambda2 - lambda1) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(lambda2 - lambda1)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun circularMeanDegrees(angles: List<Double>): Double {
        val sinSum = angles.sumOf { sin(Math.toRadians(it)) }
        val cosSum = angles.sumOf { cos(Math.toRadians(it)) }
        return (Math.toDegrees(atan2(sinSum, cosSum)) + 360.0) % 360.0
    }

    private fun isClusterHopEligible(point: DisplayPoint): Boolean =
        point.stepsDelta == 0 &&
            point.displayMode != MovementDetector.Mode.VEHICLE &&
            !point.returnBurstFixed &&
            !point.clusterHopFixed

    private fun isPreFixedPoint(point: DisplayPoint): Boolean =
        point.returnBurstFixed || point.clusterHopFixed

    private fun detectReturnJumpBursts(points: List<DisplayPoint>): ReturnBurstResult {
        val corrected = points.toMutableList()
        var index = 1
        while (index < corrected.lastIndex) {
            val previous = corrected[index - 1]
            val current = corrected[index]
            if (!isClusterHopEligible(previous) || !isClusterHopEligible(current) || isPreFixedPoint(previous) || isPreFixedPoint(current)) {
                index += 1
                continue
            }
            val jumpDistance = haversineM(previous.lat, previous.lon, current.lat, current.lon)
            if (jumpDistance < RETURN_BURST_ENTER_DISTANCE_M) {
                index += 1
                continue
            }

            var foundIndex = -1
            var peakDistance = jumpDistance
            val searchEnd = min(corrected.size, index + RETURN_BURST_MAX_POINTS)
            for (pointIndex in index + 1 until searchEnd) {
                val candidate = corrected[pointIndex]
                if (!isClusterHopEligible(candidate) || isPreFixedPoint(candidate)) break
                if ((candidate.timestamp - current.timestamp) > RETURN_BURST_MAX_DURATION_MS) break
                val distanceFromPrevious = haversineM(previous.lat, previous.lon, candidate.lat, candidate.lon)
                peakDistance = max(peakDistance, distanceFromPrevious)
                if (distanceFromPrevious <= RETURN_BURST_RETURN_DISTANCE_M) {
                    foundIndex = pointIndex
                    break
                }
            }

            if (foundIndex == -1 || peakDistance < RETURN_BURST_PEAK_DISTANCE_M) {
                index += 1
                continue
            }

            val returnAnchor = corrected[foundIndex]
            val span = max(1, foundIndex - index)
            for (pointIndex in index until foundIndex) {
                val progress = (pointIndex - index + 1).toDouble() / (span + 1).toDouble()
                corrected[pointIndex] = corrected[pointIndex].copy(
                    lat = previous.lat + (returnAnchor.lat - previous.lat) * progress,
                    lon = previous.lon + (returnAnchor.lon - previous.lon) * progress,
                    returnBurstFixed = true
                )
            }
            index = foundIndex + 1
        }
        return ReturnBurstResult(corrected)
    }

    private fun detectClusterHopStays(points: List<DisplayPoint>): ClusterHopResult {
        val corrected = points.toMutableList()
        val deviceStillParams = STOP_NORMALIZATION_PARAMS.getValue(MovementDetector.Mode.DEVICE_STILL)
        val stoppedParams = STOP_NORMALIZATION_PARAMS.getValue(MovementDetector.Mode.STOPPED)
        val anchorWindow = max(deviceStillParams.clusterHopAnchorWindow, stoppedParams.clusterHopAnchorWindow)
        var index = anchorWindow
        while (index < points.size - anchorWindow) {
            val seed = points[index]
            if (!isClusterHopEligible(seed)) {
                index += 1
                continue
            }
            val params = STOP_NORMALIZATION_PARAMS[seed.displayMode]
            if (params == null) {
                index += 1
                continue
            }

            var runEnd = index
            while (runEnd + 1 < points.size) {
                val candidate = points[runEnd + 1]
                if (!isClusterHopEligible(candidate)) break
                if ((runEnd - index + 2) > params.clusterHopMaxPoints) break
                if ((points[runEnd + 1].timestamp - points[index].timestamp) > params.clusterHopMaxDurationMs) break
                if (haversineM(seed.lat, seed.lon, candidate.lat, candidate.lon) > params.clusterHopRadiusM) break
                runEnd += 1
            }

            val runPointCount = runEnd - index + 1
            if (runPointCount < params.clusterHopMinPoints) {
                index += 1
                continue
            }

            val leftPoints = points.subList(index - params.clusterHopAnchorWindow, index)
            val rightPoints = points.subList(runEnd + 1, min(points.size, runEnd + 1 + params.clusterHopAnchorWindow))
            if (leftPoints.size < params.clusterHopAnchorWindow || rightPoints.size < params.clusterHopAnchorWindow) {
                index = runEnd + 1
                continue
            }
            if (leftPoints.any { !isClusterHopEligible(it) } || rightPoints.any { !isClusterHopEligible(it) }) {
                index = runEnd + 1
                continue
            }

            val leftCenter = segmentSpatialCenter(leftPoints)
            val rightCenter = segmentSpatialCenter(rightPoints)
            val runCenter = segmentSpatialCenter(points.subList(index, runEnd + 1))
            val anchorDistance = haversineM(leftCenter.first, leftCenter.second, rightCenter.first, rightCenter.second)
            val runToLeft = haversineM(runCenter.first, runCenter.second, leftCenter.first, leftCenter.second)
            val runToRight = haversineM(runCenter.first, runCenter.second, rightCenter.first, rightCenter.second)

            if (
                anchorDistance <= params.clusterHopReturnDistanceM &&
                runToLeft >= params.clusterHopDistanceM &&
                runToRight >= params.clusterHopDistanceM
            ) {
                val anchorLat = (leftCenter.first + rightCenter.first) / 2.0
                val anchorLon = (leftCenter.second + rightCenter.second) / 2.0
                for (pointIndex in index..runEnd) {
                    corrected[pointIndex] = corrected[pointIndex].copy(
                        lat = anchorLat,
                        lon = anchorLon,
                        clusterHopFixed = true
                    )
                }
            }
            index = runEnd + 1
        }
        return ClusterHopResult(corrected)
    }

    private fun segmentSpatialCenter(points: List<DisplayPoint>): Pair<Double, Double> =
        median(points.map { it.lat }) to median(points.map { it.lon })

    private fun deviationSeriesStopCorrection(
        points: List<DisplayPoint>,
        windowPointCount: Int,
        params: StopNormalizationParams
    ): BurstRepairResultWithPoints {
        if (points.size < 3) {
            return BurstRepairResultWithPoints(points)
        }
        val center = segmentSpatialCenter(points)
        val latScale = 111_320.0
        val lonScale = 111_320.0 * cos(center.first * Math.PI / 180.0)
        val xs = points.map { (it.lon - center.second) * lonScale }
        val ys = points.map { (it.lat - center.first) * latScale }
        val baseX = runningMedian(xs, windowPointCount)
        val baseY = runningMedian(ys, windowPointCount)
        val residuals = xs.indices.map { idx -> hypot(xs[idx] - baseX[idx], ys[idx] - baseY[idx]) }
        val burstRepair = repairBurstClusters(xs, ys, baseX, baseY, residuals, params)
        val repairedResiduals = burstRepair.xs.indices.map { idx ->
            hypot(burstRepair.xs[idx] - baseX[idx], burstRepair.ys[idx] - baseY[idx])
        }
        val medianResidual = median(repairedResiduals)
        val madResidual = median(repairedResiduals.map { abs(it - medianResidual) })
        val robustSigma = max(0.5, 1.4826 * madResidual)
        val outlierThreshold = medianResidual + params.residualSigma * robustSigma

        val correctedResidualPoints = points.indices.map { idx ->
            val residual = repairedResiduals[idx]
            val rawX = burstRepair.xs[idx]
            val rawY = burstRepair.ys[idx]
            when {
                residual > outlierThreshold || residual > params.hardRadiusM -> baseX[idx] to baseY[idx]
                residual > params.noiseRadiusM -> (rawX * 0.35 + baseX[idx] * 0.65) to (rawY * 0.35 + baseY[idx] * 0.65)
                else -> rawX to rawY
            }
        }

        val radialCompression = compressRadialDeviation(
            correctedResidualPoints.map { it.first },
            correctedResidualPoints.map { it.second },
            params
        )

        val correctedPoints = points.indices.map { idx ->
            val correctedX = radialCompression.xs[idx]
            val correctedY = radialCompression.ys[idx]
            points[idx].copy(
                lat = center.first + correctedY / latScale,
                lon = center.second + correctedX / max(1e-9, lonScale)
            )
        }
        return BurstRepairResultWithPoints(correctedPoints)
    }

    private data class BurstRepairResultWithPoints(
        val points: List<DisplayPoint>
    )

    private fun repairBurstClusters(
        xs: List<Double>,
        ys: List<Double>,
        baseX: List<Double>,
        baseY: List<Double>,
        residuals: List<Double>,
        params: StopNormalizationParams
    ): BurstRepairResult {
        val correctedX = xs.toMutableList()
        val correctedY = ys.toMutableList()
        var index = 0
        while (index < residuals.size) {
            if (residuals[index] <= params.burstHighM) {
                index += 1
                continue
            }
            var burstEnd = index
            var lowStreak = 0
            while (burstEnd + 1 < residuals.size && (burstEnd - index + 1) < params.maxBurstPoints) {
                burstEnd += 1
                if (residuals[burstEnd] <= params.burstLowM) {
                    lowStreak += 1
                    if (lowStreak >= params.burstReturnPoints) {
                        burstEnd -= params.burstReturnPoints
                        break
                    }
                } else {
                    lowStreak = 0
                }
            }
            if (burstEnd >= index) {
                val left = index - 1
                val right = burstEnd + 1
                for (pointIndex in index..burstEnd) {
                    when {
                        left >= 0 && right < residuals.size -> {
                            val progress = (pointIndex - index + 1).toDouble() / (burstEnd - index + 2).toDouble()
                            correctedX[pointIndex] = correctedX[left] + (correctedX[right] - correctedX[left]) * progress
                            correctedY[pointIndex] = correctedY[left] + (correctedY[right] - correctedY[left]) * progress
                        }
                        left >= 0 -> {
                            correctedX[pointIndex] = correctedX[left] + (baseX[pointIndex] - correctedX[left]) * 0.5
                            correctedY[pointIndex] = correctedY[left] + (baseY[pointIndex] - correctedY[left]) * 0.5
                        }
                        right < residuals.size -> {
                            correctedX[pointIndex] = correctedX[right] + (baseX[pointIndex] - correctedX[right]) * 0.5
                            correctedY[pointIndex] = correctedY[right] + (baseY[pointIndex] - correctedY[right]) * 0.5
                        }
                        else -> {
                            correctedX[pointIndex] = baseX[pointIndex]
                            correctedY[pointIndex] = baseY[pointIndex]
                        }
                    }
                }
            }
            index = max(burstEnd + 1, index + 1)
        }
        return BurstRepairResult(correctedX, correctedY)
    }

    private fun compressRadialDeviation(
        xs: List<Double>,
        ys: List<Double>,
        params: StopNormalizationParams
    ): RadialCompressionResult {
        val correctedX = xs.toMutableList()
        val correctedY = ys.toMutableList()
        for (index in xs.indices) {
            val radius = hypot(correctedX[index], correctedY[index])
            if (radius <= params.radialSoftStartM) continue
            var targetRadius = params.radialSoftStartM + (radius - params.radialSoftStartM) * params.radialKeepRatio
            targetRadius = min(targetRadius, params.radialHardCapM)
            if (targetRadius >= radius) continue
            val scale = targetRadius / max(1e-9, radius)
            correctedX[index] *= scale
            correctedY[index] *= scale
        }
        return RadialCompressionResult(correctedX, correctedY)
    }

    private fun runningMedian(values: List<Double>, windowPointCount: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val radius = max(1, windowPointCount)
        return values.indices.map { index ->
            val from = max(0, index - radius)
            val to = min(values.size, index + radius + 1)
            median(values.subList(from, to))
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    fun cumulativeDistances(entries: List<LogEntry>): List<Double> {
        val dist = mutableListOf(0.0)
        for (i in 1 until entries.size) {
            val prevLat = entries[i - 1].latitude ?: continue
            val prevLon = entries[i - 1].longitude ?: continue
            val curLat = entries[i].latitude ?: continue
            val curLon = entries[i].longitude ?: continue
            dist.add(dist.last() + haversineM(prevLat, prevLon, curLat, curLon))
        }
        return dist
    }

    // ── 状態ラベル（地図上に stK / W / 定速領域 を表示するためのマーカーデータ） ──

    const val STATE_LABEL_COLOR_STK1  = 0xFF555566.toInt()  // 暗グレー（静止）
    const val STATE_LABEL_COLOR_STK2  = 0xFFBB6600.toInt()  // アンバー（振動）
    const val STATE_LABEL_COLOR_STK4  = 0xFFCC2200.toInt()  // 赤橙（加速）
    const val STATE_LABEL_COLOR_W1    = 0xFF1E66E8.toInt()  // 青（歩行中）
    const val STATE_LABEL_COLOR_W2    = 0xFF7799BB.toInt()  // グレー青（歩行なし）
    const val STATE_LABEL_COLOR_STAY  = 0xFF1E9944.toInt()  // 緑（滞在）
    const val STATE_LABEL_COLOR_CMOVE = 0xFF8822CC.toInt()  // 紫（定速移動）
    const val STATE_LABEL_COLOR_TRK_ON  = 0xFFD84315.toInt() // 赤橙（GPS即時トリガーON）
    const val STATE_LABEL_COLOR_TRK_OFF = 0xFF607D8B.toInt() // 青灰（GPS即時トリガーOFF）

    /**
     * 地図上に重ねて表示する状態ラベルの1件。
     * @param text  ラベル文字列（"K1"/"K2"/"K4"/"W1"/"W2"/"STAY"/"CMOV"/"tON"）
     * @param bgColor バッジ背景色（STATE_LABEL_COLOR_* 定数）
     */
    data class StateLabel(
        val lat: Double,
        val lon: Double,
        val text: String,
        val bgColor: Int,
        val timestamp: Long = 0L
    )

    /**
     * MotionSample の stK / constantRegionKind の遷移点を GPS 座標と紐付け、
     * 状態イベントラベルの一覧を返す。
     *
     * 同じ状態が続く間はラベルを出さず、変化したときだけ1件追加する。
     * これにより 3 秒ごとに大量のラベルが出ることを防ぐ。
     */
    fun buildStateLabels(entries: List<LogEntry>, motionSamples: List<MotionSample>): List<StateLabel> {
        if (entries.isEmpty() || motionSamples.isEmpty()) return emptyList()
        val locatedEntries = entries.filter { it.hasLocation }.sortedBy { it.timestamp }
        if (locatedEntries.isEmpty()) return emptyList()

        val sorted = motionSamples.sortedBy { it.timestamp }
        val labels = mutableListOf<StateLabel>()
        var prevStK: StKStatus? = null
        var prevRegion: ConstantRegionKind? = null

        for (sample in sorted) {
            val stK = StKStatus.fromStored(sample.stKStatus)
            val region = parseConstantRegionKind(sample.constantRegionKind)

            val stKChanged = stK != null && stK != prevStK
            val regionChanged = region != prevRegion

            if (stKChanged || regionChanged) {
                val location = displayLocationAt(locatedEntries, sample.timestamp)
                val lat = location?.first
                val lon = location?.second
                if (lat != null && lon != null) {
                    if (stKChanged && stK == StKStatus.STK4) {
                        labels += StateLabel(lat, lon, "AC", STATE_LABEL_COLOR_STK4, sample.timestamp)
                    }
                    if (regionChanged) {
                        when (region) {
                            ConstantRegionKind.STAY          -> labels += StateLabel(lat, lon, "STAY", STATE_LABEL_COLOR_STAY, sample.timestamp)
                            ConstantRegionKind.CONSTANT_MOVE -> labels += StateLabel(lat, lon, "CMOV", STATE_LABEL_COLOR_CMOVE, sample.timestamp)
                            else -> {}
                        }
                    }
                }
            }

            if (stK != null) prevStK = stK
            prevRegion = region
        }
        return labels
    }

    /**
     * MotionSample の trK 遷移点または GPS 即時取得記録を GPS 座標と紐付け、
     * トリガーラベル一覧を返す。
     *
     * trK は GPS 即時取得 / 短周期復帰の制御用トリガーなので、stK4 ではない
     * trK4 変化点または GpsImmediate=1 だけを tON として表示する。
     * stK4 は AC ラベル側に任せる。
     */
    fun buildTrkLabels(entries: List<LogEntry>, motionSamples: List<MotionSample>): List<StateLabel> {
        if (entries.isEmpty() || motionSamples.isEmpty()) return emptyList()
        val locatedEntries = entries.filter { it.hasLocation }.sortedBy { it.timestamp }
        if (locatedEntries.isEmpty()) return emptyList()

        val sorted = motionSamples.sortedBy { it.timestamp }
        val labels = mutableListOf<StateLabel>()
        var prevTrK: TrKStatus? = null

        for (sample in sorted) {
            val trK = parseTrKStatus(sample.trKStatus)
            val stK = StKStatus.fromStored(sample.stKStatus)
            val trK4Changed = trK != null && trK != prevTrK && trK == TrKStatus.TRK4
            val gpsImmediate = sample.gpsImmediate == true
            if ((trK4Changed || gpsImmediate) && stK != StKStatus.STK4) {
                val location = displayLocationAt(locatedEntries, sample.timestamp)
                val lat = location?.first
                val lon = location?.second
                if (lat != null && lon != null) {
                    labels += StateLabel(
                        lat = lat,
                        lon = lon,
                        text = "tON",
                        bgColor = STATE_LABEL_COLOR_TRK_ON,
                        timestamp = sample.timestamp
                    )
                }
            }
            if (trK != null) prevTrK = trK
        }
        return dedupeNearbyLabels(labels, minDistanceM = 35.0, minTimeMs = 20 * 60_000L)
    }

    private fun dedupeNearbyLabels(
        labels: List<StateLabel>,
        minDistanceM: Double,
        minTimeMs: Long
    ): List<StateLabel> {
        val kept = mutableListOf<StateLabel>()
        for (label in labels) {
            val duplicate = kept.any { previous ->
                previous.text == label.text &&
                    abs(previous.timestamp - label.timestamp) <= minTimeMs &&
                    haversineM(previous.lat, previous.lon, label.lat, label.lon) <= minDistanceM
            }
            if (!duplicate) kept += label
        }
        return kept
    }

    /**
     * ソート済みの位置付きエントリ一覧から、timestamp に最も近いエントリを二分探索で返す。
     */
    private fun nearestLocatedEntry(locatedEntries: List<LogEntry>, timestamp: Long): LogEntry? {
        if (locatedEntries.isEmpty()) return null
        var idx = locatedEntries.binarySearch { it.timestamp.compareTo(timestamp) }
        if (idx < 0) idx = -(idx + 1)
        return when {
            idx >= locatedEntries.size -> locatedEntries.last()
            idx == 0                  -> locatedEntries.first()
            else -> {
                val before = locatedEntries[idx - 1]
                val after  = locatedEntries[idx]
                if (timestamp - before.timestamp <= after.timestamp - timestamp) before else after
            }
        }
    }

    private fun displayLocationAt(locatedEntries: List<LogEntry>, timestamp: Long): Pair<Double, Double>? {
        if (locatedEntries.isEmpty()) return null
        var idx = locatedEntries.binarySearch { it.timestamp.compareTo(timestamp) }
        if (idx >= 0) {
            val entry = locatedEntries[idx]
            val lat = entry.latitude
            val lon = entry.longitude
            return if (lat != null && lon != null) lat to lon else null
        }
        idx = -(idx + 1)
        if (idx in 1 until locatedEntries.size) {
            val before = locatedEntries[idx - 1]
            val after = locatedEntries[idx]
            val gapMs = after.timestamp - before.timestamp
            val beforeLat = before.latitude
            val beforeLon = before.longitude
            val afterLat = after.latitude
            val afterLon = after.longitude
            if (
                gapMs in GPS_GAP_INTERPOLATION_MIN_MS..GPS_GAP_INTERPOLATION_MAX_MS &&
                beforeLat != null &&
                beforeLon != null &&
                afterLat != null &&
                afterLon != null
            ) {
                val ratio = (timestamp - before.timestamp).toDouble() / gapMs.toDouble()
                return (beforeLat + (afterLat - beforeLat) * ratio) to
                    (beforeLon + (afterLon - beforeLon) * ratio)
            }
        }
        val nearest = nearestLocatedEntry(locatedEntries, timestamp)
        val lat = nearest?.latitude
        val lon = nearest?.longitude
        return if (lat != null && lon != null) lat to lon else null
    }

    private fun stKLabelColor(stK: StKStatus): Int = when (stK) {
        StKStatus.STK1 -> STATE_LABEL_COLOR_STK1
        StKStatus.STK2 -> STATE_LABEL_COLOR_STK2
        StKStatus.STK4 -> STATE_LABEL_COLOR_STK4
    }

    private fun wLabelColor(w: String): Int = when (w) {
        "W1" -> STATE_LABEL_COLOR_W1
        else -> STATE_LABEL_COLOR_W2
    }

    private fun parseTrKStatus(value: String?): TrKStatus? = TrKStatus.fromStored(value)
}
