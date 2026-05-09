const Settings = (() => {
  let _settings = {
    theme: 'light',
    showStatusLine: false,
    serverUrl: 'http://172.23.64.1:1234',
    modelName: 'mistralai/ministral-3-14b-reasoning'
  };

  const btnSettings = document.getElementById('btn-settings');
  const modal       = document.getElementById('settings-modal');
  const form        = document.getElementById('settings-form');
  const themeSelect = document.getElementById('settings-theme');
  const serverUrlInput = document.getElementById('settings-server-url');
  const modelNameInput = document.getElementById('settings-model-name');
  const showStatusLineCheckbox = document.getElementById('settings-show-status-line');
  const btnCancel   = document.getElementById('settings-cancel');

  function apply(settings) {
    _settings = {
      theme: settings?.theme === 'dark' ? 'dark' : 'light',
      showStatusLine: !!settings?.showStatusLine,
      serverUrl: settings?.serverUrl ?? _settings.serverUrl,
      modelName: settings?.modelName ?? _settings.modelName
    };
    document.documentElement.dataset.theme = _settings.theme;
    if (themeSelect) themeSelect.value = _settings.theme;
    if (serverUrlInput) serverUrlInput.value = _settings.serverUrl;
    if (modelNameInput) modelNameInput.value = _settings.modelName;
    if (showStatusLineCheckbox) showStatusLineCheckbox.checked = _settings.showStatusLine;
    // Apply status line visibility
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
    themeSelect.value = _settings.theme;
    serverUrlInput.value = _settings.serverUrl;
    modelNameInput.value = _settings.modelName;
    showStatusLineCheckbox.checked = _settings.showStatusLine;
    modal.classList.add('open');
    themeSelect.focus();
  }

  function closeModal() {
    modal.classList.remove('open');
  }

  form.addEventListener('submit', async e => {
    e.preventDefault();
    try {
      await save({
        theme: themeSelect.value,
        serverUrl: serverUrlInput.value.trim(),
        modelName: modelNameInput.value.trim(),
        showStatusLine: showStatusLineCheckbox.checked
      });
      closeModal();
    } catch (err) {
      alert(`Failed to save settings: ${err.message}`);
    }
  });

  btnSettings.addEventListener('click', openModal);
  btnCancel.addEventListener('click', closeModal);
  modal.addEventListener('click', e => { if (e.target === modal) closeModal(); });

  return { load, apply, save };
})();
