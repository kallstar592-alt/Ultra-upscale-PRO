# Ultra-upscale-PRO — Enhanced Android Browser

This repository contains a reconstructed, editable Android Studio project based on the compiled APK supplied for this task. The original APK did not include its original GitHub source, so this project is a new WebView browser shell plus a port of the available enhancement script—not a byte-for-byte restoration of the original source.

## Added features

- **Browser tabs:** open, switch, and close independent browser tabs, each with its own WebView and navigation history. User-initiated pop-up windows can open as tabs.
- **Enhance panel:** settings for video enhancement, sharpness, smart edge enhancement, adaptive load reduction, YouTube source-quality preference, and selectable 4K, 5K, 8K, 10K, 16K-target, and 32K-target modes.
- **Experimental 16K/32K targets:** requested output dimensions are limited by WebGL/device capability and a conservative pixel budget. These options do not create native 16K/32K source video or guarantee new detail that is absent from the source.
- **Ad/tracker blocking:** basic request interception for common ad/tracker domains plus cosmetic hiding of common ad containers. This is not a full EasyList/uBlock-style filter engine; some ads may remain and some embedded content can be affected.
- **Video support:** WebView media playback and full-screen custom-view handling.

## Open and build

1. Install [Android Studio](https://developer.android.com/studio), JDK 17, and Android SDK Platform 35.
2. Clone or download this repository and open the repository folder in Android Studio.
3. Let Gradle sync and install any SDK components Android Studio requests.
4. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. If successful, the debug APK is normally at `app/build/outputs/apk/debug/app-debug.apk`.

The environment that prepared these source files did not contain the Android SDK/Gradle build toolchain, so an APK could not be compiled or runtime-tested here. Device testing is still required before release.

## Main files

- `app/src/main/java/com/ultraupscaler/app/MainActivity.java` — browser UI, tabs, navigation, Enhance settings, request filtering, and JavaScript bridge.
- `app/src/main/assets/inject.js` — WebGL-based video enhancement renderer and best-effort resolution targets.
- `app/src/main/assets/adblock.js` — cosmetic page-element ad hiding.
- `app/src/main/AndroidManifest.xml` — Android app entry point and permissions.

## Important limitations

- Web video sites may restrict embedding, autoplay, or playback within a WebView.
- A selected upscaling target is not a guarantee that the rendered video or delivered stream reaches that resolution. Actual rendering is hardware, memory, WebView, and source-video dependent.
- The ad blocker is intentionally lightweight and should not be described as complete protection from ads, trackers, or malicious content.
