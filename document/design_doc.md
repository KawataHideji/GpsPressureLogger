# 設計書：GpsPressureLogger

最終更新: 2026-04-16

## 1. モジュール構成

### 1.1 Service Layer
- `LoggingService`
  - センサー登録、GPS 取得、定期記録、通知更新を担当する。
  - 歩数センサーの累積値から `StepsDelta` を計算し、1 レコードを組み立てる。
  - DB 保存後にローカル CSV へ追記し、必要に応じてウィジェット更新を行う。
  - 3 秒ごとにモード判定前の補助指標も生成し、主記録とは別系統で保持する。
  - 調査用に `onStartCommand()` 到達ログと、サービス開始後の初回記録成功ログを出す。
  - 停止中に新しい GPS コールバックが来ない場合でも、`lastLocation` / `getCurrentLocation()` を使った bootstrap と直近有効 GPS の再利用で、再起動直後や 03:00 境界直後の GPS 固着を避ける。
- `BootReceiver`
  - `ACTION_BOOT_COMPLETED` と `ACTION_MY_PACKAGE_REPLACED` を受け、記録サービス再開の入口になる。
  - `ACTION_SHUTDOWN` は再開しないが、終了トリガ調査用にデバッグログへ残す。
  - APK 更新で foreground service が落ちた後も、利用者が手動でアプリを開かなくても復帰できるようにする。

### 1.2 Data Layer
- `LogEntry`
  - 端末内正規データの 1 行を表す Room entity。
  - `timestamp` を一意キーとし、各観測列は nullable とする。
- `MotionSample`
  - モード判定用の補助指標と、新方式の確定状態 1 行を表す entity。
  - `timestamp` を一意キーとし、旧互換の `AccelStddev3s` / `AccelMad3s` に加えて、`KStatus`、`KAvg`、`KVariance`、`WStatus`、`GpsIntervalMs`、`ConfirmedMode` を保持する。
- `LogDao`
  - 単件保存、バッチ保存、`timestamp` 単位の検索、期間取得を担当する。
- `MotionSampleDao`
  - 3 秒ごとの補助指標保存、期間取得、import / export 用の取得を担当する。
- `AppDatabase`
  - Room DB の生成を担う。
  - 主記録と補助指標の両方を保持する。
  - migration で destructive migration を使わない。
- `SettingsRepository`
  - DataStore による設定値管理を行う。
  - 起動時歩数修復の実行済みバージョンと、詳細デバッグログ ON/OFF を保持する。
  - `lookbackMin` の既定値は `3d` とし、UI は `6h / 12h / 1d / 2d / 3d / 1w` の候補値だけを選択できるようにする。
  - widget 更新間隔は pressure / map とも `30秒..300秒` に保存時点で clamp する。

### 1.3 Utility Layer
- `ExportUtil`
  - 標準 CSV の export / import、ローカル CSV 追記、デバッグログ出力を担当する。
  - import は単一 CSV URI を読み、`Timestamp` 単位で既存 DB と照合し、衝突時は上書き方針に従ってマージする。
  - import 対象は `gps_pressure_full_backup*.csv` かつ標準ヘッダ一致ファイルに限定する。
  - CSV import / ローカルCSV再読込では、`#` コメント行と空行を読み飛ばす。
  - スキップファイルと解析エラーを `ImportReport` とデバッグログへ集約する。
  - 日常ログ保存先は `getExternalFilesDir(null)` を優先し、`GpsPressureLogger/logs/` と `GpsPressureLogger/debug/` を使い分ける。
  - 日次ログファイル名は `GpsUtil.getLoggingStart()` を使って 03:00 区切りで決める。
  - `writeDebugLog()` の重要イベントは、debug テキストログに加えて日次 CSV に `# EVENT <timestamp> ...` 形式で追記する。
  - `writeEntriesToUri()` は日次 CSV に残っている `# EVENT` コメントを時系列順に手動バックアップ CSV へも反映する。
  - 補助センサー判定ログは `motion_metrics_yyyyMMdd.csv` として主記録とは別に追記する。
  - 起動時の歩数補完では、旧形式 `Steps` と新形式 `StepsDelta` の両方をローカル CSV から読み取って DB へ反映する。
  - `writeDebugLog()` はローカル debug ログへ追記し、設定が有効なら選択済みログファイル URI にも非同期追記する。
  - `writeVerboseDebugLog()` は高頻度ログ専用で、`verboseDebugLogEnabled` が ON の時だけ記録する。
  - `logDatabaseSummary()` は DB の最古/最新、3時区切り日ごとの件数、0時-2時59分ウィンドウ件数をデバッグログへ出力する。
