package com.example.gpspressurelogger.sensor

import kotlin.math.sqrt

/**
 * 変換済み加速度サンプル。
 *
 * x/y/z は、世界座標を利用できる場合は East/North/Up、利用できない場合は端末座標を表す。
 */
data class AccelSample(
    val timestampMs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val norm: Float,
    val source: AccelCoordinateSource
)

/**
 * 判定窓内の加速度を要約した統計ベクトル。
 *
 * trK / stK4 は世界座標系へ変換した加速度の水平成分だけを使う。
 * [avgMagnitude] は平均水平加速度ベクトルの大きさ k = |average(h_i)|。
 * [directionalityRatio] は代表方向への射影列の標準偏差比 sigma / k。
 * [scalarAvg] と [variance] は stK2 用に全ノルムから求める。
 */
data class AccelWindowVector(
    val timestampMs: Long,
    val avgX: Float,
    val avgY: Float,
    val avgZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
    /** 平均水平加速度ベクトルの大きさ k = |average(h_i)|。 */
    val avgMagnitude: Float,
    /** 射影列の標準偏差 sigma。 */
    val directionalityStddev: Float?,
    /** 射影列の標準偏差比 sigma / k。k が小さすぎる場合は null。 */
    val directionalityRatio: Float?,
    /** スカラー平均ノルム = mean(|a_i|)。動きの総量を反映する。 */
    val scalarAvg: Float,
    val maxMagnitude: Float,
    val horizontalAvgMagnitude: Float,
    val horizontalMaxMagnitude: Float,
    val variance: Float,
    val sampleCount: Int,
    val source: AccelCoordinateSource
)

private const val HORIZONTAL_RATIO_EPSILON = 1.0e-4f

/**
 * trK: GPSを即時に起こすための加速度トリガー。
 *
 * 直近の短い窓だけを見て、ONになった瞬間を LoggingService へ通知する。
 */
class TrKDetector(
    private val paramsProvider: MotionStateParamsProvider
) {
    private val samples = ArrayDeque<AccelSample>()
    private var currentStatus = TrKStatus.OFF

    fun addSample(sample: AccelSample) {
        samples.addLast(sample)
        trimSamples(sample.timestampMs)
    }

    fun addNormSample(norm: Float, timestampMs: Long) {
        samples.addLast(
            AccelSample(
                timestampMs = timestampMs,
                x = 0f,
                y = 0f,
                z = 0f,
                norm = norm.coerceAtLeast(0f),
                source = AccelCoordinateSource.NORM_ONLY
            )
        )
        trimSamples(timestampMs)
    }

    fun update(timestampMs: Long): TrKSnapshot {
        trimSamples(timestampMs)
        val params = paramsProvider.current()
        val vector = buildWindowVector(samples.toList(), timestampMs)
        if (vector == null) {
            return TrKSnapshot(
                timestampMs = timestampMs,
                status = currentStatus,
                rawStatus = currentStatus,
                avg = null,
                scalarAvg = null,
                directionalityRatio = null,
                maxMagnitude = null,
                horizontalMaxMagnitude = null,
                source = AccelCoordinateSource.NORM_ONLY,
                confidence = 0f
            )
        }

        val rawStatus = if (
            vector.avgMagnitude >= params.trKAvgThreshold &&
            vector.directionalityRatio != null &&
            vector.directionalityRatio <= params.trKRatioThreshold
        ) {
            TrKStatus.ON
        } else {
            TrKStatus.OFF
        }
        currentStatus = rawStatus
        return TrKSnapshot(
            timestampMs = timestampMs,
            status = currentStatus,
            rawStatus = rawStatus,
            avg = vector.avgMagnitude,
            scalarAvg = null,
            directionalityRatio = vector.directionalityRatio,
            maxMagnitude = null,
            horizontalMaxMagnitude = null,
            source = vector.source,
            confidence = confidenceFor(vector, params)
        )
    }

    private fun trimSamples(nowMs: Long) {
        val windowStart = nowMs - paramsProvider.current().trKWindowMs
        while (samples.isNotEmpty() && samples.first().timestampMs < windowStart) {
            samples.removeFirst()
        }
    }

    private fun confidenceFor(vector: AccelWindowVector, params: MotionStateParams): Float {
        val avgScore = (vector.avgMagnitude / params.trKAvgThreshold).coerceIn(0f, 3f) / 3f
        val ratio = vector.directionalityRatio ?: return 0f
        val ratioScore = (1f - (ratio / params.trKRatioThreshold).coerceIn(0f, 1f))
            .coerceIn(0f, 1f)
        return ((avgScore + ratioScore) / 2f).coerceIn(0f, 1f)
    }
}

