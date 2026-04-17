package com.example.gpspressurelogger.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.data.SettingsRepository
import com.example.gpspressurelogger.util.GraphUtil
import com.example.gpspressurelogger.util.GpsUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val altitudeM: Double = 0.0,
    val pressureRaw: Float = 0f,
    val pressureQnh: Float = 0f,
    val stepsToday: Int = 0,
    val lookbackMin: Int = SettingsRepository.DEFAULT_LOOKBACK_MIN,
    val history: List<LogEntry> = emptyList(),
    val motionHistory: List<MotionSample> = emptyList(),
    val graphWindowEndMs: Long = System.currentTimeMillis()
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val settings = SettingsRepository(application)
    private val graphWindowEndMs = MutableStateFlow(System.currentTimeMillis())

    private companion object {
        const val GRAPH_INTERPOLATION_CONTEXT_MS = 30 * 60_000L
        const val GRAPH_MIN_VISIBLE_LOOKBACK_MS = 30 * 60_000L
    }

    private val latestEntryFlow: Flow<LogEntry?> = db.logDao().observeLatest()

    private val todayEntriesFlow: Flow<List<LogEntry>> =
        db.logDao().getEntriesSince(com.example.gpspressurelogger.util.GpsUtil.getLoggingStart(System.currentTimeMillis()))

    private data class GraphWindowData(
        val windowEndMs: Long,
        val entries: List<LogEntry>,
        val motionSamples: List<MotionSample>
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val graphWindowFlow: Flow<GraphWindowData> =
        combine(settings.lookbackMin, graphWindowEndMs) { lookbackMin, windowEnd ->
            lookbackMin to windowEnd
        }.flatMapLatest { (lookbackMin, windowEnd) ->
            val lookbackMs = lookbackMin * 60_000L
            val maxVisibleLookbackMs = lookbackMs * GraphUtil.MAX_ZOOM_OUT_FACTOR
            val windowStart = (windowEnd - maxVisibleLookbackMs).coerceAtLeast(0L)
            val fromTs = minOf(
                GpsUtil.getLoggingStart(windowStart),
                (windowStart - GRAPH_INTERPOLATION_CONTEXT_MS).coerceAtLeast(0L)
            ).coerceAtLeast(0L)
            combine(
                db.logDao().getEntriesBetween(fromTs, windowEnd),
                db.motionSampleDao().getBetween(fromTs, windowEnd)
            ) { entries, motionSamples ->
                GraphWindowData(
                    windowEndMs = windowEnd,
                    entries = entries,
                    motionSamples = motionSamples.sortedBy { it.timestamp }
                )
            }
        }

    val uiState: StateFlow<HomeUiState> = combine(
        settings.lookbackMin,
        graphWindowFlow,
        latestEntryFlow,
        todayEntriesFlow
    ) { lookbackMin, graphWindow, latest, todayEntries ->
        val history = graphWindow.entries
        val latestMetrics = GraphUtil.resolveLatestMetricValues(history.sortedBy { it.timestamp }, latest)
        HomeUiState(
            altitudeM = latestMetrics.altitude ?: 0.0,
            pressureRaw = latestMetrics.pressureRaw ?: 0f,
            pressureQnh = latestMetrics.pressureQnh ?: 0f,
            stepsToday = GraphUtil.calculateTodaySteps(todayEntries),
            lookbackMin = lookbackMin,
            history = history,
            motionHistory = graphWindow.motionSamples,
            graphWindowEndMs = graphWindow.windowEndMs
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun shiftGraphWindowBy(deltaMs: Long, visibleLookbackMs: Long) {
        viewModelScope.launch {
            val oldest = db.logDao().getOldestTimestamp() ?: return@launch
            val latest = db.logDao().getLatest()?.timestamp ?: return@launch
            val configuredLookbackMs = settings.lookbackMin.first() * 60_000L
            val maxVisibleLookbackMs = configuredLookbackMs * GraphUtil.MAX_ZOOM_OUT_FACTOR
            val effectiveLookbackMs = visibleLookbackMs
                .coerceIn(GRAPH_MIN_VISIBLE_LOOKBACK_MS, maxVisibleLookbackMs)
            val nominalMinWindowEnd = (oldest + effectiveLookbackMs).coerceAtLeast(oldest)
            val maxWindowEnd = latest
            val effectiveMinWindowEnd = minOf(nominalMinWindowEnd, maxWindowEnd)
            graphWindowEndMs.value =
                (graphWindowEndMs.value + deltaMs).coerceIn(effectiveMinWindowEnd, maxWindowEnd)
        }
    }

    fun resetGraphWindowToLatest() {
        viewModelScope.launch {
            graphWindowEndMs.value = db.logDao().getLatest()?.timestamp ?: System.currentTimeMillis()
        }
    }
}
