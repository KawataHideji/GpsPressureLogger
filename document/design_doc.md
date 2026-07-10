# 設計書：GpsPressureLogger

最終更新: 2026-05-06

## 1. モジュール構成

### 1.1 Service Layer
- `LoggingService`
  - センサー登録、GPS 取得、定期記録、通知更新を担当する。
  - Android の `SensorEvent` 入口を担当し、加速度・歩数イベントは専用 dispatcher 経由で `MotionStateManager` へ渡す。
  - 加速度判定用に `TYPE_LINEAR_ACCELERATION`、可能なら `TYPE_ROTATION_VECTOR`、fallback として `TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD` を登録する。センサー登録と Android API 依存は service 側へ寄せ、判定器は Android の `SensorEvent` を直接知らない。
  - `MotionStateManager` が算出した `StepsDelta` と状態スナップショットを使って 1 レコードを組み立てる。
  - `trK`（加速度トリガー）が ON へ確定遷移した通知を受けた場合は、3 秒 base cycle を待たずに 1 回目の GPS 即時取得を行い、通常 GPS 更新も `gpsKMinMs` 周期へ張り替える。
  - DB 保存後にローカル CSV へ追記し、必要に応じてウィジェット更新を行う。
  - 3 秒ごとにモード判定前の補助指標を生成するが、永続化は状態変化点を中心に行い、主記録とは別系統で保持する。
  - 調査用に `onStartCommand()` 到達ログと、サービス開始後の初回記録成功ログを出す。
  - 停止中に新しい GPS コールバックが来ない場合でも、`lastLocation` / `getCurrentLocation()` を使った bootstrap と直近有効 GPS の再利用で、起動直後や 03:00 境界直後の GPS 固着を避ける。
  - ウィジェット周期は `LoggingService` が唯一の時間管理者として扱い、サービス起動直後または設定値変更直後は即時に 1 回描画し、その後は `slotTimestamp + interval` を次回予定時刻にする。
- `BootReceiver`
  - `ACTION_BOOT_COMPLETED` / `ACTION_MY_PACKAGE_REPLACED` / `ACTION_SHUTDOWN` を受信したという事実だけをデバッグログへ残す。記録サービスは自動再開しない。
  - Android のバックグラウンド foreground service 起動制限により、`MY_PACKAGE_REPLACED` 直後にここから `LoggingService` を起動するとクラッシュする端末があるため、自動再開は行わず、利用者が次にアプリを開いた時の `MainActivity.checkAndRequestPermissions()` 経由でのみサービスを起動する。
  - 端末終了・端末再起動・アプリ更新後の経路追跡は、ここで残す `SERVICE_RECEIVER` / `SERVICE_RESTART_SKIPPED` / `SERVICE_SHUTDOWN_TRIGGER` ログと、その後 `MainActivity` 経由で起動された場合に出る `SERVICE_START_COMMAND` / `SERVICE_FIRST_RECORD_SUCCESS` を組み合わせて行う。

### 1.2 Data Layer
- `LogEntry`
  - 端末内正規データの 1 行を表す Room entity。
  - `timestamp` を一意キーとし、各観測列は nullable とする。
- `MotionSample`
  - モード判定用の補助指標と、新方式の確定状態 1 行を表す entity。
  - `timestamp` を一意キーとし、旧互換の `AccelStddev3s` / `AccelMad3s` に加えて、`KStatus`、`StKAvg`、`StKRatio`、`TrKStatus`、`TrKAvg`、`TrKRatio`、`WStatus`、`GpsIntervalMs`、`ConfirmedMode` を保持する。Room のフィールド名は互換性のため `kAvg / kDirectionalityRatio / trKDirectionalityRatio` を維持するが、外部 CSV では `StKAvg / StKRatio / TrKRatio` として出力する。
- `LogDao`
  - 単件保存、バッチ保存、`timestamp` 単位の検索、期間取得を担当する。
- `MotionSampleDao`
  - 状態変化点の保存、期間取得、import / export 用の取得を担当する。旧 3 秒サンプル形式も import / viewer 互換として扱う。
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
  - 状態イベントログは `motion_events_yyyyMMdd.csv` として主記録とは別に追記する。旧 `motion_metrics_yyyyMMdd.csv` は互換入力として残す。
  - 手動 export は `openOutputStream()` の成否だけで成功扱いせず、close 後に出力先ドキュメントサイズが 0 byte でないことまで確認する。0 byte の場合は失敗扱いとし、可能ならその場で削除する。
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
  - `getProcessedSeriesForWindow()` は表示窓の開始前 30 分だけを補間文脈として使い、表示窓と同じ長さの過去範囲を追加処理しない。外れ値除去とモード列再構成も、この絞り込み後のデータだけに対して行う。
  - 標高・気圧・補正気圧の表示系列は、target time と timestamp 昇順の入力を 1 回だけ前進走査して線形補間する。系列ごとに補間器配列を再構築しない。
  - 歩数系列はスプライン補間せず、各時刻までの累積値を保持する階段状系列として生成する。
  - `MotionSample` から再構成したモード列で歩数系列を 4 本に分割し、`DEVICE_STILL=黒 / STOPPED=グレー / WALKING=青 / VEHICLE=赤` で描く。
  - 補正気圧系列は白で描く。
  - 現在歩数表示の共通色と、00:00 縦線・日付ラベルの色・時刻計算を一元管理する。
- `GpsUtil`
  - GPS 外れ値除去、地図表示用の共通日次抽出、停留点集約を担当する。
  - 地図画面向けに、停止点を保ったまま移動区間だけを軽く平準化し、tension 付き Catmull-Rom 系スプラインで描画用点列へ展開する。
  - 地図表示向けに、停止候補区間の GPS ブレを目標半径以内へ抑える `normalizeStopsForDisplay()` を提供する。
  - `buildDisplayPolyline()` で、viewer を基準にした折れ線生成を app / widget 共通関数として提供する。
  - `buildDisplayPolyline()` は各点へ `displayMode` を付与し、viewer と同じモード別配色の折れ線を app / widget 共通で生成する。停止代表点は移動チャンクの境界として扱い、徒歩と高速移動は同じ移動チャンク内で連続スプライン化する。
  - `buildStateLabels()` は `stK / W / ConstantRegionKind` の遷移点を、`buildTrkLabels()` は `trK4` の遷移点を、それぞれ GPS 座標へひも付けたラベル配列として返す。
  - `computeDirectionArrowMarkers()` で、折れ線の局所接線方向から `>` 風の進行方向マーカー位置と向きを共通計算する。方向マーカーも `STAY` 点をまたがず、移動チャンクごとに計算する。
  - アプリ地図と地図ウィジェットでは、方向マーカー密度の決定には screen px 距離ベースの `computeDirectionArrowMarkersOnScreen()` を使う。
  - 地図ウィジェット向けには `computeDirectionArrowMarkersOnScreen()` を別に持ち、widget bitmap 上の screen px 距離で方向マーカーの間隔・最小セグメント長・始終端スキップ距離を決める。
  - `normalizeStopsForDisplay()` は表示専用関数であり、取得データや Room 保存値は変更しない。
  - 停止標準化は `DEVICE_STILL` と `STOPPED` で別パラメータを持ち、完全停止はより強く、停止はより弱く補正する。
  - 停止標準化の半径・最小継続時間・最小点数は `StopNormalizationParams` で一元管理し、後から調整できるようにする。
  - 開始点・現在点・滞在点のマーカーサイズ規則を `MarkerStyle` と各 style 関数で共通化し、`MarkerSurface` ごとの倍率差もここで一元管理する。
  - 地図ウィジェット向けの `widget*MarkerStyle()` と `widgetDirectionArrow*()` は、地図縮尺ではなく widget bitmap 上の固定 screen size を返す。
