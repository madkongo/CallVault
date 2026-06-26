# Optional Dialer Mode — Phase 1 Design

**Date:** 2026-06-26
**Status:** Approved design (pre-implementation-plan)
**Baseline:** v1.2.1

## 1. Motivation

Today CallVault detects calls via the `PHONE_STATE` broadcast (with a post-boot live
`TelephonyCallback` in `CallMonitorService`). That detection is inherently laggy and has been the
source of repeated bugs (e.g. the 1.2.0 "first call after reboot records ~9s late" fix).

Becoming the **default phone app** gives CallVault an `InCallService`, which delivers precise,
real-time per-call state from Telecom (`DIALING`/`RINGING`/`ACTIVE`/`DISCONNECTED`). This both
**fixes the detection-timing class of bugs** and enables a **cleaner, integrated calling UX**.

Crucially, being the default dialer does **not** grant any additional access to call *audio* — that
remains the job of the existing privileged `app_process` daemon (scrcpy `VOICE_CALL` capture over
embedded ADB). The dialer *wraps* the recording system; it does not replace or simplify it.

## 2. Goals / Non-Goals

**Goals (Phase 1)**
- Make the dialer **optional**: existing users stay on today's behavior unless they opt in.
- Let users switch between **recording-only** and **dialer + recording** anytime in Settings.
- Ship a **minimal-but-complete** default phone app: place/receive/emergency calls, incoming-call
  screen, in-call screen, minimal dialpad.
- Migrate call detection to **Telecom** while in dialer mode, feeding the **unchanged** recording
  pipeline.

**Non-Goals (Phase 1 — deferred to later phases)**
- Rich call-log/history UI (Phase 2).
- Contacts browser/search, favorites/speed-dial (Phase 3).
- Call blocking/screening, search, post-call screen, conference UI (Phase 4).
- Play Store eligibility (call recording remains banned regardless of dialer role).
- Video/VoIP calls (route to system; out of scope).

## 3. Phased Roadmap

| Phase | Scope | Value |
|---|---|---|
| **1 (this spec)** | Mode infra + Settings toggle (reversible) · become/release default-dialer role · `InCallService` · incoming-call screen · in-call screen (mute/speaker/hold/DTMF/end) · minimal dialpad (place + emergency) · Telecom-based detection in dialer mode · wire existing recording pipeline to Telecom events | Reliability + a real phone |
| **2** | Rich call log/history UI; link each call to its recording | Everyday usability |
| **3** | Contacts browser/search, contact detail, favorites/speed-dial | Everyday usability |
| **4** | Polish: search, blocking/screening, post-call screen, conference | Nice-to-have |

Recording-only mode remains byte-for-byte the current app throughout. Dialer features only activate
when the user opts in **and** the `ROLE_DIALER` role is actually held.

## 4. Architecture

### 4.1 The `CallEventSource` seam (core refactor)

Introduce a thin interface between *"a call happened"* and *"decide whether to record"*:

```
CallEventSource (interface)
   emits normalized CallEvent: Ringing / Dialing / Active / Ended  (+ number, direction, sim)
        │
        ├── BroadcastCallEventSource  ← existing PhoneStateReceiver + CallMonitorService  (recording-only)
        └── TelecomCallEventSource     ← new CallVaultInCallService                         (dialer)
                                  │
                                  ▼
   CallSessionManager.processSessionUpdate → shouldAutoRecord → RecordingForegroundService → daemon
                         (UNCHANGED: recording decision + pipeline identical in both modes)
```

- Only the **source** of call events swaps based on mode. The recording decision logic
  (`shouldAutoRecord`, contact/anonymous/cross-country filters, storage) is untouched.
- In dialer mode the Telecom source fires on `STATE_DIALING`/`STATE_RINGING` — earlier and more
  precise than the broadcast — enabling capture at dial tone (subject to daemon warmth; see §6).
- **Mutual exclusion:** in dialer mode, `BroadcastCallEventSource` defers to Telecom, reusing the
  existing pattern where `PhoneStateReceiver` already defers when `CallMonitorService.isListening`.

### 4.2 Components (new unless noted)

1. **Mode + role infrastructure**
   - `AppPreferences.DIALER_MODE_ENABLED` (default `false`) + accessors.
   - `DialerRoleController` wrapping `RoleManager`: requests `ROLE_DIALER`, handles the system
     result, releases on toggle-off, and treats **`RoleManager.isRoleHeld` as the source of truth**
     (detects external changes to the default phone app). The Settings toggle drives the role
     request and reflects real role state, not just the stored preference.

2. **`CallVaultInCallService`** — manifest-registered with `BIND_INCALL_SERVICE`. Tracks `Call`
   objects, registers `Call.Callback`s, maps Telecom states → normalized `CallEvent`s (feeding the
   pipeline in §4.1), and exposes current-call state to the UI. Pulls number/direction directly off
   the `Call` object (no `READ_CALL_LOG` double-broadcast hack needed in this mode).

