package com.example.gpspressurelogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.gpspressurelogger.R
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.data.SettingsRepository
import com.example.gpspressurelogger.sensor.MotionGpsPoint
import com.example.gpspressurelogger.sensor.MotionStateManager
import com.example.gpspressurelogger.sensor.MotionStateSnapshot
import com.example.gpspressurelogger.sensor.MovementDetector.Mode
import com.example.gpspressurelogger.sensor.StaticMotionStateParamsProvider
import com.example.gpspressurelogger.util.ExportUtil
import com.example.gpspressurelogger.util.GpsUtil
import com.example.gpspressurelogger.util.LoggingConfig
import com.example.gpspressurelogger.util.PressureUtil
import com.example.gpspressurelogger.widget.MapWidgetReceiver
import com.example.gpspressurelogger.widget.PressureWidgetReceiver
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 気圧・GPS定期記録フォアグラウンドサービス
 */
class LoggingService : Service(), SensorEventListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var sensorManager: SensorManager
    private var pressureSensor:      Sensor? = null
    private var linearAccelerationSensor: Sensor? = null
    private var fallbackAccelerometerSensor: Sensor? = null
    private var stepCounterSensor:   Sensor? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private data class GpsSample(
        val timestampMs: Long,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float
    )

    private data class AggregatedGpsPoint(
        val timestampMs: Long,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float?
    )

    private var lastPressure: Float? = null
    private var lastStepCount: Int? = null
    private var lastSlotStepCount: Int? = null
    private val gpsPool = ArrayDeque<GpsSample>()
    private var lastAcceptedGpsPoint: AggregatedGpsPoint? = null
    private var lastBootstrapGpsRequestMs: Long = 0L

    private var nextPressureWidgetUpdateMs: Long = Long.MAX_VALUE
    private var nextMapWidgetUpdateMs:      Long = Long.MAX_VALUE
    private var currentPressureWidgetIntervalMs: Long = -1L
    private var currentMapWidgetIntervalMs: Long = -1L

    private val motionDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MotionStateManager")
    }.asCoroutineDispatcher()
    private val motionScope = CoroutineScope(SupervisorJob() + motionDispatcher)
    private val motionStateParamsProvider = StaticMotionStateParamsProvider()
    private val motionStateManager = MotionStateManager(motionStateParamsProvider)
    private var currentGpsMode   = Mode.UNKNOWN
    private var currentGpsRequestIntervalMs: Long = -1L
    private var lastGpsIntervalChangeMs: Long = 0L
    private var lastImmediateGpsRequestMs: Long = 0L
    private var previousSlotMode = Mode.UNKNOWN

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private var samplingJob: Job? = null
    private var hasLoggedFirstRecordAfterStart = false

    override fun onCreate() {
        super.onCreate()
        db       = AppDatabase.getInstance(this)
        settings = SettingsRepository(this)

        ExportUtil.writeDebugLog(this, "SERVICE_CREATE: サービスを開始しました")

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor      = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        fallbackAccelerometerSensor =
            if (linearAccelerationSensor == null) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        stepCounterSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { loc ->
                    enqueueGpsLocation(loc)
                }
            }
        }

        registerAllSensors()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(Mode.UNKNOWN))
    }

    private fun registerAllSensors() {
        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        linearAccelerationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        fallbackAccelerometerSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        stepCounterSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ExportUtil.writeDebugLog(
            this,
            "SERVICE_START_COMMAND: action=${intent?.action} flags=$flags startId=$startId"
        )
        hasLoggedFirstRecordAfterStart = false
        updateGpsRequest(
            mode = Mode.UNKNOWN,
            targetIntervalMs = motionStateParamsProvider.current().gpsStretchMaxMs,
            immediate = false,
            referenceTimestampMs = System.currentTimeMillis(),
            force = true
        )
        startSamplingLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        ExportUtil.writeDebugLog(this, "SERVICE_DESTROY: サービスを終了しました")
        ExportUtil.flushPendingCsvQueues(this)
        super.onDestroy()
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        motionScope.cancel()
        motionDispatcher.close()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSensors(mode: Mode, interval: Long) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(LoggingConfig.GPS_MIN_INTERVAL_MS).build()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            requestBootstrapLocationIfNeeded(mode)
        } catch (e: SecurityException) { Log.e(TAG, "Location error", e) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> lastPressure = event.values[0]
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val timestampMs = System.currentTimeMillis()
                motionScope.launch {
                    motionStateManager.addLinearAccelerationSample(ax, ay, az, timestampMs)
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (linearAccelerationSensor == null) {
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val az = event.values[2]
                    val timestampMs = System.currentTimeMillis()
                    val motionNorm = abs(sqrt(ax * ax + ay * ay + az * az) - GRAVITY_MPS2)
                    motionScope.launch {
                        motionStateManager.addAccelerationNormSample(motionNorm, timestampMs)
                    }
                }
            }
            Sensor.TYPE_STEP_COUNTER -> lastStepCount = event.values[0].toInt()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startSamplingLoop() {
        samplingJob?.cancel()
        samplingJob = serviceScope.launch {
            while (isActive) {
                delay(LoggingConfig.SLOT_INTERVAL_MS)
                processSamplingSlot()
            }
        }
    }

    private suspend fun processSamplingSlot() {
        val slotTimestamp = System.currentTimeMillis()
        val gpsSamplesInSlot = synchronized(gpsPool) { gpsPool.count { it.timestampMs <= slotTimestamp } }
        val stepDeltaRaw = consumeStepDeltaForSlot()
        val motionSnapshot = withContext(motionDispatcher) {
            motionStateManager.addStepDelta(stepDeltaRaw, slotTimestamp)
            motionStateManager.updateBaseCycle(slotTimestamp)
        }
        persistMotionSample(slotTimestamp, motionSnapshot, stepDeltaRaw)

        val previousMode = currentGpsMode
        val gpsRequestChanged = updateGpsRequest(
            mode = motionSnapshot.finalMode,
            targetIntervalMs = motionSnapshot.gpsSampling.intervalMs,
            immediate = motionSnapshot.gpsSampling.immediate,
            referenceTimestampMs = slotTimestamp,
            force = motionSnapshot.finalMode != currentGpsMode
        )

        if (motionSnapshot.finalMode != previousMode) {
            ExportUtil.writeDebugLog(
                this,
                buildModeLogMessage(previousMode, motionSnapshot)
            )
        } else if (gpsRequestChanged) {
            val avgText = motionSnapshot.kStatus.avg?.let { String.format("%.3f", it) } ?: "null"
            val varText = motionSnapshot.kStatus.variance?.let { String.format("%.4f", it) } ?: "null"
            ExportUtil.writeDebugLog(
                this,
                "GPS_INTERVAL_CHANGED: mode=${motionSnapshot.finalMode} intervalSec=${currentGpsRequestIntervalMs / 1000.0} " +
                    "k=${motionSnapshot.kStatus.status} w=${motionSnapshot.wStatus.status} avg=$avgText var=$varText"
            )
        }

        val entry = buildLogEntry(
            slotTimestamp = slotTimestamp,
            mode = motionSnapshot.finalMode,
            stepDelta = stepDeltaRaw ?: 0
        )
        ExportUtil.writeVerboseDebugLog(
            this,
            "SLOT_SUMMARY: timestamp=$slotTimestamp mode=${motionSnapshot.finalMode} " +
                "gpsPoolCount=$gpsSamplesInSlot hasGps=${entry.hasLocation} " +
                "pressure=${entry.pressureRaw} stepsDelta=${entry.stepsDelta}"
        )
        val rowId = db.logDao().insertReplace(entry)
        val persistedEntry = entry.copy(id = rowId)
        ExportUtil.enqueueEntryToLocalCsv(this, persistedEntry)
        if (!hasLoggedFirstRecordAfterStart) {
            ExportUtil.writeDebugLog(
                this,
                "SERVICE_FIRST_RECORD_SUCCESS: timestamp=$slotTimestamp hasGps=${persistedEntry.hasLocation} " +
                    "lat=${persistedEntry.latitude} lon=${persistedEntry.longitude} " +
                    "pressure=${persistedEntry.pressureRaw} stepsDelta=${persistedEntry.stepsDelta}"
            )
            hasLoggedFirstRecordAfterStart = true
        }

        previousSlotMode = motionSnapshot.finalMode

        updateWidgetsIfDue(slotTimestamp)
    }

    private fun updateNotification(mode: Mode) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(mode))
    }

    private fun buildNotification(mode: Mode): Notification {
        val intervalSecondsText = currentGpsRequestIntervalMs
            .takeIf { it > 0L }
            ?.let { String.format("%.1f", it / 1000.0) }
            ?: "?"
        val modeStr = when (mode) {
            Mode.DEVICE_STILL -> "完全停止 (GPS ${intervalSecondsText}s)"
            Mode.STOPPED -> "静止中 (GPS ${intervalSecondsText}s)"
            Mode.WALKING -> "歩行中 (GPS ${intervalSecondsText}s)"
            Mode.VEHICLE -> "乗り物 (GPS ${intervalSecondsText}s)"
            Mode.UNKNOWN -> "起動中..."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS・気圧ロガー")
            .setContentText(modeStr)
            .setSmallIcon(R.drawable.ic_logger_notification)
            .setOngoing(true)
            .build()
    }

    private fun updateGpsRequest(
        mode: Mode,
        targetIntervalMs: Long,
        immediate: Boolean,
        referenceTimestampMs: Long,
        force: Boolean
    ): Boolean {
        if (immediate) {
            requestImmediateLocationIfDue(referenceTimestampMs, targetIntervalMs)
        }
        val shouldShorten = currentGpsRequestIntervalMs <= 0L || targetIntervalMs < currentGpsRequestIntervalMs
        val shouldLengthen =
            targetIntervalMs > currentGpsRequestIntervalMs &&
                (referenceTimestampMs - lastGpsIntervalChangeMs) >= LoggingConfig.GPS_DYNAMIC_INTERVAL_MIN_HOLD_MS
        val shouldRestart =
            force ||
                currentGpsRequestIntervalMs <= 0L ||
                shouldShorten ||
                shouldLengthen
        if (!shouldRestart) return false

        currentGpsMode = mode
        currentGpsRequestIntervalMs = targetIntervalMs
        lastGpsIntervalChangeMs = referenceTimestampMs
        startSensors(mode, targetIntervalMs)
        updateNotification(mode)
        return true
    }

    private fun requestImmediateLocationIfDue(nowMs: Long, cooldownMs: Long) {
        val effectiveCooldownMs = cooldownMs.coerceAtLeast(LoggingConfig.GPS_MIN_INTERVAL_MS)
        if (nowMs - lastImmediateGpsRequestMs < effectiveCooldownMs) return
        lastImmediateGpsRequestMs = nowMs
        try {
            val request = CurrentLocationRequest.Builder()
                .setMaxUpdateAgeMillis(0L)
                .build()
            fusedLocationClient.getCurrentLocation(request, null)
                .addOnSuccessListener { location ->
                    location?.let { enqueueGpsLocation(it) }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Immediate location error", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "GPS記録", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "LoggingService"
        private const val GRAVITY_MPS2 = 9.80665f
        const val CHANNEL_ID = "logging_service_channel"
        const val NOTIFICATION_ID = 1001
    }

    private fun consumeStepDeltaForSlot(): Int? {
        val current = lastStepCount ?: return null
        val previous = lastSlotStepCount
        lastSlotStepCount = current
        if (previous == null) return null
        return if (current >= previous) {
            current - previous
        } else {
            current.coerceAtLeast(0)
        }
    }

    private suspend fun updateWidgetsIfDue(slotTimestamp: Long) {
        val pressureIntervalMs = (settings.pressureWidgetIntervalMin.first().coerceAtLeast(1)) * 1000L
        if (pressureIntervalMs != currentPressureWidgetIntervalMs) {
            currentPressureWidgetIntervalMs = pressureIntervalMs
            nextPressureWidgetUpdateMs = slotTimestamp + pressureIntervalMs
        }
        if (slotTimestamp >= nextPressureWidgetUpdateMs) {
            PressureWidgetReceiver.updateAll(this)
            nextPressureWidgetUpdateMs = alignNextWidgetUpdate(slotTimestamp, pressureIntervalMs)
        }

        val mapIntervalMs = (settings.mapWidgetIntervalMin.first().coerceAtLeast(1)) * 1000L
        if (mapIntervalMs != currentMapWidgetIntervalMs) {
            currentMapWidgetIntervalMs = mapIntervalMs
            nextMapWidgetUpdateMs = slotTimestamp + mapIntervalMs
        }
        if (slotTimestamp >= nextMapWidgetUpdateMs) {
            MapWidgetReceiver.updateAll(this)
            nextMapWidgetUpdateMs = alignNextWidgetUpdate(slotTimestamp, mapIntervalMs)
        }
    }

    private fun alignNextWidgetUpdate(nowMs: Long, intervalMs: Long): Long {
        if (intervalMs <= 0L) return nowMs
        val intervalsElapsed = nowMs / intervalMs
        return (intervalsElapsed + 1L) * intervalMs
    }

    private suspend fun persistMotionSample(
        slotTimestamp: Long,
        snapshot: MotionStateSnapshot,
        stepDelta3s: Int?
    ) {
        val normalizedStepDelta = stepDelta3s?.coerceAtLeast(0)
        val stepRate3s = normalizedStepDelta?.div(LoggingConfig.SLOT_INTERVAL_SECONDS)
        val region = snapshot.completedRegion ?: snapshot.activeRegionEstimate
        val sample = MotionSample(
            timestamp = slotTimestamp,
            stepDelta3s = normalizedStepDelta,
            stepRate3s = stepRate3s,
            kStatus = snapshot.kStatus.status.name,
            kRawStatus = snapshot.kStatus.rawStatus.name,
            kAvg = snapshot.kStatus.avg,
            kVariance = snapshot.kStatus.variance,
            kConfidence = snapshot.kStatus.confidence,
            wStatus = snapshot.wStatus.status.name,
            stepDeltaWindow = snapshot.wStatus.stepDeltaWindow,
            gpsIntervalMs = snapshot.gpsSampling.intervalMs,
            gpsImmediate = snapshot.gpsSampling.immediate,
            confirmedMode = snapshot.finalMode.name,
            constantRegionKind = region?.kind?.name,
            constantRegionSpeedKmh = region?.averageSpeedKmh,
            constantRegionStartLat = region?.startPoint?.latitude,
            constantRegionStartLon = region?.startPoint?.longitude,
            constantRegionEndLat = region?.endPoint?.latitude,
            constantRegionEndLon = region?.endPoint?.longitude,
            constantRegionStayLat = region?.stayPoint?.latitude,
            constantRegionStayLon = region?.stayPoint?.longitude,
            constantRegionDirectionDeg = region?.directionDeg
        )
        db.motionSampleDao().insertReplace(sample)
        ExportUtil.enqueueMotionSampleToLocalCsv(this, sample)
    }

    private fun enqueueGpsLocation(location: android.location.Location) {
        val accuracy = location.accuracy
        if (accuracy <= 0f || accuracy > LoggingConfig.GPS_ACCURACY_THRESHOLD_M * 2) return
        val latitude = location.latitude
        val longitude = location.longitude
        if (latitude == 0.0 && longitude == 0.0) return
        val altitude = location.altitude
        val timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        synchronized(gpsPool) {
            gpsPool.addLast(
                GpsSample(
                    timestampMs = timestampMs,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    accuracy = accuracy
                )
            )
        }
        motionScope.launch {
            motionStateManager.addGpsPoint(
                MotionGpsPoint(
                    timestampMs = timestampMs,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    accuracy = accuracy
                )
            )
        }
    }

    private fun buildLogEntry(
        slotTimestamp: Long,
        mode: Mode,
        stepDelta: Int
    ): LogEntry {
        val pressure = lastPressure
        val aggregatedGps = aggregateGpsForSlot(slotTimestamp, mode)
        val qnh = if (pressure != null && aggregatedGps != null) {
            PressureUtil.calcQnh(pressure, aggregatedGps.altitude)
        } else {
            null
        }
        return LogEntry(
            timestamp = slotTimestamp,
            latitude = aggregatedGps?.latitude,
            longitude = aggregatedGps?.longitude,
            altitudeGps = aggregatedGps?.altitude,
            pressureRaw = pressure,
            pressureQnh = qnh,
            gpsAccuracy = aggregatedGps?.accuracy,
            stepsDelta = stepDelta
        )
    }

    private fun aggregateGpsForSlot(slotTimestamp: Long, mode: Mode): AggregatedGpsPoint? {
        val slotSamples = synchronized(gpsPool) {
            val samples = mutableListOf<GpsSample>()
            while (gpsPool.isNotEmpty() && gpsPool.first().timestampMs <= slotTimestamp) {
                samples += gpsPool.removeFirst()
            }
            samples
        }
        if (slotSamples.isEmpty()) {
            if (mode == Mode.DEVICE_STILL || mode == Mode.STOPPED) {
                reuseLastAcceptedGpsPoint(slotTimestamp)?.let { return it }
                requestBootstrapLocationIfNeeded(mode)
            }
            return null
        }
        val aggregated = when (mode) {
            Mode.WALKING, Mode.VEHICLE -> aggregateMovingGps(slotTimestamp, slotSamples)
            Mode.DEVICE_STILL -> averageGps(slotSamples, includeBefore = previousSlotMode == Mode.DEVICE_STILL)
            Mode.STOPPED, Mode.UNKNOWN -> averageGps(
                slotSamples,
                includeBefore = previousSlotMode == Mode.DEVICE_STILL || previousSlotMode == Mode.STOPPED
            )
        }
        lastAcceptedGpsPoint = aggregated
        return aggregated
    }

    private fun reuseLastAcceptedGpsPoint(slotTimestamp: Long): AggregatedGpsPoint? {
        val previous = lastAcceptedGpsPoint ?: return null
        val ageMs = slotTimestamp - previous.timestampMs
        if (ageMs > LoggingConfig.STATIONARY_GPS_REUSE_MAX_AGE_MS) return null
        return previous.copy(timestampMs = slotTimestamp)
    }

    private fun requestBootstrapLocationIfNeeded(mode: Mode) {
        if (mode != Mode.UNKNOWN && mode != Mode.DEVICE_STILL && mode != Mode.STOPPED) return
        val now = System.currentTimeMillis()
        if (now - lastBootstrapGpsRequestMs < LoggingConfig.GPS_BOOTSTRAP_COOLDOWN_MS) return
        lastBootstrapGpsRequestMs = now
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    location?.let { enqueueGpsLocation(it) }
                }
            val request = CurrentLocationRequest.Builder()
                .setMaxUpdateAgeMillis(LoggingConfig.GPS_BOOTSTRAP_MAX_UPDATE_AGE_MS)
                .build()
            fusedLocationClient.getCurrentLocation(request, null)
                .addOnSuccessListener { location ->
                    location?.let { enqueueGpsLocation(it) }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bootstrap location error", e)
        }
    }

    private fun aggregateMovingGps(slotTimestamp: Long, slotSamples: List<GpsSample>): AggregatedGpsPoint {
        if (slotSamples.size == 1) {
            return slotSamples.first().toAggregated()
        }
        val points = buildInterpolationPoints(slotSamples)
        if (points.size < 2) {
            return slotSamples.last().toAggregated()
        }
        val target = slotTimestamp.toDouble()
        return AggregatedGpsPoint(
            timestampMs = slotTimestamp,
            latitude = interpolateCubic(points.map { it.timestampMs.toDouble() }, points.map { it.latitude }, target),
            longitude = interpolateCubic(points.map { it.timestampMs.toDouble() }, points.map { it.longitude }, target),
            altitude = interpolateCubic(points.map { it.timestampMs.toDouble() }, points.map { it.altitude }, target),
            accuracy = points.minOfOrNull { it.accuracy }
        )
    }

    private fun buildInterpolationPoints(slotSamples: List<GpsSample>): List<GpsSample> {
        val allPoints = mutableListOf<GpsSample>()
        lastAcceptedGpsPoint?.let {
            allPoints += GpsSample(
                timestampMs = it.timestampMs,
                latitude = it.latitude,
                longitude = it.longitude,
                altitude = it.altitude,
                accuracy = it.accuracy ?: LoggingConfig.GPS_ACCURACY_THRESHOLD_M
            )
        }
        allPoints += slotSamples
        return allPoints
            .sortedBy { it.timestampMs }
            .distinctBy { it.timestampMs }
    }

    private fun averageGps(slotSamples: List<GpsSample>, includeBefore: Boolean): AggregatedGpsPoint {
        val samples = mutableListOf<GpsSample>()
        if (includeBefore) {
            lastAcceptedGpsPoint?.let {
                samples += GpsSample(
                    timestampMs = it.timestampMs,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    altitude = it.altitude,
                    accuracy = it.accuracy ?: LoggingConfig.GPS_ACCURACY_THRESHOLD_M
                )
            }
        }
        samples += slotSamples
        return AggregatedGpsPoint(
            timestampMs = slotSamples.last().timestampMs,
            latitude = samples.map { it.latitude }.average(),
            longitude = samples.map { it.longitude }.average(),
            altitude = samples.map { it.altitude }.average(),
            accuracy = samples.map { it.accuracy }.average().toFloat()
        )
    }

    private fun interpolateCubic(xValues: List<Double>, yValues: List<Double>, target: Double): Double {
        if (xValues.isEmpty() || yValues.isEmpty()) return 0.0
        if (xValues.size == 1) return yValues.first()
        if (target <= xValues.first()) return yValues.first()
        if (target >= xValues.last()) return yValues.last()
        if (xValues.size == 2) {
            return linearInterpolate(xValues[0], yValues[0], xValues[1], yValues[1], target)
        }

        val n = xValues.size
        val h = DoubleArray(n - 1) { index -> xValues[index + 1] - xValues[index] }
        if (h.any { it <= 0.0 }) {
            return linearInterpolate(
                xValues[n - 2], yValues[n - 2],
                xValues[n - 1], yValues[n - 1],
                target
            )
        }

        val alpha = DoubleArray(n)
        for (index in 1 until n - 1) {
            alpha[index] =
                (3.0 / h[index]) * (yValues[index + 1] - yValues[index]) -
                    (3.0 / h[index - 1]) * (yValues[index] - yValues[index - 1])
        }

        val l = DoubleArray(n)
        val mu = DoubleArray(n)
        val z = DoubleArray(n)
        l[0] = 1.0
        for (index in 1 until n - 1) {
            l[index] = 2.0 * (xValues[index + 1] - xValues[index - 1]) - h[index - 1] * mu[index - 1]
            mu[index] = h[index] / l[index]
            z[index] = (alpha[index] - h[index - 1] * z[index - 1]) / l[index]
        }
        l[n - 1] = 1.0

        val c = DoubleArray(n)
        val b = DoubleArray(n - 1)
        val d = DoubleArray(n - 1)
        for (index in n - 2 downTo 0) {
            c[index] = z[index] - mu[index] * c[index + 1]
            b[index] = (yValues[index + 1] - yValues[index]) / h[index] - h[index] * (c[index + 1] + 2.0 * c[index]) / 3.0
            d[index] = (c[index + 1] - c[index]) / (3.0 * h[index])
        }

        var segment = n - 2
        for (index in 0 until n - 1) {
            if (target <= xValues[index + 1]) {
                segment = index
                break
            }
        }
        val dx = target - xValues[segment]
        return yValues[segment] + b[segment] * dx + c[segment] * dx * dx + d[segment] * dx * dx * dx
    }

    private fun linearInterpolate(x0: Double, y0: Double, x1: Double, y1: Double, target: Double): Double {
        if (x1 == x0) return y1
        return y0 + (y1 - y0) * (target - x0) / (x1 - x0)
    }

    private fun GpsSample.toAggregated(): AggregatedGpsPoint = AggregatedGpsPoint(
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracy = accuracy
    )

    private fun buildModeLogMessage(
        previousMode: Mode,
        snapshot: MotionStateSnapshot
    ): String {
        val avgText = snapshot.kStatus.avg?.let { String.format("%.3f", it) } ?: "null"
        val varText = snapshot.kStatus.variance?.let { String.format("%.4f", it) } ?: "null"
        val regionText = snapshot.completedRegion?.let {
            "${it.kind}, speed=${String.format("%.2f", it.averageSpeedKmh)}km/h"
        } ?: "none"
        return "MODE_CONFIRMED: $previousMode -> ${snapshot.finalMode} " +
            "(k=${snapshot.kStatus.status}, rawK=${snapshot.kStatus.rawStatus}, " +
            "w=${snapshot.wStatus.status}, avg=$avgText, var=$varText, " +
            "stepDeltaWindow=${snapshot.wStatus.stepDeltaWindow}, " +
            "gpsIntervalSec=${snapshot.gpsSampling.intervalMs / 1000.0}, " +
            "gpsImmediate=${snapshot.gpsSampling.immediate}, constantRegion=$regionText)"
    }
}