- `MovementDetector`
  - 旧方式の状態判定クラス。
  - 本体記録の状態管理には使わず、古い `MotionSample` に `confirmedMode` が無い場合の表示再構成 fallback と、既存表示コードの `Mode` 型互換のために残す。
- `MotionStateParams`
  - 新しいハイブリッド状態管理のパラメータを一元管理する。
  - `trK`（GPS 即時起動用の加速度トリガー）、`stK`（3 秒スロットの加速度状態）、`w-status` 判定窓、GPS 取得間隔、定速領域判定値を保持する。
  - 状態管理部の各クラスは直値を持たず、`MotionStateParamsProvider.current()` から現在値を参照する。
- `MotionStateManager`
  - 新しい状態管理方式の司令塔。
  - `AccelManager`、`StepManager`、`GpsSamplingPolicy`、`ConstantRegionTracker`、`FinalContextResolver` を束ねる。
  - `LoggingService` からは専用 single-thread dispatcher 経由でのみ呼び出し、初期化後の状態更新を 1 本のイベント列に閉じ込める。
- `AccelManager`
  - 加速度入力を受け取り、`TrKDetector` と `StKSlotClassifier` へ同じサンプルを分配する。
  - `WorldAccelerationTransformer` を使い、`TYPE_LINEAR_ACCELERATION` の端末座標値を可能な限り世界座標（East / North / Up）へ変換してから判定器へ渡す。
  - `TYPE_ROTATION_VECTOR` が使える場合はこれを優先し、未対応端末では `TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD` の回転行列へ fallback する。どちらも使えない場合は端末座標のまま扱う。
  - `TYPE_LINEAR_ACCELERATION` が無い端末では、fallback の raw accelerometer を `LoggingService` 側で重力差分ノルムへ変換した値を受け取る。
  - `trK` が変化した時だけ callback を呼び、GPS 即時取得と詳細デバッグログへ接続できるようにする。`stK` は base cycle ごとに消費して `MotionSample` へ保存する。
- `WorldAccelerationTransformer`
  - 端末座標の線形加速度を世界座標へ変換する薄い変換器。
  - 状態判定はこの変換済み `AccelSample` だけを扱い、Android の sensor API 依存を持たない。
- `StepManager`
  - `TYPE_STEP_DETECTOR` の低遅延イベント時刻と、`TYPE_STEP_COUNTER` の累積値を分けて扱う。
  - `TYPE_STEP_DETECTOR` は歩行中 / 非歩行中のリアルタイム判定へ使い、歩数としては二重計上しない。
  - `TYPE_STEP_COUNTER` はスロット用 `StepsDelta` とリセット時刻以降の内部累計を管理する正式な歩数ソースとして使う。
  - `TYPE_STEP_DETECTOR` が使える端末では、歩行判定窓は detector イベントだけで更新する。`TYPE_STEP_COUNTER` の増分を歩行イベントとして使うのは detector が無い端末だけとする。
  - `getWStatusSnapshot()` で、直近 `wWindowMs` 内の歩行イベント数が `wStepDeltaThreshold` 以上、かつ最終歩行イベントから `walkingThresholdMs` 以内なら `W1`、それ以外は `W2` を返す。
  - 3 秒スロット開始時は `consumeStepDeltaForSlot()` 経由で蓄積済み増分を消費し、主記録の `StepsDelta` へ渡す。
  - 歩数の日替わりは `MotionStateParams.stepResetHour` を参照し、既定は 03:00 とする。
- `TrKDetector`
  - GPS 即時起動専用の加速度トリガーを判定する。
  - 過去 `trKDirectionWindowMs=2s` の変換済み加速度から水平平均方向 `H` を求め、直近 `trKWindowMs=1s` の水平サンプル `k_i` を `H` へ射影して `Hk_i` を得る。
  - `TrKAvg=average(Hk_i)`, `TrKRatio=stddev(Hk_i)/abs(TrKAvg)` とし、`abs(TrKAvg) < 0.015` なら `trK1`、`0.05 <= abs(TrKAvg) < 0.28`、`Hk_i` に `TrKAvg` と逆符号の値がない、かつ `TrKRatio <= 0.65` なら `trK4`、それ以外は `trK2/trK3` とする。`H` や ratio が成立しない場合は `trK2/trK3` とする。delay は使わない。
  - `trK4` へ遷移した瞬間だけ `LoggingService` へ通知し、3 秒 base cycle を待たず GPS を 1 回起こす。
- `StKSlotClassifier`
  - 3 秒ログスロットの正式な加速度状態 `stK` を判定する。
  - 前回 base cycle から今回 base cycle までの変換済み加速度列全体を消費し、同じ水平成分ベース式で `KAvg(=k)` と `KDirectionalityRatio(=ratio)` を計算し、あわせて `stK2` 用にスカラー平均 `KScalarAvg` と合成加速度ノルム分散 `KVariance` も保存する。
  - `abs(StKAvg) < stK1AvgThreshold(0.015)` なら `STK1`、`0.08 <= abs(StKAvg) < 0.28`、射影値に逆符号がない、かつ `StKRatio <= 0.75` なら `STK4`、それ以外は `STK2` とする。`KScalarAvg >= stK2ScalarAvgThreshold` または `KVariance >= stK2VarianceThreshold` は `STK2` の参考指標として保存する。
- `GpsSamplingPolicy`
  - `stK` と `w-status` から GPS 取得間隔を決める。分岐は `STK4`、`W1`、その他の 3 つに限定する。`trK4` への確定遷移時の 1 回目の即時取得は `LoggingService` の `trK` 変更 callback が担当する。
  - 暫定 `CONSTANT_MOVE` は GPS 取得間隔の特別扱いには使わない。
- `ConstantRegionTracker`
  - `W2` の区間を定速領域として管理する。
  - 区間内 GPS 点列へ直線近似を行い、区間終了時に `STAY / CONSTANT_MOVE` を確定する。
