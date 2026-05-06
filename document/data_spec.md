# データ形式仕様書

最終更新: 2026-05-06

## 1. 目的

本仕様書は、GpsPressureLogger で扱う記録データの標準形式を定義する。

対象:

- アプリのインポート
- アプリのエクスポート
- Windows 上の Python コンバータ
- 外部保存ログ

基本方針:

- 標準形式は 1 種類に統一する
- StepWalk / Barograph など固有形式は、必要に応じて Python 側で事前変換する
- デバッグログは記録データと混在させない
- アプリ内部の主記録生成周期は 3 秒を基準とする
- 主記録は 3 秒スロットごとに欠損列を含む 1 レコードを持てる
- 気圧 (`PresRaw` / `PresQnh`) は通常 3 分ごとのみ値を保存し、それ以外の主記録行では空欄を許容する。GPS と歩数の記録周期はこの間引きの影響を受けない
- 交換 CSV は不定間隔レコードも許容する
- 地図の線色、進行方向マーカー、歩数グラフ色、補正気圧系列色、停止標準化や動的 GPS 取得間隔などの表示・取得制御変更は、本仕様で定義する CSV / Room データ形式を変更しない
- SAF export では、出力先 URI を開けない場合や書き込み例外が出た場合を失敗として扱い、0 byte ファイルを正しいバックアップとは見なさない

## 2. 標準交換形式

標準交換形式は CSV とする。

コメント行:

- `#` で始まる行はコメント行として許可する
- コメント行はヘッダ前、データ行の間、末尾のいずれにも置ける
- import / 解析時はコメント行と空行を読み飛ばす
- 運用イベントを残す場合は `# EVENT <timestampMs> <message>` 形式を推奨する

ヘッダ:

```csv
Timestamp,Lat,Lon,Alt,PresRaw,PresQnh,StepsDelta,GpsAccuracy
```

旧ヘッダ `Timestamp,Lat,Lon,Alt,PresRaw,PresQnh,StepsDelta` も import 互換として受け入れる。旧 CSV では `GpsAccuracy` は `null` として扱う。

## 2.1 補助センサー判定ログ形式

モード判定前の連続値は、標準交換形式とは別ファイルの CSV で保持する。

ヘッダ:

```csv
Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s,KStatus,KRawStatus,KAvg,KScalarAvg,KDirectionalityRatio,KVariance,TrKStatus,TrKRawStatus,TrKAvg,TrKDirectionalityRatio,WStatus,StepDeltaWindow,GpsIntervalMs,GpsImmediate,ConfirmedMode,ConstantRegionKind,ConstantRegionSpeedKmh,ConstantRegionStartLat,ConstantRegionStartLon,ConstantRegionEndLat,ConstantRegionEndLon,ConstantRegionStayLat,ConstantRegionStayLon,ConstantRegionDirectionDeg
```

`AccelStddev3s` / `AccelMad3s` は旧方式互換列であり、新方式では空欄を許容する。新方式の正式な加速度状態は `stK` と呼ぶ。CSV / Room では既存互換のため `KStatus` / `KRawStatus` を維持し、値は新方式では `STK1 / STK2 / STK4` を保存する。旧値 `K1 / K2_K3 / K4` は import / 表示再構成時に互換解釈する。外部バックアップ CSV は、現行判定で使う項目だけを出力する。

2026-05 以降の新規補助ログは、3 秒ごとの全サンプルではなく状態変化イベントを中心に保存する。表示側は時刻順に読み込み、`KStatus` / `TrKStatus` / `WStatus` / `ConfirmedMode` / `ConstantRegionKind` を前方補完して地図・グラフの状態を復元する。旧 `motion_metrics` の 3 秒サンプル形式は import / viewer 互換として引き続き受け入れる。

追加列:

