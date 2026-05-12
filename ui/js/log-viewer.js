/**
 * log-viewer.js — Debug log viewer with SSE streaming.
 * Connects to /log/stream, displays lines with filtering and level colouring.
 */

const LogViewer = (() => {
  const logLines = document.getElementById('log-lines');
  const logFilter = document.getElementById('log-filter');
  const btnPause = document.getElementById('btn-pause');
  const btnClear = document.getElementById('btn-clear');
  const btnScroll = document.getElementById('btn-scroll');
  const connectionStatus = document.getElementById('connection-status');
  const logDisplay = document.getElementById('log-display');

  let ws = null;
  let wsReconnectTimer = null;
  let isPaused = false;
  let shouldAutoScroll = true;
  let lineCount = 0;

  /**
   * Initialize the log viewer.
   */
  function init() {
    connect();
    setupEventListeners();
    applyTheme();
  }

  /**
   * Connect to the WebSocket stream with exponential backoff on failure.
   * @param {number} delay  Milliseconds to wait before connecting (0 = immediate).
   */
  function connect(delay = 0) {
    if (wsReconnectTimer) {
      clearTimeout(wsReconnectTimer);
      wsReconnectTimer = null;
    }
    if (ws) {
      ws.onclose = null;
      ws.onerror = null;
      ws.close();
      ws = null;
    }

    wsReconnectTimer = setTimeout(() => {
      wsReconnectTimer = null;
      const token   = new URLSearchParams(window.location.search).get('token') || '';
      const proto   = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const url     = `${proto}//${window.location.host}/log/stream?token=${encodeURIComponent(token)}`;

      ws = new WebSocket(url);

      ws.onopen = () => {
        connectionStatus.textContent = 'Connected';
        connectionStatus.className = 'connected';
      };

      ws.onmessage = (event) => {
        if (!isPaused) {
          appendLine(event.data);
        }
      };

      ws.onclose = () => {
        connectionStatus.textContent = 'Disconnected';
        connectionStatus.className = 'disconnected';
        connect(3000);
      };

      ws.onerror = () => {
        ws.close();
      };
    }, delay);
  }

  /**
   * Append a log line to the display.
   */
  function appendLine(rawLine) {
    let data;
    try {
      data = JSON.parse(rawLine);
    } catch (e) {
      // If not valid JSON, show raw
      data = { ts: new Date().toISOString(), level: 'INFO', msg: rawLine };
    }

    const line = document.createElement('div');
    line.className = `log-line ${data.level?.toLowerCase() || 'info'}`;
    line.dataset.level = data.level || 'INFO';
    line.dataset.msg = data.msg || '';
    line.dataset.traceId = data.traceId || '';

    const timestamp = data.ts ? formatTimestamp(data.ts) : '';
    const level = data.level || 'INFO';
    const msg = data.msg || '';
    const traceId = data.traceId ? `[${data.traceId}] ` : '';
    const reserved = new Set(['ts', 'level', 'traceId', 'msg']);
    const extras = Object.entries(data)
      .filter(([k]) => !reserved.has(k))
      .map(([k, v]) => `<span class="log-field"><span class="log-field-key">${escapeHtml(k)}</span>=<span class="log-field-val">${escapeHtml(String(v))}</span></span>`)
      .join(' ');

    line.innerHTML = `
      <span class="log-timestamp">${timestamp}</span>
      <span class="log-level ${level.toLowerCase()}">${level}</span>
      <span class="log-trace">${traceId}</span>
      <span class="log-message">${escapeHtml(msg)}</span>
      ${extras}
    `;

    logLines.appendChild(line);
    lineCount++;

    // Keep only last 5000 lines to prevent memory issues
    if (lineCount > 5000) {
      logLines.removeChild(logLines.firstChild);
      lineCount--;
    }

    applyFilter();

    if (shouldAutoScroll) {
      scrollToBottom();
    }
  }

  /**
   * Format ISO timestamp to readable time.
   */
  function formatTimestamp(ts) {
    try {
      const d = new Date(ts);
      const date = d.toLocaleDateString('en-US', { month: '2-digit', day: '2-digit' });
      const time = d.toLocaleTimeString('en-US', { hour12: false });
      return date + ' ' + time + '.' + String(d.getMilliseconds()).padStart(3, '0');
    } catch (e) {
      return ts;
    }
  }

  /**
   * Escape HTML entities.
   */
  function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  }

  /**
   * Scroll to bottom of log display.
   */
  function scrollToBottom() {
    logDisplay.scrollTop = logDisplay.scrollHeight;
  }

  /**
   * Apply filter to log lines.
   */
  function applyFilter() {
    const filter = logFilter.value.toLowerCase().trim();
    const lines = logLines.querySelectorAll('.log-line');

    lines.forEach(line => {
      if (!filter) {
        line.classList.remove('hidden');
        return;
      }

      const text = (line.dataset.level + ' ' + line.dataset.msg + ' ' + line.dataset.traceId).toLowerCase();
      if (text.includes(filter)) {
        line.classList.remove('hidden');
      } else {
        line.classList.add('hidden');
      }
    });
  }

  /**
   * Setup event listeners.
   */
  function setupEventListeners() {
    logFilter.addEventListener('input', applyFilter);

    btnPause.addEventListener('click', () => {
      isPaused = !isPaused;
      btnPause.textContent = isPaused ? 'Resume' : 'Pause';
      btnPause.classList.toggle('active', !isPaused);
    });

    btnClear.addEventListener('click', () => {
      logLines.innerHTML = '';
      lineCount = 0;
    });

    btnScroll.addEventListener('click', () => {
      shouldAutoScroll = !shouldAutoScroll;
      btnScroll.classList.toggle('active', shouldAutoScroll);
      if (shouldAutoScroll) {
        scrollToBottom();
      }
    });

    // Pause auto-scroll when user manually scrolls up
    logDisplay.addEventListener('scroll', () => {
      const isAtBottom = logDisplay.scrollTop + logDisplay.clientHeight >= logDisplay.scrollHeight - 50;
      if (!isAtBottom && shouldAutoScroll) {
        shouldAutoScroll = false;
        btnScroll.classList.remove('active');
      }
    });
  }

  /**
   * Apply current theme from localStorage.
   */
  function applyTheme() {
    const theme = localStorage.getItem('agentica_theme') || 'light';
    document.documentElement.dataset.theme = theme;
  }

  // Best-effort clean close on tab/window unload
  window.addEventListener('beforeunload', () => {
    if (ws) { ws.onclose = null; ws.close(1000, 'page unload'); }
  });

  // Initialize on load
  init();

  return {
    connect,
    disconnect: () => {
      if (wsReconnectTimer) { clearTimeout(wsReconnectTimer); wsReconnectTimer = null; }
      if (ws) { ws.onclose = null; ws.close(); ws = null; }
    }
  };
})();
