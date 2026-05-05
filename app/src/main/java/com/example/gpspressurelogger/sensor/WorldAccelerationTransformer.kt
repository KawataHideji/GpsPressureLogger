package com.example.gpspressurelogger.sensor

import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * 端末座標の線形加速度を、可能な限り世界座標(East/North/Up)へ変換する。
 *
 * 判定器は Android の SensorEvent を知らず、変換済みの AccelSample だけを扱う。
 */
class WorldAccelerationTransformer {
    private val rotationMatrix = FloatArray(9)
    private val tempRotationMatrix = FloatArray(9)
    private val orientationAccelValues = FloatArray(3)
    private val magneticValues = FloatArray(3)

    private var hasRotationVectorMatrix = false
    private var hasOrientationAccel = false
    private var hasMagnetic = false

    fun updateRotationVector(values: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        hasRotationVectorMatrix = true
    }

    fun updateOrientationAcceleration(ax: Float, ay: Float, az: Float) {
        orientationAccelValues[0] = ax
        orientationAccelValues[1] = ay
        orientationAccelValues[2] = az
        hasOrientationAccel = true
    }

    fun updateMagneticField(x: Float, y: Float, z: Float) {
        magneticValues[0] = x
        magneticValues[1] = y
        magneticValues[2] = z
        hasMagnetic = true
    }

    fun transformLinearAcceleration(
        ax: Float,
        ay: Float,
        az: Float,
        timestampMs: Long
    ): AccelSample {
        val source = when {
            hasRotationVectorMatrix -> AccelCoordinateSource.WORLD_ROTATION_VECTOR
            hasOrientationAccel && hasMagnetic &&
                SensorManager.getRotationMatrix(tempRotationMatrix, null, orientationAccelValues, magneticValues) ->
                AccelCoordinateSource.WORLD_ACCEL_MAG
            else -> AccelCoordinateSource.DEVICE
        }
        val matrix = when (source) {
            AccelCoordinateSource.WORLD_ROTATION_VECTOR -> rotationMatrix
            AccelCoordinateSource.WORLD_ACCEL_MAG -> tempRotationMatrix
            else -> null
        }
        val (x, y, z) = if (matrix != null) {
            Triple(
                matrix[0] * ax + matrix[1] * ay + matrix[2] * az,
                matrix[3] * ax + matrix[4] * ay + matrix[5] * az,
                matrix[6] * ax + matrix[7] * ay + matrix[8] * az
            )
        } else {
            Triple(ax, ay, az)
        }
        return AccelSample(
            timestampMs = timestampMs,
            x = x,
            y = y,
            z = z,
            norm = sqrt(x * x + y * y + z * z),
            source = source
        )
    }
}
