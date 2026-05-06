# Room 内部データ仕様書

最終更新: 2026-05-06

## 1. 目的

本仕様書は、GpsPressureLogger が Room へ保存する内部正規データ形式を定義する。

基本方針:

- Room はアプリ内部の正規ストアとする
- CSV 標準交換形式の上位互換として扱う
- 欠損値は可能な限り `null` で保持する
- import / export 時に標準 CSV へ変換可能であること

関連仕様:

- [data_spec.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\data_spec.md)

## 2. 役割

Room は次を担う。

- 最新値の取得
- グラフ描画用の履歴取得
- 地図表示用の履歴取得
- import 後の統合結果の保存
- export 元データの保持

Room は表示都合の派生値ではなく、できるだけ元の意味に近い内部正規値を保持する。

保存方針:

- Room は正規ストアなので、ファイル書き出しキューとは分離して即時保存する
- 日次 CSV や補助ログ CSV は遅延書き出しを許容するが、Room は表示整合性を優先して遅延させない

## 3. テーブル方針

現時点では、主記録と状態イベントログを分けた 2 テーブル構成を基本とする。`motion_samples` という既存テーブル名は維持するが、新規記録では 3 秒ごとの全サンプルではなく状態変化点を中心に保存する。

想定テーブル名:

- `log_entries`
- `motion_samples`

## 4. カラム仕様

### 4.1 主キー

- `id: Long`
  - 自動採番
  - 内部識別用

### 4.2 時刻

- `timestamp: Long`
  - 必須
  - Unix time milliseconds
  - 一意制約対象
  - アプリ内での論理キー

### 4.3 位置

- `latitude: Double?`
  - nullable
- `longitude: Double?`
  - nullable

### 4.4 高度

- `altitude: Double?`
  - nullable
  - 通常は GPS 由来
  - 他ソース由来も許可

### 4.5 気圧

- `pressure_raw: Float?`
  - nullable
  - 実測気圧
  - 単位は hPa
- `pressure_qnh: Float?`
  - nullable
  - 補正気圧
  - 単位は hPa
  - GPS 高度がその時刻に有効な場合のみ保存する

### 4.6 歩数

- `steps_delta: Int?`
  - nullable
  - その時刻の「前回計測から増えた歩数」

- `legacy_step_count: Int?`
  - nullable
  - 旧スキーマ互換の累積歩数
  - 新規記録では使用しない
  - 既存端末データの表示互換と migration 用にのみ保持する

意味:

- `null`: 歩数情報なし
- `0`: 歩数情報あり、増分なし
- `1` 以上: 増分あり

### 4.7 補助センサー判定ログ

- `motion_samples.timestamp: Long`
  - 必須
  - Unix time milliseconds
  - 一意制約対象
  - 3 秒ごとの観測基準時刻

- `motion_samples.accel_stddev_3s: Float?`
  - nullable
  - 直近 3 秒ウィンドウの加速度動き量の標準偏差

- `motion_samples.accel_mad_3s: Float?`
  - nullable
  - 直近 3 秒ウィンドウの加速度動き量の平均絶対偏差

- `motion_samples.step_delta_3s: Int?`
  - nullable
  - その 3 秒ウィンドウで観測された歩数増分

- `motion_samples.step_rate_3s: Float?`
  - nullable
  - `step_delta_3s / 実効ウィンドウ秒数`

- `motion_samples.kStatus: String?`
  - nullable
  - 互換列名。新方式では 3 秒スロット加速度状態 `stK` の値 `STK1 / STK2 / STK4` を保存する。旧値 `K1 / K2_K3 / K4` は互換解釈する

- `motion_samples.kRawStatus: String?`
  - nullable
  - 互換列名。新方式では `stK` の raw 判定を保存する。現行の `stK` はスロット単位判定なので通常 `kStatus` と同じ

- `motion_samples.kAvg: Float?`
  - nullable
  - `stK` 判定用に、前回 base_cycle から今回 base_cycle までの 3 軸線形加速度を可能なら世界座標へ変換したうえで軸ごとに平均し、その平均ベクトルのノルムを取った値
  - 外部 CSV では `StKAvg` として出力する。旧 CSV の `KAvg` は import 互換として受け入れる

