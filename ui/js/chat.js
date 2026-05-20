/**
 * chat.js — Chat rendering and SSE stream management.
 * Depends on: api.js, marked.min.js
 */

const Chat = (() => {
  let _sessionId    = null;
  let _activeStream = null;
  let _activeRunId  = null;

  const messagesEl = document.getElementById('messages');
  const inputEl    = document.getElementById('msg-input');
  const btnSend    = document.getElementById('btn-send');
  const titleEl    = document.getElementById('chat-title');
  const pathEl     = document.getElementById('chat-path');
  const createdEl  = document.getElementById('chat-created');

  function setSession(session) {
    _sessionId = session?.id ?? null;
    updateHeader(session);
    messagesEl.innerHTML = '';
    inputEl.disabled     = !session;
    btnSend.disabled     = !session;
    if (session) loadHistory();
  }

  function updateHeader(session) {
    titleEl.textContent  = session ? session.title : 'Select or create a session';
    titleEl.disabled     = !session;
    pathEl.textContent   = session?.rootPath ? `Working folder: ${session.rootPath}` : '';
    createdEl.textContent = session ? `Created: ${formatSessionTime(session.createdAt)}` : '';
  }

  function formatSessionTime(value) {
    if (!value) return '';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return date.toLocaleString();
  }

  // ── Markdown ──────────────────────────────────────────────────────────────

  function bulletPlanSteps(text) {
    if (!text) return '';

    const actionLine = /^(\s*)(?![-*]\s+)(?:\*\*)?(List|Search|Read|Analyze|Synthesize|Extract|Compare|Review|Check|Run|Execute|Fetch|Get|Look|Find|Scan|Summarize|Draft|Write|Create|Generate|Query|Call)\b/i;
    const lines = text.split(/\r?\n/);
    const out = [];
    let inPlan = false;
    let inBullets = false;

    for (const line of lines) {
      const trimmed = line.trim();
      const isActionLine = actionLine.test(line);
      const isToolOrCommand = /^run\(|^files\.|^```/.test(trimmed);

      if (/\b(my plan|plan|i will)[:;]/i.test(line)) {
        inPlan = true;
      }

      if (inPlan && isActionLine && !isToolOrCommand) {
        if (!inBullets && out.length > 0 && out[out.length - 1] !== '') {
          out.push('');
        }
        out.push(line.replace(actionLine, (match, indent) => `${indent}- ${match.slice(indent.length)}`));
        inBullets = true;
        continue;
      }

      if (inBullets && trimmed !== '' && !isActionLine) {
        if (out.length > 0 && out[out.length - 1] !== '') {
          out.push('');
        }
        inBullets = false;
        inPlan = false;
      }

      out.push(line);
    }

    return out.join('\n');
  }

  function renderMarkdown(text) {
    return marked.parse(bulletPlanSteps(text || ''), { breaks: true, gfm: true });
  }

  // ── Step/turn DOM helpers ─────────────────────────────────────────────────

  /**
   * Creates the outer agent-steps block that sits above the final answer bubble.
   * Returns { stepsEl, addIteration }.
   * addIteration(n) → { thinkingEl, addToolChip }
   */
  function createStepsBlock() {
    const wrap = document.createElement('div');
    wrap.className = 'agent-steps';

    const header = document.createElement('div');
    header.className = 'agent-steps-header';
    header.textContent = 'Steps';
    header.addEventListener('click', () => wrap.classList.toggle('collapsed'));
    wrap.appendChild(header);

    const body = document.createElement('div');
    body.className = 'agent-steps-body';
    wrap.appendChild(body);

    function addIteration(n) {
      const iterEl = document.createElement('div');
      iterEl.className = 'agent-iter';

      const iterHeader = document.createElement('div');
      iterHeader.className = 'agent-iter-header';
      iterHeader.textContent = `Turn ${n}`;
      iterHeader.addEventListener('click', () => iterEl.classList.toggle('collapsed'));
      iterEl.appendChild(iterHeader);

      const thinkingEl = document.createElement('div');
      thinkingEl.className = 'agent-thinking';
      iterEl.appendChild(thinkingEl);

      function addToolChip(command) {
        const chip = document.createElement('div');
        chip.className = 'agent-tool-chip';

        const cmdEl = document.createElement('div');
        cmdEl.className = 'agent-tool-cmd';
        cmdEl.textContent = command;
        chip.appendChild(cmdEl);

        const resultEl = document.createElement('div');
        resultEl.className = 'agent-tool-result';
        chip.appendChild(resultEl);

        iterEl.appendChild(chip);
        return resultEl;
      }

      body.appendChild(iterEl);
      return { thinkingEl, addToolChip };
    }

    return { stepsEl: wrap, addIteration };
  }

  /**
   * Renders a complete AgentTurn (from history reload) as a steps block.
   * Groups steps by iteration and populates thinking/tool_call rows.
   */
  function renderTurnSteps(turn) {
    const { stepsEl, addIteration } = createStepsBlock();
    stepsEl.classList.add('collapsed');  // start collapsed on reload

    // Group steps by iteration number
    const byIter = {};
    for (const step of turn.steps) {
      if (!byIter[step.iteration]) byIter[step.iteration] = [];
      byIter[step.iteration].push(step);
    }

    for (const iterNum of Object.keys(byIter).sort((a, b) => a - b)) {
      const { thinkingEl, addToolChip } = addIteration(Number(iterNum));
      for (const step of byIter[iterNum]) {
        if (step.stepType === 'thinking') {
          thinkingEl.innerHTML = renderMarkdown(step.content);
        } else if (step.stepType === 'tool_call') {
          const resultEl = addToolChip(step.command);
          resultEl.textContent = step.result;
          resultEl.classList.add(step.result.includes('\nerror:') ? 'error' : 'ok');
          // Tool result starts collapsed; click chip header to expand
          const chip = resultEl.parentElement;
          chip.querySelector('.agent-tool-cmd').addEventListener('click', () =>
            chip.classList.toggle('expanded')
          );
        }
      }
    }

    return stepsEl;
  }

  // ── Token stats UI ────────────────────────────────────────────────────────

  function makeTokenStatsUI(stats) {
    const chartBtn = document.createElement('button');
    chartBtn.className = 'msg-action-btn';
    chartBtn.title = 'Token usage';
    chartBtn.innerHTML = '<img src="icons/chart.svg" alt="" width="16" height="16"/>';

    const statsEl = document.createElement('div');
    statsEl.className = 'msg-token-stats';
    const latencySec = (stats.latencyMs / 1000).toFixed(1) + 's';
    statsEl.innerHTML =
      `<div class="msg-token-stat"><span class="msg-token-stat-label">Tokens In</span><span class="msg-token-stat-value">${stats.inputTokens.toLocaleString()}</span></div>` +
      `<div class="msg-token-stat"><span class="msg-token-stat-label">Tokens Out</span><span class="msg-token-stat-value">${stats.outputTokens.toLocaleString()}</span></div>` +
      `<div class="msg-token-stat"><span class="msg-token-stat-label">Total Time</span><span class="msg-token-stat-value">${latencySec}</span></div>` +
      `<div class="msg-token-stat"><span class="msg-token-stat-label">Turns</span><span class="msg-token-stat-value">${stats.turns}</span></div>`;

    chartBtn.addEventListener('click', () => statsEl.classList.toggle('visible'));
    return [chartBtn, statsEl];
  }

  // ── Message rendering ─────────────────────────────────────────────────────

  function appendMessage(role, content, stepsEl = null, messageId = null, tokenStats = null) {
    const wrap = document.createElement('div');
    wrap.className = `msg ${role}`;
    if (messageId) wrap.dataset.messageId = messageId;

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

    // Actions bar with copy/restart icons
    const actionsEl = document.createElement('div');
    actionsEl.className = 'msg-actions';

    // Copy icon (both user and agent)
    const copyBtn = document.createElement('button');
    copyBtn.className = 'msg-action-btn';
    copyBtn.title = 'Copy';
    copyBtn.innerHTML = '<img src="icons/copy.svg" alt="" width="16" height="16"/>';
    copyBtn.addEventListener('click', () => copyMessage(role, bubble));
    actionsEl.appendChild(copyBtn);

    // Restart icon (only for user turns)
    if (role === 'user' && messageId) {
      const restartBtn = document.createElement('button');
      restartBtn.className = 'msg-action-btn';
      restartBtn.title = 'Restart from here';
      restartBtn.innerHTML = '<img src="icons/restart.svg" alt="" width="16" height="16"/>';
      restartBtn.addEventListener('click', () => confirmRestart(messageId));
      actionsEl.appendChild(restartBtn);
    }

    let tokenStatsEl = null;
    if (role === 'assistant' && tokenStats) {
      const [chartBtn, sEl] = makeTokenStatsUI(tokenStats);
      actionsEl.appendChild(chartBtn);
      tokenStatsEl = sEl;
    }

    wrap.appendChild(roleEl);
    if (stepsEl) wrap.appendChild(stepsEl);
    wrap.appendChild(bubble);
    wrap.appendChild(actionsEl);
    if (tokenStatsEl) wrap.appendChild(tokenStatsEl);
    messagesEl.appendChild(wrap);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return bubble;
  }

  // ── History load ──────────────────────────────────────────────────────────

  async function loadHistory() {
    const [messages, turns, usageList] = await Promise.all([
      Api.get(`/sessions/${_sessionId}/messages`),
      Api.get(`/sessions/${_sessionId}/agent-turns`),
      Api.get(`/sessions/${_sessionId}/token-usage`).catch(() => [])
    ]);

    // Sum token usage per traceId (a turn can have multiple LLM calls)
    const tokensByTraceId = {};
    for (const u of usageList) {
      if (!tokensByTraceId[u.traceId]) tokensByTraceId[u.traceId] = { inputTokens: 0, outputTokens: 0, latencyMs: 0, turns: 0 };
      tokensByTraceId[u.traceId].inputTokens  += u.promptTokens;
      tokensByTraceId[u.traceId].outputTokens += u.completionTokens;
      tokensByTraceId[u.traceId].latencyMs    += u.latencyMs;
      tokensByTraceId[u.traceId].turns        += 1;
    }

    // Build lookup: assistantMsgId → AgentTurn
    const turnByMsgId = {};
    for (const t of turns) {
      if (t.assistantMsgId) turnByMsgId[t.assistantMsgId] = t;
    }

    messagesEl.innerHTML = '';
    for (const m of messages) {
      const turn       = m.role === 'assistant' ? (turnByMsgId[m.id] ?? null) : null;
      const stepsEl    = turn ? renderTurnSteps(turn) : null;
      const tokenStats = turn ? (tokensByTraceId[turn.traceId] ?? null) : null;
      appendMessage(m.role, m.content, stepsEl, m.id, tokenStats);
    }
  }

  // ── Live run ──────────────────────────────────────────────────────────────

  async function send() {
    if (!_sessionId) return;
    const content = inputEl.value.trim();
    if (!content) return;

    inputEl.value    = '';
    inputEl.disabled = true;
    setRunning();

    // Append user message and capture its ID for restart functionality
    const userBubble = appendMessage('user', content, null, null);
    let userMsgId = null;

    // Create the assistant message wrap now; steps block and bubble live inside it
    const wrap = document.createElement('div');
    wrap.className = 'msg assistant';
    const roleEl = document.createElement('div');
    roleEl.className   = 'msg-role';
    roleEl.textContent = 'Agentica';
    wrap.appendChild(roleEl);
    messagesEl.appendChild(wrap);
    messagesEl.scrollTop = messagesEl.scrollHeight;

    const { stepsEl, addIteration } = createStepsBlock();
    wrap.appendChild(stepsEl);

    const asstBubble = document.createElement('div');
    asstBubble.className = 'msg-bubble streaming';
    wrap.appendChild(asstBubble);

    let result;
    try {
      result = await Api.post(`/sessions/${_sessionId}/messages`, { content });
    } catch (err) {
      asstBubble.textContent = `[Error sending message: ${err.message}]`;
      asstBubble.classList.remove('streaming');
      setIdle();
      return;
    }

    userMsgId = result.userMessageId;
    _activeRunId = result.runId;

    // Update user message element with the ID
    if (userMsgId) {
      const userMsgEl = userBubble.parentElement;
      userMsgEl.dataset.messageId = userMsgId;
      // Add restart button now that we have the ID
      const actionsEl = userMsgEl.querySelector('.msg-actions');
      if (actionsEl && !actionsEl.querySelector('.restart-btn')) {
        const restartBtn = document.createElement('button');
        restartBtn.className = 'msg-action-btn restart-btn';
        restartBtn.title = 'Restart from here';
        restartBtn.innerHTML = '<img src="icons/restart.svg" alt="" width="16" height="16"/>';
        restartBtn.addEventListener('click', () => confirmRestart(userMsgId));
        actionsEl.appendChild(restartBtn);
      }
    }

    // Per-iteration state
    let currentThinkingEl = null;
    let currentAddToolChip = null;
    let currentToolResultEl = null;
    let rawThinking = '';

    _activeStream = Api.stream(`/sessions/${_sessionId}/stream/${result.runId}`, {
      onIteration: ({ iteration }) => {
        rawThinking = '';
        const iter = addIteration(iteration);
        currentThinkingEl  = iter.thinkingEl;
        currentAddToolChip = iter.addToolChip;
        currentToolResultEl = null;
        messagesEl.scrollTop = messagesEl.scrollHeight;
      },
      onToken: tok => {
        // Tokens stream into the thinking div for the current iteration.
        // The final answer tokens (last iteration, no tool calls) go into asstBubble.
        if (currentThinkingEl) {
          rawThinking += tok;
          currentThinkingEl.innerHTML = renderMarkdown(rawThinking);
        } else {
          // No iteration boundary fired yet (single-shot response) — goes straight to bubble
          asstBubble.innerHTML = renderMarkdown((asstBubble.dataset.raw || '') + tok);
          asstBubble.dataset.raw = (asstBubble.dataset.raw || '') + tok;
        }
        messagesEl.scrollTop = messagesEl.scrollHeight;
      },
      onToolStart: ({ tool }) => {
        if (currentAddToolChip) {
          currentToolResultEl = currentAddToolChip(tool);
          const chip = currentToolResultEl.parentElement;
          chip.querySelector('.agent-tool-cmd').addEventListener('click', () =>
            chip.classList.toggle('expanded')
          );
        }
        messagesEl.scrollTop = messagesEl.scrollHeight;
      },
      onToolResult: ({ tool, output }) => {
        if (currentToolResultEl) {
          currentToolResultEl.textContent = output;
          currentToolResultEl.classList.add(output.includes('\nerror:') ? 'error' : 'ok');
          currentToolResultEl = null;
        }
        // After a tool result the next tokens belong to the next LLM call —
        // reset thinking so the next onIteration starts fresh.
        rawThinking = '';
        currentThinkingEl = null;
        messagesEl.scrollTop = messagesEl.scrollHeight;
      },
      onFinal: ({ assistantMessageId, sessionTitle }) => {
        // Move the accumulated thinking text out of the last iteration's thinking div
        // (that was the final answer, not a tool-calling step) and into the main bubble.
        if (currentThinkingEl && rawThinking) {
          asstBubble.innerHTML = renderMarkdown(rawThinking);
          currentThinkingEl.innerHTML = '';
          currentThinkingEl.closest('.agent-iter')?.remove();
        }
        asstBubble.classList.remove('streaming');
        delete asstBubble.dataset.raw;
        // Collapse the steps block if there are no iterations (simple answer)
        if (!stepsEl.querySelector('.agent-iter')) stepsEl.remove();
        if (sessionTitle) {
          const active = Sessions.getActive();
          if (active && active.id === _sessionId) {
            const updated = { ...active, title: sessionTitle };
            Sessions.setSession(updated, false);
            updateHeader(updated);
          }
        }
        // Add copy button (live run wrap is built manually and has no actions bar yet)
        const actionsEl = document.createElement('div');
        actionsEl.className = 'msg-actions';
        const copyBtn = document.createElement('button');
        copyBtn.className = 'msg-action-btn';
        copyBtn.title = 'Copy';
        copyBtn.innerHTML = '<img src="icons/copy.svg" alt="" width="16" height="16"/>';
        copyBtn.addEventListener('click', () => copyMessage('assistant', asstBubble));
        actionsEl.appendChild(copyBtn);
        wrap.appendChild(actionsEl);
        setIdle();
        // Async: fetch token usage and append chart button once available
        Api.get(`/sessions/${_sessionId}/token-usage`).then(usageList => {
          const stats = { inputTokens: 0, outputTokens: 0, latencyMs: 0, turns: 0 };
          for (const u of usageList) {
            if (u.traceId === result.traceId) {
              stats.inputTokens  += u.promptTokens;
              stats.outputTokens += u.completionTokens;
              stats.latencyMs    += u.latencyMs;
              stats.turns        += 1;
            }
          }
          if (stats.inputTokens > 0 || stats.outputTokens > 0) {
            const [chartBtn, statsEl] = makeTokenStatsUI(stats);
            actionsEl.appendChild(chartBtn);
            wrap.appendChild(statsEl);
          }
        }).catch(() => {});
      },
      onCancelled: () => {
        asstBubble.classList.remove('streaming');
        asstBubble.innerHTML += '<em> [cancelled]</em>';
        setIdle();
      },
      onError: msg => {
        asstBubble.innerHTML += `<em>\n[Error: ${msg}]</em>`;
        asstBubble.classList.remove('streaming');
        setIdle();
      },
      onDone: () => setIdle(),
      onPermissionRequired: data => Permissions.show(data, result.runId)
    });
  }

  // ── Run control ───────────────────────────────────────────────────────────

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
    inputEl.disabled    = !_sessionId;
    btnSend.textContent = 'Send';
    btnSend.classList.remove('stopping');
    btnSend.disabled    = !_sessionId;
    _activeStream       = null;
    _activeRunId        = null;
  }

  // ── Copy functionality ────────────────────────────────────────────────────────

  function copyMessage(role, bubble) {
    let text;
    if (role === 'user') {
      // Simple text copy for user messages
      text = bubble.textContent;
    } else {
      // Rich HTML copy for agent messages
      text = bubble.innerHTML;
    }

    if (navigator.clipboard && navigator.clipboard.writeText) {
      if (role === 'assistant') {
        const htmlBlob  = new Blob([text], { type: 'text/html' });
        const plainBlob = new Blob([bubble.innerText], { type: 'text/plain' });
        const item = new ClipboardItem({ 'text/html': htmlBlob, 'text/plain': plainBlob });
        navigator.clipboard.write([item]).catch(err => {
          console.error('Failed to copy HTML:', err);
          navigator.clipboard.writeText(bubble.innerText);
        });
      } else {
        navigator.clipboard.writeText(text).catch(err => {
          console.error('Failed to copy text:', err);
        });
      }
    } else {
      // Fallback for older browsers
      const textarea = document.createElement('textarea');
      textarea.value = bubble.textContent;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
    }
  }

  // ── Restart functionality ────────────────────────────────────────────────────

  function confirmRestart(userMessageId) {
    if (!_sessionId) return;
    if (!window.confirm('This will delete all messages after this point and restart the conversation. Are you sure?')) {
      return;
    }
    restartFrom(userMessageId);
  }

  async function restartFrom(userMessageId) {
    try {
      await Api.post(`/sessions/${_sessionId}/restart`, { fromMessageId: userMessageId });
      // Reload the conversation
      await loadHistory();
    } catch (err) {
      console.error('Failed to restart:', err);
      alert('Failed to restart conversation: ' + err.message);
    }
  }

  btnSend.addEventListener('click', () => {
    if (_activeRunId) cancel();
    else send();
  });

  inputEl.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
  });

  titleEl.addEventListener('click', () => {
    if (_sessionId) Sessions.openRenameModal();
  });

  return { setSession };
})();
