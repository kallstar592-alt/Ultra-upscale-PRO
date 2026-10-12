// Lightweight cosmetic ad blocking. Network-level domain filtering is implemented in MainActivity.
(function () {
  if (window.__uaCosmeticBlockerInstalled) return;
  window.__uaCosmeticBlockerInstalled = true;

  var css = [
    '.adsbygoogle, [id^="google_ads"], [id^="div-gpt-ad"], [class*=" ad-slot"],',
    '[class^="ad-container"], [class*=" ad-container"], [class*="advertisement"],',
    '[id*="ad_banner"], [id*="adBanner"], [class*="sponsored-content"],',
    'iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"],',
    'iframe[src*="adnxs.com"], iframe[src*="taboola.com"], iframe[src*="outbrain.com"]',
    '{ display:none !important; visibility:hidden !important; width:0 !important; height:0 !important; }'
  ].join('\n');

  function installStyle() {
    if (!document.documentElement || document.getElementById('__ua_adblock_style')) return;
    var style = document.createElement('style');
    style.id = '__ua_adblock_style';
    style.textContent = css;
    (document.head || document.documentElement).appendChild(style);
  }

  installStyle();
  if (document.documentElement && window.MutationObserver) {
    try {
      new MutationObserver(installStyle).observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
  }
})();
