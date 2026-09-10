# Winlator Architecture Report (Phase 1 Analysis)

> Analysis of the upstream `brunodev85/winlator` repository as cloned on 2026-09-10,
> at `app` submodule commit `c03f6ab`, app **versionName 11.2 / versionCode 32**.
> This document is the Phase 1 deliverable required before any substantial modification.
> Nothing in Winlator has been modified to produce this report.

---

## 1. What Winlator is

Winlator is an Android app that runs **x86 / x86_64 Windows** programs on **ARM64 Android**
by combining:

- **Wine** — implements the Windows API (Win32, DirectX via wrappers, registry, etc.).
- **Box64** — a userspace x86/x86_64 → ARM64 dynamic-recompiler (dynarec). Wine's own
  binaries are x86_64 Linux; Box64 executes them, and 32-bit Windows code runs through
  Wine's WoW64 layer (see §9).
- **A Java-implemented X11 server** (`com.winlator.xserver`) that Wine's X11 driver talks to
  over a Unix socket. There is **no real Xorg** — Winlator reimplements the parts of the X
  protocol Wine needs, and composites the resulting windows with OpenGL ES.
- **A Vulkan/GL graphics stack**: DXVK/VKD3D/WineD3D translate Direct3D → Vulkan/GL, and a
  Vulkan driver (Turnip for Adreno, or the bundled **Vortek** driver, or Zink/VirGL) executes it.

It does **not** require root.

---

## 2. Repository layout (this is a meta-repo + submodules)

The top-level `winlator` repo is mostly a **container for prebuilt runtime blobs plus three
git submodules**. The actual Android/Java/C++ source lives in the `app` submodule.

```
winlator/                         (meta-repo)
├── .gitmodules                   → app, vortek, gladio submodules
├── app/            (SUBMODULE brunodev85/winlator-app)  ← THE ANDROID APP
├── vortek/         (SUBMODULE brunodev85/vortek)        ← Vortek Vulkan driver sources
├── gladio/         (SUBMODULE brunodev85/gladio)        ← Gladio GL renderer sources
├── installable_components/       box64/ dxvk/ turnip/ vkd3d/ wined3d/  (prebuilt, versioned)
├── wine_addons/                  wine-gecko / wine-mono MSIs
├── glibc_patches/                sysdeps patches for glibc under Android
├── android_alsa/                 ALSA→Android audio bridge (CMake native lib)
├── input_controls/               54 ready-made *.icp touch-control profiles for PC games
└── LICENSE (GPLv3)
```

> **Note:** a *shallow* clone (`--depth 1`) leaves `app/`, `vortek/`, `gladio/` empty until
> `git submodule update --init` is run. All three were fetched for this analysis.

### The Android module (`app/`)

```
app/                              (Gradle root project)
├── settings.gradle               include ':app'
├── build.gradle                  AGP 8.4.2
├── gradle/wrapper/…              Gradle 8.14.5
└── app/                          (the :app module)
    ├── build.gradle              namespace/appId com.winlator, see §3
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml   4 activities + FileProvider (see §7)
        ├── java/com/winlator/…   Java source (see §5)
        ├── cpp/                  native C/C++ (see §8)
        ├── assets/               runtime blobs shipped in the APK (see §10)
        ├── jniLibs/arm64-v8a/    prebuilt .so (glib, oboe, FLAC, fluidsynth, opus, …)
        └── res/                  Android resources / layouts / strings
```

---

## 3. Build system & toolchain (exact requirements)

From `app/app/build.gradle` and the Gradle wrapper:

| Item | Value |
|---|---|
| Application id / namespace | `com.winlator` |
| compileSdk | **35** |
| minSdk | **26** (Android 8.0) |
| targetSdk | **28** |
| versionCode / versionName | 32 / **11.2** |
| ABI filter | **arm64-v8a only** |
| NDK | **24.0.8215888** |
| CMake | **3.22.1** |
| Android Gradle Plugin | **8.4.2** |
| Gradle | **8.14.5** (wrapper) |
| Required JDK | **JDK 17** (AGP 8.4 does not support JDK 22+) |

