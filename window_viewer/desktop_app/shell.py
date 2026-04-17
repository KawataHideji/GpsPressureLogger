def render_shell_html() -> str:
    return """<!doctype html>
<html lang="ja">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>GpsPressureLogger Viewer</title>
  <style>
    :root {
      --bg: #0d1422;
      --panel: #12203a;
      --panel-2: #17284a;
      --line: #294166;
      --text: #ecf3ff;
      --muted: #9ab3da;
      --accent: #5fa0ff;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: "Segoe UI", sans-serif;
      background: var(--bg);
      color: var(--text);
      display: grid;
      grid-template-rows: auto 1fr;
      min-height: 100vh;
    }
    .toolbar {
      display: flex;
      gap: 10px;
      align-items: center;
      padding: 12px 16px;
      background: rgba(10, 18, 34, 0.98);
      border-bottom: 1px solid var(--line);
      position: sticky;
      top: 0;
      z-index: 10;
    }
    .title {
      font-size: 20px;
      font-weight: 700;
      margin-right: 8px;
      white-space: nowrap;
    }
    .meta {
      flex: 1;
      min-width: 0;
      color: var(--muted);
      font-size: 13px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .actions {
      display: flex;
      gap: 8px;
      align-items: center;
      flex-wrap: wrap;
      justify-content: flex-end;
    }
    button, select {
      appearance: none;
      border: 1px solid var(--line);
      background: var(--panel-2);
      color: var(--text);
      border-radius: 10px;
      padding: 8px 12px;
      font: inherit;
    }
    button {
      cursor: pointer;
      min-width: 112px;
    }
    button.primary {
      background: linear-gradient(180deg, #2c6ed8, #2253a0);
      border-color: #2f6fce;
    }
    button:disabled {
      cursor: wait;
      opacity: 0.7;
    }
    .frame-wrap {
      background: var(--bg);
      padding: 0;
    }
    iframe {
      width: 100%;
      height: calc(100vh - 57px);
      border: 0;
      background: var(--bg);
    }
  </style>
</head>
<body>
  <div class="toolbar">
    <div class="title">GpsPressureLogger Viewer</div>
    <div class="meta" id="metaText">読み込み準備中...</div>
    <div class="actions">
      <select id="viewSelect" title="表示範囲">
        <option value="latest-session">最新セッション</option>
        <option value="full">全体</option>
      </select>
      <select id="correctionSelect" title="初期補正表示">
        <option value="corrected">アプリ補正あり</option>
        <option value="raw">補正なし</option>
      </select>
      <button id="openButton">CSVを開く</button>
      <button id="reloadButton" class="primary">最新を再読込</button>
    </div>
  </div>
  <div class="frame-wrap">
    <iframe id="dashboardFrame" title="GpsPressureLogger dashboard"></iframe>
  </div>
  <script>
    const metaText = document.getElementById('metaText');
    const frame = document.getElementById('dashboardFrame');
    const reloadButton = document.getElementById('reloadButton');
    const openButton = document.getElementById('openButton');
    const viewSelect = document.getElementById('viewSelect');
    const correctionSelect = document.getElementById('correctionSelect');

    function setBusy(isBusy, text) {
      reloadButton.disabled = isBusy;
      openButton.disabled = isBusy;
      viewSelect.disabled = isBusy;
      correctionSelect.disabled = isBusy;
      if (text) {
        metaText.textContent = text;
      }
    }

    function applyResult(result) {
      frame.removeAttribute('src');
      frame.srcdoc = result.dashboard_html;
      viewSelect.value = result.view;
      correctionSelect.value = result.correction;
      metaText.textContent = `${result.csv_name} | ${result.range_text}`;
      document.title = `GpsPressureLogger Viewer - ${result.csv_name}`;
    }

    async function loadInitialState() {
      setBusy(true, 'ビューアを構築しています...');
      try {
        const result = await window.pywebview.api.get_initial_state();
        applyResult(result);
      } catch (error) {
        metaText.textContent = `初期化エラー: ${error}`;
      } finally {
        setBusy(false);
      }
    }

    async function reloadLatest() {
      setBusy(true, '最新ログを再読込しています...');
      try {
        const result = await window.pywebview.api.reload_latest(viewSelect.value, correctionSelect.value);
        applyResult(result);
      } catch (error) {
        metaText.textContent = `再読込エラー: ${error}`;
      } finally {
        setBusy(false);
      }
    }

    async function openCsv() {
      setBusy(true, 'CSVを選択しています...');
      try {
        const result = await window.pywebview.api.open_csv(viewSelect.value, correctionSelect.value);
        if (result) {
          applyResult(result);
        } else {
          metaText.textContent = 'CSV選択はキャンセルされました。';
        }
      } catch (error) {
        metaText.textContent = `CSV読込エラー: ${error}`;
      } finally {
        setBusy(false);
      }
    }

    reloadButton.addEventListener('click', reloadLatest);
    openButton.addEventListener('click', openCsv);
    viewSelect.addEventListener('change', reloadLatest);
    correctionSelect.addEventListener('change', reloadLatest);
    window.addEventListener('pywebviewready', loadInitialState);
  </script>
</body>
</html>
"""
