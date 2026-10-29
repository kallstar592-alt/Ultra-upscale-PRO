# Ultra-upscale-PRO — Enhanced Android Browser

This is an editable Android Studio project reconstructed from the compiled APK supplied for this task. The source ZIP did not include the original app source, so this project is a new native WebView browser shell plus a video-rendering enhancement script, not a byte-for-byte restoration of the original app.

## Features

- **Mobile-first compact layout:** open/switch/close tabs stay visible; browser navigation and all enhancement controls are hidden in an eye-button dropdown to maximize page space.
- **Legacy Enhance panel preserved:** browser tabs, address/navigation bar, ad blocker, Smart edge enhancement, adaptive render load, and target selection stay in the original layout.
- **AI Upscaler (experimental):** optional Real-ESRGAN x4 ONNX mode is added to the existing Enhance dialog. While AI mode is on, the standard WebGL overlay is paused to prevent stacked video layers.
- **Video tuning:** sharpness, brightness, contrast, and saturation controls share the same dark themed settings dialog. Resolution options remain readable on the dark background.
- **Adaptive video rendering:** WebGL renderer checks hardware limits and a conservative pixel budget before sizing its canvas.
- **Basic ad blocking:** blocks requests matching a list of common advertising/tracking domains and hides common ad containers.
- **DuckDuckGo:** starts new tabs on DuckDuckGo and sends non-URL text searches to DuckDuckGo.

## Build

1. Install [Android Studio](https://developer.android.com/studio), JDK 17, and Android SDK Platform 35.
2. Clone or download this repository and open the repository folder in Android Studio.
3. Allow Gradle sync and install any requested SDK components.
4. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. If the build succeeds, the debug APK is normally under `app/build/outputs/apk/debug/app-debug.apk`.

The `AI Upscaler` downloads its model and ONNX Runtime files from public CDNs on first use. Video processing is attempted locally, but model downloads require internet. CORS/DRM, site protections, lack of WebGPU, or device memory/performance can prevent AI upscaling. The input is intentionally downscaled before inference to limit memory use; the 4K–32K labels are target preferences, not true native output sizes.

## Important limits

A 16K/32K setting is a best-effort render target, not a promise of native 16K/32K source video or playback. The renderer is capped by GPU, WebView, memory, viewport, and pixel-budget limits, and it cannot invent detail missing from the source. Video sites can also restrict playback or behavior inside WebView. The ad blocker is intentionally lightweight, not a full EasyList/uBlock filter engine, so some ads may remain and some ad-supported embeds may be affected.

## Main source files

- `app/src/main/java/com/ultraupscaler/app/MainActivity.java` — browser UI, tabs, navigation, Enhance settings, network filtering, and JS bridge.
- `app/src/main/assets/inject.js` — WebGL video rendering and GPU-safe resolution target caps.
- `app/src/main/assets/ai-upscaler.js` — experimental on-device x4 super-resolution mode using ONNX Runtime Web.
- `app/src/main/assets/adblock.js` — cosmetic ad-element hiding.
- `app/src/main/AndroidManifest.xml` — app entry point and permissions.
