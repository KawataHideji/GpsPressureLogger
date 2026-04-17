# データ形式仕様書

最終更新: 2026-04-16

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
- 交換 CSV は不定間隔レコードも許容する
- 地図の線色、進行方向マーカー、歩数グラフ色、補正気圧系列色、停止標準化や動的 GPS 取得間隔などの表示・取得制御変更は、本仕様で定義する CSV / Room データ形式を変更しない

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
Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s,KStatus,KRawStatus,KAvg,KVariance,KConfidence,WStatus,StepDeltaWindow,GpsIntervalMs,GpsImmediate,ConfirmedMode,ConstantRegionKind,ConstantRegionSpeedKmh,ConstantRegionStartLat,ConstantRegionStartLon,ConstantRegionEndLat,ConstantRegionEndLon,ConstantRegionStayLat,ConstantRegionStayLon,ConstantRegionDirectionDeg
```

`AccelStddev3s` / `AccelMad3s` は旧方式互換列であり、新方式では空欄を許容する。新方式の正式な加速度判定値は `KAvg` / `KVariance` / `KStatus` とする。

追加列:

- `KStatus`: ヒステリシス適用後の `K1 / K2_K3 / K4`
- `KRawStatus`: ヒステリシス適用前の瞬間判定
- `KAvg`: k-status 解析窓内の合成加速度平均
- `KVariance`: k-status 解析窓内の合成加速度分散
- `KConfidence`: k-status 判定の参考信頼度
- `WStatus`: `W1 / W2`
- `StepDeltaWindow`: w-status 判定窓内の歩数増分合計
- `GpsIntervalMs`: 新状態管理が決めた GPS 要求間隔
- `GpsImmediate`: 即時 GPS 取得要求なら `1`、それ以外は `0`
- `ConfirmedMode`: アプリ表示用の `DEVICE_STILL / STOPPED / WALKING / VEHICLE / UNKNOWN`
- `ConstantRegionKind`: 定速領域終了時の `NONE / STAY / CONSTANT_MOVE`
- `ConstantRegionSpeedKmh`: 定速領域の直線近似から求めた平均速度
- `ConstantRegionStartLat` / `ConstantRegionStartLon`: 定速領域の直線近似 `g(t_s)` による始点座標
- `ConstantRegionEndLat` / `ConstantRegionEndLon`: 定速領域の直線近似 `g(t_e)` による終点座標
- `ConstantRegionStayLat` / `ConstantRegionStayLon`: `STAY` 時の stay point 座標。原則として `(g(t_s)+g(t_e))/2` を使う
- `ConstantRegionDirectionDeg`: `CONSTANT_MOVE` 時の移動方向

`ConstantRegionKind=STAY` は最終表示状態ではなく、定速領域解析の中間判定とする。表示色・状態名・後続の再構成では `ConfirmedMode` を正式な状態として使う。たとえば `STAY + K1 + W2` は `ConfirmedMode=DEVICE_STILL`、`STAY + K2_K3 + W2` は `ConfirmedMode=STOPPED` として保存する。

定速領域継続中も `ConstantRegionKind` は暫定値として更新される。区間が閉じるまで判定は変化しうるため、表示側は毎回この暫定値で描き替える。`STAY` の間は地図上で stay point 1 点へ集約し、`CONSTANT_MOVE` の間は通常の連続点として扱う。

import は、旧ヘッダ `Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s` と、`ConstantRegionSpeedKmh` までの旧拡張ヘッダも受け入れる。旧 CSV では存在しない新方式列は `null` として扱う。

既存の日次補助ログファイルが旧ヘッダで残っている場合、アプリは追記前にヘッダを新形式へ置き換える。旧行は先頭 5 列だけを持つ短い行として残し、新方式列は import 時に `null` として扱う。

方針:

- 3 秒ごとに 1 レコードを記録する
- 標準交換形式の記録 CSV とは混在させない
- 判定用の基礎指標に加えて、新方式の `k-status / w-status / 確定モード / GPS 取得判断` を保持する
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
- そのスロットでは GPS、高度、気圧、歩数増分の欠損を許容する
- GPS 集約では、直前に採用済みの GPS を `before` として参照してよい
- 移動中（徒歩 / 高速移動）:
  - スロット内 GPS プール 0 件: 欠損
  - スロット内 GPS プール 1 件: その値を採用
  - スロット内 GPS プール 2 件以上: `before` とプール内 GPS 群から 3 次スプライン補間を作り、スロット時刻の `Lat / Lon / Alt` を採用
- 完全停止かつ直前スロットも完全停止:
  - スロット内 GPS プール 0 件: 欠損
  - スロット内 GPS プール 1 件以上: `before` を含めた平均の `Lat / Lon / Alt` を採用
- 完全停止かつ直前スロットが完全停止ではない:
  - スロット内 GPS プール 0 件: 欠損
  - スロット内 GPS プール 1 件以上: `before` を含めない平均の `Lat / Lon / Alt` を採用
- 停止 `STOPPED` は、完全停止寄りの平均化ルールを使う
- 気圧はスロット時点の最新保持値を採用し、未初期化時のみ欠損とする
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
- 補助センサー判定ログの日次ファイルは 03:00 区切りで `motion_metrics_yyyyMMdd.csv` とする
- 補助センサー判定ログの手動バックアップは `gps_pressure_motion_metrics_backup_yyyyMMdd_HHmmss.csv` を基本とする
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
