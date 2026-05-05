package com.example.gpspressurelogger.sensor

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 直近GPS点列から、歩行判定の補助に使うGPS速度を計算する。
 *
 * 状態判定スレッド上からだけ呼ぶ前提で、内部同期は持たない。
 */
class GpsSpeedTracker(
    private val paramsProvider: MotionStateParamsProvider
) {
    private val points = ArrayDeque<MotionGpsPoint>()

    fun addPoint(point: MotionGpsPoint) {
        points.addLast(point)
        trim(point.timestampMs)
    }

    fun speedKmh(nowMs: Long): Double? {
        trim(nowMs)
        if (points.size < 2) return null

        val first = points.first()
        val last = points.last()
        val durationMs = last.timestampMs - first.timestampMs
        if (durationMs <= 0L) return null

        val distanceM = haversineM(first.latitude, first.longitude, last.latitude, last.longitude)
        return distanceM / (durationMs / 1000.0) * 3.6
    }

    private fun trim(nowMs: Long) {
        val windowStart = nowMs - paramsProvider.current().walkingSpeedWindowMs
        while (points.isNotEmpty() && points.first().timestampMs < windowStart) {
            points.removeFirst()
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
}
