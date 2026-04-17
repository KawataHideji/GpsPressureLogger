package com.example.gpspressurelogger.util

/**
 * 汎用データ補間ユーティリティ
 * 3次スプラインのオーバーシュート（暴れ）を防ぐため、線形補間を採用。
 * 不等間隔なデータ点 (x, y) から指定間隔の滑らかな系列を生成する。
 */
class SplineInterpolator(private val x: DoubleArray, private val y: DoubleArray) {

    private val n = x.size

    init {
        require(n >= 2) { "At least 2 points are required for interpolation" }
    }

    /** 線形補間により指定した x に対する補間値 y を返す */
    fun interpolate(xVal: Double): Double {
        if (xVal <= x[0]) return y[0]
        if (xVal >= x[n - 1]) return y[n - 1]

        // 二分探索で適切なセグメント [i, i+1] を見つける
        var i = 0
        var j = n - 1
        while (j - i > 1) {
            val k = (i + j) / 2
            if (x[k] > xVal) j = k else i = k
        }

        // 線形補間式: y = y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        val x0 = x[i]; val y0 = y[i]
        val x1 = x[i + 1]; val y1 = y[i + 1]
        
        return y0 + (y1 - y0) * (xVal - x0) / (x1 - x0)
    }

    companion object {
        /**
         * LogEntry リストから指定された時間間隔で補間データを生成する
         */
        fun createInterpolatedSeries(
            times: LongArray,
            values: FloatArray,
            intervalMs: Long
        ): Pair<LongArray, FloatArray> {
            if (times.size < 2) return Pair(times, values)

            val x = times.map { it.toDouble() }.toDoubleArray()
            val y = values.map { it.toDouble() }.toDoubleArray()
            val interpolator = SplineInterpolator(x, y)

            val startTime = times.first()
            val endTime = times.last()
            val count = ((endTime - startTime) / intervalMs).toInt() + 1
            
            if (count <= 1) return Pair(times, values)

            val newTimes = LongArray(count)
            val newValues = FloatArray(count)

            for (i in 0 until count) {
                val t = startTime + i * intervalMs
                newTimes[i] = t
                newValues[i] = interpolator.interpolate(t.toDouble()).toFloat()
            }

            return Pair(newTimes, newValues)
        }
    }
}