Dependencies: `androidx.appcompat 1.4.0`, `androidx.preference 1.2.1`,
`material 1.4.0`, `zstd-jni 1.5.2-3@aar`, `org.tukaani:xz 1.7`, `commons-compress 1.20`.

**Notable quirk:** there is only a `debug` build type in `build.gradle`, and it sets
`minifyEnabled true` with ProGuard. There is no explicit `release` block — release builds
inherit defaults. `lintOptions { checkReleaseBuilds false }`.

Native code builds via `externalNativeBuild { cmake { path 'src/main/cpp/CMakeLists.txt' } }`.

---

## 4. Runtime architecture (the big picture)

```
        ┌─────────────────────────── Android process (com.winlator) ────────────────────────────┐
        │                                                                                        │
        │  XServerDisplayActivity                                                                 │
        │   ├── XServerView (GLSurfaceView) ── GLRenderer  ── composites X windows via OpenGL ES  │
        │   ├── TouchpadView / InputControlsView ── touch → mouse/keyboard events                 │
        │   └── XEnvironment  (owns the components below)                                         │
        │                                                                                        │
        │   Java XServer  ◄── Unix socket ──►  Wine (guest)                                       │
        │      xserver/*  (protocol, windows, atoms, GLX, keyboard, input devices)               │
        │                                                                                        │
        │   Environment components (xenvironment/components/*):                                   │
        │      • XServerComponent            Unix socket X server endpoint                        │
        │      • SysVSharedMemoryComponent   SysV SHM bridge (MIT-SHM)                            │
        │      • GuestProgramLauncherComponent   ← launches Box64 → Wine (THE game launcher)      │
        │      • ALSAServerComponent / PulseAudioComponent   audio                                │
        │      • VortekRendererComponent / VirGLRendererComponent   Vulkan/GL server side         │
        │      • NetworkInfoUpdateComponent                                                       │
        │                                                                                        │
        │   WinHandler  ◄── UDP/loopback ──►  winhandler.exe (guest)   process list, gamepad,     │
        │                                                              clipboard, kill/launch     │
        └────────────────────────────────────────────────────────────────────────────────────────┘

     Guest side (inside Box64 + Wine):
        box64  →  wine explorer /desktop=nogui,<WxH>  →  winhandler.exe  →  <the Windows game .exe>
                                     │
                                     └── Direct3D → DXVK(d3d9/10/11) / VKD3D(d3d12) / WineD3D → Vulkan/GL
```

### Key subsystems (Java packages under `com.winlator`)

| Package | Role |
|---|---|
| *(root)* | Activities & fragments: `MainActivity`, `XServerDisplayActivity`, `ControlsEditorActivity`, `ExternalControllerBindingsActivity`; fragments for Containers, Shortcuts, Settings, InputControls, FileManager. |
| `container` | `Container` (the Wine-prefix profile & all its settings), `ContainerManager` (create/duplicate/remove, on-disk layout), `Shortcut` (per-game launch profile), `Drive`, `GraphicsDrivers`, `DXWrappers`, `AudioDrivers`. |
| `box64` | `Box64Preset`, `Box64PresetManager` — named env-var presets (Compatibility/Performance/Stability/…). |
| `xserver` (36 files) | The Java X11 server: `XServer`, `Window`, `WindowManager`, `Keyboard`, `InputDeviceManager`, `Atom`, `Property`, request handlers (`requests/*`), extensions (`GLXExtension`, …). |
| `xenvironment` | `XEnvironment`, `RootFS`, `RootFSInstaller`, and `components/*` (see diagram). |
| `renderer` | `GLRenderer` + GL helpers — composite/blit X windows & cursor to the surface, magnifier, screen effects. |
| `inputcontrols` | `ControlsProfile`, `ControlElement` (buttons/d-pads/sticks), `InputControlsManager`, `ExternalController(Binding)` — the touch-overlay + gamepad model. |
| `widget` | Custom views: `XServerView`, `TouchpadView`, `InputControlsView`, `FrameRating` (FPS/HUD), `MagnifierView`, `LogView`. |
| `winhandler` | `WinHandler` (host side of `winhandler.exe`), gamepad delivery, `TaskManagerDialog`. |
| `win32` | `PEParser` and PE/Win32 helpers. |
| `core` | Utilities: `WineInfo`, `WineInstaller`, `WineUtils`, `WineRegistryEditor`, `WineStartMenuCreator`, `WineThemeManager`, `GeneralComponents`, `TarCompressorUtils` (zstd), `ProcessHelper`, `FileUtils`, `EnvVars`, `KeyValueSet`, `Win32AppWorkarounds`, `DefaultVersion`. |
| `contentdialog` | All the config dialogs (DXVK, VKD3D, Turnip, WineD3D, VirGL, audio, screen-effect, debug, …). |
| `alsaserver`, `sysvshm`, `xconnector`, `math` | ALSA client, SysV SHM, Unix-socket connector framework, math helpers. |

