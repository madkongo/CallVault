# Dialer Mode — "Engage + Reachable" Increment (A+B) Design

**Date:** 2026-06-28
**Status:** Approved design (pre-plan)
**Branch:** feat/dialer-mode-phase1 (builds on the Phase 1 dialer work + on-device fixes)

## Motivation

On-device testing (OnePlus / OxygenOS) proved that the standard `RoleManager` default-dialer
prompt is granted at the role level but **not propagated into Telecom's default-dialer cache** by
the OEM, so CallVault's `InCallService` never binds and dialer mode never engages. We verified that
the **privileged shell path the app already uses** (over its ADB/daemon channel) *does* move
Telecom's default:

- `cmd telecom set-default-dialer com.baba.callvault` → `mDefaultDialerCache = com.baba.callvault`,
  CallVault's `InCallService` binds (`dumpsys telecom`: `type: 1 is crashed: false`, call CREATED).
- The override does **not** auto-grant role permissions, so `pm grant … CALL_PHONE` is also needed
  for the in-app dialpad to place calls (otherwise it falls back to `ACTION_DIAL` → Google Phone).

This increment makes dialer mode **actually engage** via that ADB privilege (A) and adds a
**toggle-linked launcher icon** so the dialpad is reachable (B). The larger "proper dialer" UX
(history/contacts/autocomplete) is a separate, later increment (C).

## Goals / Non-Goals

**Goals**
- Enabling dialer mode reliably makes CallVault the *functional* Telecom default dialer on locked-down
  OEMs, using the existing ADB/daemon channel.
- Grant the role-implied `CALL_PHONE` so the in-app dialpad places calls directly (no Google bounce).
- Re-assert sensibly (enable + boot + app-resume); surface the existing banner otherwise.
- On disable, relinquish cleanly (restore the prior default dialer).
- A "CallVault Dialer" launcher icon that appears/disappears with the toggle and opens the dialpad.

**Non-Goals (this increment)**
- Proper dialer UX: autocomplete, call history, contacts browser, favorites, search (increment C).
- Overwriting the OEM's hardcoded "Phone" launcher icon (not possible; we add our own icon).
- Winning a continuous tug-of-war with Google Phone (we re-assert at defined points, not in background).
- Surviving on devices without the app's ADB/daemon setup (already a hard app dependency).

## Architecture

### A. `DialerDefaultEnforcer` (new) — forces/relinquishes the Telecom default via ADB

Uses the existing `AdbShell` (the same uid-2000 shell channel that grants `WRITE_SECURE_SETTINGS`,
toggles Wireless Debugging, and launches the daemon).

- `fun isDefault(): Boolean` — delegates to `DialerRoleController.isDefaultDialer()`
  (`TelecomManager.getDefaultDialerPackage() == packageName`).
- `suspend fun enforce(): Result` — capture+store the current default (if not already us), then run
  over `AdbShell`:
  - `cmd telecom set-default-dialer com.baba.callvault`
  - `pm grant com.baba.callvault android.permission.CALL_PHONE`
  then verify `isDefault()`. Returns success/failure (failure → recording-only + banner).
- `suspend fun relinquish()` — restore the stored prior default dialer:
  `cmd telecom set-default-dialer <priorPackage>` (fallback to
  `TelecomManager.getSystemDialerPackage()` if none stored). Clears the stored value.
- Prior-default storage: `AppPreferences.PRIOR_DEFAULT_DIALER` (string).
- Command strings are pure/testable; the AdbShell invocation is the only Android dependency.

### B. Toggle-able launcher icon — manifest `activity-alias`

- New `activity-alias` (e.g. `.dialer.DialerLauncherAlias`) targeting a thin entry that routes to
  the dialpad (`AppScreen.Dialer`) — either `MainActivity` with an extra (`open=dialer`) or a small
  `DialerEntryActivity`. `LAUNCHER` category, `android:enabled="false"`, `android:exported="true"`,
  label `@string/dialer_launcher_label` ("CallVault Dialer"), app icon.
- `DialerLauncherIcon` helper: `setEnabled(context, on)` →
  `PackageManager.setComponentEnabledSetting(aliasComponent, ENABLED|DISABLED, DONT_KILL_APP)`.
- The dialpad entry must read the routing extra and navigate to `AppScreen.Dialer` on launch.

### Integration — orchestrated in `SettingsViewModel.setDialerModeEnabled(on)`

```
on == true:
  store prior default (if not us); preferences.setDialerModeEnabled(true)
  DialerDefaultEnforcer.enforce()            // ADB: set-default-dialer + grant CALL_PHONE
  DialerLauncherIcon.setEnabled(true)
  CallDetection.setMode(DialerModeState.effective(prefOn=true, roleHeld=isDefault()))
on == false:
  preferences.setDialerModeEnabled(false)
  DialerLauncherIcon.setEnabled(false)
  DialerDefaultEnforcer.relinquish()         // restore prior default
  CallDetection.setMode(false)
```

Re-assert points (only when pref on and `!isDefault()`):
- **Boot:** existing post-boot path (`AdbConnectionService` after the daemon connects) calls `enforce()`.
- **App resume:** existing `ON_RESUME` in `AppNavigationScreen` (or `MainActivity`) calls `enforce()`.
- **Banner tap:** the existing role-lost banner now calls `enforce()` instead of `requestRoleIntent()`.

The RoleManager prompt path is retired for enabling on these builds; `DialerRoleController` keeps
`isDefaultDialer()` (used everywhere) and may drop `requestRoleIntent()` once the banner/toggle no
longer call it.

## Error handling

- `enforce()` failure (ADB/daemon unavailable, or Telecom didn't move): do **not** flip effective
  mode; keep recording-only (broadcast detection) and show the role-lost banner with guidance that
  dialer mode needs the ADB setup. Never crash; log via `AppLogger`.
- `relinquish()` best-effort; if it fails, log and proceed (worst case the banner reflects state).
- All ADB commands run on a background thread (suspend); never on main.

## Testing

- **Unit (JVM):** exact command strings produced by `DialerDefaultEnforcer` (set-default-dialer,
  pm grant, relinquish with stored/ fallback package); prior-default capture/restore logic;
  `DialerLauncherIcon` enabled/disabled decision mapping; orchestration order in a fake VM.
- **On-device (manual):** enable → `get-default-dialer == self`, `CALL_PHONE granted`, alias icon
  appears, dialpad places a call, CallVault in-call UI + recording via Telecom; disable → prior
  default restored, icon gone, recording-only via fallback; reboot → re-assert; resume after Google
  Phone steals it → re-assert; ADB-down → graceful banner.

## Constraints / caveats

- Requires the app's ADB/daemon channel (already mandatory for recording).
- Launcher alias icon appears in the **app drawer**, not auto-placed on the home screen.
- Tug-of-war with Google Phone is handled only at enable/boot/resume + banner (no background loop).
- The `set-default-dialer` override likely does not survive reboot → boot re-assert covers it.
- Emergency calls always use the preloaded dialer (AOSP guarantee) — unaffected.
- Recording-only mode remains byte-for-byte unchanged when dialer mode is off.

## Open Questions

None blocking. Increment C (proper dialer UX) will get its own design.
