# CallVault Unified — Plan 4: Shizuku Removal + Hands-Free Boot

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** (A) Delete the now-dormant Shizuku code so CallVault is a clean single app, and (B) make recording survive a reboot **hands-free** (auto-reconnect, no taps), with a one-tap fallback if the OEM interferes.

**Architecture:** The recording transport is already embedded ADB (Plans 1–3); Shizuku code is dormant. Part A removes it. Part B adds a `BOOT_COMPLETED` bridge + a watchdog that (re)establishes `AdbShell.ensureConnected` after boot/connection-drop, gated behind battery/auto-launch onboarding, with a Quick-Settings-tile fallback. **The reboot behaviour is OEM-dependent, so Part B starts with an empirical on-device test that determines how much machinery is actually needed.**

**Tech Stack:** Kotlin · existing `AdbShell`/`AdbConnectionManager`/`AdbMdns` · `BOOT_COMPLETED` receiver + foreground service · WorkManager (already a dep) · Quick Settings TileService.

---

## Decomposition (Plan 4 of 4)

- Plans 1–3 (done): embedded-ADB transport, records real calls, Drive routing.
- **Plan 4 (this doc):** Part A Shizuku removal · Part B hands-free boot.

## What is "real Shizuku usage" (Part A scope — ignore package-name/copyright matches)

`grep shizuku` mostly hits the app package `com.kitsumed.shizucallrecorder` and the file-header
copyright — DO NOT touch those (renaming the source package is out of scope). Remove only the real
integration:
- `integrations/shizuku/ShizukuConnectionManager.kt` — delete.
- `services/ShellService.kt`, `aidl/.../IShellService.aidl`, `aidl/.../ILogCallback.aidl` — delete.
- `RecordingForegroundService.kt` — remove `shizukuManager`, `tryStartShizukuServer()`, the
  `ShizukuConnectionManager.startServer/stopServer` calls, and the Shizuku-pref checks. Keep the ADB path.
- `data/AppPreferences.kt` — remove `SHIZUKU_AUTO_MANAGE`, `SHIZUKU_START_ON_RECORD`,
  `SHIZUKU_KEEP_ALIVE`, `SHIZUKU_AUTH_KEY` (DefaultsValue + Key + getters/setters).
- `ui/screens/SettingsScreen.kt` + `ui/viewmodels/SettingsViewModel.kt` — remove the Shizuku settings
  section (auto-manage / start-on-record / keep-alive / auth-key) and its actions.
- `system/SystemIntentHelpers.kt` — remove `openShizukuManager()` and other Shizuku helpers.
- `AppUrls.kt` — remove `SHIZUKU_WEBSITE` (and any Shizuku wiki links no longer used).
- `utils/AppLogger.kt` — remove `Shizuku.getLatestServiceVersion()` from `exportReport`; remove the
  `ILogCallback`-based remote logging (`callback`, `initAsRemote`, the IPC path) that only existed for
  `ShellService`.
- `app/build.gradle.kts` — remove `implementation(libs.shizukuApi)` + `libs.shizukuProvider`; consider
  setting `buildFeatures { aidl = false }` once the AIDL files are gone.
- `gradle/libs.versions.toml` — remove the `shizuku-api`/`shizuku-provider` versions + library aliases.
- `AndroidManifest.xml` — remove `<uses-permission moe.shizuku.manager.permission.API_V23>`, the
  `rikka.shizuku.ShizukuProvider` `<provider>`, and reword the FGS `specialUse` property text away from
  "via Shizuku".
- `spike/` package (AdbSpikeActivity, AdbPairingService, AdbMdns, SpikeAdbManager, SpikeActions,
  SpikeLog) + its two manifest entries — delete.
- Clean now-unused Shizuku imports flagged by the compiler.

---

## Task A1: Remove the spike package + manifest entries
**Files:** delete `java/.../spike/*`; edit `AndroidManifest.xml`.
- [ ] Delete the `spike/` directory and remove the `<activity .spike.AdbSpikeActivity>` and
  `<service .spike.AdbPairingService>` manifest entries.
- [ ] `./gradlew :app:compileDebugKotlin` → fix any references (there should be none outside spike).
- [ ] Commit `chore: remove throwaway ADB spike package`.

## Task A2: Strip Shizuku from the recording service + prefs
**Files:** `RecordingForegroundService.kt`, `AppPreferences.kt`.
- [ ] Remove `shizukuManager` field, `tryStartShizukuServer()`, the `ShizukuConnectionManager.*` calls
  in `onDestroy`/standby/start, and the Shizuku-pref reads. The standby `ACTION_STANDBY` path can simply
  pre-warm via `AdbShell.ensureConnected` on an IO thread instead of starting Shizuku.
- [ ] Remove the four `SHIZUKU_*` prefs (DefaultsValue + Key + accessors).
- [ ] Build `:app:assembleDebug`. Commit `refactor: drop Shizuku from recording service + prefs`.

## Task A3: Remove Shizuku settings UI + helpers + URLs + logger refs
**Files:** `SettingsScreen.kt`, `SettingsViewModel.kt`, `SystemIntentHelpers.kt`, `AppUrls.kt`, `AppLogger.kt`.
- [ ] Remove the Shizuku settings section + ViewModel actions; remove `openShizukuManager()` + Shizuku
  URL constants; in `AppLogger.exportReport` drop the `Shizuku.getLatestServiceVersion()` line and remove
  the `ILogCallback` remote-logging members (now unused).