- `KStatus`: 互換列名。新方式では 3 秒スロット加速度状態 `stK` を保存し、値は `STK1 / STK2 / STK4` とする
- `KRawStatus`: 互換列名。新方式では `stK` の raw 判定を保存する。現行の `stK` は 3 秒スロット単位判定のため通常 `KStatus` と同じ値になる
- `KAvg`: 3 秒スロット全体の 3 軸線形加速度を、可能なら世界座標（East / North / Up）へ変換したうえで軸ごとに平均し、その平均ベクトルのノルムを取った値。振動ではなく、スロット全体として方向性が残る加速・減速・旋回を捉えるために使う
- `KScalarAvg`: 3 秒スロット全体の各サンプル加速度ノルム `|a_i|` の平均。動きの総量を表す
- `KVariance`: 3 秒スロット全体の変換済み合成加速度ノルムの分散。平均加速ではなく、振動・揺れの大きさを捉えるために使う
- `TrKStatus` / `TrKRawStatus`: 1 秒窓 `trK` の確定値 / raw 候補値。値は `ON / OFF`
- `StKAvg`: 3 秒窓の平均水平加速度ベクトルの大きさ `k`。旧ヘッダ `KAvg` は import 互換として受け入れる
- `StKRatio`: 3 秒窓の射影標準偏差比 `sigma / k`。旧ヘッダ `KDirectionalityRatio` は import 互換として受け入れる
- `TrKAvg`: 1 秒窓の平均水平加速度ベクトルの大きさ `k`
- `TrKRatio`: 1 秒窓の射影標準偏差比 `sigma / k`。`k` がほぼ 0 の場合など、ratio が成立しないときは空欄を記録する。旧ヘッダ `TrKDirectionalityRatio` は import 互換として受け入れる
- `WStatus`: `W1 / W2`
- `StepDeltaWindow`: `wWindowMs` 内の歩行イベント数 / 歩数増分合計。`WStatus=W1` は `StepDeltaWindow >= wStepDeltaThreshold` かつ最終歩行イベントから `walkingThresholdMs` 以内で判定する
- `GpsIntervalMs`: 新状態管理が決めた GPS 要求間隔
- `GpsImmediate`: 直前の 3 秒スロット内で `trK` ON 遷移による即時 GPS 取得要求が発生した場合、または `stK4` へ新規遷移した場合は `1`、それ以外は `0`。CSV では `0 / 1`、Room では `Boolean?`、空欄は `null` として扱う
- `# EVENT GPS_BURST_*`: コメント行として出力される加速度トリガー時 GPS burst 要求の診断ログ。`START`、`CANDIDATE`、`ACCEPT`、`REJECT`、`STOP`、`SKIPPED_COOLDOWN`、`UNAVAILABLE`、`SECURITY_ERROR` を使い、CSV の通常データ列形式は変更しない
- `ConfirmedMode`: 確定キャッシュとして保存するアプリ表示用の `DEVICE_STILL / STOPPED / WALKING / VEHICLE / UNKNOWN`。現在まで継続中の未確定領域では空欄を許容する。
- `ConstantRegionKind`: 定速領域終了時の `NONE / STAY / CONSTANT_MOVE`
- `ConstantRegionSpeedKmh`: 定速領域の直線近似から求めた平均速度
- `ConstantRegionStartLat` / `ConstantRegionStartLon`: 定速領域の直線近似 `g(t_s)` による始点座標
- `ConstantRegionEndLat` / `ConstantRegionEndLon`: 定速領域の直線近似 `g(t_e)` による終点座標
- `ConstantRegionStayLat` / `ConstantRegionStayLon`: `STAY` 時の stay point 座標。原則として `(g(t_s)+g(t_e))/2` を使う
- `ConstantRegionDirectionDeg`: `CONSTANT_MOVE` 時の移動方向

`ConstantRegionKind=STAY` は最終表示状態ではなく、定速領域解析の中間判定とする。表示色・状態名・後続の再構成では `ConfirmedMode` を正式な状態として使う。たとえば閉じた `STAY + STK1 + W2` は `ConfirmedMode=DEVICE_STILL`、閉じた `STAY + STK2 + W2` は `ConfirmedMode=STOPPED` として保存する。

`ConfirmedMode=WALKING` は保存時点の確定キャッシュだが、表示再構成では `ConstantRegionSpeedKmh` と表示対象の GPS 点列から再評価する。GPS速度または `ConstantRegionSpeedKmh` が `walkingVehicleSpeedThresholdKmh` 以上、または歩数推定速度との差が `walkingGpsStepMismatchThresholdKmh` 以上なら、表示色は `VEHICLE` として扱う。CSV / Room の列は増やさず、既存列の解釈だけで行う。

定速領域継続中も `ConstantRegionKind` は暫定値として更新される。区間が閉じるまで判定は変化しうるため、`ConfirmedMode` は空欄のままにする。表示側は最後に `ConfirmedMode` が入っている行を確定ポイントとし、確定ポイント以後の行だけ、この暫定値で描き替える。`STAY` の間は地図上で stay point 1 点へ集約し、`CONSTANT_MOVE` の間は記録された GPS 点列をそのまま扱う。