---

## 5. The game-launch flow (traced through the code)

This is the single most important path for RAN Mobile. Source: `XServerDisplayActivity` +
`GuestProgramLauncherComponent`.

1. **User taps a Shortcut** (home screen) or opens an `.exe` in the file manager.
   `MainActivity`/`ShortcutsFragment` starts **`XServerDisplayActivity`** with intent extras:
   `container_id`, and either `shortcut_path` (a `.desktop` file) or `exec_path` (a raw path).

2. `XServerDisplayActivity.onCreate`:
   - Loads the `Container` via `ContainerManager.getContainerById(...)` and
     **`activateContainer()`** (symlinks `home/xuser` → `home/xuser-<id>` so the prefix is "mounted").
   - Resolves effective settings from Container, then **overridden by the Shortcut** if present
     (graphicsDriver, dxwrapper, wincomponents, audioDriver, screenSize, box64Preset, envVars…).
   - Applies `Win32AppWorkarounds` keyed on the exe/wmClass name.

3. On a background executor: `setupWineSystemFiles()` (extracts DX wrapper + wincomponent DLLs,
   applies registry/theme/services), `extractGraphicsDriverFiles()`, `changeWineAudioDriver()`,
   then **`setupXEnvironment()`**.

4. `setupXEnvironment()` builds the environment and the **guest command**:

   ```
   guestExecutable = "wine explorer /desktop=<name>,<WxH> " + getWineStartCommand()
   name = "nogui"  when launching a shortcut/exec_path (fullscreen game),  else "shell"
   ```

   `getWineStartCommand()` produces, for a normal exe:

   ```
   C:\windows\winhandler.exe /dir <DOS dir> "<exefile>" <execArgs>
   ```

   (`winhandler.exe` is Winlator's guest-side helper; the actual game is its child.)

5. `GuestProgramLauncherComponent.start()`:
   - `extractBox64File()` + `copyDefaultBox64RCFile()`.
   - Builds env (`HOME`, `USER`, `DISPLAY=:0`, `WINEPREFIX`, `PATH`, `LD_LIBRARY_PATH`,
     Box64 vars from the selected preset + `BOX64_DYNAREC=1`, `BOX64_RCFILE`, audio/graphics vars).
   - Executes:

     ```
     <rootfs>/usr/local/bin/box64  wine explorer /desktop=nogui,<WxH> C:\windows\winhandler.exe /dir … "<exe>" …
     ```

   via `ProcessHelper.exec(...)`. The returned PID is tracked; `onPause/onResume` suspend/resume
   guest child processes; termination fires `terminationCallback → exit()`.

**Implication for RAN:** launching the RAN client is *entirely a matter of configuration* — a
Container plus a Shortcut whose `path` points at the RAN `.exe`. No Wine/Box64 code changes are
needed to start it.

---

## 6. Container & Shortcut model (persistence)

- A **Container** is a Wine prefix + a JSON settings blob. On disk:
  `…/home/xuser-<id>/` with `.container` (JSON) and `.wine/` (the prefix, `drive_c`, etc.).
  Settings (see `Container.java`): `screenSize`, `envVars`, `graphicsDriver`,
  `dxwrapper(+Config)`, `wincomponents`, `audioDriver(+Config)`, `drives`, `wineVersion`,
  `box64Preset`, `cpuList` / `cpuListWoW64`, `hudMode`, `startupSelection`, `desktopTheme`,
  plus a free-form `extraData` JSON.
- **Defaults** worth noting for RAN:
  - `DEFAULT_SCREEN_SIZE = 1280x720`
  - `DEFAULT_DXWRAPPER = DXVK`
  - `DEFAULT_AUDIO_DRIVER = ALSA`
  - `DEFAULT_WINCOMPONENTS` includes `direct3d=1, directsound=1, directmusic=1, xaudio=1,
    vcrun2010=1, wmdecoder=1` (DirectShow/DirectPlay off by default).
  - `DEFAULT_DRIVES = D:<Downloads> E:<internal storage>`.
- **ContainerManager** creates a container by extracting `assets/container_pattern.tzst` and
  copying "common DLLs" from `common_dlls.json`. Containers are enumerated from `home/xuser-*`.
- A **Shortcut** is a `.desktop` file under the prefix's `Desktop/`, holding per-game overrides
  of any container setting plus `execArgs`, `controlsProfile`, `forceFullscreen`, `box64Preset`,
  `dinputMapperType`, etc. This is Winlator's natural place for a "RAN Online" launch profile.

---

## 7. Android entry points (`AndroidManifest.xml`)

| Activity | Purpose |
|---|---|
| `MainActivity` (LAUNCHER) | Home; drawer nav → Containers / Shortcuts / Input Controls / Settings / About; file manager; permission + RootFS install bootstrap. |
| `XServerDisplayActivity` | The in-game display + launcher (landscape, PiP-capable). |
| `ControlsEditorActivity` | Visual editor for touch-control layouts. |
| `ExternalControllerBindingsActivity` | Gamepad → key/mouse binding editor. |
| `FileProvider` | `com.winlator.FileProvider` for sharing files. |

Permissions: INTERNET, ACCESS_NETWORK_STATE/WIFI_STATE, READ/WRITE_EXTERNAL_STORAGE,
MODIFY_AUDIO_SETTINGS, VIBRATE. Requires GLES 2.0. `android:isGame="true"`.

---

## 8. Native components (`src/main/cpp/`)

`CMakeLists.txt` adds: `winlator` (core JNI: X server glue, SysV SHM, process helpers),
`vortekrenderer` (Vortek Vulkan driver client), `virglrenderer`, `midihandler`,
`libadrenotools` (Adreno driver loader for custom Turnip), `gladiorenderer`.
Prebuilt shared libs ship in `jniLibs/arm64-v8a/` (glib stack, `liboboe`, FLAC, fluidsynth,
opus, ogg, pcre…). Box64/DXVK/VKD3D/Turnip/WineD3D themselves are **prebuilt** and shipped as
versioned archives (see §9/§10), not compiled in this project.

---

## 9. Installable components, assets & versioning

- **`installable_components/`** (in the meta-repo): `box64`, `dxvk`, `turnip`, `vkd3d`,
  `wined3d` — prebuilt, versioned; surfaced in-app via `core/DefaultVersion` +
  `core/GeneralComponents` and extracted on demand.
- **`app/src/main/assets/`** ships: `rootfs.tzst` (the guest Linux root filesystem = Wine +
  libs), `container_pattern.tzst` (fresh-prefix template), `common_dlls.json`,
  `wincomponents/*.tzst` (+`wincomponents.json`), `dxwrapper/*` (d8vk, d7vk, cnc-ddraw),
  `graphics_driver/*` (vortek, zink, virgl, gladio `.tzst`), `box64/default.box64rc`,
  `gpu_cards.json`, `gamepad_models.json`, `inputcontrols/`, `soundfont/`, `wallpapers/`,
  `wine_startmenu.json`, `wine_debug_channels.json`.
- Wine addons (`wine-gecko`, `wine-mono` MSIs) live in the meta-repo's `wine_addons/`.

---

## 10. RAN Online implications (verified facts)

Verified against the user's client at `C:\_KYUDO DEVELOPMENT CLIENT`:

- `Update.exe` → **PE32, Intel i386 (32-bit x86)**, GUI. Patcher/launcher.
- `minia.exe` → **PE32, Intel i386 (32-bit x86)**, GUI. Main game client (~10 MB).
- DirectX dependencies present: `d3dx9_43.dll`, `D3DCompiler_43.dll`, `D3dx9d_43.dll`
  ⇒ the game is **Direct3D 9**.
- Other DLLs: `ijl15` (Intel JPEG), `BugTrap`/`dbghelp` (crash reporting), `tbb`/`tbbmalloc`
  (Intel TBB), `netauth.dll`, `mtp.dll`/`mtd.dll`, `MShield`/`HackShield` in the SDK
  (anti-cheat — **a known risk area**, see below).

**What this means for the port:**

1. **32-bit x86 + D3D9 is a well-trodden, favorable path for Winlator.** Wine runs the 32-bit
   client through its WoW64 layer on the x86_64 Wine build that Box64 executes; D3D9 is handled
   by **DXVK** (default `d3d9.dll` → Vulkan), which is the recommended renderer here.
   `Container.cpuListWoW64` exists specifically for tuning the 32-bit path.
2. **No Wine/Box64 source changes are required** to *attempt* running RAN — it is a Container +
   Shortcut configuration exercise. RAN Mobile should therefore be built as a **configuration &
   UX layer on top of Winlator**, not a fork of its runtime.
3. **Anti-cheat (HackShield / MShield / nProtect-style) is the primary unknown.** Kernel/driver
   or aggressive user-mode anti-cheat frequently does not run under Wine and is the most likely
   blocker. This must be tested with the *actual* build the user intends to ship, and cannot be
   asserted to work from analysis alone.
4. **Recommended starting Container profile for RAN** (defaults, to be validated on-device):
   DXVK, ALSA, screen 1280x720 (configurable), Box64 preset `Compatibility` first then
   `Performance`, wincomponents leave `direct3d/directsound/directmusic/xaudio` native as per
   default, `vcrun2010` on. Point a Drive (e.g. `D:`) at the folder holding the RAN client so
   the client's own files stay **user-supplied and external** (no copyrighted assets bundled).

---

## 11. Where "RAN Mobile" will hook in (retain / modify / new)

| Winlator component | Plan |
|---|---|
| Wine, Box64, XServer, XEnvironment, renderer, DXVK/VKD3D, audio, SysV SHM, native cpp | **RETAIN unchanged.** This is the runtime foundation. |
| `Container` / `Shortcut` / `ContainerManager` | **REUSE**; add a RAN profile factory that produces a preconfigured container + shortcut (no core edits, or minimal additive helpers). |
| `inputcontrols` / `ControlsEditorActivity` / `.icp` profiles | **REUSE + EXTEND**: ship a RAN touch-control profile (movement, action bar, targeting) as data, mirroring the existing `input_controls/*.icp` pattern. |
| `MainActivity` launcher UX | **NEW** RAN launcher screen/flow (Play / Settings / Controls / Graphics / Server / Diagnostics) — a new activity/fragment layer that ultimately starts `XServerDisplayActivity`. |
| App identity (`com.winlator` → `com.kyudo.ranmobile`, name, icon) | **MODIFY** (build.gradle + manifest + resources), preserving upstream GPL/license notices. |
| Server config / patcher / diagnostics / logging (task §13–§17) | **NEW**, additive modules that read a `server_config.json` and drive the existing launch flow. |

---

## 12. Environment constraints observed on this machine (important)

This analysis machine is **Windows 10 x64** and is **not** able to build or run the Android app:

- No Android SDK, no NDK 24.0.8215888, no CMake 3.22.1, no Gradle on PATH, no Android Studio.
- Installed JDK is **26** — too new for AGP 8.4.2 (needs JDK 17).
- ~9 GB free on `C:` — insufficient for SDK+NDK+build cache (tens of GB).
- **Fundamentally, Wine + Box64 execute only on Android/ARM64** — they cannot run on this
  Windows/x86 host, so end-to-end "does RAN launch" testing is impossible here regardless of
  toolchain. Building the APK and testing the client require an Android ARM64 device/emulator
  and a proper Android dev setup.

Consequently, work produced here is **source, configuration, and documentation** that must be
compiled and tested on an Android development machine. See `docs/BUILD_RAN_MOBILE.md` (to be
authored) for the reproducible build recipe.