- `GraphUtil`
  - `StepsDelta` を積み上げて歩数表示用の系列を生成する。
  - 欠損値は `NaN` として扱い、描画時に線を分断する。
  - 旧 `legacyStepCount` しか持たない端末内データでは、前行との差分を導出して歩数表示へフォールバックする。
  - `resolveLatestMetricValues()` で、最新行が欠損していても履歴から直近有効な標高・生気圧・補正気圧を復元する。
  - `getProcessedSeriesForWindow()` で、指定した表示窓を左端から右端まで固定した系列を app / widget 共通で生成する。
  - `MotionSample` から再構成したモード列で歩数系列を 4 本に分割し、`DEVICE_STILL=黒 / STOPPED=グレー / WALKING=青 / VEHICLE=赤` で描く。
  - 補正気圧系列は白で描く。
  - 現在歩数表示の共通色と、00:00 縦線・日付ラベルの色・時刻計算を一元管理する。
- `GpsUtil`
  - GPS 外れ値除去、地図表示用の共通日次抽出、停留点集約を担当する。
  - 地図画面向けに、停止点を保ったまま移動区間だけを軽く平準化する `smoothTrackForDisplay()` を提供する。
  - 地図表示向けに、停止候補区間の GPS ブレを目標半径以内へ抑える `normalizeStopsForDisplay()` を提供する。
  - `buildDisplayPolyline()` で、viewer を基準にした折れ線生成を app / widget 共通関数として提供する。
  - `buildDisplayPolyline()` は各点へ `displayMode` を付与し、viewer と同じモード別配色の折れ線を app / widget 共通で生成する。
  - `computeDirectionArrowMarkers()` で、折れ線の局所接線方向から `>` 風の進行方向マーカー位置と向きを共通計算する。
  - `normalizeStopsForDisplay()` は表示専用関数であり、取得データや Room 保存値は変更しない。
  - 停止標準化は `DEVICE_STILL` と `STOPPED` で別パラメータを持ち、完全停止はより強く、停止はより弱く補正する。
  - 停止標準化の半径・最小継続時間・最小点数は `StopNormalizationParams` で一元管理し、後から調整できるようにする。
  - 開始点・現在点・滞在点のマーカーサイズ規則を `MarkerStyle` と各 style 関数で共通化し、`MarkerSurface` ごとの倍率差もここで一元管理する。
- `MovementDetector`
  - 旧方式の状態判定クラス。
  - 本体記録の状態管理には使わず、古い `MotionSample` に `confirmedMode` が無い場合の表示再構成 fallback と、既存表示コードの `Mode` 型互換のために残す。
- `MotionStateParams`
  - 新しいハイブリッド状態管理のパラメータを一元管理する。
  - `k-status` 閾値、`w-status` 判定窓、GPS 取得間隔、定速領域判定値を保持する。
  - 状態管理部の各クラスは直値を持たず、`MotionStateParamsProvider.current()` から現在値を参照する。
- `MotionStateManager`
  - 新しい状態管理方式の司令塔。
  - `KStatusDetector`、`WStatusDetector`、`GpsSamplingPolicy`、`ConstantRegionTracker`、`FinalContextResolver` を束ねる。
  - `LoggingService` からは専用 single-thread dispatcher 経由でのみ呼び出し、初期化後の状態更新を 1 本のイベント列に閉じ込める。
- `KStatusDetector`
  - `TYPE_LINEAR_ACCELERATION` 相当の重力除去済み 3 軸加速度から合成加速度ノルムを作る。
  - 解析窓内の平均 `Avg` と分散 `Var` から `K4 / K2_K3 / K1` を判定する。
  - `On-delay` / `Off-delay` によりチャタリングを抑える。
- `WStatusDetector`
  - 判定窓内の歩数増分から `W1 / W2` を判定する。
- `GpsSamplingPolicy`
  - `k-status` と `w-status` から GPS 取得間隔と即時取得要否を決める。
- `ConstantRegionTracker`
  - `(K1 or K2_K3) and W2` の区間を定速領域として管理する。
  - 区間内 GPS 点列へ直線近似を行い、区間終了時に `STAY / CONSTANT_MOVE` を確定する。
- `FinalContextResolver`
  - `k-status`、`w-status`、定速領域結果を既存の `DEVICE_STILL / STOPPED / WALKING / VEHICLE` へ変換する。

### 1.5 Converter Layer (Windows / Python)
- `log_converter/step1_extract.py`
  - Barograph CSV と StepWalk DB から raw JSONL を抽出する。
  - JST 固定でタイムスタンプを生成する。
- `log_converter/step2_convert.py`
  - raw JSONL 群を `Timestamp` 単位でマージし、標準 CSV を生成する。
  - 時刻丸めは行わず、欠損は空欄、歩数は `StepsDelta` のまま保持する。
