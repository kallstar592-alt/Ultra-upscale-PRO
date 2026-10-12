# Ultra-upscale-PRO — Enhanced Android Browser

This is an editable Android Studio project reconstructed from the compiled APK supplied for this task. The source ZIP did not include the original app source, so this project is a new native WebView browser shell plus a video-rendering enhancement script, not a byte-for-byte restoration of the original app.

## Features

- **Browser-style tabs:** open, switch between, and close tabs. Each tab has its own WebView and navigation history. User-initiated pop-up windows can open as tabs.
- **Enhance panel:** enable or disable video enhancement, adjust sharpness, and choose 4K, 8K, experimental 16K, or experimental 32K render targets.
- **Adaptive video rendering:** WebGL renderer checks hardware limits and a conservative pixel budget before sizing its canvas.
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

A 16K/32K setting is a best-effort render target, not a promise of native 16K/32K source video or playback. The renderer is capped by GPU, WebView, memory, viewport, and pixel-budget limits, and it cannot invent detail missing from the source. Video sites can also restrict playback or behavior inside WebView. The ad blocker is intentionally lightweight, not a full EasyList/uBlock filter engine, so some ads may remain and some ad-supported embeds may be affected.

## Main source files

- `app/src/main/java/com/ultraupscaler/app/MainActivity.java` — browser UI, tabs, navigation, Enhance settings, network filtering, and JS bridge.
- `app/src/main/assets/inject.js` — WebGL video rendering and resolution target caps.
- `app/src/main/assets/adblock.js` — cosmetic ad-element hiding.
- `app/src/main/AndroidManifest.xml` — app entry point and permissions.
