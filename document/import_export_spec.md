# Import / Export 仕様書

最終更新: 2026-04-26

## 1. 目的

本仕様書は、GpsPressureLogger の import / export の外部仕様と実装方針を定義する。

基本方針:

- import と export は同一の標準 CSV 形式に統一する
- StepWalk / Barograph などの個別形式はアプリ外で事前変換する
- 詳細なデバッグログ本文は import / export 対象の記録 CSV と分離する
- 重要な運用イベントは、記録 CSV に `#` コメント行としても残せるようにする
- モード判定前の連続値は、主記録 CSV とは別系統の補助 CSV として扱う

関連仕様:

- [data_spec.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\data_spec.md)
- [room_data_spec.md](C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\document\room_data_spec.md)

## 2. 対象形式

### 2.1 標準 CSV

ヘッダ:

```csv
Timestamp,Lat,Lon,Alt,PresRaw,PresQnh,StepsDelta,GpsAccuracy
```

### 2.2 補助センサー判定ログ CSV

ヘッダ:

```csv
Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s,KStatus,KRawStatus,KAvg,KScalarAvg,KDirectionalityRatio,KVariance,TrKStatus,TrKRawStatus,TrKAvg,TrKDirectionalityRatio,WStatus,StepDeltaWindow,GpsIntervalMs,GpsImmediate,ConfirmedMode,ConstantRegionKind,ConstantRegionSpeedKmh,ConstantRegionStartLat,ConstantRegionStartLon,ConstantRegionEndLat,ConstantRegionEndLon,ConstantRegionStayLat,ConstantRegionStayLon,ConstantRegionDirectionDeg
```

### 2.3 非対象

アプリ本体では次の個別形式を直接解釈しない方向で整理する。

- StepWalk SQLite
- Barograph CSV
- その他ベンダー固有形式

これらは Python コンバータで標準 CSV に変換した後に import する。

## 3. Export 仕様

### 3.1 目的

export は次の用途に使う。

- バックアップ
- 復元
- PC 上での解析
- 他環境への受け渡し

### 3.2 出力形式

- 標準 CSV を出力する
- 必要に応じてヘッダ前に `#` コメント行を付与してよい
- 日次 CSV に存在する `# EVENT <timestamp> ...` コメントは、手動バックアップ CSV にも時系列順で反映する
- 並び順は昇順
- `Timestamp` は一意
- 欠損は空欄
- 状態イベントログを出力する場合は、主記録とは別ファイルに分ける
- 設定画面からは主記録バックアップと状態イベントログバックアップを個別に出力できる
- export は `ACTION_CREATE_DOCUMENT` で作成した文書 URI へ直接書き込む
- export 成功判定は「書き込み例外なし」だけでなく、「close 後に文書サイズが 0 byte より大きいこと」も必須とする
- `openOutputStream()==null`、書き込み例外、close 後 0 byte 文書のいずれかは失敗扱いとし、可能ならその場で空ファイルを削除する
- 状態イベントログの手動 export は、過去の `motion_samples` が多数残っていてもメモリを圧迫しないよう、Room から昇順ページ単位で読み出して CSV へ逐次書き込む

### 3.3 データ源

- export 元データは Room とする
- 表示用派生値ではなく内部正規値を出力する
- 補助センサー判定ログ export 元は、主記録とは別の内部ストアとする

### 3.4 デバッグログ

- 詳細デバッグログ本文は export CSV に含めない
- ただし重要イベントは `# EVENT` コメントとして export CSV に含めてよい
- 必要な場合は別ファイルとして出力する

## 4. Import 仕様

### 4.1 入力形式

- import 対象は標準 CSV のみ
- ファイル名は `gps_pressure_full_backup*.csv` とする
- ヘッダは `Timestamp,Lat,Lon,Alt,PresRaw,PresQnh,StepsDelta,GpsAccuracy` に完全一致させる
- 旧ヘッダ `Timestamp,Lat,Lon,Alt,PresRaw,PresQnh,StepsDelta` も互換 import として受け入れる
- 入力元は `ACTION_OPEN_DOCUMENT` で選択した単一 CSV ファイル URI とする

補助センサー判定ログ import を行う場合:

- 新形式のファイル名は `gps_pressure_motion_events*.csv` または `motion_events_*.csv` とする
- 旧形式の `gps_pressure_motion_metrics*.csv` または `motion_metrics_*.csv` も互換 import 対象とする
- ヘッダは新方式の補助センサー判定ログヘッダに一致させる
- 旧拡張ヘッダ `...ConstantRegionSpeedKmh` までの形式も互換 import として受け入れる
- 旧ヘッダ `Timestamp,AccelStddev3s,AccelMad3s,StepDelta3s,StepRate3s` も互換 import として受け入れる
- 主記録 import とは別操作として扱う
- 設定画面で事前に選択した URI を使って手動 import を実行する

