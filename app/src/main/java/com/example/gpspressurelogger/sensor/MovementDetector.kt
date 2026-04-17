package com.example.gpspressurelogger.sensor

import com.example.gpspressurelogger.util.LoggingConfig
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 加速度センサーと歩数増分から移動状態を推定する。
 */
class MovementDetector {

    enum class Mode {
        UNKNOWN,
        DEVICE_STILL,
        STOPPED,
        WALKING,
        VEHICLE
    }

    companion object {
        data class ModeCandidateResult(
            val candidateMode: Mode,
            val walkingScore: Float,
            val immediate: Boolean
        )

        private const val WINDOW_SIZE = 30
        private const val MIN_SAMPLE_SIZE = WINDOW_SIZE / 2
        private const val WINDOW_SECONDS = LoggingConfig.SLOT_INTERVAL_SECONDS
        private const val STEP_SMOOTH_WINDOW_COUNT = 3

        const val DEVICE_STILL_STDDEV_MAX = 0.03f
        const val DEVICE_STILL_MAD_MAX = 0.02f
        const val DEVICE_STILL_EXIT_STDDEV = 0.05f
        const val DEVICE_STILL_EXIT_MAD = 0.03f
        const val STOPPED_STDDEV_MAX = 0.08f
        const val WALK_STDDEV_MIN = 0.08f
        const val WALK_STDDEV_MAX = 0.80f
        const val WALK_STEP_RATE_REF = 1.00f
        const val WALK_ACCEL_WEIGHT = 0.65f
        const val WALK_STEP_WEIGHT = 0.35f
        const val WALK_STEP_BONUS = 0.15f
        const val WALKING_SCORE_ENTER = 0.55f
        const val WALKING_SCORE_FAST_ENTER = 0.80f
        const val VEHICLE_STDDEV_ENTER = 1.20f
        const val VEHICLE_STDDEV_FAST_ENTER = 1.60f
        const val VEHICLE_STEP_RATE_BLOCK = 1.00f
        const val VEHICLE_WALKING_SCORE_BLOCK = 0.85f
        const val STEPLESS_MOVING_MAX_STEP_DELTA_9S = 1

        private const val DEVICE_STILL_ENTER_WINDOWS = 5
        private const val DEVICE_STILL_EXIT_WINDOWS = 2
        private const val STOPPED_CONFIRM_WINDOWS = 2
        private const val WALKING_CONFIRM_WINDOWS = 2
        private const val VEHICLE_CONFIRM_WINDOWS = 3

        const val GRAVITY = 9.80665f

        fun computeModeCandidate(
            currentMode: Mode,
            stddev: Float?,
            mad: Float?,
            stepDelta9s: Int,
            stepRate9s: Float
        ): ModeCandidateResult {
            if (stddev == null || mad == null) {
                return ModeCandidateResult(
                    candidateMode = Mode.UNKNOWN,
                    walkingScore = 0f,
                    immediate = false
                )
            }

            val walkAccelStrength = normalize(stddev, WALK_STDDEV_MIN, WALK_STDDEV_MAX)
            val walkStepStrength = (stepRate9s / WALK_STEP_RATE_REF).coerceIn(0f, 1f)
            val stepBonus = if (stepDelta9s > 0) WALK_STEP_BONUS else 0f
            val walkingScore = (
                WALK_ACCEL_WEIGHT * walkAccelStrength +
                    WALK_STEP_WEIGHT * walkStepStrength +
                    stepBonus
                ).coerceIn(0f, 1f)

            val deviceStillCandidate =
                stddev <= DEVICE_STILL_STDDEV_MAX &&
                    mad <= DEVICE_STILL_MAD_MAX &&
                    stepDelta9s == 0
            val keepDeviceStill =
                currentMode == Mode.DEVICE_STILL &&
                    stddev < DEVICE_STILL_EXIT_STDDEV &&
                    mad < DEVICE_STILL_EXIT_MAD &&
                    stepDelta9s == 0

            val stepLessMoving = stepDelta9s <= STEPLESS_MOVING_MAX_STEP_DELTA_9S
            val vehicleBlockedByWalking =
                !stepLessMoving &&
                    stepRate9s >= VEHICLE_STEP_RATE_BLOCK &&
                    walkingScore >= VEHICLE_WALKING_SCORE_BLOCK
            val vehicleFastCandidate =
                stddev >= VEHICLE_STDDEV_FAST_ENTER && !vehicleBlockedByWalking
            val vehicleCandidate =
                stddev >= VEHICLE_STDDEV_ENTER &&
                    !vehicleBlockedByWalking &&
                    (stepLessMoving || stepRate9s < VEHICLE_STEP_RATE_BLOCK)
            val preferVehicleForStepLess =
                stepLessMoving &&
                    stddev >= WALK_STDDEV_MIN &&
                    !deviceStillCandidate &&
                    stddev >= STOPPED_STDDEV_MAX

            val candidateMode = when {
                keepDeviceStill -> Mode.DEVICE_STILL
                deviceStillCandidate -> Mode.DEVICE_STILL
                vehicleFastCandidate -> Mode.VEHICLE
                vehicleCandidate -> Mode.VEHICLE
                walkingScore >= WALKING_SCORE_FAST_ENTER && !stepLessMoving -> Mode.WALKING
                walkingScore >= WALKING_SCORE_ENTER && !stepLessMoving -> Mode.WALKING
                preferVehicleForStepLess -> Mode.VEHICLE
                stddev < STOPPED_STDDEV_MAX && stepDelta9s == 0 -> Mode.STOPPED
                stddev >= WALK_STDDEV_MIN && stddev < VEHICLE_STDDEV_ENTER -> if (stepLessMoving) Mode.VEHICLE else Mode.WALKING
                else -> Mode.STOPPED
            }

            val immediate = when (candidateMode) {
                Mode.WALKING -> !stepLessMoving && walkingScore >= WALKING_SCORE_FAST_ENTER
                Mode.VEHICLE -> stddev >= VEHICLE_STDDEV_FAST_ENTER || preferVehicleForStepLess
                else -> false
            }

            return ModeCandidateResult(
                candidateMode = candidateMode,
                walkingScore = walkingScore,
                immediate = immediate
            )
        }

        fun requiredWindowsForMode(mode: Mode): Int = when (mode) {
            Mode.DEVICE_STILL -> DEVICE_STILL_ENTER_WINDOWS
            Mode.STOPPED -> STOPPED_CONFIRM_WINDOWS
            Mode.WALKING -> WALKING_CONFIRM_WINDOWS
            Mode.VEHICLE -> VEHICLE_CONFIRM_WINDOWS
            Mode.UNKNOWN -> DEVICE_STILL_EXIT_WINDOWS
        }

        private fun normalize(value: Float, minValue: Float, maxValue: Float): Float {
            if (maxValue <= minValue) return 0f
            return ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
        }
    }