3. **Calling UI** (Compose) — reachable only in dialer mode:
   - **`InCallActivity`** — a *standalone* Activity launched by the InCallService via full-screen
     intent (it must appear over the lock screen / when the app is closed). Therefore it is
     deliberately **not** part of the onboarding `AppNavigationScreen` router. States: incoming
     (answer/reject), active (mute/speaker/hold/DTMF/end), dialing/connecting.
   - **Minimal dialpad** — digits, call, backspace; places via `TelecomManager.placeCall`.
     **Emergency numbers always pass through** unfiltered (hard requirement).

4. **Recording control in dialer mode** — existing auto-record toggles still apply (incoming/
   outgoing/contact filters unchanged), **plus** a manual record/stop button on the in-call screen.

### 4.3 Mode/role lifecycle

```
Settings toggle ON  → DialerRoleController.requestRole() → system role dialog
   granted  → isRoleHeld=true  → InCallService active → TelecomCallEventSource drives detection
   denied   → revert toggle, stay recording-only
Settings toggle OFF → release role → fall back to BroadcastCallEventSource
External change (user picks another default phone app) → detected on resume via isRoleHeld
   → auto-fall back to BroadcastCallEventSource + show "dialer features off" banner
```

## 5. Data Flow (dialer mode, a call)

1. Telecom adds a `Call` → `CallVaultInCallService.onCallAdded` → register `Call.Callback`.
2. State transitions (`DIALING`/`RINGING`/`ACTIVE`/`DISCONNECTED`) map to normalized `CallEvent`s.
3. `TelecomCallEventSource` forwards events to `CallSessionManager.processSessionUpdate` (the same
   entry the broadcast path uses today).
4. `shouldAutoRecord` decides; `RecordingForegroundService` starts/stops the daemon capture — all
   unchanged.
5. `InCallActivity` renders call state and exposes answer/reject/end + manual record toggle.

## 6. Risks & Edge Cases

- **Emergency calls (hard requirement):** emergency dialing must always work; emergency numbers
  bypass all filtering/standby and are never blocked or record-gated. Explicit test coverage.
- **Role loss / external change:** `RoleManager.isRoleHeld` is checked on every resume; on loss we
  auto-fall back to the broadcast source so recording never dies silently, plus a UI banner.
- **Daemon cold-start is separate:** Telecom detection is instant, but *capture* still needs the
  privileged daemon warm. Being the dialer fixes detection timing, not daemon warm-up — the existing
  "starting up… / ready" notification still applies. We will not over-promise "record from dial
  tone" when the daemon is cold.
- **Concurrent calls / call waiting:** Phase 1 handles the primary active call with basic hold/swap;
  full conference UI deferred. Recording follows the active call.
- **Multi-SIM, VoLTE/VoWiFi, video calls:** Phase 1 records voice calls as today; video/VoIP route
  to the system and are out of scope.
- **Lock-screen incoming UI:** requires `USE_FULL_SCREEN_INTENT` (dialers are exempt from the
  Android 14 restriction).
- **Distribution:** still F-Droid/GitHub only; the dialer role does not change the recording ban.
- **Permissions:** holding `ROLE_DIALER` implicitly grants `CALL_PHONE`/`READ_CALL_LOG` while held;
  no `MANAGE_OWN_CALLS` (we ride system telephony via InCallService, not a self-managed
  ConnectionService). When the role is released, those revert.
- **OEM quirks (OxygenOS/OnePlus):** default-dialer behavior on heavily-skinned OEMs needs on-device
  verification (consistent with existing battery/autostart friction).

## 7. Testing

- **Unit:** `CallEventSource` normalization (Telecom states → events); `DialerRoleController` state
  machine; **regression** that `shouldAutoRecord` / recording-decision logic is unchanged.
- **On-device (manual/instrumented):** place + receive calls; emergency-number dial; reject/miss;
  role grant→revoke mid-session; recording start timing (warm vs cold daemon); lock-screen incoming;
  multi-SIM if available.
- **Regression:** recording-only mode behaves byte-for-byte as 1.2.1 (default `DIALER_MODE_ENABLED`
  is `false`).

## 8. Integration Seams (from codebase map)

| Concern | File | Note |
|---|---|---|
| Mode pref | `data/AppPreferences.kt` | Add `DIALER_MODE_ENABLED` key + default + accessors |
| Detection entry | `services/call/CallSessionManager.kt` | `processSessionUpdate` is the shared sink for both sources |
| Existing broadcast source | `services/call/PhoneStateReceiver.kt`, `services/call/CallMonitorService.kt` | Becomes `BroadcastCallEventSource`; defers in dialer mode |
| Recording start | `services/recording/RecordingForegroundService.kt`, `server/RecorderServerLauncher.kt` | Unchanged |
| Settings UI | `ui/screens/SettingsScreen.kt`, `ui/viewmodels/SettingsViewModel.kt` | Mode toggle + role request |
| Manifest | `AndroidManifest.xml` | Add `InCallService` (`BIND_INCALL_SERVICE`), full-screen-intent perm |
| New UI | `ui/dialer/` (InCallActivity, dialpad, viewmodels) | Standalone calling surfaces |

## 9. Open Questions

None blocking Phase 1. Later phases (call log, contacts) will get their own specs.
