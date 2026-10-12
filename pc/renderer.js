(() => {
  'use strict';

  const $ = id => document.getElementById(id);
  const tabStrip = $('tabStrip');
  const stage = $('viewStage');
  const home = $('homeScreen');
  const loadingBar = $('loadingBar');
  const address = $('addressInput');
  const settingsPanel = $('enhancePanel');
  const panelScrim = $('panelScrim');
  const tabs = [];
  let nextTabId = 1;
  let activeTab = null;
  let loadTimer = null;

  const defaults = {
    enhanceEnabled: true,
    target: '4k',
    sharpness: 35,
    smart: false,
    adaptive: true,
    adBlock: true
  };
  let settings;
  try { settings = { ...defaults, ...JSON.parse(localStorage.getItem('ultra-upscaler-settings') || '{}') }; }
  catch { settings = { ...defaults }; }

  function saveSettings() {
    localStorage.setItem('ultra-upscaler-settings', JSON.stringify(settings));
  }

  function setStatus(message) { $('statusText').textContent = message; }

  function normalizeUrl(raw) {
    let value = String(raw || '').trim();
    if (!value) return null;
    if (/^https?:\/\//i.test(value)) {
      try { const u = new URL(value); return ['http:', 'https:'].includes(u.protocol) ? u.href : null; }
      catch { return null; }
    }
    if (/^[a-z][a-z0-9+.-]*:/i.test(value)) return null;
    if (value.includes('.') && !/\s/.test(value)) return 'https://' + value;
    return 'https://duckduckgo.com/?q=' + encodeURIComponent(value);
  }

  function pageTitle(tab) {
    const raw = (tab.title || '').trim();
    if (raw && raw !== 'about:blank') return raw.length > 28 ? raw.slice(0, 27) + '…' : raw;
    if (tab.url && tab.url !== 'about:blank') {
      try { return new URL(tab.url).hostname.replace(/^www\./, '') || 'Loading…'; }
      catch { return 'Loading…'; }
    }
    return 'New Tab';
  }

  function renderTabs() {
    tabStrip.replaceChildren();
    for (const tab of tabs) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'tab' + (tab === activeTab ? ' active' : '');
      button.setAttribute('role', 'tab');
      button.setAttribute('aria-selected', tab === activeTab ? 'true' : 'false');
      button.title = tab.url && tab.url !== 'about:blank' ? tab.url : 'New Tab';

      const favicon = document.createElement('span');
      favicon.className = 'tab-favicon';
      favicon.textContent = pageTitle(tab) === 'New Tab' ? '+' : '◈';
      const title = document.createElement('span');
      title.className = 'tab-title';
      title.textContent = pageTitle(tab);
      const close = document.createElement('span');
      close.className = 'tab-close';
      close.textContent = '×';
      close.setAttribute('role', 'button');
      close.setAttribute('aria-label', 'Close tab');
      close.addEventListener('click', event => { event.stopPropagation(); closeTab(tab); });
      button.append(favicon, title, close);
      button.addEventListener('click', () => selectTab(tab));
      tabStrip.appendChild(button);
    }
  }

  function showHome(isHome) {
    home.classList.toggle('hidden', !isHome);
  }

  function selectTab(tab) {
    if (!tab || !tabs.includes(tab)) return;
    activeTab = tab;
    for (const item of tabs) item.view.classList.toggle('active', item === tab && !item.isHome);
    showHome(tab.isHome);
    address.value = tab.isHome ? '' : tab.url;
    renderTabs();
    updateNavigationButtons();
    if (tab.isHome) $('homeSearchInput').focus({ preventScroll: true });
  }

  function updateNavigationButtons() {
    if (!activeTab) return;
    try {
      $('backButton').disabled = !activeTab.view.canGoBack();
      $('forwardButton').disabled = !activeTab.view.canGoForward();
    } catch {}
  }

  function injectPageSettings(tab) {
    if (!tab || !tab.view || tab.isHome) return;
    const payload = JSON.stringify(settings);
    const source = '(' + window.UltraPageScripts.adblock.toString() + ')(' + Boolean(settings.adBlock) + ');'
      + '(' + window.UltraPageScripts.enhance.toString() + ')(' + payload + ');';
    tab.view.executeJavaScript(source, true).catch(() => {});
  }

  function createTab(initialUrl = '') {
    const view = document.createElement('webview');
    view.className = 'browser-view';
    view.setAttribute('webpreferences', 'contextIsolation=yes,nodeIntegration=no,sandbox=yes');
    view.setAttribute('allowpopups', '');
    view.setAttribute('partition', 'persist:ultra-upscaler-browser');
    // The BrowserWindow's default session filter is also applied to the persistent browser partition below.
    const tab = { id: nextTabId++, view, title: 'New Tab', url: 'about:blank', isHome: true, loading: false };
    view.addEventListener('did-start-loading', () => {
      tab.loading = true;
      if (tab === activeTab) { loadingBar.classList.remove('done'); loadingBar.classList.add('loading'); setStatus('Loading…'); }
    });
    view.addEventListener('did-stop-loading', () => {
      tab.loading = false;
      if (tab === activeTab) { loadingBar.classList.remove('loading'); loadingBar.classList.add('done'); setStatus('Ready'); }
      injectPageSettings(tab);
    });
    view.addEventListener('dom-ready', () => injectPageSettings(tab));
    view.addEventListener('did-navigate', event => {
      tab.url = event.url || tab.url;
      tab.isHome = tab.url === 'about:blank';
      if (tab === activeTab) { address.value = tab.isHome ? '' : tab.url; showHome(tab.isHome); }
      renderTabs(); updateNavigationButtons();
    });
    view.addEventListener('did-navigate-in-page', event => {
      tab.url = event.url || tab.url;
      if (tab === activeTab) address.value = tab.url;
      renderTabs();
    });
    view.addEventListener('page-title-updated', event => {
      tab.title = event.title || tab.title;
      renderTabs();
    });
    view.addEventListener('new-window', event => {
      // Compatibility path for Electron releases exposing the legacy webview event.
      if (event.url && normalizeUrl(event.url)) createTab(normalizeUrl(event.url));
    });
    view.addEventListener('will-navigate', event => {
      if (!/^https?:\/\//i.test(event.url) && event.url !== 'about:blank') {
        event.preventDefault();
        setStatus('For safety, only web pages (http/https) can be opened in tabs.');
      }
    });
    stage.appendChild(view);
    tabs.push(tab);
    selectTab(tab);
    if (initialUrl) navigate(tab, initialUrl);
    else setStatus('New tab ready');
    return tab;
  }

  function navigate(tab, raw) {
    const url = normalizeUrl(raw);
    if (!url) { setStatus('Enter a valid website or search phrase.'); return; }
    tab.isHome = false;
    tab.url = url;
    tab.title = 'Loading…';
    tab.view.classList.toggle('active', tab === activeTab);
    if (tab === activeTab) { showHome(false); address.value = url; loadingBar.classList.remove('done'); loadingBar.classList.add('loading'); }
    tab.view.src = url;
    renderTabs();
    setStatus('Opening ' + new URL(url).hostname + '…');
  }

  function closeTab(tab) {
    const index = tabs.indexOf(tab);
    if (index < 0) return;
    const wasActive = tab === activeTab;
    tabs.splice(index, 1);
    try { tab.view.stop(); } catch {}
    tab.view.remove();
    if (!tabs.length) { activeTab = null; createTab(''); return; }
    if (wasActive) selectTab(tabs[Math.max(0, index - 1)]);
    renderTabs();
  }

  function reloadTab() {
    if (!activeTab || activeTab.isHome) return;
    try { activeTab.view.reload(); } catch {}
  }

  function goBack() {
    if (!activeTab || activeTab.isHome) return;
    try { if (activeTab.view.canGoBack()) activeTab.view.goBack(); } catch {}
  }

  function goForward() {
    if (!activeTab || activeTab.isHome) return;
    try { if (activeTab.view.canGoForward()) activeTab.view.goForward(); } catch {}
  }

  function openAddress(raw = address.value) {
    if (!activeTab) createTab('');
    navigate(activeTab, raw);
  }

  function toggleEnhancePanel(open = !settingsPanel.classList.contains('open')) {
    settingsPanel.classList.toggle('open', open);
    panelScrim.classList.toggle('visible', open);
    settingsPanel.setAttribute('aria-hidden', open ? 'false' : 'true');
  }

  function updateTargetLabel() {
    const labels = { '4k':'4K', '5k':'5K', '8k':'8K UHD', '10k':'10K', '16k':'16K experimental target', '32k':'32K experimental target' };
    $('targetStatus').textContent = (labels[settings.target] || '4K') + ' selected';
    $('sharpnessValue').textContent = settings.sharpness + '%';
  }

  function applySettingsToTabs() {
    saveSettings();
    window.ultraApp.setAdBlockEnabled(settings.adBlock).catch(() => {});
    for (const tab of tabs) injectPageSettings(tab);
    $('adBlockLabel').textContent = settings.adBlock ? 'Ad block on' : 'Ad block off';
    $('adBlockButton').classList.toggle('off', !settings.adBlock);
    $('panelAdBlock').checked = settings.adBlock;
    $('enhanceStatus').textContent = settings.enhanceEnabled ? 'Picture enhancement enabled' : 'Picture enhancement disabled';
    updateTargetLabel();
  }

  function syncControls() {
    $('enhanceEnabled').checked = settings.enhanceEnabled;
    $('targetSelect').value = settings.target;
    $('sharpnessRange').value = String(settings.sharpness);
    $('smartEnhance').checked = settings.smart;
    $('adaptiveMode').checked = settings.adaptive;
    $('panelAdBlock').checked = settings.adBlock;
    applySettingsToTabs();
  }

  $('newTabButton').addEventListener('click', () => createTab(''));
  $('backButton').addEventListener('click', goBack);
  $('forwardButton').addEventListener('click', goForward);
  $('reloadButton').addEventListener('click', reloadTab);
  $('homeButton').addEventListener('click', () => {
    if (!activeTab) return;
    activeTab.isHome = true; activeTab.url = 'about:blank'; activeTab.title = 'New Tab';
    try { activeTab.view.loadURL('about:blank'); } catch {}
    selectTab(activeTab); setStatus('Home');
  });
  $('addressForm').addEventListener('submit', event => { event.preventDefault(); openAddress(); });
  $('homeSearchForm').addEventListener('submit', event => {
    event.preventDefault();
    const value = $('homeSearchInput').value;
    if (!activeTab) createTab('');
    openAddress(value);
  });
  $('settingsButton').addEventListener('click', () => toggleEnhancePanel(true));
  $('closePanelButton').addEventListener('click', () => toggleEnhancePanel(false));
  panelScrim.addEventListener('click', () => toggleEnhancePanel(false));
  $('adBlockButton').addEventListener('click', () => { settings.adBlock = !settings.adBlock; applySettingsToTabs(); });
  $('enhanceEnabled').addEventListener('change', event => { settings.enhanceEnabled = event.target.checked; applySettingsToTabs(); });
  $('targetSelect').addEventListener('change', event => { settings.target = event.target.value; applySettingsToTabs(); });
  $('sharpnessRange').addEventListener('input', event => { settings.sharpness = Number(event.target.value); updateTargetLabel(); applySettingsToTabs(); });
  $('smartEnhance').addEventListener('change', event => { settings.smart = event.target.checked; applySettingsToTabs(); });
  $('adaptiveMode').addEventListener('change', event => { settings.adaptive = event.target.checked; applySettingsToTabs(); });
  $('panelAdBlock').addEventListener('change', event => { settings.adBlock = event.target.checked; applySettingsToTabs(); });

  window.ultraApp.onNewTabRequested(url => createTab(url));
  window.ultraApp.onShortcut(shortcut => handleShortcut(shortcut));

  function handleShortcut(shortcut) {
    if (shortcut === 'new-tab') createTab('');
    else if (shortcut === 'close-tab' && activeTab) closeTab(activeTab);
    else if (shortcut === 'reload') reloadTab();
    else if (shortcut === 'focus-address') { address.focus(); address.select(); }
    else if (shortcut === 'next-tab' || shortcut === 'previous-tab') {
      if (tabs.length < 2) return;
      const current = tabs.indexOf(activeTab);
      const step = shortcut === 'next-tab' ? 1 : -1;
      selectTab(tabs[(current + step + tabs.length) % tabs.length]);
    }
  }

  document.addEventListener('keydown', event => {
    if (!event.ctrlKey || event.altKey) return;
    const key = event.key.toLowerCase();
    const shortcuts = { t: 'new-tab', w: 'close-tab', l: 'focus-address', r: 'reload' };
    if (key === 'tab') {
      event.preventDefault();
      handleShortcut(event.shiftKey ? 'previous-tab' : 'next-tab');
    } else if (shortcuts[key]) {
      event.preventDefault();
      handleShortcut(shortcuts[key]);
    }
  });

  window.ultraApp.getVersion().then(version => { $('versionText').textContent = 'Ultra Upscaler PRO · v' + version; }).catch(() => {});
  syncControls();
  createTab('');
  window.addEventListener('beforeunload', () => { if (loadTimer) clearTimeout(loadTimer); });
})();