- `window_viewer/step3_visualize.py`
  - 標準 CSV と、`# EVENT` コメントを含む手動バックアップ CSV を読み、気圧・高度・歩数累積ビューと地図を可視化する。
  - `#` コメント行を無視し、`Lat=0 / Lon=0` の無効 GPS 点は地図から除外する。
  - 既定では `C:\MyDrive\android` の最新バックアップを最優先し、最後の長い空白以降の最新セッションだけを表示する。
  - ビューア内では `補正あり / なし` を切り替えられるが、通常の確認で使う `補正あり` は Android アプリと同じ固定描画を表す。
  - `補正あり` のグラフは、Android と同じ考え方で外れ値除去、30 秒間隔の線形補間、移動平均平滑化を適用する。
  - `補正あり` の地図は、Android と同じ固定順序 `復帰バースト -> 偽クラスタ滞在 -> 停止標準化 -> GPS 平準化` を適用する。
  - viewer は読み込んだセッションから日付キーを抽出し、`日付` プルダウンで日別モードデータへ切り替える。
- viewer は `停止偏差` グラフを持ち、停止区間中心からの raw 偏差と、各点の補正量 (`raw -> corrected` の移動距離) を比較できる。
  - viewer の `停止偏差` グラフは、全期間表示に加えて大きな偏差ピーク周辺へフォーカスできる。
- viewer の地図は `地図時間` で `全日 / 偏差フォーカス連動` を切り替えられ、偏差フォーカスで選んだ時刻帯だけの軌跡を確認できる。
- viewer / Android の地図には、直線区間だけでなく曲線区間にも `>` 風の進行方向マーカーを差し込み、長い移動区間の向きを読み取りやすくする。
- 進行方向マーカーは、始点・終点付近を避け、前後数点の局所接線方向で固定サイズ描画する。
- 進行方向マーカーの回転角は、GPS 方位角（北=0度, 時計回り）を `>` 文字の基準向き（右向き=0度）へ合わせるため `bearing - 90度` を使う。
- 進行方向マーカーの色は折れ線と同じモード色を使う。
- 進行方向マーカーの密度は控えめにし、既定では長い移動区間でもおよそ従来の 1/3 程度の数に抑える。
- viewer の停止標準化は、CSV に MotionSample が無い前提で `# EVENT` の `MODE_CONFIRMED` を読み、Android の確定モード遷移と同じ考え方のタイムラインを再構成して `DEVICE_STILL / STOPPED` 区間へ適用する。
- viewer の停止標準化前段では、`stepsDelta=0` かつ `displayMode != VEHICLE` の GPS 点列に対して、`return burst` も検知する。これは大ジャンプ後に数点から数十点で元クラスタ近傍へ戻る短時間バーストで、戻り判定距離は `90m` を初期値とする。
- 検知した `return burst` は、ジャンプ直前点と復帰アンカーの間を時間順に補間して全置換し、`returnBurstFixed` として後段の停止標準化では再補正しない。
- viewer の停止標準化前段では、`stepsDelta=0` かつ `displayMode != VEHICLE` の GPS 点列に対して、短時間だけ別クラスタへ飛んで前後クラスタへ戻る `cluster hop stay` を検知する。
- `cluster hop stay` は、短い連続点群が狭い半径内に収まり、前後アンカー同士は近いが、その点群中心は前後アンカーから十分離れている場合に成立する。
- 検知した `cluster hop stay` は、点群全体を前後アンカー中心の中点へ全置換してから停止標準化へ渡し、その点群には後段の弱補正を適用しない。
- viewer の停止標準化は、`MODE_CONFIRMED` の連続 `DEVICE_STILL / STOPPED` 区間をそのまま対象にする。
- viewer の停止標準化は、停止区間ごとの中央値中心を基準に偏差列を作り、短いバースト塊を前後アンカーで補修したうえで、局所中央値からの残差が大きい点だけを補正する偏差列ベース方式を採用する。
  - viewer の停止標準化は、偏差の向きは保ちつつ中心からの半径だけを圧縮する後段を持ち、停止中の大きい尾をさらに抑える。
  - viewer の停止標準化は、なめらかな低周波のうねりは残しつつ、停止中スパイクとランダム暴れを優先的に落とす。
  - viewer の既定バースト塊長は `DEVICE_STILL=12点`, `STOPPED=10点` を上限とし、戻り切るまで同じ塊として扱いやすくする。
  - viewer の半径圧縮は `DEVICE_STILL=(softStart=2m, keepRatio=0.28, hardCap=2m)`, `STOPPED=(softStart=10m, keepRatio=0.45, hardCap=24m)` を既定とする。
  - viewer の停止標準化パラメータは `DEVICE_STILL` と `STOPPED` で分け、完全停止と停止の補正強度を別に調整する。
  - 既定では `window_viewer/merged_dashboard.html` を出力し、`window_viewer/README.md` に起動方法を記載する。
  - 表示アルゴリズムを app 側で変更した場合は、viewer の `補正あり` にも同じ変更を反映して整合を維持する。