### 4.2 読み込みルール

- ヘッダ行を必須とする
- `Timestamp` は必須
- 他列は欠損可
- 欠損は空欄として読む
- `#` で始まるコメント行と空行は読み飛ばす
- 補助センサー判定ログでも `Timestamp` は必須、他列は欠損可とする

### 4.3 バリデーション

各行のバリデーション:

- `Timestamp` が数値であること
- `Timestamp` がファイル内で昇順であることが望ましい
- 同一ファイル内で `Timestamp` が重複しないこと
- 数値列は数値として解釈可能であること

実装方針:

- 対象外ファイルはスキップして件数を記録する
- 行単位の解析失敗は件数と行番号をデバッグログへ残す
- インポート完了時に、取込件数・スキップ件数・解析エラー件数をユーザーへ通知する

厳格運用の候補:

- 重複 `Timestamp` をエラーとする
- 昇順違反をエラーとする

## 5. 競合解決

### 5.1 対象

競合とは、import 対象 CSV の 1 行が、既存 Room の同一 `timestamp` レコードと衝突する場合を指す。

### 5.2 解決方式

ユーザー選択可能:

- 上書き
- 既存優先

### 5.3 マージの考え方

相補的なデータは 1 レコードへ統合する。

例:

- 既存に気圧だけある
- import 側に GPS だけある

この場合、競合解決方針に従って 1 レコードへ統合する。

## 6. 日常ログと手動 export の違い

### 6.1 日常ログ

- 外部記憶へ追記型で保存する運用ログ
- 主に障害時救出や外部参照用
- 保存先は app-specific external storage を優先し、利用不可時のみ内部保存へフォールバックする
- ファイル単位は 03:00 区切り日で分ける
- ファイル名は `gps_log_yyyyMMdd.csv` とする
- 重要イベントは `# EVENT <timestamp> <message>` 形式で同じ日次 CSV に追記する
- 状態イベントログは別系列の日常ログとして `motion_events_yyyyMMdd.csv` を用いる
- 日次 CSV と状態イベントログ CSV への書き出しは、メモリキュー 100 件到達時または強制フラッシュ時にまとめて行う
- 手動 export 開始時は、バックアップ CSV 生成前に未書込キューを日次 CSV 側へ flush する
- 手動 export 成功時は `EXPORT_STANDARD_OK` / `EXPORT_MOTION_OK`、失敗時は `EXPORT_STANDARD_FAILED` / `EXPORT_MOTION_FAILED` を debug log へ残す

### 6.2 手動 export

- Room から再生成する正規バックアップ
- import の正規入力として利用する

方針:

- 復元元としては手動 export を優先する
- 日常ログは補助的な運用ログとする

## 7. 外部保存先の方針

想定ディレクトリ:

- `logs/`
- `exports/`
- `debug/`

例:

- `logs/log_YYYYMMDD.csv`
- `exports/export_YYYYMMDD_HHMMSS.csv`
- `metrics/motion_events_YYYYMMDD.csv`
- `exports/gps_pressure_motion_events_backup_YYYYMMDD_HHMMSS.csv`
- 旧 `metrics/motion_metrics_YYYYMMDD.csv` / `exports/gps_pressure_motion_metrics_backup_*.csv` は互換入力として読む
- `debug/debug_log_YYYYMMDD.txt`

現行実装:

- ルートは app-specific external storage 配下の `GpsPressureLogger/` を優先する
- 日常ログは `GpsPressureLogger/logs/`
- 補助センサー判定ログは `GpsPressureLogger/metrics/`
- デバッグログは `GpsPressureLogger/debug/`
- デバッグ共有が有効な場合は、`ACTION_CREATE_DOCUMENT` で選択した単一ログファイル URI にも重要ログを追記する

## 8. Python コンバータとの責務分担

### 8.1 アプリ

- 標準 CSV の import / export
- 補助センサー判定ログ CSV の import / export
- 競合解決
- 内部保存

### 8.2 Python 側

- 固有形式の解釈
- 標準 CSV への変換
- 必要に応じた複数ソース統合
- `log_converter/step2_convert.py` は時刻丸めを行わず、`StepsDelta` のまま標準 CSV を出力する

## 9. 今後の実装方針

- 既存の複数形式 import を段階的に縮小する
- export は常に標準 CSV を出す
- debug 追記を export CSV へ混ぜない
- import 時の検証エラー表示を継続改善する
