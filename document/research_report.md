# 調査メモ

最終更新: 2026-05-06

## ウィジェット更新が数秒で走る件

### 事象
- 利用者設定は 60 秒だが、ウィジェットが数秒差で再描画されることがある。
- 共有デバッグログでは `MAP_WIDGET_RENDER` が通常は約 60 秒ごとだが、`SERVICE_CREATE` 前後で数秒差の重複更新が混ざる。

### 確認したログ例
- `06:24:41 MAP_WIDGET_RENDER` -> `06:24:41 SERVICE_CREATE` -> `06:24:44 MAP_WIDGET_RENDER`
- `07:44:02 MAP_WIDGET_RENDER` -> `07:44:02 SERVICE_CREATE` -> `07:44:05 MAP_WIDGET_RENDER`
- `07:51:37 MAP_WIDGET_RENDER` -> `07:51:37 SERVICE_CREATE` -> `07:51:40 MAP_WIDGET_RENDER`

### 原因
1. ウィジェット更新経路が複数あり、入口ごとに最終描画時刻ガードが共通化されていなかった。
- `LoggingService.updateWidgetsIfDue()` の定期更新
- `AppWidgetProvider.onUpdate()`（ホスト由来の初回配置・復元・リサイズ）
- `MapWidgetReceiver.onAppWidgetOptionsChanged()`（サイズ変更時）
- `PressureWidgetReceiver` がかつて `onUpdate()` 内で `ACTION_APPWIDGET_UPDATE` を自分で再送していた

### Android 公式仕様確認
- `AppWidgetProvider.onUpdate()` は `ACTION_APPWIDGET_UPDATE` で呼ばれる
- `onAppWidgetOptionsChanged()` は配置時・サイズ変更時に呼ばれる
- `updatePeriodMillis` は 30 分未満を保証しないため、60 秒更新には不向き

### 現行運用
- アプリ自身が `ACTION_APPWIDGET_UPDATE` を再送する処理は撤去済み。
- 周期更新の唯一の時間管理者は `LoggingService.updateWidgetsIfDue()` であり、`slotTimestamp + interval` を次回予定時刻として固定する。
- ホスト由来の初回配置・復元・リサイズと、設定変更などの強制更新は、署名一致でも省略せずに描画する（`MapWidgetReceiver` / `PressureWidgetReceiver` の `allowSignatureSkip` 分岐）。
- サービス由来の周期更新では、前回描画署名（widget id / size / 最新時刻 / 軌跡 digest など）が一致する場合は再描画を省略する。

## OutOfMemoryError と未捕捉例外検知

### 事象
- 2026-04-28 19:28 と 2026-05-05 17:35 の共有デバッグログに `UNCAUGHT_EXCEPTION: thread=main type=java.lang.OutOfMemoryError` が出力されている。
- いずれも `LoggingService.onSensorChanged` 経由で発生し、target footprint が約 386MB に達した直後に倒れている。

### 現状
- `GpsLoggerApplication.installUnhandledExceptionLogger()` で `Thread.setDefaultUncaughtExceptionHandler` を差し替え、未捕捉例外発生時に `ExportUtil.logUnhandledException()` を呼び出す構成になっている。
- `logUnhandledException()` は最初に `flushPendingCsvQueues()` を実行し、未書込の主記録 / motion CSV をディスクへ落とす。
- そのうえで `# EVENT <ts> UNCAUGHT_EXCEPTION ...` を当日の日次 CSV に追記し、`writeLocalDebugLog()` で stack trace の先頭 12 行と `cause` も残す。
- 復旧自体は OS 任せで、サービスが落ちた場合は利用者が次にアプリを開いた時に `MainActivity` 経由で再起動される。

### 今後の検討（todo.md にも反映済み）
- センサー入口で `motionScope.launch { ... }` を毎イベント発行している部分が、長時間稼働で coroutine がキューに溜まり OOM の引き金になっていないか確認する。
- 必要なら `AccelManager` 側でバッチ消費する形へ寄せる、または `motionScope` を制御フロー付きに変える。
