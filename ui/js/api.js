/**
 * api.js — HTTP wrapper with bearer-token injection.
 * All other JS modules call through this file only.
 * main.js initializes this module with the backend origin and bearer token
 * parsed from the browser URL.
 */

const Api = (() => {
  let _baseUrl = 'http://127.0.0.1:0';  // replaced at runtime by main.js
  let _token   = '';

  function init(baseUrl, token) {
    _baseUrl = baseUrl;
    _token   = token;
  }

  function headers(extra = {}) {
    return { 'Authorization': `Bearer ${_token}`, 'Content-Type': 'application/json', ...extra };
  }

  async function get(path) {
    const r = await fetch(_baseUrl + path, { headers: headers() });
    if (!r.ok) throw new Error(`GET ${path} → ${r.status}`);
    return r.json();
  }

  async function post(path, body) {
    const r = await fetch(_baseUrl + path, { method: 'POST', headers: headers(), body: JSON.stringify(body) });
    if (!r.ok) throw new Error(`POST ${path} → ${r.status}`);
    if (r.status === 204) return null;
    return r.json();
  }

  async function del(path) {
    const r = await fetch(_baseUrl + path, { method: 'DELETE', headers: headers() });
    if (!r.ok && r.status !== 204) throw new Error(`DELETE ${path} → ${r.status}`);
  }

  /**
   * Open an SSE stream. Returns an EventSource-like object with .close().
   * @param {string} path
   * @param {{ onToken, onIteration, onToolStart, onToolResult, onFinal, onCancelled, onError, onDone, onPermissionRequired }} handlers
   */
  function stream(path, handlers) {
    const url  = new URL(_baseUrl + path);
    url.searchParams.set('_t', _token); // token via query param for EventSource (no custom headers)

    // Use fetch + ReadableStream so we can set the Authorization header properly
    let closed = false;
    const ctrl = new AbortController();

    fetch(_baseUrl + path, { headers: headers({ 'Accept': 'text/event-stream' }), signal: ctrl.signal })
      .then(r => {
        if (!r.ok) { handlers.onError?.(`SSE connect failed: ${r.status}`); return; }
        const reader = r.body.getReader();
        const dec    = new TextDecoder();
        let buf      = '';

        function pump() {
          if (closed) return;
          reader.read().then(({ done, value }) => {
            if (done) { handlers.onDone?.(); return; }
            buf += dec.decode(value, { stream: true });
            const parts = buf.split('\n\n');
            buf = parts.pop();   // incomplete last chunk
            for (const block of parts) {
              let event = 'message', data = '';
              for (const line of block.split('\n')) {
                if (line.startsWith('event: ')) event = line.slice(7).trim();
                if (line.startsWith('data: '))  data  = line.slice(6);
              }
              dispatchEvent(event, data, handlers);
            }
            pump();
          }).catch(err => { if (!closed) handlers.onError?.(err.message); });
        }
        pump();
      })
      .catch(err => { if (!closed) handlers.onError?.(err.message); });

    return { close() { closed = true; ctrl.abort(); } };
  }

  function dispatchEvent(event, data, handlers) {
    switch (event) {
      case 'token':       handlers.onToken?.(data.replace(/\\n/g, '\n')); break;
      case 'iteration':   handlers.onIteration?.(JSON.parse(data)); break;
      case 'tool_start':  handlers.onToolStart?.(JSON.parse(data)); break;
      case 'tool_result': handlers.onToolResult?.(JSON.parse(data)); break;
      case 'final':       handlers.onFinal?.(JSON.parse(data)); break;
      case 'cancelled':   handlers.onCancelled?.(); break;
      case 'error':       handlers.onError?.(JSON.parse(data).message); break;
      case 'done':        handlers.onDone?.(); break;
      case 'permission_required': handlers.onPermissionRequired?.(JSON.parse(data)); break;
      case 'tool_progress': handlers.onToolProgress?.(JSON.parse(data)); break;
    }
  }

  return { init, get, post, del, stream };
})();
