# 2026-09-19 — Android 17 hides `adb_enabled` and `development_settings_enabled` from apps (issue #40)

Status: **📐 ESTABLISHED FROM SOURCE — no Android 17 device here.** Every claim about the platform below
was read out of AOSP `android-17.0.0_r1` and re-checked line by line; every claim about CallVault was read
out of our own tree. Nothing has been run on Android 17, by us or (as far as the sources show) by anybody
who wrote it up. No code has been changed.

## The report

GitHub issue #40, `teou1`, 2026-09-18. Pixel 8, CallVault 2.3.0, **Android 17 QPR1** (the reporter wrote
QPR2; the September Pixel drop is QPR1, build `CP3A.260905.009`, ~15 Sep 2026 — worth correcting in the
reply). Both debugging switches genuinely on.

> querying the settings from a third party app **always returns adb and dev settings are disabled**
> regardless of the actual state … You just cannot use the built in mode anymore.
> … Install CallVault anew or delete the data to launch the initial install — it fails to detect the dev
> mode and adb and always throws you to the version screen.

He is right, and he named the right two settings.

## What Google did — verified, not inferred

A new `redactedValue` field on the platform's private `@Readable` annotation, with a value of `"0"` on
exactly two settings. Read directly from
`https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/core/java/android/provider/Settings.java`:

```java
// Settings.java:4311
String redactedValue() default "";

// Settings.java:14761-14765
 * Whether ADB over USB is enabled. (0 = false, 1 = true).
 * This will always return 0 for all third-party apps.
@Readable(redactedValue = "0")
public static final String ADB_ENABLED = "adb_enabled";

// Settings.java:15047-15051
* Whether user has enabled development settings. (0 = false, 1 = true).
* This will always return 0 for all third-party apps.
@Readable(redactedValue = "0")
public static final String DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled";
```

Enforced **inside the calling app's own process**, before any IPC (`Settings.java:3846-3859`):

```java
// Check if there is a redacted value for this setting
if (!mReadableFieldsWithRedactedValue.isEmpty()) {
    boolean isSystemCaller = Settings.isInSystemServer()
            || UserHandle.getAppId(Binder.getCallingUid()) < Process.FIRST_APPLICATION_UID;
    if (!isSystemCaller) {
        String redactedValue = mReadableFieldsWithRedactedValue.get(name);
        if (redactedValue != null && !redactedValue.isEmpty()
                && Flags.enableRedactedValueForReadable()) {
            return redactedValue;
        }
    }
}
```

and again in `SettingsProvider.getEffectiveValue` (`SettingsProvider.java:2460-2482`), so a raw
`ContentResolver.query()` is covered too. Gated on the aconfig flag
`android.provider.Flags.enableRedactedValueForReadable` (`core/java/android/provider/flags.aconfig`,
namespace `app_compat`, internal bug 440232200 — not publicly readable).

### What follows from those lines

| Question | Answer |
| --- | --- |
| Spoofed or thrown? | **Spoofed.** `getString` returns the literal string `"0"`. Not null, no exception. |
| Depends on `targetSdkVersion`? | **No.** The check reads no `ApplicationInfo` at all. Bumping or lowering targetSdk changes nothing. |
| Who is exempt? | **uid only**: `isInSystemServer()`, or appId < 10000. |
| Does `adb shell settings get global adb_enabled` still tell the truth? | **Yes** — shell is uid 2000, exempt. |
| Does a `ContentObserver` on the URI still fire? | **Yes.** Only the read paths were changed. You learn *that* it changed, never *to what*. |
| Are **writes** affected? | **No.** `getEffectiveValue` appears only in read paths. `WRITE_SECURE_SETTINGS` works as before. |
| Is `adb_wifi_enabled` redacted? | **No.** `Settings.java:14771` is a plain `@Readable`. It stays truthful. |
| Which builds? | Present in `android-17.0.0_r1`. **Absent from Android 16** — I grepped `android-16.0.0_r1` for `redactedValue`: zero hits. So `SDK_INT >= 37` is the right threshold. |
| Announced? | **No.** Not in `developer.android.com/about/versions/17/behavior-changes-all`. It is in the SDK reference for those two constants only. |

Inference, not proven: the code shipped in 17.0 (June 2026) but users only broke after QPR1, which
suggests the flag was off in the 17.0 release build and turned on for QPR1.

## What everyone else is doing