### 1.3.1 Android 表示用の停止補正仕様
- viewer で試した `復帰バースト / 偽クラスタ滞在 / 停止標準化` は、**Android の表示用 GPS 系列へ反映済み** とする。
- Android 側での適用対象は、`MapScreen` と `MapWidgetReceiver` が描画する **表示用 GPS 系列のみ** とする。
- Android 側では次の 4 段で補正する。
  - 1. `MODE_CONFIRMED` を使って `DEVICE_STILL / STOPPED` の停止区間候補を得る。
  - 2. 停止区間候補の前段で、`stepsDelta=0` かつ `displayMode != VEHICLE` を満たす点列に対して `return burst` を検知し、ジャンプ直前点と復帰アンカーの補間で全置換する。
  - 3. 同じく前段で `cluster hop stay` を検知し、前後アンカー中心の中点へ全置換する。
  - 4. 前段で fix 済みでない停止区間にだけ、偏差列ベースの停止標準化を適用する。
- Android 側では `returnBurstFixed` / `clusterHopFixed` に相当する内部フラグを持ち、前段で補正した点は後段の弱補正で再び触らない。
- Android 側の初期パラメータは viewer と揃えて開始する。
  - `return burst`: `enterDistance=180m`, `returnDistance=90m`, `peakDistance=250m`, `maxPoints=30`, `maxDuration=8分`
  - `cluster hop stay`: `radius=20m`, `minPoints=5`, `maxPoints=30`, `distance=180m`, `returnDistance=90m`, `anchorWindow=4`
  - `DEVICE_STILL`: `softStart=2m`, `keepRatio=0.28`, `hardCap=2m`
  - `STOPPED`: `softStart=10m`, `keepRatio=0.45`, `hardCap=24m`
- Android 側でも、viewer と同様に **取得値・Room 保存値・CSV 出力値は変更しない**。表示用に生成した補正済み系列だけを地図描画へ渡す。
- Android 側の回帰ケースは、viewer で効果確認した代表ケースをそのまま使う。
  - `2026-04-12 10:50-11:30`
  - `11:03`, `11:10`, `11:13`, `11:15`, `11:21`, `11:28`
- Android 側の折れ線は、app / widget とも `GpsUtil.buildDisplayPolyline()` を通し、viewer の `GPS 平準化 ON` に合わせた表示へ揃える。
- widget の更新周期は `LoggingService.updateWidgetsIfDue()` が管理し、前回更新時刻との差ではなく「次回予定時刻」に達したかで判定する。

### 1.4 UI Layer
- `HomeViewModel`
  - 表示期間に応じた履歴取得、今日の歩数計算、最新値抽出を担当する。
  - グラフの表示終端時刻を持ち、左スワイプ時の古い時間帯読込を仲介する。
- `HomeScreen`
  - グラフのジェスチャを横方向ズーム / 時間移動に制限する。
  - 通常のドラッグで表示窓を古い時刻 / 新しい時刻へ移動し、ピンチインで可視期間を広げる。
  - 1 本指ドラッグは `draggable`、2 本指ピンチは `transformable` へ役割分担し、競合を避ける。
  - 調査時はホームグラフのサイズ、ドラッグ、ズームのイベントをデバッグログへ出力する。
  - 歩数グラフはモード色ごとの分割系列を描き、単一色の累積線にはしない。
- `SettingsViewModel`
  - export / import 操作と設定変更を仲介する。
  - import 対象 CSV の選択、Google ドライブ向けデバッグログ送信の ON/OFF、ログファイル手動同期を仲介する。
  - 主記録 CSV と補助センサー判定ログ CSV の両方について、選択 URI の保持と手動 import / export を仲介する。
  - 手動の DB サマリログ出力を仲介する。
- `SettingsScreen`
  - 主記録周期は 3 秒固定として表示し、旧来の記録間隔スライダは使わない。
  - `表示する期間` は固定候補の単一選択 UI を使い、既定は `3d` とする。
  - widget 更新間隔のスライダ範囲は pressure / map とも `30秒..300秒` に統一する。
- `MapViewModel`
  - 03:00 区切り日単位の地図表示データを `GpsUtil.prepareMapEntries()` で共通前処理して作る。
  - 同日の `MotionSample` を合わせて読み、`GpsUtil.normalizeStopsForDisplay(entries, motionSamples)` を通した表示用系列を `entries` として提供する。
  - 前日 / 翌日移動では、位置データが存在する日だけへ遷移する。
- `MapScreen`
  - 地図 overlay の再描画時に、同一日では自動ズームし直さない。
  - `MapViewModel` が返す表示補正済み系列をそのまま描画に使う。
  - 停止区間では `復帰バースト -> 偽クラスタ滞在 -> 偏差列ベース停止補正` を通した表示用 GPS 系列を使う。
  - 折れ線描画では `GpsUtil.buildDisplayPolyline()` を使い、viewer の `GPS 平準化 ON` と同じ考え方の連続折れ線系列を描く。停止マーカーや開始・現在地マーカーは生の位置を維持する。
  - 折れ線色は `displayMode` に応じて `黒 / グレー / 青 / 赤` を使い、旧グラデーションは使わない。