定速領域が閉じて `STAY / CONSTANT_MOVE` が確定した場合、アプリは当該区間の既存 `MotionSample` を確定結果でバックフィルする。標準列は増やさず、同じ `Timestamp` の `ConfirmedMode`、`ConstantRegionKind`、`ConstantRegionSpeedKmh`、`ConstantRegionStart*`、`ConstantRegionEnd*`、`ConstantRegionStay*`、`ConstantRegionDirectionDeg` を置き換える。これにより、`ConfirmedMode` は「ここまでは後から変わらない」確定キャッシュとして扱える。

import は、旧ヘッダ `Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s` と、`ConstantRegionSpeedKmh` までの旧拡張ヘッダも受け入れる。旧 CSV では存在しない新方式列は `null` として扱う。

既存の日次補助ログファイルが旧ヘッダで残っている場合、アプリは追記前にヘッダを新形式へ置き換える。旧行は先頭 5 列だけを持つ短い行として残し、新方式列は import 時に `null` として扱う。

方針:

- 新規記録では状態変化点を中心に 1 レコードを記録する
- 標準交換形式の記録 CSV とは混在させない
- 判定用の基礎指標に加えて、新方式の `stK / trK / w-status / 確定モード / GPS 取得判断` を保持する。`trK` は表示モードへ直接は使わないが、`trK=ON` や `STK4` 変化点では `TrKAvg / TrKRatio / StKAvg / StKRatio` を保存し、閾値解析に使う
- 加速度座標変換の source（RotationVector / accel+mag / device / norm fallback）は `KAccelSource` として CSV / Room に保存する。デバッグログの `accelSource` も実機調査用に維持する
- 欠損値は空欄で表現する
- これらの値は `完全停止 / 停止 / 徒歩 / 高速移動` のモード判定に利用する

## 3. 列仕様

### 3.1 Timestamp

- 必須
- Unix time milliseconds
- ファイル内で昇順
- ファイル内で一意
- 丸めなし
- 計測側都合による不定間隔をそのまま保持する

### 3.2 Lat

- 任意
- 緯度
- 欠損可

### 3.3 Lon

- 任意
- 経度
- 欠損可

### 3.4 Alt

- 任意
- その時刻の高度値
- 通常は GPS 由来だが、他ソース由来でもよい
- 欠損可

### 3.5 PresRaw

- 任意
- 実測気圧
- 単位は hPa
- 欠損可

### 3.6 PresQnh

- 任意
- 補正気圧
- 単位は hPa
- 欠損可
- GPS 高度がその時刻に有効な場合のみ算出する

### 3.7 StepsDelta

- 任意
- 前回計測から増えた歩数
- 負値は不可
- 欠損可

意味:

- 空欄: その時刻に歩数情報そのものがない
- `0`: 歩数情報はあるが、前回から増えていない
- `1` 以上: 前回から歩数が増えた

### 3.8 GpsAccuracy

- 任意
- GPS 測位精度
- 単位は m
- 欠損可
- 地図外れ値除去や後解析で、保存時点の GPS 精度を再利用するために保持する

## 4. 欠損値ルール

- 欠損値は空欄で表現する
- `0` は実値として扱う
- `Lat=0` や `Lon=0` を欠損表現として使わない
- `#` で始まる行はデータ行ではなくコメント行として扱う

## 5. レコードの考え方

1 レコードは、「ある時刻に取得できた値の集合」とする。

アプリ内部の主記録方針:

- 3 秒ごとに 1 スロットを持つ
- そのスロットでは GPS、標高、気圧、歩数増分の欠損を許容する
- GPS 集約では、直前に採用済みの GPS を `before` として参照してよい
- 移動中（徒歩 / 高速移動）:
  - スロット内 GPS プール 0 件: 欠損
  - スロット内 GPS プール 1 件: その値を採用
  - スロット内 GPS プール 2 件以上: `before` とプール内 GPS 群から 3 次スプライン補間を作り、スロット時刻の `Lat / Lon` を採用
- 完全停止かつ直前スロットも完全停止:
  - スロット内 GPS プール 0 件: 欠損
  - スロット内 GPS プール 1 件以上: `before` を含めた平均の `Lat / Lon` を採用