- `GpsSpeedTracker`
  - 直近 `walkingSpeedWindowMs` 内の GPS 点列からGPS速度を計算する。
  - W1時の「徒歩 / 高速移動」最終判定だけに使い、GPS取得間隔制御には直接使わない。
- `FinalContextResolver`
  - `stK`、`w-status`、歩行速度比較、定速領域結果を既存の `DEVICE_STILL / STOPPED / WALKING / VEHICLE` へ変換する。
  - `W1` 判定時に直近 GPS 速度が取れない場合は、`CONSTANT_MOVE` の直線近似速度を GPS 速度の補助値として使い、車・電車内の歩数誤検知が `WALKING` に固定されないようにする。
- `GpsAggregationMode`
  - GPS座標の記録集約だけに使う内部状態。
  - `MOVING / DEVICE_STILL / STOPPED / UNKNOWN` を持ち、表示用 `finalMode` とは独立して計算する。

### 1.5 Converter Layer (Windows / Python)
- `log_converter/step1_extract.py`
  - Barograph CSV と StepWalk DB から raw JSONL を抽出する。
  - JST 固定でタイムスタンプを生成する。
- `log_converter/step2_convert.py`
  - raw JSONL 群を `Timestamp` 単位でマージし、標準 CSV を生成する。
  - 時刻丸めは行わず、欠損は空欄、歩数は `StepsDelta` のまま保持する。
- `window_viewer/step3_visualize.py`
  - 標準 CSV と、必要に応じて補助ログバックアップ CSV を読み、気圧・高度・歩数累積ビューと地図を可視化する。
  - `#` コメント行を無視し、`Lat=0 / Lon=0` の無効 GPS 点は地図から除外する。
  - 既定では `C:\MyDrive\android` の最新バックアップを最優先し、最後の長い空白以降の最新セッションだけを表示する。
  - ビューア内では `補正あり / なし` を切り替えられるが、通常の確認で使う `補正あり` は Android アプリと同じ固定描画を表す。
  - `補正あり` のグラフは、Android と同じ考え方で外れ値除去、30 秒間隔の線形補間、移動平均平滑化を適用する。
  - `補正あり` の地図は、Android と同じ固定順序 `復帰バースト -> 偽クラスタ滞在 -> 停止標準化 -> GPS 平準化` を適用する。
  - viewer は読み込んだセッションから日付キーを抽出し、`日付` プルダウンで日別モードデータへ切り替える。
- viewer は `停止偏差` グラフを持ち、停止区間中心からの raw 偏差と、各点の補正量 (`raw -> corrected` の移動距離) を比較できる。
  - viewer の `停止偏差` グラフは、全期間表示に加えて大きな偏差ピーク周辺へフォーカスできる。
- viewer の地図は `地図時間` で `全日 / 偏差フォーカス連動` を切り替えられ、偏差フォーカスで選んだ時刻帯だけの軌跡を確認できる。
- viewer の地図は状態イベントラベル層と `trK` ラベル層を個別に ON/OFF できる。状態イベントログまたは旧補助ログバックアップ CSV がある場合は `MotionSample` の `KStatus / WStatus / ConstantRegionKind / TrKStatus` を使って Android と同じ変化点ラベルを再構成する。表示ラベルは `AC`（STK4 enter）、`STAY`、`CMOV`、`tON` のみとする。
- viewer の地図軌跡には表示線と同じ座標列で透明な hit 用 polyline を重ね、mousemove 時に画面距離で最も近い表示点を探して日時 tooltip を出す。スプライン補間点は前後 GPS 点の時刻を線形補間した `timestamp / dt` を持つ。
- viewer / Android の地図には、直線区間だけでなく曲線区間にも `>` 風の進行方向マーカーを差し込み、長い移動区間の向きを読み取りやすくする。
- 進行方向マーカーは、始点・終点付近を避け、画面上の折れ線距離に沿って一定間隔でサンプリングする。角度はサンプル位置の前後を同じ screen px 距離だけ離した点から接線方向として求める。配置間隔は線幅の 9 倍、接線計算距離は線幅の 3 倍、始終端スキップ距離は線幅の 4.5 倍を基本にし、元 GPS 点の頂点位置には依存しない。
- 進行方向マーカーの回転角は、GPS 方位角（北=0度, 時計回り）を `>` 文字の基準向き（右向き=0度）へ合わせるため `bearing - 90度` を使う。
- 進行方向マーカーは、丸背景を使わず、モード色の `>` 文字に白い縁取りを付けて表示する。Android アプリ、地図ウィジェット、Windows viewer で同じ描画イメージへ揃える。
- Windows viewer の `>` 進行方向マーカーは、復旧修正時でもサイズを `font-size: 16px`、`iconSize: [16, 16]`、`iconAnchor: [8, 8]` の現在調整値から変えない。過去の暫定値 `font-size: 30px` / `iconSize: [44, 44]` は大きすぎるため、表示復旧目的で戻してはいけない。
- 進行方向マーカーの密度は控えめにし、既定では長い移動区間でもおよそ従来の 1/3 程度の数に抑える。
- viewer は補助ログバックアップ CSV が無い場合に `# EVENT` から `DisplayMode` / `K/W` / `trK` を推測再構成しない。Android アプリと同じく、補助ログが無い場合はこれらの状態ラベルと mode 由来表示は欠損として扱う。
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
- `GpsUtil.buildDisplayPolyline()` は `MotionSample` の `ConfirmedMode / ConstantRegionKind` と定速領域座標を読み、`DEVICE_STILL / STOPPED` の連続区間を代表点 1 点へ畳む。旧ログ互換として `ConstantRegionKind=STAY` の連続区間も保存済み `ConstantRegionStayLat/Lon` を使って代表点へ畳む。停止代表点は移動平均・スプライン補間のどちらでも周囲へ引っ張らない。
- `GpsUtil.buildDisplayPolyline()` の平滑化は、`WALKING` の同一モード連続チャンクにだけ適用する。`VEHICLE` は点間が粗く曲率変化も大きいため、移動平均で角を削らない。
- widget の更新周期は `LoggingService.updateWidgetsIfDue()` が唯一の周期管理者として扱い、前回更新時刻との差ではなく「次回予定時刻」に達したかで判定する。
- `PressureWidgetReceiver` / `MapWidgetReceiver` にはサービス由来更新を再判定する前回時刻ゲートを置かない。呼ばれた receiver は、サービス周期・強制更新・ホスト由来更新のいずれであっても、その呼び出しで必要な描画だけを行う。