/**
 * stK: 3秒ログスロットの正式な加速度状態。
 *
 * 前回 base_cycle 以降に入った加速度列全体を消費して判定する。
 */
class StKSlotClassifier(
    private val paramsProvider: MotionStateParamsProvider
) {
    private val slotSamples = ArrayDeque<AccelSample>()
    private var lastSnapshot = StKSnapshot(
        timestampMs = 0L,
        status = StKStatus.STK1,
        rawStatus = StKStatus.STK1,
        avg = null,
        variance = null,
        maxMagnitude = null,
        source = AccelCoordinateSource.NORM_ONLY,
        confidence = 0f
    )

    fun addSample(sample: AccelSample) {
        slotSamples.addLast(sample)
    }

    fun addNormSample(norm: Float, timestampMs: Long) {
        slotSamples.addLast(
            AccelSample(
                timestampMs = timestampMs,
                x = 0f,
                y = 0f,
                z = 0f,
                norm = norm.coerceAtLeast(0f),
                source = AccelCoordinateSource.NORM_ONLY
            )
        )
    }

    fun consumeSlot(timestampMs: Long): StKSnapshot {
        val samples = mutableListOf<AccelSample>()
        while (slotSamples.isNotEmpty() && slotSamples.first().timestampMs <= timestampMs) {
            samples += slotSamples.removeFirst()
        }

        val vector = buildWindowVector(samples, timestampMs)
        if (vector == null) {
            return lastSnapshot.copy(
                timestampMs = timestampMs,
                avg = null,
                variance = null,
                maxMagnitude = null,
                source = AccelCoordinateSource.NORM_ONLY,
                confidence = 0f
            )
        }

        val params = paramsProvider.current()
        val rawStatus = when {
            vector.avgMagnitude >= params.stK4AvgThreshold &&
                vector.directionalityRatio != null &&
                vector.directionalityRatio <= params.stK4RatioThreshold -> StKStatus.STK4
            // K2/K3：往復振動的な動き（徒歩など）。
            vector.scalarAvg >= params.stK2ScalarAvgThreshold ||
                vector.variance >= params.stK2VarianceThreshold -> StKStatus.STK2
            else -> StKStatus.STK1
        }
        val snapshot = StKSnapshot(
            timestampMs = timestampMs,
            status = rawStatus,
            rawStatus = rawStatus,
            avg = vector.avgMagnitude,
            scalarAvg = vector.scalarAvg,
            directionalityRatio = vector.directionalityRatio,
            variance = vector.variance,
            maxMagnitude = null,
            source = vector.source,
            confidence = confidenceFor(rawStatus, vector, params)
        )
        lastSnapshot = snapshot
        return snapshot
    }

    private fun confidenceFor(
        status: StKStatus,
        vector: AccelWindowVector,
        params: MotionStateParams
    ): Float = when (status) {
        StKStatus.STK4 -> {
            val avgScore = (vector.avgMagnitude / params.stK4AvgThreshold).coerceIn(0f, 3f) / 3f
            val ratio = vector.directionalityRatio ?: return 0f
            val ratioScore = (1f - (ratio / params.stK4RatioThreshold).coerceIn(0f, 1f))
                .coerceIn(0f, 1f)
            ((avgScore + ratioScore) / 2f).coerceIn(0f, 1f)
        }
        StKStatus.STK2 -> maxOf(
            vector.scalarAvg / params.stK2ScalarAvgThreshold,
            vector.variance / params.stK2VarianceThreshold
        ).coerceIn(0f, 3f) / 3f
        StKStatus.STK1 -> (1f - (vector.avgMagnitude / params.stK4AvgThreshold).coerceIn(0f, 1f))
            .coerceIn(0f, 1f)
    }
}

