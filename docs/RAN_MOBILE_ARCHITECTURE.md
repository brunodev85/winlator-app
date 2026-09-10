# RAN Online Mobile — Architecture & Change Log

> RAN Online Mobile is a **configuration and UX layer built on top of Winlator**. Wine, Box64,
> the X server, the renderer, DXVK/VKD3D and the audio stack are **reused unchanged**. This
> document records exactly what was retained, modified, and added, and how the pieces fit.
> Read `docs/WINLATOR_ARCHITECTURE.md` first for the Winlator baseline.

- **App identity:** `com.kyudo.ranmobile` (installed id) / display name **RAN Online Mobile**.
- **Java namespace / JNI package:** unchanged `com.winlator` (see "Why the namespace stays").
- **Base:** Winlator app submodule `winlator-app` @ `c03f6ab`, versionName 11.2.
- **License:** upstream Winlator is GPLv3; all notices are preserved. RAN additions inherit GPLv3.
- **Branch:** `ran-mobile-development`.

---

## 1. Retained (unchanged) Winlator components

Wine, Box64, the Java X server (`com.winlator.xserver.*`), `XEnvironment` and all its components
(`GuestProgramLauncherComponent`, `XServerComponent`, `SysVSharedMemoryComponent`, audio,
Vortek/VirGL renderers), the OpenGL renderer, DXVK/VKD3D/WineD3D wrappers, `Container` /
`ContainerManager` / `Shortcut`, the input-controls engine, `WinHandler`, and all native `cpp/`
modules. **No runtime logic in these was changed.** Launching the RAN client is a matter of
configuration, so there was no need to fork the runtime.

## 2. Modified Winlator files (minimal, enumerated)

| File | Change | Why |
|---|---|---|
| `app/app/build.gradle` | `applicationId` → `com.kyudo.ranmobile` (namespace kept `com.winlator`) | Installed identity per spec, without touching JNI/R plumbing. |
| `AndroidManifest.xml` | `RanLauncherActivity` becomes the `LAUNCHER`; `MainActivity` keeps its declaration but loses the launcher `intent-filter`; FileProvider authority → `com.kyudo.ranmobile.FileProvider` | Dedicated RAN home screen; unique provider authority so it can coexist with real Winlator. |
| `core/AppUtils.java` | `INTERNAL_STORAGE` literal → `/data/data/com.kyudo.ranmobile/storage` | Must match the new data dir (was hardcoded to the old id). |
| `core/FileUtils.java` | FileProvider authority literal → `com.kyudo.ranmobile.FileProvider` | Match manifest. |
| `cpp/winlator/include/winlator.h` | `APP_CACHE_DIR` → `com.kyudo.ranmobile` | Native cache path must match the new data dir. |
| `cpp/vortekrenderer/include/vortek.h` | `VORTEK_SERVER_PATH` → `com.kyudo.ranmobile` | Native Vulkan socket path must match. |
| `cpp/gladiorenderer/include/gladio.h` | `X11_SERVER_PATH` → `com.kyudo.ranmobile` | Native X11 socket path must match. |
| `xenvironment/RootFSInstaller.java` | `install(...)` / `installIfNeeded(...)` param `MainActivity` → `AppCompatActivity` | Lets `RanLauncherActivity` bootstrap the rootfs; safe widening (only `Activity` APIs used). Existing callers still compile. |
| `XServerDisplayActivity.java` | `setupUI()`: when there is no shortcut, apply an optional `controls_profile_id` intent extra | Auto-applies the RAN touch-controls profile on an `exec_path` launch (mirrors the existing shortcut→`controlsProfile` path). |
| `res/values/strings.xml` | `app_name` → "RAN Online Mobile"; added `ran_*` and `save` strings | Branding + launcher strings. |

> **Why the namespace stays `com.winlator`:** the native libraries bind JNI symbols by class path
> (`Java_com_winlator_...`), and hundreds of layouts reference custom views as
> `com.winlator.widget.*`. Renaming the *namespace* would break every one of those bindings.
> Android separates the **installed application id** (`applicationId`) from the **code namespace**,
> so we rebrand the former and leave the latter — the smallest change that satisfies the identity
> requirement. **Consequence:** the three native `#define`s above must be kept in sync with
> `applicationId` and the native code rebuilt; if the app ever shows a black screen with no X
> socket, that mismatch is the first place to look.

## 3. New RAN components (`com.winlator.ran.*`)

| File | Role |
|---|---|
| `ran/RanLauncherActivity.java` | The dedicated home screen (LAUNCHER). Requests storage permission → installs the guest rootfs if needed → finds/creates the RAN container → launches the client. Hosts the Server, Graphics/Performance, and Diagnostics dialogs (Diagnostics runs a real TCP connection test to the server) and routes to Winlator's Settings/Controls and "Advanced (Winlator)". |
| `ran/RanConfig.java` | Config model + persistence for `RANMobile/config/server_config.json`. Owns the on-device RAN directory layout, the `GAME_EXE` / `GAME_DIRECTORY` / `GAME_ARGUMENTS` / server fields, and the graphics preset + HUD toggles. Includes filename sanitisation + path-traversal defense. |
| `ran/RanContainerProfile.java` | Find-or-create the dedicated RAN Wine container (DXVK, ALSA), map the client folder as drive `R:`, and launch the configured exe via the `exec_path` intent (with the RAN controls profile). |
| `ran/RanControls.java` | Imports and looks up the "RAN Online" touch-controls profile (Phase 5). |
| `ran/RanGraphics.java` | Performance/Balanced/Quality presets → container resolution + Box64 preset + DXVK; FPS/dev HUD toggles (Phases 6–7). |
| `res/layout/ran_launcher_activity.xml` | The launcher UI. |
| `assets/ran/ran_controls_default.icp` | The default RAN touch-controls layout (Winlator `ControlsProfile` JSON). |
| `config/server_config.json` | Sample developer config (checked in as a template; the live copy lives on-device). |
| `docs/*` | This document, the Winlator analysis, and the build guide. |