### 1.4 UI Layer
- `HomeViewModel`
  - 表示期間に応じた履歴取得、今日の歩数計算、最新値抽出を担当する。
  - 今日の歩数計算に使う取得開始時刻は、初期化時の現在時刻ではなく、`latestEntryFlow` の最新 timestamp から `GpsUtil.getLoggingStart()` で導出する。
  - 最新ログが 03:00 の記録日境界を跨いだ場合、`todayLoggingStartFlow` が新しい開始時刻を流し、`todayEntriesFlow` の取得範囲を切り替える。
  - `GraphWindowController` が管理する表示終端時刻を使い、左スワイプ時の古い時間帯読込を仲介する。
- `GraphWindowController`
  - ホーム画面グラフの表示終端時刻、最新追従状態、手動移動時の DB 範囲クランプを担当する。
  - 通常時は `latestEntryFlow` の最新 timestamp に追従し、手動移動時は追従を止める。
  - 手動移動直後は短時間の追従ガードを置き、ログ到着と操作完了が近接しても意図しない最新復帰を避ける。
  - 手動移動結果が最新 timestamp から許容範囲内なら最新追従を維持し、微小な丸め差や軽いタッチで表示が止まらないようにする。
  - `resetToLatest()` で最新追従へ戻す。
- `HomeScreen`
  - グラフのジェスチャを横方向ズーム / 時間移動に制限する。
  - 通常のドラッグで表示窓を古い時刻 / 新しい時刻へ移動し、ピンチインで可視期間を広げる。
  - 単一の `pointerInput` で 1 本指ドラッグと 2 本指以上のピンチを判別し、ピンチ中心の時刻を保持する。
  - グラフ右上の `最新` 操作で、手動移動後の表示を最新ログ追従へ戻す。
  - グラフウィジェットからの起動通知を受けた場合も `HomeViewModel.resetGraphWindowToLatest()` を呼び、前回の手動移動状態や空の表示窓を引き継がない。
  - グラフの描画用 `Path` は表示データ・表示範囲・Canvas サイズが変わった時に再生成し、draw 本体では cached path を描く。
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
  - 過去日は対象日を選んだ時に `getEntriesBetweenAscOnce()` / `getBetweenOnce()` で one-shot 読み込みする。現在の論理日だけ 30 秒周期で再読込し、記録中の 3 秒 insert ごとには地図 overlay を再作成しない。
  - 軌跡補間、停止クラスタ、モード別線分、GPS 欠損線分を `Dispatchers.Default` 上で `MapRenderData` にまとめ、`MapScreen` の UI スレッドへ完成済み結果を渡す。
  - 前日 / 翌日移動では、位置データが存在する日だけへ遷移する。
- `MapScreen`
  - 地図 overlay の再描画時に、同一日では自動ズームし直さない。
  - `MapViewModel` が返す表示補正済み系列をそのまま描画に使う。
  - 進行方向矢印は矢印ごとの `Marker` を作らず、単一の専用 overlay で同じ文字・白縁・角度をまとめて描画する。画面外の矢印は描画しない。
  - 停止区間では `復帰バースト -> 偽クラスタ滞在 -> 偏差列ベース停止補正` を通した表示用 GPS 系列を使う。
  - 折れ線描画では `GpsUtil.buildDisplayPolyline()` を使い、viewer の `GPS 平準化 ON` と同じ考え方の連続折れ線系列を描く。停止マーカーや開始・現在地マーカーは生の位置を維持する。
  - 折れ線幅は `GpsUtil.mapTrackStrokeWidthPx()` で取得し、density を掛けた dp 基準で描く。初回 auto-fit が必要な場合は、地図を fit / zoom してから overlay を追加する。
  - 折れ線色は `displayMode` に応じて `黒 / グレー / 青 / 赤` を使い、旧グラデーションは使わない。
  - 進行方向マーカーは `GpsUtil.computeDirectionArrowMarkersOnScreen()` の結果を使って `>` を描き、縮尺変更で密度が増えすぎないようにする。
  - Android アプリ本体の `>` 進行方向マーカーは、個別 `Marker` の bitmap icon として描く。表示イメージは折れ線色の `>` 文字に白い縁取りを付けたものとし、丸背景は使わない。
  - Android アプリ本体の地図も `GpsUtil.buildDisplayPolyline()` の標準補間点列を使う。地図操作時の重さ対策は `MapViewModel` の one-shot 読み込みと overlay 再作成抑制で行い、`>` の描画イメージや点列密度は変えない。
  - マーカーサイズは widget と同じ共通規則を使い、`MarkerSurface.APP_MAP` の倍率で体感サイズを揃える。
  - marker icon の `BitmapDrawable` は `Resources` 付きで生成し、density 差による見え方のズレを防ぐ。
  - 現在点マーカーは `isHollow = true` を使い、白地に青リングの見え方で開始点と対になるように描く。
  - 現在点の青リングは開始点の赤リングと同じ線幅パラメータを使う。
- `MapWidgetReceiver`
  - アプリ地図と同じ日次抽出、GPS 外れ値除去、停留点セグメント分割方針で軌跡を描く。
  - 同日の `MotionSample` を読み、`GpsUtil.normalizeStopsForDisplay(entries, motionSamples)` で本体地図と同じ表示補正を適用する。
  - 折れ線描画では `GpsUtil.buildDisplayPolyline()` を使い、本体地図と同じ viewer 準拠の連続折れ線系列を適用する。
  - タイル地図を bitmap に描いた後、ログ線・方向マーカー・開始/現在地マーカーを screen px 座標へ描く。ログ線は `2.2dp` 相当の太さを density で px へ変換し、launcher 側の `fitXY` 縮小後も読みやすいが過度に太く見えない設定にする。
  - 折れ線色は `displayMode` に応じて `黒 / グレー / 青 / 赤` を使い、viewer と同じ配色へ揃える。
  - 進行方向マーカーは `GpsUtil.computeDirectionArrowMarkersOnScreen()` を使い、widget bitmap 上の画面距離基準で `>` を配置する。
  - ウィジェット側の `>` 進行方向マーカーも、本体地図と同じく白い縁取り付きの glyph を描く。
  - マーカーサイズは `GpsUtil` の widget 専用 style を使い、widget bitmap 上で一定 screen size に保つ。
  - `widget_map_layout.xml` は `fitXY` で生成済み Bitmap をそのまま表示する。
  - 描画時に widget サイズ、前処理前後件数、地図 bounds、最新タイムスタンプをデバッグログへ出力する。
  - 現在点マーカーは白地に青リングの hollow style で app と同じ意味になるように描く。
  - ウィジェット更新は `LoggingService` の周期更新で行い、AppWidgetProvider XML の `updatePeriodMillis` は使わない。
  - `LoggingService` は描画後の次回予定時刻を「今回処理した slot timestamp + 設定間隔」で更新し、壁時計の分境界などへ丸めない。
  - 現在点の青リングは app と同じ線幅パラメータを使う。
  - サービス由来の周期更新では、`widgetId / widget size / 最新時刻 / 正規化済み軌跡 digest` を使った署名が前回描画と一致する場合、再描画を省略する。
  - 背景タイル地図は、`widgetId / widget size / zoom / viewport center globalPx` を使った viewport 署名でキャッシュし、同一 viewport の周期更新では再取得・再合成しない。
