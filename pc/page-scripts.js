// Functions are serialized by the trusted shell and executed inside each web page.
window.UltraPageScripts = {
  adblock: function(enabled) {
    var styleId = '__ultra_upscaler_adblock_css';
    var existing = document.getElementById(styleId);
    if (!enabled) { if (existing) existing.remove(); return; }
    var css = [
      '.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],',
      '[class*=" ad-slot"],[class^="ad-container"],[class*=" ad-container"],',
      '[class*="advertisement"],[id*="ad_banner"],[id*="adBanner"],',
      '[class*="sponsored-content"],[data-ad-slot],',
      'iframe[src*="doubleclick.net"],iframe[src*="googlesyndication.com"],',
      'iframe[src*="taboola.com"],iframe[src*="outbrain.com"]',
      '{display:none!important;visibility:hidden!important;width:0!important;height:0!important}'
    ].join('\n');
    function install() {
      if (!document.documentElement || document.getElementById(styleId)) return;
      var style = document.createElement('style');
      style.id = styleId;
      style.textContent = css;
      (document.head || document.documentElement).appendChild(style);
    }
    install();
    if (!window.__ultraAdObserver && window.MutationObserver && document.documentElement) {
      window.__ultraAdObserver = new MutationObserver(install);
      window.__ultraAdObserver.observe(document.documentElement, { childList: true, subtree: true });
    }
  },

  enhance: function(config) {
    config = config || {};
    if (window.__ultraUpscalerApply) {
      window.__ultraUpscalerApply(config);
      return;
    }
    var settings = { enabled: true, target: '4k', sharpness: 35, smart: false, adaptive: true };
    Object.assign(settings, config);
    var filterId = '__ultra_upscaler_sharpen';
    var lastFrames = new WeakMap();
    var performanceScale = 1;
    var lastDropTime = 0;

    function ensureFilter() {
      if (!document.documentElement || document.getElementById(filterId)) return;
      var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('width', '0'); svg.setAttribute('height', '0');
      svg.style.position = 'absolute'; svg.style.pointerEvents = 'none'; svg.setAttribute('aria-hidden', 'true');
      var defs = document.createElementNS('http://www.w3.org/2000/svg', 'defs');
      var filter = document.createElementNS('http://www.w3.org/2000/svg', 'filter');
      filter.setAttribute('id', filterId); filter.setAttribute('x', '-10%'); filter.setAttribute('y', '-10%');
      filter.setAttribute('width', '120%'); filter.setAttribute('height', '120%');
      var conv = document.createElementNS('http://www.w3.org/2000/svg', 'feConvolveMatrix');
      conv.setAttribute('id', filterId + '_kernel'); conv.setAttribute('order', '3');
      conv.setAttribute('preserveAlpha', 'true'); conv.setAttribute('divisor', '1');
      filter.appendChild(conv); defs.appendChild(filter); svg.appendChild(defs);
      (document.body || document.documentElement).appendChild(svg);
    }
    function kernelMatrix() {
      var amount = Math.max(0, Math.min(100, Number(settings.sharpness) || 0)) / 100 * 0.20 * performanceScale;
      return '0 ' + (-amount) + ' 0 ' + (-amount) + ' ' + (1 + 4 * amount) + ' ' + (-amount) + ' 0 ' + (-amount) + ' 0';
    }
    function tune(video) {
      if (!video || !video.style) return;
      if (!settings.enabled) {
        video.style.removeProperty('filter');
        video.removeAttribute('data-ultra-enhanced');
        return;
      }
      var sharp = Math.max(0, Math.min(100, Number(settings.sharpness) || 0)) * performanceScale;
      var contrast = 1 + sharp / 1100;
      var saturation = 1 + Math.min(0.07, sharp / 1800);
      var filter = 'contrast(' + contrast.toFixed(3) + ') saturate(' + saturation.toFixed(3) + ')';
      if (settings.smart && sharp > 0) filter += ' url(#' + filterId + ')';
      video.style.setProperty('filter', filter, 'important');
      video.style.setProperty('max-width', '100%');
      video.setAttribute('data-ultra-enhanced', 'true');
    }
    function applyAll() {
      ensureFilter();
      var kernel = document.getElementById(filterId + '_kernel');
      if (kernel) kernel.setAttribute('kernelMatrix', kernelMatrix());
      document.querySelectorAll('video').forEach(tune);
    }
    function scan() {
      if (settings.enabled) applyAll();
    }
    window.__ultraUpscalerApply = function(next) {
      Object.assign(settings, next || {});
      performanceScale = 1;
      applyAll();
    };
    applyAll();
    if (window.MutationObserver && document.documentElement) {
      try {
        new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true });
      } catch (_) {}
    }
    if (settings.adaptive && typeof setInterval === 'function') {
      setInterval(function() {
        var now = Date.now();
        document.querySelectorAll('video').forEach(function(video) {
          if (typeof video.getVideoPlaybackQuality !== 'function') return;
          var q = video.getVideoPlaybackQuality();
          var prev = lastFrames.get(video);
          if (prev) {
            var drops = q.droppedVideoFrames - prev.dropped;
            var frames = q.totalVideoFrames - prev.total;
            if (frames > 0 && drops / frames > 0.12) {
              performanceScale = 0.55; lastDropTime = now;
            }
          }
          lastFrames.set(video, { dropped: q.droppedVideoFrames, total: q.totalVideoFrames });
        });
        if (performanceScale < 1 && now - lastDropTime > 7000) performanceScale = 1;
        applyAll();
      }, 2500);
    }
  }
};
