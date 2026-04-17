package com.example.gpspressurelogger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "motion_samples",
    indices = [Index(value = ["timestamp"], unique = true)]
)
data class MotionSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val accelStddev3s: Float? = null,
    val accelMad3s: Float? = null,
    val stepDelta3s: Int? = null,
    val stepRate3s: Float? = null,
    val kStatus: String? = null,
    val kRawStatus: String? = null,
    val kAvg: Float? = null,
    val kVariance: Float? = null,
    val kConfidence: Float? = null,
    val wStatus: String? = null,
    val stepDeltaWindow: Int? = null,
    val gpsIntervalMs: Long? = null,
    val gpsImmediate: Boolean? = null,
    val confirmedMode: String? = null,
    val constantRegionKind: String? = null,
    val constantRegionSpeedKmh: Double? = null,
    val constantRegionStartLat: Double? = null,
    val constantRegionStartLon: Double? = null,
    val constantRegionEndLat: Double? = null,
    val constantRegionEndLon: Double? = null,
    val constantRegionStayLat: Double? = null,
    val constantRegionStayLon: Double? = null,
    val constantRegionDirectionDeg: Double? = null
)
