# ToDo リスト

最終更新: 2026-05-06

## 状態判定・GPS 制御
- 4 モード判定（`DEVICE_STILL / STOPPED / WALKING / VEHICLE`）が実機で自然か確認し、閾値を微調整する
- 新状態管理の GPS 取得間隔 (`trK4遷移時即時 / STK4=2秒 / W1=5秒 / その他=5秒から5秒ずつ最大30秒まで引き延ばし`) が、意図どおり切り替わるか実機確認する
- `trKDirectionWindowMs=2s`、`trKWindowMs=1s`、`trK1AvgThreshold=0.015`、`trKAvgThreshold=0.05`、`trKAvgUpperThreshold=0.28`、`trKRatioThreshold=0.65`、`stK1AvgThreshold=0.015`、`stK4AvgThreshold=0.08`、`stK4AvgUpperThreshold=0.28`、`stK4RatioThreshold=0.75` で車両・電車の減速、右左折、カーブ、発進時に `TRK_GPS_IMMEDIATE` が出て GPS が起き、徒歩・停止中の強い手持ち揺れが抑制されるか実機確認する
- 補助ログ CSV / Room に保存される `KAvg / KScalarAvg / KVariance / TrKAvg` が実機ログで埋まり、「車移動だが STK4 が出ない」区間の原因切り分けに使えるか確認する
- `TRK_GPS_IMMEDIATE` 後に `GPS_BURST_START / CANDIDATE / ACCEPT / REJECT` が出力され、GPS 空欄が続く区間で「候補なし」か「低精度棄却」かを区別できるか実機ログで確認する
- その他状態の GPS 間隔伸長が、採用可能GPS（位置あり、精度80m以下）を得られた場合だけ進み、欠損・低精度では5秒へ戻るか実機確認する
- `TYPE_ROTATION_VECTOR` 優先の世界座標変換で `KAccelSource=WORLD_ROTATION_VECTOR` が補助CSV/Roomに保存されるか、また右左折・カーブ時に `trK` の判定で GPS 即時取得が増えるか実機確認する
- `gpsStretchMaxMs=30000` でその他状態の GPS 間隔が 30 秒上限で止まり、将来 `0` にした場合は上限なしで伸びるか実機確認する
- 定速領域確定後、区間序盤の暫定 `STOPPED` が `CONSTANT_MOVE=VEHICLE` へバックフィルされ、地図・歩数グラフ・補助ログ CSV にグレーとして残らないか実機確認する
- 進行中の未確定停止区間では `confirmedMode` が空欄のまま保存され、区間確定後だけ確定キャッシュとしてバックフィルされるか実機ログで確認する
- 電車・車ログで、保存済み `ConfirmedMode=WALKING` でも `ConstantRegionSpeedKmh` または表示対象GPS速度から高速移動へ再評価され、地図と歩数グラフの長い青線が赤へ倒れるか確認する
- W1時のGPS速度/歩数速度比較 (`walkingVehicleSpeedThresholdKmh=10`, `walkingStepLengthM=0.60`, `walkingGpsStepMismatchThresholdKmh=5`) で、車内の歩数誤検出が `VEHICLE` へ倒れ、実徒歩が過度に `VEHICLE` へ倒れないか実機確認する
- 歩行判定を `StepDeltaWindow >= 2` かつ最終歩行イベントから 5 秒以内に変更したため、徒歩中に過度な `STOPPED / VEHICLE` 穴が増えないか実機確認する
- `Sensor.TYPE_LINEAR_ACCELERATION` が実機で取得できるか、未対応端末では raw accelerometer fallback が働くか確認する
- 定速領域の直線近似と `stay / constant move` 判定が、停止中・電車定速走行中・徒歩中の代表ログで自然か viewer と実機ログで確認する
- `AccelManager` / `StepManager` 分離後、実機で加速度イベント・`TYPE_STEP_DETECTOR`・`TYPE_STEP_COUNTER` が `MotionStateManager` に届き、`KStatus(stK) / WStatus / StepsDelta` が継続更新されるか確認する