- `motion_samples.kScalarAvg: Float?`
  - nullable
  - 3 秒スロットの各サンプル加速度ノルム平均

- `motion_samples.kDirectionalityRatio: Float?`
  - nullable
  - `kAvg / kScalarAvg`
  - 外部 CSV では `StKRatio` として出力する。旧 CSV の `KDirectionalityRatio` は import 互換として受け入れる

- `motion_samples.kVariance: Float?`
  - nullable
  - `stK` 判定用に、前回 base_cycle から今回 base_cycle までの変換済み合成加速度ノルム分散

- `motion_samples.kMaxMagnitude: Float?`
  - nullable
  - 3 秒スロット内の合成加速度ノルム最大値

- `motion_samples.kConfidence: Float?`
  - nullable
  - `stK` 判定の参考信頼度

- `motion_samples.kAccelSource: String?`
  - nullable
  - 3 秒スロットの加速度座標変換 source
  - 値: `WORLD_ROTATION_VECTOR` / `WORLD_ACCEL_MAG` / `DEVICE` / `NORM_ONLY`

- `motion_samples.trKStatus: String?`
  - nullable
  - 1 秒窓 `trK` の確定値。`ON / OFF`

- `motion_samples.trKRawStatus: String?`
  - nullable
  - 1 秒窓 `trK` の raw 候補値。`ON / OFF`

- `motion_samples.trKAvg: Float?`
  - nullable
  - 1 秒窓の 3 軸ベクトル平均ノルム

- `motion_samples.trKScalarAvg: Float?`
  - nullable
  - 1 秒窓の各サンプル加速度ノルム平均

- `motion_samples.trKDirectionalityRatio: Float?`
  - nullable
  - `trKAvg / trKScalarAvg`
  - 外部 CSV では `TrKRatio` として出力する。算出不能時は null を保存する。旧 CSV の `TrKDirectionalityRatio` は import 互換として受け入れる

- `motion_samples.trKMaxMagnitude: Float?`
  - nullable
  - 1 秒窓内の合成加速度ノルム最大値

- `motion_samples.trKHorizontalMaxMagnitude: Float?`
  - nullable
  - 1 秒窓内の水平面加速度最大値

- `motion_samples.trKConfidence: Float?`
  - nullable
  - `trK` 判定の参考信頼度

- `motion_samples.trKAccelSource: String?`
  - nullable
  - 1 秒窓 `trK` の加速度座標変換 source
  - 値: `WORLD_ROTATION_VECTOR` / `WORLD_ACCEL_MAG` / `DEVICE` / `NORM_ONLY`

- `motion_samples.wStatus: String?`
  - nullable
- 歩行状態。`W1 / W2`。現行判定では `stepDeltaWindow >= wStepDeltaThreshold` かつ最終歩行イベントから `walkingThresholdMs` 以内なら `W1`

- `motion_samples.stepDeltaWindow: Int?`
  - nullable
  - w-status 判定窓内の歩数増分合計

- `motion_samples.gpsIntervalMs: Long?`
  - nullable
  - 新状態管理が決めた GPS 要求間隔

- `motion_samples.gpsImmediate: Boolean?`
  - nullable
  - 即時 GPS 取得要求の有無
  - SQLite では `INTEGER` として保存し、`true` を `1`、`false` を `0`、欠損を `null` とする
  - 外部 CSV (`GpsImmediate`) は `1 / 0 / 空欄` で表現する

- `motion_samples.confirmedMode: String?`
  - nullable
  - 新方式で確定した `DEVICE_STILL / STOPPED / WALKING / VEHICLE / UNKNOWN`
  - 確定キャッシュとして扱う。現在まで続く未確定領域の暫定 `DEVICE_STILL / STOPPED` は保存せず、表示時に確定ポイント以後だけ再構成する。

- `motion_samples.constantRegionKind: String?`
  - nullable
  - 定速領域の暫定または確定 `NONE / STAY / CONSTANT_MOVE`

- `motion_samples.constantRegionSpeedKmh: Double?`
  - nullable
  - 定速領域の直線近似から求めた平均速度
  - 単位は km/h

