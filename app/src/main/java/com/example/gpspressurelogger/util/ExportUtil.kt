package com.example.gpspressurelogger.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPInputStream

/**
 * 高度なデータ統合ユーティリティ (v9: 最終・完全解析版)
 */
object ExportUtil {
    private val debugScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // verboseDebugLog の有効フラグをキャッシュ（DataStore を毎回読まない）
    @Volatile private var verboseLogEnabled = false
    @Volatile private var verboseSettingsInitialized = false

    private fun ensureVerboseSettingsInit(context: Context) {
        if (verboseSettingsInitialized) return
        synchronized(this) {
            if (verboseSettingsInitialized) return
            val settings = SettingsRepository(context.applicationContext)
            debugScope.launch {
                settings.verboseDebugLogEnabled.collect { enabled ->
                    verboseLogEnabled = enabled
                }
            }
            verboseSettingsInitialized = true
        }
    }

    private const val ROOT_DIR = "GpsPressureLogger"
    private const val LOGS_DIR = "logs"
    private const val METRICS_DIR = "metrics"
    private const val DEBUG_DIR = "debug"
    private const val BATCH_SIZE = 1000
    private const val STANDARD_BACKUP_PREFIX = "gps_pressure_full_backup"
    private const val MOTION_BACKUP_PREFIX = "gps_pressure_motion_metrics"
    private const val LEGACY_STANDARD_CSV_HEADER = "Timestamp,Lat,Lon,Alt,PresRaw,PresQnh,StepsDelta"
    private const val STANDARD_CSV_HEADER = "Timestamp,Lat,Lon,Alt,PresRaw,PresQnh,StepsDelta,GpsAccuracy"
    private const val LEGACY_MOTION_SAMPLE_CSV_HEADER = "Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s"
    private const val PREVIOUS_MOTION_SAMPLE_CSV_HEADER =
        "Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s,KStatus,KRawStatus,KAvg,KVariance,KConfidence,WStatus,StepDeltaWindow,GpsIntervalMs,GpsImmediate,ConfirmedMode,ConstantRegionKind,ConstantRegionSpeedKmh"
    private const val PREVIOUS_REGION_MOTION_SAMPLE_CSV_HEADER =
        "Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s,KStatus,KRawStatus,KAvg,KVariance,KConfidence,WStatus,StepDeltaWindow,GpsIntervalMs,GpsImmediate,ConfirmedMode,ConstantRegionKind,ConstantRegionSpeedKmh,ConstantRegionStartLat,ConstantRegionStartLon,ConstantRegionEndLat,ConstantRegionEndLon,ConstantRegionStayLat,ConstantRegionStayLon,ConstantRegionDirectionDeg"
    private const val PREVIOUS_ACCEL_SOURCE_MOTION_SAMPLE_CSV_HEADER =
        "Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s,KStatus,KRawStatus,KAvg,KVariance,KConfidence,WStatus,StepDeltaWindow,GpsIntervalMs,GpsImmediate,ConfirmedMode,ConstantRegionKind,ConstantRegionSpeedKmh,ConstantRegionStartLat,ConstantRegionStartLon,ConstantRegionEndLat,ConstantRegionEndLon,ConstantRegionStayLat,ConstantRegionStayLon,ConstantRegionDirectionDeg,KAccelSource"
    private const val PREVIOUS_DETAILED_MOTION_SAMPLE_CSV_HEADER =
        "Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s,KStatus,KRawStatus,KAvg,KScalarAvg,KDirectionalityRatio,KVariance,KMaxMagnitude,KConfidence,KAccelSource,TrKStatus,TrKRawStatus,TrKAvg,TrKScalarAvg,TrKDirectionalityRatio,TrKMaxMagnitude,TrKHorizontalMaxMagnitude,TrKConfidence,TrKAccelSource,WStatus,StepDeltaWindow,GpsIntervalMs,GpsImmediate,ConfirmedMode,ConstantRegionKind,ConstantRegionSpeedKmh,ConstantRegionStartLat,ConstantRegionStartLon,ConstantRegionEndLat,ConstantRegionEndLon,ConstantRegionStayLat,ConstantRegionStayLon,ConstantRegionDirectionDeg"
    private const val MOTION_SAMPLE_CSV_HEADER =
        "Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s,KStatus,KRawStatus,KAvg,KScalarAvg,KDirectionalityRatio,KVariance,TrKStatus,TrKRawStatus,TrKAvg,TrKDirectionalityRatio,WStatus,StepDeltaWindow,GpsIntervalMs,GpsImmediate,ConfirmedMode,ConstantRegionKind,ConstantRegionSpeedKmh,ConstantRegionStartLat,ConstantRegionStartLon,ConstantRegionEndLat,ConstantRegionEndLon,ConstantRegionStayLat,ConstantRegionStayLon,ConstantRegionDirectionDeg"
    private const val DAILY_LOG_PREFIX = "gps_log"
    private const val DAILY_MOTION_LOG_PREFIX = "motion_metrics"
    private const val DEBUG_LOG_FILE_NAME = "debug_log.txt"

    private val pendingEntryQueue = ArrayDeque<LogEntry>()
    private val pendingMotionQueue = ArrayDeque<MotionSample>()
    private val csvQueueLock = Any()

    data class ImportIssue(
        val fileName: String,
        val lineNumber: Int? = null,
        val message: String
    )

    data class ImportReport(
        val importedCount: Int,
        val processedFiles: Int,
        val skippedFiles: List<ImportIssue>,
        val parseErrors: List<ImportIssue>
    ) {
        val hasIssues: Boolean
            get() = skippedFiles.isNotEmpty() || parseErrors.isNotEmpty()
    }

    private data class StepRepairRecord(
        val timestamp: Long,
        val stepsDelta: Int?,
        val legacyStepCount: Int?
    )

    private data class CsvEventComment(
        val timestamp: Long,
        val line: String
    )

    private fun csvValue(value: Any?): String = value?.toString() ?: ""

    private fun logEntryCsvRow(entry: LogEntry): String =
        listOf(
            entry.timestamp,
            csvValue(entry.latitude),
            csvValue(entry.longitude),
            csvValue(entry.altitudeGps),
            csvValue(entry.pressureRaw),
            csvValue(entry.pressureQnh),
            csvValue(entry.stepsDelta),
            csvValue(entry.gpsAccuracy)
        ).joinToString(",")

    private fun motionSampleCsvRow(sample: MotionSample): String =
        listOf(
            sample.timestamp,
            csvValue(sample.accelStddev3s),
            csvValue(sample.accelMad3s),
            csvValue(sample.stepDelta3s),
            csvValue(sample.stepRate3s),
            csvValue(sample.kStatus),
            csvValue(sample.kRawStatus),
            csvValue(sample.kAvg),
            csvValue(sample.kScalarAvg),
            csvValue(sample.kDirectionalityRatio),
            csvValue(sample.kVariance),
            csvValue(sample.trKStatus),
            csvValue(sample.trKRawStatus),
            csvValue(sample.trKAvg),
            csvValue(sample.trKDirectionalityRatio),
            csvValue(sample.wStatus),
            csvValue(sample.stepDeltaWindow),
            csvValue(sample.gpsIntervalMs),
            sample.gpsImmediate?.let { if (it) "1" else "0" } ?: "",
            csvValue(sample.confirmedMode),
            csvValue(sample.constantRegionKind),
            csvValue(sample.constantRegionSpeedKmh),
            csvValue(sample.constantRegionStartLat),
            csvValue(sample.constantRegionStartLon),
            csvValue(sample.constantRegionEndLat),
            csvValue(sample.constantRegionEndLon),
            csvValue(sample.constantRegionStayLat),
            csvValue(sample.constantRegionStayLon),
            csvValue(sample.constantRegionDirectionDeg)
        ).joinToString(",")

    private fun isCommentOrBlankCsvLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isEmpty() || trimmed.startsWith("#")
    }

    private fun readNextCsvContentLine(
        reader: BufferedReader,
        currentLineNumber: Int
    ): Pair<Int, String>? {
        var lineNumber = currentLineNumber
        while (true) {
            val line = reader.readLine() ?: return null
            lineNumber += 1
            if (isCommentOrBlankCsvLine(line)) {
                continue
            }
            return lineNumber to line
        }
    }

    private fun writeCsvComment(writer: Writer, comment: String) {
        writer.write("# $comment\n")
    }

    private fun writeCsvEventComment(writer: Writer, timestamp: Long, message: String) {
        val singleLine = message.replace("\r", " ").replace("\n", " ")
        writer.write("# EVENT $timestamp $singleLine\n")
    }

    fun logUnhandledException(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = System.currentTimeMillis()
        val summary = buildUnhandledExceptionSummary(thread, throwable)
        try {
            flushPendingCsvQueues(context)
        } catch (_: Exception) {
        }
        try {
            writeLocalDebugLog(context, summary)
            throwable.stackTrace
                .take(12)
                .forEachIndexed { index, element ->
                    writeLocalDebugLog(
                        context,
                        "UNCAUGHT_EXCEPTION_TRACE[$index]: ${element.className}.${element.methodName}:${element.lineNumber}"
                    )
                }
            throwable.cause?.let { cause ->
                writeLocalDebugLog(
                    context,
                    "UNCAUGHT_EXCEPTION_CAUSE: ${cause.javaClass.name}: ${cause.message ?: "(no message)"}"
                )
            }
        } catch (_: Exception) {
        }
        try {
            appendEventCommentToDailyCsv(context, timestamp, summary)
        } catch (_: Exception) {
        }
    }

    private fun buildUnhandledExceptionSummary(thread: Thread, throwable: Throwable): String {
        val message = throwable.message?.replace("\r", " ")?.replace("\n", " ") ?: "(no message)"
        return "UNCAUGHT_EXCEPTION: thread=${thread.name} type=${throwable.javaClass.name} message=$message"
    }

    private fun appendEventCommentToDailyCsv(context: Context, timestamp: Long, message: String) {
        try {
            val file = buildDailyLogFile(context, timestamp)
            val isNew = !file.exists()
            FileOutputStream(file, true).use { out ->
                OutputStreamWriter(out).use { writer ->
                    if (isNew) {
                        writeCsvComment(writer, "GpsPressureLogger daily log")
                        writer.write("$STANDARD_CSV_HEADER\n")
                    }
                    writeCsvEventComment(writer, timestamp, message)
                    writer.flush()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun parseCsvEventComment(line: String): CsvEventComment? {
        val match = Regex("^#\\s*EVENT\\s+(\\d+)\\s+(.*)$").matchEntire(line.trim()) ?: return null
        val timestamp = match.groupValues[1].toLongOrNull() ?: return null
        return CsvEventComment(timestamp, line.trim())
    }

    private fun collectDailyCsvEventComments(context: Context): List<CsvEventComment> {
        val logDir = getStorageDir(context, LOGS_DIR)
        val comments = mutableListOf<CsvEventComment>()
        logDir.listFiles { file -> file.isFile && file.name.startsWith("${DAILY_LOG_PREFIX}_") && file.extension.equals("csv", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                try {
                    file.forEachLine { line ->
                        parseCsvEventComment(line)?.let { comments += it }
                    }
                } catch (_: Exception) {
                }
            }
        return comments.sortedBy { it.timestamp }
    }

    private fun getAppStorageRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, ROOT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getStorageDir(context: Context, childDirName: String): File {
        val dir = File(getAppStorageRoot(context), childDirName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun buildDailyLogFile(context: Context, timestamp: Long): File {
        val loggingStart = GpsUtil.getLoggingStart(timestamp)
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(Date(loggingStart))
        return File(getStorageDir(context, LOGS_DIR), "${DAILY_LOG_PREFIX}_$dateStr.csv")
    }

    private fun buildDailyMotionLogFile(context: Context, timestamp: Long): File {
        val loggingStart = GpsUtil.getLoggingStart(timestamp)
        val dateStr = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(Date(loggingStart))
        return File(getStorageDir(context, METRICS_DIR), "${DAILY_MOTION_LOG_PREFIX}_$dateStr.csv")
    }

    private fun buildDebugLogFile(context: Context): File =
        File(getStorageDir(context, DEBUG_DIR), DEBUG_LOG_FILE_NAME)

    fun enqueueEntryToLocalCsv(context: Context, entry: LogEntry) {
        synchronized(csvQueueLock) {
            flushEntryQueueForDayBoundaryLocked(context, entry.timestamp)
            pendingEntryQueue.addLast(entry)
            if (pendingEntryQueue.size >= LoggingConfig.CSV_FLUSH_QUEUE_SIZE) {
                flushEntryQueueLocked(context)
            } else {
                trimEntryQueueLocked(context)
            }
        }
    }

    fun enqueueMotionSampleToLocalCsv(context: Context, sample: MotionSample) {
        synchronized(csvQueueLock) {
            flushMotionQueueForDayBoundaryLocked(context, sample.timestamp)
            pendingMotionQueue.addLast(sample)
            if (pendingMotionQueue.size >= LoggingConfig.CSV_FLUSH_QUEUE_SIZE) {
                flushMotionQueueLocked(context)
            } else {
                trimMotionQueueLocked(context)
            }
        }
    }

    fun flushPendingCsvQueues(context: Context) {
        synchronized(csvQueueLock) {
            val entryCount = pendingEntryQueue.size
            val motionCount = pendingMotionQueue.size
            flushEntryQueueLocked(context)
            flushMotionQueueLocked(context)
            writeLocalDebugLog(context, "CSV_QUEUE_FLUSH: entries=$entryCount motion=$motionCount")
        }
    }

    private fun flushEntryQueueForDayBoundaryLocked(context: Context, timestamp: Long) {
        val first = pendingEntryQueue.firstOrNull() ?: return
        if (GpsUtil.getLoggingStart(first.timestamp) != GpsUtil.getLoggingStart(timestamp)) {
            flushEntryQueueLocked(context)
        }
    }

    private fun flushMotionQueueForDayBoundaryLocked(context: Context, timestamp: Long) {
        val first = pendingMotionQueue.firstOrNull() ?: return
        if (GpsUtil.getLoggingStart(first.timestamp) != GpsUtil.getLoggingStart(timestamp)) {
            flushMotionQueueLocked(context)
        }
    }

    private fun flushEntryQueueLocked(context: Context) {
        var flushedCount = 0
        while (pendingEntryQueue.isNotEmpty()) {
            val dayStart = GpsUtil.getLoggingStart(pendingEntryQueue.first().timestamp)
            val batch = mutableListOf<LogEntry>()
            for (entry in pendingEntryQueue) {
                if (GpsUtil.getLoggingStart(entry.timestamp) != dayStart) break
                batch += entry
            }
            if (!appendEntriesToFile(buildDailyLogFile(context, dayStart), batch)) {
                trimEntryQueueLocked(context)
                return
            }
            repeat(batch.size) { if (pendingEntryQueue.isNotEmpty()) pendingEntryQueue.removeFirst() }
            flushedCount += batch.size
        }
        if (flushedCount > 0) {
            writeLocalDebugLog(context, "CSV_ENTRY_FLUSHED: count=$flushedCount")
        }
    }

    private fun flushMotionQueueLocked(context: Context) {
        var flushedCount = 0
        while (pendingMotionQueue.isNotEmpty()) {
            val dayStart = GpsUtil.getLoggingStart(pendingMotionQueue.first().timestamp)
            val batch = mutableListOf<MotionSample>()
            for (sample in pendingMotionQueue) {
                if (GpsUtil.getLoggingStart(sample.timestamp) != dayStart) break
                batch += sample
            }
            if (!appendMotionSamplesToFile(buildDailyMotionLogFile(context, dayStart), batch)) {
                trimMotionQueueLocked(context)
                return
            }
            repeat(batch.size) { if (pendingMotionQueue.isNotEmpty()) pendingMotionQueue.removeFirst() }
            flushedCount += batch.size
        }
        if (flushedCount > 0) {
            writeLocalDebugLog(context, "CSV_MOTION_FLUSHED: count=$flushedCount")
        }
    }

    private fun trimEntryQueueLocked(context: Context) {
        var dropped = 0
        while (pendingEntryQueue.size > LoggingConfig.CSV_MAX_QUEUE_SIZE) {
            pendingEntryQueue.removeFirst()
            dropped += 1
        }
        if (dropped > 0) {
            writeLocalDebugLog(context, "CSV_ENTRY_QUEUE_TRIMMED: dropped=$dropped remaining=${pendingEntryQueue.size}")
        }
    }

    private fun trimMotionQueueLocked(context: Context) {
        var dropped = 0
        while (pendingMotionQueue.size > LoggingConfig.CSV_MAX_QUEUE_SIZE) {
            pendingMotionQueue.removeFirst()
            dropped += 1
        }
        if (dropped > 0) {
            writeLocalDebugLog(context, "CSV_MOTION_QUEUE_TRIMMED: dropped=$dropped remaining=${pendingMotionQueue.size}")
        }
    }

    private fun appendEntriesToFile(file: File, entries: List<LogEntry>): Boolean {
        if (entries.isEmpty()) return true
        return try {
            val isNew = !file.exists()
            if (!isNew) {
                ensureStandardCsvHeader(file)
            }
            FileOutputStream(file, true).use { out ->
                OutputStreamWriter(out).use { writer ->
                    if (isNew) {
                        writeCsvComment(writer, "GpsPressureLogger daily log")
                        writer.write("$STANDARD_CSV_HEADER\n")
                    }
                    entries.forEach { entry ->
                        writer.write(
                            logEntryCsvRow(entry) + "\n"
                        )
                    }
                    writer.flush()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureStandardCsvHeader(file: File) {
        val lines = file.readLines()
        val headerIndex = lines.indexOfFirst { !isCommentOrBlankCsvLine(it) }
        if (headerIndex < 0) return
        val header = lines[headerIndex].split(",").map { cleanHeader(it) }.joinToString(",")
        if (header != LEGACY_STANDARD_CSV_HEADER) return
        val updated = lines.toMutableList()
        updated[headerIndex] = STANDARD_CSV_HEADER
        file.writeText(updated.joinToString("\n") + "\n")
    }

    private fun appendMotionSamplesToFile(file: File, samples: List<MotionSample>): Boolean {
        if (samples.isEmpty()) return true
        return try {
            val isNew = !file.exists()
            if (!isNew) {
                ensureMotionSampleHeader(file)
            }
            FileOutputStream(file, true).use { out ->
                OutputStreamWriter(out).use { writer ->
                    if (isNew) {
                        writeCsvComment(writer, "GpsPressureLogger daily motion metrics")
                        writer.write("$MOTION_SAMPLE_CSV_HEADER\n")
                    }
                    samples.forEach { sample ->
                        writer.write(
                            motionSampleCsvRow(sample) + "\n"
                        )
                    }
                    writer.flush()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun rewriteMotionSamplesInFile(file: File, samples: List<MotionSample>) {
        if (!file.exists() || samples.isEmpty()) return
        ensureMotionSampleHeader(file)

        val replacements = samples.associateBy { it.timestamp }
        val written = mutableSetOf<Long>()
        val lines = file.readLines()
        val headerIndex = lines.indexOfFirst { !isCommentOrBlankCsvLine(it) }
        if (headerIndex < 0) return

        val rewritten = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            if (index <= headerIndex || isCommentOrBlankCsvLine(line)) {
                rewritten += line
                return@forEachIndexed
            }

            val timestamp = line.substringBefore(",").toLongOrNull()
            val replacement = timestamp?.let { replacements[it] }
            if (timestamp != null && replacement != null) {
                if (written.add(timestamp)) {
                    rewritten += motionSampleCsvRow(replacement)
                }
            } else {
                rewritten += line
            }
        }

        samples.sortedBy { it.timestamp }.forEach { sample ->
            if (written.add(sample.timestamp)) {
                rewritten += motionSampleCsvRow(sample)
            }
        }
        file.writeText(rewritten.joinToString("\n") + "\n")
    }

    private fun ensureMotionSampleHeader(file: File) {
        val lines = file.readLines()
        val headerIndex = lines.indexOfFirst { !isCommentOrBlankCsvLine(it) }
        if (headerIndex < 0) return
        val header = lines[headerIndex].split(",").map { cleanHeader(it) }.joinToString(",")
        if (
            header != LEGACY_MOTION_SAMPLE_CSV_HEADER &&
            header != PREVIOUS_MOTION_SAMPLE_CSV_HEADER &&
            header != PREVIOUS_REGION_MOTION_SAMPLE_CSV_HEADER &&
            header != PREVIOUS_ACCEL_SOURCE_MOTION_SAMPLE_CSV_HEADER &&
            header != PREVIOUS_DETAILED_MOTION_SAMPLE_CSV_HEADER
        ) return
        val updated = lines.toMutableList()
        updated[headerIndex] = MOTION_SAMPLE_CSV_HEADER
        file.writeText(updated.joinToString("\n") + "\n")
    }

    fun appendEntryToLocalCsv(context: Context, entry: LogEntry) {
        enqueueEntryToLocalCsv(context, entry)
    }

    fun writeLocalDebugLog(context: Context, message: String) {
        try {
            val file = buildDebugLogFile(context)
            FileOutputStream(file, true).use { out ->
                OutputStreamWriter(out).use { writer ->
                    val timeStr = SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(Date())
                    writer.write("[$timeStr] $message\n")
                    writer.flush()
                }
            }
        } catch (e: Exception) {}
    }

    fun writeEntriesToUri(context: Context, fileUri: Uri, entries: List<LogEntry>): Boolean {
        val sortedEntries = entries.sortedBy { it.timestamp }
        return try {
            flushPendingCsvQueues(context)
            val outputStream = context.contentResolver.openOutputStream(fileUri)
            if (outputStream == null) {
                writeLocalDebugLog(
                    context,
                    "EXPORT_STANDARD_FAILED uri=$fileUri reason=openOutputStreamReturnedNull rows=${sortedEntries.size}"
                )
                deleteUriQuietly(context, fileUri)
                return false
            }
            outputStream.use { out ->
                BufferedWriter(OutputStreamWriter(out)).use { writer ->
                    writeCsvComment(writer, "GpsPressureLogger standard backup")
                    writer.write("$STANDARD_CSV_HEADER\n")
                    val comments = collectDailyCsvEventComments(context)
                    var commentIndex = 0
                    sortedEntries.forEach { e ->
                        while (commentIndex < comments.size && comments[commentIndex].timestamp <= e.timestamp) {
                            writer.write(comments[commentIndex].line + "\n")
                            commentIndex += 1
                        }
                        writer.write(
                            logEntryCsvRow(e) + "\n"
                        )
                    }
                    while (commentIndex < comments.size) {
                        writer.write(comments[commentIndex].line + "\n")
                        commentIndex += 1
                    }
                    writer.flush()
                }
            }
            val exportedSize = exportedDocumentSize(context, fileUri)
            if (exportedSize <= 0L) {
                writeLocalDebugLog(
                    context,
                    "EXPORT_STANDARD_FAILED uri=$fileUri reason=emptyDocument rows=${sortedEntries.size} size=$exportedSize"
                )
                deleteUriQuietly(context, fileUri)
                return false
            }
            writeLocalDebugLog(
                context,
                "EXPORT_STANDARD_OK uri=$fileUri rows=${sortedEntries.size} size=$exportedSize"
            )
            true
        } catch (e: Exception) {
            writeLocalDebugLog(
                context,
                "EXPORT_STANDARD_FAILED uri=$fileUri reason=${e.javaClass.simpleName}:${e.message ?: "unknown"} rows=${sortedEntries.size}"
            )
            deleteUriQuietly(context, fileUri)
            false
        }
    }

    fun writeMotionSamplesToUri(context: Context, fileUri: Uri, samples: List<MotionSample>): Boolean {
        val sortedSamples = samples.sortedBy { it.timestamp }
        return try {
            flushPendingCsvQueues(context)
            val outputStream = context.contentResolver.openOutputStream(fileUri)
            if (outputStream == null) {
                writeLocalDebugLog(
                    context,
                    "EXPORT_MOTION_FAILED uri=$fileUri reason=openOutputStreamReturnedNull rows=${sortedSamples.size}"
                )
                deleteUriQuietly(context, fileUri)
                return false
            }
            outputStream.use { out ->
                BufferedWriter(OutputStreamWriter(out)).use { writer ->
                    writeCsvComment(writer, "GpsPressureLogger motion metrics backup")
                    writer.write("$MOTION_SAMPLE_CSV_HEADER\n")
                    sortedSamples.forEach { sample ->
                        writer.write(
                            motionSampleCsvRow(sample) + "\n"
                        )
                    }
                    writer.flush()
                }
            }
            val exportedSize = exportedDocumentSize(context, fileUri)
            if (exportedSize <= 0L) {
                writeLocalDebugLog(
                    context,
                    "EXPORT_MOTION_FAILED uri=$fileUri reason=emptyDocument rows=${sortedSamples.size} size=$exportedSize"
                )
                deleteUriQuietly(context, fileUri)
                return false
            }
            writeLocalDebugLog(
                context,
                "EXPORT_MOTION_OK uri=$fileUri rows=${sortedSamples.size} size=$exportedSize"
            )
            true
        } catch (e: Exception) {
            writeLocalDebugLog(
                context,
                "EXPORT_MOTION_FAILED uri=$fileUri reason=${e.javaClass.simpleName}:${e.message ?: "unknown"} rows=${sortedSamples.size}"
            )
            deleteUriQuietly(context, fileUri)
            false
        }
    }

    private fun deleteUriQuietly(context: Context, fileUri: Uri) {
        try {
            DocumentFile.fromSingleUri(context, fileUri)?.delete()
        } catch (_: Exception) {
        }
    }

    private fun exportedDocumentSize(context: Context, fileUri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(fileUri, "r")?.use { descriptor ->
                val statSize = descriptor.statSize
                if (statSize >= 0L) return statSize
            }
            DocumentFile.fromSingleUri(context, fileUri)?.length() ?: 0L
        } catch (_: Exception) {
            DocumentFile.fromSingleUri(context, fileUri)?.length() ?: 0L
        }
    }

    private fun flexibleParseDate(dateStr: String): Long {
        return try {
            val match = Regex("(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})\\s+(\\d{1,2}):(\\d{1,2}):(\\d{1,2})").find(dateStr)
            if (match != null) {
                val (y, m, d, hh, mm, ss) = match.destructured
                val cal = Calendar.getInstance()
                cal.set(y.toInt(), m.toInt() - 1, d.toInt(), hh.toInt(), mm.toInt(), ss.toInt())
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            } else 0L
        } catch (e: Exception) { 0L }
    }

    suspend fun importFromUriWithProgress(
        context: Context,
        fileUri: Uri,
        overwrite: Boolean,
        onProgress: (fileName: String, count: Int) -> Unit
    ): ImportReport {
        try {
            val db = AppDatabase.getInstance(context)
            val doc = DocumentFile.fromSingleUri(context, fileUri)
            val name = doc?.name ?: "selected.csv"
            if (!shouldImportFile(name)) {
                return ImportReport(
                    importedCount = 0,
                    processedFiles = 0,
                    skippedFiles = listOf(ImportIssue(name, message = "標準バックアップ CSV 名ではないためスキップ")),
                    parseErrors = emptyList()
                )
            }
            val importResult = streamImportStandardCsv(context, db, fileUri, name, overwrite) { c ->
                onProgress(name, c)
            }
            if (importResult.importedCount > 0) {
                writeDebugLog(context, "IMPORT_SUCCESS: $name (${importResult.importedCount} records)")
            }
            return importResult.copy(processedFiles = 1)
        } catch (e: Throwable) {
            writeDebugLog(context, "IMPORT_FATAL: ${e.message}")
            return ImportReport(
                importedCount = 0,
                processedFiles = 0,
                skippedFiles = listOf(ImportIssue("(file)", message = "ファイル読込エラー: ${e.message}")),
                parseErrors = emptyList()
            )
        }
    }

    suspend fun importMotionSamplesFromUriWithProgress(
        context: Context,
        fileUri: Uri,
        overwrite: Boolean,
        onProgress: (fileName: String, count: Int) -> Unit
    ): ImportReport {
        try {
            val db = AppDatabase.getInstance(context)
            val doc = DocumentFile.fromSingleUri(context, fileUri)
            val name = doc?.name ?: "selected_motion_metrics.csv"
            if (!shouldImportMotionFile(name)) {
                return ImportReport(
                    importedCount = 0,
                    processedFiles = 0,
                    skippedFiles = listOf(ImportIssue(name, message = "補助センサー判定ログ CSV 名ではないためスキップ")),
                    parseErrors = emptyList()
                )
            }
            val importResult = streamImportMotionCsv(context, db, fileUri, name, overwrite) { c ->
                onProgress(name, c)
            }
            if (importResult.importedCount > 0) {
                writeDebugLog(context, "MOTION_IMPORT_SUCCESS: $name (${importResult.importedCount} records)")
            }
            return importResult.copy(processedFiles = 1)
        } catch (e: Throwable) {
            writeDebugLog(context, "MOTION_IMPORT_FATAL: ${e.message}")
            return ImportReport(
                importedCount = 0,
                processedFiles = 0,
                skippedFiles = listOf(ImportIssue("(file)", message = "ファイル読込エラー: ${e.message}")),
                parseErrors = emptyList()
            )
        }
    }

    private fun shouldImportFile(name: String): Boolean {
        if (!name.endsWith(".csv", ignoreCase = true)) return false
        return name.startsWith(STANDARD_BACKUP_PREFIX, ignoreCase = true)
    }

    private fun shouldImportMotionFile(name: String): Boolean {
        if (!name.endsWith(".csv", ignoreCase = true)) return false
        return name.startsWith(MOTION_BACKUP_PREFIX, ignoreCase = true) ||
            name.startsWith(DAILY_MOTION_LOG_PREFIX, ignoreCase = true)
    }

    private fun mergeEntries(existing: LogEntry, incoming: LogEntry, overwrite: Boolean): LogEntry {
        fun <T> pick(e: T?, i: T?): T? {
            if (i == null) return e
            if (e == null) return i
            return if (overwrite) i else e
        }
        return existing.copy(
            latitude    = pick(existing.latitude, incoming.latitude),
            longitude   = pick(existing.longitude, incoming.longitude),
            altitudeGps = pick(existing.altitudeGps, incoming.altitudeGps),
            pressureRaw = pick(existing.pressureRaw, incoming.pressureRaw),
            pressureQnh = pick(existing.pressureQnh, incoming.pressureQnh),
            gpsAccuracy = pick(existing.gpsAccuracy, incoming.gpsAccuracy),
            stepsDelta  = pick(existing.stepsDelta, incoming.stepsDelta)
        )
    }

    private fun mergeMotionSamples(existing: MotionSample, incoming: MotionSample, overwrite: Boolean): MotionSample {
        fun <T> pick(e: T?, i: T?): T? {
            if (i == null) return e
            if (e == null) return i
            return if (overwrite) i else e
        }
        return existing.copy(
            accelStddev3s = pick(existing.accelStddev3s, incoming.accelStddev3s),
            accelMad3s = pick(existing.accelMad3s, incoming.accelMad3s),
            stepDelta3s = pick(existing.stepDelta3s, incoming.stepDelta3s),
            stepRate3s = pick(existing.stepRate3s, incoming.stepRate3s),
            kStatus = pick(existing.kStatus, incoming.kStatus),
            kRawStatus = pick(existing.kRawStatus, incoming.kRawStatus),
            kAvg = pick(existing.kAvg, incoming.kAvg),
            kScalarAvg = pick(existing.kScalarAvg, incoming.kScalarAvg),
            kDirectionalityRatio = pick(existing.kDirectionalityRatio, incoming.kDirectionalityRatio),
            kVariance = pick(existing.kVariance, incoming.kVariance),
            kMaxMagnitude = pick(existing.kMaxMagnitude, incoming.kMaxMagnitude),
            kConfidence = pick(existing.kConfidence, incoming.kConfidence),
            kAccelSource = pick(existing.kAccelSource, incoming.kAccelSource),
            trKStatus = pick(existing.trKStatus, incoming.trKStatus),
            trKRawStatus = pick(existing.trKRawStatus, incoming.trKRawStatus),
            trKAvg = pick(existing.trKAvg, incoming.trKAvg),
            trKScalarAvg = pick(existing.trKScalarAvg, incoming.trKScalarAvg),
            trKDirectionalityRatio = pick(existing.trKDirectionalityRatio, incoming.trKDirectionalityRatio),
            trKMaxMagnitude = pick(existing.trKMaxMagnitude, incoming.trKMaxMagnitude),
            trKHorizontalMaxMagnitude = pick(existing.trKHorizontalMaxMagnitude, incoming.trKHorizontalMaxMagnitude),
            trKConfidence = pick(existing.trKConfidence, incoming.trKConfidence),
            trKAccelSource = pick(existing.trKAccelSource, incoming.trKAccelSource),
            wStatus = pick(existing.wStatus, incoming.wStatus),
            stepDeltaWindow = pick(existing.stepDeltaWindow, incoming.stepDeltaWindow),
            gpsIntervalMs = pick(existing.gpsIntervalMs, incoming.gpsIntervalMs),
            gpsImmediate = pick(existing.gpsImmediate, incoming.gpsImmediate),
            confirmedMode = pick(existing.confirmedMode, incoming.confirmedMode),
            constantRegionKind = pick(existing.constantRegionKind, incoming.constantRegionKind),
            constantRegionSpeedKmh = pick(existing.constantRegionSpeedKmh, incoming.constantRegionSpeedKmh),
            constantRegionStartLat = pick(existing.constantRegionStartLat, incoming.constantRegionStartLat),
            constantRegionStartLon = pick(existing.constantRegionStartLon, incoming.constantRegionStartLon),
            constantRegionEndLat = pick(existing.constantRegionEndLat, incoming.constantRegionEndLat),
            constantRegionEndLon = pick(existing.constantRegionEndLon, incoming.constantRegionEndLon),
            constantRegionStayLat = pick(existing.constantRegionStayLat, incoming.constantRegionStayLat),
            constantRegionStayLon = pick(existing.constantRegionStayLon, incoming.constantRegionStayLon),
            constantRegionDirectionDeg = pick(existing.constantRegionDirectionDeg, incoming.constantRegionDirectionDeg)
        )
    }

    private suspend fun flushBatch(db: AppDatabase, batch: MutableList<LogEntry>, overwrite: Boolean) {
        if (batch.isEmpty()) return
        val toSave = mutableListOf<LogEntry>()
        batch.forEach { incoming ->
            val existing = db.logDao().findByTimestamp(incoming.timestamp)
            toSave.add(if (existing == null) incoming else mergeEntries(existing, incoming, overwrite))
        }
        db.logDao().insertAllReplace(toSave)
        batch.clear()
    }

    private suspend fun flushMotionBatch(db: AppDatabase, batch: MutableList<MotionSample>, overwrite: Boolean) {
        if (batch.isEmpty()) return
        val toSave = mutableListOf<MotionSample>()
        batch.forEach { incoming ->
            val existing = db.motionSampleDao().findByTimestamp(incoming.timestamp)
            toSave.add(if (existing == null) incoming else mergeMotionSamples(existing, incoming, overwrite))
        }
        db.motionSampleDao().insertAllReplace(toSave)
        batch.clear()
    }

    private fun cleanHeader(h: String) = h.replace("\uFEFF", "").trim()

    private suspend fun streamImportStandardCsv(
        context: Context,
        db: AppDatabase,
        uri: Uri,
        fileName: String,
        overwrite: Boolean,
        onProgress: (Int) -> Unit
    ): ImportReport {
        var count = 0
        val batch = mutableListOf<LogEntry>()
        val parseErrors = mutableListOf<ImportIssue>()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input))
                var lineNumber = 0
                val headerLine = readNextCsvContentLine(reader, lineNumber)
                    ?: return ImportReport(0, 0, emptyList(), listOf(ImportIssue(fileName, 1, "ヘッダー行が存在しません")))
                lineNumber = headerLine.first
                val rawHeader = headerLine.second
                val header = rawHeader.split(",").map { cleanHeader(it) }
                val iTs = header.indexOf("Timestamp")
                val iLa = header.indexOf("Lat")
                val iLo = header.indexOf("Lon")
                val iAl = header.indexOf("Alt")
                val iPr = header.indexOf("PresRaw")
                val iQn = header.indexOf("PresQnh")
                val iSt = header.indexOf("StepsDelta")
                val iAcc = header.indexOf("GpsAccuracy")
                val normalizedHeader = header.joinToString(",")
                if ((normalizedHeader != STANDARD_CSV_HEADER && normalizedHeader != LEGACY_STANDARD_CSV_HEADER) || iTs < 0) {
                    return ImportReport(0, 0, emptyList(), listOf(ImportIssue(fileName, lineNumber, "標準 CSV ヘッダーではありません")))
                }
                while (true) {
                    val contentLine = readNextCsvContentLine(reader, lineNumber) ?: break
                    lineNumber = contentLine.first
                    val c = contentLine.second.split(",").map { it.trim().removeSurrounding("\"") }
                    try {
                        val ts = c.getOrNull(iTs)?.toLongOrNull() ?: 0L
                        if (ts == 0L) {
                            parseErrors += ImportIssue(fileName, lineNumber, "Timestamp を解釈できません")
                            continue
                        }
                        batch.add(
                            LogEntry(
                                timestamp = ts,
                                latitude = if (iLa >= 0 && iLa < c.size && c[iLa].isNotEmpty()) c[iLa].toDoubleOrNull() else null,
                                longitude = if (iLo >= 0 && iLo < c.size && c[iLo].isNotEmpty()) c[iLo].toDoubleOrNull() else null,
                                altitudeGps = if (iAl >= 0 && iAl < c.size && c[iAl].isNotEmpty()) c[iAl].toDoubleOrNull() else null,
                                pressureRaw = if (iPr >= 0 && iPr < c.size && c[iPr].isNotEmpty()) c[iPr].toFloatOrNull() else null,
                                pressureQnh = if (iQn >= 0 && iQn < c.size && c[iQn].isNotEmpty()) c[iQn].toFloatOrNull() else null,
                                stepsDelta = if (iSt >= 0 && iSt < c.size && c[iSt].isNotEmpty()) c[iSt].toIntOrNull() else null,
                                gpsAccuracy = if (iAcc >= 0 && iAcc < c.size && c[iAcc].isNotEmpty()) c[iAcc].toFloatOrNull() else null
                            )
                        )
                        if (batch.size >= BATCH_SIZE) { flushBatch(db, batch, overwrite); count += BATCH_SIZE; onProgress(count) }
                    } catch (e: Exception) {
                        parseErrors += ImportIssue(fileName, lineNumber, e.message ?: "行の解析に失敗しました")
                    }
                }
            }
            if (batch.isNotEmpty()) { flushBatch(db, batch, overwrite); count += batch.size }
        } catch (e: Throwable) {
            parseErrors += ImportIssue(fileName, message = "ファイル読込失敗: ${e.message}")
        }
        return ImportReport(
            importedCount = count,
            processedFiles = 1,
            skippedFiles = emptyList(),
            parseErrors = parseErrors
        )
    }

    private suspend fun streamImportMotionCsv(
        context: Context,
        db: AppDatabase,
        uri: Uri,
        fileName: String,
        overwrite: Boolean,
        onProgress: (Int) -> Unit
    ): ImportReport {
        var count = 0
        val batch = mutableListOf<MotionSample>()
        val parseErrors = mutableListOf<ImportIssue>()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input))
                var lineNumber = 0
                val headerLine = readNextCsvContentLine(reader, lineNumber)
                    ?: return ImportReport(0, 0, emptyList(), listOf(ImportIssue(fileName, 1, "ヘッダー行が存在しません")))
                lineNumber = headerLine.first
                val rawHeader = headerLine.second
                val header = rawHeader.split(",").map { cleanHeader(it) }
                val headerText = header.joinToString(",")
            val supportedHeader =
                headerText == MOTION_SAMPLE_CSV_HEADER ||
                    headerText == PREVIOUS_DETAILED_MOTION_SAMPLE_CSV_HEADER ||
                    headerText == PREVIOUS_MOTION_SAMPLE_CSV_HEADER ||
                    headerText == PREVIOUS_REGION_MOTION_SAMPLE_CSV_HEADER ||
                    headerText == PREVIOUS_ACCEL_SOURCE_MOTION_SAMPLE_CSV_HEADER ||
                    headerText == LEGACY_MOTION_SAMPLE_CSV_HEADER
                if (!supportedHeader) {
                    return ImportReport(0, 0, emptyList(), listOf(ImportIssue(fileName, lineNumber, "補助センサー判定ログ CSV ヘッダーではありません")))
                }
                fun indexOf(name: String): Int = header.indexOf(name)
                val iTs = indexOf("Timestamp")
                val iStddev = indexOf("AccelStddev3s")
                val iMad = indexOf("AccelMad3s")
                val iStepDelta = indexOf("StepDelta3s")
                val iStepRate = indexOf("StepRate3s")
                val iKStatus = indexOf("KStatus")
                val iKRawStatus = indexOf("KRawStatus")
                val iKAvg = indexOf("KAvg")
                val iKScalarAvg = indexOf("KScalarAvg")
                val iKDirectionalityRatio = indexOf("KDirectionalityRatio")
                val iKVariance = indexOf("KVariance")
                val iKMaxMagnitude = indexOf("KMaxMagnitude")
                val iKConfidence = indexOf("KConfidence")
                val iKAccelSource = indexOf("KAccelSource")
                val iTrKStatus = indexOf("TrKStatus")
                val iTrKRawStatus = indexOf("TrKRawStatus")
                val iTrKAvg = indexOf("TrKAvg")
                val iTrKScalarAvg = indexOf("TrKScalarAvg")
                val iTrKDirectionalityRatio = indexOf("TrKDirectionalityRatio")
                val iTrKMaxMagnitude = indexOf("TrKMaxMagnitude")
                val iTrKHorizontalMaxMagnitude = indexOf("TrKHorizontalMaxMagnitude")
                val iTrKConfidence = indexOf("TrKConfidence")
                val iTrKAccelSource = indexOf("TrKAccelSource")
                val iWStatus = indexOf("WStatus")
                val iStepDeltaWindow = indexOf("StepDeltaWindow")
                val iGpsIntervalMs = indexOf("GpsIntervalMs")
                val iGpsImmediate = indexOf("GpsImmediate")
                val iConfirmedMode = indexOf("ConfirmedMode")
                val iConstantRegionKind = indexOf("ConstantRegionKind")
                val iConstantRegionSpeedKmh = indexOf("ConstantRegionSpeedKmh")
                val iConstantRegionStartLat = indexOf("ConstantRegionStartLat")
                val iConstantRegionStartLon = indexOf("ConstantRegionStartLon")
                val iConstantRegionEndLat = indexOf("ConstantRegionEndLat")
                val iConstantRegionEndLon = indexOf("ConstantRegionEndLon")
                val iConstantRegionStayLat = indexOf("ConstantRegionStayLat")
                val iConstantRegionStayLon = indexOf("ConstantRegionStayLon")
                val iConstantRegionDirectionDeg = indexOf("ConstantRegionDirectionDeg")
                fun List<String>.valueAt(index: Int): String? =
                    if (index >= 0) getOrNull(index)?.takeIf { it.isNotEmpty() } else null
                while (true) {
                    val contentLine = readNextCsvContentLine(reader, lineNumber) ?: break
                    lineNumber = contentLine.first
                    val c = contentLine.second.split(",").map { it.trim().removeSurrounding("\"") }
                    try {
                        val ts = c.valueAt(iTs)?.toLongOrNull() ?: 0L
                        if (ts == 0L) {
                            parseErrors += ImportIssue(fileName, lineNumber, "Timestamp を解釈できません")
                            continue
                        }
                        batch.add(
                            MotionSample(
                                timestamp = ts,
                                accelStddev3s = c.valueAt(iStddev)?.toFloatOrNull(),
                                accelMad3s = c.valueAt(iMad)?.toFloatOrNull(),
                                stepDelta3s = c.valueAt(iStepDelta)?.toIntOrNull(),
                                stepRate3s = c.valueAt(iStepRate)?.toFloatOrNull(),
                                kStatus = c.valueAt(iKStatus),
                                kRawStatus = c.valueAt(iKRawStatus),
                                kAvg = c.valueAt(iKAvg)?.toFloatOrNull(),
                                kScalarAvg = c.valueAt(iKScalarAvg)?.toFloatOrNull(),
                                kDirectionalityRatio = c.valueAt(iKDirectionalityRatio)?.toFloatOrNull(),
                                kVariance = c.valueAt(iKVariance)?.toFloatOrNull(),
                                kMaxMagnitude = c.valueAt(iKMaxMagnitude)?.toFloatOrNull(),
                                kConfidence = c.valueAt(iKConfidence)?.toFloatOrNull(),
                                kAccelSource = c.valueAt(iKAccelSource),
                                trKStatus = c.valueAt(iTrKStatus),
                                trKRawStatus = c.valueAt(iTrKRawStatus),
                                trKAvg = c.valueAt(iTrKAvg)?.toFloatOrNull(),
                                trKScalarAvg = c.valueAt(iTrKScalarAvg)?.toFloatOrNull(),
                                trKDirectionalityRatio = c.valueAt(iTrKDirectionalityRatio)?.toFloatOrNull(),
                                trKMaxMagnitude = c.valueAt(iTrKMaxMagnitude)?.toFloatOrNull(),
                                trKHorizontalMaxMagnitude = c.valueAt(iTrKHorizontalMaxMagnitude)?.toFloatOrNull(),
                                trKConfidence = c.valueAt(iTrKConfidence)?.toFloatOrNull(),
                                trKAccelSource = c.valueAt(iTrKAccelSource),
                                wStatus = c.valueAt(iWStatus),
                                stepDeltaWindow = c.valueAt(iStepDeltaWindow)?.toIntOrNull(),
                                gpsIntervalMs = c.valueAt(iGpsIntervalMs)?.toLongOrNull(),
                                gpsImmediate = c.valueAt(iGpsImmediate)?.let { it == "1" || it.equals("true", ignoreCase = true) },
                                confirmedMode = c.valueAt(iConfirmedMode),
                                constantRegionKind = c.valueAt(iConstantRegionKind),
                                constantRegionSpeedKmh = c.valueAt(iConstantRegionSpeedKmh)?.toDoubleOrNull(),
                                constantRegionStartLat = c.valueAt(iConstantRegionStartLat)?.toDoubleOrNull(),
                                constantRegionStartLon = c.valueAt(iConstantRegionStartLon)?.toDoubleOrNull(),
                                constantRegionEndLat = c.valueAt(iConstantRegionEndLat)?.toDoubleOrNull(),
                                constantRegionEndLon = c.valueAt(iConstantRegionEndLon)?.toDoubleOrNull(),
                                constantRegionStayLat = c.valueAt(iConstantRegionStayLat)?.toDoubleOrNull(),
                                constantRegionStayLon = c.valueAt(iConstantRegionStayLon)?.toDoubleOrNull(),
                                constantRegionDirectionDeg = c.valueAt(iConstantRegionDirectionDeg)?.toDoubleOrNull()
                            )
                        )
                        if (batch.size >= BATCH_SIZE) {
                            flushMotionBatch(db, batch, overwrite)
                            count += BATCH_SIZE
                            onProgress(count)
                        }
                    } catch (e: Exception) {
                        parseErrors += ImportIssue(fileName, lineNumber, e.message ?: "行の解析に失敗しました")
                    }
                }
            }
            if (batch.isNotEmpty()) {
                flushMotionBatch(db, batch, overwrite)
                count += batch.size
            }
        } catch (e: Throwable) {
            parseErrors += ImportIssue(fileName, message = "ファイル読込失敗: ${e.message}")
        }
        return ImportReport(
            importedCount = count,
            processedFiles = 1,
            skippedFiles = emptyList(),
            parseErrors = parseErrors
        )
    }

    fun appendDebugLogToUri(context: Context, fileUri: Uri, message: String) {
        try {
            context.contentResolver.openOutputStream(fileUri, "wa")?.use { out ->
                OutputStreamWriter(out).use { it.write("[${SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN).format(Date())}] $message\n"); it.flush() }
            }
        } catch (e: Exception) {}
    }

    fun appendMotionSampleToLocalCsv(context: Context, sample: MotionSample) {
        enqueueMotionSampleToLocalCsv(context, sample)
    }

    fun rewriteMotionSamplesInLocalCsv(context: Context, samples: List<MotionSample>) {
        if (samples.isEmpty()) return
        synchronized(csvQueueLock) {
            flushMotionQueueLocked(context)
            samples.groupBy { GpsUtil.getLoggingStart(it.timestamp) }.forEach { (dayStart, daySamples) ->
                rewriteMotionSamplesInFile(buildDailyMotionLogFile(context, dayStart), daySamples)
            }
        }
    }

    fun syncDebugLogToUri(context: Context, fileUri: Uri) {
        try {
            val localFile = buildDebugLogFile(context)
            if (!localFile.exists()) return
            val content = localFile.readText()
            context.contentResolver.openOutputStream(fileUri, "wt")?.use { out ->
                BufferedWriter(OutputStreamWriter(out)).use { it.write(content) }
            }
        } catch (e: Exception) {}
    }

    fun writeDebugLog(context: Context, message: String) {
        val now = System.currentTimeMillis()
        writeLocalDebugLog(context, message)
        appendEventCommentToDailyCsv(context, now, message)
        debugScope.launch {
            try {
                val settings = SettingsRepository(context)
                val enabled = settings.driveDebugEnabled.first()
                val fileUri = settings.debugLogFileUri.first()
                if (!enabled || fileUri.isNullOrBlank()) return@launch
                appendDebugLogToUri(context, Uri.parse(fileUri), message)
            } catch (_: Exception) {
            }
        }
    }

    fun writeVerboseDebugLog(context: Context, message: String) {
        ensureVerboseSettingsInit(context)
        if (!verboseLogEnabled) return  // 設定無効時はコルーチン起動もスキップ
        debugScope.launch {
            try {
                writeDebugLog(context, message)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun logDatabaseSummary(context: Context) {
        try {
            val db = AppDatabase.getInstance(context)
            val entries = db.logDao().getEntriesSince(0L).first().sortedBy { it.timestamp }
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)

            fun formatTs(timestamp: Long?): String {
                return if (timestamp == null) {
                    "none"
                } else {
                    formatter.format(Date(timestamp))
                }
            }

            writeDebugLog(
                context,
                "DB_SUMMARY: total=${entries.size} oldest=${formatTs(entries.firstOrNull()?.timestamp)} newest=${formatTs(entries.lastOrNull()?.timestamp)}"
            )

            val now = System.currentTimeMillis()
            val currentLoggingStart = GpsUtil.getLoggingStart(now)
            for (offset in 0..6) {
                val start = currentLoggingStart - offset * GpsUtil.DAY_MS
                val end = start + GpsUtil.DAY_MS
                val count = entries.count { it.timestamp in start until end }
                val locationCount = entries.count { it.timestamp in start until end && it.hasLocation }
                writeDebugLog(
                    context,
                    "DB_LOGGING_DAY: start=${formatTs(start)} end=${formatTs(end)} count=$count locationCount=$locationCount"
                )
            }

            repeat(3) { offset ->
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_YEAR, -offset)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val start = cal.timeInMillis
                val end = start + 3 * 3600_000L
                val count = entries.count { it.timestamp in start until end }
                val locationCount = entries.count { it.timestamp in start until end && it.hasLocation }
                writeDebugLog(
                    context,
                    "DB_PRE3_WINDOW: date=${SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(Date(start))} count=$count locationCount=$locationCount"
                )
            }
        } catch (e: Exception) {
            writeDebugLog(context, "DB_SUMMARY_ERROR: ${e.message}")
        }
    }

    suspend fun repairStepDataFromLocalLogs(context: Context): Boolean {
        try {
            val db = AppDatabase.getInstance(context)
            val records = collectStepRepairRecords(context)
            if (records.isEmpty()) {
                writeDebugLog(context, "STEP_REPAIR_DONE: no_local_csv_records")
                return true
            }

            val updates = mutableListOf<LogEntry>()
            var repairedCount = 0
            records.forEach { record ->
                val existing = db.logDao().findByTimestamp(record.timestamp) ?: return@forEach
                val updated = existing.copy(
                    stepsDelta = existing.stepsDelta ?: record.stepsDelta,
                    legacyStepCount = existing.legacyStepCount ?: record.legacyStepCount
                )
                if (updated != existing) {
                    updates += updated
                    repairedCount += 1
                }
                if (updates.size >= BATCH_SIZE) {
                    db.logDao().insertAllReplace(updates.toList())
                    updates.clear()
                }
            }
            if (updates.isNotEmpty()) {
                db.logDao().insertAllReplace(updates.toList())
            }
            writeDebugLog(context, "STEP_REPAIR_DONE: source=${records.size}, repaired=$repairedCount")
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun collectStepRepairRecords(context: Context): List<StepRepairRecord> {
        val files = linkedSetOf<File>()
        val internalRoot = File(context.filesDir, ROOT_DIR)
        val externalRoot = context.getExternalFilesDir(null)?.let { File(it, ROOT_DIR) }
        listOf(internalRoot, externalRoot).filterNotNull().forEach { root ->
            root.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith(DAILY_LOG_PREFIX) && file.extension.equals("csv", ignoreCase = true)) {
                    files += file
                }
            }
            File(root, LOGS_DIR).listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith(DAILY_LOG_PREFIX) && file.extension.equals("csv", ignoreCase = true)) {
                    files += file
                }
            }
        }
        if (files.isEmpty()) return emptyList()

        val rawRecords = mutableListOf<StepRepairRecord>()
        files.sortedBy { it.name }.forEach { file ->
            rawRecords += parseStepRecords(file)
        }
        if (rawRecords.isEmpty()) return emptyList()

        val sorted = rawRecords.sortedBy { it.timestamp }
        var previousLegacy: Int? = null
        return sorted.map { record ->
            val resolvedDelta = when {
                record.stepsDelta != null -> record.stepsDelta
                record.legacyStepCount != null -> {
                    if (previousLegacy == null) 0 else (record.legacyStepCount - previousLegacy!!).coerceAtLeast(0)
                }
                else -> null
            }
            if (record.legacyStepCount != null) {
                previousLegacy = record.legacyStepCount
            }
            StepRepairRecord(record.timestamp, resolvedDelta, record.legacyStepCount)
        }
    }

    private fun parseStepRecords(file: File): List<StepRepairRecord> {
        val records = mutableListOf<StepRepairRecord>()
        try {
            file.bufferedReader().use { reader ->
                var lineNumber = 0
                val headerLine = readNextCsvContentLine(reader, lineNumber) ?: return emptyList()
                lineNumber = headerLine.first
                val header = headerLine.second.split(",").map { cleanHeader(it) }
                val tsIndex = header.indexOf("Timestamp")
                if (tsIndex < 0) return emptyList()
                val deltaIndex = header.indexOf("StepsDelta")
                val legacyIndex = header.indexOf("Steps")
                while (true) {
                    val contentLine = readNextCsvContentLine(reader, lineNumber) ?: break
                    lineNumber = contentLine.first
                    val cols = contentLine.second.split(",").map { it.trim().removeSurrounding("\"") }
                    val ts = cols.getOrNull(tsIndex)?.toLongOrNull() ?: continue
                    val delta = if (deltaIndex >= 0) cols.getOrNull(deltaIndex)?.takeIf { it.isNotEmpty() }?.toIntOrNull() else null
                    val legacy = if (legacyIndex >= 0) cols.getOrNull(legacyIndex)?.takeIf { it.isNotEmpty() }?.toIntOrNull() else null
                    records += StepRepairRecord(ts, delta, legacy)
                }
            }
        } catch (_: Exception) {
        }
        return records
    }
}