- `PressureWidgetReceiver`
  - タップ時は `ACTION_OPEN_GRAPH` と専用 requestCode の PendingIntent で `MainActivity` を開き、`EXTRA_RESET_GRAPH_WINDOW=true` を渡す。
  - 履歴取得範囲は `windowStartMs..windowEndMs` に加えて左側 `30分` の補間文脈だけを読む。以前のように `lookback * 2` 全体は読まない。
  - サービス由来の周期更新では、`widgetId / widget size / lookback / 最新時刻 / 最新表示値 / 日歩数 / 取得件数` を使った署名が前回描画と一致する場合、再描画を省略する。
- `MainActivity`
  - 起動時に記録に必須の位置・活動認識権限が揃っていれば `LoggingService` を開始する。
  - Android 13 以降の通知権限は、通知表示のための任意権限として扱い、通知権限だけが未許可でも記録サービス開始を止めない。
  - 起動 Intent と `onNewIntent()` を `OpenRequest` として Compose 側へ流し、既存 Activity 再利用時も指定画面への移動とグラフ表示窓リセットを行う。
  - 地図ウィジェットとグラフウィジェットは別 action / 別 requestCode を使い、PendingIntent の内容が相互に上書きされることを避ける。

## 2. API / データフロー

### 2.1 記録フロー
1. `LoggingService` がセンサー値と GPS 値を保持する。
2. 3 秒スロットごとに `LogEntry` を生成する。
3. `LogDao.insertReplace()` で DB に即時保存する。
4. 同じ `LogEntry` をファイル書き出し用キューへ enqueue する。
5. キュー件数が閾値に達した時、または強制フラッシュ契機で、日次 CSV へまとめ書きする。
6. 同じ 3 秒ループで `MotionSample` 相当の状態点を生成する。
7. `KStatus / TrKStatus / WStatus / ConfirmedMode / ConstantRegionKind` のいずれかが変化した場合だけ `MotionSampleDao` で DB に保存し、状態イベントログ CSV 用キューへ enqueue する。表示側は保存済み状態点を前方補完する。
   - `MotionSample` の保存内容は `LoggingConfig.MOTION_LOG_ROUTINE` で切り替える。既定は `NORMAL` とし、表示維持に必要な最小列だけを保存する。`FULL` に切り替えると、解析用の全列を従来どおり保存する。
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