`thedjchi/Shizuku` issue #301 (opened 2026-09-16, **still open**) is the thread the reporter linked. Its
diagnosis never got past quoting the two javadoc lines. The fork itself has no fix — last commit is
"Update README to indicate maintenance pause". Upstream `RikkaApps/Shizuku` has had no commits since
June 2025.

**RazGame's patch** (`RazGame/Shizuku`, commit `0b3611afe5dce8de695d6565363c961e2bf429ac`) — the whole of
it, fetched and read:

```kotlin
/**
 * Android 17 redacts Settings.Global.ADB_ENABLED to 0 for third-party apps,
 * so a 0 there no longer means USB debugging is actually disabled.
 */
fun isAdbEnabled(): Boolean {
    if (Settings.Global.getInt(appContext.contentResolver, Settings.Global.ADB_ENABLED, 0) > 0) return true
    return Build.VERSION.SDK_INT >= 37
}
```

applied at three call sites that each used to do `if (adbEnabled == 0) { block }`. That is all it is:
**it removes the blocking gate.** It probes nothing new.

Its weaknesses, which we should not copy:
- A blanket `true` on SDK ≥ 37. A user who genuinely has USB debugging **off** now gets no guidance at
  all — the "enable USB debugging" dialog and the deep link that highlights the toggle both stop firing.
- It conflates "cannot read" with "is on". A tri-state keeps the ability to say "we can't tell".
- It never reaches for a signal that is actually truthful.

