package com.example.gpspressurelogger.sensor

import kotlin.math.abs
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
    /** 代表方向への射影列に平均射影と逆符号の値が含まれるか。 */
    val hasOppositeSign: Boolean,
    /** スカラー平均ノルム = mean(|a_i|)。動きの総量を反映する。 */
    val scalarAvg: Float,
    val maxMagnitude: Float,
    val horizontalAvgMagnitude: Float,
    val horizontalMaxMagnitude: Float,
    val variance: Float,
    val sampleCount: Int,
    val source: AccelCoordinateSource
)

private data class TrKProjectionWindow(
    /** 2秒窓で求めた基準方向 H への、直近1秒サンプル射影値の平均。符号を保持する。 */
    val avgProjection: Float,
    val directionalityRatio: Float?,
    val hasOppositeSign: Boolean,
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
    private var currentStatus = TrKStatus.TRK2_TRK3

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
        val projection = buildTrKProjectionWindow(samples.toList(), timestampMs, params)
        if (projection == null) {
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

        val avgAbs = abs(projection.avgProjection)
        val rawStatus = when {
            avgAbs < params.trK1AvgThreshold -> TrKStatus.TRK1
            avgAbs >= params.trKAvgThreshold &&
                avgAbs < params.trKAvgUpperThreshold &&
                !projection.hasOppositeSign &&
                projection.directionalityRatio != null &&
                projection.directionalityRatio <= params.trKRatioThreshold -> TrKStatus.TRK4
            else -> TrKStatus.TRK2_TRK3
        }
        currentStatus = rawStatus
        return TrKSnapshot(
            timestampMs = timestampMs,
            status = currentStatus,
            rawStatus = rawStatus,
            avg = projection.avgProjection,
            scalarAvg = null,
            directionalityRatio = projection.directionalityRatio,
            maxMagnitude = null,
            horizontalMaxMagnitude = null,
            source = projection.source,
            confidence = confidenceFor(projection, params)
        )
    }

    private fun trimSamples(nowMs: Long) {
        val params = paramsProvider.current()
        val windowStart = nowMs - maxOf(params.trKWindowMs, params.trKDirectionWindowMs)
        while (samples.isNotEmpty() && samples.first().timestampMs < windowStart) {
            samples.removeFirst()
        }
    }

    private fun confidenceFor(projection: TrKProjectionWindow, params: MotionStateParams): Float {
        val avgAbs = abs(projection.avgProjection)
        if (avgAbs >= params.trKAvgUpperThreshold || projection.hasOppositeSign) return 0f
        val avgScore = (avgAbs / params.trKAvgThreshold).coerceIn(0f, 3f) / 3f
        val ratio = projection.directionalityRatio ?: return 0f
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
        return buildSnapshot(samples, timestampMs, updateLast = true)
    }

    fun peekSlot(timestampMs: Long): StKSnapshot =
        buildSnapshot(slotSamples.filter { it.timestampMs <= timestampMs }, timestampMs, updateLast = false)

    private fun buildSnapshot(
        samples: List<AccelSample>,
        timestampMs: Long,
        updateLast: Boolean
    ): StKSnapshot {
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
                vector.avgMagnitude < params.stK4AvgUpperThreshold &&
                !vector.hasOppositeSign &&
                vector.directionalityRatio != null &&
                vector.directionalityRatio <= params.stK4RatioThreshold -> StKStatus.STK4
            vector.avgMagnitude < params.stK1AvgThreshold -> StKStatus.STK1
            // K2/K3：往復振動的な動き（徒歩など）。
            vector.scalarAvg >= params.stK2ScalarAvgThreshold ||
                vector.variance >= params.stK2VarianceThreshold -> StKStatus.STK2
            else -> StKStatus.STK2
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
        if (updateLast) lastSnapshot = snapshot
        return snapshot
    }

    private fun confidenceFor(
        status: StKStatus,
        vector: AccelWindowVector,
        params: MotionStateParams
    ): Float {
        return when (status) {
        StKStatus.STK4 -> {
            if (vector.avgMagnitude >= params.stK4AvgUpperThreshold || vector.hasOppositeSign) return 0f
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
        StKStatus.STK1 -> (1f - (vector.avgMagnitude / params.stK1AvgThreshold).coerceIn(0f, 1f))
            .coerceIn(0f, 1f)
        }
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
    val projectionStats = computeProjectionStats(samples, avgX, avgY)
    val directionalityRatio =
        if (avgMagnitude >= HORIZONTAL_RATIO_EPSILON && projectionStats != null) {
            projectionStats.stddev / avgMagnitude
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
        directionalityStddev = projectionStats?.stddev,
        directionalityRatio = directionalityRatio,
        hasOppositeSign = projectionStats?.hasOppositeSign ?: false,
        scalarAvg = scalarAvg,
        maxMagnitude = maxMagnitude.coerceAtLeast(0f),
        horizontalAvgMagnitude = horizontalMagnitude(avgX, avgY),
        horizontalMaxMagnitude = horizontalMax,
        variance = variance,
        sampleCount = count,
        source = dominantSource(samples)
    )
}

private fun buildTrKProjectionWindow(
    samples: List<AccelSample>,
    timestampMs: Long,
    params: MotionStateParams
): TrKProjectionWindow? {
    if (samples.isEmpty()) return null

    val directionStart = timestampMs - params.trKDirectionWindowMs
    var directionSumX = 0f
    var directionSumY = 0f
    var directionCount = 0
    samples.forEach { sample ->
        if (sample.timestampMs in directionStart..timestampMs) {
            directionSumX += sample.x
            directionSumY += sample.y
            directionCount += 1
        }
    }
    if (directionCount == 0) return null

    val directionAvgX = directionSumX / directionCount
    val directionAvgY = directionSumY / directionCount
    val directionMagnitude = horizontalMagnitude(directionAvgX, directionAvgY)
    if (directionMagnitude < HORIZONTAL_RATIO_EPSILON) return null

    val unitX = directionAvgX / directionMagnitude
    val unitY = directionAvgY / directionMagnitude
    val projectionStart = timestampMs - params.trKWindowMs
    val projectionSamples = samples.filter { it.timestampMs in projectionStart..timestampMs }
    if (projectionSamples.isEmpty()) return null

    val projections = FloatArray(projectionSamples.size)
    var projectionSum = 0f
    projectionSamples.forEachIndexed { index, sample ->
        val projection = sample.x * unitX + sample.y * unitY
        projections[index] = projection
        projectionSum += projection
    }

    val avgProjection = projectionSum / projections.size
    var squaredSum = 0f
    projections.forEach { projection ->
        val delta = projection - avgProjection
        squaredSum += delta * delta
    }
    val stddev = sqrt(squaredSum / projections.size)
    val avgAbs = abs(avgProjection)
    val ratio = if (avgAbs >= HORIZONTAL_RATIO_EPSILON) stddev / avgAbs else null
    val hasOppositeSign =
        avgAbs >= HORIZONTAL_RATIO_EPSILON && projections.any { projection -> projection * avgProjection < 0f }

    return TrKProjectionWindow(
        avgProjection = avgProjection,
        directionalityRatio = ratio,
        hasOppositeSign = hasOppositeSign,
        source = dominantSource(projectionSamples)
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

private data class ProjectionStats(
    val stddev: Float,
    val hasOppositeSign: Boolean
)

private fun computeProjectionStats(samples: List<AccelSample>, avgX: Float, avgY: Float): ProjectionStats? {
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
    return ProjectionStats(
        stddev = sqrt(squaredSum / projections.size),
        hasOppositeSign = projections.any { projection -> projection * projectionAvg < 0f }
    )
}