- 進行方向マーカーは `GpsUtil.computeDirectionArrowMarkers()` の結果を使って `>` を描く。
- Android 側の `>` 進行方向マーカーは、折れ線色の文字に白い縁取りを付けて地図上で見失いにくくする。
  - マーカーサイズは widget と同じ共通規則を使い、`MarkerSurface.APP_MAP` の倍率で体感サイズを揃える。
  - marker icon の `BitmapDrawable` は `Resources` 付きで生成し、density 差による見え方のズレを防ぐ。
  - 現在点マーカーは `isHollow = true` を使い、白地に青リングの見え方で開始点と対になるように描く。
  - 現在点の青リングは開始点の赤リングと同じ線幅パラメータを使う。
- `MapWidgetReceiver`
  - アプリ地図と同じ日次抽出、GPS 外れ値除去、停留点セグメント分割方針で軌跡を描く。
  - 同日の `MotionSample` を読み、`GpsUtil.normalizeStopsForDisplay(entries, motionSamples)` で本体地図と同じ表示補正を適用する。
  - 折れ線描画では `GpsUtil.buildDisplayPolyline()` を使い、本体地図と同じ viewer 準拠の連続折れ線系列を適用する。
  - 折れ線色は `displayMode` に応じて `黒 / グレー / 青 / 赤` を使い、viewer と同じ配色へ揃える。
- 進行方向マーカーは `GpsUtil.computeDirectionArrowMarkers()` を使って本体地図と同じ配置規則で `>` を描く。
- ウィジェット側の `>` 進行方向マーカーも、本体地図と同じく白い縁取り付きの glyph を描く。
  - マーカーサイズは `GpsUtil` の共通 style を使い、`MarkerSurface.WIDGET` の倍率で描く。
  - `widget_map_layout.xml` は `fitXY` で生成済み Bitmap をそのまま表示する。
  - 描画時に widget サイズ、前処理前後件数、地図 bounds、最新タイムスタンプをデバッグログへ出力する。
  - 現在点マーカーは白地に青リングの hollow style で app と同じ意味になるように描く。
  - ウィジェット更新は `LoggingService` の周期更新で行い、AppWidgetProvider XML の `updatePeriodMillis` は使わない。
  - 現在点の青リングは app と同じ線幅パラメータを使う。

## 2. API / データフロー

### 2.1 記録フロー
1. `LoggingService` がセンサー値と GPS 値を保持する。
2. 3 秒スロットごとに `LogEntry` を生成する。
3. `LogDao.insertReplace()` で DB に即時保存する。
4. 同じ `LogEntry` をファイル書き出し用キューへ enqueue する。
5. キュー件数が閾値に達した時、または強制フラッシュ契機で、日次 CSV へまとめ書きする。
6. 同じ 3 秒ループで `MotionSample` を生成する。
7. `MotionSampleDao` で DB に即時保存し、補助ログ CSV 用キューへ enqueue する。
8. 起動時の旧歩数修復は `SettingsRepository.stepRepairVersion` を見て一度だけ実行する。
9. 停止中に GPS プールが空でも、直近有効 GPS がまだ新しければ、その値をスロット時刻へ延長して使う。
10. 停止中に再利用できる GPS も古い場合は、`lastLocation` と `getCurrentLocation()` で bootstrap 取得を試みる。

### 2.2 import フロー
1. `SettingsViewModel.triggerImport()` が選択済み CSV ファイル URI を取得する。
2. `ExportUtil.importFromUriWithProgress()` がその CSV を読み込む。
3. `streamImportStandardCsv()` が標準 CSV を `LogEntry` に変換する。
4. `flushBatch()` が DB 既存行と `Timestamp` 単位でマージし、`insertAllReplace()` する。

### 2.3 export フロー
1. `SettingsViewModel.exportToUri()` が DB 全件を取得する。
2. `ExportUtil.writeEntriesToUri()` が標準 CSV として昇順出力する。

### 2.4 補助センサー判定ログ export / import フロー
1. アプリは補助センサー判定ログを主記録とは別ファイルとして扱う。
2. export 時は `MotionSample` を専用 CSV ヘッダで昇順出力する。
3. import 時は `timestamp` 単位で `motion_samples` に upsert する。

## 3. アルゴリズム

### 3.1 GPS モード切替

#### 3.1.1 新ハイブリッド状態管理

`LoggingService` は旧 `MovementDetector.update()` ではなく、`MotionStateManager.updateBaseCycle()` の結果を正式な状態として使う。`MotionStateManager` は専用 single-thread dispatcher 上でだけ更新し、センサー・GPS・歩数・3 秒 base cycle の各イベントはこの dispatcher に投入する。

