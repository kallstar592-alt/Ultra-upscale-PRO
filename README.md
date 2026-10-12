# Ultra Upscaler — enhanced Android source project

This is an editable Android Studio/Gradle project prepared from the APK supplied in chat. The uploaded ZIP contained only `app-debug.apk` (compiled bytecode and assets), not the original GitHub repository or its original source files. As a result, this is a reconstructed project using the visible enhancement script and a new Android WebView browser shell; it is not a byte-for-byte restoration of the original source project.

## Features in this project

- **Real tab bar:** each tab is a separate WebView with independent navigation history, URL/search bar, back/forward/reload, close-tab controls, and a new-tab button.
- **Open links in new tabs:** user-initiated `target="_blank"` / `window.open()` popups are handled as tabs. Popups without a user gesture are rejected to reduce ad-created tabs.
- **Enhance panel:** controls for video enhancement, 4K, 5K, 8K, 10K, experimental 16K/32K targets, smart edge enhancement, YouTube source-quality preference, adaptive load reduction, and sharpness.
- **GPU safety caps:** the requested 16K/32K targets are capped to the WebGL renderbuffer/texture/viewport and a conservative pixel budget. Actual rendered dimensions are sent to the status line. These settings do not turn a site's source stream into native 16K/32K video or reconstruct missing detail.
- **Built-in ad blocking:** network interception blocks a bundled list of common advertising/tracking domains, and an injected stylesheet hides some common ad containers. This is a lightweight built-in blocker, not a full EasyList/uBlock Origin filter engine; some ads may remain and filters can occasionally block embedded ad-supported content.
- **Video fullscreen support** and media autoplay configuration for web videos.

## Build in Android Studio

1. Install Android Studio with a JDK 17 and Android SDK Platform 35.
2. Open this folder (`UltraUpscaler_Enhanced`) as an existing project.
3. Let Gradle sync, then select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. The output APK is normally under `app/build/outputs/apk/debug/`.

The source targets Android 6.0+ (API 23) and compile SDK 35. This environment did not include the Android SDK/Gradle build toolchain, so an APK could not be built or runtime-tested here. The JavaScript enhancement assets and manifest were checked, but device testing is still required.

## GitHub

Upload the contents of this folder to the repository as source. Do not upload only the generated APK if you want to edit the features again. If you already have a GitHub repository with original source, compare/merge `MainActivity.java`, `inject.js`, `adblock.js`, the manifest, and Gradle configuration with that repository rather than replacing unrelated project files.

## Main files

- `app/src/main/java/com/ultraupscaler/app/MainActivity.java` — tabs, browser UI, settings, ad-block interception and JS bridge.
- `app/src/main/assets/inject.js` — video upscaling and enhancement renderer, including 16K/32K best-effort targets with hardware/memory limits.
- `app/src/main/assets/adblock.js` — cosmetic ad-element hiding.
- `app/src/main/AndroidManifest.xml` — Android entry point and Internet permission.