private fun buildWindowVector(samples: List<AccelSample>, timestampMs: Long): AccelWindowVector? {
    if (samples.isEmpty()) return null

    var sumX = 0f
    var sumY = 0f
    var sumZ = 0f
    var maxMagnitude = -1f
    var maxX = 0f
    var maxY = 0f
    var maxZ = 0f
    var horizontalMax = 0f
    samples.forEach { sample ->
        sumX += sample.x
        sumY += sample.y
        sumZ += sample.z
        val horizontal = horizontalMagnitude(sample.x, sample.y)
        if (horizontal > horizontalMax) horizontalMax = horizontal
        if (sample.norm > maxMagnitude) {
            maxMagnitude = sample.norm
            maxX = sample.x
            maxY = sample.y
            maxZ = sample.z
        }
    }

    val count = samples.size
    val avgX = sumX / count
    val avgY = sumY / count
    val avgZ = sumZ / count
    val avgMagnitude = horizontalMagnitude(avgX, avgY)
    val scalarAvg = samples.map { it.norm }.average().toFloat()
    val variance = samples.map { value ->
        val delta = value.norm - scalarAvg
        delta * delta
    }.average().toFloat()
    val directionalityStddev = computeProjectedStddev(samples, avgX, avgY)
    val directionalityRatio =
        if (avgMagnitude >= HORIZONTAL_RATIO_EPSILON && directionalityStddev != null) {
            directionalityStddev / avgMagnitude
        } else {
            null
        }

    return AccelWindowVector(
        timestampMs = timestampMs,
        avgX = avgX,
        avgY = avgY,
        avgZ = avgZ,
        maxX = maxX,
        maxY = maxY,
        maxZ = maxZ,
        avgMagnitude = avgMagnitude,
        directionalityStddev = directionalityStddev,
        directionalityRatio = directionalityRatio,
        scalarAvg = scalarAvg,
        maxMagnitude = maxMagnitude.coerceAtLeast(0f),
        horizontalAvgMagnitude = horizontalMagnitude(avgX, avgY),
        horizontalMaxMagnitude = horizontalMax,
        variance = variance,
        sampleCount = count,
        source = dominantSource(samples)
    )
}

private fun dominantSource(samples: List<AccelSample>): AccelCoordinateSource =
    samples.groupingBy { it.source }
        .eachCount()
        .maxWithOrNull(compareBy<Map.Entry<AccelCoordinateSource, Int>> { it.value }
            .thenBy { sourcePriority(it.key) })
        ?.key ?: AccelCoordinateSource.NORM_ONLY

private fun sourcePriority(source: AccelCoordinateSource): Int = when (source) {
    AccelCoordinateSource.WORLD_ROTATION_VECTOR -> 4
    AccelCoordinateSource.WORLD_ACCEL_MAG -> 3
    AccelCoordinateSource.DEVICE -> 2
    AccelCoordinateSource.NORM_ONLY -> 1
}

private fun norm(x: Float, y: Float, z: Float): Float =
    sqrt(x * x + y * y + z * z)

private fun horizontalMagnitude(x: Float, y: Float): Float =
    sqrt(x * x + y * y)

private fun computeProjectedStddev(samples: List<AccelSample>, avgX: Float, avgY: Float): Float? {
    if (samples.isEmpty()) return null
    val k = horizontalMagnitude(avgX, avgY)
    if (k < HORIZONTAL_RATIO_EPSILON) return null

    val unitX = avgX / k
    val unitY = avgY / k
    val projections = FloatArray(samples.size)
    var projectionSum = 0f
    samples.forEachIndexed { index, sample ->
        val projection = sample.x * unitX + sample.y * unitY
        projections[index] = projection
        projectionSum += projection
    }
    val projectionAvg = projectionSum / samples.size
    var squaredSum = 0f
    projections.forEach { projection ->
        val delta = projection - projectionAvg
        squaredSum += delta * delta
    }
    return sqrt(squaredSum / projections.size)
}
