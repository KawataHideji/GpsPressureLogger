package com.example.gpspressurelogger.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.gpspressurelogger.ui.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.*

private data class LookbackOption(
    val label: String,
    val minutes: Int
)

private val LOOKBACK_OPTIONS = listOf(
    LookbackOption("6h", 6 * 60),
    LookbackOption("12h", 12 * 60),
    LookbackOption("1d", 24 * 60),
    LookbackOption("2d", 2 * 24 * 60),
    LookbackOption("3d", 3 * 24 * 60),
    LookbackOption("1w", 7 * 24 * 60)
)

/**
 * 設定画面：記録方式・遡り時間・ウィジェット更新間隔を設定
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val lookbackMin              by viewModel.lookbackMin.collectAsState()
    val pressureWidgetIntervalMin by viewModel.pressureWidgetIntervalMin.collectAsState()
    val mapWidgetIntervalMin     by viewModel.mapWidgetIntervalMin.collectAsState()
    val widgetTransparency       by viewModel.widgetTransparency.collectAsState()
    val importFileUri            by viewModel.importFileUri.collectAsState()
    val debugLogFileUri          by viewModel.debugLogFileUri.collectAsState()
    val driveDebugEnabled        by viewModel.driveDebugEnabled.collectAsState()
    val verboseDebugLogEnabled   by viewModel.verboseDebugLogEnabled.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }

    // 衝突解決ダイアログ
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("インポート設定") },
            text = { Text("既存のデータと時間が重複する場合の処理を選択してください。") },
            confirmButton = {
                TextButton(onClick = { 
                    viewModel.triggerImport(overwrite = true)
                    showImportDialog = false 
                }) {
                    Text("上書きする")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    viewModel.triggerImport(overwrite = false)
                    showImportDialog = false 
                }) {
                    Text("既存を優先 (スキップ)")
                }
            }
        )
    }

    val importFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            viewModel.setImportFileUri(it.toString())
        }
    }

    val debugFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            viewModel.setDebugLogFileUri(it.toString())
        }
    }

    // ファイル保存ランチャー (エクスポート用：汎用インテント方式)
    val fileSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> viewModel.exportToUri(uri) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("記録設定", style = MaterialTheme.typography.titleMedium)

            Text(
                text = "主記録周期: 3秒固定\nCSV書き出し: 100件ごと",
                style = MaterialTheme.typography.bodyMedium
            )

            val selectedLookback = remember(lookbackMin) {
                LOOKBACK_OPTIONS.minByOrNull { kotlin.math.abs(it.minutes - lookbackMin) } ?: LOOKBACK_OPTIONS[4]
            }
            OptionChipSetting(
                label = "表示する期間",
                options = LOOKBACK_OPTIONS,
                selectedOption = selectedLookback,
                optionLabel = { it.label },
                onSelect = { viewModel.setLookbackMin(it.minutes) }
            )

            HorizontalDivider()

            Text("ウィジェット設定", style = MaterialTheme.typography.titleMedium)

            SliderSetting(
                label = "気圧・標高ウィジェット: ${pressureWidgetIntervalMin}秒ごと",
                value = pressureWidgetIntervalMin.toFloat(),
                valueRange = SettingsRange.WIDGET_INTERVAL_RANGE,
                steps = SettingsRange.WIDGET_INTERVAL_STEPS,
                onValueChangeFinished = { viewModel.setPressureWidgetIntervalMin(it.toInt()) }
            )

            SliderSetting(
                label = "地図ウィジェット: ${mapWidgetIntervalMin}秒ごと",
                value = mapWidgetIntervalMin.toFloat(),
                valueRange = SettingsRange.WIDGET_INTERVAL_RANGE,
                steps = SettingsRange.WIDGET_INTERVAL_STEPS,
                onValueChangeFinished = { viewModel.setMapWidgetIntervalMin(it.toInt()) }
            )

            val transparencyPercent = ((255 - widgetTransparency) * 100 / 255)
            SliderSetting(
                label = "グラフウィジェットの透明度: ${transparencyPercent}%",
                value = transparencyPercent.toFloat(),
                valueRange = 0f..100f,
                steps = 20,
                onValueChangeFinished = { 
                    val alpha = (255 - (it * 255 / 100)).toInt()
                    viewModel.setWidgetTransparency(alpha) 
                }
            )

            HorizontalDivider()

            Text("データ同期 (Google ドライブ・外部保存)", style = MaterialTheme.typography.titleMedium)
            
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                
                // 標準データのエクスポート：統一18カラム CSV を 1 ファイルで出す。
                Button(
                    onClick = {
                        val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/csv"
                            putExtra(Intent.EXTRA_TITLE, "gps_pressure_standard_$timeStr.csv")
                            putExtra("android.content.extra.SHOW_ADVANCED", true)
                            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                        }
                        fileSaveLauncher.launch(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("標準データを保存 (Googleドライブ/SDカード等)")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // インポート（標準CSV：Type 1 / Type 2 のどちらも統一パーサーで読み込む）
                Text("標準データ CSV の取り込み・復元", style = MaterialTheme.typography.labelMedium)
                Button(
                    onClick = { importFilePickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("読込元の CSV ファイルを選択")
                }

                if (importFileUri != null) {
                    Button(
                        onClick = { showImportDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Text("選択した CSV からデータを統合・復元")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("デバッグログ共有", style = MaterialTheme.typography.labelMedium)
                Button(
                    onClick = { debugFilePickerLauncher.launch("app_debug_log.txt") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("デバッグログ出力先ファイルを選択")
                }

                if (debugLogFileUri != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Google ドライブへデバッグログ送信")
                        Switch(
                            checked = driveDebugEnabled,
                            onCheckedChange = { viewModel.setDriveDebugEnabled(it) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("詳細デバッグログ")
                        Switch(
                            checked = verboseDebugLogEnabled,
                            onCheckedChange = { viewModel.setVerboseDebugLogEnabled(it) }
                        )
                    }

                    Button(
                        onClick = { viewModel.syncDebugLogNow() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("デバッグログを今すぐ送信")
                    }

                    Button(
                        onClick = { viewModel.logDatabaseSummary() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("DB サマリをログ出力")
                    }
                }
            }
        }
    }
}

private object SettingsRange {
    val WIDGET_INTERVAL_RANGE = 30f..300f
    const val WIDGET_INTERVAL_STEPS = 26
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (Float) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = { onValueChangeFinished(sliderValue) }
        )
    }
}

@Composable
private fun <T> OptionChipSetting(
    label: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        options.chunked(3).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowOptions.forEach { option ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = option == selectedOption,
                        onClick = { onSelect(option) },
                        label = {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(optionLabel(option))
                            }
                        }
                    )
                }
                repeat(3 - rowOptions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