- [ ] Build. Commit `refactor: remove Shizuku settings, intent helpers, logger hooks`.

## Task A4: Delete Shizuku classes, AIDL, deps, manifest provider
**Files:** delete `ShizukuConnectionManager.kt`, `ShellService.kt`, `IShellService.aidl`,
`ILogCallback.aidl`; edit `build.gradle.kts`, `libs.versions.toml`, `AndroidManifest.xml`.
- [ ] Delete the classes + AIDL. Remove the Shizuku Gradle deps + catalog aliases + the manifest
  `<provider>` and `API_V23` permission; reword the FGS property text. Set `buildFeatures.aidl = false`
  if no AIDL remains.
- [ ] `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. Clean any leftover unused imports.
- [ ] **On-device smoke test:** install, record a call — confirm it still works with all Shizuku code gone.
- [ ] Commit `feat: remove Shizuku entirely — CallVault is now a single self-contained app`.

---

## Task B0: GATE — empirical reboot behaviour (decides Part B design)
No code. On the OnePlus 12, with Wireless debugging ON and ADB already paired:
- [ ] **Reboot.** After boot, WITHOUT opening the app, check: is **Wireless debugging still ON**?
  (`adb shell settings get global adb_wifi_enabled` or observe the toggle / `_adb-tls-connect._tcp` via
  `adb mdns services`.)
- [ ] **Without launching the app**, place an incoming call. Does `PhoneStateReceiver` wake the app and
  record (the manifest receiver can start the process; `RecordingForegroundService` calls
  `AdbShell.ensureConnected` which mDNS-reconnects with the persisted key)? Note how much of the call
  start is missed while connecting.
- [ ] Record findings → choose the Part B path:
  - **If WD persists + per-call reconnect works:** Part B = a lightweight boot **pre-warm** (connect at
    boot to remove first-call latency) + a watchdog. No re-pairing ever.
  - **If WD persists but first-call audio is clipped:** add the boot pre-warm so the connection is hot.
  - **If WD turns OFF on reboot:** hands-free is impossible without user action → Part B = the **QS-tile /
    notification one-tap** to re-enable + reconnect, plus clear guidance. (This is the pre-agreed fallback.)

## Task B1: BootBridge — reconnect on boot
**Files:** create `services/boot/BootReceiver.kt` + `services/boot/AdbConnectionService.kt`; manifest.
- [ ] `BootReceiver` (`RECEIVE_BOOT_COMPLETED` permission + `<receiver>` for `BOOT_COMPLETED` and
  `QUICKBOOT_POWERON`) starts a short foreground service.
- [ ] `AdbConnectionService` (foreground, `specialUse`): runs `AdbShell.ensureConnected` on an IO thread;
  on success, stop (or stay resident if a persistent connection proves necessary — decide from B0).
- [ ] Build. On-device: reboot, confirm via logcat the service connects without any taps.
- [ ] Commit `feat(boot): reconnect ADB after reboot (BootReceiver + connection service)`.

## Task B2: Watchdog — re-establish on drop
**Files:** reuse WorkManager (a periodic check) or a lightweight monitor in `AdbConnectionService`.
- [ ] Add a watchdog that, while the app is expected to be ready, periodically verifies
  `AdbConnectionManager.isConnected` and calls `ensureConnected` if dropped. Keep it cheap (e.g. a
  periodic WorkManager job, or check at call-start which already happens).
- [ ] Commit `feat(boot): watchdog re-establishes ADB connection if it drops`.

## Task B3: OEM survivability onboarding
**Files:** the permissions/settings UI.
- [ ] Add guidance/actions: battery-optimization exemption (already a permission), and OnePlus/OOS
  **auto-launch / "allow background activity"** whitelisting (deep-link to the OEM settings where
  possible; otherwise instructions). Explain that Wireless debugging must stay ON.
- [ ] Commit `feat(boot): OEM auto-launch + battery whitelisting onboarding`.

## Task B4: Quick-Settings-tile fallback
**Files:** create `services/boot/ReconnectTileService.kt`; manifest `<service>` with `TileService`.
- [ ] A QS tile that, on tap, runs `AdbShell.ensureConnected` (and deep-links to Wireless debugging if
  the connect fails because WD is off). One tap restores recording readiness after a reboot if the
  automatic path was blocked.
- [ ] On-device: add the tile, reboot, tap it, confirm reconnect.
- [ ] Commit `feat(boot): Quick Settings tile to reconnect in one tap (fallback)`.

## Task B5: Full hands-free reboot validation
- [ ] Reboot with everything configured; with **zero taps**, place an incoming and an outgoing call and
  confirm both record with both sides audible. If the automatic path is blocked by the OEM, confirm the
  one-tap tile path works. Record results.

**Exit criterion:** after a reboot (Wireless debugging left ON), a call is auto-recorded hands-free; or,
if the OEM disables WD on reboot, a single tap (tile) restores it — documented clearly.

## Risks
- **WD persistence across reboot (OOS16):** the central unknown — Task B0 settles it before building.
- **OEM background-start limits:** `BOOT_COMPLETED`/FGS may be throttled until whitelisted (B3); the
  QS-tile (B4) is the guaranteed fallback.
- **Shizuku removal regressions:** Part A touches many files; rebuild + a real-call smoke test after A4.