`thejaustin/ShizukuPlus` (issue #508, commit `1d1afb262b`) did the same thing at four call sites.
`sam1am/anyapk` PR #91 (merged 2026-09-18) is the closest analogue to us — their setup checklist stuck on
"Enable Developer Options" so pairing was never reachable — and they also changed the **UI**: the step
shows ℹ️ with "if you haven't yet, tap Build number 7 times" instead of an unverifiable ✅.

Still unfixed with the naive read, at the time of writing: LADB, aShellYou, AxManager, Canta, OwnDroid,
Tweaker, KeyMapper, Nightzuku. **Nobody anywhere has published a detection method better than "treat 0 as
unknown on 37+".**

## Signals that still tell the truth on Android 17

Checked against Android 17 `system/sepolicy`.

| Signal | Usable by us? |
| --- | --- |
| `init.svc.adbd` | ✅ **Yes** — `private/domain.te:534` grants `init_service_status_prop` to *every* domain. This is the best replacement for "is debugging alive". R8 of the switches model survives. |
| `service.adb.tcp.port` | ✅ Yes — `private/app.te:54`. Our `isLoopbackArmed` keeps working. |
| `adb_wifi_enabled` | ✅ Yes — not redacted. |
| `settings get global adb_enabled` over **our own** adb shell | ✅ Truthful (uid 2000). Circular during onboarding, but a perfect post-pairing truth source and a good debug-report field. |
| A live connection / daemon binder | ✅ The strongest signal, and we already have it — useless during first-run, which is exactly #40. |
| `Settings.Global.getString` instead of `getInt` | ❌ Returns `"0"`. **This is what breaks us**, see below. |
| `ContentResolver.query` on the settings URI | ❌ Same redaction, provider side. |
| `service.adb.tls.port`, `persist.adb.tls_server.enable` | ❌ `system_internal_prop`, coredomain only. |
| `sys.usb.state` / `UsbManager` | ❌ Not granted / says nothing about the switch. |
| `AdbManager` / `IAdbManager` | ❌ Signature permission. |

## What breaks in CallVault

Only four places read these settings, which is the good news — they are chokepoints.

| Key | Function | File |
| --- | --- | --- |
| `adb_enabled` | `isUsbDebuggingEnabled` | `integrations/adb/AdbShell.kt:153-155` |
| `development_settings_enabled` (getInt) | `DeveloperOptions.isEnabled` | `integrations/adb/DeveloperOptions.kt:29-35` |
| `development_settings_enabled` (getString) | `DeveloperOptions.isExplicitlyDisabled` | `integrations/adb/DeveloperOptions.kt:42-47` |
| `adb_wifi_enabled` | `isWirelessDebuggingEnabled` | `integrations/adb/AdbShell.kt:649-652` — **unaffected** |

### 🚨 The defence we built for exactly this case is the one that fails

`DeveloperOptions.isExplicitlyDisabled` exists so that a ROM which does not expose the global never turns
the Home card red. It assumes an unreadable setting comes back **null**:

```kotlin
fun isExplicitlyDisabled(context: Context): Boolean = runCatching {
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        ?.trim()?.toIntOrNull()
}.getOrNull() == 0
```

Android 17 returns the string `"0"`. `toIntOrNull()` gives `0`. **So it returns `true` on every Android 17
phone**, and the guard is completely defeated. This is from the source, not a guess.

### The damage, worst first

1. **Onboarding dead-ends at the About-phone screen — issue #40 verbatim.**
   `ui/screens/PermissionsScreen.kt:424-439` routes the ADB step's button by `!devOptionsEnabled` first,
   so it always opens `ACTION_DEVICE_INFO_SETTINGS` — the Pixel "About phone / Android version" page, the
   reporter's "version screen". `AdbPairingService.start()` is never called and the Wireless-debugging
   page is never opened, so **pairing is unreachable and onboarding cannot finish.** The text at `:578`
   tells the user to tap Build number 7 times — which he has already done.
   `OnboardingStatus.isComplete()` itself does **not** read these settings, so the block is purely CTA
   routing. Small, safe fix.

2. **The keep-alive stops relaunching the daemon off Wi-Fi — silent missed calls.**
   `services/recording/DaemonKeepAliveService.kt:424-428` returns without attempting a relaunch when the
   notice is `NEEDS_WIFI`, and `ReadinessNotice.kt:72` produces `NEEDS_WIFI` from
   `wifi == NOT_CONNECTED && !usbDebuggingOn`. With `usbDebuggingOn` stuck false, every off-Wi-Fi user
   whose USB debugging + armed loopback would have recovered instantly gets nothing. Indefinite, silent.
   The comment two lines above it (`ReadinessNotice.kt:70`) says *"Only a positive 'no Wi-Fi' counts; an
   unreadable state is not evidence."* We got that right for Wi-Fi and wrong for USB debugging.

3. **The health ledger is poisoned — every call logged as a miss.**
   `SetupPrerequisites.kt:105` returns `DEVELOPER_OPTIONS`, and
   `CallSessionManager.reportGapIfPrerequisiteMissing` (`:346-355`) runs at call start and writes
   `CallMissedNotReady(DEVELOPER_OPTIONS)` for calls that recorded perfectly. The Home hero card sits
   permanently on `DEV_OPTIONS_OFF` — error tone, "Recording broken" — on a phone that is fine. Note the
   asymmetry: the very next line already applies "a live daemon outranks the flag"; line 105 does not.
   This breaks our own rule that a claim about the present needs evidence about the present.

4. **Off-Wi-Fi recording can never be turned on.** `OfflineRecording.kt:44-47` returns
   `NEEDS_USB_DEBUGGING` unconditionally.

5. **Wireless debugging is never released.** `WirelessDebuggingPolicy.plan` always returns
   `KEEP_ONLY_TRANSPORT`, so `mustKeepWirelessDebugging` is permanently true and the open debugging port
   outlives its purpose — the exact thing `WirelessDebuggingLease` exists to prevent.

6. **Every notification on either URI wipes the observation window.**
   `DaemonKeepAliveService.kt:136-176` always takes the `!usbOn` branch (the `wdOn ->` arm is now dead
   code) and `:285-290`'s early-return guard is always false, so the app permanently believes it has just
   entered an unrecordable window — the state that excuses missed calls.

7. **The Settings switch snaps back.** `SettingsScreen.kt:2435` writes `adb_enabled` successfully, the
   observer at `:2422` re-reads it as `0`, and the toggle flips itself off in front of the user. The
   write worked; only the read-back lies.

8. **Every debug report from an Android 17 user will say `USB debugging: false`** (`AppLogger.kt:505`).
   Triage poison.

**Shizuku-mode users are spared 2, 6 and 7** — `ReadinessNoticeText.current` returns early for them
(`ReadinessNotice.kt:90-98`) and the Shizuku heal is binder-ping based. **All writes keep working.**

### `2026-09-14-debugging-switches-model.md` after this

R3–R6 (everything keyed on `adb_wifi_enabled`), R8 (`init.svc.adbd` readable, `tls.port` not — re-verified
against Android 17 sepolicy) and R10 all survive. R1 and R2 are still true of the phone but **CallVault can
no longer evaluate them**: one half of the disjunction is permanently false in app-land. The note's
recommended setup — "USB debugging on, off-Wi-Fi recording on, Wireless debugging off" — is unreachable
through the app on Android 17.

The model rests on two cheaply-readable switches. Android 17 removes one, and our default for the missing
one (`getOrDefault(false)`) is the pessimistic choice. That is why the failure mode is "everything reads
as broken and recovery declines to act" rather than "everything reads as fine".

Prior art already in this repo: `2026-09-16-oem-adb-restrictions-and-onboarding-check.md:108-117` records
Samsung Auto Blocker forcing both flags to 0, and already prescribes the right pattern — **probe
capability, don't read switches**.

## Where the fix goes (not written)

The codebase already has the right idiom for "unreadable is not a negative": `WifiState.UNKNOWN` and
`AdbdState.UNKNOWN`. The two functions the OS change hits are exactly the two that never got it.

1. `AdbShell.kt:153` — make it a tri-state (`ON`/`OFF`/`UNKNOWN`), `UNKNOWN` when `SDK_INT >= 37` and the
   read is 0, corroborated by `init.svc.adbd == running`. One change covers damage 2, 5, 6, 7, 8.
2. `SetupPrerequisites.kt:105` — add the `&& !daemonConnected` guard that line 107 already uses. Kills
   the permanent red card and the false ledger entries in one line.
3. `DaemonKeepAliveService.kt:424` — don't decline a relaunch on `NEEDS_WIFI` while adbd is running or the
   loopback is armed.
4. `PermissionsScreen.kt:426` — don't route to About-phone on `!devOptionsEnabled` alone. Follow anyapk:
   show "tap Build number 7 times" as information, not as a blocker, and let the button fall through to
   pairing. **This alone unblocks #40.**
5. `DeveloperOptions.isExplicitlyDisabled` — its null-means-unknown premise is now false; it needs a
   corroborator.
6. Debug reports — replace the `USB debugging: <bool>` line with `init.svc.adbd`,
   `service.adb.tcp.port`, `adb_wifi_enabled`, and once connected `settings get global adb_enabled` over
   our own shell.

## Two Android 17 traps waiting behind this one

Neither bites us today; both will.

- **`ACCESS_LOCAL_NETWORK` becomes mandatory at `targetSdk 37`**, and covers mDNS and LAN sockets. We are
  `targetSdk = 36` (`app/build.gradle.kts:146`) and declare neither it nor `NEARBY_WIFI_DEVICES`, so we
  are fine until we bump. `thedjchi/Shizuku` hit it: without the grant the OS showed *"an endless 'Choose
  a device to connect' picker that never resolved"* (commit `eea81ed7a`).
- **`USE_LOOPBACK_INTERFACE`**, a new install permission at protection level `normal`, guards loopback
  traffic between apps. Our off-Wi-Fi mode connects to `127.0.0.1` and does not declare it. Declaring a
  `normal` permission costs nothing. Related: thedjchi had to stop hardcoding `127.0.0.1` in the pairing
  probe (commit `3b1735223`) because on Android 17 *"the adb pairing/connect daemon binds to the device's
  local-network address, not loopback"* — our `AdbMdns.isLoopbackPortTaken` does the same bind probe and
  is **unverified on Android 17**. Possible second reason pairing fails there.

## What we still cannot answer without a device

- Whether the flag is on in a *non-QPR* Android 17 build.
- Whether the hidden-API list still lets us reflect `android.os.SystemProperties` on 17 (no change found
  in the source, but not proven).
- Whether `AdbMdns`'s loopback bind probe works on 17.
- Why two people in #301 report the Play Store Shizuku working. Upstream has no fix, so that data point
  is unexplained — do not build on it.

## Requirements the maintainer set for the fix (2026-09-19)

### 1. It must not cause regressions

The Android 17 fix changes how "USB debugging is off" and "Developer options are off" are decided, and
those two answers feed **ten** call sites (see C9b). The risk is not the Android 17 path — it is Android
≤16, where the readings are truthful today and everything already works. A tri-state that is wrong in the
"unreadable" direction on an older phone would silently stop recovery, exactly as this change does on 17.

Concretely, the things that must still behave on Android ≤16 after the change:

- **USB debugging genuinely off** must still produce `NO_DEBUGGING` / `NEEDS_WIFI` and still tell the user
  what to switch on. The Shizuku forks' blanket "treat 0 as unknown on SDK ≥ 37" loses this; we must not,
  which is why it is gated on `SDK_INT >= 37` and never applied below.
- **`WirelessDebuggingPolicy.plan`** must still return `DROP_USB_KEEPS_ADBD` when USB debugging is really
  on, so Wireless debugging is still released. Getting this wrong leaves a debugging port open forever.
- **`AdbdRevivalPolicy`'s `usbDebuggingOn -> NOTHING` early-out** must still fire, or a stale
  `init.svc.adbd` reading sends us into a `CYCLE_WIRELESS_DEBUGGING` that restarts adbd — and kills
  Shizuku — for nothing.
- **`SetupPrerequisites`** must still report `DEVELOPER_OPTIONS` on a phone where they really are off, or
  a genuinely broken setup reads as healthy.
- The whole **2026-09-19 reboot chain** (`LoopbackBorrowPolicy`, `LoopbackArmWait`) must be untouched in
  behaviour; it is verified on a device and must stay that way.

The guard is the existing house rule: decisions are pure functions with their own tests, so each of the
above gets a test at `SDK_INT = 36` **and** `= 37` with the same inputs, and the ≤36 row must be
unchanged from today. `1606` tests pass now; that number is the floor.

### 2. It must cover built-in **and** Shizuku mode

Checked against the code rather than assumed. **Shizuku mode is already spared the worst of it**, and by
deliberate design rather than luck:

| Site | Shizuku mode | Why |
|---|---|---|
| Onboarding ADB card | **not shown** | `PermissionsScreen.kt:342` skips it for `PrivilegedMode.SHIZUKU` |
| The About-phone dead-end CTA (issue #40 itself) | **not reached** | `isAdbStep` needs `!status.adbConnected`, and `AppPreferences.isPrivilegedTransportSetUp()` returns **true** for Shizuku (`:968-973`), so `adbConnected` is true |
| `SetupPrerequisites` → `DEVELOPER_OPTIONS` | **not reached** | the `mode.needsShizuku` branch returns first (`:96-102`) |
| Readiness notices (`NEEDS_WIFI`, `WD_OFF_BY_USER`, …) | **not reached** | `ReadinessNoticeText.current` returns early for Shizuku (`ReadinessNotice.kt:90-98`) |
| Settings ▸ USB debugging toggle | **not shown** | guarded by `usesEmbeddedAdb = !needsShizuku` (`SettingsScreen.kt:2399`) |
| Stuck-mic auto-heal | **ruled out** | `MicOpHealPolicy` takes `standalone` and declines otherwise (`MicOpAutoHeal.kt:80`) |

**But two sites hit both modes and must be fixed for both:**

1. **`PermissionsScreen.kt:391-400`** — the "USB debugging (Recommended)" card is **outside** the mode
   gate, so on Android 17 it reads permanently not-granted in Shizuku mode too. Cosmetic, but it is a
   false statement about the user's phone on the first screen they see.
2. **`AppLogger.kt:505`** — the debug-report header prints `USB debugging: false` in both modes. Every
   Android 17 report we ever receive will carry it, and we triage from that header.

And one thing that is **not ours to fix but must be said to users**: a Shizuku user on Android 17 may be
unable to start Shizuku at all, because *Shizuku's own* manager is broken by the same change and upstream
has no fix (`RikkaApps/Shizuku` has had no commits since June 2025). Our Shizuku-mode onboarding should
not imply that starting Shizuku will work, and pointing at a patched fork is a decision for the
maintainer, not something to bury in a help string.

## Second research pass, 2026-09-19 — two corrections and one unexplored escape

Everything below marked ✅ I re-verified myself from primary sources, not from the research summary.

### ⚠️ CORRECTION: `SDK_INT >= 37` is NOT a safe gate

This note said *"Absent from Android 16 … So `SDK_INT >= 37` is the correct threshold; Android 16 is
safe."* **True of AOSP, false of the field.** Two Android 16 custom ROMs have backported the redaction:

✅ Verified by fetching both trees:

| ROM | Branch | `Build.java` | `Settings.java` |
|---|---|---|---|
| `ProjectCiRCLE-ROM/frameworks_base` | `16.2` | `BAKLAVA = 36`, no 37 | `@Readable(redactedValue = "0")` on `ADB_ENABLED` |
| `CharaROMAndroid/android_frameworks_base` | `hershey` | `BAKLAVA = 36` | same |

Both also ship the aconfig flag as `state: ENABLED, permission: READ_ONLY` in their vendor release config
(`ProjectCiRCLE-ROM/vendor_circle` `c2d9507dd`, 2026-08-05, *"Required for our backport of redactedValue"*).
`READ_ONLY` means it is baked in at build time — **not togglable with `device_config`**, so there is no
user-side workaround to suggest either.

**So the tri-state must treat an uncorroborated `0` as UNKNOWN regardless of SDK level**, corroborated by
`init.svc.adbd`. Doing it that way makes the version check unnecessary and covers these ROMs for free.
This also satisfies the maintainer's no-regressions requirement better than a version gate would: the
corroborator is what decides, so an Android ≤16 phone with debugging genuinely off still reads as off.

### ✅ RESOLVED: why the Play Store Shizuku works

This note listed that as *"unexplained — do not build on it"*. It is explained: **upstream Shizuku never
reads the setting.** `RikkaApps/Shizuku`'s `AdbDialogFragment` only ever *writes* `adb_wifi_enabled` and
`ADB_ENABLED`, and its `EnvironmentUtils` has no `isAdbEnabled` at all. The blocking read-gate is a
**fork addition** (`thedjchi/Shizuku`'s `AdbStarter.kt:82-83`). Same lesson as ours: writes are fine,
**only the read-gate breaks**. Our own upstream, `kitsumed/ShizuCallRecorder`, never reads either setting
— structurally immune, and no fix there to borrow.

### 🔓 NEW, unexplored by anyone: a provider path that is not redacted

✅ Verified by reading `packages/SettingsProvider/.../SettingsProvider.java` at `android-17.0.0_r1`
myself. The redaction sits **only** on the generation-tracking branch:

```java
// :2877-2889
private Bundle packageValueForCallResult(int type, @NonNull String name, int userId,
        int deviceId, @Nullable Setting setting, boolean trackingGeneration) {
    if (!trackingGeneration) {
        if (setting == null || setting.isNull()) return NULL_SETTING_BUNDLE;
        return Bundle.forPair(Settings.NameValueTable.VALUE, setting.getValue());   // RAW
    }
    ...
    String value = getEffectiveValue(setting, redactedSettingsMap);                 // redacted
```

and the flag is set by the *caller*:

```java
// :3025-3027
private boolean isTrackingGeneration(Bundle args) {
    return args != null && args.containsKey(Settings.CALL_METHOD_TRACK_GENERATION_KEY);
}
```

`CALL_METHOD_GET_GLOBAL` dispatches straight into it (`:474-480`), and `getGlobalSetting` (`:1576-1591`)
does nothing but `enforceSettingReadable` — which `adb_enabled` passes, being `@Readable` with no
`maxTargetSdk`. The in-process check at `Settings.java:3846` never runs because `Settings.Global.getString`
is never called. So:

```kotlin
val b = contentResolver.call("settings", "GET_global", "adb_enabled", null)
val real = b?.getString("value")   // the true value, in principle
```

**Caveats, and they matter.** Nobody has published this. **It has not been run on an Android 17 device —
we do not have one.** It is plainly an oversight in Google's patch, so a later QPR may close it. Use it
as a **corroborator only, never the sole signal**, and make its absence non-fatal. `query()` is *not*
leaky — both query paths go through `getEffectiveValue`.

### ✅ No allowlist, no exemption, no opt-out — plainly

The only escape at either enforcement point is uid. `MANAGE_DEBUGGING` is `signature|privileged`,
`@SystemApi @hide`, gates an `AdbManager` that has no "is USB debugging on" getter anyway. No role, no
permission, no `targetSdk` opt-out. **Even a platform-signed privileged app with uid ≥ 10000 gets `"0"`.**

### Provenance, now evidence rather than inference

Gerrit topic `dev-options-redacted-value`, merge `792c34a97`, **Pradyuman Dixit, 2026-03-25**; substantive
CL `5d466bf8025e`, **2026-03-02**:

> "We update ADB_ENABLED and DEVELOPEMENT_SETTINGS_ENABLED to have a redactedValue of 0, since **this
> information is not important for the third-party apps**." — Bug: 440232200, Flag: EXEMPT BUGFIX,
> Test: `atest android.appseurity.cts.ReadableSettingsFieldsTest`

Written March 2026, in the tree three months before 17.0 shipped, classified `PURPOSE_BUGFIX`. So it was
never secret — `DroidWin` reported it on 3 Aug 2026. "Undocumented" and "unannounced" remain correct;
"unknown until it shipped" was not, and this note should not imply it.

### ⚠️ Do not read AOSP `main` as a revert

`refs/heads/main` = `refs/heads/master` = `1cdfff555f`, HEAD dated **2025-03-27** — frozen a year *before*
the change. Its zero `redactedValue` hits are staleness, not relaxation. There is **no published QPR1 or
QPR2 source** (no `android17-qpr1-release` branch exists), so the build users are reporting cannot be
diffed. `android17-security-release` is byte-identical to `android-17.0.0_r1` for `Settings.java`,
`flags.aconfig` and `SettingsProvider.java`. Still exactly two redacted settings; no extension, no revert.

Gitiles `+log` now returns **HTTP 401** ("Please sign in"), so history must be reconstructed from ROM
mirrors; `?format=TEXT` on a blob still works anonymously.

### Three more things for the fix

1. **`persist.adb.tcp.port`** — `Stellar` and `AxManager` both fall back to it after `service.adb.tcp.port`.
   We read only the `service.` form (`AdbShell.kt`, `LoopbackArmWait.kt`, `LoopbackBorrowPolicy.kt`,
   `WirelessDebuggingPolicy.kt`). Cheap gap on OEMs that set the persist form.
2. **`ACCESS_LOCAL_NETWORK` hits every connect, not only pairing.** `thedjchi/Shizuku` **#302**
   (2026-09-16, open): the already-paired start path calls `adbMdns.start()` with no permission check and
   the OS picker reappears *on every start*. Our `AdbMdns` has the same shape.
3. **`AppManager`'s default is the opposite of ours, and better**: `init.svc.adbd` defaults to
   `"running"` — *"Default is set to 'running' to avoid other issues"*. Ours default to `false`
   everywhere, which is why an unreadable state reads as broken.

### ✅ Android 17's adbd now toggles Wireless debugging by itself

From Google's own ADB Wi-Fi 2.0 post (9 Sep 2026), fetched and quoted verbatim:

> "the daemon automatically turns off ADB Wi-Fi when it detects an untrusted network and re-enables itself
> once running on a user-allowed network"

**This lands directly on `WirelessDebuggingOffCause`.** It has exactly two excuses for an off-write we did
not make — `ANDROID_REFUSED` (we had just written it on) and `ANDROID_NO_WIFI` (Wi-Fi gone). Neither
covers *"untrusted network, Wi-Fi still connected"*, so on Android 17 that write is classified **USER**
and sets `WD_TURNED_OFF_BY_USER` — the flag behind the reboot deadlock of
`2026-09-19-reboot-deadlock-wd-off-by-user.md`. `LoopbackBorrowPolicy` means it can no longer deadlock,
but it would re-arm that flag repeatedly, and every borrow restarts `adbd` and kills any Shizuku server.
The post also replaces the mDNS stack outright, which our discovery sits on.

### The ecosystem has produced nothing new

Re-checked on 2026-09-19: `thedjchi/Shizuku` unchanged since its July maintenance-pause commit, #301 still
open at 12 comments with no diagnosis past the javadoc, `RazGame` and `ShizukuPlus` unchanged, upstream
Shizuku unchanged since June 2025. `gh search issues` for `"third-party apps" adb_enabled created:>2026-08-01`
returns **2 results total**. LADB, aShellYou, Canta, KeyMapper, AxManager, Tasker plugins and the
root-detection vendors have not noticed in either direction. **Nobody is past "treat 0 as unknown".
Whatever we build, we build first.**

## Third pass — official docs, CTS and the flag. Two findings that decide the design.

### ✅ It is NOT CTS-enforced and NOT CDD-required — so OEMs can differ

- CTS `tests/tests/provider` at `android-17.0.0_r1`: **zero** `redact`, **zero** `Readable`. The one
  `ADB_ENABLED` reference (`SettingsTest#testSecureTable`) only asserts the cursor is non-null; it never
  reads the value, so it passes either way. Same null result across CTS `settings`, `security`, `appop`,
  `deviceconfig`, `os`, and the platform's own `SettingsProvider` unit tests.
- The Android 17 CDD (644 kB of text) never mentions it. §6.1 requires adb support and a user-accessible
  way to turn it on — nothing about hiding the state from apps.

✅ And the flag cannot easily *be* CTS-tested. Verified by fetching `core/java/android/provider/flags.aconfig`
at `android-17.0.0_r1`: the declaration carries **no `is_exported` and no `is_fixed_read_only`**, while
**13 of the 15 flags in that same file do declare `is_exported`**. A non-exported flag is not visible to a
CTS test built against the SDK.

**Consequence for us: do not assume uniformity.** Samsung, Xiaomi and OnePlus may each ship this
differently, and #39/#23-style per-OEM divergence is likely. Detection must be behavioural, not
"is this Android 17".

### ✅ Stock AOSP 17 appears to have it OFF

Default aconfig state is DISABLED unless a release config enables it, and **none of AOSP's 18 Android 17
release configs** (`platform/build/release`, `android17-release`) contain a `flag_value` for it. Google's
Pixel/GMS release config is internal and unpublished, so the shipping state cannot be proved from AOSP —
but it is consistent with the ROM evidence above, where Evolution-X and CharaROM each had to add an
`ENABLED` / `READ_ONLY` textproto by hand. **This is a per-build decision, not an Android-version fact.**
One more reason the tri-state must corroborate rather than gate on `SDK_INT`.

### 🔑 The decisive finding: our own daemon can still read the truth

Both enforcement points exempt `UserHandle.getAppId(callingUid) < Process.FIRST_APPLICATION_UID` (10000).
**An adb shell is uid 2000, and CallVault runs its own shell-uid daemon.** So on Android 17:

- from the app process → `"0"`, always;
- from our daemon / our ADB shell → **the true value**.

If that holds on hardware, the redaction is a **display and onboarding problem for us, not a capability
loss**. Everything after pairing — the readiness notices, the keep-alive's decisions, the debug-report
header, `SetupPrerequisites` — can be told the truth by the daemon we already run.

It does **not** help the case in issue #40, which is first-run: there is no daemon yet, and that is
exactly when onboarding blocks. So the fix still needs both halves — a truthful reading where a daemon
exists, and a never-blocking onboarding where one does not.

Note the asymmetry that makes this work: the second enforcement point is **in-process**
(`Settings.NameValueCache`), so no amount of reflection or direct provider work *inside the app* recovers
the value. It has to come from a lower-uid process. (The `GET_global` hole in the previous section is the
one exception, and it is unverified.)

### Documentation: one sentence, and it is mis-dated

`developer.android.com/reference/android/provider/Settings.Global` says, for both constants:

> "This will always return 0 for all third-party apps."

Two traps in that sentence: **it carries no version qualifier**, so a developer reading it today would
conclude it was always true; and the "Added in API level 17" above it refers to the *constant*, which is a
coincidence — Android 17 is API 37. The word "redacted" never appears on the page. Confirmed new in 17 by
diffing the javadoc against `android-16.0.0_r1`.

Everything else is silent: **zero hits** across `behavior-changes-all`, `behavior-changes-17`, `features`,
`summary`, `migration`, `release-notes`, `qpr1`, `qpr1/release-notes`, `qpr2`, `qpr2/release-notes`. The
API diff 36→37 cannot show it — JDiff does not track annotation or javadoc changes. AOSP Gerrit returns
`[]` for both `bug:440232200` and the flag name, so it landed by internal merge with no public CL.

Worth knowing: Google never documented the `@Readable` mechanism itself in Android 12 either. This family
of change lives only in javadoc, by habit.

### There is no sanctioned alternative API

Play Integrity is the only sanctioned device-trust API and **has no developer-options or adb signal** —
its verdicts cover licensing, app recognition, device integrity, recent activity and access risk. It is
also an attestation oracle for a *server*, needs a Play Console app and a Cloud project, and our users
sideload, so the licensing and recognition verdicts would come back negative anyway. Useless to us three
times over.

### targetSdk independence, re-confirmed, and the confusion named

Neither redaction site reads `ApplicationInfo`. The reason people conflate this with a targetSdk gate is
that `@Readable` carries **both** `maxTargetSdk` and `redactedValue`, collected in the same loop into two
different maps — `keysWithMaxTargetSdk` feeds `enforceSettingReadable` (the Android 12 SecurityException
path, which *is* targetSdk-gated), `keysWithRedactedValue` feeds the redaction (which is not).

### The one device test that settles everything left open

On any Android 17 phone, three commands:

```
adb shell aflags list | grep redacted                    # state + READ_ONLY/READ_WRITE
adb shell settings get global adb_enabled                # uid 2000 -- expect the TRUE value
# and from inside an app process                         -- expect "0"
```

That answers: whether the flag is flippable on shipping builds, whether it went live in 17.0 or QPR1, and
— the one that decides our design — **whether our daemon keeps the capability**. We have no Android 17
device; the Android 17 emulator image is the cheapest route and is worth trying before writing the fix.
