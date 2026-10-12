# Ultra-upscale-PRO — Enhanced Android browser

This repository contains an editable Android Studio project. The source files were initially uploaded into the repository root; this branch organizes them into the Android Gradle layout so the project can be opened and built normally.

## Features

- **Browser tabs:** create, switch between and close independent WebView tabs. User-initiated pop-up windows can open in new tabs.
- **Enhance panel:** 4K, 5K, 8K UHD, 10K, experimental 16K and 32K render targets, smart edge enhancement, sharpness settings and adaptive load handling.
- **Ad blocker:** request interception for a list of common ad/tracker domains plus cosmetic hiding of common ad containers. It is a lightweight blocker rather than a complete EasyList/uBlock Origin engine.
- **Video display:** WebView video playback and full-screen handling.

## About 16K and 32K

These are experimental upscaling/render targets, not guarantees of native 16K/32K source streams. The actual output is capped by WebGL texture/renderbuffer/viewport limits and a conservative pixel budget on mobile. Upscaling cannot recover source detail that was never present, and some websites restrict video access to WebGL.

## Build the APK

1. Install [Android Studio](https://developer.android.com/studio), JDK 17 and Android SDK Platform 35.
2. Open the repository's root folder in Android Studio.
3. Let Gradle sync and install any SDK components requested.
4. Choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. The debug APK is normally created at `app/build/outputs/apk/debug/app-debug.apk`.

A GitHub Actions workflow is included to attempt a debug build on pushes and pull requests. This project has not yet been compiled or device-tested in this environment, so wait for the workflow result and test on an Android device before release.
