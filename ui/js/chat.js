/**
 * chat.js — Chat rendering and SSE stream management.
 * Depends on: api.js
 */

const Chat = (() => {
  let _sessionId   = null;
  let _activeStream = null;
  let _activeRunId  = null;

  const messagesEl  = document.getElementById('messages');
  const inputEl     = document.getElementById('msg-input');
  const btnSend     = document.getElementById('btn-send');
  const titleEl     = document.getElementById('chat-title');
  const pathEl      = document.getElementById('chat-path');

  function setSession(session) {
    _sessionId = session?.id ?? null;
    titleEl.textContent = session ? session.title : 'Select or create a session';
    pathEl.textContent  = session?.rootPath ?? '';
    messagesEl.innerHTML = '';
    inputEl.disabled     = !session;
    btnSend.disabled     = !session;

    if (session) loadHistory();
  }

  async function loadHistory() {
    const messages = await Api.get(`/sessions/${_sessionId}/messages`);
    messagesEl.innerHTML = '';
    for (const m of messages) appendMessage(m.role, m.content);
  }

  function renderMarkdown(text) {
    return marked.parse(text || '', { breaks: true, gfm: true });
  }

  function appendMessage(role, content) {
    const wrap = document.createElement('div');
    wrap.className = `msg ${role}`;

    const roleEl = document.createElement('div');
    roleEl.className   = 'msg-role';
    roleEl.textContent = role === 'user' ? 'You' : 'Agentica';

    const bubble = document.createElement('div');
    bubble.className = 'msg-bubble';
    if (role === 'assistant') {
      bubble.innerHTML = renderMarkdown(content);
    } else {
      bubble.textContent = content;
    }

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
    setRunning();

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

    let rawText = '';

    _activeStream = Api.stream(`/sessions/${_sessionId}/stream/${result.runId}`, {
      onToken: tok => {
        rawText += tok;
        asstBubble.innerHTML = renderMarkdown(rawText);
        messagesEl.scrollTop = messagesEl.scrollHeight;
      },
      onIteration: d => {},
      onToolStart:  d => {},
      onToolResult: d => {},
      onFinal: d => {
        asstBubble.classList.remove('streaming');
        setIdle();
      },
      onCancelled: () => {
        asstBubble.classList.remove('streaming');
        asstBubble.textContent += ' [cancelled]';
        setIdle();
      },
      onError: msg => {
        asstBubble.textContent += `\n[Error: ${msg}]`;
        asstBubble.classList.remove('streaming');
        setIdle();
      },
      onDone: () => setIdle(),
      onPermissionRequired: data => {
        // Pause streaming display while permission modal is open
        Permissions.show(data, result.runId);
      }
    });
  }

  function setRunning() {
    btnSend.textContent = '■ Stop';
    btnSend.classList.add('stopping');
    btnSend.disabled = false;
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
    inputEl.disabled = !_sessionId;
    btnSend.textContent = 'Send';
    btnSend.classList.remove('stopping');
    btnSend.disabled = !_sessionId;
    _activeStream    = null;
    _activeRunId     = null;
  }

  btnSend.addEventListener('click', () => {
    if (btnSend.classList.contains('stopping')) cancel();
    else send();
  });
  function handleInputKey(e) {
    const isEnter = e.key === 'Enter' || e.code === 'Enter' || e.keyCode === 13 || e.which === 13;
    if (isEnter && !e.shiftKey) { e.preventDefault(); send(); }
  }

  inputEl.addEventListener('keydown', handleInputKey);
  inputEl.addEventListener('keypress', handleInputKey);

  return { setSession };
})();