- 完全停止かつ直前スロットが完全停止ではない:
  - スロット内 GPS プール 0 件: 欠損
  - スロット内 GPS プール 1 件以上: `before` を含めない平均の `Lat / Lon` を採用
- 停止 `STOPPED` は、完全停止寄りの平均化ルールを使う
- `Alt` は GPS 座標の平均・スプライン補間には含めず、標高取得関数が保持する直近の有効 GPS 標高を採用する
- 気圧は 3 分ごとの記録スロットだけ最新保持値を採用し、それ以外の主記録行では欠損とする。表示では直近有効値を参照する
- 歩数は 3 秒区間の増分を採用し、初期基準未確定時は `0` としてよい

許可される例:

- GPS のみ
- 気圧のみ
- 歩数のみ
- GPS と気圧のみ
- GPS と歩数のみ
- 気圧と歩数のみ
- GPS と気圧と歩数

## 6. 同一 Timestamp の扱い

### 6.1 標準 CSV 内

- 同一ファイル内で `Timestamp` 重複は禁止

### 6.2 コンバータ内

- 複数ソースを統合する場合は、同一 `Timestamp` の情報を 1 レコードへマージする
- 現時点の想定では、ソース間は相補的であり、同一列同士の競合は原則少ない

### 6.3 アプリのインポート

- 既存 DB に同一 `Timestamp` のレコードがある場合のみ競合解決を行う
- 競合解決方式はアプリで選択可能とする
  - 上書き
  - 既存優先

## 7. 外部保存仕様

### 7.1 目的

外部保存は次の用途に用いる。

- バックアップ
- 復元
- PC 上での解析
- 形式変換

### 7.2 保存形式

- 標準交換形式と同じ CSV を使用する
- 日次 CSV と手動バックアップ CSV では、必要に応じて `# EVENT <timestampMs> <message>` コメントを含めてよい
- 手動バックアップファイル名は `gps_pressure_full_backup_yyyyMMdd_HHmmss.csv` 形式を基本とする
- 日常ログは app-specific external storage を優先し、利用不可時のみ内部保存へフォールバックする
- 日常ログファイルは 03:00 区切り日単位で `gps_log_yyyyMMdd.csv` とする
- 状態イベントログの日次ファイルは 03:00 区切りで `motion_events_yyyyMMdd.csv` とする
- 状態イベントログの手動バックアップは `gps_pressure_motion_events_backup_yyyyMMdd_HHmmss.csv` を基本とする
- 旧補助ログ `motion_metrics_yyyyMMdd.csv` / `gps_pressure_motion_metrics_backup_*.csv` は互換入力として扱う
- 日次 CSV への追記は逐次書き込みではなく、既定 100 件ごとのまとめ書き出しを許容する
- まとめ書き出し前のレコードはメモリバッファ上に保持し、手動 export、日次境界、サービス終了などのタイミングで強制フラッシュしてよい

### 7.3 デバッグログ

- デバッグログは別ファイルとする
- ただし重要イベントの要約は、記録 CSV に `# EVENT` コメントとして併記してよい
- 補助センサー判定ログもデバッグログとは別ファイルとする

## 8. Room 内部表現の方針

Room は内部正規形式とし、標準交換形式および補助センサー判定ログ形式の上位互換として扱う。

方針:

- 意味は標準交換形式に合わせる
- 欠損は可能な限り `null` で保持する
- 内部列名は実装に合わせてよい
- 交換時は本仕様の CSV に変換する
- 補助センサー判定ログは主記録とは別テーブルまたは別ストアで保持する

## 9. 変換責務

### 9.1 アプリ

- 標準形式の import / export を担当する
- 必要に応じて補助センサー判定ログ CSV の import / export も担当する
- StepWalk / Barograph 個別形式の直接解釈は原則行わない方向で整理する

### 9.2 Python コンバータ

- StepWalk / Barograph など個別形式を標準 CSV に変換する
- 必要に応じて複数ソースを `Timestamp` 単位で統合する
- 変換出力は標準 CSV ヘッダを持つ `gps_pressure_full_backup*.csv` を基本とする
- 補助センサー判定ログは現時点では Python コンバータの入力対象外とする

## 10. 既知の今後検討事項

- Room の nullable 化方針
- `Alt` の由来識別列が必要かどうか
- `Source` 列や `Flags` 列の追加要否
- 日次ログファイル運用と単一バックアップファイル運用の整理
- 補助センサー判定ログの import UI を主記録 import と分けるかどうか
