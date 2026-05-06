# 調査レポート：新ハイブリッド状態管理と GPS 制御

最終更新: 2026-05-06

## 1. 調査目的
GpsPressureLogger の現行ハイブリッド状態管理（`trK / stK / w-status / 定速領域`）が、移動状態判定と GPS 取得制御の両面で意図どおりに機能しているかを記録する。

## 2. 採用しているロジック

### 2.1 加速度を 2 経路に分ける `trK` / `stK`
- `trK`（GPS 即時起動用、1 秒窓）と `stK`（3 秒スロット保存用）を別の判定器として `AccelManager` に同居させ、世界座標へ変換した加速度の水平成分から `k = |average(h_i)|`、`ratio = sigma / k` を求める方式を採用している。
- `trK=ON` への遷移検知の瞬間に `LoggingService` へ callback し、3 秒 base cycle を待たずに `requestLocationUpdates()` を burst (`interval=500ms`、`duration=10秒`、`maxUpdates=10`) で開始する。
- burst の `START / CANDIDATE / ACCEPT / REJECT / STOP / SKIPPED_COOLDOWN / UNAVAILABLE / SECURITY_ERROR` を debug event で記録し、空欄区間が「要求未発行」か「低精度棄却」かを後から区別できる。

### 2.2 GPS 取得間隔の 3 段制御
- `STK4` 中: `gpsKMinMs=2秒`
- `W1` 中: `gpsWalkIntervalMs=5秒`
- それ以外: `gpsStableInitialMs=5秒` から `gpsStretchStepMs=5秒` 刻みで `gpsStretchMaxMs=30秒` まで線形に伸ばす
- 伸長カウンタは「直前スロットの主記録に位置があり、`gpsAccuracy <= 80m`」の場合だけ進める。欠損または低精度ではカウンタを 0 に戻し、5 秒からやり直す。
- `trK=ON` または `STK4` 検知時はカウンタを 0 に戻し、加速から外れた直後も 5 秒から再開する。

### 2.3 定速領域の確定とバックフィル
- `(STK1 or STK2) and W2` の区間を `ConstantRegionTracker` が定速領域として保持し、終了時に直線近似で `STAY / CONSTANT_MOVE` を確定する。
- 確定後に `LoggingService` が当該区間の既存 `MotionSample` を読み直して `confirmedMode` と定速領域情報をバックフィルする。Room DB は即時、ローカル motion CSV は debounce / バッチ単位で書き換え（`CSV_MOTION_REWRITE_DEBOUNCE_MS=60秒`、`CSV_MOTION_REWRITE_MAX_PENDING_SAMPLES=500件`）。

### 2.4 W1 時の GPS 速度 / 歩数速度比較
- `FinalContextResolver` は `W1` の状態でも、`GpsSpeedTracker` の GPS 速度と `StepDeltaWindow * walkingStepLengthM / walkingSpeedWindowMs` の歩数推定速度を比較し、GPS 速度 ≥ `walkingVehicleSpeedThresholdKmh=10km/h`、または GPS 速度と歩数速度の差 ≥ `walkingGpsStepMismatchThresholdKmh=5km/h` のときは `VEHICLE` へ倒す。
- 直近 GPS 速度が取れず `CONSTANT_MOVE` の `ConstantRegionSpeedKmh` がある場合は、その速度を GPS 速度の代替として使う。

## 3. 大規模時系列データの扱い
- 標準バックアップ CSV は `Timestamp,Lat,Lon,Alt,PresRaw,PresQnh,StepsDelta,GpsAccuracy` 8 列で固定し、`# EVENT` コメントを混在させて運用イベントを残す。
- 状態イベントログ CSV は `motion_events_*` ファイルとして主記録から分離し、状態変化点だけを保存する。表示側は時刻順に前方補完して状態を復元する。
- 手動 export では Room から one-shot で全件を取得し、状態イベントログ側だけは `MotionSampleDao.getPageAfter()` で 1000 件ずつページ読み出しして CSV へ逐次書き込む（メモリ圧迫対策）。
- import 時は `Timestamp` 単位でマージし、競合解決は「上書き / 既存優先」をユーザー選択。スキップ件数と解析エラー件数は `ImportReport` でユーザーへ通知する。

## 4. 描画アルゴリズムの最適化

### 4.1 グラフ表示
- ホーム画面グラフは Compose の `draggable + transformable` で 1 本指ドラッグと 2 本指ピンチを分離し、可視窓は `transientWindowEndMs` で UI ローカルに保持して指を離した時だけ ViewModel と DB へ反映する。
- 履歴は `lookbackMin + windowEndMs` を `conflate()` した上で、`LogDao.getEntriesBetweenAscOnce()` / `MotionSampleDao.getBetweenOnce()` の one-shot クエリを順次実行し、3 秒ごとの最新追従で履歴クエリがキャンセルされ続ける状態を避ける。

### 4.2 地図表示
- `STAY` を境界に分けた移動チャンクごとにローカルメートル投影 + tension 付き Catmull-Rom スプライン (tension=0.55) で補間し、`>` 進行方向マーカーは画面上の折れ線距離に沿って一定間隔でサンプリングする。
- `STAY` 区間は保存済み `ConstantRegionStayLat/Lon` を使って 1 点へ畳み、`CONSTANT_MOVE` は記録 GPS 点列をそのまま描く（直線近似 `g(t)` 上への再配置はしない）。
- 表示用 GPS 系列だけを対象に `復帰バースト / 偽クラスタ滞在 / 停止標準化 / GPS 平準化` の 4 段補正を適用し、Room と CSV の raw GPS は変更しない。

## 5. 総評
新ハイブリッド方式は、旧 `MovementDetector` の確信度積分方式を置き換え、`trK` で GPS 即時取得を、`stK` で 3 秒主記録の正式な加速度状態を、定速領域でモード確定後のバックフィルを、それぞれ責務分離した構成になっている。`MotionStateParamsProvider.current()` から閾値・時間・距離を一元参照することで、調整の窓口は 1 ファイルに集約されている。ローカル DB 正規化と SAF 経由の手動 export の両立、Room と CSV の段階的なまとめ書き出し、表示用補正と保存値の分離など、データの完全性と応答性のバランスを優先する設計が一貫している。
