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

    /**
     * `walkingSpeedWindowMs` 内の点だけを使って速度を返す（厳密版）。
     * 高速移動中の GPS 欠落で 9 秒窓に 1 点しか無いときは null。
     */
    fun speedKmh(nowMs: Long): Double? {
        trim(nowMs)
        val windowStart = nowMs - paramsProvider.current().walkingSpeedWindowMs
        return computeSpeed(points.filter { it.timestampMs >= windowStart })
    }

    /**
     * `walkingSpeedFallbackWindowMs`（より広い窓）に入っている全点を使って速度を返す。
     * 厳密窓では 1 点しか取れない場合の VEHICLE 昇格救済用。
     * 窓が広いぶん精度は粗くなるが、車・電車のような高速・連続移動は十分に拾える。
     */
    fun fallbackSpeedKmh(nowMs: Long): Double? {
        trim(nowMs)
        return computeSpeed(points.toList())
    }

    private fun computeSpeed(window: List<MotionGpsPoint>): Double? {
        if (window.size < 2) return null
        val first = window.first()
        val last = window.last()
        val durationMs = last.timestampMs - first.timestampMs
        if (durationMs <= 0L) return null
        val distanceM = haversineM(first.latitude, first.longitude, last.latitude, last.longitude)
        return distanceM / (durationMs / 1000.0) * 3.6
    }

    private fun trim(nowMs: Long) {
        // 内部バッファは「広い側の窓 (fallback)」分まで保持する。
        // 厳密版の `speedKmh()` は呼び出し時にさらに `walkingSpeedWindowMs` で絞る。
        val windowStart = nowMs - paramsProvider.current().walkingSpeedFallbackWindowMs
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
