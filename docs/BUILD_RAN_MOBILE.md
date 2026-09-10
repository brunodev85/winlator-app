# Building RAN Online Mobile

RAN Online Mobile is a standard Winlator (Android/Gradle/NDK) build. It **cannot be built on a
plain Windows/x86 machine without the Android toolchain**, and it can only be *run* on an Android
ARM64 device (Wine/Box64 are ARM64-only). Choose one of the two paths below.

## Option A — Cloud build (recommended, no local toolchain) ⭐

A GitHub Actions workflow (`.github/workflows/build-ran-apk.yml`) builds the debug APK for you in
the cloud. You only need a free GitHub account.

1. Create an empty repo on GitHub (e.g. `ran-online-mobile`). It can be private.
2. From the app project folder (`winlator/app`), push this branch to it:

   ```bash
   cd winlator/app
   git remote add ranmobile https://github.com/<your-user>/ran-online-mobile.git
   git push -u ranmobile ran-mobile-development
   ```

3. On GitHub open the repo → **Actions**. The "Build RAN Online Mobile APK" workflow starts
   automatically (or click **Run workflow**). It takes ~10–20 min.
4. When it finishes (green check), open the run and download the artifact
   **RAN-Online-Mobile-debug-apk** — it contains `app-debug.apk`. Copy it to your phone and install
   (enable "Install unknown apps" for your file manager/browser).

> Nothing secret is uploaded — the RAN game client is never committed; you copy it to the phone
> separately (see §5). A private repo keeps the source private if you prefer.

## Option B — Local build (Android Studio)

## 1. Prerequisites (exact versions)

| Tool | Version | Notes |
|---|---|---|
| JDK | **17** | AGP 8.4.2 requires JDK 17 (NOT 21/22+). `java -version` must show 17. |
| Android SDK Platform | **API 35** | `compileSdk 35`. |
| Android SDK Build-Tools | 34/35 | Installed by Android Studio. |
| NDK | **24.0.8215888** | Pinned by `build.gradle`. Install via SDK Manager → "NDK (Side by side)". |
| CMake | **3.22.1** | Pinned by `build.gradle`. Install via SDK Manager. |
| Gradle | **8.14.5** | Provided by the wrapper (`gradlew`) — do not install separately. |
| Git | any | For submodules. |
| Disk | ~30–50 GB free | SDK + NDK + build cache. |

Target device/emulator: **arm64-v8a** only (the APK builds no other ABI), Android 8.0+ (minSdk 26).
Vulkan-capable GPU recommended.

## 2. Get the source (with submodules)

```bash
git clone https://github.com/brunodev85/winlator.git
cd winlator
git submodule update --init            # fetches the app / vortek / gladio submodules
cd app                                 # the Gradle root project is winlator/app
git checkout ran-mobile-development    # the RAN branch (this work)
```

> The buildable Gradle project root is **`winlator/app/`** (open THIS folder in Android Studio),
> not the outer meta-repo. The RAN code lives in the `app` submodule on branch
> `ran-mobile-development`.

## 3. Point Gradle at your SDK

Create `winlator/app/local.properties` (not committed):

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Android Studio writes this for you when you open the project. Ensure the pinned NDK
(24.0.8215888) and CMake (3.22.1) are installed via **SDK Manager → SDK Tools**.

## 4. Build the debug APK

From `winlator/app/`:

```bash
./gradlew assembleDebug
```

(Windows: `gradlew.bat assembleDebug`.) First build downloads Gradle 8.14.5 and dependencies and
compiles the native code — allow 10–30+ minutes.

**Output APK:**

```
winlator/app/app/build/outputs/apk/debug/app-debug.apk
```

Install to a connected device:

```bash
./gradlew installDebug
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Note: the current `build.gradle` defines only a `debug` build type (with `minifyEnabled true`).
> A signed `release` build needs a `release {}` block + signing config added first.

## 5. First run on the device

1. Launch **RAN Online Mobile**. Grant storage permission. It installs the guest rootfs
   (one-time, downloads/extracts several hundred MB from the APK assets).
2. Copy your Windows RAN client (e.g. the whole `KYUDO DEVELOPMENT CLIENT` folder) to:
   `/sdcard/RANMobile/client/`  (create the folder if needed).
3. Open **Server** on the launcher and confirm the executable (default `Update.exe`).
4. Tap **PLAY RAN ONLINE**. The launcher creates the RAN container on first play, then boots the
   client through Wine/Box64.

## 6. Troubleshooting the build

- **"Namespace not specified" / package errors** — you're on the wrong Gradle root; open
  `winlator/app`, not the outer folder.
- **NDK/CMake "not found"** — install the exact pinned versions via SDK Manager.
- **JDK errors from AGP** — you're not on JDK 17. Set Gradle JDK in Android Studio → Settings →
  Build Tools → Gradle → Gradle JDK = 17.
- **Native build fails referencing `com.winlator` paths** — verify the `applicationId` in
  `build.gradle` and the three native `#define`s (see `docs/RAN_MOBILE_ARCHITECTURE.md` §2) all
  read `com.kyudo.ranmobile`.

## 7. Runtime troubleshooting (device)

- **Black screen after Play** — likely a native socket path mismatch (see §2 of the architecture
  doc) or a graphics-driver incompatibility; try switching the RAN container's graphics driver in
  "Advanced (Winlator) → Containers".
- **Client not found** — the files aren't under `/sdcard/RANMobile/client/`, or `GAME_EXE` is
  mis-set in Server settings.
- **Anti-cheat blocks launch** — HackShield/MShield frequently refuses to run under Wine; this is
  a client/server-side matter, not an app bug.
