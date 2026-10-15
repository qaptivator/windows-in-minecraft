# WindowsCraft: build and run guide

Target: Minecraft Java 26.1.2, Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2, Java 25, Windows 10 1903+ (Windows 11 recommended).

This is a skeleton. It captures one window with Windows Graphics Capture (WGC), copies the frames to the CPU, uploads them to a Minecraft texture, and draws that texture as a HUD quad. Input, resizing, and the in-world quad come later.

---

## 1. How the pieces fit together

```
 Windows window
      │  Windows Graphics Capture (WinRT)
      ▼
 native/src/capture.cpp      C++17 + C++/WinRT + D3D11
      │  GPU copy -> staging texture -> Map (one frame behind) -> RGBA8 buffer
      ▼
 native/src/exports.cpp      extern "C" functions, __declspec(dllexport)
      │  windowscraft_native.dll  (built by CMake, copied by Gradle)
      ▼
 src/client/.../capture/NativeCapture.java   Java 25 FFM (java.lang.foreign), no JNI headers
      ▼
 src/client/.../capture/WindowCapture.java   one captured window
      ▼
 src/client/.../render/CaptureTexture.java   NativeImage + DynamicTexture, native writes into it
      ▼
 src/client/.../WindowsCraftClient.java      HUD element, GuiGraphicsExtractor.blit(...)
```

Who builds what:

| Step | Tool | Input | Output |
|---|---|---|---|
| 1. Configure native | CMake (`native/CMakeLists.txt`) | C++ sources | VS solution in `native/build/` |
| 2. Build native | MSVC via CMake | C++ sources | `native/build/Release/windowscraft_native.dll` |
| 3. Copy DLL | Gradle `copyNativeDll` | the DLL | `build/generated/windowscraft-natives/natives/windows-x64/` |
| 4. Compile Java | Gradle + javac (release 25) | `src/main`, `src/client` | class files |
| 5. Package | Loom (`build`) | classes + resources + DLL | `build/libs/windowscraft-0.1.0.jar` |
| 6. Run | Loom (`runClient`) | everything | Minecraft with the mod |

Gradle runs steps 1 to 3 for you. You do not need to run CMake by hand unless you want to debug the C++.

---

## 2. Prerequisites (install once)

### 2.1 Java 25 (JDK, not JRE)
- Install Temurin 25 (`winget install EclipseAdoptium.Temurin.25.JDK`) or any JDK 25.
- Set `JAVA_HOME` to the JDK 25 folder and put `%JAVA_HOME%\bin` first in `PATH`.
- Check: `java -version` must say `25`.

Gradle and the Minecraft run both use this JDK.

### 2.2 Visual Studio with C++ (for MSVC and the Windows SDK)
Install **Visual Studio 2022 Build Tools** (or full Visual Studio 2022) with the workload **"Desktop development with C++"**. In the installer details, make sure these are ticked:
- MSVC v143 (x64/x86 build tools)
- Windows 11 SDK (or Windows 10 SDK 10.0.19041 or newer)
- C++ CMake tools for Windows

The Windows SDK already contains the C++/WinRT headers (`winrt/...`) and the `windowsapp.lib` import library that WGC needs. You do **not** need a NuGet package for C++/WinRT.

### 2.3 CMake 3.21+
- Installed by the VS "C++ CMake tools" option, or `winget install Kitware.CMake`.
- Check: `cmake --version`. If it is not found, add it to `PATH` (the VS copy is at `...\Microsoft Visual Studio\2022\...\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin`).

### 2.4 Gradle wrapper files (no global Gradle needed)
The wrapper binaries are not in this skeleton, because they are generated and binary. Get them from the official template:

