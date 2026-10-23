// Ultra Upscaler for Android: injected into every page by MainActivity.
// Ported from the Electron preload.js (shader + logic unchanged).
(function () {
if (window.__vqInstalled) { return; }
window.__vqInstalled = true;

// Targets request a render-buffer size; they cannot enlarge a site's encoded stream.
// Large real-time buffers are costly, so highResolution opts into a larger but still safe pixel budget.
const IS_MOBILE = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
const STANDARD_OUTPUT_PIXELS = IS_MOBILE ? 8294400 : 16777216;
const HIGH_OUTPUT_PIXELS = 33177600; // about 8K UHD (7680 × 4320), not 16K/32K
const LEVELS = [
  { name: '32K target', w: 32768, h: 18432 },
  { name: '16K target', w: 16384, h: 9216 },
  { name: '10K target', w: 10240, h: 5760 },
  { name: '8K UHD', w: 7680, h: 4320 },
  { name: '5K', w: 5120, h: 2880 },
  { name: '4K', w: 3840, h: 2160 }
];
// Pixel budgets prevent 16K/32K requests from allocating hundreds of MB on phones.
const DEFAULTS = {
  enabled: true, target: '4k', sharpness: 35,
  brightness: 100, contrast: 100, saturation: 100,
  autoQuality: true, adaptive: true, highResolution: false,
  screenModeEnabled: false, screenTarget: '4k',
  smart: false, smartStrength: 60
};

let S = { ...DEFAULTS };
let levelIdx = 3;
let current = null;
const blocked = new WeakSet();
let lastStatus = '';

const targetIdx = () => {
  const target = S.screenModeEnabled ? S.screenTarget : S.target;
  return ({ '32k': 0, '16k': 1, '10k': 2, '8k': 3, '5k': 4, '4k': 5 }[target] ?? 5);
};
function status(t) {
  if (t !== lastStatus) {
    lastStatus = t;
    try { if (window.AndroidVQ) window.AndroidVQ.status(t); } catch (_) {}
  }
}

// Screen Boost adds a subtle page-wide clarity profile. The requested resolution target
// applies to the video renderer only; webpage DOM and the Android display remain at their
// normal device-controlled raster size.
function applyScreenBoost() {
  let style = document.getElementById('__vq-screen-boost-style');
  if (!S.screenModeEnabled) {
    if (style) style.remove();
    return;
  }
  if (!style) {
    style = document.createElement('style');
    style.id = '__vq-screen-boost-style';
    (document.head || document.documentElement).appendChild(style);
  }
  const factors = { '4k': 1.02, '5k': 1.025, '8k': 1.03, '10k': 1.035, '16k': 1.04 };
  const factor = factors[S.screenTarget] || factors['4k'];
  style.textContent =
    'html { filter: contrast(' + factor + ') saturate(' + factor + ') !important; }' +
    'html, body { text-rendering: optimizeLegibility; -webkit-font-smoothing: antialiased; }' +
    'img, picture, svg, canvas, video { image-rendering: auto; }';
}

const VS = `
attribute vec2 p;
varying vec2 uv;
void main(){
  uv = vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
  gl_Position = vec4(p, 0.0, 1.0);
}`;

const VS_FBO = `
attribute vec2 p;
varying vec2 uv;
void main(){
  uv = p * 0.5 + 0.5;
  gl_Position = vec4(p, 0.0, 1.0);
}`;

// "Smart Enhance": edge-aware shock filter (Anime4K-style line refinement, no trained model).
// Finds edges with a Sobel filter, then pushes pixels on each side of an edge toward the
// neighbour on their own side, which thins/sharpens lines and cleans up soft edges.
const SMART_FS = `
precision highp float;
varying vec2 uv;
uniform sampler2D tex;
uniform vec2 texSize;
uniform float strength;
float lum(vec3 c){ return dot(c, vec3(0.299, 0.587, 0.114)); }
void main(){
  vec2 px = 1.0 / texSize;
  vec3 c = texture2D(tex, uv).rgb;
  float l00 = lum(texture2D(tex, uv + px * vec2(-1.0, -1.0)).rgb);
  float l10 = lum(texture2D(tex, uv + px * vec2( 0.0, -1.0)).rgb);
  float l20 = lum(texture2D(tex, uv + px * vec2( 1.0, -1.0)).rgb);
  float l01 = lum(texture2D(tex, uv + px * vec2(-1.0,  0.0)).rgb);
  float l21 = lum(texture2D(tex, uv + px * vec2( 1.0,  0.0)).rgb);
  float l02 = lum(texture2D(tex, uv + px * vec2(-1.0,  1.0)).rgb);
  float l12 = lum(texture2D(tex, uv + px * vec2( 0.0,  1.0)).rgb);
  float l22 = lum(texture2D(tex, uv + px * vec2( 1.0,  1.0)).rgb);
  float gx = (l20 + 2.0 * l21 + l22) - (l00 + 2.0 * l01 + l02);
  float gy = (l02 + 2.0 * l12 + l22) - (l00 + 2.0 * l10 + l20);
  vec2 g = vec2(gx, gy);
  float mag = length(g);
  float edge = smoothstep(0.03, 0.22, mag * 0.25);
  vec2 o = (g / (mag + 0.0001)) * px;
  vec3 a = texture2D(tex, uv + o).rgb;
  vec3 b = texture2D(tex, uv - o).rgb;
  float lc = lum(c);
  float lap = lum(a) + lum(b) - 2.0 * lc;
  vec3 target = lap > 0.0 ? b : a;
  float w = clamp(abs(lap) * 10.0, 0.0, 1.0) * edge * strength * 0.8;
  gl_FragColor = vec4(mix(c, target, w), 1.0);
}`;

const FS = `
precision highp float;
varying vec2 uv;
uniform sampler2D tex;
uniform vec2 texSize;
uniform float sharp;
uniform float bright;
uniform float contrast;
uniform float sat;

// 9-tap Catmull-Rom bicubic using hardware bilinear fetches
vec3 catmull(vec2 coord){
  vec2 samplePos = coord * texSize;
  vec2 t1 = floor(samplePos - 0.5) + 0.5;
  vec2 f = samplePos - t1;
  vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
  vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
  vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
  vec2 w3 = f * f * (-0.5 + 0.5 * f);
  vec2 w12 = w1 + w2;
  vec2 o12 = w2 / w12;
  vec2 t0 = (t1 - 1.0) / texSize;
  vec2 t3 = (t1 + 2.0) / texSize;
  vec2 t12 = (t1 + o12) / texSize;
  vec3 r = vec3(0.0);
  r += texture2D(tex, vec2(t0.x,  t0.y )).rgb * w0.x  * w0.y;
  r += texture2D(tex, vec2(t12.x, t0.y )).rgb * w12.x * w0.y;
  r += texture2D(tex, vec2(t3.x,  t0.y )).rgb * w3.x  * w0.y;
  r += texture2D(tex, vec2(t0.x,  t12.y)).rgb * w0.x  * w12.y;
  r += texture2D(tex, vec2(t12.x, t12.y)).rgb * w12.x * w12.y;
  r += texture2D(tex, vec2(t3.x,  t12.y)).rgb * w3.x  * w12.y;
  r += texture2D(tex, vec2(t0.x,  t3.y )).rgb * w0.x  * w3.y;
  r += texture2D(tex, vec2(t12.x, t3.y )).rgb * w12.x * w3.y;
  r += texture2D(tex, vec2(t3.x,  t3.y )).rgb * w3.x  * w3.y;
  return r;
}

void main(){
  vec3 c = catmull(uv);
  vec2 px = 1.0 / texSize;
  vec3 blur = (texture2D(tex, uv + vec2(px.x, 0.0)).rgb +
               texture2D(tex, uv - vec2(px.x, 0.0)).rgb +
               texture2D(tex, uv + vec2(0.0, px.y)).rgb +
               texture2D(tex, uv - vec2(0.0, px.y)).rgb) * 0.25;
  c += (c - blur) * sharp;
  c = (c - 0.5) * contrast + 0.5;
  c *= bright;
  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
  c = mix(vec3(l), c, sat);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}`;

function compile(gl, type, src) {
  const s = gl.createShader(type);
  gl.shaderSource(s, src);
  gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) || 'shader error');
  return s;
}

