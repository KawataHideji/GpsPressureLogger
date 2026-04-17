# 調査メモ

最終更新: 2026-04-13

## ウィジェット更新が数秒で走る件

### 事象
- 利用者設定は 60 秒だが、ウィジェットが数秒差で再描画されることがある。
- 共有デバッグログでは `MAP_WIDGET_RENDER` が通常は約 60 秒ごとだが、`SERVICE_CREATE` 前後で数秒差の重複更新が混ざる。

### 確認したログ例
- `06:24:41 MAP_WIDGET_RENDER` -> `06:24:41 SERVICE_CREATE` -> `06:24:44 MAP_WIDGET_RENDER`
- `07:44:02 MAP_WIDGET_RENDER` -> `07:44:02 SERVICE_CREATE` -> `07:44:05 MAP_WIDGET_RENDER`
- `07:51:37 MAP_WIDGET_RENDER` -> `07:51:37 SERVICE_CREATE` -> `07:51:40 MAP_WIDGET_RENDER`

### コード上の原因
1. ウィジェット更新経路が複数ある
- `LoggingService.updateWidgetsIfDue()` の定期更新
- `AppWidgetProvider.onUpdate()`
- `MapWidgetReceiver.onAppWidgetOptionsChanged()`
- `PressureWidgetReceiver` は `onUpdate()` からさらに `ACTION_APPWIDGET_UPDATE` を自分で再送していた

2. 入口ごとに共通の最終描画時刻ガードが無かった
- receiver 側イベントは、定期更新間隔を見ずにそのまま `updateSingleWidget()` へ進んでいた

### Android 公式仕様確認
- `AppWidgetProvider.onUpdate()` は `ACTION_APPWIDGET_UPDATE` で呼ばれる
- `onAppWidgetOptionsChanged()` は配置時・サイズ変更時に呼ばれる
- `updatePeriodMillis` は 30 分未満を保証しないため、今回のような 60 秒更新には不向き

### 修正方針
- アプリ自身が `ACTION_APPWIDGET_UPDATE` を再送する処理をやめる
- `onAppWidgetOptionsChanged()` は即再描画せず、次回描画時にサイズを読むだけにする
- app / widget 共通で「前回描画から設定秒数未満なら描かない」ゲートを receiver 側にも持つ