1. Download the zip of the branch `26.1.2` from https://github.com/FabricMC/fabric-example-mod/archive/refs/heads/26.1.2.zip
2. Copy these items from the unzipped folder into `D:\dev\expt\java\windowscraft\`:
   - `gradlew`
   - `gradlew.bat`
   - the `gradle\` folder (contains `wrapper\gradle-wrapper.jar` and `gradle-wrapper.properties`)

The wrapper uses Gradle 9.7.1. Do not copy the template's `build.gradle`, `settings.gradle`, `gradle.properties`, or `src\` folder. This skeleton already has its own.

---

## 3. Dependencies: what gets pulled, and from where

Nothing needs to be installed by hand beyond section 2.

| Dependency | Pulled by | Source | Pinned in |
|---|---|---|---|
| Minecraft 26.1.2 (unobfuscated) | Loom | Mojang Maven | `gradle.properties` `minecraft_version` |
| Fabric Loader 0.19.5 | Gradle | maven.fabricmc.net | `loader_version` |
| Fabric API 0.155.3+26.1.2 | Gradle | maven.fabricmc.net | `fabric_api_version` |
| Loom plugin 1.18-SNAPSHOT | Gradle plugin portal | maven.fabricmc.net | `loom_version` |
| Java libraries (slf4j, etc.) | Loom | Mojang/Maven Central | automatic |
| Windows SDK (WinRT headers, d3d11, dwmapi, windowsapp.lib) | Visual Studio installer | Microsoft | section 2.2 |

The first Gradle run downloads several hundred MB (Minecraft, libraries, decompiled sources). That is normal.

---

## 4. Build and run

Open a terminal in `D:\dev\expt\java\windowscraft`.

### 4.1 Build only the native DLL (fastest way to check C++)
```bat
cmake -S native -B native\build -A x64
cmake --build native\build --config Release
```
Expected output file: `native\build\Release\windowscraft_native.dll`.

### 4.2 Build the whole mod jar (C++ + Java + packaging)
```bat
gradlew.bat build
```
Runs `configureNative` -> `buildNative` -> `copyNativeDll` -> compile -> jar. The result is `build\libs\windowscraft-0.1.0.jar`. Check that it contains `natives/windows-x64/windowscraft_native.dll`.

### 4.3 Run Minecraft with the mod
```bat
gradlew.bat runClient
```
Before starting the game:
1. Open Notepad and leave it visible. The default target is any window whose title contains `Notepad`.
2. To pick a different window, change `property('windowscraft.target', 'Notepad')` in `build.gradle` (for example `'Visual Studio Code'`), or launch with `-Dwindowscraft.target=...`.

Expected result: in-game, a live copy of the window appears at the top left, under the chat area, 640 px wide.

Expected logs (in the Gradle console):
- `Capturing window matching 'Notepad'`
- or `No visible window title contains 'Notepad', retrying` every two seconds until a match appears

If the window is minimized, the picture freezes. Restore the window.

### 4.4 Running the mod outside the dev environment
Launchers need the same JVM flag, or Java prints FFM warnings:
`--enable-native-access=ALL-UNNAMED`
Add it in the launcher's JVM arguments, and set `-Dwindowscraft.target=...` the same way.

---

## 5. Repository layout

```
windowscraft/
├── BUILD.md                       this file
├── build.gradle                   Loom + native build tasks
├── gradle.properties              version pins (MC, Loader, Fabric API, Loom)
├── settings.gradle
├── gradlew, gradlew.bat, gradle/  copied from the template (section 2.4)
├── native/                        C++17, built by CMake
│   ├── CMakeLists.txt
│   └── src/
│       ├── capture.h / capture.cpp   WGC + D3D11 capture core (no Minecraft knowledge)
│       ├── input.h / input.cpp       window search, click injection
│       └── exports.cpp               extern "C" API used by Java
└── src/
    ├── main/
    │   ├── java/dev/windowscraft/WindowsCraft.java     common entrypoint
    │   └── resources/fabric.mod.json
    └── client/java/dev/windowscraft/client/
        ├── WindowsCraftClient.java                     client entrypoint, HUD quad
        ├── capture/NativeCapture.java                  FFM bindings, DLL extraction
        ├── capture/WindowCapture.java                  one captured window
        └── render/CaptureTexture.java                  Minecraft texture backed by native memory