- `KStatusDetector` は `TYPE_LINEAR_ACCELERATION` 相当の重力除去済み加速度を受け取り、直近 `kWindowMs` の合成加速度ノルムから平均 `Avg` と分散 `Var` を計算する。
- k-status は `Avg > k4AvgThreshold` なら `K4`、それ以外で `Var > k2k3VarThreshold` なら `K2_K3`、どちらでもなければ `K1` とする。
- k-status の確定には `kOnDelayMs` と `kOffDelayMs` を使い、短時間で状態が行き来するチャタリングを抑える。初期値では `K4` への遷移にも `kOnDelayMs=500ms` を要求し、段差などの単発ノイズによる誤割り込みを抑える。
- `WStatusDetector` は直近 `wWindowMs` の歩数増分合計が `wStepDeltaThreshold` を超えたら `W1`、それ以外を `W2` とする。
- `GpsSamplingPolicy` は `K4` を最優先して即時 GPS と `gpsKMinMs` を返す。`W1` は `gpsWalkIntervalMs`、`W2` は `gpsKMinMs` から `gpsStretchStepMs` ずつ伸ばし、最大 `gpsStretchMaxMs` までに抑える。
- `ConstantRegionTracker` は `K4` ではなく、かつ `W2` の区間を定速領域として扱う。区間継続中も base cycle ごとに暫定直線近似 `g(t)` を更新し、暫定 `STAY / CONSTANT_MOVE` を `MotionSample.constantRegionKind` に保存する。
- `ConstantRegionTracker` は直線近似の前に、点群重心からの距離が中央値 + MAD ベースしきい値を大きく超える孤立 GPS 点を棄却する。これにより stay point と移動ベクトルが単発飛び点に引っ張られるのを避ける。
- 区間終了時は区間全体の外れ値棄却済み GPS 点を時刻に対して直線近似し、速度が `staySpeedThresholdKmh` 以下なら `STAY`、超えるなら `CONSTANT_MOVE` として確定する。
- `FinalContextResolver` は `K4` または `CONSTANT_MOVE` を `VEHICLE`、`W1` を `WALKING`、`STAY + K1` を `DEVICE_STILL`、`STAY + K2_K3` を `STOPPED` へ変換する。区間継続中は暫定 `ConstantRegionKind` も参照し、現時点の最善推定で表示を更新できるようにする。
- すべての閾値・時間・距離は `MotionStateParamsProvider.current()` から取得し、状態管理クラス内に調整値を直接埋め込まない。
- 初期パラメータは `baseCycle=3s`, `kWindow=1s`, `k4Avg=0.70`, `k2k3Var=0.01`, `kOnDelay=500ms`, `kOffDelay=1000ms`, `wWindow=9s`, `gps=5s/10s/15s`, `staySpeed=2km/h`, `constantRegionMin=15s`, `outlierMad=4.0`, `outlierMin=50m` とする。
- 確定モードは `MotionSample.confirmedMode` に保存し、地図・歩数グラフは新 CSV ではこの値を優先する。旧 CSV で `confirmedMode` が無い場合のみ旧 `MovementDetector` 互換ロジックで表示モードを再構成する。

#### 3.1.2 旧 `MovementDetector` 互換方式

- 旧 `MovementDetector` は本体記録では使わない。
- 旧補助ログ CSV のように `MotionSample.confirmedMode` が無いデータを表示するときだけ、標準偏差ベース判定を用いた互換 fallback として使う。
- 旧方式の GPS 更新間隔選択、確信度積分、徒歩強化ロジックは現行の記録制御には使わない。
- 旧方式互換 fallback は `GpsUtil.inferModeStates()` 内に閉じ込め、`confirmedMode` が保存されている新ログでは必ず `confirmedMode` を優先する。

モードの意味:

- `DEVICE_STILL`
  - 携帯が机や棚などに置かれ、ほぼ触られていない完全停止状態
- `STOPPED`
  - 利用者が携帯を保持している可能性はあるが、歩行・高速移動はしていない状態
- `WALKING`
  - 徒歩または低速移動
- `VEHICLE`
  - 自転車、車、電車、飛行機などの高速移動

新方式初期パラメータ:

- `baseCycleMs = 3000`
- `kWindowMs = 1000`
- `k4AvgThreshold = 0.70`
- `k2k3VarThreshold = 0.01`
- `kOnDelayMs = 500`
- `kOffDelayMs = 1000`
- `wWindowMs = 9000`
- `wStepDeltaThreshold = 0`
- `gpsKMinMs = 5000`
- `gpsWalkIntervalMs = 5000`
- `gpsStretchStepMs = 5000`
- `gpsStretchMaxMs = 15000`
- `staySpeedThresholdKmh = 2.0`
- `constantRegionMinDurationMs = 15000`
- `stayPointMaxRadiusM = 20.0`
- `constantRegionOutlierMadMultiplier = 4.0`
- `constantRegionOutlierMinThresholdM = 50.0`

