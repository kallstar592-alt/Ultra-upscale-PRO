const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('ultraApp', {
  setAdBlockEnabled: enabled => ipcRenderer.invoke('adblock:set-enabled', Boolean(enabled)),
  getVersion: () => ipcRenderer.invoke('app:get-version'),
  openExternal: url => ipcRenderer.invoke('browser:open-external', String(url)),
  onNewTabRequested: callback => {
    if (typeof callback !== 'function') return () => {};
    const listener = (_event, url) => callback(url);
    ipcRenderer.on('browser:new-tab', listener);
    return () => ipcRenderer.removeListener('browser:new-tab', listener);
  },
  onShortcut: callback => {
    if (typeof callback !== 'function') return () => {};
    const listener = (_event, shortcut) => callback(shortcut);
    ipcRenderer.on('browser:shortcut', listener);
    return () => ipcRenderer.removeListener('browser:shortcut', listener);
  }
});