- `motion_samples.constantRegionStartLat: Double?`
  - nullable
  - 定速領域の直線近似 `g(t_s)` による始点緯度

- `motion_samples.constantRegionStartLon: Double?`
  - nullable
  - 定速領域の直線近似 `g(t_s)` による始点経度

- `motion_samples.constantRegionEndLat: Double?`
  - nullable
  - 定速領域の直線近似 `g(t_e)` による終点緯度

- `motion_samples.constantRegionEndLon: Double?`
  - nullable
  - 定速領域の直線近似 `g(t_e)` による終点経度

- `motion_samples.constantRegionStayLat: Double?`
  - nullable
  - `STAY` 時の stay point 緯度

- `motion_samples.constantRegionStayLon: Double?`
  - nullable
  - `STAY` 時の stay point 経度

- `motion_samples.constantRegionDirectionDeg: Double?`
  - nullable
  - `CONSTANT_MOVE` 時の移動方向

方針:

- `motion_samples` は主記録 `log_entries` と別に保存する
- 主記録の復元可否に影響しない補助データとして扱う
- 新方式では表示再構成の揺れを避けるため、確定モード `confirmedMode` も保存する
- `confirmedMode` が保存された最後の行を表示側の確定ポイントとし、それ以前は確定キャッシュを優先する。それ以後だけ raw の `kStatus(stK) / wStatus / constantRegionKind` から暫定表示を導出する。
- 加速度座標変換の source は `motion_samples.kAccelSource` に保存する。debug log の `accelSource` も実機調査用に維持する。
- 主記録 `log_entries` の `timestamp` は現行実装では 3 秒スロットの終了時刻を採用する

## 5. 欠損値ルール

Room では欠損値を `null` とする。

禁止事項:

- 欠損を `0`, `0.0`, 空文字で代用しない

理由:

- 実値の 0 と欠損を明確に区別するため
- import / export の意味崩れを防ぐため

## 6. 同一 timestamp の扱い

- `timestamp` は一意
- 同一 `timestamp` の別ソース情報は、1 レコードへマージする
- import 時に既存レコードと衝突した場合のみ競合解決を行う

## 7. マージルール

### 7.1 基本

既存値と新規値がある場合:

- 新規値が `null` なら既存値を維持
- 既存値が `null` で新規値があるなら新規値を採用
- 両方に値がある場合は、競合解決方針に従う

### 7.2 競合解決方針

- 上書き
- 既存優先

### 7.3 steps_delta

`steps_delta` は差分値なので、同一 `timestamp` の重複時は注意が必要である。

現時点の方針:

- 同一 `timestamp` で `steps_delta` が両方にある場合は競合とみなす
- 自動加算しない
- 競合解決方針に従う

互換方針:

- `steps_delta` が欠損し、`legacy_step_count` がある旧データでは、表示時に前行との差分を計算して扱う
- 新規 export / import の標準 CSV では `legacy_step_count` を交換しない
- アプリ起動時には、ローカル日次 CSV を走査して欠損している歩数情報を補完する

## 8. CSV との対応

標準 CSV と Room の対応は次のとおり。

- `Timestamp` -> `timestamp`
- `Lat` -> `latitude`
- `Lon` -> `longitude`
- `Alt` -> `altitude`
- `PresRaw` -> `pressure_raw`
- `PresQnh` -> `pressure_qnh`
- `StepsDelta` -> `steps_delta`
- `GpsAccuracy` -> `gpsAccuracy`

補助センサー判定ログ CSV との対応:

