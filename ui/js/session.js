/**
 * session.js — Session list management.
 * Depends on: api.js
 */

const Sessions = (() => {
  let _sessions    = [];
  let _activeId    = null;
  let _onSelect    = null;

  const listEl     = document.getElementById('session-list');
  const btnNew     = document.getElementById('btn-new-session');
  const modal      = document.getElementById('new-session-modal');
  const modalForm  = document.getElementById('new-session-form');
  const inputTitle = document.getElementById('new-session-title');
  const inputPath  = document.getElementById('new-session-path');
  const btnCancel  = document.getElementById('new-session-cancel');
  const btnBrowse  = document.getElementById('btn-browse-folder');
  const folderPicker = document.getElementById('folder-picker');
  const renameModal = document.getElementById('rename-session-modal');
  const renameForm  = document.getElementById('rename-session-form');
  const renameInput = document.getElementById('rename-session-title');
  const renameCancel = document.getElementById('rename-session-cancel');

  function onSelect(cb) { _onSelect = cb; }

  async function load() {
    _sessions = await Api.get('/sessions');
    render();
  }

  function render() {
    listEl.innerHTML = '';
    for (const s of _sessions) {
      const li    = document.createElement('li');
      li.dataset.id = s.id;
      if (s.id === _activeId) li.classList.add('active');

      const title = document.createElement('span');
      title.className   = 'session-title';
      title.textContent = s.title;
      title.title       = s.rootPath ?? '';

      const del   = document.createElement('button');
      del.className   = 'session-delete';
      del.textContent = '×';
      del.title       = 'Delete session';
      del.addEventListener('click', async e => {
        e.stopPropagation();
        if (!confirm(`Delete "${s.title}"?`)) return;
        await Api.del(`/sessions/${s.id}`);
        if (_activeId === s.id) { _activeId = null; _onSelect?.(null); }
        await load();
      });

      li.appendChild(title);
      li.appendChild(del);
      li.addEventListener('click', () => activate(s.id));
      li.addEventListener('dblclick', e => {
        e.stopPropagation();
        activate(s.id);
        openRenameModal();
      });
      listEl.appendChild(li);
    }
  }

  function setSession(updated, notify = true) {
    const idx = _sessions.findIndex(s => s.id === updated.id);
    if (idx >= 0) {
      _sessions[idx] = updated;
    } else {
      _sessions.unshift(updated);
    }
    render();
    if (notify && _activeId === updated.id) _onSelect?.(updated);
  }

  function activate(id) {
    _activeId = id;
    render();
    const session = _sessions.find(s => s.id === id);
    _onSelect?.(session ?? null);
  }

  function openRenameModal() {
    const session = _sessions.find(s => s.id === _activeId);
    if (!session) return;
    renameInput.value = session.title;
    renameModal.classList.add('open');
    renameInput.focus();
    renameInput.select();
  }

  function closeRenameModal() {
    renameModal.classList.remove('open');
  }

  function openModal() {
    inputTitle.value = `Session ${new Date().toLocaleString()}`;
    inputPath.value  = '';
    modal.classList.add('open');
    inputTitle.focus();
  }

  function closeModal() {
    modal.classList.remove('open');
  }

  modalForm.addEventListener('submit', async e => {
    e.preventDefault();
    const title    = inputTitle.value.trim() || `Session ${new Date().toLocaleString()}`;
    const rootPath = inputPath.value.trim()  || null;
    try {
      const session = await Api.post('/sessions', { title, model: 'local-model', rootPath });
      closeModal();
      await load();
      activate(session.id);
    } catch (err) {
      console.error('Failed to create session:', err);
      alert(`Failed to create session: ${err.message}`);
    }
  });

  renameForm.addEventListener('submit', async e => {
    e.preventDefault();
    const session = _sessions.find(s => s.id === _activeId);
    if (!session) return;
    const title = renameInput.value.trim();
    if (!title) return;
    try {
      const updated = await Api.post(`/sessions/${session.id}/title`, { title });
      closeRenameModal();
      setSession(updated);
    } catch (err) {
      console.error('Failed to rename session:', err);
      alert(`Failed to rename session: ${err.message}`);
    }
  });

  btnBrowse.addEventListener('click', () => folderPicker.click());
  folderPicker.addEventListener('change', () => {
    const file = folderPicker.files[0];
    if (!file) return;
    // webkitRelativePath is "folderName/..." — extract just the root folder name
    // and append to whatever the user already typed as a parent path prefix.
    const folderName = file.webkitRelativePath.split('/')[0];
    const current    = inputPath.value.trim();
    // If field is empty just put the folder name; user can prepend the parent path.
    inputPath.value = current ? current : folderName;
    folderPicker.value = '';
  });

  btnCancel.addEventListener('click', closeModal);
  modal.addEventListener('click', e => { if (e.target === modal) closeModal(); });
  btnNew.addEventListener('click', openModal);
  renameCancel.addEventListener('click', closeRenameModal);
  renameModal.addEventListener('click', e => { if (e.target === renameModal) closeRenameModal(); });

  return {
    load,
    onSelect,
    setSession,
    openRenameModal,
    getActive: () => _sessions.find(s => s.id === _activeId) ?? null
  };
})();
