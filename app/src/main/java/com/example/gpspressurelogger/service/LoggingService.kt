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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.gpspressurelogger.R
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.data.SettingsRepository
import com.example.gpspressurelogger.sensor.ConstantRegionKind
import com.example.gpspressurelogger.sensor.ConstantRegionResult
import com.example.gpspressurelogger.sensor.GpsAggregationMode
import com.example.gpspressurelogger.sensor.MotionGpsPoint
import com.example.gpspressurelogger.sensor.MotionStateManager
import com.example.gpspressurelogger.sensor.MotionStateSnapshot
import com.example.gpspressurelogger.sensor.MovementDetector.Mode
import com.example.gpspressurelogger.sensor.StKStatus
import com.example.gpspressurelogger.sensor.StaticMotionStateParamsProvider
import com.example.gpspressurelogger.sensor.TrKStatus
import com.example.gpspressurelogger.sensor.TrKTransitionSnapshot
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
    private var rotationVectorSensor: Sensor? = null
    private var orientationAccelerometerSensor: Sensor? = null
    private var magneticFieldSensor: Sensor? = null
    private var fallbackAccelerometerSensor: Sensor? = null
    private var stepCounterSensor:   Sensor? = null
    private var stepDetectorSensor:  Sensor? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private data class GpsSample(
        val timestampMs: Long,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val accuracy: Float
    )

    private data class AggregatedGpsPoint(
        val timestampMs: Long,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val accuracy: Float?
    )

    private var lastPressure: Float? = null
    private val gpsPool = ArrayDeque<GpsSample>()
    private var lastAcceptedGpsPoint: AggregatedGpsPoint? = null
    private var lastGpsAltitude: Double? = null
    private var lastBootstrapGpsRequestMs: Long = 0L

    private var nextPressureWidgetUpdateMs: Long = Long.MAX_VALUE
    private var nextMapWidgetUpdateMs: Long = Long.MAX_VALUE
    private var currentPressureWidgetIntervalMs: Long = -1L
    private var currentMapWidgetIntervalMs: Long = -1L
    // DataStore 毎回読み込みを避けるためにキャッシュ（Flow collector で更新）
    @Volatile private var cachedPressureWidgetIntervalMin: Int = SettingsRepository.DEFAULT_PRESSURE_WIDGET_INTERVAL_MIN
    @Volatile private var cachedMapWidgetIntervalMin: Int = SettingsRepository.DEFAULT_MAP_WIDGET_INTERVAL_MIN

    private val motionDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MotionStateManager")
    }.asCoroutineDispatcher()
    // motionScope.launch 内で例外が発生したとき、SupervisorJob だけでは個別 Job をキャンセルするだけで
    // 例外がデフォルトハンドラに到達して別経路の挙動を変えないよう、明示的に握り潰してログだけ残す。
    // motionStateManager 側の状態を巻き戻すロールバック処理が無い以上、握り潰しは妥当な選択。
    private val motionExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "MotionScope coroutine failed", throwable)
        // ローカル debug log にも残し、長期実機ログから OOM / NPE 系を後追いできるようにする。
        runCatching { ExportUtil.writeDebugLog(this, "MOTION_SCOPE_EXCEPTION: ${throwable.javaClass.simpleName}: ${throwable.message}") }
    }
    private val motionScope = CoroutineScope(SupervisorJob() + motionDispatcher + motionExceptionHandler)
    private val motionStateParamsProvider = StaticMotionStateParamsProvider()
    private val motionStateManager = MotionStateManager(
        paramsProvider = motionStateParamsProvider,
        onStepCountUp = { delta, total, timestampMs ->
            ExportUtil.writeVerboseDebugLog(this, "STEP_COUNT_UP: delta=$delta total=$total ts=$timestampMs")
        },
        onStepReset = {
            ExportUtil.writeDebugLog(this, "STEP_RESET: 歩数をリセットしました")
        },
        onTrKChanged = { snapshot ->
            ExportUtil.writeVerboseDebugLog(this, "TRK_CHANGED: ${snapshot.trK.status}")
            if (snapshot.trK.status == TrKStatus.TRK4) {
                serviceScope.launch {
                    handleTrKTransitionGps(snapshot)
                }
            }
        }
    )
    private var currentGpsMode   = Mode.UNKNOWN
    private var currentGpsRequestIntervalMs: Long = -1L
    private var lastGpsIntervalChangeMs: Long = 0L
    private var lastBurstGpsRequestMs: Long = 0L
    private var burstGpsCallback: LocationCallback? = null
    private var burstGpsTimeoutJob: Job? = null
    private var burstBestLocation: android.location.Location? = null
    private var burstCandidateCount: Int = 0
    // バースト世代カウンタ。requestBurstLocationIfDue で stopBurstGps 後にインクリメントし、
    // その時点で生成した LocationCallback / timeout coroutine の closure に capture する。
    // 入口（handleBurstGpsCandidate / finalizeBurstGps）で「自分が capture した世代」と現在値を比較し、
    // 一致しなければ no-op で抜ける。これにより以下の競合を塞ぐ:
    //   1) timeoutJob.cancel() が間に合わず stale finalizeBurstGps("timeout") が新バーストの callback を殺す
    //   2) removeLocationUpdates 後に FusedLocationProviderClient が遅延配信した古いイベントを
    //      新バーストのカウンタ・best location として誤採用する
    private var burstGeneration: Int = 0
    private var previousGpsAggregationMode = GpsAggregationMode.UNKNOWN
    private var lastPressureRecordMs: Long = 0L
    private var lastLoggedStKStatus: StKStatus? = null
    private var lastLoggedTrKStatus: TrKStatus? = null
    private var lastLoggedWStatus: String? = null
    private var lastLoggedConfirmedMode: Mode? = null
    private var lastLoggedConstantRegionKind: ConstantRegionKind? = null

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private var samplingJob: Job? = null
    private var hasLoggedFirstRecordAfterStart = false
    private val pendingMotionCsvRewriteLock = Any()
    private val pendingMotionCsvRewrites = linkedMapOf<Long, MotionSample>()
    private var pendingMotionCsvRewriteJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        db       = AppDatabase.getInstance(this)
        settings = SettingsRepository(this)

        ExportUtil.writeDebugLog(this, "SERVICE_CREATE: サービスを開始しました")
        ExportUtil.writeDebugLog(
            this,
            "MOTION_LOG_ROUTINE: ${LoggingConfig.MOTION_LOG_ROUTINE.name}"
        )

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        pressureSensor      = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        orientationAccelerometerSensor =
            if (rotationVectorSensor == null) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        magneticFieldSensor =
            if (rotationVectorSensor == null) sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) else null
        fallbackAccelerometerSensor =
            if (linearAccelerationSensor == null) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        stepCounterSensor   = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepDetectorSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        motionStateManager.setStepDetectorAvailable(stepDetectorSensor != null)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { loc ->
                    enqueueGpsLocation(loc)
                }
            }
        }

        // ウィジェット更新間隔設定をキャッシュし、変更時に再スケジュール
        serviceScope.launch {
            settings.pressureWidgetIntervalMin.collect { min ->
                cachedPressureWidgetIntervalMin = min
                currentPressureWidgetIntervalMs = -1L // 次スロットで再スケジュール
            }
        }
        serviceScope.launch {
            settings.mapWidgetIntervalMin.collect { min ->
                cachedMapWidgetIntervalMin = min
                currentMapWidgetIntervalMs = -1L // 次スロットで再スケジュール
            }
        }

        registerAllSensors()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(Mode.UNKNOWN))
    }

    private fun registerAllSensors() {
        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        linearAccelerationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        rotationVectorSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        orientationAccelerometerSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magneticFieldSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        fallbackAccelerometerSensor
            ?.takeIf { it != orientationAccelerometerSensor }
            ?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        stepCounterSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        stepDetectorSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ExportUtil.writeDebugLog(
            this,
            "SERVICE_START_COMMAND: action=${intent?.action} flags=$flags startId=$startId"
        )
        hasLoggedFirstRecordAfterStart = false
        updateGpsRequest(
            mode = Mode.UNKNOWN,
            targetIntervalMs = motionStateParamsProvider.current().gpsStableInitialMs,
            immediate = false,
            referenceTimestampMs = System.currentTimeMillis(),
            force = true
        )
        startSamplingLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        ExportUtil.writeDebugLog(this, "SERVICE_DESTROY: サービスを終了しました")
        runBlocking {
            val pendingJob = synchronized(pendingMotionCsvRewriteLock) {
                pendingMotionCsvRewriteJob?.also { pendingMotionCsvRewriteJob = null }
            }
            pendingJob?.cancelAndJoin()
            flushPendingMotionCsvRewrites("service_destroy")
        }
        ExportUtil.flushPendingCsvQueues(this)
        super.onDestroy()
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        burstGpsCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        burstGpsTimeoutJob?.cancel()
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
            requestBootstrapLocationIfNeeded(gpsAggregationModeForBootstrap(mode))
        } catch (e: SecurityException) { Log.e(TAG, "Location error", e) }
    }

    private fun gpsAggregationModeForBootstrap(mode: Mode): GpsAggregationMode = when (mode) {
        Mode.DEVICE_STILL -> GpsAggregationMode.DEVICE_STILL
        Mode.STOPPED -> GpsAggregationMode.STOPPED
        Mode.WALKING, Mode.VEHICLE -> GpsAggregationMode.MOVING
        Mode.UNKNOWN -> GpsAggregationMode.UNKNOWN
    }

    override fun onSensorChanged(event: SensorEvent) {
        // 加速度系（LinearAccel / Rotation / Magnetic / Accel）は trK / stK 検出のキモであり、
        // motionScope.launch によるジョブ蓄積で OOM の原因になっていた経路。これらは
        // AccelManager 内部が `synchronized(this)` でスレッドセーフなので、Android の sensor
        // スレッドから直接呼び出して取りこぼしゼロ・遅延ゼロにする。
        // 歩数系・GPS は元のまま motionScope.launch 経由（数秒に1回〜数Hzの低頻度なのでジョブ蓄積を起こさない）。
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> lastPressure = event.values[0]
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                motionStateManager.addLinearAccelerationSample(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                    System.currentTimeMillis()
                )
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                motionStateManager.updateRotationVector(event.values.copyOf())
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                motionStateManager.updateMagneticField(
                    event.values[0],
                    event.values[1],
                    event.values[2]
                )
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                if (rotationVectorSensor == null) {
                    motionStateManager.updateOrientationAcceleration(ax, ay, az)
                }
                if (linearAccelerationSensor == null) {
                    val motionNorm = abs(sqrt(ax * ax + ay * ay + az * az) - GRAVITY_MPS2)
                    motionStateManager.addAccelerationNormSample(motionNorm, System.currentTimeMillis())
                }
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val currentTotal = event.values[0].toInt()
                val timestampMs = System.currentTimeMillis()
                motionScope.launch {
                    motionStateManager.addStepCounterTotal(currentTotal, timestampMs)
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                val timestampMs = System.currentTimeMillis()
                motionScope.launch {
                    motionStateManager.addStepDetectorEvent(timestampMs)
                }
            }
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
        val (stepDeltaRaw, motionSnapshot) = withContext(motionDispatcher) {
            val stepDelta = motionStateManager.consumeStepDeltaForSlot()
            stepDelta to motionStateManager.updateBaseCycle(slotTimestamp)
        }
        val previousLoggedStKStatus = lastLoggedStKStatus
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
            val avgText = motionSnapshot.stK.avg?.let { String.format("%.3f", it) } ?: "null"
            val ratioText = motionSnapshot.stK.directionalityRatio?.let { String.format("%.3f", it) } ?: "null"
            val scalarText = motionSnapshot.stK.scalarAvg?.let { String.format("%.3f", it) } ?: "null"
            val varText = motionSnapshot.stK.variance?.let { String.format("%.4f", it) } ?: "null"
            ExportUtil.writeDebugLog(
                this,
                "GPS_INTERVAL_CHANGED: mode=${motionSnapshot.finalMode} intervalSec=${currentGpsRequestIntervalMs / 1000.0} " +
                    "stK=${motionSnapshot.stK.status} w=${motionSnapshot.wStatus.status} " +
                    "avg=$avgText ratio=$ratioText scalar=$scalarText var=$varText accelSource=${motionSnapshot.stK.source}"
            )
        }
        if (previousLoggedStKStatus != StKStatus.STK4 && motionSnapshot.stK.status == StKStatus.STK4) {
            val params = motionStateParamsProvider.current()
            val stKAvgText = motionSnapshot.stK.avg?.let { String.format("%.3f", it) } ?: "null"
            val stKRatioText = motionSnapshot.stK.directionalityRatio?.let { String.format("%.3f", it) } ?: "null"
            val trKAvgText = motionSnapshot.trK.avg?.let { String.format("%.3f", it) } ?: "null"
            val trKRatioText = motionSnapshot.trK.directionalityRatio?.let { String.format("%.3f", it) } ?: "null"
            ExportUtil.writeDebugLog(
                this,
                "AC_EVENT: ts=$slotTimestamp StKAvg=$stKAvgText StKRatio=$stKRatioText " +
                    "StKAvgThreshold=${params.stK4AvgThreshold} StKRatioThreshold=${params.stK4RatioThreshold} " +
                    "TrKAvg=$trKAvgText TrKRatio=$trKRatioText"
            )
        }

        val entry = buildLogEntry(
            slotTimestamp = slotTimestamp,
            gpsAggregationMode = motionSnapshot.gpsAggregationMode,
            stepDelta = stepDeltaRaw ?: 0
        )
        withContext(motionDispatcher) {
            motionStateManager.recordGpsSamplingResult(isGpsAcceptedForStretch(entry))
        }
        ExportUtil.writeVerboseDebugLog(
            this,
            "SLOT_SUMMARY: timestamp=$slotTimestamp mode=${motionSnapshot.finalMode} " +
                "gpsAggregation=${motionSnapshot.gpsAggregationMode} " +
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

        previousGpsAggregationMode = motionSnapshot.gpsAggregationMode

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

    private fun isGpsAcceptedForStretch(entry: LogEntry): Boolean =
        entry.hasLocation &&
            entry.gpsAccuracy != null &&
            entry.gpsAccuracy <= LoggingConfig.GPS_STRETCH_ACCEPT_ACCURACY_M

    @Synchronized
    private fun updateGpsRequest(
        mode: Mode,
        targetIntervalMs: Long,
        immediate: Boolean,
        referenceTimestampMs: Long,
        force: Boolean,
        forceImmediate: Boolean = false
    ): Boolean {
        if (immediate) {
            requestBurstLocationIfDue(referenceTimestampMs, targetIntervalMs, force = forceImmediate)
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

    private fun handleTrKTransitionGps(snapshot: TrKTransitionSnapshot) {
        val trK = snapshot.trK
        val stK = snapshot.stK
        val params = motionStateParamsProvider.current()
        updateGpsRequest(
            mode = Mode.VEHICLE,
            targetIntervalMs = params.gpsKMinMs,
            immediate = true,
            referenceTimestampMs = trK.timestampMs,
            force = true,
            forceImmediate = true
        )
        val avgText = trK.avg?.let { String.format("%.3f", it) } ?: "null"
        val ratioText = trK.directionalityRatio?.let { String.format("%.3f", it) } ?: "null"
        val stKAvgText = stK.avg?.let { String.format("%.3f", it) } ?: "null"
        val stKRatioText = stK.directionalityRatio?.let { String.format("%.3f", it) } ?: "null"
        ExportUtil.writeDebugLog(
            this,
            "TRK_GPS_IMMEDIATE: ts=${trK.timestampMs} intervalSec=${params.gpsKMinMs / 1000.0} " +
                "trK=${trK.status} stK=${stK.status} TrKAvg=$avgText TrKRatio=$ratioText " +
                "StKAvg=$stKAvgText StKRatio=$stKRatioText " +
                "TrKAvgThreshold=${params.trKAvgThreshold} TrKAvgUpperThreshold=${params.trKAvgUpperThreshold} " +
                "TrKRatioThreshold=${params.trKRatioThreshold} " +
                "accelSource=${trK.source} stKAccelSource=${stK.source}"
        )
    }

    @Synchronized
    private fun requestBurstLocationIfDue(nowMs: Long, cooldownMs: Long, force: Boolean = false) {
        // serviceScope は Dispatchers.IO（プール）上で動くため、processSamplingSlot 経由の updateGpsRequest と
        // handleTrKTransitionGps からの呼び出しが別スレッドで同時に走り得る。
        // 同期化しないと cooldown チェック → stopBurstGps → callback 設定 → timeoutJob 設定 の各ステップで
        // 後勝ちが発生し、片方の LocationCallback が orphan になって fusedLocationClient に残り続けるため、
        // メソッド全体を @Synchronized で逐次化する。
        // 内部で呼ぶ stopBurstGps / handleBurstGpsCandidate / finalizeBurstGps / acceptBurstGps も @Synchronized
        // だが、Java の synchronized はリエントラントなので同一スレッドからの再取得は問題ない。
        val effectiveCooldownMs = cooldownMs.coerceAtLeast(LoggingConfig.GPS_MIN_INTERVAL_MS)
        if (!force && nowMs - lastBurstGpsRequestMs < effectiveCooldownMs) {
            ExportUtil.writeDebugLog(
                this,
                "GPS_BURST_SKIPPED_COOLDOWN: ts=$nowMs elapsedSinceLastMs=${nowMs - lastBurstGpsRequestMs} " +
                    "cooldownMs=$effectiveCooldownMs"
            )
            return
        }
        lastBurstGpsRequestMs = nowMs
        stopBurstGps("replaced")
        // 旧バーストの in-flight イベント・stale timeout を世代不一致で弾くため、
        // ここで世代を進める。stopBurstGps が callback を null にしたあとに進めることで、
        // late callback と新バーストの世代がきれいに分離される。
        burstGeneration += 1
        val gen = burstGeneration
        val requestStartedMs = System.currentTimeMillis()
        burstBestLocation = null
        burstCandidateCount = 0
        try {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                LoggingConfig.GPS_BURST_INTERVAL_MS
            )
                .setMinUpdateIntervalMillis(LoggingConfig.GPS_BURST_MIN_INTERVAL_MS)
                .setMaxUpdateAgeMillis(LoggingConfig.GPS_BURST_MAX_UPDATE_AGE_MS)
                .setWaitForAccurateLocation(true)
                .setDurationMillis(LoggingConfig.GPS_BURST_DURATION_MS)
                .setMaxUpdates(LoggingConfig.GPS_BURST_MAX_UPDATES)
                .build()
            ExportUtil.writeDebugLog(
                this,
                "GPS_BURST_START: ts=$nowMs gen=$gen priority=HIGH_ACCURACY " +
                    "intervalMs=${LoggingConfig.GPS_BURST_INTERVAL_MS} " +
                    "minIntervalMs=${LoggingConfig.GPS_BURST_MIN_INTERVAL_MS} " +
                    "maxUpdateAgeMs=${LoggingConfig.GPS_BURST_MAX_UPDATE_AGE_MS} " +
                    "durationMs=${LoggingConfig.GPS_BURST_DURATION_MS} " +
                    "maxUpdates=${LoggingConfig.GPS_BURST_MAX_UPDATES} force=$force"
            )

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach { location ->
                        handleBurstGpsCandidate(location, requestStartedMs, gen)
                    }
                }

                override fun onLocationAvailability(availability: LocationAvailability) {
                    if (!availability.isLocationAvailable) {
                        ExportUtil.writeDebugLog(
                            this@LoggingService,
                            "GPS_BURST_UNAVAILABLE: gen=$gen elapsedMs=${System.currentTimeMillis() - requestStartedMs}"
                        )
                    }
                }
            }
            burstGpsCallback = callback
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            burstGpsTimeoutJob = serviceScope.launch {
                delay(LoggingConfig.GPS_BURST_DURATION_MS + 500L)
                finalizeBurstGps("timeout", expectedGen = gen)
            }
        } catch (e: SecurityException) {
            ExportUtil.writeDebugLog(
                this,
                "GPS_BURST_SECURITY_ERROR: ts=$nowMs error=${e.message ?: ""}"
            )
            Log.e(TAG, "Burst location error", e)
        }
    }

    @Synchronized
    private fun handleBurstGpsCandidate(
        location: android.location.Location,
        requestStartedMs: Long,
        expectedGen: Int
    ) {
        // 旧バーストの callback から removeLocationUpdates 後に遅延配信されたイベントは、
        // ここで世代不一致として弾く。これがないと新バーストのカウンタ・best location を汚染する。
        if (expectedGen != burstGeneration) return
        if (burstGpsCallback == null) return
        burstCandidateCount += 1
        val elapsedMs = System.currentTimeMillis() - requestStartedMs
        val ageMs = locationAgeMs(location)
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY
        val currentBest = burstBestLocation
        if (currentBest == null || accuracy < currentBest.accuracy) {
            burstBestLocation = location
        }
        ExportUtil.writeDebugLog(
            this,
            "GPS_BURST_CANDIDATE: elapsedMs=$elapsedMs ageMs=$ageMs " +
                "accuracy=$accuracy provider=${location.provider} count=$burstCandidateCount " +
                "lat=${location.latitude} lon=${location.longitude}"
        )

        when {
            accuracy <= LoggingConfig.GPS_BURST_GOOD_ACCURACY_M -> {
                acceptBurstGps(location, "good", elapsedMs, ageMs, accuracy)
            }
            burstCandidateCount >= LoggingConfig.GPS_BURST_MAX_UPDATES -> {
                finalizeBurstGps("max_updates")
            }
        }
    }

    @Synchronized
    private fun finalizeBurstGps(reason: String, expectedGen: Int? = null) {
        // timeout coroutine から呼ばれた場合は capture 時の世代と現在世代が一致しないと
        // 「既に置き換わったバーストの timeout」なので no-op で抜ける。
        // handleBurstGpsCandidate からの max_updates 経路では expectedGen=null で素通り
        //（呼び出し元で世代チェック済み）。
        if (expectedGen != null && expectedGen != burstGeneration) return
        val callback = burstGpsCallback ?: return
        val best = burstBestLocation
        val bestAccuracy = best?.takeIf { it.hasAccuracy() }?.accuracy
        if (best != null && bestAccuracy != null && bestAccuracy <= LoggingConfig.GPS_BURST_USABLE_ACCURACY_M) {
            acceptBurstGps(best, "usable_$reason", null, locationAgeMs(best), bestAccuracy)
            return
        }
        fusedLocationClient.removeLocationUpdates(callback)
        burstGpsCallback = null
        burstGpsTimeoutJob?.cancel()
        burstGpsTimeoutJob = null
        burstBestLocation = null
        burstCandidateCount = 0
        ExportUtil.writeDebugLog(
            this,
            "GPS_BURST_REJECT: reason=$reason bestAccuracy=${bestAccuracy ?: "null"}"
        )
    }

    @Synchronized
    private fun acceptBurstGps(
        location: android.location.Location,
        reason: String,
        elapsedMs: Long?,
        ageMs: Long,
        accuracy: Float
    ) {
        val callback = burstGpsCallback ?: return
        fusedLocationClient.removeLocationUpdates(callback)
        burstGpsCallback = null
        burstGpsTimeoutJob?.cancel()
        burstGpsTimeoutJob = null
        burstBestLocation = null
        burstCandidateCount = 0
        ExportUtil.writeDebugLog(
            this,
            "GPS_BURST_ACCEPT: reason=$reason elapsedMs=${elapsedMs ?: "n/a"} " +
                "ageMs=$ageMs accuracy=$accuracy provider=${location.provider} " +
                "lat=${location.latitude} lon=${location.longitude}"
        )
        enqueueGpsLocation(location)
    }

    @Synchronized
    private fun stopBurstGps(reason: String) {
        val callback = burstGpsCallback ?: return
        fusedLocationClient.removeLocationUpdates(callback)
        burstGpsCallback = null
        burstGpsTimeoutJob?.cancel()
        burstGpsTimeoutJob = null
        burstBestLocation = null
        burstCandidateCount = 0
        ExportUtil.writeDebugLog(this, "GPS_BURST_STOP: reason=$reason")
    }

    private fun locationAgeMs(location: android.location.Location): Long {
        val elapsedRealtimeNanos = location.elapsedRealtimeNanos
        return if (elapsedRealtimeNanos > 0L) {
            ((SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
        } else {
            (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
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

    private suspend fun updateWidgetsIfDue(slotTimestamp: Long) {
        // DataStore 読み込みなし: キャッシュ済み値を使用（Flow collector が設定変更時に更新）
        val pressureIntervalMs = cachedPressureWidgetIntervalMin.coerceAtLeast(1) * 1000L
        if (pressureIntervalMs != currentPressureWidgetIntervalMs) {
            currentPressureWidgetIntervalMs = pressureIntervalMs
            nextPressureWidgetUpdateMs = slotTimestamp
            Log.d(TAG, "WIDGET_SCHEDULE_INIT: type=pressure intervalMs=$pressureIntervalMs next=$nextPressureWidgetUpdateMs")
        }
        if (slotTimestamp >= nextPressureWidgetUpdateMs) {
            PressureWidgetReceiver.updateAll(this)
            nextPressureWidgetUpdateMs = slotTimestamp + pressureIntervalMs
            Log.d(TAG, "WIDGET_UPDATE_DUE: type=pressure slot=$slotTimestamp intervalMs=$pressureIntervalMs next=$nextPressureWidgetUpdateMs")
        }

        val mapIntervalMs = cachedMapWidgetIntervalMin.coerceAtLeast(1) * 1000L
        if (mapIntervalMs != currentMapWidgetIntervalMs) {
            currentMapWidgetIntervalMs = mapIntervalMs
            nextMapWidgetUpdateMs = slotTimestamp
            Log.d(TAG, "WIDGET_SCHEDULE_INIT: type=map intervalMs=$mapIntervalMs next=$nextMapWidgetUpdateMs")
        }
        if (slotTimestamp >= nextMapWidgetUpdateMs) {
            MapWidgetReceiver.updateAll(this)
            nextMapWidgetUpdateMs = slotTimestamp + mapIntervalMs
            Log.d(TAG, "WIDGET_UPDATE_DUE: type=map slot=$slotTimestamp intervalMs=$mapIntervalMs next=$nextMapWidgetUpdateMs")
        }
    }

    private suspend fun persistMotionSample(
        slotTimestamp: Long,
        snapshot: MotionStateSnapshot,
        stepDelta3s: Int?
    ) {
        val sample = buildMotionSample(slotTimestamp, snapshot, stepDelta3s)
        if (shouldPersistMotionEvent(snapshot)) {
            db.motionSampleDao().insertReplace(sample)
            ExportUtil.enqueueMotionSampleToLocalCsv(this, sample)
            updateLastLoggedMotionState(snapshot)
        }
        finalizeCompletedConstantRegion(snapshot.completedRegion)
    }

    private fun shouldPersistMotionEvent(snapshot: MotionStateSnapshot): Boolean {
        val region = snapshot.completedRegion ?: snapshot.activeRegionEstimate
        val regionKind = region?.kind?.takeUnless { it == ConstantRegionKind.NONE }
        val confirmedMode = snapshot.finalMode.takeIf { snapshot.finalModeConfirmed }
        return lastLoggedStKStatus == null ||
            snapshot.stK.status != lastLoggedStKStatus ||
            snapshot.trK.status != lastLoggedTrKStatus ||
            snapshot.wStatus.status.name != lastLoggedWStatus ||
            confirmedMode != lastLoggedConfirmedMode ||
            regionKind != lastLoggedConstantRegionKind
    }

    private fun updateLastLoggedMotionState(snapshot: MotionStateSnapshot) {
        val region = snapshot.completedRegion ?: snapshot.activeRegionEstimate
        lastLoggedStKStatus = snapshot.stK.status
        lastLoggedTrKStatus = snapshot.trK.status
        lastLoggedWStatus = snapshot.wStatus.status.name
        lastLoggedConfirmedMode = snapshot.finalMode.takeIf { snapshot.finalModeConfirmed }
        lastLoggedConstantRegionKind = region?.kind?.takeUnless { it == ConstantRegionKind.NONE }
    }

    private fun buildMotionSample(
        slotTimestamp: Long,
        snapshot: MotionStateSnapshot,
        stepDelta3s: Int?
    ): MotionSample =
        when (LoggingConfig.MOTION_LOG_ROUTINE) {
            LoggingConfig.MotionLogRoutine.NORMAL ->
                buildNormalMotionSample(slotTimestamp, snapshot, stepDelta3s)
            LoggingConfig.MotionLogRoutine.FULL ->
                buildFullMotionSample(slotTimestamp, snapshot, stepDelta3s)
        }

    private fun buildNormalMotionSample(
        slotTimestamp: Long,
        snapshot: MotionStateSnapshot,
        stepDelta3s: Int?
    ): MotionSample {
        val normalizedStepDelta = stepDelta3s?.coerceAtLeast(0)
        val region = snapshot.completedRegion ?: snapshot.activeRegionEstimate
        val trK = snapshot.trKImmediate?.trK ?: snapshot.trK
        return MotionSample(
            timestamp = slotTimestamp,
            stepDelta3s = normalizedStepDelta,
            kStatus = snapshot.stK.status.name,
            kRawStatus = snapshot.stK.rawStatus.name,
            kAvg = snapshot.stK.avg,
            kDirectionalityRatio = snapshot.stK.directionalityRatio,
            trKStatus = trK.status.name,
            trKRawStatus = trK.rawStatus.name,
            trKAvg = trK.avg,
            trKDirectionalityRatio = trK.directionalityRatio,
            wStatus = snapshot.wStatus.status.name,
            stepDeltaWindow = snapshot.wStatus.stepDeltaWindow,
            gpsIntervalMs = snapshot.gpsSampling.intervalMs,
            gpsImmediate = snapshot.gpsSampling.immediate,
            confirmedMode = if (snapshot.finalModeConfirmed) snapshot.finalMode.name else null,
            constantRegionKind = region?.kind?.name,
            constantRegionSpeedKmh = region?.averageSpeedKmh,
            constantRegionStartLat = region?.startPoint?.latitude,
            constantRegionStartLon = region?.startPoint?.longitude,
            constantRegionEndLat = region?.endPoint?.latitude,
            constantRegionEndLon = region?.endPoint?.longitude,
            constantRegionStayLat = region?.stayPoint?.latitude,
            constantRegionStayLon = region?.stayPoint?.longitude
        )
    }

    private fun buildFullMotionSample(
        slotTimestamp: Long,
        snapshot: MotionStateSnapshot,
        stepDelta3s: Int?
    ): MotionSample {
        val normalizedStepDelta = stepDelta3s?.coerceAtLeast(0)
        val stepRate3s = normalizedStepDelta?.div(LoggingConfig.SLOT_INTERVAL_SECONDS)
        val trK = snapshot.trKImmediate?.trK ?: snapshot.trK

        // 確定した結果があればそれを、なければ現在の暫定推計（active）を保存する。
        // confirmedMode には確定済みの状態だけを入れ、現在進行中の暫定停止は混ぜない。
        val region = snapshot.completedRegion ?: snapshot.activeRegionEstimate

        return MotionSample(
            timestamp = slotTimestamp,
            stepDelta3s = normalizedStepDelta,
            stepRate3s = stepRate3s,
            kStatus = snapshot.stK.status.name,
            kRawStatus = snapshot.stK.rawStatus.name,
            kAvg = snapshot.stK.avg,
            kScalarAvg = snapshot.stK.scalarAvg,
            kDirectionalityRatio = snapshot.stK.directionalityRatio,
            kVariance = snapshot.stK.variance,
            kMaxMagnitude = snapshot.stK.maxMagnitude,
            kConfidence = snapshot.stK.confidence,
            kAccelSource = snapshot.stK.source.name,
            trKStatus = trK.status.name,
            trKRawStatus = trK.rawStatus.name,
            trKAvg = trK.avg,
            trKScalarAvg = trK.scalarAvg,
            trKDirectionalityRatio = trK.directionalityRatio,
            trKMaxMagnitude = trK.maxMagnitude,
            trKHorizontalMaxMagnitude = trK.horizontalMaxMagnitude,
            trKConfidence = trK.confidence,
            trKAccelSource = trK.source.name,
            wStatus = snapshot.wStatus.status.name,
            stepDeltaWindow = snapshot.wStatus.stepDeltaWindow,
            gpsIntervalMs = snapshot.gpsSampling.intervalMs,
            gpsImmediate = snapshot.gpsSampling.immediate,
            confirmedMode = if (snapshot.finalModeConfirmed) snapshot.finalMode.name else null,
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
    }

    private suspend fun finalizeCompletedConstantRegion(region: ConstantRegionResult?) {
        if (region == null || region.kind == ConstantRegionKind.NONE) return
        if (region.endTimestampMs <= region.startTimestampMs) return

        val regionStartSample = MotionSample(
            timestamp = region.startTimestampMs,
            confirmedMode = when (region.kind) {
                ConstantRegionKind.CONSTANT_MOVE -> Mode.VEHICLE.name
                ConstantRegionKind.STAY -> Mode.STOPPED.name
                ConstantRegionKind.NONE -> null
            },
            constantRegionKind = region.kind.name,
            constantRegionSpeedKmh = region.averageSpeedKmh,
            constantRegionStartLat = region.startPoint?.latitude,
            constantRegionStartLon = region.startPoint?.longitude,
            constantRegionEndLat = region.endPoint?.latitude,
            constantRegionEndLon = region.endPoint?.longitude,
            constantRegionStayLat = region.stayPoint?.latitude,
            constantRegionStayLon = region.stayPoint?.longitude,
            constantRegionDirectionDeg = region.directionDeg
        )
        db.motionSampleDao().insertReplace(regionStartSample)
        ExportUtil.enqueueMotionSampleToLocalCsv(this, regionStartSample)

        val samples = db.motionSampleDao().getBetweenOnce(
            fromTs = region.startTimestampMs,
            toExclusiveTs = region.endTimestampMs
        )
        if (samples.isEmpty()) return

        val finalized = samples.map { sample ->
            sample.copy(
                confirmedMode = finalizedModeFor(sample, region).name,
                constantRegionKind = region.kind.name,
                constantRegionSpeedKmh = region.averageSpeedKmh,
                constantRegionStartLat = region.startPoint?.latitude,
                constantRegionStartLon = region.startPoint?.longitude,
                constantRegionEndLat = region.endPoint?.latitude,
                constantRegionEndLon = region.endPoint?.longitude,
                constantRegionStayLat = region.stayPoint?.latitude,
                constantRegionStayLon = region.stayPoint?.longitude,
                constantRegionDirectionDeg = region.directionDeg
            )
        }

        db.motionSampleDao().insertAllReplace(finalized)
        enqueueMotionCsvRewrite(finalized)
        ExportUtil.writeVerboseDebugLog(
            this,
            "CONSTANT_REGION_FINALIZED: kind=${region.kind} start=${region.startTimestampMs} " +
                "end=${region.endTimestampMs} samples=${finalized.size} speed=${String.format("%.2f", region.averageSpeedKmh)}"
        )
    }

    private fun enqueueMotionCsvRewrite(samples: List<MotionSample>) {
        if (samples.isEmpty()) return
        val flushImmediately = synchronized(pendingMotionCsvRewriteLock) {
            samples.forEach { sample ->
                pendingMotionCsvRewrites[sample.timestamp] = sample
            }
            val shouldFlushNow =
                pendingMotionCsvRewrites.size >= LoggingConfig.CSV_MOTION_REWRITE_MAX_PENDING_SAMPLES
            pendingMotionCsvRewriteJob?.cancel()
            pendingMotionCsvRewriteJob = if (shouldFlushNow) {
                serviceScope.launch {
                    flushPendingMotionCsvRewrites("queue_limit")
                }
            } else {
                serviceScope.launch {
                    delay(LoggingConfig.CSV_MOTION_REWRITE_DEBOUNCE_MS)
                    flushPendingMotionCsvRewrites("debounce")
                }
            }
            shouldFlushNow
        }
        if (flushImmediately) {
            ExportUtil.writeVerboseDebugLog(
                this,
                "MOTION_CSV_REWRITE_SCHEDULED: reason=queue_limit pending=${samples.size}"
            )
        }
    }

    private suspend fun flushPendingMotionCsvRewrites(reason: String) {
        val samples = synchronized(pendingMotionCsvRewriteLock) {
            pendingMotionCsvRewriteJob = null
            if (pendingMotionCsvRewrites.isEmpty()) return
            val batch = pendingMotionCsvRewrites.values.sortedBy { it.timestamp }
            pendingMotionCsvRewrites.clear()
            batch
        }
        ExportUtil.rewriteMotionSamplesInLocalCsv(this, samples)
        ExportUtil.writeVerboseDebugLog(
            this,
            "MOTION_CSV_REWRITE_FLUSH: reason=$reason samples=${samples.size}"
        )
    }

    private fun finalizedModeFor(sample: MotionSample, region: ConstantRegionResult): Mode =
        when (region.kind) {
            ConstantRegionKind.CONSTANT_MOVE -> Mode.VEHICLE
            ConstantRegionKind.STAY -> if (StKStatus.fromStored(sample.kStatus) == StKStatus.STK1) {
                Mode.DEVICE_STILL
            } else {
                Mode.STOPPED
            }
            ConstantRegionKind.NONE -> sample.confirmedMode
                ?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
                ?: Mode.UNKNOWN
        }

    private fun enqueueGpsLocation(location: android.location.Location) {
        val accuracy = location.accuracy
        if (accuracy <= 0f || accuracy > LoggingConfig.GPS_ACCURACY_THRESHOLD_M * 2) return
        val latitude = location.latitude
        val longitude = location.longitude
        if (latitude == 0.0 && longitude == 0.0) return
        val altitude = getLatestAltitude(location)
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
        gpsAggregationMode: GpsAggregationMode,
        stepDelta: Int
    ): LogEntry {
        val shouldRecordPressure = lastPressure != null &&
            (lastPressureRecordMs == 0L ||
                slotTimestamp - lastPressureRecordMs >= LoggingConfig.PRESSURE_RECORD_INTERVAL_MS)
        val pressure = if (shouldRecordPressure) lastPressure else null
        if (shouldRecordPressure) {
            lastPressureRecordMs = slotTimestamp
        }
        val aggregatedGps = aggregateGpsForSlot(slotTimestamp, gpsAggregationMode)
        val altitude = aggregatedGps?.altitude
        val qnh = if (pressure != null && altitude != null) {
            PressureUtil.calcQnh(pressure, altitude)
        } else {
            null
        }
        return LogEntry(
            timestamp = slotTimestamp,
            latitude = aggregatedGps?.latitude,
            longitude = aggregatedGps?.longitude,
            altitudeGps = altitude,
            pressureRaw = pressure,
            pressureQnh = qnh,
            gpsAccuracy = aggregatedGps?.accuracy,
            stepsDelta = stepDelta
        )
    }

    private fun aggregateGpsForSlot(slotTimestamp: Long, gpsAggregationMode: GpsAggregationMode): AggregatedGpsPoint? {
        val slotSamples = synchronized(gpsPool) {
            val samples = mutableListOf<GpsSample>()
            while (gpsPool.isNotEmpty() && gpsPool.first().timestampMs <= slotTimestamp) {
                samples += gpsPool.removeFirst()
            }
            samples
        }
        if (slotSamples.isEmpty()) {
            if (
                gpsAggregationMode == GpsAggregationMode.DEVICE_STILL ||
                gpsAggregationMode == GpsAggregationMode.STOPPED
            ) {
                reuseLastAcceptedGpsPoint(slotTimestamp)?.let { return it }
                requestBootstrapLocationIfNeeded(gpsAggregationMode)
            }
            return null
        }
        val aggregated = when (gpsAggregationMode) {
            GpsAggregationMode.MOVING -> aggregateMovingGps(slotTimestamp, slotSamples)
            GpsAggregationMode.DEVICE_STILL -> averageGps(
                slotSamples,
                includeBefore = previousGpsAggregationMode == GpsAggregationMode.DEVICE_STILL
            )
            GpsAggregationMode.STOPPED, GpsAggregationMode.UNKNOWN -> averageGps(
                slotSamples,
                includeBefore = previousGpsAggregationMode == GpsAggregationMode.DEVICE_STILL ||
                    previousGpsAggregationMode == GpsAggregationMode.STOPPED
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

    private fun requestBootstrapLocationIfNeeded(gpsAggregationMode: GpsAggregationMode) {
        if (
            gpsAggregationMode != GpsAggregationMode.UNKNOWN &&
            gpsAggregationMode != GpsAggregationMode.DEVICE_STILL &&
            gpsAggregationMode != GpsAggregationMode.STOPPED
        ) return
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
        val xs = points.map { it.timestampMs.toDouble() }  // 一度だけ計算
        return AggregatedGpsPoint(
            timestampMs = slotTimestamp,
            latitude = interpolateCubic(xs, points.map { it.latitude }, target),
            longitude = interpolateCubic(xs, points.map { it.longitude }, target),
            altitude = getLatestAltitude(points.asReversed().firstNotNullOfOrNull { it.altitude }),
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
        // allPoints は構造上すでに時系列順（前スロットのpoint + gpsPool FIFO）
        return allPoints.distinctBy { it.timestampMs }
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
        // 3つの map を1パスに統合
        var latSum = 0.0; var lonSum = 0.0; var accSum = 0.0; var latestAlt: Double? = null
        for (s in samples) {
            latSum += s.latitude; lonSum += s.longitude; accSum += s.accuracy
            if (s.altitude != null) latestAlt = s.altitude
        }
        val n = samples.size.toDouble()
        return AggregatedGpsPoint(
            timestampMs = slotSamples.last().timestampMs,
            latitude = latSum / n,
            longitude = lonSum / n,
            altitude = getLatestAltitude(latestAlt),
            accuracy = (accSum / n).toFloat()
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

    @Synchronized
    private fun getLatestAltitude(location: android.location.Location): Double? {
        val altitude = if (location.hasAltitude()) location.altitude else null
        return getLatestAltitude(altitude)
    }

    @Synchronized
    private fun getLatestAltitude(altitude: Double?): Double? {
        if (altitude != null && altitude.isFinite()) {
            lastGpsAltitude = altitude
        }
        return lastGpsAltitude
    }

    private fun GpsSample.toAggregated(): AggregatedGpsPoint = AggregatedGpsPoint(
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitude = getLatestAltitude(altitude),
        accuracy = accuracy
    )

    private fun buildModeLogMessage(
        previousMode: Mode,
        snapshot: MotionStateSnapshot
    ): String {
        val avgText = snapshot.stK.avg?.let { String.format("%.3f", it) } ?: "null"
        val ratioText = snapshot.stK.directionalityRatio?.let { String.format("%.3f", it) } ?: "null"
        val scalarText = snapshot.stK.scalarAvg?.let { String.format("%.3f", it) } ?: "null"
        val varText = snapshot.stK.variance?.let { String.format("%.4f", it) } ?: "null"
        val gpsSpeedText = snapshot.walkingSpeed.gpsSpeedKmh?.let { String.format("%.2f", it) } ?: "null"
        val stepSpeedText = snapshot.walkingSpeed.stepSpeedKmh?.let { String.format("%.2f", it) } ?: "null"
        val speedDiffText = snapshot.walkingSpeed.differenceKmh?.let { String.format("%.2f", it) } ?: "null"
        val region = snapshot.completedRegion ?: snapshot.activeRegionEstimate
        val regionText = region?.let {
            "${it.kind}${if (snapshot.completedRegion != null) "" else "(active)"}, speed=${String.format("%.2f", it.averageSpeedKmh)}km/h"
        } ?: "none"
        return "MODE_CONFIRMED: $previousMode -> ${snapshot.finalMode} " +
            "(stK=${snapshot.stK.status}, rawStK=${snapshot.stK.rawStatus}, " +
            "w=${snapshot.wStatus.status}, avg=$avgText, ratio=$ratioText, scalar=$scalarText, var=$varText, " +
            "accelSource=${snapshot.stK.source}, " +
            "stepDeltaWindow=${snapshot.wStatus.stepDeltaWindow}, " +
            "walkGpsSpeed=$gpsSpeedText, walkStepSpeed=$stepSpeedText, walkSpeedDiff=$speedDiffText, " +
            "gpsAggregation=${snapshot.gpsAggregationMode}, " +
            "gpsIntervalSec=${snapshot.gpsSampling.intervalMs / 1000.0}, " +
            "gpsImmediate=${snapshot.gpsSampling.immediate}, constantRegion=$regionText)"
    }
}

