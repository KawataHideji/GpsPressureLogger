# Windows Viewer

`step3_visualize.py` は、`GpsPressureLogger` の標準 CSV / 手動バックアップ CSV を Windows 上で確認するためのビューアです。

## できること

- `# EVENT ...` コメント付きバックアップ CSV をそのまま読み込む
- 先頭列がミリ秒 `Timestamp` の標準バックアップ CSV だけでなく、`YYYY-MM-DD HH:MM:SS` 形式の日時文字列を持つ CSV も読み込める
- 気圧・高度・歩数累積のグラフを HTML で確認する
- GPS 軌跡を地図で確認する
- バックアップ内の `日付` を選んで、その日だけのグラフ・地図・イベントを確認できる
- `Lat=0, Lon=0` の無効 GPS 点を地図から除外する
- アプリ相当の補正処理をビューア側でも再現し、`補正あり / なし` を切り替えられる
- 停止標準化の前段で、`stepsDelta=0` かつ `VEHICLE` ではない短時間の `復帰バースト` を検知し、大ジャンプ後に元クラスタ近傍へ戻る区間を補正する
- 停止標準化の前段で、`stepsDelta=0` かつ `VEHICLE` ではない短時間の `偽クラスタ滞在` を検知し、前後クラスタへ戻す補正も行う
- `停止偏差` グラフで、停止区間の中心からのズレ量 (`Deviation Raw`) と、その点が何 m 補正されたか (`Correction Shift`) を見比べられる
- `停止偏差` グラフは `偏差フォーカス` で全期間とピーク周辺を切り替えられ、前後 3 / 5 / 10 / 20 分で拡大確認できる
- 地図は `地図時間` で `全日 / 偏差フォーカス連動` を切り替えられ、停止偏差のピーク時間帯だけの軌跡を確認できる
- 停止標準化の後段では、向きを保ったまま中心からの半径だけを圧縮する段も入り、停止中の大きい尾をさらに抑える
- `--summary-only` でコンソール要約だけ確認する
- 独立した Windows アプリとして、ブラウザを使わずにネイティブウィンドウで表示できる

補正ありのグラフでは、アプリと同じ考え方で

- 外れ値除去
- 30 秒間隔の線形補間
- 移動平均による平滑化

を適用します。

`補正あり` の地図表示は、Android アプリと同じ固定パイプラインを使います。
- `復帰バースト`
- `偽クラスタ滞在`
- `停止標準化`
- `GPS 平準化`

この順で表示用系列だけへ適用し、停止補正は `# EVENT` の `MODE_CONFIRMED` をもとに `DEVICE_STILL / STOPPED` 区間だけへかけます。
CSV には `MotionSample` が入らないため、viewer は `MODE_CONFIRMED` を使って Android の確定モード遷移と同じ考え方の表示モード列を再構成します。
完全停止と停止では別パラメータを使い、完全停止の方を強く、停止の方を弱く補正します。現行の `DEVICE_STILL` は、偏差半径をおよそ `2m` まで強く圧縮する設定です。
停止標準化の前段では、`stepsDelta=0` かつ `VEHICLE` ではない GPS 点列を見て、`復帰バースト` と `偽クラスタ滞在` を順に補正します。`復帰バースト` はジャンプ直前点と復帰アンカーの間を補間で戻し、`偽クラスタ滞在` は前後アンカーの中点へ**全置換**します。どちらも後段の弱補正では触りません。
停止区間では、そのあとで `MODE_CONFIRMED` の連続区間をそのまま対象にし、区間中央値を基準に偏差列を作ります。まず短いバースト塊だけを前後アンカーで補修し、その後に局所中央値から大きく外れた残差だけを補正します。なめらかなうねりは残しつつ、停止中のスパイクとランダム暴れを抑える方針です。

## 起動方法

### 0. 仮想環境を使う

今後ライブラリを追加しやすいように、`window_viewer\venv` を作成済みです。

