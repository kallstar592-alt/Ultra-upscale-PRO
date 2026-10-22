# Ultra-upscale-PRO — Enhanced Android Browser

This is an editable Android Studio project reconstructed from the compiled APK supplied for this task. The source ZIP did not include the original app source, so this project is a new native WebView browser shell plus a video-rendering enhancement script, not a byte-for-byte restoration of the original app.

## Features

- **Browser-style tabs:** open, switch between, and close tabs. Each tab has its own WebView and navigation history. User-initiated pop-up windows can open as tabs.
- **Enhance panel:** enable or disable enhancement, adjust sharpness and edge-aware Smart Enhance strength, and choose 4K, 5K, 8K, 10K, experimental 16K, or experimental 32K targets.
- **High-resolution mode:** optionally raises the render-buffer budget toward 8K-class (about 33 megapixels) on supported GPUs.
- **Adaptive video rendering:** WebGL renderer checks separate GPU viewport dimensions and a pixel budget before sizing its canvas; the status reports actual buffer dimensions and cap reasons.
- **Basic ad blocking:** blocks requests matching a list of common advertising/tracking domains and hides common ad containers.
- **Browser navigation:** address/search bar, back, forward, reload, and external app hand-off for non-web links.

## Build

1. Install [Android Studio](https://developer.android.com/studio), JDK 17, and Android SDK Platform 35.
2. Clone or download this repository and open the repository folder in Android Studio.
3. Allow Gradle sync and install any requested SDK components.
4. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. If the build succeeds, the debug APK is normally under `app/build/outputs/apk/debug/app-debug.apk`.

This environment does not have the Android SDK/Gradle build toolchain, so I could not compile or runtime-test the APK. Build and test on a device before relying on it.

## Important limits

A 16K/32K setting is a requested render target, not native 16K/32K source video. High-resolution mode raises the budget to about 33 million pixels (8K-class) where supported. A 16:9 16K frame is about 151 million pixels and 32K about 604 million pixels, requiring roughly 0.6 GB and 2.4 GB respectively for one uncompressed 4-byte RGBA buffer alone; attempting to force that on phones is unsafe. GPU/WebGL dimension limits and a memory-safety budget remain in effect, and the renderer now reports actual buffer dimensions and cap reasons. Upscaling and edge enhancement can smooth or sharpen pixels but cannot recover factual detail that is absent from the source. Cross-origin and DRM restrictions can prevent access to some frames; this app does not bypass those restrictions. The ad blocker is intentionally lightweight, not a full EasyList/uBlock filter engine, so some ads may remain and some ad-supported embeds may be affected.

## Main source files

- `app/src/main/java/com/ultraupscaler/app/MainActivity.java` — browser UI, tabs, navigation, Enhance settings, network filtering, and JS bridge.
- `app/src/main/assets/inject.js` — WebGL video rendering and resolution target caps.
- `app/src/main/assets/adblock.js` — cosmetic ad-element hiding.
- `app/src/main/AndroidManifest.xml` — app entry point and permissions.
