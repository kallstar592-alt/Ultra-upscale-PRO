const { app, BrowserWindow, ipcMain, session, shell } = require('electron');
const path = require('node:path');

let mainWindow;
let adBlockEnabled = true;

const blockedDomains = [
  'doubleclick.net', 'googlesyndication.com', 'googleadservices.com',
  'adservice.google.com', 'adnxs.com', 'taboola.com', 'outbrain.com',
  'scorecardresearch.com', 'adroll.com', 'criteo.com', 'rubiconproject.com',
  'pubmatic.com', 'openx.net', 'adsrvr.org', 'adform.net',
  'amazon-adsystem.com', 'serving-sys.com', 'moatads.com',
  'adcolony.com', 'unityads.unity3d.com'
];

function shouldBlock(urlText) {
  try {
    const url = new URL(urlText);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return false;
    const host = url.hostname.toLowerCase();
    return blockedDomains.some(domain => host === domain || host.endsWith('.' + domain));
  } catch {
    return false;
  }
}

function configureRequestBlocking() {
  // The browser views use this persistent partition, so filtering is applied to their requests.
  const browserSession = session.fromPartition('persist:ultra-upscaler-browser');
  browserSession.webRequest.onBeforeRequest(
    { urls: ['http://*/*', 'https://*/*'] },
    (details, callback) => callback({ cancel: adBlockEnabled && shouldBlock(details.url) })
  );
}

function isWebUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 940,
    minWidth: 900,
    minHeight: 620,
    backgroundColor: '#0c0c13',
    title: 'Ultra Upscaler PRO',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webviewTag: true
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'index.html'));
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (isWebUrl(url)) shell.openExternal(url).catch(() => {});
    return { action: 'deny' };
  });
  mainWindow.on('closed', () => { mainWindow = null; });
}

app.on('web-contents-created', (_event, contents) => {
  if (contents.getType() !== 'webview') return;

  contents.setWindowOpenHandler(({ url }) => {
    if (mainWindow && !mainWindow.isDestroyed() && isWebUrl(url)) {
      mainWindow.webContents.send('browser:new-tab', url);
    }
    return { action: 'deny' };
  });

  contents.on('will-navigate', (event, url) => {
    if (!isWebUrl(url) && url !== 'about:blank') event.preventDefault();
  });

  contents.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown' || !input.control || input.alt) return;
    const key = String(input.key || '').toLowerCase();
    const shortcuts = {
      t: 'new-tab',
      w: 'close-tab',
      l: 'focus-address',
      r: 'reload',
      tab: input.shift ? 'previous-tab' : 'next-tab'
    };
    if (shortcuts[key] && mainWindow && !mainWindow.isDestroyed()) {
      event.preventDefault();
      mainWindow.webContents.send('browser:shortcut', shortcuts[key]);
    }
  });
});

ipcMain.handle('adblock:set-enabled', (_event, value) => {
  adBlockEnabled = Boolean(value);
  return adBlockEnabled;
});

ipcMain.handle('browser:open-external', async (_event, value) => {
  if (!isWebUrl(String(value))) return false;
  await shell.openExternal(String(value));
  return true;
});

ipcMain.handle('app:get-version', () => app.getVersion());

app.whenReady().then(() => {
  configureRequestBlocking();
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
