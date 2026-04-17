package com.example.gpspressurelogger.util

import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.sensor.ConstantRegionKind
import com.example.gpspressurelogger.sensor.MovementDetector
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
    private const val SINGLE_POINT_SPIKE_NEIGHBOR_DISTANCE_M = 80.0
    private const val SINGLE_POINT_SPIKE_DETOUR_DISTANCE_M = 150.0
    private const val SINGLE_POINT_SPIKE_DETOUR_RATIO = 4.0
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
    
    private const val APP_MAP_MARKER_SCALE = 1.25f
    private const val WIDGET_MARKER_SCALE = 1.0f
    private const val DIRECTION_ARROW_MIN_SPACING_M = 360.0
    private const val DIRECTION_ARROW_MIN_SEGMENT_M = 18.0
    private const val DIRECTION_ARROW_START_END_SKIP_M = 40.0
    private const val DIRECTION_ARROW_LOCAL_BEARING_WINDOW = 2
    private const val DIRECTION_ARROW_TEXT_SIZE_PX = 24f
    private const val DIRECTION_ARROW_BITMAP_PADDING_PX = 10f
    private const val DIRECTION_ARROW_OUTLINE_WIDTH_PX = 4f

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
                if (dt > 0 && (haversineM(prevLat, prevLon, lat, lon) / dt * 3.6) > MAX_REASONABLE_SPEED_KMH) continue
            }
            pass1.add(e)
        }
        return removeIsolatedJumpClusters(
            removeTransientDetours(
                removeSinglePointSpikes(pass1)
            )
        )
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
            if (!isSinglePointSpike(prev, current, next)) {
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

        val prevToNext = haversineM(prevLat, prevLon, nextLat, nextLon)
        if (prevToNext > SINGLE_POINT_SPIKE_NEIGHBOR_DISTANCE_M) return false

        val prevToCurrent = haversineM(prevLat, prevLon, curLat, curLon)
        val currentToNext = haversineM(curLat, curLon, nextLat, nextLon)
        val detour = minOf(prevToCurrent, currentToNext)
        if (detour < SINGLE_POINT_SPIKE_DETOUR_DISTANCE_M) return false

        val dtPrevMs = current.timestamp - prev.timestamp
        val dtNextMs = next.timestamp - current.timestamp
        if (dtPrevMs !in 1..SINGLE_POINT_SPIKE_MAX_DURATION_MS) return false
        if (dtNextMs !in 1..SINGLE_POINT_SPIKE_MAX_DURATION_MS) return false

        return detour >= prevToNext * SINGLE_POINT_SPIKE_DETOUR_RATIO
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
            if (!isTransientDetour(prev, current, next)) {
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
                for (idx in clusterStart..clusterEnd) keep[idx] = false
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

        val modeStates = inferModeStates(motionSamples.sortedBy { it.timestamp })
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

        val stayCollapsedPoints = collapseStayRegions(normalizedPoints)
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
        val isStop: Boolean = false, // 滞在地点（重心）
        val stopCount: Int  = 0
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
     * viewer の GPS 平準化 ON と揃えるための、折れ線表示専用系列。
     * 停止補正済みの GPS 点列へ直接移動平均をかけ、連続折れ線として返す。
     * 停止点集約は滞在マーカー専用に分離する。
     */
    fun buildDisplayPolyline(
        entries: List<LogEntry>,
        motionSamples: List<MotionSample>,
        windowRadius: Int = DISPLAY_SMOOTHING_WINDOW_RADIUS
    ): List<TrackPoint> {
        val locatedEntries = entries.filter { it.hasLocation }
        if (locatedEntries.isEmpty()) return emptyList()

        val modeTimeline = inferDisplayModes(motionSamples)
        var modeIndex = 0
        var currentMode = MovementDetector.Mode.UNKNOWN
        val cumulativeDistances = cumulativeDistances(locatedEntries)
        val totalDistance = cumulativeDistances.last().coerceAtLeast(1.0)
        val baseTrack = locatedEntries.mapIndexed { index, entry ->
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
        if (baseTrack.size < windowRadius * 2 + 1 || windowRadius <= 0) return baseTrack

        val smoothed = baseTrack.toMutableList()
        for (index in windowRadius until baseTrack.size - windowRadius) {
            val neighbors = baseTrack.subList(index - windowRadius, index + windowRadius + 1)
            smoothed[index] = baseTrack[index].copy(
                lat = neighbors.map { it.lat }.average(),
                lon = neighbors.map { it.lon }.average()
            )
        }
        return smoothed
    }

    fun splitTrackByMode(track: List<TrackPoint>): List<TrackSegment> {
        if (track.size < 2) return emptyList()
        val segments = mutableListOf<TrackSegment>()
        var currentMode = track.first().displayMode
        var currentPoints = mutableListOf(track.first())
        for (index in 1 until track.size) {
            val point = track[index]
            val previous = track[index - 1]
            if (point.displayMode == currentMode) {
                currentPoints += point
            } else {
                if (currentPoints.size == 1) {
                    currentPoints += point
                }
                if (currentPoints.size >= 2) {
                    segments += TrackSegment(currentMode, currentPoints.toList())
                }
                currentMode = point.displayMode
                currentPoints = mutableListOf(previous, point)
            }
        }
        if (currentPoints.size >= 2) {
            segments += TrackSegment(currentMode, currentPoints.toList())
        }
        return segments
    }

    fun computeDirectionArrowMarkers(
        track: List<TrackPoint>,
        params: DirectionArrowParams = DirectionArrowParams()
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

    fun inferDisplayModes(samples: List<MotionSample>): List<DisplayModeSample> =
        inferModeStates(samples.sortedBy { it.timestamp }).map { DisplayModeSample(it.timestamp, it.confirmedMode) }

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

    private fun inferModeStates(samples: List<MotionSample>): List<ModeState> {
        if (samples.isEmpty()) return emptyList()
        val history = ArrayDeque<Int>()
        var currentMode = MovementDetector.Mode.UNKNOWN
        var pendingMode = MovementDetector.Mode.UNKNOWN
        var pendingCount = 0
        val result = mutableListOf<ModeState>()

        samples.forEach { sample ->
            parseConfirmedMode(sample.confirmedMode)?.let { confirmedMode ->
                currentMode = confirmedMode
                pendingMode = MovementDetector.Mode.UNKNOWN
                pendingCount = 0
                result += ModeState(
                    timestamp = sample.timestamp,
                    confirmedMode = currentMode,
                    constantRegionKind = parseConstantRegionKind(sample.constantRegionKind),
                    constantRegionStayLat = sample.constantRegionStayLat,
                    constantRegionStayLon = sample.constantRegionStayLon
                )
                return@forEach
            }

            val stepDelta3s = sample.stepDelta3s?.coerceAtLeast(0)
            history.addLast(stepDelta3s ?: 0)
            while (history.size > MODE_STEP_SMOOTH_WINDOW_COUNT) history.removeFirst()

            val stepDelta9s = history.sum()
            val stepRate9s = stepDelta9s / (LoggingConfig.SLOT_INTERVAL_SECONDS * MODE_STEP_SMOOTH_WINDOW_COUNT)

            val stddev = sample.accelStddev3s
            val mad = sample.accelMad3s
            val candidateResult = MovementDetector.computeModeCandidate(
                currentMode = currentMode,
                stddev = stddev,
                mad = mad,
                stepDelta9s = stepDelta9s,
                stepRate9s = stepRate9s
            )
            val candidateMode = candidateResult.candidateMode

            currentMode = when {
                candidateMode == MovementDetector.Mode.UNKNOWN -> currentMode
                candidateMode == currentMode -> {
                    pendingMode = MovementDetector.Mode.UNKNOWN
                    pendingCount = 0
                    currentMode
                }
                candidateResult.immediate -> {
                    pendingMode = MovementDetector.Mode.UNKNOWN
                    pendingCount = 0
                    candidateMode
                }
                else -> {
                    if (candidateMode != pendingMode) {
                        pendingMode = candidateMode
                        pendingCount = 1
                    } else {
                        pendingCount += 1
                    }
                    if (pendingCount >= MovementDetector.requiredWindowsForMode(candidateMode)) {
                        pendingMode = MovementDetector.Mode.UNKNOWN
                        pendingCount = 0
                        candidateMode
                    } else {
                        currentMode
                    }
                }
            }

            result += ModeState(
                timestamp = sample.timestamp,
                confirmedMode = currentMode,
                constantRegionKind = parseConstantRegionKind(sample.constantRegionKind),
                constantRegionStayLat = sample.constantRegionStayLat,
                constantRegionStayLon = sample.constantRegionStayLon
            )
        }

        return result
    }

    private fun parseConfirmedMode(value: String?): MovementDetector.Mode? =
        value?.let {
            runCatching { MovementDetector.Mode.valueOf(it) }.getOrNull()
        }

    private fun parseConstantRegionKind(value: String?): ConstantRegionKind? =
        value?.let {
            runCatching { ConstantRegionKind.valueOf(it) }.getOrNull()
        }?.takeUnless { it == ConstantRegionKind.NONE }

    private fun collapseStayRegions(points: List<DisplayPoint>): List<DisplayPoint> {
        if (points.isEmpty()) return emptyList()
        val collapsed = mutableListOf<DisplayPoint>()
        var index = 0
        while (index < points.size) {
            val point = points[index]
            if (point.constantRegionKind != ConstantRegionKind.STAY) {
                collapsed += point
                index += 1
                continue
            }

            var endExclusive = index + 1
            while (
                endExclusive < points.size &&
                points[endExclusive].constantRegionKind == ConstantRegionKind.STAY
            ) {
                endExclusive += 1
            }

            val segment = points.subList(index, endExclusive)
            val keepPoint = segment.last()
            val stayLat = keepPoint.constantRegionStayLat ?: segment.map { it.lat }.average()
            val stayLon = keepPoint.constantRegionStayLon ?: segment.map { it.lon }.average()
            collapsed += keepPoint.copy(
                lat = stayLat,
                lon = stayLon
            )
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
}

