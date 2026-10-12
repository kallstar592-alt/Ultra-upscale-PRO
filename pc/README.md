# Ultra Upscaler PRO for Windows

A standalone Windows desktop app based on Electron/Chromium. It is separate from the Android application and does not require Android Studio.

## What it includes
- Browser-style tabs, including Ctrl+T, Ctrl+W, Ctrl+L, Ctrl+R and Ctrl+Tab.
- Search/address bar and browser navigation.
- Enhance panel with 4K, 5K, 8K, 10K, experimental 16K and 32K targets; crispness and optional edge enhancement controls.
- Adaptive filter load reduction based on dropped video frames.
- Lightweight ad/tracker domain request blocking and cosmetic page-element hiding.

## Build on Windows (optional)
Install Node.js 22+ from https://nodejs.org/. Then open PowerShell in this `pc` folder and run:

```powershell
npm install
npm run start
```

To create an installer and a portable EXE:

```powershell
npm run dist:win
```

Artifacts appear in `pc/dist`.

## Download without installing build tools
GitHub Actions builds the Windows installer and portable executable and publishes them as a workflow artifact. Open the repository's **Actions** tab, choose **Windows desktop build**, open the successful run, and download **ultra-upscale-pro-windows**.

## Honest resolution note
The 16K/32K controls are experimental processing-target settings, not guaranteed native source-stream resolutions. This implementation applies image/display enhancement filters to detected HTML video. It does not transcode or export an actual 16K/32K video file, and it cannot restore detail absent from the original source. Actual effects depend on the site's video handling, Chromium, GPU and hardware limits. The lightweight ad blocker cannot catch every ad.

## Security note
The application uses Electron with context isolation, no Node integration in the UI, sandboxed rendering and a narrow preload bridge. Ads are blocked by a short domain list; review that list before relying on it as a privacy or security product.


## Crimson Ruby theme

The mobile and desktop variants share a black/crimson visual theme, chained ruby app icon, soft red splatter accents, and a slow-moving multicolor liquid backdrop behind desktop tabs. The desktop tab animation respects `prefers-reduced-motion` and stays behind readable tab labels.
