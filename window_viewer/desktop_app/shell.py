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
      position: relative;
    }
    iframe {
      width: 100%;
      height: calc(100vh - 57px);
      border: 0;
      background: var(--bg);
    }
    .loading-overlay {
      position: absolute;
      inset: 0;
      display: none;
      place-items: center;
      background: rgba(8, 13, 24, 0.72);
      backdrop-filter: blur(2px);
      z-index: 5;
    }
    .loading-overlay.active {
      display: grid;
    }
    .loading-panel {
      min-width: 280px;
      padding: 18px 22px;
      border: 1px solid rgba(95, 160, 255, 0.42);
      border-radius: 10px;
      background: rgba(18, 32, 58, 0.96);
      box-shadow: 0 16px 45px rgba(0, 0, 0, 0.35);
      color: var(--text);
      text-align: center;
    }
    .loading-spinner {
      width: 28px;
      height: 28px;
      margin: 0 auto 12px;
      border: 3px solid rgba(154, 179, 218, 0.28);
      border-top-color: var(--accent);
      border-radius: 50%;
      animation: spin 0.9s linear infinite;
    }
    .loading-title {
      font-weight: 700;
      margin-bottom: 6px;
    }
    .loading-text {
      color: var(--muted);
      font-size: 13px;
      line-height: 1.45;
    }
    @keyframes spin {
      to { transform: rotate(360deg); }
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
      <select id="dateSelect" title="日付"></select>
      <select id="correctionSelect" title="初期補正表示">
        <option value="corrected">アプリ補正あり</option>
        <option value="raw">補正なし</option>
      </select>
      <button id="openButton">CSVを開く</button>
      <button id="openMotionButton">補助CSVを開く</button>
      <button id="reloadButton" class="primary">最新を再読込</button>
    </div>
  </div>
  <div class="frame-wrap">
    <iframe id="dashboardFrame" title="GpsPressureLogger dashboard"></iframe>
    <div id="loadingOverlay" class="loading-overlay">
      <div class="loading-panel">
        <div class="loading-spinner"></div>
        <div class="loading-title">読み込み中</div>
        <div id="loadingText" class="loading-text">データを処理しています...</div>
      </div>
    </div>
  </div>
  <script>
    const metaText = document.getElementById('metaText');
    let frame = document.getElementById('dashboardFrame');
    const reloadButton = document.getElementById('reloadButton');
    const openButton = document.getElementById('openButton');
    const openMotionButton = document.getElementById('openMotionButton');
    const viewSelect = document.getElementById('viewSelect');
    const dateSelect = document.getElementById('dateSelect');
    const correctionSelect = document.getElementById('correctionSelect');
    const loadingOverlay = document.getElementById('loadingOverlay');
    const loadingText = document.getElementById('loadingText');

    function setBusy(isBusy, text) {
      reloadButton.disabled = isBusy;
      openButton.disabled = isBusy;
      openMotionButton.disabled = isBusy;
      viewSelect.disabled = isBusy;
      dateSelect.disabled = isBusy;
      correctionSelect.disabled = isBusy;
      if (text) {
        metaText.textContent = text;
        loadingText.textContent = text;
      }
      loadingOverlay.classList.toggle('active', isBusy);
    }

    function renderDateOptions(dateKeys, selectedDateKey) {
      dateSelect.innerHTML = '';
      (dateKeys || []).forEach((dateKey) => {
        const option = document.createElement('option');
        option.value = dateKey;
        option.textContent = dateKey;
        if (dateKey === selectedDateKey) {
          option.selected = true;
        }
        dateSelect.appendChild(option);
      });
      dateSelect.style.display = (dateKeys && dateKeys.length > 1) ? 'inline-block' : 'none';
    }

    function applyResult(result) {
      const nextFrame = frame.cloneNode(false);
      nextFrame.addEventListener('load', () => setBusy(false), { once: true });
      nextFrame.srcdoc = result.dashboard_html;
      frame.replaceWith(nextFrame);
      frame = nextFrame;
      viewSelect.value = result.view;
      correctionSelect.value = result.correction;
      renderDateOptions(result.date_keys || [], result.selected_date_key);
      metaText.textContent = `${result.csv_name} | motion=${result.motion_csv_name} | ${result.range_text}`;
      document.title = `GpsPressureLogger Viewer - ${result.csv_name}`;
    }

    async function loadInitialState() {
      setBusy(true, 'ビューアを構築しています...');
      try {
        const result = await window.pywebview.api.get_initial_state();
        applyResult(result);
      } catch (error) {
        metaText.textContent = `初期化エラー: ${error}`;
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
        setBusy(false);
      }
    }

    async function openCsv() {
      setBusy(true, 'CSVを開いています...');
      try {
        const result = await window.pywebview.api.open_csv_file();
        if (result) {
          applyResult(result);
        } else {
          setBusy(false);
        }
      } catch (error) {
        metaText.textContent = `ファイルオープンエラー: ${error}`;
        setBusy(false);
      }
    }

    async function openMotionCsv() {
      setBusy(true, '補助CSVを開いています...');
      try {
        const result = await window.pywebview.api.open_motion_csv_file();
        if (result) {
          applyResult(result);
        } else {
          setBusy(false);
        }
      } catch (error) {
        metaText.textContent = `ファイルオープンエラー: ${error}`;
        setBusy(false);
      }
    }

    async function onDateChange() {
      const dateKey = dateSelect.value;
      if (!dateKey) return;
      setBusy(true, '日付変更中...');
      try {
        const result = await window.pywebview.api.set_date(dateKey, viewSelect.value, correctionSelect.value);
        applyResult(result);
      } catch (error) {
        metaText.textContent = `日付変更エラー: ${error}`;
        setBusy(false);
      }
    }

    window.addEventListener('message', (event) => {
      const data = event.data || {};
      if (data.type !== 'gpspl-date-change') return;
      const dateKey = data.dateKey;
      if (!dateKey || dateSelect.value === dateKey) return;
      dateSelect.value = dateKey;
      onDateChange();
    });

    reloadButton.addEventListener('click', reloadLatest);
    openButton.addEventListener('click', openCsv);
    openMotionButton.addEventListener('click', openMotionCsv);
    dateSelect.addEventListener('change', onDateChange);

    window.addEventListener('pywebviewready', loadInitialState);
  </script>
</body>
</html>
"""