```

The split matches the earlier advice. `native/src/capture.*` knows nothing about Minecraft, so the Vulkan importer and the OpenGL importer can be added later without rewriting capture.

---

## 6. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `cmake` not found | CMake not on PATH | Add the VS CMake `bin` folder to PATH, or install CMake |
| `winrt/Windows.Graphics.Capture.h: No such file` | Windows SDK missing or too old | Install Windows 10 SDK 10.0.19041+ via the VS installer |
| `LNK2019 ... GraphicsCaptureItem` or `unresolved external` for `CreateDirect3D11DeviceFromDXGIDevice` | Missing import lib | Make sure `d3d11`, `dxgi`, `windowsapp` are in `target_link_libraries` (they are) and the SDK is complete |
| `Could not find ... -A x64` error | Old CMake or wrong VS | Use CMake 3.21+ and VS 2022 |
| `Missing /natives/windows-x64/windowscraft_native.dll` | `copyNativeDll` did not run | Run `gradlew.bat copyNativeDll` and check the DLL path in its log |
| `Native call wc_init failed` / `wc_init returned failure` | D3D11 device creation failed (driver or RDP session) | Run on the local desktop. Check GPU drivers are up to date |
| Log says `No visible window title contains ...` forever | Window title does not match | Match a substring of the real title, shown in the Windows taskbar |
| Frame never updates | Window minimized, or WGC unsupported for that window | Restore the window. Some UWP or protected windows (DRM video) refuse capture |
| `WARNING: A restricted method ... has been called` | Missing `--enable-native-access` | Add the JVM flag (section 4.4) |
| Black or wrong-size image | Capture started before the window was laid out | Wait a second. The size is re-read on every frame |

To read the native side of an error, check the Java exception chain. The C++ side returns `false` on most WinRT or D3D failures, so Java reports `wc_*` failures rather than the HRESULT. For deeper debugging, add `OutputDebugStringW` calls in `capture.cpp` and view them with DebugView.

---

## 7. Unverified points (check these first if the build fails)

I checked the 26.1.2 Fabric template, `gradle.properties`, Loom's run options, and the Fabric 26.1.2 docs for the HUD and GUI APIs (`HudElementRegistry`, `GuiGraphicsExtractor.blit`, `Identifier`, `RenderPipelines.GUI_TEXTURED`). The following are written from memory of the 1.21.x API and need a check against the 26.1.2 sources:

1. **Imports** of `DeltaTracker`, `GuiGraphicsExtractor`, `RenderPipelines`, `Identifier`, `NativeImage`, `DynamicTexture`, `TextureManager`. If the IDE shows a red import, use its quick-fix to find the new package.
2. **`new DynamicTexture(Supplier<String>, NativeImage)`** in `CaptureTexture.java`. In older versions it took a `String` name.
3. **`NativeImage.getPointer()`** in `CaptureTexture.java`. Native code writes directly into this memory, so it must exist and return the real pixel address. If it is missing in 26.1.2, the fallback is a `DynamicTexture`-owned `NativeImage` plus a Java-side copy.
4. **`TextureManager.release(Identifier)`**. It must close the old texture. If it does not, the NativeImage leaks on every resize.
5. **`NativeImage.Format.RGBA` byte order.** The native code writes R,G,B,A bytes. If colors come out swapped, change the swizzle in `capture.cpp` (`Poll`).

To check these, run `gradlew.bat genSources`. This produces a decompiled Minecraft sources jar in the Gradle cache, which you can open in the IDE and search for the exact signatures.

---

## 8. Known gaps (planned, not in the skeleton)

- **Input.** `wc_click` exists, but Java does not call it yet. Keyboard and text need `SendInput` with `KEYEVENTF_SCANCODE` or `KEYEVENTF_UNICODE`.
- **Coordinates.** `ClickFrame` assumes the captured frame equals the extended frame bounds. Verify this on a window with rounded corners or a custom border.
- **Resizing.** The frame size follows the window automatically (the texture is reallocated). Resizing the window from Minecraft needs `SetWindowPos` plus debouncing.
- **Cursor.** Cursor capture is off. Minecraft does not draw it yet.
- **DPI.** The process is not declared DPI-aware, so coordinates can be off on scaled displays. Handle it when the coordinate mapping is finalized.
- **Popups.** WGC captures one window. Menus and tooltips are separate windows and will not show up. The monitor-crop approach from the previous discussion is the fix.
- **In-world quad.** Currently a HUD element. Next step: render the texture as a quad in the world (`WorldRenderEvents`, after the HUD path is confirmed).
- **Threading.** Everything must run on the Minecraft render thread. `Poll` is not thread-safe.
- **Shutdown.** `CLIENT_STOPPING` closes sessions and the device. If the game crashes, the DLL is unloaded with the process.
