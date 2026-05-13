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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
    private const val ANALYSIS_DIR = "analysis"
    private const val BATCH_SIZE = 1000

    /**
     * バックアップエクスポート / インポートで使う統一CSVのヘッダー（18カラム）。
     *
     * - 左8カラム: LogEntry 由来（GPS / 気圧 / 歩数）。Type 2（気圧のみ・GPS のみ等）の
     *   外部入力はこの範囲のサブセットだけが入っていれば良い。
     * - 右10カラム: MotionSample 由来（モード / 状態ラベル / 定速領域）。Type 1 の
     *   完全エクスポートで埋まる。Type 2 入力では空になる。
     *
     * パーサーはヘッダー名で各カラムを解決するので、入力ファイルが本ヘッダーの
     * 部分集合 / 順序違いでも正しく読める。
     */
    private const val STANDARD_CSV_HEADER =
        "Timestamp,Lat,Lon,Alt,PresRaw,PresQnh,StepsDelta,GpsAccuracy," +
            "ConfirmedMode,StKStatus,TrKStatus,WStatus,StepDeltaWindow,GpsImmediate," +
            "ConstantRegionKind,ConstantRegionSpeedKmh,ConstantRegionStayLat,ConstantRegionStayLon"

    /** 日次ローカル `gps_log_*.csv` のヘッダー（LogEntry 8 カラム）。 */
    private const val DAILY_LOG_CSV_HEADER =
        "Timestamp,Lat,Lon,Alt,PresRaw,PresQnh,StepsDelta,GpsAccuracy"

    /** 日次ローカル `motion_essential_*.csv` のヘッダー（MotionSample 11 カラム）。 */
    private const val DAILY_MOTION_CSV_HEADER =
        "Timestamp,StKStatus,TrKStatus,WStatus,StepDeltaWindow,GpsImmediate,ConfirmedMode," +
            "ConstantRegionKind,ConstantRegionSpeedKmh,ConstantRegionStayLat,ConstantRegionStayLon"

    private const val STANDARD_BACKUP_PREFIX = "gps_pressure_standard"
    private const val DAILY_LOG_PREFIX = "gps_log"
    private const val DAILY_MOTION_LOG_PREFIX = "motion_essential"
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

    private fun gpsImmediateCsv(value: Boolean?): String = value?.let { if (it) "1" else "0" } ?: ""

    /** 日次 `gps_log_*.csv` 向けの 8 カラム LogEntry 行を作る。 */
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

    /** 日次 `motion_essential_*.csv` 向けの 11 カラム MotionSample 行を作る。 */
    private fun motionSampleCsvRow(sample: MotionSample): String =
        listOf(
            sample.timestamp,
            csvValue(sample.stKStatus),
            csvValue(sample.trKStatus),
            csvValue(sample.wStatus),
            csvValue(sample.stepDeltaWindow),
            gpsImmediateCsv(sample.gpsImmediate),
            csvValue(sample.confirmedMode),
            csvValue(sample.constantRegionKind),
            csvValue(sample.constantRegionSpeedKmh),
            csvValue(sample.constantRegionStayLat),
            csvValue(sample.constantRegionStayLon)
        ).joinToString(",")

    /**
     * バックアップエクスポート向けに、LogEntry 由来の行を統一18カラム形式で出す。
     * MotionSample 列は空欄にする。
     */
    private fun unifiedRowFromEntry(entry: LogEntry): String =
        listOf(
            entry.timestamp,
            csvValue(entry.latitude),
            csvValue(entry.longitude),
            csvValue(entry.altitudeGps),
            csvValue(entry.pressureRaw),
            csvValue(entry.pressureQnh),
            csvValue(entry.stepsDelta),
            csvValue(entry.gpsAccuracy),
            "", "", "", "", "", "", "", "", "", ""
        ).joinToString(",")

    /**
     * バックアップエクスポート向けに、MotionSample 由来の行を統一18カラム形式で出す。
     * LogEntry 列は空欄にする。
     */
    private fun unifiedRowFromMotion(sample: MotionSample): String =
        listOf(
            sample.timestamp,
            "", "", "", "", "", "", "",
            csvValue(sample.confirmedMode),
            csvValue(sample.stKStatus),
            csvValue(sample.trKStatus),
            csvValue(sample.wStatus),
            csvValue(sample.stepDeltaWindow),
            gpsImmediateCsv(sample.gpsImmediate),
            csvValue(sample.constantRegionKind),
            csvValue(sample.constantRegionSpeedKmh),
            csvValue(sample.constantRegionStayLat),
            csvValue(sample.constantRegionStayLon)
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
                        writer.write("$DAILY_LOG_CSV_HEADER\n")
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

    /**
     * 解析用データ（生センサーログ）のローカル保存ディレクトリ。
     * `RawSensorWriter` が `raw_*.csv.gz` を直近2日分だけ保持する。
     */
    fun getAnalysisDir(context: Context): File = getStorageDir(context, ANALYSIS_DIR)

    /**
     * 解析データを 1 ファイルの ZIP にまとめてユーザー指定 Uri へ書き出す。
     *
     * - `raw_*.csv.gz` を 1 つずつ読み出して内部の生 CSV を取り出し、ZIP の deflate
     *   エントリとして格納する。受け取り側は普通の ZIP として解凍するだけで
     *   `raw_*.csv` が出てくる（ユーザーは gzip → csv の二重解凍をしなくて済む）。
     * - 直近2日分の全ファイルをまとめる（`RawSensorWriter` 側で既に古いファイルは削除済み）。
     */
    fun exportAnalysisDataToZip(context: Context, fileUri: Uri): Boolean {
        return try {
            // RawSensorWriter が書き込み中の gzip ストリームを閉じて trailer を確定させる。
            // 閉じないと GZIPInputStream が "Unexpected end of ZLIB input stream" で失敗する。
            // 次に sensor サンプルが来たら writer は自動再オープンするので記録は途切れない。
            RawSensorWriter.sealForExport()
            val files = analysisExportableFiles(context)
            if (files.isEmpty()) {
                writeLocalDebugLog(context, "EXPORT_ANALYSIS_EMPTY uri=$fileUri reason=noFiles")
                deleteUriQuietly(context, fileUri)
                return false
            }
            val outputStream = context.contentResolver.openOutputStream(fileUri)
            if (outputStream == null) {
                writeLocalDebugLog(
                    context,
                    "EXPORT_ANALYSIS_FAILED uri=$fileUri reason=openOutputStreamReturnedNull files=${files.size}"
                )
                deleteUriQuietly(context, fileUri)
                return false
            }
            outputStream.use { out ->
                ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                    val buffer = ByteArray(8 * 1024)
                    files.forEach { file ->
                        val entryName = file.name.removeSuffix(".gz")
                        val entry = ZipEntry(entryName).apply { time = file.lastModified() }
                        zip.putNextEntry(entry)
                        runCatching {
                            FileInputStream(file).use { fis ->
                                GZIPInputStream(fis).use { gz ->
                                    while (true) {
                                        val read = gz.read(buffer)
                                        if (read <= 0) break
                                        zip.write(buffer, 0, read)
                                    }
                                }
                            }
                        }.onFailure { throwable ->
                            writeLocalDebugLog(
                                context,
                                "EXPORT_ANALYSIS_ENTRY_FAILED file=${file.name} reason=${throwable.javaClass.simpleName}:${throwable.message ?: "unknown"}"
                            )
                        }
                        zip.closeEntry()
                    }
                }
            }
            val exportedSize = exportedDocumentSize(context, fileUri)
            if (exportedSize <= 0L) {
                writeLocalDebugLog(
                    context,
                    "EXPORT_ANALYSIS_FAILED uri=$fileUri reason=emptyDocument files=${files.size}"
                )
                deleteUriQuietly(context, fileUri)
                return false
            }
            writeLocalDebugLog(
                context,
                "EXPORT_ANALYSIS_OK uri=$fileUri files=${files.size} size=$exportedSize"
            )
            true
        } catch (e: Throwable) {
            writeLocalDebugLog(
                context,
                "EXPORT_ANALYSIS_FAILED uri=$fileUri reason=${e.javaClass.simpleName}:${e.message ?: "unknown"}"
            )
            deleteUriQuietly(context, fileUri)
            false
        }
    }

    private fun analysisExportableFiles(context: Context): List<File> {
        val dir = getAnalysisDir(context)
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray()).asSequence()
            .filter { it.isFile && it.name.endsWith(".csv.gz") && it.name.startsWith("raw_") }
            .sortedBy { it.name }
            .toList()
    }

    fun enqueueEntryToLocalCsv(context: Context, entry: LogEntry) {
        synchronized(csvQueueLock) {
            // 日付境界 flush で予期しない例外が出ても、新しいエントリがキューから silent drop されないようにする。
            try {
                flushEntryQueueForDayBoundaryLocked(context, entry.timestamp)
            } catch (e: Throwable) {
                writeLocalDebugLog(context, "CSV_DAY_FLUSH_FAILED: kind=entry msg=${e.message}")
            }
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
            try {
                flushMotionQueueForDayBoundaryLocked(context, sample.timestamp)
            } catch (e: Throwable) {
                writeLocalDebugLog(context, "CSV_DAY_FLUSH_FAILED: kind=motion msg=${e.message}")
            }
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
            FileOutputStream(file, true).use { out ->
                OutputStreamWriter(out).use { writer ->
                    if (isNew) {
                        writeCsvComment(writer, "GpsPressureLogger daily log")
                        writer.write("$DAILY_LOG_CSV_HEADER\n")
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

    private fun appendMotionSamplesToFile(file: File, samples: List<MotionSample>): Boolean {
        if (samples.isEmpty()) return true
        return try {
            val isNew = !file.exists()
            FileOutputStream(file, true).use { out ->
                OutputStreamWriter(out).use { writer ->
                    if (isNew) {
                        writeCsvComment(writer, "GpsPressureLogger daily motion essential")
                        writer.write("$DAILY_MOTION_CSV_HEADER\n")
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

    /**
     * 標準バックアップを統一18カラム CSV としてユーザー指定 Uri へ書き出す。
     * log_entries と motion_samples を timestamp で時系列マージし、それぞれ専用の
     * カラムだけが埋まった行として連続出力する。日次 EVENT コメントも保持する。
     */
    /**
     * 標準バックアップを ZIP（1 エントリの CSV）として書き出す。
     *
     * 経緯: 直接 .csv を SAF で Google Drive に書き込むと、ファイルサイズが 100 MB
     * 程度を超えた瞬間に Drive 側が cloud 上で 0 バイトとして扱う挙動が観測された
     * （MIME を application/octet-stream にしても改善せず）。Drive は ZIP ファイルは
     * 元々問題なく扱う（解析データ ZIP は通っている）ので、CSV を 1 つだけ含む
     * ZIP として包んで送る。
     *
     * 受け取り側（PC、viewer）は unzip して中の .csv を取り出してから読み込む。
     */
    suspend fun writeStandardBackupToUri(context: Context, fileUri: Uri, db: AppDatabase): Boolean {
        val tStart = System.currentTimeMillis()
        return try {
            flushPendingCsvQueues(context)
            writeLocalDebugLog(context, "EXPORT_STANDARD_FLUSHED queues elapsedMs=${System.currentTimeMillis() - tStart}")
            val outputStream = context.contentResolver.openOutputStream(fileUri)
            if (outputStream == null) {
                writeLocalDebugLog(
                    context,
                    "EXPORT_STANDARD_FAILED uri=$fileUri reason=openOutputStreamReturnedNull"
                )
                deleteUriQuietly(context, fileUri)
                return false
            }
            writeLocalDebugLog(
                context,
                "EXPORT_STANDARD_STREAM_OPENED uri=$fileUri elapsedMs=${System.currentTimeMillis() - tStart}"
            )

            var entryRows = 0
            var motionRows = 0
            var commentRows = 0
            val entryName = "gps_pressure_standard_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())}.csv"
            outputStream.use { out ->
                ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                    zip.putNextEntry(ZipEntry(entryName))
                    // BufferedWriter を ZipOutputStream の上に重ねる。
                    // writer.close() すると zip まで閉じてしまうので明示 close せず、flush だけ呼ぶ。
                    val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
                    writeCsvComment(writer, "GpsPressureLogger standard backup")
                    writer.write("$STANDARD_CSV_HEADER\n")
                    writer.flush()
                    writeLocalDebugLog(
                        context,
                        "EXPORT_STANDARD_HEADER_WRITTEN elapsedMs=${System.currentTimeMillis() - tStart}"
                    )

                    val comments = collectDailyCsvEventComments(context)
                    writeLocalDebugLog(
                        context,
                        "EXPORT_STANDARD_COMMENTS_LOADED count=${comments.size} elapsedMs=${System.currentTimeMillis() - tStart}"
                    )

                    // log_entries を timestamp 昇順で 1 ページずつ書き出す。
                    // EVENT コメントは log_entries の時刻に合わせて挟み込む。
                    // motion_samples は末尾にまとめて追記する（viewer 側で timestamp ソートする）。
                    val entryPager = pageEntries(db)
                    var commentIndex = 0
                    while (true) {
                        val entry = entryPager.next() ?: break
                        while (commentIndex < comments.size && comments[commentIndex].timestamp <= entry.timestamp) {
                            writer.write(comments[commentIndex].line + "\n")
                            commentIndex += 1
                            commentRows += 1
                        }
                        writer.write(unifiedRowFromEntry(entry) + "\n")
                        entryRows += 1
                        if (entryRows % BATCH_SIZE == 0) {
                            writer.flush()
                            writeLocalDebugLog(
                                context,
                                "EXPORT_STANDARD_PROGRESS entries=$entryRows elapsedMs=${System.currentTimeMillis() - tStart}"
                            )
                        }
                    }
                    // 残りのコメント
                    while (commentIndex < comments.size) {
                        writer.write(comments[commentIndex].line + "\n")
                        commentIndex += 1
                        commentRows += 1
                    }
                    writer.flush()
                    writeLocalDebugLog(
                        context,
                        "EXPORT_STANDARD_ENTRIES_DONE entries=$entryRows comments=$commentRows elapsedMs=${System.currentTimeMillis() - tStart}"
                    )

                    // motion_samples を末尾に追記
                    val motionPager = pageMotionSamples(db)
                    while (true) {
                        val sample = motionPager.next() ?: break
                        writer.write(unifiedRowFromMotion(sample) + "\n")
                        motionRows += 1
                        if (motionRows % BATCH_SIZE == 0) writer.flush()
                    }
                    writer.flush()
                    // ZipOutputStream.use 終了で entry/zip ともに close され、ZIP の中央ディレクトリが書かれる
                    zip.closeEntry()
                    writeLocalDebugLog(
                        context,
                        "EXPORT_STANDARD_MOTION_DONE motion=$motionRows elapsedMs=${System.currentTimeMillis() - tStart}"
                    )
                }
            }

            val exportedSize = exportedDocumentSize(context, fileUri)
            if (exportedSize <= 0L) {
                writeLocalDebugLog(
                    context,
                    "EXPORT_STANDARD_FAILED uri=$fileUri reason=emptyDocument entries=$entryRows motion=$motionRows size=$exportedSize"
                )
                deleteUriQuietly(context, fileUri)
                return false
            }
            writeLocalDebugLog(
                context,
                "EXPORT_STANDARD_OK uri=$fileUri entryName=$entryName entries=$entryRows motion=$motionRows comments=$commentRows zipSize=$exportedSize totalElapsedMs=${System.currentTimeMillis() - tStart}"
            )
            true
        } catch (e: Throwable) {
            writeLocalDebugLog(
                context,
                "EXPORT_STANDARD_FAILED uri=$fileUri reason=${e.javaClass.simpleName}:${e.message ?: "unknown"} elapsedMs=${System.currentTimeMillis() - tStart}"
            )
            deleteUriQuietly(context, fileUri)
            false
        }
    }

    /**
     * timestamp 昇順で log_entries を 1 件ずつ流すページャ。1 ページ BATCH_SIZE 件読み、
     * 使い切ったら次ページを読みに行く。end は null を返す。
     */
    private fun pageEntries(db: AppDatabase): TimestampedPager<LogEntry> =
        object : TimestampedPager<LogEntry>() {
            override suspend fun loadPage(after: Long): List<LogEntry> =
                db.logDao().getPageAfter(after, BATCH_SIZE)

            override fun timestampOf(item: LogEntry): Long = item.timestamp
        }

    private fun pageMotionSamples(db: AppDatabase): TimestampedPager<MotionSample> =
        object : TimestampedPager<MotionSample>() {
            override suspend fun loadPage(after: Long): List<MotionSample> =
                db.motionSampleDao().getPageAfter(after, BATCH_SIZE)

            override fun timestampOf(item: MotionSample): Long = item.timestamp
        }

    private abstract class TimestampedPager<T> {
        private var buffer: List<T> = emptyList()
        private var index = 0
        private var lastTimestamp = Long.MIN_VALUE
        private var exhausted = false

        protected abstract suspend fun loadPage(after: Long): List<T>
        protected abstract fun timestampOf(item: T): Long

        suspend fun next(): T? {
            if (index < buffer.size) {
                val item = buffer[index]
                index += 1
                lastTimestamp = timestampOf(item)
                return item
            }
            if (exhausted) return null
            val page = loadPage(lastTimestamp)
            if (page.isEmpty()) {
                exhausted = true
                return null
            }
            buffer = page
            index = 0
            return next()
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

    /**
     * 統一CSVを読み込んで log_entries / motion_samples へ振り分けインポートする。
     * - LogEntry 系カラム（Lat/Lon/Alt/PresRaw/PresQnh/StepsDelta/GpsAccuracy）が
     *   何か入っていれば log_entries 側にもこの行を upsert する。
     * - MotionSample 系カラム（StKStatus/TrKStatus/WStatus/StepDeltaWindow/GpsImmediate/
     *   ConfirmedMode/ConstantRegion*）が何か入っていれば motion_samples 側にも upsert。
     * - 両方なければスキップ。
     *
     * カラム名ベースで解決するので、入力CSVが部分集合（Type 2: 気圧のみ・GPSのみ等）
     * でも欠落カラムは null として扱われる。
     */
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
            val isCsv = name.endsWith(".csv", ignoreCase = true)
            val isZip = name.endsWith(".zip", ignoreCase = true)
            if (!isCsv && !isZip) {
                return ImportReport(
                    importedCount = 0,
                    processedFiles = 0,
                    skippedFiles = listOf(ImportIssue(name, message = "CSV / ZIP ではないためスキップ")),
                    parseErrors = emptyList()
                )
            }
            // ZIP（標準バックアップ）の場合は中の最初の .csv エントリを取り出して
            // 一時ファイル化したうえで通常の CSV パーサーに流す。
            val csvUriToImport = if (isZip) {
                val tempCsv = extractFirstCsvFromZip(context, fileUri)
                if (tempCsv == null) {
                    return ImportReport(
                        importedCount = 0,
                        processedFiles = 0,
                        skippedFiles = listOf(ImportIssue(name, message = "ZIP に CSV エントリが見つかりません")),
                        parseErrors = emptyList()
                    )
                }
                Uri.fromFile(tempCsv)
            } else {
                fileUri
            }
            val importResult = streamImportUnifiedCsv(context, db, csvUriToImport, name, overwrite) { c ->
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

    /**
     * ZIP の中身を走査し、最初に見つかった `.csv` エントリの内容を一時ファイルへ展開して返す。
     * 見つからなければ null。
     */
    private fun extractFirstCsvFromZip(context: Context, zipUri: Uri): File? {
        return try {
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.endsWith(".csv", ignoreCase = true)) {
                            val tempFile = File.createTempFile("imported_", ".csv", context.cacheDir)
                            tempFile.deleteOnExit()
                            FileOutputStream(tempFile).use { fos ->
                                val buffer = ByteArray(8 * 1024)
                                while (true) {
                                    val read = zis.read(buffer)
                                    if (read <= 0) break
                                    fos.write(buffer, 0, read)
                                }
                            }
                            return tempFile
                        }
                        zis.closeEntry()
                    }
                }
            }
            null
        } catch (e: Throwable) {
            writeLocalDebugLog(context, "IMPORT_ZIP_EXTRACT_FAILED reason=${e.javaClass.simpleName}:${e.message ?: "unknown"}")
            null
        }
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
            stKStatus = pick(existing.stKStatus, incoming.stKStatus),
            trKStatus = pick(existing.trKStatus, incoming.trKStatus),
            wStatus = pick(existing.wStatus, incoming.wStatus),
            stepDeltaWindow = pick(existing.stepDeltaWindow, incoming.stepDeltaWindow),
            gpsImmediate = pick(existing.gpsImmediate, incoming.gpsImmediate),
            confirmedMode = pick(existing.confirmedMode, incoming.confirmedMode),
            constantRegionKind = pick(existing.constantRegionKind, incoming.constantRegionKind),
            constantRegionSpeedKmh = pick(existing.constantRegionSpeedKmh, incoming.constantRegionSpeedKmh),
            constantRegionStayLat = pick(existing.constantRegionStayLat, incoming.constantRegionStayLat),
            constantRegionStayLon = pick(existing.constantRegionStayLon, incoming.constantRegionStayLon)
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

    /**
     * 統一CSVを 1 ファイル読み、行ごとに log_entries / motion_samples へ振り分ける。
     *
     * カラム名は大文字小文字を区別しない。Type 1 完全エクスポート / Type 2 部分入力
     * （気圧のみ・GPS のみ・両方）いずれも、欠落カラムは null で扱われる。
     * 行に Timestamp 以外の有意な値が 1 つも無ければスキップする。
     */
    private suspend fun streamImportUnifiedCsv(
        context: Context,
        db: AppDatabase,
        uri: Uri,
        fileName: String,
        overwrite: Boolean,
        onProgress: (Int) -> Unit
    ): ImportReport {
        var count = 0
        val entryBatch = mutableListOf<LogEntry>()
        val motionBatch = mutableListOf<MotionSample>()
        val parseErrors = mutableListOf<ImportIssue>()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input))
                var lineNumber = 0
                val headerLine = readNextCsvContentLine(reader, lineNumber)
                    ?: return ImportReport(0, 0, emptyList(), listOf(ImportIssue(fileName, 1, "ヘッダー行が存在しません")))
                lineNumber = headerLine.first
                val header = headerLine.second.split(",").map { cleanHeader(it) }
                val lookup = header.mapIndexed { idx, name -> name.lowercase(Locale.ROOT) to idx }.toMap()
                fun col(name: String): Int = lookup[name.lowercase(Locale.ROOT)] ?: -1

                val iTs = col("Timestamp")
                if (iTs < 0) {
                    return ImportReport(0, 0, emptyList(), listOf(ImportIssue(fileName, lineNumber, "Timestamp カラムがありません")))
                }
                // LogEntry 系
                val iLat = col("Lat")
                val iLon = col("Lon")
                val iAlt = col("Alt")
                val iPresRaw = col("PresRaw")
                val iPresQnh = col("PresQnh")
                val iStepsDelta = col("StepsDelta")
                val iGpsAcc = col("GpsAccuracy")
                // MotionSample 系
                val iStK = col("StKStatus")
                val iTrK = col("TrKStatus")
                val iW = col("WStatus")
                val iStepWin = col("StepDeltaWindow")
                val iGpsImm = col("GpsImmediate")
                val iConfMode = col("ConfirmedMode")
                val iRegKind = col("ConstantRegionKind")
                val iRegSpd = col("ConstantRegionSpeedKmh")
                val iRegStayLat = col("ConstantRegionStayLat")
                val iRegStayLon = col("ConstantRegionStayLon")

                fun List<String>.valueAt(index: Int): String? =
                    if (index >= 0 && index < size) get(index).trim().removeSurrounding("\"").takeIf { it.isNotEmpty() } else null

                while (true) {
                    val contentLine = readNextCsvContentLine(reader, lineNumber) ?: break
                    lineNumber = contentLine.first
                    val c = contentLine.second.split(",")
                    try {
                        val ts = c.valueAt(iTs)?.toLongOrNull()
                        if (ts == null) {
                            parseErrors += ImportIssue(fileName, lineNumber, "Timestamp を解釈できません")
                            continue
                        }
                        // LogEntry 系の値抽出
                        val lat = c.valueAt(iLat)?.toDoubleOrNull()
                        val lon = c.valueAt(iLon)?.toDoubleOrNull()
                        val alt = c.valueAt(iAlt)?.toDoubleOrNull()
                        val presRaw = c.valueAt(iPresRaw)?.toFloatOrNull()
                        val presQnh = c.valueAt(iPresQnh)?.toFloatOrNull()
                        val stepsDelta = c.valueAt(iStepsDelta)?.toIntOrNull()
                        val gpsAcc = c.valueAt(iGpsAcc)?.toFloatOrNull()
                        val hasEntry = lat != null || lon != null || alt != null ||
                            presRaw != null || presQnh != null || stepsDelta != null || gpsAcc != null
                        if (hasEntry) {
                            entryBatch.add(
                                LogEntry(
                                    timestamp = ts,
                                    latitude = lat,
                                    longitude = lon,
                                    altitudeGps = alt,
                                    pressureRaw = presRaw,
                                    pressureQnh = presQnh,
                                    stepsDelta = stepsDelta,
                                    gpsAccuracy = gpsAcc
                                )
                            )
                        }
                        // MotionSample 系の値抽出
                        val stK = c.valueAt(iStK)
                        val trK = c.valueAt(iTrK)
                        val w = c.valueAt(iW)
                        val stepWin = c.valueAt(iStepWin)?.toIntOrNull()
                        val gpsImm = c.valueAt(iGpsImm)?.let { it == "1" || it.equals("true", ignoreCase = true) }
                        val confMode = c.valueAt(iConfMode)
                        val regKind = c.valueAt(iRegKind)
                        val regSpd = c.valueAt(iRegSpd)?.toDoubleOrNull()
                        val regStayLat = c.valueAt(iRegStayLat)?.toDoubleOrNull()
                        val regStayLon = c.valueAt(iRegStayLon)?.toDoubleOrNull()
                        val hasMotion = stK != null || trK != null || w != null || stepWin != null ||
                            gpsImm != null || confMode != null || regKind != null || regSpd != null ||
                            regStayLat != null || regStayLon != null
                        if (hasMotion) {
                            motionBatch.add(
                                MotionSample(
                                    timestamp = ts,
                                    stKStatus = stK,
                                    trKStatus = trK,
                                    wStatus = w,
                                    stepDeltaWindow = stepWin,
                                    gpsImmediate = gpsImm,
                                    confirmedMode = confMode,
                                    constantRegionKind = regKind,
                                    constantRegionSpeedKmh = regSpd,
                                    constantRegionStayLat = regStayLat,
                                    constantRegionStayLon = regStayLon
                                )
                            )
                        }
                        if (entryBatch.size >= BATCH_SIZE) {
                            flushBatch(db, entryBatch, overwrite); count += BATCH_SIZE; onProgress(count)
                        }
                        if (motionBatch.size >= BATCH_SIZE) {
                            flushMotionBatch(db, motionBatch, overwrite); count += BATCH_SIZE; onProgress(count)
                        }
                    } catch (e: Exception) {
                        parseErrors += ImportIssue(fileName, lineNumber, e.message ?: "行の解析に失敗しました")
                    }
                }
            }
            if (entryBatch.isNotEmpty()) { flushBatch(db, entryBatch, overwrite); count += entryBatch.size }
            if (motionBatch.isNotEmpty()) { flushMotionBatch(db, motionBatch, overwrite); count += motionBatch.size }
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
            // 長期運用で debug_log.txt が数百 MB になることがあり、readText() で
            // 一括ロードすると OOM でクラッシュする。バイト単位でストリームコピーし、
            // ヒープを増やさず Drive へ流す。
            val sizeBytes = localFile.length()
            writeLocalDebugLog(context, "DEBUG_SYNC_START uri=$fileUri size=$sizeBytes")
            context.contentResolver.openOutputStream(fileUri, "wt")?.use { out ->
                BufferedOutputStream(out).use { buffered ->
                    FileInputStream(localFile).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            buffered.write(buffer, 0, read)
                        }
                        buffered.flush()
                    }
                }
            }
            writeLocalDebugLog(context, "DEBUG_SYNC_OK uri=$fileUri size=$sizeBytes")
        } catch (e: Throwable) {
            writeLocalDebugLog(context, "DEBUG_SYNC_FAILED uri=$fileUri reason=${e.javaClass.simpleName}:${e.message ?: "unknown"}")
        }
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