## 表示
- 地図画面と地図ウィジェットの `>` 進行方向マーカーが viewer と同じ向き・密度で表示されるか実機確認する
- 地図画面と地図ウィジェットの `>` 進行方向マーカーが曲線区間にも入り、長い曲線で方向が読めるか実機確認する
- 地図画面・地図ウィジェットの折れ線色が `黒 / グレー / 青 / 赤` に統一され、旧グラデーションが出ないか実機確認する
- 地図ウィジェットの折れ線が広域表示でも細くなりすぎず、launcher の `fitXY` 表示後も視認できるか実機確認する
- 歩数グラフが `黒 / グレー / 青 / 赤` のモード色で自然に見えるか app / widget 実機確認する
- 現在歩数表示が app / widget ともラベル・数値とも青になっているか実機確認する
- app / widget のグラフに 00:00 の細い白い縦線が表示され、既存の目盛り線と混ざって見にくくならないか実機確認する
- 補正気圧系列が白で表示され、既存の背景や他系列と視認性が崩れていないか実機確認する
- 完全停止 `DEVICE_STILL` の 2m 停止標準化が実機でも十分に GPS ブレを抑えられているか確認する
- 現在点マーカーの白地青リングが app / widget とも同じ見え方になっているか実機確認する
- 標高 / 補正気圧の欠損時に app / widget とも `-` ではなく直近有効値へフォールバックしているか実機確認する
- `表示する期間=24時間` のとき、widget のグラフが app 初期表示と同じ固定時間窓になっているか実機確認する
- `DEVICE_STILL / STOPPED` の連続停止領域が app 地図・地図ウィジェットの折れ線で代表点 1 点に畳まれて表示され、旧ログの `STAY` 領域も保存済み stay point 1 点に畳まれて表示されるか実機確認する
- 地図画面・地図ウィジェットの移動区間スプライン補間が、STAY 点を歪ませず、徒歩から高速移動またはその逆の境界を自然につなげるか実機確認する
- 地図系表示の GPS 凍結復帰検知（同一座標 2 分以上 + 直前点から 200m / 300km/h 以上のジャンプ）で、凍結中区間がオレンジ破線として描かれ、通常線で結ばれないか実機・viewer 双方で確認する
- GPS 欠損補間（前後 1〜10 分なら 30 秒間隔の表示用補間点を挿入）が、Android アプリ・地図ウィジェット・viewer で同じイメージになっているか確認する
- 歩数がほぼ無い移動区間が `WALKING` に倒れず `VEHICLE` 寄りへ補正されるか、電車・車ログで実機確認する
- ホーム画面グラフのピンチ / フリック操作が重くならず、画面再起動や操作取りこぼしが起きないか実機確認する
- ホーム画面グラフの初回表示が数秒単位で待たされず、初回計算中表示から速やかに系列表示へ移るか実機確認する
- グラフウィジェットをタップして既存アプリ画面へ戻った場合でも、ホームグラフが DB 最新ログ時刻を基準に表示され、`表示範囲にデータがありません` / `データを記録中...` のまま残らないか実機確認する
- グラフの左スワイプとピンチインで古い時間帯を自然に辿れるか実機確認する

## ウィジェット
- ウィジェット更新が設定秒数どおりサービス由来で継続し、ホスト由来更新や設定変更時の強制更新ではクリック復旧を含めて正しく描画されるか実機確認する
- 地図ウィジェットの背景タイル viewport キャッシュが、同一 viewport の周期更新で再取得を行わないか debug log で確認する
- グラフウィジェットの履歴取得範囲が「表示窓 + 左 30 分の補間文脈」だけに限定され、`lookback * 2` の余分な読込が発生しないか確認する

## import / export / ストレージ
- 手動 export で `EXPORT_STANDARD_REQUESTED / OK / FAILED` と `EXPORT_MOTION_REQUESTED / OK / FAILED` が debug log に残り、`openOutputStream==null` や書き込み例外時に 0 byte ファイルを成功扱いしないことを実機確認する
- 状態イベントログの手動 export がページ単位（1000 件ごと）で書き出され、件数が多い場合でも `EXPORT_MOTION_FAILED reason=emptyDocument` にならないか確認する
- 状態イベントログ化後、旧 `motion_metrics` バックアップと新 `motion_events` バックアップの両方を viewer で読み、`AC / STAY / CMOV / tON` ラベルと前方補完された表示モードが一致するか確認する
- `# EVENT` コメントが日次 CSV と手動バックアップ CSV の両方へ出力されるか実機確認する
- 気圧を 3 分ごとのみ保存する新主記録で、ホーム画面・グラフウィジェット・Windows viewer が直近有効気圧を自然に表示できるか実機確認する
- `log_converter` が生成した標準 CSV を端末 import して挙動確認する

## サービス起動・運用
- アプリ更新後 / 端末再起動後に `BootReceiver` の `SERVICE_RECEIVER` / `SERVICE_RESTART_SKIPPED` ログだけが出て、サービス自動再起動を試みないことを実機ログで確認する
- 利用者がアプリを開いた時に `MainActivity` 経由で `LoggingService` が確実に起動し、`SERVICE_START_COMMAND` と `SERVICE_FIRST_RECORD_SUCCESS` のペアが debug log に残るか実機確認する
- アプリを開かない期間がある場合の記録欠落が、利用者にとって許容範囲か運用観察する（自動再開を撤廃したトレードオフの確認）
- 停止中に再起動後または 03:00 越え後でも、GPS bootstrap / 再利用で記録が再開するか実機確認する

## 信頼性・性能
- `LoggingService.onSensorChanged` 経由で発生していた OutOfMemoryError（2026-04-28、2026-05-05）の再発有無を共有デバッグログで継続監視する
- OOM の根本原因として、加速度イベントごとに `motionScope.launch` を発行する経路が長期稼働でキューに溜まっていないかを `verboseDebugLogEnabled` で計測し、必要なら `AccelManager` 側でバッチ消費する形へ寄せる
- 3 秒スロット化後の主記録件数増加が表示性能とストレージ使用量に与える影響を実機確認する
- 電池消費が改善しているか、しばらく実機運用して確認する

## 解析・将来検討
- 3 秒主記録の長期容量対策として、データ種別ごとの間引き・再集約方針を設計する
- 気圧は長期保存時にかなり粗く再集約できる前提で、時間窓平均などの間引き方式を検討する
- GPS はスプライン補間でつなぐ前提で、曲線誤差が小さい点を落とす間引き方式を検討する
- `MotionStateParamsProvider.current()` 参照に統一された新状態管理を、今後の設定 UI / 実験値読み込みへどう接続するか決める
- `MotionStateParams.stepResetHour` と表示側の `GpsUtil.LOGGING_RESET_HOUR` を、将来的に 1 つの設定源へ統合するか決める
- viewer の `補正あり` 固定描画が Android の地図・歩数グラフと同じ見え方になるか、代表ケース（`2026-04-12 10:50-11:30`）で回帰確認する