class Upscaler {
  constructor(video) {
    this.v = video;
    this.active = false;
    this.lastQ = null;
    this.parentFixed = null;
    this.canvas = document.createElement('canvas');
    this.canvas.setAttribute('data-vq', '1');
    this.canvas.style.cssText = 'position:absolute;pointer-events:none;margin:0;padding:0;border:0;';
    const gl = this.canvas.getContext('webgl', {
      alpha: false, antialias: false, powerPreference: 'high-performance', preserveDrawingBuffer: false
    });
    if (!gl) throw new Error('WebGL unavailable');
    this.gl = gl;

    const prog = gl.createProgram();
    gl.attachShader(prog, compile(gl, gl.VERTEX_SHADER, VS));
    gl.attachShader(prog, compile(gl, gl.FRAGMENT_SHADER, FS));
    gl.bindAttribLocation(prog, 0, 'p');
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error('shader link failed');
    gl.useProgram(prog);
    this.prog = prog;

    // Optional Smart Enhance pass (rendered offscreen at source resolution)
    this.smartOk = true;
    this.sw = 0; this.sh = 0;
    this.fbo = null; this.smartTex = null;
    try {
      const ps = gl.createProgram();
      gl.attachShader(ps, compile(gl, gl.VERTEX_SHADER, VS_FBO));
      gl.attachShader(ps, compile(gl, gl.FRAGMENT_SHADER, SMART_FS));
      gl.bindAttribLocation(ps, 0, 'p');
      gl.linkProgram(ps);
      if (!gl.getProgramParameter(ps, gl.LINK_STATUS)) throw new Error('smart link failed');
      this.progS = ps;
      this.us = {};
      for (const n of ['tex', 'texSize', 'strength']) this.us[n] = gl.getUniformLocation(ps, n);
    } catch (e) { this.smartOk = false; }
    gl.useProgram(prog);

    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

    this.tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, this.tex);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);

    this.u = {};
    for (const n of ['tex', 'texSize', 'sharp', 'bright', 'contrast', 'sat']) this.u[n] = gl.getUniformLocation(prog, n);

    this.canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); this.fail('GPU context lost'); });
  }

  start() {
    this.active = true;
    this.layout();
    this.timer = setInterval(() => this.housekeeping(), 250);
    this.perfTimer = setInterval(() => this.checkPerf(), 3000);
    this.loop();
  }

  stop() {
    this.active = false;
    clearInterval(this.timer);
    clearInterval(this.perfTimer);
    if (this.canvas.parentNode) this.canvas.parentNode.removeChild(this.canvas);
    if (this.parentFixed) { this.parentFixed.style.position = ''; this.parentFixed = null; }
    try {
      const ext = this.gl.getExtension('WEBGL_lose_context');
      if (ext) ext.loseContext();
    } catch (_) {}
  }

  fail(msg) {
    blocked.add(this.v);
    this.stop();
    if (current === this) current = null;
    status(msg);
  }

  loop() {
    if (!this.active) return;
    this.draw();
    const next = () => this.loop();
    if (this.v.requestVideoFrameCallback) this.v.requestVideoFrameCallback(next);
    else requestAnimationFrame(next);
  }

  housekeeping() {
    if (!this.active) return;
    if (!this.v.isConnected) return;
    this.layout();
    if (this.v.paused || this.v.readyState < 2) this.draw();
  }

  layout() {
    const v = this.v;
    const parent = v.parentElement;
    if (!parent) return;
    if (this.canvas.parentElement !== parent) {
      if (getComputedStyle(parent).position === 'static') {
        parent.style.position = 'relative';
        this.parentFixed = parent;
      }
      parent.insertBefore(this.canvas, v.nextSibling);
    }
    const vr = v.getBoundingClientRect();
    const pr = parent.getBoundingClientRect();
    const bw = vr.width, bh = vr.height;
    const vw = v.videoWidth || bw, vh = v.videoHeight || bh;
    let cw = bw, ch = bh;
    if (getComputedStyle(v).objectFit !== 'fill') {
      const sc = Math.min(bw / vw, bh / vh);
      cw = vw * sc; ch = vh * sc;
    }
    const z = getComputedStyle(v).zIndex;
    Object.assign(this.canvas.style, {
      left: (vr.left - pr.left - parent.clientLeft + parent.scrollLeft + (bw - cw) / 2) + 'px',
      top: (vr.top - pr.top - parent.clientTop + parent.scrollTop + (bh - ch) / 2) + 'px',
      width: cw + 'px',
      height: ch + 'px',
      zIndex: z === 'auto' ? '' : z
    });
  }

  sizeFor(vw, vh) {
    const L = LEVELS[levelIdx];
    const gl = this.gl;
    const maxTexture = gl.getParameter(gl.MAX_TEXTURE_SIZE) || 4096;
    const maxRenderbuffer = gl.getParameter(gl.MAX_RENDERBUFFER_SIZE) || maxTexture;
    const vp = gl.getParameter(gl.MAX_VIEWPORT_DIMS) || [maxTexture, maxTexture];
    // Viewport width and height may differ; avoid an unnecessarily low square cap.
    const maxWidth = Math.max(1, Math.min(maxTexture, maxRenderbuffer, vp[0]));
    const maxHeight = Math.max(1, Math.min(maxTexture, maxRenderbuffer, vp[1]));
    const requestedScale = Math.max(1, Math.min(L.w / vw, L.h / vh));
    let w = Math.max(1, Math.round(vw * requestedScale));
    let h = Math.max(1, Math.round(vh * requestedScale));
    const pixelBudget = (S.highResolution || S.screenModeEnabled) ? HIGH_OUTPUT_PIXELS : STANDARD_OUTPUT_PIXELS;
    const pixelScale = Math.sqrt(pixelBudget / Math.max(1, w * h));
    const hardwareScale = Math.min(1, maxWidth / w, maxHeight / h);
    const clampScale = Math.min(1, pixelScale, hardwareScale);
    this.outputCapped = clampScale < 0.999;
    const capReasons = [];
    if (pixelScale < 0.999) capReasons.push(Math.round(pixelBudget / 1000000) + ' MP budget');
    if (hardwareScale < 0.999) capReasons.push('GPU/WebGL ' + maxWidth + '×' + maxHeight);
    this.capReason = capReasons.join(' + ');
    if (this.outputCapped) {
      w = Math.max(1, Math.floor(w * clampScale));
      h = Math.max(1, Math.floor(h * clampScale));
    }
    if (this.canvas.width !== w || this.canvas.height !== h) {
      this.canvas.width = w;
      this.canvas.height = h;
    }
    this.renderWidth = w;
    this.renderHeight = h;
  }

  // Offscreen Smart Enhance pass: video texture -> smartTex (same size as the video).
  runSmart(w, h) {
    const gl = this.gl;
    try {
      if (!this.fbo || this.sw !== w || this.sh !== h) {
        if (!this.fbo) { this.fbo = gl.createFramebuffer(); this.smartTex = gl.createTexture(); }
        gl.bindTexture(gl.TEXTURE_2D, this.smartTex);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, this.smartTex, 0);
        if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
          this.smartOk = false;
          gl.bindFramebuffer(gl.FRAMEBUFFER, null);
          return false;
        }
        this.sw = w; this.sh = h;
      }
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.fbo);
      gl.viewport(0, 0, w, h);
      gl.useProgram(this.progS);
      gl.bindTexture(gl.TEXTURE_2D, this.tex);
      gl.uniform1i(this.us.tex, 0);
      gl.uniform2f(this.us.texSize, w, h);
      gl.uniform1f(this.us.strength, S.smartStrength / 100);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      return true;
    } catch (e) {
      this.smartOk = false;
      try { gl.bindFramebuffer(gl.FRAMEBUFFER, null); } catch (_) {}
      return false;
    }
  }

  draw() {
    const v = this.v, gl = this.gl;
    if (!this.active || v.readyState < 2 || !v.videoWidth) return;
    this.sizeFor(v.videoWidth, v.videoHeight);
    gl.bindTexture(gl.TEXTURE_2D, this.tex);
    try {
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, v);
    } catch (e) {
      this.fail('WebGL could not read this video frame (cross-origin/DRM or GPU format limit)');
      return;
    }
    let src = this.tex, smart = false;
    if (S.smart && this.smartOk && this.runSmart(v.videoWidth, v.videoHeight)) { src = this.smartTex; smart = true; }
    gl.useProgram(this.prog);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    gl.bindTexture(gl.TEXTURE_2D, src);
    gl.uniform1i(this.u.tex, 0);
    gl.uniform2f(this.u.texSize, v.videoWidth, v.videoHeight);
    gl.uniform1f(this.u.sharp, (S.sharpness / 100) * 1.5);
    gl.uniform1f(this.u.bright, S.brightness / 100);
    gl.uniform1f(this.u.contrast, S.contrast / 100);
    gl.uniform1f(this.u.sat, S.saturation / 100);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    const capNote = this.outputCapped ? ' · capped: ' + this.capReason : '';
    status(`Enhancing ${v.videoWidth}\u00d7${v.videoHeight} → buffer ${this.canvas.width}\u00d7${this.canvas.height} (${LEVELS[levelIdx].name}${capNote})` + (smart ? ' + Smart' : (S.smart && !this.smartOk ? ' (Smart unavailable)' : '')));
  }

  // Auto-tune: if the machine can't keep up, step the render size down.
  checkPerf() {
    const v = this.v;
    if (!S.adaptive || v.paused || v.readyState < 3 || !v.getVideoPlaybackQuality) { this.lastQ = null; return; }
    const q = v.getVideoPlaybackQuality();
    if (this.lastQ) {
      const dt = q.totalVideoFrames - this.lastQ.total;
      const dd = q.droppedVideoFrames - this.lastQ.dropped;
      if (dt > 30 && dd / dt > 0.08 && levelIdx < LEVELS.length - 1) levelIdx++;
    }
    this.lastQ = { total: q.totalVideoFrames, dropped: q.droppedVideoFrames };
  }
}