記録・フラッシュ方針:

- GPS / 気圧 / 歩数の取得周期と、DB / CSV への永続化周期は分離する。
- 主記録レコードと `motion_samples` レコードの論理生成周期は 3 秒を基準とする。
- 生成済みレコードはメモリバッファへ積み、日次 CSV などファイル系ストアへのフラッシュは既定 5 分ごとにまとめて行う。
- 既定の flush 閾値は 100 件で、3 秒主記録 기준ではおよそ 5 分相当になる。
- GPS は各 3 秒スロットごとに一度だけ主記録へ集約し、GPS / 高度の欠損を許容する。
- GPS 集約では、直前に採用済みの GPS を `before` と呼ぶ。
- 移動中（`WALKING` / `VEHICLE`）の GPS 集約規則:
  - スロット内プール 0 件: GPS / 高度は欠損
  - スロット内プール 1 件: その 1 件を採用
  - スロット内プール 2 件以上: `before` とプール内 GPS 群から 3 次スプライン補間を構成し、スロット時刻における `lat / lon / alt` を採用
- 完全停止（`DEVICE_STILL`）の GPS 集約規則:
  - 直前スロットも `DEVICE_STILL` で、スロット内プール 1 件以上なら `before` を含めた平均位置・平均高度を採用
  - 直前スロットが `DEVICE_STILL` でなく、スロット内プール 1 件以上なら `before` を含めない平均位置・平均高度を採用
  - いずれもスロット内プール 0 件なら欠損