- 加速度は用途を 2 つに分ける。`trK` は GPS を即時に起こすための短周期トリガー、`stK` は 3 秒主記録スロットへ保存する正式な加速度状態である。
- `LoggingService` は `TYPE_LINEAR_ACCELERATION` と姿勢系センサーを `MotionStateManager` へ投入し、`WorldAccelerationTransformer` が可能な限り世界座標へ変換する。`TYPE_ROTATION_VECTOR` を優先し、未対応時は `TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD`、それも不可なら端末座標またはノルム fallback を使う。
- `TrKDetector` は変換済みの重力除去済み加速度を受け取り、過去 2 秒の水平平均方向 `H` と直近 1 秒の `H` 方向射影から `TrKAvg` と `TrKRatio` を計算する。`abs(TrKAvg) < 0.015` なら `trK1`、`0.05 <= abs(TrKAvg) < 0.28`、射影値に逆符号がない、かつ `TrKRatio <= 0.65` なら `trK4`、それ以外は `trK2/trK3` とする。
- `trK` は毎センサーイベントで再評価し、`trK4` へ遷移した瞬間だけ、3 秒 base cycle を待たず GPS を 1 回即時取得する。
- `trK4` 確定時の即時GPSは、単発 `CurrentLocationRequest` ではなく短時間の burst `requestLocationUpdates()` で行う。burst は `PRIORITY_HIGH_ACCURACY`、`interval=500ms`、`minInterval=100ms`、`maxUpdateAge=500ms`、`duration=10000ms`、`maxUpdates=10` とし、`accuracy <= 30m` の候補を即採用、良点が来なければ最良候補が `accuracy <= 80m` の場合だけ採用する。通常の `requestLocationUpdates()` も同時に `gpsKMinMs` 周期へ張り替え、burst の開始・候補・採用・棄却を debug event に残して次回解析できるようにする。状態イベントには `TrKStatus / TrKAvg / TrKRatio` を保存する。`TrKRatio` は算出不能時に null を許容する。
- `StKSlotClassifier` は前回 base cycle から今回 base cycle までの変換済み加速度列全体を消費し、3 軸ベクトル平均ノルムを `KAvg`、スカラー平均を `KScalarAvg`、合成加速度ノルムの分散を `KVariance` として計算する。
- `stK` は `abs(StKAvg) < 0.015` なら `STK1`、`0.08 <= abs(StKAvg) < 0.28`、射影値に逆符号がない、かつ `StKRatio <= 0.75` なら `STK4`、それ以外で `KScalarAvg >= stK2ScalarAvgThreshold` または `KVariance >= stK2VarianceThreshold` なら `STK2`、どちらでもなければ `STK2` とする。現行の外部 CSV は `KStatus / KRawStatus / KAvg / KScalarAvg / KVariance / TrKStatus / TrKRawStatus / TrKAvg` を中心に出力する。
- `StepManager` は `TYPE_STEP_DETECTOR` が使える端末では detector イベントだけの最終発生時刻と event window を保持する。直近 `wWindowMs` 内の歩行イベント数が `wStepDeltaThreshold` 以上、かつ判定時刻と最終歩行イベントとの差が `walkingThresholdMs` 以内なら `W1`、それ以外は `W2` とする。`TYPE_STEP_COUNTER` は保存歩数用であり、detector が使える端末では `W1 / W2` 判定窓へ混ぜない。detector が無い端末だけ counter 増分を歩行イベントの fallback として使う。
- `GpsSamplingPolicy` は `trK4` 遷移または `STK4` を最優先して `gpsKMinMs` を返す。`W1` は `gpsWalkIntervalMs`、その他は `gpsStableInitialMs` から `gpsStretchStepMs` ずつ伸ばし、`gpsStretchMaxMs` を上限とする。`gpsStretchMaxMs` は現状コード固定値 30 秒（`MotionStateParams` の既定値）で、UI からは変更しない。`gpsStretchMaxMs` を将来 `0` にした場合は上限なしで伸ばし続ける挙動も実装上は許す。その他状態の伸長カウンタは、直前スロットの主記録に位置があり、かつ `gpsAccuracy <= GPS_STRETCH_ACCEPT_ACCURACY_M(80m)` の場合だけ進める。欠損または低精度の場合はカウンタを 0 に戻し、次回は 5 秒からやり直す。`trK4` または `STK4` 中は伸長カウンタを 0 に戻すため、加速から外れてその他状態へ入った直後は 5 秒から再開する。`trK4` への確定遷移そのものは `LoggingService` の `trK` 変更 callback で受け取り、遷移時の 1 回目だけは cooldown を待たず即時 burst を開始する。
- `ConstantRegionTracker` は `W2` の区間を定速領域として扱う。区間継続中も base cycle ごとに暫定直線近似 `g(t)` を更新し、暫定 `STAY / CONSTANT_MOVE` を `MotionSample.constantRegionKind` に保存する。
- `ConstantRegionTracker` は直線近似の前に、点群重心からの距離が中央値 + MAD ベースしきい値を大きく超える孤立 GPS 点を棄却する。これにより stay point と移動ベクトルが単発飛び点に引っ張られるのを避ける。
- 区間終了時は区間全体の外れ値棄却済み GPS 点を時刻に対して直線近似し、速度が `staySpeedThresholdKmh` 以下なら `STAY`、超えるなら `CONSTANT_MOVE` として確定する。
- `FinalContextResolver` は `STK4 + W2` を `VEHICLE` とする。`W1` では `GpsSpeedTracker` のGPS速度と `StepDeltaWindow * walkingStepLengthM / walkingSpeedWindowMs` の歩数推定速度を比較し、GPS速度が `walkingVehicleSpeedThresholdKmh` 以上、またはGPS速度と歩数推定速度の差が `walkingGpsStepMismatchThresholdKmh` 以上なら `VEHICLE`、それ以外は `WALKING` とする。直近GPS速度が未取得で `CONSTANT_MOVE` 速度がある場合は、その速度をGPS速度の補助値として使う。`STK1` は `DEVICE_STILL`、`(STAY or STK2) and not STK1` は `STOPPED`、`CONSTANT_MOVE` は `VEHICLE` へ変換する。区間継続中は暫定 `ConstantRegionKind` も参照し、現時点の最善推定で表示を更新できるようにする。
- `MotionStateManager` は `finalMode` とは別に `gpsAggregationMode` を計算する。`STK4`、`W1`、`CONSTANT_MOVE` は `MOVING`、`STAY + STK1` は `DEVICE_STILL`、`STAY + STK2` は `STOPPED` とする。`LoggingService.buildLogEntry()` と `aggregateGpsForSlot()` は `finalMode` ではなく `gpsAggregationMode` を使うため、表示4状態の調整がGPS座標生成へ波及しない。
- すべての閾値・時間・距離は `MotionStateParamsProvider.current()` から取得し、状態管理クラス内に調整値を直接埋め込まない。
- 初期パラメータは `baseCycle=3s`, `trKWindow=1s`, `trKDirectionWindow=2s`, `trK1Avg=0.015`, `trKAvg=0.05`, `trKAvgUpper=0.28`, `trKRatio=0.65`, `stK1Avg=0.015`, `stK4Avg=0.08`, `stK4AvgUpper=0.28`, `stK4Ratio=0.75`, `stK2ScalarAvg=0.25`, `stK2Var=0.01`, `wWindow=9s`, `wStepDeltaThreshold=2`, `walkingThreshold=5s`, `walkingSpeedWindow=9s`, `walkingVehicleSpeed=10km/h`, `walkingStepLength=0.60m`, `walkingGpsStepMismatch=5km/h`, `stK4Gps=2s`, `walkGps=5s`, `stableGps=5s+5s*n`, `stableMax=30s`, `gpsStretchAcceptAccuracy=80m`, `gpsBurst=500ms/min100ms/maxAge500ms/duration10s/max10`, `gpsBurstGood=30m`, `gpsBurstUsable=80m`, `staySpeed=2km/h`, `constantRegionMin=15s`, `outlierMad=4.0`, `outlierMin=50m` とする。
- 確定モードは `MotionSample.confirmedMode` に保存し、地図・歩数グラフは新 CSV ではこの値を優先する。旧 CSV で `confirmedMode` が無い場合のみ旧 `MovementDetector` 互換ロジックで表示モードを再構成する。
- 定速領域が閉じて `STAY / CONSTANT_MOVE` が確定したら、`LoggingService` は `region.startTimestampMs <= timestamp < region.endTimestampMs` の既存 `MotionSample` を読み直し、確定結果で `confirmedMode` と定速領域情報をバックフィルする。終了時刻の行は `STK4` または `W1` の新状態に属するため、バックフィル対象に含めない。Room DB への反映は即時、ローカル motion CSV の再書き換えは debounce / バッチ flush でまとめて実行する。
- `confirmedMode` は確定キャッシュとして扱い、進行中の未確定領域の `DEVICE_STILL / STOPPED` は保存しない。`STK4`、`W1`、閉じた `STAY / CONSTANT_MOVE` だけを確定済みとして保存する。
- 表示側の確定ポイントは `GpsUtil.inferModeStates()` が最後に見つけた `confirmedMode` 行である。確定ポイント以前は確定キャッシュを基本に使うが、`ConfirmedMode=WALKING` は表示対象の GPS 点列、`StepDeltaWindow`、`ConstantRegionSpeedKmh` で再評価し、高速移動と判定できる場合は `VEHICLE` として描画する。確定ポイント以後だけ raw の `KStatus(stK) / WStatus / ConstantRegionKind` から暫定状態を計算する。
- バックフィル後は Room の `motion_samples` を `insertAllReplace()` で更新し、日次補助 CSV も同じ timestamp の行を置き換える。これにより、区間序盤の暫定判断が確定後のログ・表示に残らない。
- 地図表示で `DEVICE_STILL` または `STOPPED` が連続する場合は、両者が交互でも区間内 GPS 点を停止代表点 1 点へ畳んで描く。代表点は保存済み stay point 座標を優先し、なければ区間平均を使う。旧ログ互換として `ConstantRegionKind=STAY` だけが連続する区間も同じ代表点へ畳む。`ConstantRegionKind=CONSTANT_MOVE` は定速領域メタデータだけを参照し、地図上の座標列は記録された GPS 点をそのまま使う。
- 地図折れ線の最終描画点列は、停止代表点を境界に分けた移動チャンクごとにローカルメートル座標へ投影し、tension 付き Catmull-Rom 系スプラインで補間してから緯度経度へ戻す。`WALKING` と `VEHICLE` の境界は軌跡としてはつなぐが、色分けは `displayMode` でセグメント分割して維持する。
- 停止代表点自体は補間に混ぜず固定する。一方、停止以外の移動チャンクは `VEHICLE` を含めて全セグメントをスプライン化し、tension で張り出しを抑える。

#### 3.1.2 旧 `MovementDetector` 互換方式