- `Timestamp` -> `motion_samples.timestamp`
- `AccelStddev3s` -> `motion_samples.accel_stddev_3s`
- `AccelMad3s` -> `motion_samples.accel_mad_3s`
- `StepDelta3s` -> `motion_samples.step_delta_3s`
- `StepRate3s` -> `motion_samples.step_rate_3s`
- `KStatus` -> `motion_samples.kStatus`
- `KRawStatus` -> `motion_samples.kRawStatus`
- `KAvg` -> `motion_samples.kAvg`
- `KScalarAvg` -> `motion_samples.kScalarAvg`
- `KDirectionalityRatio` -> `motion_samples.kDirectionalityRatio`
- `KVariance` -> `motion_samples.kVariance`
- `KMaxMagnitude` -> `motion_samples.kMaxMagnitude`
- `KConfidence` -> `motion_samples.kConfidence`
- `KAccelSource` -> `motion_samples.kAccelSource`
- `TrKStatus` -> `motion_samples.trKStatus`
- `TrKRawStatus` -> `motion_samples.trKRawStatus`
- `TrKAvg` -> `motion_samples.trKAvg`
- `TrKScalarAvg` -> `motion_samples.trKScalarAvg`
- `TrKDirectionalityRatio` -> `motion_samples.trKDirectionalityRatio`
- `TrKMaxMagnitude` -> `motion_samples.trKMaxMagnitude`
- `TrKHorizontalMaxMagnitude` -> `motion_samples.trKHorizontalMaxMagnitude`
- `TrKConfidence` -> `motion_samples.trKConfidence`
- `TrKAccelSource` -> `motion_samples.trKAccelSource`
- `WStatus` -> `motion_samples.wStatus`
- `StepDeltaWindow` -> `motion_samples.stepDeltaWindow`
- `GpsIntervalMs` -> `motion_samples.gpsIntervalMs`
- `GpsImmediate` -> `motion_samples.gpsImmediate`
- `ConfirmedMode` -> `motion_samples.confirmedMode`
- `ConstantRegionKind` -> `motion_samples.constantRegionKind`
- `ConstantRegionSpeedKmh` -> `motion_samples.constantRegionSpeedKmh`
- `ConstantRegionStartLat` -> `motion_samples.constantRegionStartLat`
- `ConstantRegionStartLon` -> `motion_samples.constantRegionStartLon`
- `ConstantRegionEndLat` -> `motion_samples.constantRegionEndLat`
- `ConstantRegionEndLon` -> `motion_samples.constantRegionEndLon`
- `ConstantRegionStayLat` -> `motion_samples.constantRegionStayLat`
- `ConstantRegionStayLon` -> `motion_samples.constantRegionStayLon`
- `ConstantRegionDirectionDeg` -> `motion_samples.constantRegionDirectionDeg`

CSV の空欄は Room では `null` として扱う。

## 9. 派生値の扱い

次の値は Room に常設保存しない。

- 今日の歩数
- 3時リセット後の累積歩数
- グラフ補間結果
- 移動平均値
- 地図クラスタリング結果
- 旧方式では最終的な移動モードラベルを常設保存しなかったが、新方式では確定済みの移動モードラベルだけを `confirmedMode` として保存する

これらは表示・集計時に導出する。

## 10. 実装メモ

現行実装メモ:

- `LogEntry` は nullable ベースへ移行済み
- `stepCount` は `stepsDelta` へ移行済み
- 欠損表現としての 0 使用は廃止済み
- `MotionSample` と `motion_samples` テーブルを追加し、補助センサー判定ログを主記録と分離して保持する
- Room version 7 から 8 への移行で、新方式の stK/w-status、GPS 判定、確定モード列を追加する。Room / CSV の列名は互換のため `kStatus` 系を維持する
- Room version 10 で `motion_samples.kAccelSource` を追加し、3 秒スロットごとの加速度座標変換 source を保存する
- Room version 11 で `motion_samples.kScalarAvg / kDirectionalityRatio / kMaxMagnitude / trK*` を追加し、3 秒 `stK` 指標と 1 秒 `trK` 指標の実測値を後から解析できるようにした
- 2026-05 以降の新規記録では `motion_samples` を状態イベントログとして使い、`KStatus / TrKStatus / WStatus / confirmedMode / constantRegionKind` の変化点を中心に保存する。表示側は前方補完で状態を復元する。旧 3 秒サンプル形式は import / viewer 互換として維持する
- destructive migration は使用しない

移行方針:

- 旧 version 4 DB からの移行時は新テーブルを作成してデータコピーする
- 旧 `stepCount` は新仕様の `stepsDelta` と意味が異なるため、自動変換せず `null` とする
- 位置・高度・気圧は旧 DB からそのまま引き継ぐ
