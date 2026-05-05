package com.example.gpspressurelogger.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.data.SettingsRepository
import com.example.gpspressurelogger.util.ExportUtil
import com.example.gpspressurelogger.util.GraphUtil
import com.example.gpspressurelogger.util.GpsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val altitudeM: Double = 0.0,
    val pressureRaw: Float = 0f,
    val pressureQnh: Float = 0f,
    val stepsToday: Int = 0,
    val lookbackMin: Int = SettingsRepository.DEFAULT_LOOKBACK_MIN,
    val history: List<LogEntry> = emptyList(),
    val motionHistory: List<MotionSample> = emptyList(),
    val graphWindowEndMs: Long = System.currentTimeMillis(),
    val latestTimestampMs: Long? = null,
    val isCurrentLoaded: Boolean = false,
    val isGraphLoaded: Boolean = false,
    val graphLoadError: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val settings = SettingsRepository(application)
    private val appContext = application

    private companion object {
        const val GRAPH_INTERPOLATION_CONTEXT_MS = 30 * 60_000L
        const val GRAPH_MIN_VISIBLE_LOOKBACK_MS = 30 * 60_000L
    }

    private val graphWindowController = GraphWindowController(
        initialWindowEndMs = System.currentTimeMillis(),
        minVisibleLookbackMs = GRAPH_MIN_VISIBLE_LOOKBACK_MS,
        maxZoomOutFactor = GraphUtil.MAX_ZOOM_OUT_FACTOR
    )
    private val requestedVisibleLookbackMs = MutableStateFlow(GRAPH_MIN_VISIBLE_LOOKBACK_MS)

    private val lookbackMinFlow: Flow<Int> = settings.lookbackMin
        .onStart { emit(SettingsRepository.DEFAULT_LOOKBACK_MIN) }
        .catch { e ->
            ExportUtil.writeLocalDebugLog(appContext, "HOME_LOOKBACK_FLOW_ERROR: ${e.message}")
            emit(SettingsRepository.DEFAULT_LOOKBACK_MIN)
        }
        .distinctUntilChanged()

    private val latestEntryFlow: Flow<LogEntry?> = db.logDao().observeLatest()
        .onStart { emit(db.logDao().getLatest()) }
        .catch { e ->
            ExportUtil.writeLocalDebugLog(appContext, "HOME_LATEST_FLOW_ERROR: ${e.message}")
            emit(null)
        }

    private val todayLoggingStartFlow: Flow<Long> = latestEntryFlow
        .map { latest ->
            GpsUtil.getLoggingStart(latest?.timestamp ?: System.currentTimeMillis())
        }
        .distinctUntilChanged()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val todayStepsFlow: Flow<Int> =
        todayLoggingStartFlow.flatMapLatest { loggingStart ->
            db.logDao().getEntriesSinceAsc(loggingStart)
        }
        .map { entries -> GraphUtil.calculateTodaySteps(entries) }
        .onStart { emit(0) }
        .catch { e ->
            ExportUtil.writeLocalDebugLog(appContext, "HOME_TODAY_STEPS_FLOW_ERROR: ${e.message}")
            emit(0)
        }
        .flowOn(Dispatchers.Default)

    private data class GraphWindowData(
        val windowEndMs: Long,
        val entries: List<LogEntry>,
        val motionSamples: List<MotionSample>,
        val isLoaded: Boolean = false,
        val errorMessage: String? = null
    )

    private data class CurrentMetricsData(
        val lookbackMin: Int = SettingsRepository.DEFAULT_LOOKBACK_MIN,
        val latestEntry: LogEntry? = null,
        val stepsToday: Int = 0,
        val isLoaded: Boolean = false
    )

    private val currentMetricsFlow: Flow<CurrentMetricsData> = combine(
        lookbackMinFlow,
        latestEntryFlow,
        todayStepsFlow
    ) { lookbackMin, latest, stepsToday ->
        CurrentMetricsData(
            lookbackMin = lookbackMin,
            latestEntry = latest,
            stepsToday = stepsToday,
            isLoaded = true
        )
    }.onStart {
        emit(CurrentMetricsData())
    }.catch { e ->
        ExportUtil.writeLocalDebugLog(appContext, "HOME_CURRENT_METRICS_FLOW_ERROR: ${e.message}")
        emit(CurrentMetricsData(isLoaded = true))
    }

    private val graphWindowFlow: Flow<GraphWindowData> =
        combine(
            lookbackMinFlow,
            graphWindowController.windowEndMs,
            requestedVisibleLookbackMs
        ) { lookbackMin, windowEnd, requestedVisibleLookback ->
            Triple(lookbackMin, windowEnd, requestedVisibleLookback)
        }
        .distinctUntilChanged()
        .conflate()
        .map { (lookbackMin, windowEnd, requestedVisibleLookback) ->
            val configuredLookbackMs = lookbackMin * 60_000L
            val visibleLookbackMs = requestedVisibleLookback.coerceIn(
                GRAPH_MIN_VISIBLE_LOOKBACK_MS,
                configuredLookbackMs * GraphUtil.MAX_ZOOM_OUT_FACTOR
            )
            val windowStart = (windowEnd - visibleLookbackMs).coerceAtLeast(0L)
            val fromTs = minOf(
                GpsUtil.getLoggingStart(windowStart),
                (windowStart - GRAPH_INTERPOLATION_CONTEXT_MS).coerceAtLeast(0L)
            ).coerceAtLeast(0L)
            try {
                val toExclusiveTs = (windowEnd + 1L).coerceAtLeast(windowEnd)
                val entries = withContext(Dispatchers.IO) {
                    db.logDao().getEntriesBetweenAscOnce(fromTs, toExclusiveTs)
                }
                val motionSamples = withContext(Dispatchers.IO) {
                    db.motionSampleDao().getBetweenOnce(fromTs, toExclusiveTs)
                }
                GraphWindowData(
                    windowEndMs = windowEnd,
                    entries = entries,
                    motionSamples = motionSamples,
                    isLoaded = true
                )
            } catch (e: Exception) {
                ExportUtil.writeLocalDebugLog(
                    appContext,
                    "HOME_GRAPH_WINDOW_FLOW_ERROR: windowEndMs=$windowEnd fromTs=$fromTs message=${e.message}"
                )
                GraphWindowData(
                    windowEndMs = windowEnd,
                    entries = emptyList(),
                    motionSamples = emptyList(),
                    isLoaded = true,
                    errorMessage = e.message
                )
            }
        }
        .onStart {
            emit(
                GraphWindowData(
                    windowEndMs = graphWindowController.windowEndMs.value,
                    entries = emptyList(),
                    motionSamples = emptyList(),
                    isLoaded = false
                )
            )
        }

    val uiState: StateFlow<HomeUiState> = combine(
        currentMetricsFlow,
        graphWindowFlow,
    ) { currentMetrics, graphWindow ->
        val history = graphWindow.entries
        val latestMetrics = GraphUtil.resolveLatestMetricValues(history, currentMetrics.latestEntry)
        HomeUiState(
            altitudeM = latestMetrics.altitude ?: 0.0,
            pressureRaw = latestMetrics.pressureRaw ?: 0f,
            pressureQnh = latestMetrics.pressureQnh ?: 0f,
            stepsToday = currentMetrics.stepsToday,
            lookbackMin = currentMetrics.lookbackMin,
            history = history,
            motionHistory = graphWindow.motionSamples,
            graphWindowEndMs = graphWindow.windowEndMs,
            latestTimestampMs = currentMetrics.latestEntry?.timestamp,
            isCurrentLoaded = currentMetrics.isLoaded,
            isGraphLoaded = graphWindow.isLoaded,
            graphLoadError = graphWindow.errorMessage
        )
    }.catch { e ->
        ExportUtil.writeLocalDebugLog(appContext, "HOME_UI_STATE_FLOW_ERROR: ${e.message}")
        emit(
            HomeUiState(
                isCurrentLoaded = true,
                isGraphLoaded = true,
                graphLoadError = e.message
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            db.logDao().getLatest()?.timestamp?.let { latestTimestamp ->
                graphWindowController.resetToLatest(latestTimestamp)
                ExportUtil.writeLocalDebugLog(
                    getApplication(),
                    "HOME_GRAPH_INIT_TO_LATEST: latestTs=$latestTimestamp"
                )
            }
        }
        viewModelScope.launch {
            latestEntryFlow.collect { latest ->
                graphWindowController.onLatestTimestamp(latest?.timestamp)
            }
        }
    }

    fun shiftGraphWindowBy(deltaMs: Long, visibleLookbackMs: Long) {
        viewModelScope.launch {
            val oldest = db.logDao().getOldestTimestamp() ?: return@launch
            val latest = db.logDao().getLatest()?.timestamp ?: return@launch
            val configuredLookbackMs = settings.lookbackMin.first() * 60_000L
            graphWindowController.shiftBy(
                deltaMs = deltaMs,
                visibleLookbackMs = visibleLookbackMs,
                oldestTimestampMs = oldest,
                latestTimestampMs = latest,
                configuredLookbackMs = configuredLookbackMs
            )
        }
    }

    fun updateGraphVisibleLookback(visibleLookbackMs: Long) {
        viewModelScope.launch {
            val configuredLookbackMs = settings.lookbackMin.first() * 60_000L
            val clampedVisibleLookbackMs = visibleLookbackMs.coerceIn(
                GRAPH_MIN_VISIBLE_LOOKBACK_MS,
                configuredLookbackMs * GraphUtil.MAX_ZOOM_OUT_FACTOR
            )
            if (requestedVisibleLookbackMs.value != clampedVisibleLookbackMs) {
                requestedVisibleLookbackMs.value = clampedVisibleLookbackMs
            }
        }
    }

    fun resetGraphWindowToLatest() {
        viewModelScope.launch {
            val latestTimestamp = db.logDao().getLatest()?.timestamp ?: System.currentTimeMillis()
            graphWindowController.resetToLatest(latestTimestamp)
            ExportUtil.writeLocalDebugLog(
                getApplication(),
                "HOME_GRAPH_RESET_TO_LATEST: latestTs=$latestTimestamp"
            )
        }
    }

    fun recoverGraphWindowIfEmpty(
        historySize: Int,
        latestTimestampMs: Long?,
        graphWindowEndMs: Long
    ) {
        if (historySize >= 2 || latestTimestampMs == null || graphWindowEndMs == latestTimestampMs) return
        viewModelScope.launch {
            val latestTimestamp = db.logDao().getLatest()?.timestamp ?: return@launch
            if (graphWindowController.windowEndMs.value == latestTimestamp) return@launch
            graphWindowController.resetToLatest(latestTimestamp)
            ExportUtil.writeLocalDebugLog(
                getApplication(),
                "HOME_GRAPH_EMPTY_RECOVER: historySize=$historySize windowEndMs=$graphWindowEndMs latestTs=$latestTimestamp"
            )
        }
    }
}