## 4. On-device layout

```
<external storage>/RANMobile/
  ├── client/                 ← USER copies their RAN Online client here (Update.exe, minia.exe, data/, ...)
  ├── config/server_config.json
  ├── logs/                   ← reserved for exported diagnostics (later phase)
  ├── cache/
  └── user/

<app data>/files/rootfs/      ← Winlator guest rootfs (Wine + libs), installed automatically
<app home>/home/xuser-<id>/   ← the RAN Wine container (prefix, drive_c, .container settings)
```

The RAN container maps drives: `D:`→Downloads, `E:`→app storage (Winlator defaults) and
`R:`→`RANMobile/client`. The client is launched as e.g. `R:\Update.exe`.

## 5. Launch sequence (RAN)

```
RanLauncherActivity.onPlay()
  → ensure storage permission
  → ensure RANMobile/ dirs
  → RootFS.isValid()?  (install rootfs if not, ask to retry)
  → RanConfig.isClientPresent()?  (else show "copy your client here" dialog)
  → RanContainerProfile.find()  →  create if missing (RAN-tuned JSON)
  → RanContainerProfile.startClient()  →  XServerDisplayActivity(container_id, exec_path=R:\Update.exe)
        → Winlator: box64 → wine explorer /desktop=nogui → winhandler.exe → Update.exe
```

## 6. RAN container defaults (first-run bias: compatibility)

| Setting | Value | Rationale |
|---|---|---|
| DX wrapper | DXVK | Client is Direct3D 9 → `d3d9.dll` → Vulkan. |
| Graphics driver | Winlator default (Vortek Vulkan + Gladio GL) | Broadest device compatibility without a vendor Turnip driver. |
| Box64 preset | STABILITY | Older engine + anti-cheat: correctness before FPS; switch to Performance later. |
| Audio | ALSA | Winlator default. |
| Screen | 1280×720 | Safe default; will become configurable (Graphics profiles phase). |
| wincomponents | Winlator default (d3d/dsound/dmusic/xaudio/vcrun2010 native) | Matches a typical DX9 client. |

## 6a. Mobile touch controls (Phase 5)

The default **RAN Online** control profile ships as `assets/ran/ran_controls_default.icp` in
Winlator's own `ControlsProfile` format and is imported once via `InputControlsManager.importProfile`
(so app updates never overwrite the user's tuning). It is auto-applied when Play launches the
client (via the `controls_profile_id` extra) and is fully editable in **Controls** (the Winlator
controls editor). Layout:

- **Left `D_PAD` → WASD** movement.
- **Right `TRACKPAD` → `MOUSE_MOVE_*`** for camera / mouse-look by dragging.
- **Buttons:** ATK (left mouse), USE (right mouse), TGT (Tab), PICK (Z), SIT (Insert), MENU (Esc),
  CHAT (Enter).
- **`RADIAL_MENU`** (left) → Inventory (I), Character (C), Skills (K), Map (M), Quest (Q).
- **8-slot hotbar** across the bottom → keys 1–8.

> Bindings are sensible defaults for a keyboard/mouse MMO; **tune them to your client's actual
> keys** in the Controls editor (RAN is heavily mouse-driven, so ATK/USE/TRACKPAD do most of the
> work; the WASD d-pad and number hotbar assume those keys are enabled in the client).

## 6b. Graphics / performance / HUD (Phases 6–7)

The **Graphics** launcher dialog applies a single Performance / Balanced / Quality choice to the RAN
container via `RanGraphics.apply()`:

| Preset | Resolution | Box64 preset | DX wrapper |
|---|---|---|---|
| Performance | 854×480 | PERFORMANCE | DXVK |
| Balanced (default) | 1280×720 | INTERMEDIATE | DXVK |
| Quality | 1600×900 | STABILITY | DXVK |

Same dialog toggles the **FPS overlay** (`FrameRating.Mode.SIMPLE`) and the **Developer HUD**
(`FrameRating.Mode.FULL` — CPU/RAM/GPU, Phase 21). The choice is stored in `server_config.json`
and re-applied to the container on every Play.

**On the game HUD (HP/SP/EXP/mini-map):** RAN's client renders these itself; they cannot be read
from outside the game, so RAN Mobile does not duplicate them. The "HUD/layout editor" the brief
asks for is Winlator's **Controls editor** — every on-screen control is movable, resizable, and
saved per profile — reached from the launcher's **Controls** button.

**Gamepad / keyboard / mouse (brief §6–7):** already provided by Winlator unchanged — physical
keyboards, mice and controllers are auto-detected, and a controller can be bound via the existing
`ExternalControllerBindingsActivity`; virtual-gamepad control profiles are also supported.

## 7. Not yet implemented (tracked for later phases)

- **GAME_ARGUMENTS** are stored but not yet forwarded (the `exec_path` path doesn't carry args).
  Planned via a generated `.desktop` shortcut, which supports `execArgs` (Phase 4/8).
- Patcher/update system (Phase 14); a full network-diagnostics screen beyond the current TCP test
  (Phase 15); log export ZIP + crash-recovery UI (Phases 16–17); first-run wizard (Phase 19).

## 8. Security notes

- No server credentials are hardcoded; `server_config.json` is user-owned and holds no passwords.
- `GAME_EXE` is sanitised to a bare filename and the resolved path is verified to stay inside the
  client directory (path-traversal defense in `RanConfig.getGameExecutableFile()`).
- No root required; no downloaded shell scripts executed; the game client stays user-supplied.
