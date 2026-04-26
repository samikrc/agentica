/**
 * debug.js — Debug pane: logs structured SSE events per turn.
 */

const Debug = (() => {
  const logEl = document.getElementById('debug-log');

  function clear() { logEl.textContent = ''; }

  function append(event, data) {
    const ts   = new Date().toISOString().slice(11, 23);
    const line = `[${ts}] ${event.padEnd(12)} ${typeof data === 'object' ? JSON.stringify(data) : data}\n`;
    logEl.textContent += line;
    logEl.scrollTop    = logEl.scrollHeight;
  }

  return { clear, append };
})();