    data class DetectionResult(
        val candidateMode: Mode,
        val confirmedMode: Mode,
        val stddev: Float?,
        val mad: Float?,
        val stepDelta3s: Int?,
        val stepRate3s: Float?,
        val stepDelta9s: Int,
        val stepRate9s: Float,
        val walkingScore: Float
    )

    private val samples = ArrayDeque<Float>()
    private val stepHistory = ArrayDeque<Int>()
    private var currentMode = Mode.UNKNOWN
    private var pendingMode = Mode.UNKNOWN
    private var pendingCount = 0

    fun addSample(ax: Float, ay: Float, az: Float) {
        synchronized(samples) {
            val magnitude = sqrt(ax * ax + ay * ay + az * az)
            val motion = abs(magnitude - GRAVITY)
            samples.addLast(motion)
            if (samples.size > WINDOW_SIZE) {
                samples.removeFirst()
            }
        }
    }

    fun currentMode(): Mode = currentMode

    fun update(stepDelta3s: Int?): DetectionResult {
        val normalizedStepDelta = stepDelta3s?.coerceAtLeast(0)
        synchronized(stepHistory) {
            stepHistory.addLast(normalizedStepDelta ?: 0)
            while (stepHistory.size > STEP_SMOOTH_WINDOW_COUNT) {
                stepHistory.removeFirst()
            }
        }

        val stepDelta9s = synchronized(stepHistory) { stepHistory.sum() }
        val stepRate3s = normalizedStepDelta?.div(WINDOW_SECONDS)
        val stepRate9s = stepDelta9s / (WINDOW_SECONDS * STEP_SMOOTH_WINDOW_COUNT)

        val currentSamples = synchronized(samples) {
            if (samples.size < MIN_SAMPLE_SIZE) {
                return confirmMode(
                    candidateMode = Mode.UNKNOWN,
                    stddev = null,
                    mad = null,
                    stepDelta3s = normalizedStepDelta,
                    stepRate3s = stepRate3s,
                    stepDelta9s = stepDelta9s,
                    stepRate9s = stepRate9s,
                    walkingScore = 0f,
                    immediate = false
                )
            }
            samples.toList()
        }

        val stddev = calculateStdDev(currentSamples)
        val mad = calculateMad(currentSamples)
        val candidateResult = computeModeCandidate(
            currentMode = currentMode,
            stddev = stddev,
            mad = mad,
            stepDelta9s = stepDelta9s,
            stepRate9s = stepRate9s
        )

        return confirmMode(
            candidateMode = candidateResult.candidateMode,
            stddev = stddev,
            mad = mad,
            stepDelta3s = normalizedStepDelta,
            stepRate3s = stepRate3s,
            stepDelta9s = stepDelta9s,
            stepRate9s = stepRate9s,
            walkingScore = candidateResult.walkingScore,
            immediate = candidateResult.immediate
        )
    }

    private fun confirmMode(
        candidateMode: Mode,
        stddev: Float?,
        mad: Float?,
        stepDelta3s: Int?,
        stepRate3s: Float?,
        stepDelta9s: Int,
        stepRate9s: Float,
        walkingScore: Float,
        immediate: Boolean
    ): DetectionResult {
        val confirmedMode = when {
            candidateMode == Mode.UNKNOWN -> currentMode
            candidateMode == currentMode -> {
                pendingMode = Mode.UNKNOWN
                pendingCount = 0
                currentMode
            }
            immediate -> {
                currentMode = candidateMode
                pendingMode = Mode.UNKNOWN
                pendingCount = 0
                currentMode
            }
            else -> {
                if (candidateMode != pendingMode) {
                    pendingMode = candidateMode
                    pendingCount = 1
                } else {
                    pendingCount += 1
                }
                if (pendingCount >= requiredWindowsForMode(candidateMode)) {
                    currentMode = candidateMode
                    pendingMode = Mode.UNKNOWN
                    pendingCount = 0
                }
                currentMode
            }
        }

        return DetectionResult(
            candidateMode = candidateMode,
            confirmedMode = confirmedMode,
            stddev = stddev,
            mad = mad,
            stepDelta3s = stepDelta3s,
            stepRate3s = stepRate3s,
            stepDelta9s = stepDelta9s,
            stepRate9s = stepRate9s,
            walkingScore = walkingScore
        )
    }

    private fun calculateStdDev(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean).pow(2) }.average().toFloat()
        return sqrt(variance)
    }

    private fun calculateMad(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        return values.map { abs(it - mean) }.average().toFloat()
    }
}