- 旧 `MovementDetector` は本体記録では使わない。
- 旧補助ログ CSV のように `MotionSample.confirmedMode` が無いデータを表示するときだけ、標準偏差ベース判定を用いた互換 fallback として使う。
- 旧方式の GPS 更新間隔選択、確信度積分、徒歩強化ロジックは現行の記録制御には使わない。
- 旧方式互換 fallback は `GpsUtil.inferModeStates()` 内に閉じ込める。`KStatus / WStatus / confirmedMode / ConstantRegionKind` のいずれかを持つ新ログでは旧方式 fallback を使わず、確定キャッシュ + 確定ポイント以後の暫定再構成だけを使う。

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
- `trKWindowMs = 1000`
- `trKDirectionWindowMs = 2000`
- `trK1AvgThreshold = 0.015`
- `trKAvgThreshold = 0.05`
- `trKAvgUpperThreshold = 0.28`
- `trKRatioThreshold = 0.65`
- `stK1AvgThreshold = 0.015`
- `stK4AvgThreshold = 0.08`
- `stK4AvgUpperThreshold = 0.28`
- `stK4RatioThreshold = 0.75`
- `stK2ScalarAvgThreshold = 0.25`
- `stK2VarianceThreshold = 0.01`
- `wWindowMs = 9000`
- `wStepDeltaThreshold = 2`
- `walkingThresholdMs = 5000`
- `walkingSpeedWindowMs = 9000`
- `walkingVehicleSpeedThresholdKmh = 10.0`
- `walkingStepLengthM = 0.60`
- `walkingGpsStepMismatchThresholdKmh = 5.0`
- `gpsKMinMs = 2000`
- `gpsWalkIntervalMs = 5000`
- `gpsStableInitialMs = 5000`
- `gpsStretchStepMs = 5000`
- `gpsStretchMaxMs = 30000`
- `GPS_STRETCH_ACCEPT_ACCURACY_M = 80`
- `GPS_BURST_INTERVAL_MS = 500`
- `GPS_BURST_MIN_INTERVAL_MS = 100`
- `GPS_BURST_MAX_UPDATE_AGE_MS = 500`
- `GPS_BURST_DURATION_MS = 10000`
- `GPS_BURST_MAX_UPDATES = 10`
- `GPS_BURST_GOOD_ACCURACY_M = 30`
- `GPS_BURST_USABLE_ACCURACY_M = 80`
- `staySpeedThresholdKmh = 2.0`
- `constantRegionMinDurationMs = 15000`
- `stayPointMaxRadiusM = 20.0`
- `constantRegionOutlierMadMultiplier = 4.0`
- `constantRegionOutlierMinThresholdM = 50.0`

記録・フラッシュ方針:

- GPS / 気圧 / 歩数の取得周期と、DB / CSV への永続化周期は分離する。
- 主記録レコードの論理生成周期は 3 秒を基準とする。`motion_samples` は状態イベントログとして変化点を中心に保存する。
- 生成済みレコードはメモリバッファへ積み、日次 CSV などファイル系ストアへのフラッシュは既定 5 分ごとにまとめて行う。
- 既定の flush 閾値は 100 件で、3 秒主記録 기준ではおよそ 5 分相当になる。
- GPS は各 3 秒スロットごとに一度だけ主記録へ集約し、GPS / 高度の欠損を許容する。
- GPS 集約では、直前に採用済みの GPS を `before` と呼ぶ。
- 移動中（`WALKING` / `VEHICLE`）の GPS 集約規則:
  - スロット内プール 0 件: GPS / 高度は欠損
  - スロット内プール 1 件: その 1 件を採用
- スロット内プール 2 件以上: `before` とプール内 GPS 群から 3 次スプライン補間を構成し、スロット時刻における `lat / lon` を採用
- 標高は `LoggingService.getLatestAltitude()` で直近の有効 GPS 標高を保持・取得し、GPS 平均やスプライン補間の対象にしない。
- 完全停止（`DEVICE_STILL`）の GPS 集約規則:
  - 直前スロットも `DEVICE_STILL` で、スロット内プール 1 件以上なら `before` を含めた平均位置・平均高度を採用
  - 直前スロットが `DEVICE_STILL` でなく、スロット内プール 1 件以上なら `before` を含めない平均位置・平均高度を採用
  - いずれもスロット内プール 0 件なら欠損
- 停止（`STOPPED`）の GPS 集約規則は、完全停止寄りの平均化ルールを使う。
- 実装上は、`STOPPED` で直前も `STOPPED` または `DEVICE_STILL` の場合に `before` を含める。
- 気圧は 3 分ごとの記録スロットで最新保持値を採用し、それ以外の主記録行では欠損とする。表示値とグラフ系列は直近有効値・補間処理で復元する。
- 歩数は 3 秒区間の増分を採用し、初期基準未確定時は `0` で開始してよい。
- 強制フラッシュ契機は少なくとも次を含む。
  - 手動 export 開始前
  - サービス終了時に到達できた場合
  - 未捕捉例外検知時（`GpsLoggerApplication.installUnhandledExceptionLogger()` 経由）
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
- GPS 外れ値除去は、精度閾値、前点からの速度閾値、前後 3 点比較による単発ジャンプ除去を組み合わせる。単発ジャンプ除去では、前後点 `A/C` の時刻補間位置 `P` と中央点 `B` の偏差距離から `2 * distance(P, B) / (time(C)-time(A))` を求め、`100km/h` 以上なら `B` を除外する。
- 表示用 GPS 系列では、同一座標付近の長時間凍結後に不可能な復帰ジャンプが出た場合、復帰点へ `gpsGapBreak` / `isGapBreak` を付ける。Android の `GpsUtil.buildDisplayPolyline()` と viewer の `build_gps_points()` はこの点をセグメント境界として扱い、平準化・スプライン・進行方向マーカーの計算をこの境界で分離する。`GpsUtil.buildGpsGapBreakSegments()` / viewer の `buildGpsGapBreakSegments()` は凍結開始座標から復帰点までをオレンジ色破線の経路不明区間として別描画する。
- 高速移動中の横飛び点には、前後点を結ぶ線分からの横ずれ距離、余分な迂回距離、折れ角を用いた transient detour フィルタを適用する。
- さらに、短時間だけ別地点群へ飛んで戻るケースには、両端の大ジャンプで囲まれた短時間・少数点クラスタをまとめて除去する。
- 本体地図と地図ウィジェットは同じ前処理結果を使う。

