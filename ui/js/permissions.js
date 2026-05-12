/**
 * permissions.js — Permission modal handler.
 * Listens for permission_required SSE events and shows modal with options.
 */

const Permissions = (() => {
  const modal = document.getElementById('permission-modal');
  const toolSpan = document.getElementById('permission-tool');
  const pathSpan = document.getElementById('permission-path');
  const btnDeny = document.getElementById('btn-permission-deny');
  const btnOnce = document.getElementById('btn-permission-once');
  const btnSession = document.getElementById('btn-permission-session');
  const btnAlways = document.getElementById('btn-permission-always');

  let currentRunId = null;
  let isResolving = false;

  /**
   * Show the permission modal.
   * @param {Object} data - Permission data from SSE event
   * @param {string} data.tool - Tool name
   * @param {string} data.path - File path
   * @param {string[]} data.options - Available options
   * @param {string} runId - Current run ID
   */
  function show(data, runId) {
    currentRunId = runId;
    isResolving = false;

    toolSpan.textContent = data.tool || 'unknown';
    pathSpan.textContent = data.path || '(none)';

    modal.classList.add('open');
  }

  /**
   * Hide the permission modal.
   */
  function hide() {
    modal.classList.remove('open');
    currentRunId = null;
  }

  /**
   * Send permission decision to backend.
   * @param {string} decision - 'granted' or 'denied'
   * @param {string} ttl - 'Once', 'ForSession', or 'Always'
   */
  async function sendDecision(decision, ttl) {
    if (!currentRunId || isResolving) return;
    isResolving = true;

    try {
      await Api.post(`/permissions/${currentRunId}`, {
        decision: decision,
        ttl: ttl,
        pathPrefix: null
      });
    } catch (err) {
      console.error('Failed to send permission decision:', err);
      alert(`Failed to send decision: ${err.message}`);
    } finally {
      hide();
    }
  }

  // Button handlers
  btnDeny?.addEventListener('click', () => {
    sendDecision('denied', 'Once');
  });

  btnOnce?.addEventListener('click', () => {
    sendDecision('granted', 'Once');
  });

  btnSession?.addEventListener('click', () => {
    sendDecision('granted', 'ForSession');
  });

  btnAlways?.addEventListener('click', () => {
    sendDecision('granted', 'Always');
  });

  // Close modal on backdrop click
  modal?.addEventListener('click', (e) => {
    if (e.target === modal) {
      // Don't auto-close on backdrop click - user must make a decision
      // This prevents accidental dismissals
    }
  });

  // Handle Escape key - treat as Deny
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && modal?.classList.contains('open')) {
      sendDecision('denied', 'Once');
    }
  });

  return {
    show,
    hide,
    get isOpen() { return modal?.classList.contains('open') || false; }
  };
})();
