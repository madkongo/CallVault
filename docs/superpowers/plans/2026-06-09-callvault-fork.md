# CallVault Fork Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fork `kitsumed/ShizuCallRecorder` into a renamed app "CallVault" and prove both-sides call recording works on a non-rooted OnePlus 12 / OxygenOS 16, then (Phase 2) make it survive reboots hands-free.

**Architecture:** Phase 1 ships a behavior-unchanged, license-compliant rename and validates the real recording pipeline on the target device over wireless adb. Phase 2 (separate plan, written after Phase 1's device results) adds embedded ADB-over-wireless-debugging self-start so Shizuku/shell auto-restarts on boot.

**Tech Stack:** Kotlin + Jetpack Compose · Gradle 9.4.1 (Kotlin DSL) · Android Gradle Plugin 8.x · JDK 17 · Shizuku · scrcpy-server v4.0 · Android SDK (compileSdk 36, minSdk 30).

---

## Scope Note

This plan covers **Phase 1 only** (toolchain → clone → baseline build → rename → device install → recording validation). Phase 2 (automation: PairingStore / AdbConnector / BootBridge / Keepalive) is intentionally deferred to its own plan because its implementation details depend on empirical answers from Phase 1 (does stock Shizuku work on OOS16; which audio source captures both sides; does scrcpy v4.0 work on this OEM build). Phase 1 produces a complete, working app on its own.

## Environment Facts (verified 2026-06-09)

- Repo root for this work: `/Users/kfirbaba/Desktop/Projects/callrecorder` (git initialized, branch `main`).
- JDK 17: installed via Homebrew at `/opt/homebrew/opt/openjdk@17/bin/java` (NOT on PATH; not symlinked to `/Library/Java`).
- Android SDK: `/Users/kfirbaba/Library/Android/sdk` — has `platforms/android-34`, `build-tools/34.0.0`, `platform-tools/adb`. **Missing** `cmdline-tools` (no `sdkmanager`), `platforms/android-36`, `build-tools/36.0.0`.
- `adb`: present at `/opt/homebrew/bin/adb` and in `platform-tools`.
- Upstream build expects: `./gradlew assembleDebug` → output `app/build/outputs/apk/debug/app-debug.apk`. Build downloads scrcpy-server v4.0 from GitHub at build time (network required).
- Rename targets: `settings.gradle.kts` (`rootProject.name`), `app/build.gradle.kts` (`applicationId`), `app/src/main/res/values/strings.xml` (`app_name`).

---

## Task 1: Set up the build toolchain

**Files:**
- Create: `~/.callvault-env.sh` (a sourceable env file for this project's shell sessions)

- [ ] **Step 1: Install Android command-line tools (provides `sdkmanager`)**

Run:
```bash
brew install --cask android-commandlinetools
```
Expected: installs to `/opt/homebrew/share/android-commandlinetools` (or reports "already installed"). `which sdkmanager` then resolves.

- [ ] **Step 2: Create and source the project env file**

Create `~/.callvault-env.sh` with this exact content:
```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="$(/opt/homebrew/opt/openjdk@17/bin/java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.home/{print $2}')"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```
Then run:
```bash
source ~/.callvault-env.sh && java -version && echo "JAVA_HOME=$JAVA_HOME"
```
Expected: prints `openjdk version "17.0.x"` and a non-empty `JAVA_HOME`.

- [ ] **Step 3: Accept SDK licenses and install platform 36 + build-tools 36**

Run:
```bash
source ~/.callvault-env.sh
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;36.0.0"
```
Expected: ends with `done`. Verify:
```bash
ls "$ANDROID_HOME/platforms" && ls "$ANDROID_HOME/build-tools"
```
Expected: lists include `android-36` and `36.0.0`.

- [ ] **Step 4: Commit the env helper reference (not the file itself — it's in $HOME)**

Add a note to the repo so the env setup is documented. Create `BUILDING.md` at repo root:
```markdown
# Building CallVault locally

1. `brew install --cask android-commandlinetools` (one-time)
2. Source the env: `source ~/.callvault-env.sh`
   - Sets JAVA_HOME (brew openjdk@17), ANDROID_HOME (~/Library/Android/sdk), PATH.
3. One-time SDK packages: `sdkmanager "platforms;android-36" "build-tools;36.0.0"`
4. Build: `cd app-src && ./gradlew assembleDebug`
5. APK: `app-src/app/build/outputs/apk/debug/app-debug.apk`
```
Run:
```bash
cd /Users/kfirbaba/Desktop/Projects/callrecorder && git add BUILDING.md && git commit -m "docs: local build setup notes"
```
Expected: one commit created.

---

## Task 2: Clone the upstream fork and verify a baseline build

This isolates "does upstream build in our environment?" from "did our rename break anything?" We build BEFORE renaming.

**Files:**
- Create: `app-src/` (cloned upstream repo, nested; kept separate from our docs)

- [ ] **Step 1: Clone upstream into `app-src/`**

Run:
```bash
cd /Users/kfirbaba/Desktop/Projects/callrecorder
git clone --depth 1 https://github.com/kitsumed/ShizuCallRecorder.git app-src
```
Expected: `app-src/` exists with `gradlew`, `app/`, `settings.gradle.kts`.

- [ ] **Step 2: Ignore the nested clone's build artifacts from our repo**

Append to `/Users/kfirbaba/Desktop/Projects/callrecorder/.gitignore`:
```
app-src/.gradle/
app-src/**/build/
app-src/local.properties
```
(We DO track `app-src/` source so our rename diff is reviewable; we ignore only build output.)

- [ ] **Step 3: Point the clone at our local SDK**

Create `app-src/local.properties`:
```properties
sdk.dir=/Users/kfirbaba/Library/Android/sdk
```

- [ ] **Step 4: Baseline build (unmodified upstream)**

Run:
```bash
source ~/.callvault-env.sh
cd /Users/kfirbaba/Desktop/Projects/callrecorder/app-src
./gradlew assembleDebug --no-daemon --stacktrace
```
Expected: `BUILD SUCCESSFUL`. Verify the APK:
```bash
ls -la app/build/outputs/apk/debug/app-debug.apk
```
Expected: file exists.

> If BUILD FAILS: read the first error. Common causes and fixes:
> - Missing SDK package → re-run Task 1 Step 3.
> - scrcpy-server download blocked → ensure network; the URL is `https://github.com/Genymobile/scrcpy/releases/download/v4.0/scrcpy-server-v4.0`.
> - AGP/Gradle JDK mismatch → confirm `JAVA_HOME` points at 17 (`./gradlew -version` shows "JVM: 17").
> Do not proceed to Task 3 until the baseline builds.

- [ ] **Step 5: Commit the vendored source + baseline-green marker**

Run:
```bash
cd /Users/kfirbaba/Desktop/Projects/callrecorder
git add app-src .gitignore
git commit -m "chore: vendor ShizuCallRecorder source (baseline build verified)"
```
Expected: commit includes `app-src/` source files (no `build/`).

---

## Task 3: Rename to CallVault (license-compliant) and rebuild

GPL-3.0 Section 7 forbids reusing the upstream name/package/logo. We change the installed `applicationId`, the app display name, and the Gradle project name, and add a NOTICE marking this as a modified fork. (The internal code `namespace` `com.kitsumed.shizucallrecorder` is left unchanged for Phase 1 to avoid a 40-file package move — it is not user-visible; revisit only if desired later.)

**Files:**
- Modify: `app-src/settings.gradle.kts:34`
- Modify: `app-src/app/build.gradle.kts:157` (`applicationId`)
- Modify: `app-src/app/src/main/res/values/strings.xml:308` (`app_name`)
- Create: `app-src/NOTICE.md`

- [ ] **Step 1: Rename the Gradle root project**

In `app-src/settings.gradle.kts`, change:
```kotlin
rootProject.name = "ShizuCallRecorder"
```
to:
```kotlin
rootProject.name = "CallVault"
```

- [ ] **Step 2: Change the installed application id**

In `app-src/app/build.gradle.kts`, inside `defaultConfig`, change:
```kotlin
        applicationId = "com.kitsumed.shizucallrecorder"
```
to:
```kotlin
        applicationId = "com.kfir.callvault"
```
(Leave `namespace = "com.kitsumed.shizucallrecorder"` unchanged — it is the code package, not the installed id. The Shizuku provider authority and all intents derive from `applicationId`/`packageName`, so they follow this change automatically.)

- [ ] **Step 3: Change the app display name**

In `app-src/app/src/main/res/values/strings.xml`, change:
```xml
    <string name="app_name" translatable="false">ShizuCallRecorder</string>
```
to:
```xml
    <string name="app_name" translatable="false">CallVault</string>
```

- [ ] **Step 4: Add a fork NOTICE (marks modified version, per GPL-3.0 §4–5)**

Create `app-src/NOTICE.md`:
```markdown
# CallVault

CallVault is a modified fork of ShizuCallRecorder
(https://github.com/kitsumed/ShizuCallRecorder), Copyright (C) kitsumed (Med),
licensed under GNU GPL-3.0 with additional Section 7 terms (see LICENSE).

This is a MODIFIED version, distinct from the original. It is not endorsed by,
affiliated with, or supported by the original author. The names, trademarks, and
logos of the original project are the property of their respective owner and are
not used here beyond this attribution.

CallVault remains licensed under GPL-3.0. Source: this repository.
```

- [ ] **Step 5: Rebuild after rename**

Run:
```bash
source ~/.callvault-env.sh
cd /Users/kfirbaba/Desktop/Projects/callrecorder/app-src
./gradlew assembleDebug --no-daemon --stacktrace
```
Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 6: Verify the APK identity is the new package + name**

Run:
```bash
source ~/.callvault-env.sh
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
"$AAPT" dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "package: name|application-label:"
```
Expected: `package: name='com.kfir.callvault'` and `application-label:'CallVault'`.

- [ ] **Step 7: Commit the rename**

Run:
```bash
cd /Users/kfirbaba/Desktop/Projects/callrecorder
git add app-src/settings.gradle.kts app-src/app/build.gradle.kts app-src/app/src/main/res/values/strings.xml app-src/NOTICE.md
git commit -m "feat: rebrand fork to CallVault (com.kfir.callvault)"
```
Expected: one commit.

---

## Task 4: Install on the OnePlus 12 over wireless adb and complete in-app setup

**Files:** none (device operations).

**Prerequisite:** Phone and Mac on the same Wi-Fi. Wireless debugging enabled on the phone (Developer options). Shizuku already configured per the user's existing setup.

- [ ] **Step 1: Connect to the phone over wireless adb**

On the phone: Developer options → Wireless debugging → note the **IP address & port** shown on that screen (this is the connect port, distinct from the pairing port). Then run:
```bash
source ~/.callvault-env.sh
adb connect <PHONE_IP>:<CONNECT_PORT>
adb devices
```
Expected: `adb devices` lists the device as `device` (not `offline`/`unauthorized`).

> If `unauthorized`/`offline`: re-pair using `adb pair <PHONE_IP>:<PAIR_PORT>` with the 6-digit code from "Pair device with pairing code", then `adb connect` again.

- [ ] **Step 2: Install the CallVault debug APK**

Run:
```bash
adb install -r /Users/kfirbaba/Desktop/Projects/callrecorder/app-src/app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`.

- [ ] **Step 3: Launch and complete onboarding**

Run:
```bash
adb shell monkey -p com.kfir.callvault -c android.intent.category.LAUNCHER 1
```
On the phone: accept the disclaimer; on the Permissions screen tap **Grant Access** and approve **Notifications, Contacts, Phone State, Call Log, Battery Optimization**, select a **recording folder**, and authorize **Shizuku** when prompted.
Expected: app reaches its main screen with Shizuku shown as available/running.

- [ ] **Step 4: Confirm the app sees shell permissions (no system-limitation crash)**

Run:
```bash
adb logcat -d | grep -iE "callvault|shizuku|shell" | tail -30
```
Expected: no fatal "shell application does not have the required permissions" message; app is running.

---

## Task 5: Validate both-sides recording (Phase 1 exit gate) + tune audio source

**Files:** none (device + manual verification).

- [ ] **Step 1: Start a live logcat capture for the test**

In a dedicated terminal:
```bash
source ~/.callvault-env.sh
adb logcat -c && adb logcat | grep -iE "callvault|recording|scrcpy|shizuku|audio"
```
Leave running during the test call.

- [ ] **Step 2: Place an outgoing test call and let it auto-record**

In CallVault settings, enable **Automatically record outgoing calls** (and set audio source to **Voice call** first). Call a number you control (or voicemail), speak from both ends ~15s, hang up.
Expected (logcat): recording start, scrcpy audio capture begins, recording stop on call end; a file is written to the chosen folder.

- [ ] **Step 3: Pull and listen to the recording**

Find the file (it saves to the SAF folder you chose). To pull from internal storage path shown in logs:
```bash
adb pull "<path-from-logcat>" ./test-recording
```
Or open it in a media player on the phone.
Expected: an audio file that plays.

- [ ] **Step 4: The decisive check — both sides audible**

Listen to the recording.
- ✅ **Both parties clear** → Phase 1 exit criterion met. Record the working audio source in `app-src/NOTICE.md` or a new `docs/DEVICE-NOTES.md`. Proceed to commit, then Phase 2 planning.
- ⚠️ **One side / muffled / silent** → run the **audio-source tuning matrix** (Step 5).

- [ ] **Step 5: Audio-source tuning matrix (only if Step 4 not both-sides)**

In CallVault settings, change **Audio source** and re-test a call for each, in this order, until both sides are clean:
1. `Voice call` (both sides, raw) — already tried.
2. `Audio output (remote submix)`.
3. `Voice communication mic`.
4. `Standard microphone`.
Also try switching **Audio codec** to **AAC** (settings note: more OEM-compatible than Opus).
If none work with stock Shizuku, install the recommended **thedjchi Shizuku fork** and repeat the matrix.
Record the outcome of each attempt in `docs/DEVICE-NOTES.md`.

- [ ] **Step 6: Record device findings and commit**

Create `/Users/kfirbaba/Desktop/Projects/callrecorder/docs/DEVICE-NOTES.md` documenting: OOS16 build, working audio source + codec, Shizuku variant used, and any limitation observed. Run:
```bash
cd /Users/kfirbaba/Desktop/Projects/callrecorder
git add docs/DEVICE-NOTES.md
git commit -m "docs: OnePlus 12 OOS16 recording validation results"
```
Expected: one commit. **This closes Phase 1.**

---

## Phase 2 (deferred — separate plan)

After Phase 1's exit gate, write a Phase 2 plan covering the automation layer from the design spec:
- **PairingStore** — persist the adb-tls keypair from a one-time in-app wireless-debugging pairing.
- **AdbConnector** — mDNS discovery of `_adb-tls-connect._tcp` + TLS connect using stored keys.
- **BootBridge** — `BOOT_COMPLETED` receiver → brief foreground service → bring shell up → hand off to existing pipeline.
- **Keepalive/setup** — battery-exemption + OnePlus auto-launch flow + watchdog.
- **Fallback** — Quick Settings tile / notification action for one-tap re-arm if self-start is unreliable on OOS16.

These tasks are TDD-suitable (unit tests for PairingStore persistence, AdbConnector port discovery with a mock transport, BootBridge receiver wiring) and will be specified with full code once Phase 1 confirms the recording path and Shizuku variant.

---

## Self-Review Notes

- **Spec coverage:** Phase 1 covers spec sections Target Environment, Licensing Constraint, Phase 1 MVP, Build/Test Loop, and the OEM-breakage + Shizuku-variant risks. Phase 2 spec items are explicitly deferred with a named follow-up plan (not dropped).
- **No placeholders:** every step has exact commands/paths/expected output; rename steps show exact before/after text.
- **Consistency:** `applicationId = com.kfir.callvault` and app name `CallVault` are used identically across Tasks 3–5 (install, launch, badging check).