### 3.6 グラフ閲覧
- `HomeViewModel` は `GraphWindowController.windowEndMs` を使い、表示終端時刻を現在時刻から独立して管理する。
- `HomeViewModel` は起動時に DB の最新ログ timestamp を取得し、`GraphWindowController` の表示終端を最新ログ時刻へ初期化する。これにより、ログが止まっている時やウィジェットから復帰した時でも、壁時計現在時刻基準の空表示窓を初期値にしない。
- `GraphWindowController` は通常時に最新ログ timestamp へ自動追従し、ユーザーが過去へ移動した場合は手動表示として追従を止める。
- `GraphWindowController` は手動移動結果が最新 timestamp から 10 秒以内なら最新追従を維持する。10 秒を超えて過去へ移動した場合だけ手動表示として扱い、その後 3 秒間は最新追従を再開しない。
- `HomeScreen` の `最新` 操作は `HomeViewModel.resetGraphWindowToLatest()` を呼び、手動表示から最新追従へ戻す。
- `GraphUtil.getProcessedSeries()` は「現在時刻基準」ではなく、指定された `windowEndMs` と可視期間に基づいて系列を生成する。
- 歩数系列は累積離散値として扱い、`GraphUtil.createStepSeries()` はスプライン補間を使わず、各 target time までに到着済みの `StepsDelta` を積み上げた保持値を返す。
- `HomeScreen` は歩数系列を水平線 + 垂直線の階段状 path として描く。
- `HomeScreen` は単一の `pointerInput` で 1 本指ドラッグと 2 本指以上のピンチを処理する。
- 横ドラッグ量は `GRAPH_DRAG_SENSITIVITY` で倍率調整し、左ドラッグで過去、右ドラッグで未来へ動く向きに統一する。
- ジェスチャ中は UI ローカルの `transientWindowEndMs` と可視期間だけを更新し、指を離した時だけ `HomeViewModel.commitGraphViewport()` を呼んで DB 再読込を行う。
- `HomeViewModel.commitGraphViewport()` には移動量だけでなく現在の可視期間も渡す。DB 保持範囲による下限丸めは設定表示期間ではなく現在の可視期間を使い、ピンチで短い期間に縮めた後も古い時刻へ辿れるようにする。
- 時間範囲ラベルと X 軸は `transientWindowEndMs` を基準にしてジェスチャへ追従させるが、`ProcessedSeries` は操作開始前の完成済み系列を再利用する。
- DB 再読込と `ProcessedSeries` 再集計はジェスチャ終了後の確定済み表示窓に対してだけ行い、ピンチイベントごとには実行しない。
- ピンチインで設定表示期間より広い可視期間が必要になるため、`HomeViewModel` は `GraphUtil.MAX_ZOOM_OUT_FACTOR` 倍まで必要に応じて履歴を読む。現在の上限は 6 倍とする。
- `HomeScreen` の最小 zoom 値も `GraphUtil.MAX_ZOOM_OUT_FACTOR` から計算し、UI の最大ピンチアウト量と DB 読込範囲を一致させる。
- 系列生成は `produceState + Dispatchers.Default` でバックグラウンド実行し、描画中のジェスチャを止めない。
- 一時的に系列生成が追いつかない場合に備え、`HomeScreen` は直前の正常系列を保持し、初回など直前系列も無い場合は `グラフ計算中...` を表示して空白状態を避ける。
- `HomeScreen` のグラフ前処理は時系列昇順を前提とするため、DB 取得結果が新しい順でも描画前に timestamp 昇順へ正規化する。
- `HomeScreen` は `HomeUiState.isCurrentLoaded` と `isGraphLoaded` を分けて扱い、現在値表示のロード状態とグラフ履歴のロード状態を分離する。グラフ履歴が未読込でも、現在値表示は独立して更新できるようにする。表示範囲に 2 件以上のデータが無い場合でも `最新へ戻す` 操作を出し、必要に応じて `HomeViewModel.recoverGraphWindowIfEmpty()` が最新ログ時刻へ復帰させる。
- `HomeViewModel.graphWindowFlow` は表示窓確定通知を 120 ms デバウンスしてから `conflate()` し、`getEntriesBetweenAscOnce()` / `getBetweenOnce()` を順次実行して履歴スナップショットを作る。これにより、倍率と表示終端の連続更新を最終状態へまとめる。
- `HomeViewModel.graphWindowFlow` の読込範囲は、初回から常に `設定表示期間 × 2` を読むのではなく、`HomeScreen` から通知される現在の可視期間に合わせる。通常表示では `現在の可視期間 + 左 30 分の補間文脈` だけを読み、ピンチアウト時だけ必要な範囲まで段階的に広げる。
- `HomeScreen` の `CombinedChart` は確定済み `windowEndMs` と可視期間、取得済み entries が変わった時だけ `ProcessedSeries` を再生成する。ジェスチャ中は直前の正常系列を保持して再集計しない。
- グラフの X 軸は常に `graphStartMs..transientWindowEndMs` を基準に計算し、最初のデータ点時刻ではなく表示窓そのものを横比率の基準とする。
- ドラッグ中の一時的な表示終端は、現在ロード済み履歴の最古時刻では制限しない。指を離した後に `HomeViewModel.commitGraphViewport()` が DB 全体の最古・最新時刻で安全に丸め、新しい範囲を読み直す。
- 歩数系列は可視窓ごとに先頭値を差し引いて 0 始まりへ正規化し、可視窓内の増分が読み取りやすい形で描画する。
- 歩数系列は `StepsDelta` の累積で作り、03:00 区切り日が切り替わった時点で累積を 0 に戻す（`GpsUtil.getLoggingStart(timestamp)` で日境界を判定）。
- ホーム画面とウィジェットのグラフは、設定された lookback を優先し、データが無い区間は空白として表示する（X 軸は `windowEndMs - lookbackMs` を開始とする）。
- 歩数グラフのモード色分けは、`MotionSample` から再構成した状態タイムラインを `GpsUtil.modesAt()` で 1 回だけ前進走査して各描画点へ割り当てる。各描画点ごとに状態履歴を先頭から再走査しない。
- `HomeScreen` は現在表示中の開始時刻 / 終了時刻をラベル表示し、時間移動結果を視覚的に確認できるようにする。
- `HomeViewModel.commitGraphViewport()` は、保持データ期間が lookback より短い場合でも空区間にならないよう、表示終端時刻の下限を最新時刻側へ安全に丸める。
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
- Windows viewer の corrected 地図表示は、Python 側で整えた `gpsPoints` に対して `STAY` 集約だけを適用し、`CONSTANT_MOVE` は記録された GPS 点列をそのまま使う。viewer 独自の停止標準化・GPS 平滑化を map 描画時には重ねず、Android アプリとの差分が出ないことを優先する。
- viewer の地図折れ線と歩数グラフは、`DisplayMode` ごとにセグメント分割して色分けする。
  - `DEVICE_STILL = 黒`
  - `STOPPED = グレー`
  - `WALKING = 青`
  - `VEHICLE = 赤`
- 地図の始点 / 終点マーカーは従来どおり独立描画とし、モード色分けの影響を受けない。

## 4. 現在の要注意点
- `PresQnh` は GPS 不在時に欠損になりうるため、表示側は欠損前提で扱う必要がある。
