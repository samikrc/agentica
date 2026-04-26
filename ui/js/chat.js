/**
 * chat.js — Chat rendering and SSE stream management.
 * Depends on: api.js, debug.js
 */

const Chat = (() => {
  let _sessionId   = null;
  let _activeStream = null;
  let _activeRunId  = null;

  const messagesEl  = document.getElementById('messages');
  const inputEl     = document.getElementById('msg-input');
  const btnSend     = document.getElementById('btn-send');
  const btnCancel   = document.getElementById('btn-cancel-run');
  const titleEl     = document.getElementById('chat-title');

  function setSession(session) {
    _sessionId = session?.id ?? null;
    titleEl.textContent = session ? session.title : 'Select or create a session';
    messagesEl.innerHTML = '';
    inputEl.disabled     = !session;
    btnSend.disabled     = !session;
    Debug.clear();

    if (session) loadHistory();
  }

  async function loadHistory() {
    const messages = await Api.get(`/sessions/${_sessionId}/messages`);
    messagesEl.innerHTML = '';
    for (const m of messages) appendMessage(m.role, m.content);
  }

  function appendMessage(role, content) {
    const wrap = document.createElement('div');
    wrap.className = `msg ${role}`;

    const roleEl = document.createElement('div');
    roleEl.className   = 'msg-role';
    roleEl.textContent = role === 'user' ? 'You' : 'Agentica';

    const bubble = document.createElement('div');
    bubble.className   = 'msg-bubble';
    bubble.textContent = content;

    wrap.appendChild(roleEl);
    wrap.appendChild(bubble);
    messagesEl.appendChild(wrap);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return bubble;
  }

  async function send() {
    if (!_sessionId) return;
    const content = inputEl.value.trim();
    if (!content) return;

    inputEl.value    = '';
    inputEl.disabled = true;
    btnSend.disabled = true;
    btnCancel.style.display = 'inline-block';
    Debug.clear();

    appendMessage('user', content);
    const asstBubble = appendMessage('assistant', '');
    asstBubble.classList.add('streaming');

    let result;
    try {
      result = await Api.post(`/sessions/${_sessionId}/messages`, { content });
    } catch (err) {
      asstBubble.textContent = `[Error sending message: ${err.message}]`;
      asstBubble.classList.remove('streaming');
      setIdle();
      return;
    }

    _activeRunId = result.runId;
    Debug.append('run_start', { runId: result.runId, traceId: result.traceId });

    _activeStream = Api.stream(`/sessions/${_sessionId}/stream/${result.runId}`, {
      onToken: tok => {
        asstBubble.textContent += tok;
        messagesEl.scrollTop    = messagesEl.scrollHeight;
      },
      onIteration: d => Debug.append('iteration', d),
      onToolStart:  d => Debug.append('tool_start', d),
      onToolResult: d => Debug.append('tool_result', d),
      onFinal: d => {
        Debug.append('final', d);
        asstBubble.classList.remove('streaming');
        setIdle();
      },
      onCancelled: () => {
        Debug.append('cancelled', {});
        asstBubble.classList.remove('streaming');
        asstBubble.textContent += ' [cancelled]';
        setIdle();
      },
      onError: msg => {
        Debug.append('error', msg);
        asstBubble.textContent += `\n[Error: ${msg}]`;
        asstBubble.classList.remove('streaming');
        setIdle();
      },
      onDone: () => setIdle()
    });
  }

  function cancel() {
    if (_activeRunId) {
      Api.del(`/runs/${_activeRunId}`).catch(() => {});
      _activeRunId = null;
    }
    _activeStream?.close();
    _activeStream = null;
  }

  function setIdle() {
    inputEl.disabled        = !_sessionId;
    btnSend.disabled        = !_sessionId;
    btnCancel.style.display = 'none';
    _activeStream           = null;
    _activeRunId            = null;
  }

  btnSend.addEventListener('click', send);
  btnCancel.addEventListener('click', cancel);
  inputEl.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
  });

  return { setSession };
})();
