# pywebview 技術メモ

最終更新: 2026-05-06

## 対象

- Windows viewer 独立アプリ
- 使用ライブラリ: `pywebview`
- 実装ファイル:
  - [viewer_app.py](/C:/Users/kawata/AndroidStudioProjects/GpsPressureLogger/window_viewer/viewer_app.py)
  - [desktop_app/api.py](/C:/Users/kawata/AndroidStudioProjects/GpsPressureLogger/window_viewer/desktop_app/api.py)
  - [desktop_app/shell.py](/C:/Users/kawata/AndroidStudioProjects/GpsPressureLogger/window_viewer/desktop_app/shell.py)
  - [desktop_app/state.py](/C:/Users/kawata/AndroidStudioProjects/GpsPressureLogger/window_viewer/desktop_app/state.py)

## 採用理由

- ブラウザ依存をやめ、独立したネイティブウィンドウで viewer を開きたかった
- 既存の `step3_visualize.py` が生成する HTML / JS / Leaflet / Chart.js をそのまま再利用したかった
- Android アプリと揃えたアルゴリズムを、Windows 側では「表示コンテナ」だけ変えて使いたかった

## 構成方針

- アルゴリズム本体は `step3_visualize.py` に残す
- `pywebview` 側は、HTML dashboard を表示する薄いシェルに徹する
- CSV 読込、最新再読込、表示モード切替は JS bridge (`ViewerApi`) から `ViewerState` を叩いて再生成する
- 独立アプリ化しても、補正ロジックを別実装しない

## 実装上の注意

- `ViewerApi` は `step3_visualize.build_dashboard()` の戻り値 `summary` を参照する
- `summary` のキーは `first` / `last` を使う
- 以前の `first_dt` / `last_dt` を前提にすると、初期 state 生成で `KeyError` になる
- `js_api` オブジェクトに `pywebview.Window` や `window.native` 系オブジェクトを保持しない
- `pywebview` の `js_api` は基本オブジェクトを exposed method として扱う前提なので、GUI ネイティブオブジェクトをぶら下げると Windows 側で再帰的な処理へ入り、`maximum recursion depth exceeded` を起こしうる
- ファイルダイアログが必要なときは、`webview.active_window()` または `webview.windows[0]` からその場で取得する
- dashboard の再生成先を毎回同じ HTML ファイルへ上書きすると、iframe 差し替えタイミングで空表示になることがある
- 独立アプリ版では `desktop_cache/merged_dashboard_<unique>.html` を毎回生成し、iframe には一意な file URI を渡す
- `desktop_cache` で一意 HTML を生成する構成にした後は、`file:///...html?t=...` のような追加クエリは不要
- pywebview / WebView2 ではローカル file URI への不要なクエリ付与が表示不安定要因になりうるため、iframe には生成済み file URI をそのまま渡す
- `desktop_app.state` から `step3_visualize` を読む import は、viewer 起動位置に依存しないよう相対 import 優先 / 直接 import fallback にしておく
- `iframe + file:///...` で dashboard を差し替える構成は、pywebview / WebView2 上で空表示になりやすい
- 独立 viewer では、生成済み dashboard HTML を `ViewerApi` から文字列で返し、shell 側の `iframe.srcdoc` へ直接流し込む方が安定する
- 単純な `srcdoc` 再代入だと日付変更後に Chart.js / Leaflet の状態が前回 dashboard を引き継ぐことがあるため、shell では iframe 要素自体を作り直してから `srcdoc` に新しい HTML を入れる
- 内側 dashboard の日付セレクトを変えても shell が知らないままだと別日のデータが描けないため、内側から `postMessage(type='gpspl-date-change')` で shell へ通知し、shell が `set_date()` 経由で HTML を再生成する

## 起動確認方法

- 文法確認:
  - `python -m py_compile ...`
- 非 GUI の smoke test:
  - `ViewerState().build()`
  - `ViewerApi.get_initial_state()`
- GUI 起動:
  - [launch_viewer.ps1](/C:/Users/kawata/AndroidStudioProjects/GpsPressureLogger/window_viewer/launch_viewer.ps1)
  - または [viewer_app.py](/C:/Users/kawata/AndroidStudioProjects/GpsPressureLogger/window_viewer/viewer_app.py)

## 既知の前提

- dashboard 本体は HTML のため、Leaflet / Chart.js の読込はネット接続前提になりやすい
- `pywebview` は表示シェルであり、dashboard 生成が重いと初回表示前に少し待つ
- 独立アプリの表示内容は HTML 版と同一であるべきなので、viewer 固有の補正ロジックを増やさない
