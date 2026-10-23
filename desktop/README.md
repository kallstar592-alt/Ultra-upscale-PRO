# Ultra Upscale PRO — Windows Control Center

A Windows desktop companion that sits in the taskbar notification area and opens a compact Screen Boost control panel.

## What it does

- Shows a Screen Boost toggle and display-resolution target selector for 4K, 5K, 8K, 10K, and 16K.
- Checks Windows display modes exposed by the active display driver and marks unsupported targets before attempting changes.
- Applies supported resolutions to the Windows desktop, so the selected logical desktop resolution affects the system-wide desktop rather than one browser tab.
- Provides a 15-second confirmation countdown after a display mode change and automatically restores the prior mode if not confirmed.
- Offers shortcuts to Windows Display Settings and NVIDIA Control Panel, with guidance for NVIDIA DSR / AMD Virtual Super Resolution.
- Lives in the taskbar notification area. Closing the panel hides it; use the tray menu to reopen or exit and restore the prior display mode.

## Limits

This is a display-mode control utility, not a driver-level AI upscaler. It can only apply modes that Windows and the installed GPU/display driver expose. For high logical resolutions, enable a GPU virtual-resolution feature such as NVIDIA DSR or AMD Virtual Super Resolution first, then refresh the mode list. The monitor still physically outputs its native pixel grid, with the GPU/display pipeline scaling the selected mode as configured by the driver. It cannot force unsupported 16K modes, create missing detail, or alter protected video streams.

## Download without Visual Studio

The Build Windows Control Center GitHub Actions workflow publishes a self-contained x64 Windows executable as the artifact ultra-upscale-control-center-win-x64. Download and extract the artifact ZIP from the successful workflow run, then run UltraUpscaleControlCenter.exe. No separate .NET runtime install is required.

## Build locally

With the .NET 8 SDK on Windows, run: dotnet publish desktop/UltraUpscaleControlCenter.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
