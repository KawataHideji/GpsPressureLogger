package com.example.gpspressurelogger.sensor

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * (k1 or k2/k3) and w2 の区間を定速領域として管理する。
 */
class ConstantRegionTracker(
    private val paramsProvider: MotionStateParamsProvider
) {
    private val points = mutableListOf<MotionGpsPoint>()
    private var active = false
    private var startTimestampMs = 0L
    private var lastCompleted: ConstantRegionResult? = null
    private var activeEstimate: ConstantRegionResult? = null

    fun addGpsPoint(point: MotionGpsPoint) {
        if (active) points += point
    }

    fun update(timestampMs: Long, kStatus: KStatus, wStatus: WStatus): ConstantRegionResult? {
        val shouldBeActive = kStatus != KStatus.K4 && wStatus == WStatus.W2
        lastCompleted = null
        when {
            shouldBeActive && !active -> {
                active = true
                startTimestampMs = timestampMs
                points.clear()
            }
            !shouldBeActive && active -> {
                lastCompleted = estimateRegion(timestampMs)
                active = false
                points.clear()
                activeEstimate = null
            }
        }
        if (active) {
            activeEstimate = estimateRegion(timestampMs)
        }
        return lastCompleted
    }

    fun isActive(): Boolean = active

    fun latestActiveEstimate(): ConstantRegionResult? = activeEstimate

    fun latestCompleted(): ConstantRegionResult? = lastCompleted

    private fun estimateRegion(endTimestampMs: Long): ConstantRegionResult {
        val params = paramsProvider.current()
        val durationMs = endTimestampMs - startTimestampMs
        val filteredPoints = rejectSpatialOutliers(points, params)
        if (filteredPoints.size < 2 || durationMs < params.constantRegionMinDurationMs) {
            return ConstantRegionResult(
                kind = ConstantRegionKind.NONE,
                startTimestampMs = startTimestampMs,
                endTimestampMs = endTimestampMs,
                startPoint = filteredPoints.firstOrNull() ?: points.firstOrNull(),
                endPoint = filteredPoints.lastOrNull() ?: points.lastOrNull(),
                stayPoint = filteredPoints.averagePoint() ?: points.averagePoint(),
                averageSpeedKmh = 0.0,
                directionDeg = null
            )
        }

        val line = fitLine(filteredPoints)
        val startPoint = line.pointAt(startTimestampMs)
        val endPoint = line.pointAt(endTimestampMs)
        val distanceM = haversineM(
            startPoint.latitude,
            startPoint.longitude,
            endPoint.latitude,
            endPoint.longitude
        )
        val speedKmh = if (durationMs > 0L) distanceM / (durationMs / 1000.0) * 3.6 else 0.0
        val kind = if (speedKmh <= params.staySpeedThresholdKmh) {
            ConstantRegionKind.STAY
        } else {
            ConstantRegionKind.CONSTANT_MOVE
        }
        val stayPoint = if (kind == ConstantRegionKind.STAY) {
            midpointPoint(startPoint, endPoint)
        } else {
            null
        }
        return ConstantRegionResult(
            kind = kind,
            startTimestampMs = startTimestampMs,
            endTimestampMs = endTimestampMs,
            startPoint = startPoint,
            endPoint = endPoint,
            stayPoint = stayPoint,
            averageSpeedKmh = speedKmh,
            directionDeg = if (kind == ConstantRegionKind.CONSTANT_MOVE) {
                bearingDegrees(startPoint.latitude, startPoint.longitude, endPoint.latitude, endPoint.longitude)
            } else {
                null
            }
        )
    }

    private fun rejectSpatialOutliers(
        source: List<MotionGpsPoint>,
        params: MotionStateParams
    ): List<MotionGpsPoint> {
        if (source.size < 5) return source
        val centerLat = source.map { it.latitude }.average()
        val centerLon = source.map { it.longitude }.average()
        val distances = source.map { point ->
            haversineM(centerLat, centerLon, point.latitude, point.longitude)
        }
        val median = distances.median()
        val mad = distances.map { kotlin.math.abs(it - median) }.median()
        val robustSigma = mad * 1.4826
        val threshold = maxOf(
            params.constantRegionOutlierMinThresholdM,
            median + params.constantRegionOutlierMadMultiplier * robustSigma
        )
        val filtered = source.filterIndexed { index, _ -> distances[index] <= threshold }
        return filtered.takeIf { it.size >= 2 } ?: source
    }

    private data class LinearFit(
        val t0: Long,
        val latIntercept: Double,
        val latSlope: Double,
        val lonIntercept: Double,
        val lonSlope: Double
    ) {
        fun pointAt(timestampMs: Long): MotionGpsPoint {
            val t = (timestampMs - t0).toDouble() / 1000.0
            return MotionGpsPoint(
                timestampMs = timestampMs,
                latitude = latIntercept + latSlope * t,
                longitude = lonIntercept + lonSlope * t
            )
        }
    }

    private fun fitLine(source: List<MotionGpsPoint>): LinearFit {
        val t0 = source.first().timestampMs
        val xs = source.map { (it.timestampMs - t0).toDouble() / 1000.0 }
        val latFit = fitSingleAxis(xs, source.map { it.latitude })
        val lonFit = fitSingleAxis(xs, source.map { it.longitude })
        return LinearFit(
            t0 = t0,
            latIntercept = latFit.first,
            latSlope = latFit.second,
            lonIntercept = lonFit.first,
            lonSlope = lonFit.second
        )
    }

    private fun fitSingleAxis(x: List<Double>, y: List<Double>): Pair<Double, Double> {
        if (x.isEmpty()) return 0.0 to 0.0
        val meanX = x.average()
        val meanY = y.average()
        var numerator = 0.0
        var denominator = 0.0
        for (index in x.indices) {
            val dx = x[index] - meanX
            numerator += dx * (y[index] - meanY)
            denominator += dx * dx
        }
        val slope = if (denominator == 0.0) 0.0 else numerator / denominator
        val intercept = meanY - slope * meanX
        return intercept to slope
    }

    private fun List<MotionGpsPoint>.averagePoint(): MotionGpsPoint? {
        if (isEmpty()) return null
        return MotionGpsPoint(
            timestampMs = last().timestampMs,
            latitude = map { it.latitude }.average(),
            longitude = map { it.longitude }.average(),
            altitude = mapNotNull { it.altitude }.takeIf { it.isNotEmpty() }?.average(),
            accuracy = mapNotNull { it.accuracy }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        )
    }

    private fun midpointPoint(startPoint: MotionGpsPoint, endPoint: MotionGpsPoint): MotionGpsPoint =
        MotionGpsPoint(
            timestampMs = endPoint.timestampMs,
            latitude = (startPoint.latitude + endPoint.latitude) / 2.0,
            longitude = (startPoint.longitude + endPoint.longitude) / 2.0
        )

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return earthRadiusM * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
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
}