function pickVideo() {
  let best = null, area = 0;
  for (const v of document.querySelectorAll('video')) {
    if (blocked.has(v) || !v.videoWidth) continue;
    const r = v.getBoundingClientRect();
    if (r.width < 200 || r.height < 112) continue;
    if (r.width * r.height > area) { area = r.width * r.height; best = v; }
  }
  return best;
}

function tick() {
  if (!S.enabled) {
    if (current) { current.stop(); current = null; }
    status('Enhancement off');
    return;
  }
  const best = pickVideo();
  if (current && (!current.v.isConnected || (best && best !== current.v))) { current.stop(); current = null; }
  if (!current && best) {
    try { current = new Upscaler(best); current.start(); }
    catch (e) { blocked.add(best); current = null; status('GPU upscaling unavailable: ' + e.message); }
  }
  if (!current && !best) status('Waiting for a video…');
}


// YouTube: pick the highest quality level the video offers (4K / 8K when available).
function forceQuality() {
  if (!S.autoQuality || !/(^|\.)youtube\.com$/.test(location.hostname)) return;
  try {
    var p = document.getElementById('movie_player'); if (!p || !p.getAvailableQualityLevels) return;
    var id = p.getVideoData && p.getVideoData().video_id;
    var st = window.__vqs = window.__vqs || { id: null, n: 0 };
    if (id !== st.id) { st.id = id; st.n = 0; }
    if (st.n >= 6) return;
    var l = (p.getAvailableQualityLevels() || []).filter(function (x) { return x !== 'auto'; }); if (!l.length) return;
    var b = l[0];
    if (p.getPlaybackQuality && p.getPlaybackQuality() === b) { st.n = 6; return; }
    st.n++;
    if (p.setPlaybackQualityRange) p.setPlaybackQualityRange(b, b);
    if (p.setPlaybackQuality) p.setPlaybackQuality(b);
  } catch (e) {}
}

// Called from the native side whenever settings change.
window.__vqSet = function (s) {
  const prevTarget = S.target, prevEnabled = S.enabled, prevHighResolution = S.highResolution;
  const prevScreenMode = S.screenModeEnabled, prevScreenTarget = S.screenTarget;
  S = { ...DEFAULTS, ...s };
  if (S.target !== prevTarget || S.enabled !== prevEnabled ||
      S.highResolution !== prevHighResolution ||
      S.screenModeEnabled !== prevScreenMode || S.screenTarget !== prevScreenTarget) {
    levelIdx = targetIdx();
  }
  applyScreenBoost();
  tick();
};

levelIdx = targetIdx();
setInterval(tick, 1000);
setInterval(forceQuality, 1500);
})();
