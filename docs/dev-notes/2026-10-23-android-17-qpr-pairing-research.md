📐 CALCULATED 2026-10-23 — desk research, nothing measured on a device.

⏸️ PARKED 2026-10-24 by the maintainer: QPR3 Beta 2 only. No fix until a stable release hides the setting too.

# Android 17 QPR3 Beta 2: pairing stuck on "Waiting for wireless debugging"

Field report: Pixel 9 Pro, "Android 17 QPR3 Beta 2". Tapping Pair leaves the pairing notification on
"Waiting for wireless debugging… Turn on wireless debugging to start pairing". It never moves on, so the
"Enter pairing code" notification never appears.

## Short answer

- **Most likely cause:** from QPR3 Beta 2, apps can no longer read `adb_wifi_enabled`. It reads as 0 (or
  throws), whatever the switch says. That is the same treatment `adb_enabled` and
  `development_settings_enabled` got in QPR1.
- Our pairing service only starts mDNS discovery after that setting reads 1. On this build it never will.
  So it waits five minutes and gives up. That matches the report exactly.
- Status: **CLAIMED by two independent sources** (one app developer who tested on a tester's phone, one
  LADB user). No AOSP source exists for QPR3, so it cannot be confirmed from code. No maintainer of a
  big project has confirmed it yet.
- The reporter's naming is right. QPR3 Beta 2 is a real build: `DP11.261002.005`, released 2026-10-20.
- **Fix for pairing:** start discovery straight away, as upstream Shizuku does. Never gate it on
  `adb_wifi_enabled`. The pairing service only exists while the user has the "Pair with code" dialog open,
  so discovery itself is the right signal.
- **Bigger risk:** about 25 other places in CallVault read the same setting. On this build the reconnect
  path will write 1, read back 0, and report `WIRELESS_DEBUGGING_REFUSED`. So already-paired users on
  QPR3 Beta 2 probably cannot record either. 📐 Read from code, not seen on a device.
- **Workaround today:** none inside CallVault's own pairing flow. Shizuku mode is the best bet (one user
  says Shizuku still connects on this build). Untested with CallVault.

## The build

