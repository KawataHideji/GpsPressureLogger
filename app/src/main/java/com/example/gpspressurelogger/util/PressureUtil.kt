package com.example.gpspressurelogger.util

import kotlin.math.pow

/**
 * 気圧補正ユーティリティ
 */
object PressureUtil {

    /**
     * 海面更正気圧（QNH）を計算する
     * 国際標準大気（ISA）の式を使用
     *
     * @param pressureHpa  実測気圧 (hPa)
     * @param altitudeM    標高 (m)  ← GPSから取得
     * @param tempCelsius  気温 (℃)  ← デフォルト15℃（ISA標準）
     * @return 海面更正気圧 (hPa)
     */
    fun calcQnh(
        pressureHpa: Float,
        altitudeM: Double,
        tempCelsius: Float = 15f
    ): Float {
        val tempK = tempCelsius + 273.15f
        val exponent = (9.80665 * 0.0289644) / (8.31432 * 0.0065)
        val factor = (1.0 - (0.0065 * altitudeM) / tempK).pow(exponent)
        return (pressureHpa / factor).toFloat()
    }
}
