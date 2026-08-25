const Settings = (() => {
  let _settings = {
    theme: 'light',
    showStatusLine: false,
    serverURL: 'http://172.23.64.1:1234',
    apiKey: '',
    modelName: 'mistralai/ministral-3-14b-reasoning',
    apiMode: 'chatcompletions',
    vlmServerURL: '',
    vlmAPIKey: '',
    vlmModel: '',
    debugMode: false,
    vlmParallelism: 4
  };

  const btnSettings = document.getElementById('btn-settings');
  const dropdown    = document.getElementById('settings-dropdown');
  const menuSettings = document.getElementById('menu-settings');
  const menuDebugLog = document.getElementById('menu-debug-log');
  const modal       = document.getElementById('settings-modal');
  const form        = document.getElementById('settings-form');
  const btnCancel   = document.getElementById('settings-cancel');

  // General tab
  const themeSelect = document.getElementById('settings-theme');
  const showStatusLineCheckbox = document.getElementById('settings-show-status-line');

  // LLM tab
  const serverURLInput = document.getElementById('settings-server-url');
  const apiKeyInput    = document.getElementById('settings-api-key');
  const modelNameInput = document.getElementById('settings-model-name');
  const apiModeSelect  = document.getElementById('settings-api-mode');

  // VLM tab
  const vlmServerURLInput    = document.getElementById('settings-vlm-server-url');
  const vlmAPIKeyInput       = document.getElementById('settings-vlm-api-key');
  const vlmModelInput        = document.getElementById('settings-vlm-model');
  const vlmParallelCheckbox  = document.getElementById('settings-vlm-parallel');
  const vlmParallelismInput  = document.getElementById('settings-vlm-parallelism');

  // Debug tab
  const debugModeCheckbox = document.getElementById('settings-debug-mode');

  // Tab switching
  const tabs  = document.querySelectorAll('.settings-tab');
  const panes = document.querySelectorAll('.settings-tab-pane');

  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      const target = tab.dataset.tab;
      tabs.forEach(t => t.classList.toggle('active', t.dataset.tab === target));
      panes.forEach(p => p.classList.toggle('active', p.dataset.tab === target));
    });
  });

  function apply(settings) {
    _settings = {
      theme: settings?.theme === 'dark' ? 'dark' : 'light',
      showStatusLine: !!settings?.showStatusLine,
      serverURL: settings?.serverURL ?? _settings.serverURL,
      apiKey: settings?.apiKey ?? '',
      modelName: settings?.modelName ?? _settings.modelName,
      apiMode: settings?.apiMode === 'responses' ? 'responses' : 'chatcompletions',
      vlmServerURL: settings?.vlmServerURL ?? '',
      vlmAPIKey: settings?.vlmAPIKey ?? '',
      vlmModel: settings?.vlmModel ?? '',
      debugMode: !!settings?.debugMode,
      vlmParallelism: Math.max(1, Math.min(32, parseInt(settings?.vlmParallelism) || 4))
    };
    document.documentElement.dataset.theme = _settings.theme;

    // General
    if (themeSelect) themeSelect.value = _settings.theme;
    if (showStatusLineCheckbox) showStatusLineCheckbox.checked = _settings.showStatusLine;

    // LLM
    if (serverURLInput) serverURLInput.value = _settings.serverURL;
    if (apiKeyInput) apiKeyInput.value = _settings.apiKey;
    if (modelNameInput) modelNameInput.value = _settings.modelName;
    if (apiModeSelect) apiModeSelect.value = _settings.apiMode;

    // VLM
    if (vlmServerURLInput) vlmServerURLInput.value = _settings.vlmServerURL;
    if (vlmAPIKeyInput) vlmAPIKeyInput.value = _settings.vlmAPIKey;
    if (vlmModelInput) vlmModelInput.value = _settings.vlmModel;
    if (vlmParallelCheckbox) vlmParallelCheckbox.checked = _settings.vlmParallelism > 1;
    if (vlmParallelismInput) vlmParallelismInput.value = _settings.vlmParallelism;

    // Debug
    if (debugModeCheckbox) debugModeCheckbox.checked = _settings.debugMode;

    // Status line visibility
    const statusLine = document.getElementById('status-line');
    if (statusLine) {
      statusLine.style.display = _settings.showStatusLine ? 'block' : 'none';
    }
  }

  async function load() {
    try {
      const settings = await Api.get('/settings');
      apply(settings);
      return _settings;
    } catch (err) {
      apply(_settings);
      console.error('Failed to load settings:', err);
      return _settings;
    }
  }

  async function save(settings) {
    const saved = await Api.post('/settings', settings);
    apply(saved);
    return saved;
  }

  function openModal() {
    // General
    themeSelect.value = _settings.theme;
    showStatusLineCheckbox.checked = _settings.showStatusLine;
    // LLM
    serverURLInput.value = _settings.serverURL;
    apiKeyInput.value = _settings.apiKey;
    modelNameInput.value = _settings.modelName;
    apiModeSelect.value = _settings.apiMode;
    // VLM
    vlmServerURLInput.value = _settings.vlmServerURL;
    vlmAPIKeyInput.value = _settings.vlmAPIKey;
    vlmModelInput.value = _settings.vlmModel;
    if (vlmParallelCheckbox) vlmParallelCheckbox.checked = _settings.vlmParallelism > 1;
    if (vlmParallelismInput) vlmParallelismInput.value = _settings.vlmParallelism;
    // Debug
    if (debugModeCheckbox) debugModeCheckbox.checked = _settings.debugMode;

    // Reset to General tab
    tabs.forEach(t => t.classList.toggle('active', t.dataset.tab === 'general'));
    panes.forEach(p => p.classList.toggle('active', p.dataset.tab === 'general'));

    modal.classList.add('open');
    themeSelect.focus();
  }

  function closeModal() {
    modal.classList.remove('open');
  }

  function switchTab(name) {
    tabs.forEach(t => t.classList.toggle('active', t.dataset.tab === name));
    panes.forEach(p => p.classList.toggle('active', p.dataset.tab === name));
  }

  function validate() {
    // Clear previous error styling
    [serverURLInput, modelNameInput, vlmServerURLInput, vlmModelInput].forEach(input => {
      input.style.borderColor = '';
    });

    const errors = [];

    const serverURL = serverURLInput.value.trim();
    const modelName = modelNameInput.value.trim();
    if (!serverURL) {
      errors.push({ input: serverURLInput, tab: 'llm', msg: 'LLM Server URL is required.' });
    }
    if (!modelName) {
      errors.push({ input: modelNameInput, tab: 'llm', msg: 'LLM Model is required.' });
    }

    const vlmServerURL = vlmServerURLInput.value.trim();
    const vlmModel = vlmModelInput.value.trim();
    if (vlmServerURL && !vlmModel) {
      errors.push({ input: vlmModelInput, tab: 'vlm', msg: 'VLM Model is required when VLM Server URL is filled.' });
    } else if (!vlmServerURL && vlmModel) {
      errors.push({ input: vlmServerURLInput, tab: 'vlm', msg: 'VLM Server URL is required when VLM Model is filled.' });
    }

    return errors;
  }

  form.addEventListener('submit', async e => {
    e.preventDefault();
    const errors = validate();
    if (errors.length > 0) {
      const first = errors[0];
      switchTab(first.tab);
      first.input.focus();
      errors.forEach(err => { err.input.style.borderColor = 'var(--accent)'; });
      alert(errors.map(err => err.msg).filter(Boolean).join('\n'));
      return;
    }
    try {
      await save({
        theme: themeSelect.value,
        showStatusLine: showStatusLineCheckbox.checked,
        serverURL: serverURLInput.value.trim(),
        apiKey: apiKeyInput.value.trim(),
        modelName: modelNameInput.value.trim(),
        apiMode: apiModeSelect.value,
        vlmServerURL: vlmServerURLInput.value.trim(),
        vlmAPIKey: vlmAPIKeyInput.value.trim(),
        vlmModel: vlmModelInput.value.trim(),
        debugMode: debugModeCheckbox.checked,
        vlmParallelism: vlmParallelCheckbox.checked ? Math.max(1, Math.min(32, parseInt(vlmParallelismInput.value) || 4)) : 1
      });
      closeModal();
    } catch (err) {
      alert(`Failed to save settings: ${err.message}`);
    }
  });

  // Dropdown menu handlers
  function openDropdown() {
    dropdown.classList.add('open');
    btnSettings.setAttribute('aria-expanded', 'true');
  }

  function closeDropdown() {
    dropdown.classList.remove('open');
    btnSettings.setAttribute('aria-expanded', 'false');
  }

  btnSettings.addEventListener('click', (e) => {
    e.stopPropagation();
    if (dropdown.classList.contains('open')) {
      closeDropdown();
    } else {
      openDropdown();
    }
  });

  document.addEventListener('click', (e) => {
    if (!dropdown.contains(e.target)) {
      closeDropdown();
    }
  });

  menuSettings?.addEventListener('click', () => {
    closeDropdown();
    openModal();
  });

  menuDebugLog?.addEventListener('click', () => {
    closeDropdown();
    const params = new URLSearchParams(window.location.search);
    const token  = params.get('token') || '';
    window.open(`/log-viewer.html?token=${encodeURIComponent(token)}`, '_blank');
  });

  btnCancel.addEventListener('click', closeModal);
  modal.addEventListener('click', e => { if (e.target === modal) closeModal(); });

  return { load, apply, save };
})();
