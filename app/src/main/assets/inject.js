// Best-effort WebGL video rendering enhancement for Ultra Upscaler.
// The target is capped by real GPU limits and a conservative pixel budget; it does not
// transform the delivered source stream into native 16K/32K or recreate missing detail.
(function () {
  if (window.__uaEnhanceLoaded) return;
  window.__uaEnhanceLoaded = true;

  var targets = {
    "4k": 3840,
    "8k": 7680,
    "16k": 16384,
    "32k": 32768
  };
  var mobile = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
  var pixelBudget = mobile ? 8294400 : 16777216;
  var config = { enabled: true, target: "4k", sharpness: 35 };
  var tracked = new WeakMap();
  var lastStatus = "";
  var glVertex = "attribute vec2 p; varying vec2 uv; void main(){uv=p*0.5+0.5;gl_Position=vec4(p,0.0,1.0);}";
  var glFragment = "precision mediump float; uniform sampler2D frame; uniform vec2 stepSize; uniform float sharp; varying vec2 uv; void main(){vec4 c=texture2D(frame,uv);vec3 n=texture2D(frame,uv+vec2(0.0,stepSize.y)).rgb;vec3 s=texture2D(frame,uv-vec2(0.0,stepSize.y)).rgb;vec3 e=texture2D(frame,uv+vec2(stepSize.x,0.0)).rgb;vec3 w=texture2D(frame,uv-vec2(stepSize.x,0.0)).rgb;vec3 edge=c.rgb*4.0-n-s-e-w;gl_FragColor=vec4(clamp(c.rgb+edge*sharp,0.0,1.0),c.a);}";

  function report(message) {
    if (message === lastStatus) return;
    lastStatus = message;
    try { if (window.AndroidVQ) window.AndroidVQ.status(message); } catch (_) {}
  }

  function shader(gl, type, source) {
    var sh = gl.createShader(type);
    gl.shaderSource(sh, source);
    gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
      var message = gl.getShaderInfoLog(sh) || "Shader compilation failed";
      gl.deleteShader(sh);
      throw new Error(message);
    }
    return sh;
  }

  function createRenderer(video) {
    var canvas = document.createElement("canvas");
    canvas.setAttribute("aria-hidden", "true");
    canvas.style.cssText = "position:fixed;left:0;top:0;width:0;height:0;pointer-events:none;z-index:2147483646;background:#000;";
    (document.documentElement || document.body).appendChild(canvas);
    var gl = canvas.getContext("webgl", { alpha: false, antialias: false, depth: false, stencil: false, preserveDrawingBuffer: false });
    if (!gl) {
      canvas.remove();
      report("Enhance unavailable: WebGL not supported on this device");
      return null;
    }
    try {
      var program = gl.createProgram();
      gl.attachShader(program, shader(gl, gl.VERTEX_SHADER, glVertex));
      gl.attachShader(program, shader(gl, gl.FRAGMENT_SHADER, glFragment));
      gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program) || "Shader link failed");
      gl.useProgram(program);

      var buffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, 1,1]), gl.STATIC_DRAW);
      var position = gl.getAttribLocation(program, "p");
      gl.enableVertexAttribArray(position);
      gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);

      var texture = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.uniform1i(gl.getUniformLocation(program, "frame"), 0);
      return {
        video: video, canvas: canvas, gl: gl, program: program, texture: texture,
        stepLocation: gl.getUniformLocation(program, "stepSize"),
        sharpLocation: gl.getUniformLocation(program, "sharp"),
        outputKey: "", lost: false
      };
    } catch (error) {
      canvas.remove();
      report("Enhance renderer failed: " + String(error.message || error).slice(0, 100));
      return null;
    }
  }

  function sizeRenderer(renderer) {
    var video = renderer.video;
    var gl = renderer.gl;
    var width = Math.max(1, video.videoWidth || video.clientWidth || 1);
    var height = Math.max(1, video.videoHeight || video.clientHeight || 1);
    var aspect = width / height;
    var requestedWidth = targets[config.target] || targets["4k"];
    var requestedHeight = Math.max(1, Math.round(requestedWidth / aspect));
    var maxTexture = gl.getParameter(gl.MAX_TEXTURE_SIZE) || 2048;
    var maxRender = gl.getParameter(gl.MAX_RENDERBUFFER_SIZE) || maxTexture;
    var viewport = gl.getParameter(gl.MAX_VIEWPORT_DIMS) || [maxTexture, maxTexture];
    var maxWidth = Math.max(1, Math.min(maxTexture, maxRender, viewport[0]));
    var maxHeight = Math.max(1, Math.min(maxTexture, maxRender, viewport[1]));
    var scale = Math.min(1, maxWidth / requestedWidth, maxHeight / requestedHeight,
      Math.sqrt(pixelBudget / Math.max(1, requestedWidth * requestedHeight)));
    var outWidth = Math.max(2, Math.floor(requestedWidth * scale));
    var outHeight = Math.max(2, Math.floor(requestedHeight * scale));
    var key = outWidth + "x" + outHeight;
    if (renderer.outputKey !== key) {
      renderer.canvas.width = outWidth;
      renderer.canvas.height = outHeight;
      gl.viewport(0, 0, outWidth, outHeight);
      renderer.outputKey = key;
      report("Enhance render target: " + key + " · source " + width + "×" + height);
    }

    var rect = video.getBoundingClientRect();
    renderer.canvas.style.left = Math.round(rect.left) + "px";
    renderer.canvas.style.top = Math.round(rect.top) + "px";
    renderer.canvas.style.width = Math.max(0, rect.width) + "px";
    renderer.canvas.style.height = Math.max(0, rect.height) + "px";
    renderer.canvas.style.display = rect.width > 1 && rect.height > 1 ? "block" : "none";
  }

  function draw(renderer) {
    var video = renderer.video;
    var gl = renderer.gl;
    if (!config.enabled || video.readyState < 2 || video.videoWidth < 1 || video.videoHeight < 1 || video.paused && video.ended) {
      renderer.canvas.style.display = "none";
      return;
    }
    try {
      sizeRenderer(renderer);
      if (renderer.canvas.style.display === "none") return;
      gl.useProgram(renderer.program);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, renderer.texture);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, video);
      gl.uniform2f(renderer.stepLocation, 1 / renderer.canvas.width, 1 / renderer.canvas.height);
      gl.uniform1f(renderer.sharpLocation, Math.max(0, Math.min(100, config.sharpness)) / 100 * 0.65);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    } catch (_) {
      // Protected/cross-origin video surfaces can reject texture upload. Leave the source
      // visible and hide the failed overlay rather than disrupting playback.
      renderer.canvas.style.display = "none";
    }
  }

  function syncVideos() {
    var videos = document.querySelectorAll("video");
    for (var i = 0; i < videos.length; i++) {
      var video = videos[i];
      if (!tracked.has(video)) {
        var renderer = createRenderer(video);
        if (renderer) tracked.set(video, renderer);
      }
    }
  }

  window.__ultraApplyConfig = function (next) {
    config = Object.assign({}, config, next || {});
    if (!targets[config.target]) config.target = "4k";
    config.sharpness = Number(config.sharpness) || 0;
    syncVideos();
    report(config.enabled ? "Enhance enabled · target " + config.target.toUpperCase() : "Enhance disabled");
  };

  function tick() {
    try {
      syncVideos();
      var videos = document.querySelectorAll("video");
      for (var i = 0; i < videos.length; i++) {
        var renderer = tracked.get(videos[i]);
        if (renderer && !renderer.lost) draw(renderer);
      }
    } catch (_) {}
    window.requestAnimationFrame(tick);
  }

  if (window.MutationObserver && document.documentElement) {
    try {
      new MutationObserver(syncVideos).observe(document.documentElement, { childList: true, subtree: true });
    } catch (_) {}
  }
  window.__ultraApplyConfig(config);
  window.requestAnimationFrame(tick);
})();