- QPR3 Beta 1: `DP11.260918.006` (most Pixels), `DP11.260918.005` (Pixel 11 series), released 2026-10-02.
  Sources: [droid-life](https://www.droid-life.com/2026/10/02/android-17-qpr3-beta-1-released-for-pixel-phones/),
  [HMA-OSS #165](https://github.com/frknkrc44/HMA-OSS/issues/165),
  [Iconify #980](https://github.com/Mahmud0808/Iconify/issues/980).
- QPR3 Beta 2: `DP11.261002.005`, released 2026-10-20, all supported Pixels (6a to 11, so the Pixel 9 Pro
  is included). Source:
  [droid-life](https://www.droid-life.com/2026/10/20/android-17-qpr3-beta-2-released-for-pixel-phones/).
- The `D` prefix is normal: QPR3 betas are built from the next release's branch. It is not Android 18.
- Google's Beta 2 changelog says nothing about ADB, Developer options or settings.
- Ask the reporter for Settings › About phone › Build number to be sure it is `DP11.261002.005`.

## Q1. Is `adb_wifi_enabled` now hidden from apps?

### Evidence that it is (QPR3 Beta 2 only)

- **mwilky/hilight-plus, commit
  [`415a6e4`](https://github.com/mwilky/hilight-plus/commit/415a6e4093da711865327b7d651bf75f7879bb94),
  2026-10-21.** Commit message: *"From QPR3 Beta 2, apps read 0 for adb_wifi_enabled whatever the switch
  says, as they already did for Developer options. Setup took that as Wireless debugging off, stayed on
  its second step and never looked for the pairing dialog, so a fresh install couldn't connect at all."*
  Says it was tested: *"setup on a QPR3 Beta 2 tester's Pro XL"*. That is our bug, word for word.
- **[tytydraco/LADB #147](https://github.com/tytydraco/LADB/issues/147), 2026-10-22, build
  `DP11.261002.005`.** *"it is no longer possible for regular apps to read the setting `adb_wifi_enabled`.
  it produces a big fat permission error."* Writing still works: *"you can see the toggle flip back and
  forth on the Developer Options settings page as LADB starts up."* And: *"Shizuku connects fine."*
  No maintainer reply yet.
- LADB targets SDK 36, like us. So staying on target 36 does not escape it.
- LADB reads it exactly as we do: `Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1`
  ([ADB.kt:285](https://github.com/tytydraco/LADB/blob/master/app/src/main/java/com/draco/ladb/utils/ADB.kt)).

### Reads 0, or throws? Unclear, and it does not matter for us

- hilight-plus says it reads 0. That fits the `@Readable(redactedValue = "0")` mechanism from QPR1.
- The LADB user says "permission error". That fits a `SecurityException` ("not readable"), which is
  what Android throws when a hidden setting loses `@Readable`.
- Our `AdbShell.isWirelessDebuggingEnabled` wraps the read in `runCatching { … }.getOrDefault(false)`.
  Either way we get `false`.
- Whether the ContentObserver still fires is unknown. It does not matter: our `onChange` re-reads the
  setting, and the re-read is false too.

### Evidence that stable builds are fine

- [hybriqtech/adbuddy #98](https://github.com/hybriqtech/adbuddy/pull/98), 2026-10-19, measured on a
  Pixel 9 Pro XL on Android 17: `adb_enabled` reads a false 0, but *"`adb_wifi_enabled` se lee bien"*
  (reads fine).
- [jemcik/JemRec #24](https://github.com/jemcik/JemRec/pull/24), 2026-10-20, QPR2 emulator
  `CP41.260831.007`: *"`adb_wifi_enabled` isn't hidden."*
- Our own issue #40 note (2026-09-19) read `android-17.0.0_r1` source: `ADB_WIFI_ENABLED` is a plain
  `@Readable`.

### AOSP source

- There is no AOSP tag for QPR2 or QPR3. The only Android 17 tags are `android-17.0.0_r1`,
  `android-security-17.0.0_r1/_r2`, CTS and VTS (checked
  [frameworks/base refs](https://android.googlesource.com/platform/frameworks/base/+refs) 2026-10-23).
- AOSP `main` still shows `ADB_WIFI_ENABLED` as plain `@Readable`, `@hide`. `main` is stale under
  Google's quarterly source drops, so this proves nothing about QPR3.
- `ADB_WIFI_ENABLED` is `@hide`, so the public
  [Settings.Global docs](https://developer.android.com/reference/android/provider/Settings.Global) do not
  list it. Only `ADB_ENABLED` and `DEVELOPMENT_SETTINGS_ENABLED` carry the "will always return 0 for all
  third-party apps" note.
- So the mechanism cannot be confirmed from source until Google publishes QPR3 code.

### Side note: custom ROMs already hide it per app

- Several custom ROMs (Evolution X, YAAP, RisingOS lineage, HertzifyOS) carry a "hide developer status"
  patch. It returns 0 for `adb_enabled`, `adb_wifi_enabled` and `development_settings_enabled` to apps the
  user picks. Example:
  [HertzifyOS `65b0c33`](https://github.com/HertzifyOS/android_frameworks_base/commit/65b0c336ae5f3cc8a4fd275e482586cb1d80c53b),
  2026-10-23. If a user hides CallVault there, the same bug appears on any Android version.

### Confidence

- **CLAIMED, strong:** two independent reports, one with a named build and one from a developer who
  tested on a device. Same symptom in both.
- **SPECULATION:** whether it ships in QPR3 stable (expected around March 2027). It could be a flag only
  on in betas. QPR1's redaction did ship to stable, so plan for it shipping.

## Q2. Other QPR2 / QPR3 pairing changes

- Nothing else found for QPR2 or QPR3 specifically. No reports of changed pairing service type, broken
  pairing notifications, RemoteInput, or foreground-service limits.
- Already known (see `android-17-2026-10-20/platform-aosp-and-docs.md`): mDNS advertising moved into
  system_server with new service names; `adb_wifi_enabled` stays 1 when Wi-Fi drops ("ADB Wi-Fi 2.0").
  We match on service type only, so the new names should not matter.
- Still open: [thedjchi/Shizuku PR #226](https://github.com/thedjchi/Shizuku/pull/226) saw the pairing
  service bound to the LAN address, not loopback, on QPR1 betas. We pair at `127.0.0.1`
  (`AdbPairingService.kt` `pair("127.0.0.1", port, code)`). Once discovery starts, this is the next thing
  that could stop pairing on a beta. **SPECULATION** for QPR3.
- [porter #168](https://github.com/d4rken-org/porter/issues/168) "stuck at searching for pairing
  service" is Android 12 ColorOS. Unrelated.

## Q3. How others fixed it

| Project | Approach | Link |
|---|---|---|
| RikkaApps/Shizuku (upstream) | Never reads the setting in pairing. `AdbPairingService.onStart` starts mDNS discovery straight away. Explains "Shizuku connects fine". | [AdbPairingService.kt](https://github.com/RikkaApps/Shizuku/blob/master/manager/src/main/java/moe/shizuku/manager/adb/AdbPairingService.kt) |
| mwilky/hilight-plus | `isWirelessDebuggingOn()` returns `true` or `null`, never `false`. Null means "can't tell". Setup goes straight to pairing. Finding the pairing service proves both switches are on. Starts always write `adb_wifi_enabled=1` (harmless if already on) and then just try to connect. Removed the "Android switched it back off" read-back. | [`415a6e4`](https://github.com/mwilky/hilight-plus/commit/415a6e4093da711865327b7d651bf75f7879bb94) |
| LADB | Not fixed. The reporter suggests ignoring the error and connecting anyway. | [#147](https://github.com/tytydraco/LADB/issues/147) |
| JemRec, porter, anyapk, Shizuku forks | Fixed only `adb_enabled` / dev options (QPR1). They treat 0 on SDK 37+ as UNKNOWN. Same pattern, needs extending to `adb_wifi_enabled`. | [JemRec #24](https://github.com/jemcik/JemRec/pull/24) |

### Cleanest fix for us (proposal, not built)

1. **Pairing service:** call `beginDiscovery()` on every start. Drop the `adb_wifi_enabled` gate. The
   pairing service only appears while the user has "Pair device with pairing code" open, so discovery
   waits for the right moment by itself.
2. Keep the waiting text, but make it true on every build: "Open Wireless debugging › Pair device with
   pairing code". Swap to "Enter pairing code" when the port is found, as today.
3. Keep the ContentObserver only as a hint for older builds, never as a gate.
4. **Everywhere else:** make the read three-state, like hilight-plus and our own `DeveloperOptions`
   handling of `adb_enabled`. On SDK 37+, a 0 means UNKNOWN, not OFF. Only a 1 is trusted.
5. **Reconnect path (`AdbShell.connectViaWirelessDebuggingWithReason`):** on SDK 37+ do not return
   `WIRELESS_DEBUGGING_REFUSED` because the read-back is 0. Go to mDNS discovery of `_adb-tls-connect`
   instead. Finding it proves Wireless debugging is on. Only call it refused if discovery also finds
   nothing. The read-back was already unreliable on 17 because of ADB Wi-Fi 2.0.
6. **`releaseWirelessDebugging`:** it returns early when the read is off. On QPR3 that leaves Wireless
   debugging on after we borrowed it. It must not decide from the read alone. Use our own
   "we enabled it" flag.
7. **UI and health checks** (`PermissionsScreen`, `SetupPrerequisites`, `ReadinessNotice`,
   `MicOpAutoHeal`, `DaemonKeepAliveService`, `tryHealWriteSecureSettings`): treat UNKNOWN as "may be on".
   Never tell the user "turn on Wireless debugging" from a 0 alone on SDK 37+.
8. Do not gate on `SDK_INT` and a build name. Per [[android-17-hides-debugging-settings]], gate on the
   value: 1 is ON, 0 on SDK 37+ is UNKNOWN.

Callers to touch (from `grep isWirelessDebuggingEnabled`): `AdbPairingService.kt` 112, 145;
`AdbShell.kt` 175, 230, 332, 345, 791, 937, 985, 1072; `DeveloperOptions.kt` 43;
`DaemonKeepAliveService.kt` 173, 245, 388, 548; `SetupPrerequisites.kt` 76; `ReadinessNotice.kt` 122;
`MicOpAutoHeal.kt` 167; `PermissionsScreen.kt` 258, 266; `AppLogger.kt` 521.

## Q4. What the reporter can do now

- **No workaround inside CallVault's own pairing.** The gate cannot be passed while the setting reads 0.
  Toggling Wireless debugging again will not help.
- **Shizuku mode** is the best bet. Upstream Shizuku's pairing does not read the setting, and the LADB
  reporter says Shizuku connects on this build. **Untested with CallVault on QPR3**, and our Shizuku path
  may still hit some of the reads listed above.
- **Leaving the beta** fixes it, but on Pixel that means a full wipe on downgrade. Only worth it if they
  planned to leave anyway.
- **Ask them for:** build number; whether Wireless debugging really shows "On"; a debug log with logging
  on (the log line `Wireless debugging:` in the support dump will read false even if it is on, which
  itself confirms the cause).
- A test build with fix step 1 would unblock pairing. If recording then fails to reconnect, that is
  step 5.

## What would settle it

- 🧪 A test build with unconditional discovery, run by the reporter on `DP11.261002.005`.
- Or `adb shell settings get global adb_wifi_enabled` (1) next to our app's own reading (0 or exception)
  on a QPR3 Beta 2 phone. We have no Pixel on the beta.