```powershell
C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\window_viewer\venv\Scripts\Activate.ps1
```

有効化したあとに `python ...` や `pip install ...` を実行できます。

### 最新バックアップを自動検出して開く

```powershell
python C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\window_viewer\step3_visualize.py
```

- `C:\MyDrive\android` の最新 `gps_pressure_full_backup_*.csv` を探して使います
- 既定では、バックアップ全体ではなく「最後の長い空白以降の最新セッション」だけを表示します
- viewer 上では、そのセッション内の日付を `日付` プルダウンで切り替えられます
- 地図では、比較的まっすぐ進む区間に `>` 風の向きマーカーが入ります
- 歩数グラフと地図の折れ線は、`DEVICE_STILL=黒 / STOPPED=グレー / WALKING=青 / VEHICLE=赤` で表示します
- 結果は `window_viewer\merged_dashboard.html` に出力されます

### 独立アプリとして開く

```powershell
python C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\window_viewer\viewer_app.py
```

- ブラウザではなく、`pywebview` のネイティブウィンドウで開きます
- 既定では `C:\MyDrive\android` の最新 `gps_pressure_full_backup_*.csv` を使います
- 上部バーから `CSVを開く` と `最新を再読込` ができます
- 内部では既存の `step3_visualize.py` を使って dashboard HTML を再生成しているため、見た目と補正ロジックは HTML 版と共通です
- 独立アプリ版は、再読込や CSV 切替のたびに `window_viewer\\desktop_cache\\` 配下へ一意な HTML を生成し、表示差し替え時の空表示を避けます
- さらに簡単に起動したいときは、次も使えます

```powershell
C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\window_viewer\launch_viewer.ps1
```

- 独立アプリは「表示シェル」で、アルゴリズム本体は `step3_visualize.py` のままです
- つまり、Android と揃えるべき補正ロジックは desktop app 側へ重複実装せず、dashboard 生成側へ集約します
- `補正あり` は Android アプリの固定描画に合わせ、地図の細かい比較用トグルは通常 UI から外しています

### CSV を指定して開く

```powershell
python C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\window_viewer\step3_visualize.py --csv-path C:\MyDrive\android\gps_pressure_full_backup_20260411_135518.csv
```

### 要約だけ確認する

```powershell
python C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\window_viewer\step3_visualize.py --csv-path C:\MyDrive\android\gps_pressure_full_backup_20260411_135518.csv --summary-only --no-browser
```

### バックアップ全体を見たいとき

```powershell
python C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\window_viewer\step3_visualize.py --csv-path C:\MyDrive\android\gps_pressure_full_backup_20260411_135518.csv --view full
```

## オプション

- `--csv-path`
  - 読み込む CSV を明示指定する
- `--html-output`
  - 出力 HTML の保存先を指定する
- `--no-browser`
  - HTML を作るが自動では開かない
- `--summary-only`
  - HTML を作らず、コンソール要約だけ出す
- `--view latest-session|full`
  - `latest-session` は最後の長い空白以降だけ表示する
  - `full` はバックアップ全体を表示する
- `--session-gap-minutes`
  - 最新セッションを切り出す境界の空白時間を分単位で指定する
- `--correction corrected|raw`
  - HTML を開いた直後の補正モードを指定する
  - `corrected` はアプリ相当の補正を初期表示にする
  - `raw` は補正なしを初期表示にする

## Python について

- 現在のビューア本体は標準ライブラリだけで動きます
- ただし将来の追加ライブラリ用に `window_viewer\venv` を作成済みです
- 追加で入れるときは、仮想環境を有効化してから `pip install ...` を使ってください
- ただし生成される HTML は `Chart.js` と `Leaflet` を CDN から読むので、HTML を開く PC はネット接続がある方が確実です
- 独立アプリ版は `pywebview` を使います。仮想環境で次を実行すると揃います。

```powershell
pip install -r C:\Users\kawata\AndroidStudioProjects\GpsPressureLogger\window_viewer\requirements.txt
```