- 停止（`STOPPED`）の GPS 集約規則は、完全停止寄りの平均化ルールを使う。
- 実装上は、`STOPPED` で直前も `STOPPED` または `DEVICE_STILL` の場合に `before` を含める。
- 気圧は 3 秒スロット時点の最新保持値を採用し、まだセンサー値を一度も受けていない場合のみ欠損とする。
- 歩数は 3 秒区間の増分を採用し、初期基準未確定時は `0` で開始してよい。
- 強制フラッシュ契機は少なくとも次を含む。
  - 手動 export 開始前
  - サービス終了時に到達できた場合
  - `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 受信後の再開直前または直後の安全なタイミング
  - 日次ファイル切替（03:00 境界）
- 高速移動中に GPS が 5 秒周期で届くケースでも、受信イベントはスロット内プールへ一旦保持し、上記集約規則で主記録へ落とし込む。
- ファイル書き出し用キューは 100 件到達で flush する。
- キューはメモリ保護のため上限を持ち、ファイル書き出し失敗が続く異常時でも無制限には増やさない。
- 現行実装のキュー上限は 1000 件で、上限超過時は古い未書込分から捨ててローカル debug ログへ記録する。

### 3.2 歩数集計
- 保存時はセンサーの累積歩数から差分 `StepsDelta` を求める。
- 表示時は 03:00 区切り日単位で `StepsDelta` を累積して日歩数とグラフ系列を作る。

### 3.3 import マージ
- 同一 `Timestamp` の既存行と入力行を列ごとに比較する。
- 入力値が `null` の列は既存値を維持する。
- 両方に値がある列は `overwrite` が true なら入力値、false なら既存値を採用する。

### 3.4 QNH 算出条件
- `PresQnh` は `PressureUtil.calcQnh()` を使って算出する。
- ただし算出条件は「その時刻に GPS を有効記録できること」とし、古い GPS 高度では補正しない。

### 3.5 地図前処理
- `GpsUtil.prepareMapEntries()` が 03:00 区切り日の範囲抽出、位置付きレコードへの限定、時系列ソート、GPS 外れ値除去を一括で行う。
- GPS 外れ値除去は、精度閾値、前点からの速度閾値、前後 3 点比較による単発ジャンプ除去を組み合わせる。
- 高速移動中の横飛び点には、前後点を結ぶ線分からの横ずれ距離、余分な迂回距離、折れ角を用いた transient detour フィルタを適用する。
- さらに、短時間だけ別地点群へ飛んで戻るケースには、両端の大ジャンプで囲まれた短時間・少数点クラスタをまとめて除去する。
- 本体地図と地図ウィジェットは同じ前処理結果を使う。

### 3.6 グラフ閲覧
- `HomeViewModel` は `graphWindowEndMs` を持ち、表示終端時刻を現在から独立して管理する。
- `GraphUtil.getProcessedSeries()` は「現在時刻基準」ではなく、指定された `windowEndMs` と可視期間に基づいて系列を生成する。
- `HomeScreen` は Compose の `draggable` と `transformable` を使って 1 本指ドラッグと 2 本指ピンチを処理する。
- 横ドラッグ量は `GRAPH_DRAG_SENSITIVITY` で倍率調整し、左ドラッグで過去、右ドラッグで未来へ動く向きに統一する。
- ドラッグ中は UI ローカルの `transientWindowEndMs` を更新し、指を離した時だけ `HomeViewModel.shiftGraphWindowBy()` を呼んで DB 再読込を行う。
- `HomeViewModel.shiftGraphWindowBy()` には移動量だけでなく現在の可視期間も渡す。DB 保持範囲による下限丸めは設定表示期間ではなく現在の可視期間を使い、ピンチで短い期間に縮めた後も古い時刻へ辿れるようにする。
- グラフの系列生成と時間範囲ラベルは `transientWindowEndMs` を基準にし、ドラッグ中も表示が追従するようにする。
- ピンチ時は倍率だけを更新し、系列生成の重い計算は UI スレッドで事前実行しない。
- ピンチアウトで設定表示期間より広い可視期間が必要になるため、`HomeViewModel` は `GraphUtil.MAX_ZOOM_OUT_FACTOR` 倍までの履歴を先読みする。
- `HomeScreen` の最小 zoom 値も `GraphUtil.MAX_ZOOM_OUT_FACTOR` から計算し、UI の最大ピンチアウト量と DB 読込範囲を一致させる。
- 系列生成は `produceState + Dispatchers.Default` でバックグラウンド実行し、描画中のジェスチャを止めない。
- 一時的に系列生成が追いつかない場合に備え、`HomeScreen` は直前の正常系列を保持し、グラフ全体が消えないようにする。
- `HomeScreen` のグラフ前処理は時系列昇順を前提とするため、DB 取得結果が新しい順でも描画前に timestamp 昇順へ正規化する。
- グラフの X 軸は常に `graphStartMs..transientWindowEndMs` を基準に計算し、最初のデータ点時刻ではなく表示窓そのものを横比率の基準とする。
- ドラッグ中の一時的な表示終端は、現在ロード済み履歴の最古時刻では制限しない。指を離した後に `HomeViewModel.shiftGraphWindowBy()` が DB 全体の最古・最新時刻で安全に丸め、新しい範囲を読み直す。
- 歩数系列は可視窓ごとに先頭値を差し引いて 0 始まりへ正規化し、可視窓内の増分が読み取りやすい形で描画する。
- 歩数系列は `StepsDelta` の累積で作り、03:00 区切り日が切り替わった時点で累積を 0 に戻す（`GpsUtil.getLoggingStart(timestamp)` で日境界を判定）。
- ホーム画面とウィジェットのグラフは、設定された lookback を優先し、データが無い区間は空白として表示する（X 軸は `windowEndMs - lookbackMs` を開始とする）。
- 歩数グラフのモード色分けは、`MotionSample` から再構成した状態タイムラインを `GpsUtil.modesAt()` で 1 回だけ前進走査して各描画点へ割り当てる。各描画点ごとに状態履歴を先頭から再走査しない。
- `HomeScreen` は現在表示中の開始時刻 / 終了時刻をラベル表示し、時間移動結果を視覚的に確認できるようにする。
- `HomeViewModel.shiftGraphWindowBy()` は、保持データ期間が lookback より短い場合でも空区間にならないよう、表示終端時刻の下限を最新時刻側へ安全に丸める。
- 調査用のグラフ操作ログは `verboseDebugLogEnabled` が ON の時だけ出力する。

### 3.7 Windows viewer 独立アプリ

- Windows viewer のアルゴリズム本体は `window_viewer/step3_visualize.py` に集約する。
- 独立アプリ版は `pywebview` を使い、ネイティブウィンドウの中に生成済み dashboard HTML を表示するだけの薄いシェルとする。
- 主要モジュール:
  - `window_viewer/viewer_app.py`
    - エントリポイント
    - ウィンドウ生成
  - `window_viewer/desktop_app/state.py`
    - `step3_visualize.build_dashboard()` 呼び出し
    - 現在の CSV / view / correction 状態保持
  - `window_viewer/desktop_app/api.py`
    - JS bridge
    - 初期 state / 最新再読込 / CSV 手動選択
  - `window_viewer/desktop_app/shell.py`
    - ツールバー付きのコンテナ HTML
- 独立アプリ版では、補正ロジックを別実装しない。
- Android と整合させるべき表示アルゴリズムは `step3_visualize.py` 側へ集約し、desktop app 側はその結果を表示するだけにする。
- viewer の地図折れ線と歩数グラフは、`DisplayMode` ごとにセグメント分割して色分けする。
  - `DEVICE_STILL = 黒`
  - `STOPPED = グレー`
  - `WALKING = 青`
  - `VEHICLE = 赤`
- 地図の始点 / 終点マーカーは従来どおり独立描画とし、モード色分けの影響を受けない。

## 4. 現在の要注意点
- `PresQnh` は GPS 不在時に欠損になりうるため、表示側は欠損前提で扱う必要がある。

