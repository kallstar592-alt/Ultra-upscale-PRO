/*
 * Experimental on-device video super-resolution using Real-ESRGAN general x4 v3.
 * Model weights: https://huggingface.co/jonathanst29/tinier-upscale-models
 * Upstream Real-ESRGAN is BSD-3-Clause licensed. Video pixels are processed locally;
 * only the model/runtime files are fetched from their public CDNs.
 *
 * This is intentionally an experimental, throttled overlay: page security policies,
 * video CORS, model download availability, and device speed can limit the result.
 * The 4K–32K setting is a render target preference, not a guarantee of native 32K detail.
 */
(function () {
  const cfg = window.__ULTRA_AI_CONFIG || { enabled: false, target: '8K' };
  if (window.__ULTRA_AI_UPSCALER) {
    window.__ULTRA_AI_UPSCALER.configure(cfg);
    return;
  }

  const ORT_URL = 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.22.0/dist/ort.min.js';
  const WASM_DIR = 'https://cdn.jsdelivr.net/npm/onnxruntime-web@1.22.0/dist/';
  const MODEL_URL = 'https://huggingface.co/jonathanst29/tinier-upscale-models/resolve/main/realesr-general-x4v3.onnx?download=true';
  const state = { enabled: false, target: '8K', running: false, busy: false, session: null, sessionPromise: null, timer: null, video: null, overlay: null, errorShown: false };
  let sourceCanvas = null, outputCtx = null, sourceCtx = null;

  function badge(message, bad) {
    let el = document.getElementById('__ultra_ai_status');
    if (!el) {
      el = document.createElement('div'); el.id = '__ultra_ai_status';
      el.style.cssText = 'position:fixed;right:10px;bottom:10px;z-index:2147483647;padding:6px 9px;border-radius:8px;background:rgba(12,14,20,.88);color:#fff;font:12px system-ui,sans-serif;pointer-events:none;box-shadow:0 2px 10px #0005;';
      (document.body || document.documentElement).appendChild(el);
    }
    el.textContent = '✦ AI Upscaler · ' + message;
    el.style.border = bad ? '1px solid #d85c5c' : '1px solid #765cff';
    el.style.display = state.enabled ? 'block' : 'none';
  }

  function loadOrt() {
    if (window.ort) return Promise.resolve(window.ort);
    return new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.async = true; script.src = ORT_URL;
      script.onload = () => window.ort ? resolve(window.ort) : reject(new Error('ONNX runtime did not initialize'));
      script.onerror = () => reject(new Error('AI runtime blocked by the page or network'));
      (document.head || document.documentElement).appendChild(script);
    });
  }

  async function getSession() {
    if (state.session) return state.session;
    if (state.sessionPromise) return state.sessionPromise;
    state.sessionPromise = (async () => {
      const ort = await loadOrt();
      ort.env.wasm.wasmPaths = WASM_DIR;
      ort.env.wasm.numThreads = 1;
      const options = { graphOptimizationLevel: 'all' };
      if (navigator.gpu) {
        try { options.executionProviders = ['webgpu', 'wasm']; return await ort.InferenceSession.create(MODEL_URL, options); }
        catch (_) { /* fall back to portable WASM below */ }
      }
      options.executionProviders = ['wasm'];
      return await ort.InferenceSession.create(MODEL_URL, options);
    })();
    try { state.session = await state.sessionPromise; return state.session; }
    finally { state.sessionPromise = null; }
  }

  function findVideo() {
    const videos = Array.from(document.querySelectorAll('video'))
      .filter(v => v.readyState >= 2 && v.videoWidth > 0 && v.videoHeight > 0 && !v.paused && !v.ended);
    videos.sort((a,b) => (b.clientWidth*b.clientHeight) - (a.clientWidth*a.clientHeight));
    return videos[0] || null;
  }

  function ensureCanvases() {
    if (!sourceCanvas) {
      sourceCanvas = document.createElement('canvas');
      sourceCtx = sourceCanvas.getContext('2d', { willReadFrequently: true });
    }
    if (!state.overlay) {
      state.overlay = document.createElement('canvas');
      state.overlay.id = '__ultra_ai_frame';
      state.overlay.style.cssText = 'position:fixed;display:none;pointer-events:none;z-index:2147483645;opacity:.96;object-fit:fill;';
      (document.body || document.documentElement).appendChild(state.overlay);
      outputCtx = state.overlay.getContext('2d', { alpha: false });
    }
  }

  function alignOverlay(video) {
    const r = video.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) { state.overlay.style.display = 'none'; return false; }
    state.overlay.style.left = r.left + 'px';
    state.overlay.style.top = r.top + 'px';
    state.overlay.style.width = r.width + 'px';
    state.overlay.style.height = r.height + 'px';
    state.overlay.style.display = 'block';
    return true;
  }

  async function processFrame(video) {
    // Cap model input size so phone RAM and GPU memory stay bounded. Real-ESRGAN
    // produces a 4x frame; the browser then fits that result over the video viewport.
    const cap = navigator.gpu ? 512 : 384;
    const scale = Math.min(1, cap / video.videoWidth);
    const w = Math.max(32, Math.floor((video.videoWidth * scale) / 8) * 8);
    const h = Math.max(32, Math.floor((video.videoHeight * (w / video.videoWidth)) / 8) * 8);
    sourceCanvas.width = w; sourceCanvas.height = h;
    sourceCtx.drawImage(video, 0, 0, w, h);
    let pixels;
    try { pixels = sourceCtx.getImageData(0, 0, w, h).data; }
    catch (_) { throw new Error('This site blocks video-frame access (CORS/protected playback)'); }

    const ort = window.ort;
    const tensorData = new Float32Array(3 * w * h);
    const plane = w * h;
    for (let i = 0, p = 0; i < plane; i++, p += 4) {
      tensorData[i] = pixels[p] / 255;
      tensorData[plane + i] = pixels[p + 1] / 255;
      tensorData[2 * plane + i] = pixels[p + 2] / 255;
    }
    const session = state.session;
    const feeds = {};
    feeds[session.inputNames[0]] = new ort.Tensor('float32', tensorData, [1, 3, h, w]);
    const results = await session.run(feeds);
    const out = results[session.outputNames[0]];
    const oh = out.dims[2], ow = out.dims[3], outPlane = oh * ow;
    if (state.overlay.width !== ow || state.overlay.height !== oh) {
      state.overlay.width = ow; state.overlay.height = oh;
      outputCtx = state.overlay.getContext('2d', { alpha: false });
    }
    const image = outputCtx.createImageData(ow, oh);
    for (let i = 0, p = 0; i < outPlane; i++, p += 4) {
      image.data[p] = Math.max(0, Math.min(255, out.data[i] * 255));
      image.data[p + 1] = Math.max(0, Math.min(255, out.data[outPlane + i] * 255));
      image.data[p + 2] = Math.max(0, Math.min(255, out.data[2 * outPlane + i] * 255));
      image.data[p + 3] = 255;
    }
    outputCtx.putImageData(image, 0, 0);
    alignOverlay(video);
  }

  async function tick() {
    if (!state.enabled || state.busy) return;
    state.busy = true;
    try {
      const video = findVideo();
      if (!video) {
        if (state.overlay) state.overlay.style.display = 'none';
        badge('waiting for playable video…', false);
      } else {
        state.video = video;
        ensureCanvases();
        badge('loading model (first use downloads ~5 MB)…', false);
        await getSession();
        if (!state.enabled) return;
        badge('running x4 model · ' + state.target + ' render target', false);
        await processFrame(video);
        state.errorShown = false;
      }
    } catch (e) {
      if (state.overlay) state.overlay.style.display = 'none';
      const message = (e && e.message) ? e.message : 'model unavailable';
      badge(message, true);
      state.errorShown = true;
    } finally {
      state.busy = false;
      if (state.enabled) state.timer = setTimeout(tick, 120);
    }
  }

  function stop() {
    state.running = false;
    if (state.timer) clearTimeout(state.timer);
    state.timer = null; state.busy = false;
    if (state.overlay) state.overlay.style.display = 'none';
    const el = document.getElementById('__ultra_ai_status');
    if (el) el.style.display = 'none';
  }
  function start() {
    if (state.running) return;
    state.running = true;
    badge('starting…', false);
    tick();
  }
  const api = {
    configure(next) {
      const enabled = !!(next && next.enabled);
      state.target = (next && next.target) || '8K';
      if (enabled === state.enabled) {
        if (enabled) badge(state.session ? 'running x4 model · ' + state.target + ' render target' : 'loading…', false);
        return;
      }
      state.enabled = enabled;
      if (enabled) start(); else stop();
    }
  };
  window.__ULTRA_AI_UPSCALER = api;
  api.configure(cfg);
})();