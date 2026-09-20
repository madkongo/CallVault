# Backlog

Agreed work that is **not** started, so it does not get lost between sessions. Ordered by the value it
delivers, not by effort. Anything already researched lives in `capture-research-directions.md`; this
file is for decided product and engineering work.

Status key: 🔵 agreed, not started · 🟡 in progress · ✅ done (kept briefly, then deleted)

**A section header is a claim about a release, so verify it against the tags rather than trusting it.**
Every ✅/🟡 below now names the release it shipped in. On 2026-08-14 three sections still read "not yet
on a device" / "awaiting device test" for work that had shipped in **v1.5.5**, two releases earlier —
they were written before that release was cut and never revisited, and reading them cost a session's
worth of wrong conclusions about what was pending. When a release is cut, reconcile this file:

```bash
# does <tag> contain the file that implements <entry>?
git ls-tree -r <tag> --name-only | grep -c '<TheClass>.kt'
```

Use `git ls-tree`, not `git cat-file -e` inside a shell loop — the latter's exit code interacts badly
with `&&`/`||` chains and silently reported the opposite answer while this was being checked.

---

## 🐞 The transcript sheet's height follows its content — one line explains most of #27's follow-up

**Read from source on 2026-09-07, not measured.** `TranscriptSheet.kt:201`:

```kotlin
LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false))
```

`fill = false` lets the list take only the height of what it has *composed so far*, so the sheet grows
as you scroll. That single line predicts nearly everything mirror176 reported in #27: it opens at about
half height, grows towards fullscreen as you scroll down, the playback controls appear to be "revealed"
because a growing list pushes them, rotating a fullscreen sheet drops it back to half (a fresh measure),
and reopening it later opens fullscreen (the retained list state composes more at once). It reads as
intermittent because it is content- and scroll-dependent, not random.

Likely fix: `Modifier.weight(1f)`. The sheet already sets `skipPartiallyExpanded = true` and its own
doc says "near full height", so filling is what was intended. **Verify on a device before believing
this** — and check what an always-near-full sheet looks like for a three-line transcript.

Same report, same area, cheap and independent: **the delete / re-transcribe confirmation closes the
sheet before you have confirmed**, and cancelling leaves it closed. Only close it when a delete is
actually performed. Note the agreed item above about removing "Transcribe again" from the sheet
removes half of this on its own.

---

## 🔵 Transcript reading: mirror176's design proposals from #27

Not defects. Recorded so they are not lost, and so the next person to open that screen sees them
together rather than as four separate asks.

- **Previous / next call from inside the transcript.** He reaches a transcript, reads it, and has to
  go back out to reach the next call. If this is built, the order must follow the list's current sort
  and filter, not the underlying unsorted order — otherwise "next" means something different from what
  the screen shows.
- **A transcript that is fullscreen all the time**, with the number, date and time in its header. He
  argues the half-height state buys nothing once the header tells you whose call it is. Depends on the
  height bug above being fixed first; they may turn out to be the same piece of work.
- **A way to deselect a highlighted line.** Rotation jumps to the highlight, and with no way to clear
  it, putting the phone down and picking it up at an angle throws away where you were reading. Two
  parts: let a highlight be cleared, and prefer restoring the scroll position over jumping to it.
- **Richer filtering and sorting of the call list**, which is what makes the prev/next ordering
  question above real.

---

## 🔵 Targeting Android 17 (API 37) will break mDNS discovery until we ask for it

Android 17 makes `ACCESS_LOCAL_NETWORK` a runtime permission and gates `NsdManager`, mDNS and every
local-network socket behind it. **It is enforced by target, not by device**: at `targetSdk 36` we keep
local-network access implicitly through `INTERNET`, so nothing is broken today — a reporter already on
Android 17 (issue #23) reaches discovery fine. The day `targetSdk` goes to 37, `AdbMdns` finds nothing
on every phone, and pairing dies with it.

What it needs when we bump: declare `ACCESS_LOCAL_NETWORK`, request it at runtime before the first
discovery, and handle denial with a real message rather than a silent nothing. Do NOT request it while
we still target 36 — Google's own guidance is that it is not enforced there.

Reference: <https://developer.android.com/privacy-and-security/local-network-permission>. Shizuku's
13.7 fork already declares `ACCESS_LOCAL_NETWORK`, `USE_LOOPBACK_INTERFACE`, `NEARBY_WIFI_DEVICES` and
`CHANGE_WIFI_MULTICAST_STATE`; we declare only `INTERNET`, which is worth re-reading at that point.

---

## 🔵 #30 follow-up: a setting to stop CallVault managing Wireless debugging

Asked for by mirror176 in #30 (2026-09-10). Agreed by the maintainer 2026-09-11, and promised in the
issue.

**What 2.3.0 already does:** CallVault only turns Wireless debugging off if CallVault turned it on
(`WirelessDebuggingPolicy.mayRelease`, pref `wd_enabled_by_us`). A switch the user flips is left alone.

**The ask:**

1. **An on/off setting for that automation**, in Settings or as a button on the home screen. With it
   off, CallVault leaves Wireless debugging to the user.
2. **Show when CallVault is leaving the switch alone.** Today the only sign is a log line ("Leaving
   Wireless debugging on … the user switched it on, not us"). He asks for something the user can see.

**Decided (maintainer, 2026-09-11): "off" means CallVault never turns Wireless debugging off.** It may
still turn it on when the recorder needs a way back in. The other reading — never turning it on — was
rejected: a dead recorder could not come back without USB debugging or loopback, and the next call
could be missed. The cost of the chosen reading is that Wireless debugging stays on, leaving a network
port open; the setting's description should say so.

**Check before building:**

- **The transport rule still wins.** Dropping adbd's last transport kills the daemon whatever the
  setting says.
- **An old switch already exists:** `wd_disable_when_idle` ("Turn off Wireless debugging when idle",
  default off, from persistent-server mode, `AppPreferences.kt`). Check whether it is still shown or read
  before adding a second one; it may be the right home for this.
- **Standalone mode only.** Shizuku does not use Wireless debugging for us, so the row should say it
  does nothing there.
- **One place turns it off:** `AdbShell.releaseWirelessDebugging`. Check the setting there, not at each
  caller — three callers had to agree before #30 was fixed.
- **Onboarding:** probably not; this is a power-user setting.

---

## Current state — 2026-09-11

**Released:** `v2.3.0` (versionCode **20350**), published 2026-09-11. Asset `CallVault.apk`, downloaded back
and verified byte-identical to the tested build (sha256 `5b795a26…0add`). `origin/main` = `756f27a`.
It carries everything once planned as 2.2.1, which never shipped on its own. The next release must be
above 20350.

**🧪 Shipped, waiting on a reporter or the maintainer:** stuck-mic auto-heal (tester's OP13), #33 progress
curve, #31 manual-run notification, #34, #35, Save + full-screen log viewer (#28/#29, mirror176), #23
(Xiaomi discovery), #24 (Shizuku hide mode).

**Backlogged, not in 2.3.0:** #32, #36, #37, #38, F-Droid, and a setting to switch off CallVault's
Wireless debugging management (#30 follow-up).

The 2026-08-24 block below is stale — it describes the 2.1.0 stack — and is kept only as history.

## Current state — 2026-08-24

**157 commits** sit unreleased on `feat/speaker-labels` → `spike/summarisation` →
`worktree-feat+transcription-engine`, all documented under **[Unreleased]** in `CHANGELOG.md` as
**2.1.0**. None of the three branches is merged or pushed. That is a whole major release in a stack
of three unmerged branches; the longer it stacks, the more a single bad merge costs.

`ciVersionName` is already **2.1.0** and `versionCode` **20100** (an earlier entry here said to bump
to 1.6.0 — stale, written before the version scheme moved). 20100 clears the 10720 floor.

The maintainer is running this build daily for a few days before cutting the release.

### ✅ Favourites, min-duration discard, storage cap — VERIFIED 2026-08-30

Three of the four things the D6 row bundled. `19a018d`, `ce9fee8`, `1afaecc`.

**Favourites.** A star on the playback header and a one-tap "Starred" chip on Home that only appears
once something is starred. Its own table at `transcripts.db` v7 — deliberately NOT a tag, because a
tag can be renamed or deleted by the user and this row now carries a promise.

**Discard short recordings** (off by default). Applied on both capture paths through one shared
policy object, before the catalog, the transcription queue and StorageRouter. Refuses to delete on a
duration of 0 — that is the extractor failing to parse the container, not an instantaneous call.

**Storage cap** (off by default). Oldest device copies first, stops the moment it is under, and a
starred recording is **never** taken even when that means the cap cannot be met. Device copies only.

🚨 **The bug this nearly shipped with, recorded so it is not reintroduced:** both `RetentionScheduler`
and `RetentionSweepWorker` treated "no age period" as "nothing to sweep for". That was true until the
cap existed. A cap set on a phone with no age period would have had its daily job cancelled out from
under it and would never have run once, while Settings showed it enabled. Both gates now test the cap
too. **Any future reason to sweep must be added to both.**

**What is NOT done from that row:** nothing else — D6 is now complete. What remains unbuilt from the
D list is D1 (live level meter), D2 (record private, publish complete), D5 (notification actions),
D9 (per-app VoIP whitelist + lock-screen ask).

**Since updated:** D10 and the off-Wi-Fi notification shipped as `d79eb72`; the Stop action as
`5f127c9`; the BCR sidecar as `0f3b3c6`. D5's QS tile was dropped by the maintainer as unwanted, and
**D5's "flag a moment" is NOT built** — see the note below.

### 🧪 Switching between an app call and a phone call — ONE file (`fcbff97`, 2026-08-30)

Reported by the maintainer: WhatsApp call → answer a cell call → return → the app call came back as
a **second** recording. The audio was never lost (the first half is published when the phone call is
answered) but one conversation in two files is not one recording.

**The constraint that shapes the whole design:** the app capture MUST release the microphone for the
phone call. A second voice AudioRecord open during a carrier recording silently drops the user's own
side — see [[no-second-voice-capture-during-call]]. So this is a *suspend*, not the pause added the
same day: the pause keeps the mic, and using it here would trade a split app recording for a
half-broken phone recording.

Encoder, muxer and fd stay open; new records are acquired on the way back. Re-acquiring mid-recording
was already proven — `retakeMic` does it on One UI.

**Two things that were nearly bugs, recorded so they are not reintroduced:**
- The feeder threads ended themselves on a failed read. Correct for a broken capture, fatal for a
  suspended one — the thread would be gone before the phone call finished. They now wait.
- `CaptureAudit.released()` was being called twice for the same id (once on suspend, once in stop),
  which logs a misleading "already released" line into the exact record a stuck-microphone report is
  read from. The id is zeroed on suspend.

**Reverse direction needs nothing.** Nothing stops a carrier recording when an app call starts, and
`mayStartNow` blocks an app recording while a phone call is up, so cell → app → cell was already one
continuous carrier file.

**Open risk, only a real call can settle it:** whether the far-party submix re-attaches after the
phone call. If it does not, the second half is near-side only — logged explicitly rather than failing
silently, so the log will say so.

### ✅ D5 "mark a moment" — BUILT 2026-08-30 on both paths (`abd5ae0`)

The maintainer chose the both-paths option, so app calls gained their own ongoing notification
(Mark + Stop; no Pause, because the daemon's VoIP capture has none to drive). D9's per-app whitelist
followed in the next commit. The note below is kept for the reasoning, which still holds.

### 🅿️ Original framing — D5 "flag a moment", before the decision

Pause/Resume already existed. Stop is done. Flag is the remaining third and it is **carrier-only**
unless a whole ongoing notification is built for the VoIP path, which today posts only error and
"ended" notifications and has no action surface at all.

That matters specifically for this maintainer: on 2026-08-30 three of the four recordings on the
OP12 that day were `voip-WhatsApp`. A carrier-only flag button would be nearly useless to the person
asking for it, so it was not built on the quiet assumption that some flag is better than none.

Cost if it is wanted: an in-memory list of offsets during the call, an elapsed-audio clock on the
engine (wall clock minus accumulated pause, since paused time is not written to the file), persisting
at finalize against the FINAL name to survive the call-log rename, a `recording_flags` table at v8
joined to the cascade, and chips on the playback screen that seek. Roughly favourites-sized for the
carrier path; roughly double that if the VoIP path gets its own ongoing notification.

**Confirmed on the OP12 by the maintainer, 2026-08-30**: the star, the Starred filter chip and both
new Settings rows all work. Installed as release 2.1.2 over 2.1.2; the v6 → v7 migration applied
cleanly on a real database.

**Still proven only by unit test**, because no real event has exercised them yet: that a genuinely
short call is discarded, and that a cap sweep deletes oldest-first while skipping a starred
recording. Neither needs chasing — they will settle themselves in ordinary use — but do not write
either into the README as a measured result until one has actually fired.

### 🅿️ Trash / recycle bin — built 2026-08-29, then REVERTED and PARKED

Built end to end and reverted the same day. The maintainer's verdict was that it was more machinery
than the problem deserved: *"I think we overdid it with the trash."* Agreed — a delete already has a
confirmation, and the bin added a setting, a Home mode, a purge schedule and two folder-scanning code
paths to protect against a mis-tap that is already guarded once.

**Do not rebuild it from scratch without reading this.** The design worked, and the reverted commits
are `1ad9391`, `d47c8d2`, `1cd5de3`, `2d59385`.

**What the design got right, if it ever comes back:**

- **Rename in place; never move or copy.** A move needs `FLAG_SUPPORTS_MOVE`, which not every SAF
  provider offers, and the copy-and-delete fallback would download and re-upload the file on the Drive
  folder — a hundred megabytes over mobile data to delete something. A rename transfers no bytes and
  is exactly as reversible. All state lives in the file name, so no table can disagree with the folder.
- **Filter at `RecordingsRepository.enumerateFolder`**, the single funnel every listing goes through.
  `UntrackedRecordings` and `DriveCatalogRepair` both use it, which is what stopped a trashed file
  reading as untracked and being deleted on the *live* retention schedule.

**Three traps that cost real time, and would again:**

1. 🚨 **Trashing must not run `TranscriptCascade`.** The transcript, summary, note and tags belong to
   a recording that can still come back; taking them on the way in makes a restore return silent
   audio. `RecordingCatalog.removeName` grew a `cascade` flag for that one caller.
2. 🚨 **`RetentionScheduler` cancels the daily sweep when retention is off — the default — and that
   sweep is the only thing that empties the trash.** So the bin never purged for most users and grew
   without bound. The purge code was written to run "regardless of whether retention is on", which was
   true of the worker and false of the world.
3. 🚨 **Every delete path has to be routed, not just the per-row one.** Bulk multi-select delete
   bypassed the bin entirely on the first pass — the easiest place in the app to destroy more than was
   meant. Per-copy delete correctly stays permanent, since the other copy survives.

**And the question that surfaced two of those:** retention and the bin are independent. Retention
deletes **permanently** and does not fill the bin, so a 7-day retention is 7 days, not 7 + 30. That is
right — retention exists to bound storage — but nothing in the UI said so, and it is the first thing
anyone asks.

### ⛔ E3 — off-Wi-Fi reboot re-arm — SPIKE DONE 2026-08-29, **UNSOLVABLE without root. Do not re-investigate.**

Off-Wi-Fi recording ships (v1.4.0): `adb tcpip` puts adbd on a TCP port and the app connects to
127.0.0.1, so a call records with no network. **The port does not survive a reboot**, and re-arming it
means sending `tcpip:<port>` *through an existing ADB connection* — which off Wi-Fi you do not have.
To get a connection you need the port; to open the port you need a connection.

## Four doors tried. Three are shut, one is untested.

Measured on the OP9 and the AOSP emulator, 2026-08-29. The post-reboot state was reproduced without
rebooting: Wi-Fi off, then `adb usb` to drop adbd back to USB-only (`service.adb.tcp.port` → 0).

| Door | Result |
|---|---|
| `setprop persist.adb.tcp.port` from shell — would open the port at every boot and end the problem outright | ❌ `Failed to set property` — **and it fails on the AOSP emulator too**, so it is SELinux, not an OEM choice. Only adbd may write it, which is the deadlock restated |
| `setprop service.adb.tcp.port` from shell | ❌ same |
| `settings put global adb_wifi_enabled 1` with Wi-Fi off — the app holds `WRITE_SECURE_SETTINGS`, so this is the one lever it *can* pull after a reboot | ❌ the platform **resets it to 0**. Control with Wi-Fi on: it sticks. So the gate is the *network*, not our permission |
| The phone's own **hotspot** as a substitute network | ❌ **tested by the maintainer by hand, 2026-08-29: Wireless Debugging cannot be enabled with only a hotspot up.** Soft-AP does not satisfy it. `cmd wifi start-softap` also refuses uid 2000, so this could only ever have been checked by a person |

## Verdict

**No code-only escape exists.** Every route to opening the port runs through adbd, and adbd only takes
the instruction over a connection that cannot exist yet. This is not a missing API we have failed to
find; it is the shape of the problem.

**All four doors are now shut.** Reboot away from a real Wi-Fi network and the privileged bridge stays
down until the phone next reaches one. Nothing in the app can change that, and no amount of further
searching will: this is a platform property, verified on AOSP as well as on two OEM ROMs.

## Second pass, 2026-08-29 — harder look, four more doors, still shut

Re-opened deliberately: the VoIP "impossible" verdict was overturned once by exactly this kind of
push, so the first spike was treated as suspect rather than final.

- ✅ **The premise is now measured, not assumed.** The OP9 was actually rebooted:
  `service.adb.tcp.port` comes back **empty**. The deadlock is real on this hardware.
- 🔎 **The first spike's negative was stronger than it claimed.** During that test the OP9 had a SIM
  and live mobile data (`rmnet_data1` holding a global IPv6). So Wireless Debugging refused **with a
  working network present** — it wants *Wi-Fi specifically*. That kills the whole family of ideas
  built on "give it some other interface": USB tethering, a VPN `tun`, mobile data, Ethernet.
- ❌ **`android.debug.IAdbManager` / `cmd adb`** — a binder service the first pass never looked at. Its
  shell surface is queries only (`is-wifi-supported`, `is-wifi-qr-supported`); the interesting methods
  (`enablePairingByPairingCode`, `allowWirelessDebugging`) are `signature|privileged` and unreachable
  from our uid.
- ❌ **`/dev/socket/adbd`** — adbd holds a **listening unix socket**, present on a fresh boot with no
  Wi-Fi and no TCP port. Exactly the shape of a door: no network needed at all. But **even shell gets
  `Permission denied` stat-ing it**, so an ordinary app is nowhere near it.

**The one condition that could not be reproduced:** Wi-Fi radio *on* but not associated with any
network. `cmd wifi disconnect` refuses uid 2000 and the phone cannot be moved out of range on demand.
It matters because if WD only needs the radio rather than an association, the whole problem collapses
into "turn Wi-Fi on, you need not connect to anything". Evidence against: the maintainer meets this in
real life, and most people leave Wi-Fi enabled — which suggests radio-on alone is not enough. **Not
proof. Worth one deliberate check next time the phone is genuinely away from any known network.**

## Third pass, 2026-08-29 — the spoofing angles, and the one useful thing they turned up

Maintainer confirmed by hand that **Wi-Fi radio on but unassociated is still refused**, closing the
condition the second pass could not reach. Then: can the association be *faked*, or the USB transport?

**The gate, in the system's own words** — caught in logcat at the moment of refusal:

    AdbDebuggingManager: Not connected to any wireless network. Not enabling adbwifi.

That is `system_server` reading `WifiManager`'s own connection state. Nothing an app or a shell can
influence; there is no setting in between to lie to.

**Why "we already have shell" does not help, precisely.** The kernel's denial, verbatim:

    avc: denied { set } for property=persist.adb.tcp.port pid=… uid=2000 gid=2000
         scontext=u:r:shell:s0 tcontext=u:object_r:default_prop:s0
         tclass=property_service permissive=0

And the reason it is not an inconsistency that adbd *can* set these: **adbd runs as uid `shell` too**,
but in the `adbd` SELinux **domain**, while our shells run in the `shell` domain. Same uid, different
domain, different rights. `service.adb.tcp.port` sits in `adbd_config_prop`; `persist.adb.tcp.port` is
plain `default_prop` — and `shell` may set neither. `adb root` is refused outright (`ro.debuggable=0`,
`ro.build.type=user`).

**USB cannot be faked either.** `sys.usb.config` is already `midi,adb`, so adb is *configured* — but a
USB device needs a host to enumerate it, and there is no transport without a computer on the other
end. Forcing the functions from shell just restarts the gadget and kills the session.

## 🔑 The useful finding: it needs an ASSOCIATION, not INTERNET

"Not connected to any wireless network" means no station association. It does **not** mean no working
internet. **Any** access point satisfies it, for a few seconds, with no login and no connectivity:

- **a second phone's hotspot** — the maintainer carries two;
- a car's Wi-Fi, a hotel or café AP nobody logs into, any open network.

That changes the advice from *"wait until you get home"* to **"associate with any AP for ten seconds,
including your other phone's hotspot"** — which is very often available when a real network is not.
Worth saying in the README and in the recovery prompt, because it is a materially smaller ask.

## Mitigations that are now clearly the right answer

1. **Warn before the reboot.** `ACTION_SHUTDOWN` arrives while the bridge is still up. If offline mode
   is on and Wi-Fi is not associated, say so *then* — the one moment the user can still act.
2. **Notify after the boot**, when the listener is down, rather than letting a missed call be the
   notification.

## Fourth pass, 2026-08-29 — four parallel research tracks + AOSP source. **Closed for good.**

### The gate, from AOSP source, byte-identical in every release 12 → main

`AdbDebuggingManager.getCurrentWifiApInfo()`:

```java
WifiInfo wifiInfo = wifiManager.getConnectionInfo();
if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
    Slog.i(TAG, "Not connected to any wireless network. Not enabling adbwifi.");
```

An **infrastructure STA association** check — not `NetworkCapabilities`, not `getActiveNetwork()`. On
null the handler writes `ADB_WIFI_ENABLED=0` and breaks; the TLS server is never started.

### Why every "make our own network" idea fails, with the reason

P2P, `startLocalOnlyHotspot()`, SoftAP and Wi-Fi Aware all live on **separate interfaces** and none
writes a `networkId` into the **STA** `WifiInfo`; several switch STA off, which independently forces
−1. `WifiP2pServiceImpl` has zero `NetworkAgent` references, so P2P can never present as
`TRANSPORT_WIFI` at all. That is why the maintainer's hotspot test failed, and the whole family was
never viable.

### Device owner: dead, and now for a stated reason

`DevicePolicyManagerService`'s `GLOBAL_SETTINGS_ALLOWLIST` **does** contain `ADB_WIFI_ENABLED`, so a
device-owner app could flip it from app code with no shell — genuinely new, and it looked like the
loop-breaker. But the gate sits **downstream of the setting**. It changes *who may ask*, not the
answer. Not worth the device-owner enrolment UX.

### The race window does not exist — measured, with a validated instrument

The claim that adbd briefly starts a listener before teardown was tested directly on the OP9:
baseline `::`-bound listeners captured from `/proc/net/tcp6`, then `adb_wifi_enabled=1`, then **200
rapid on-device polls**. **No new listener, ever.** Setting reset to 0.

🚨 **The first attempt at this test was invalid and the control caught it.** Polling
`service.adb.tls.port` / `persist.adb.tls_server.enable` returned empty *even with Wi-Fi on and the
server demonstrably running* — a blind instrument producing a false negative. The working detectors
are `/proc/net/tcp6` (state `0A`) and, from the host, `adb mdns services` showing
`_adb-tls-connect._tcp`. **Always run the positive control before believing a negative.**

Confirmed in passing: the TLS server binds `in6addr_any` — **all interfaces, loopback included** — on
an **ephemeral** port (mDNS-advertised, not 5555). So there is no interface obstacle once it runs.

### Independent corroboration that this is closed by design

`droserasprout/io.drsr.hotspotadb` is an Xposed/LSPosed module whose entire purpose is removing this
restriction. It hooks the exact three call sites and injects a **synthetic** `AdbConnectionInfo` with a
forged BSSID, because the real call returns null. **Magisk + Zygisk required; no non-root variant.**
When a root module has to fabricate a BSSID, the door is shut.

### Also: the teardown is continuous

A receiver disables wireless debugging on Wi-Fi off, disconnect, empty BSSID **or a BSSID change from
roaming**, and teardown calls `kick_all_tcp_tls_transports()`. So "enable it at home and walk out" was
never going to survive the walk either.

### 🔑 What this pass is actually worth: the recovery is far cheaper than we tell users

Neither gate consults `NET_CAPABILITY_VALIDATED`. **A bare association with no internet passes.** So
recovery is not "get home to Wi-Fi", it is:

- **another phone's hotspot** — the maintainer carries two;
- a café or hotel AP nobody logs into; a car's Wi-Fi; a travel router with nothing plugged in.

Our copy says "needs Wi-Fi", which users read as "needs internet". **That is one string, and it turns a
dead end into a ten-second fix.**

### One free diagnostic worth wiring in

`logcat -s AdbDebuggingManager` — the line `Not connected to any wireless network. Not enabling
adbwifi.` settles this entire class of bug report instantly. Worth capturing specifically, given the
256 KiB ring rotates within minutes.

### 🆕 A competitor nobody had found: `LyoSU/cally`

https://github.com/LyoSU/cally — *"Pixel call recorder for stock Android via Shizuku — no root...
Dual-track VOICE_UPLINK+DOWNLINK with 5-step fallback ladder... opt-in cloud transcription."* Almost
exactly CallVault's shape, cloud-optional where we are on-device. **It was not in the 2026-08-27
competitive research.** Worth its own look.

## ⛔ The MIC-fallback idea is DEAD — and it would have been worse than doing nothing

I proposed recording from the app's own uid when the bridge is down, and framed the cost as a privacy
trade. **That was wrong on the facts.** Verified against AOSP source at every tag from 12 to 16:

**An ordinary app uid capturing during a call receives digital zeros.** Not degraded audio, not the
near party, not a quiet far party — `AudioFlinger` `memset`s the buffer to 0 while `startRecording()`
reports success. `AudioPolicyService::updateUidStates_l()` sets `allowCapture=false` for any uid
lacking `CAPTURE_AUDIO_OUTPUT` or `BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION`.

- **Speakerphone makes no difference** — the decision references no route or device; it is
  route-independent by construction.
- **VoIP is the same gate** — `is_state_in_call()` covers `MODE_IN_COMMUNICATION` too.
- **Every source is remapped anyway.** `Engine.cpp` rewrites MIC/VOICE_RECOGNITION/CAMCORDER to
  `VOICE_COMMUNICATION` during a call, which routes to a physical mic with **AEC and NS applied** —
  the one path engineered to remove the speaker leakage an acoustic fallback would need.
- **MediaProjection cannot reach it either**, twice over: carrier downlink is a device→device patch
  that never consults mix matching, and the VoIP usage allowlist for an ordinary app is exactly
  `{UNKNOWN, MEDIA, GAME}`. 🚨 The trap: `addMatchingUid(<voip uid>)` **registers successfully and
  yields silence** — CTS asserts precisely this. It would have produced a clean-looking file of zeros.
- **Bluetooth SCO** is closed twice: `startBluetoothSco()` is ignored during a call, and the SCO mic
  never carries Telephony Rx.
- **No role or permission on 14/15/16 helps.** The three roles granting call-audio permissions are all
  `systemOnly`, `visible="false"`, static.

**So a "degraded recording" is not available at any quality. The only thing on offer was a silent file
that passes every size, waveform and success check** — which is strictly worse than recording nothing,
because it fails invisibly. Do not revisit.

**Why the shell bridge works, stated properly:** `packages/Shell/AndroidManifest.xml` grants exactly
`CAPTURE_AUDIO_OUTPUT`, `MODIFY_AUDIO_ROUTING`, `CALL_AUDIO_INTERCEPTION`, `CAPTURE_MEDIA_OUTPUT` and
`CAPTURE_VOICE_COMMUNICATION_OUTPUT`. Shell is the only uid on a stock device holding that set. The
bridge is not a workaround we found; it is the only door in the building.

**History, for the record:** Android 9 added the `CAPTURE_AUDIO_OUTPUT` gate and the in-call source
remap — that is when the direct door closed, not Android 10. Android 10 then closed the acoustic
fallback. The accessibility + `VOICE_RECOGNITION` loophole is still live in AOSP `main` but only
*un-silences*; the remap still applies, so it yields the AEC'd uplink mic — near party only, far party
actively cancelled. Play-banned since 2022-05-11. That is what Cube ACR ships.

## 🆕 Two things this turned up in OUR code

1. **`AudioRecordingConfiguration.isClientSilenced()` — public API since API 30.** It is the supported
   way to detect that a capture is being fed zeros. **Any path that only checks "did `startRecording()`
   succeed" reports false success on every blocked route.** This is directly relevant to our
   silent-recording bug class and is worth wiring in regardless of anything else here.
2. 🚨 **Possible Android 13 VoIP gap.** `CAPTURE_VOICE_COMMUNICATION_OUTPUT` was added to
   `packages/Shell/AndroidManifest.xml` **only in Android 14** — absent at `android-13.0.0_r1`. Our
   VoIP loopback path depends on it, so on Android 13 and earlier the shell daemon may be unable to
   register that mix at all. OEM Shell manifests vary. **Wants on-device confirmation before anyone
   files it as a bug** — see [[voip-recording-feasibility]].



Record from the **app's own uid with plain `MIC`**, when the privileged bridge is down. No ADB, no
shell, no Wi-Fi. It captures the near party always and the far party only on speakerphone — the
behaviour of every pre-ADB call recorder.

🚨 **The cost is not technical, it is what CallVault currently is.** The app declares **no
`RECORD_AUDIO` at all** — every capture happens in the shell-uid daemon, and the app process never
touches the microphone. That is a real privacy property and arguably a selling point. Adding a MIC
fallback trades it for degraded recording in a rare state. **Do not do this without deciding that
trade explicitly.**

## What is still worth doing, and it is small

We cannot fix it. We can stop it being **silent**, which the demand research says is what users punish
hardest — a bridge that collapses quietly is the top reason people abandon this category (24 sources).
Today the app tries to re-arm, fails, logs a warning, and the user finds out by missing a call or by
opening the app and reading the status card.

The mitigation is a **notification when offline recording is enabled and the loopback listener is
down** — "recording is paused until this phone reaches Wi-Fi once" — fired after boot rather than at
the moment a call is missed. That is a small, contained piece of work and it converts the worst
property of this hole (invisibility) into an inconvenience.

✅ **DONE 2026-08-30 (`d79eb72`)** — `SilentFailureNotifier`, raised from the boot path where the
failure is genuinely invisible, and only from there: with the app open the Home status card already
says it, and two mouths saying the same thing is how a warning becomes noise. Self-clearing, and
mode-aware so a Shizuku user is told about Shizuku rather than about Wi-Fi. The Settings row also
now states the reboot limitation up front, in the corrected "join any network for a few seconds, no
internet needed" wording. Still needs a real off-Wi-Fi reboot to confirm it fires.

**And the README must state the limitation plainly** rather than leaving it to look like an oversight.

### 🅿️ Capture fallback ladder with RMS audibility check — PARKED 2026-08-29, before any work

`cally`'s design, and the competitive research called it "the single most valuable idea in the
landscape for CallVault": try capture strategies in order — `DualUplinkDownlink → DualMicDownlink →
SingleVoiceCallStereo → SingleVoiceCallMono → SingleMic` — measuring RMS against an adaptive noise
floor on each stream, dropping a rung when a stream is silent, and caching the winner per device
fingerprint.

**Parked without starting, on the maintainer's call, and the reasoning is worth keeping:** the ladder
is insurance against silent recordings, and *we do not have a silent-recording problem*. Carrier and
VoIP capture are both reliable in daily use, with no user reports in a long time; the one incident it
was argued from has been downgraded above as self-inflicted. It is a large change to the most
safety-critical code in the app, bought to fix something that is not broken.

**What we do have, if this is ever revisited:** `VoipCaptureSession.farPartyHeard` is a peak-threshold
audibility check on the VoIP path only. The carrier paths judge the *file* (`CallOutcome`, under 1 KB
→ `NO_AUDIO`), which catches an empty file and not a full-length recording of silence. Nothing
anywhere retries with a different strategy, and nothing caches per device.

### 🅿️ E1 — non-UI `InCallService` — spike done, PARKED 2026-08-29

**Parked after the spike answered the question, not before.** The mechanism works; the reason it was
top of the list does not. Carrier calls would gain an authoritative number, direction and state at the
earliest moment, plus a foreground lift — a solid improvement, no longer a structural one, because the
VoIP half is impossible (see below). Spike reverted; the design and every measurement are kept here so
picking it up again costs nothing.

**To resume:** re-apply `da035bf`, re-grant the companion role, and start from
`CallSessionManager` — the InCallService becomes the authoritative source for carrier calls with the
broadcast path as fallback, and the two must not race. Also: any grant step **must read the value
back**, because `appops set` returns exit 0 while doing nothing on both OnePlus ROMs.

Telecom binds our app at `onCallAdded` — the earliest moment a call exists — giving the number,
direction, state and the VoIP/carrier distinction **by construction** rather than by inference. The
bind also lifts the process to foreground, which is why BCR says it avoids Android 12+'s background
microphone limitation and needs no boot receiver.

**Prior art, already studied (see `research/2026-08-27-competitive/09-capture-techniques.md` §1.7).**
BCR uses `CONTROL_INCALL_EXPERIENCE`, which needs root or system. **Our own upstream,
ShizuCallRecorder, found the non-root equivalent**: the `MANAGE_ONGOING_CALLS` appop is
`signature|appop`, so an appop grant is a legitimate path, and Telecom's `InCallController` has a
single `||` on it. Works Android 12–16; Android 11 has no appop branch and cannot.

## 🚨 The documented primary path is DEAD on the maintainer's phones — measured 2026-08-29

| | OP12 · OxygenOS · Android 16 | OP9 · ColorOS · Android 14 |
|---|---|---|
| `appops set … MANAGE_ONGOING_CALLS allow` | ❌ silently ignored, **exit 0** | ❌ ignored |
| `appops set` for *any* op (control) | ❌ also ignored — it is the ROM, not the op | ❌ |
| `cmd companiondevice associate … COMPANION_DEVICE_WATCH` | ✅ association created | ✅ association created |
| `cmd role get-role-holders … COMPANION_DEVICE_WATCH` | ✅ **holds the role** | ❌ **role not held** |

So the `companiondevice` route is not a fallback here, it is **the** route — and it is confirmed
working only on Android 16. On Android 14/ColorOS the association exists while the role does not, so
the capability may not follow.

⚠️ **`appops set` returning exit 0 while doing nothing** is the trap: any code that grants and assumes
success will believe it worked. Read the value back, always.

⚠️ Running `associate` twice creates a **duplicate** association. Done accidentally on the OP9 while
testing and removed with `cmd companiondevice disassociate 0 <pkg> <mac>`.

⚠️ A fake association was left on **both** phones during this research (mac `00:11:22:33:44:55`). It
does not appear in Settings' paired list and is removed on uninstall.

## ✅ The spike answered it: Telecom binds us on OxygenOS 16

Real outgoing call on the OP12, 2026-08-29 (`da035bf`):

    12:36:29.836  onCallAdded:   direction=outgoing selfManaged=false state=9 (CONNECTING)
    12:36:44.983  onCallRemoved: direction=outgoing selfManaged=false state=7 (DISCONNECTED)

**Direction and the self-managed flag both correct, from the platform**, on a phone where the appop
grant is silently blocked — so the companion-device role *does* carry the capability on Android 16.

The recording for that call is stamped `20260829_123630.877`, about **one second after** the callback.
📐 That is a proxy, not a direct measurement of our detection latency — the file stamp is when the
recording was named, not when the broadcast arrived, and the logcat ring had already rotated past the
call's start. It bounds the gain rather than stating it.

## 🚨 A WhatsApp call produces NO callback — the VoIP case does not work, and cannot

Measured on the OP12, 2026-08-29, immediately after the carrier call above. An 18-second WhatsApp
call was recorded normally by CallVault (`…voip-WhatsApp…ogg`, `farPartyHeard=true`) and **our
`InCallService` received nothing at all** — no `onCallAdded`, no `onCallRemoved`, with the logcat ring
still holding the carrier call from two minutes earlier.

`dumpsys telecom` says why, and it is not our binding:

    12:36:29  Enter SIM_CALL / MODE_IN_CALL / TC@161            ← the carrier call, we saw it
    12:38:59  CommSess{uid=10394, created=12:38:42, callId=none} ← WhatsApp: a session, not a Call

**`callId=none`.** WhatsApp never registers its calls with Telecom as a self-managed
`ConnectionService`, so Telecom has no `Call` object to hand anybody. No `InCallService` — ours,
BCR's, or the dialer's — can see a call that was never given to Telecom. `supportsSelfMg?true` on our
bind is necessary and not sufficient: it says we *would* be told, if anyone told Telecom.

**This corrects the research** (`09-capture-techniques.md` §1.7 and the synthesis), which presented the
VoIP/carrier distinction as coming "by construction" from the bind. For apps that register with
Telecom — Signal uses `ConnectionService` — it would. For WhatsApp on this phone it does not, and
WhatsApp is the maintainer's main VoIP case. **Untested: Signal, Telegram.**

**What survives, and it is still worth having:** carrier calls gain an authoritative number,
direction and state at the earliest moment a call exists, plus the foreground lift. And the *absence*
of a callback becomes a usable negative signal — if our own VoIP detection fires and Telecom said
nothing, it really is not a carrier call. That is weaker than the research promised but is still
better than inferring both sides.

**The OP9 is a useful negative control** — it holds the association but *not* the role. If it binds
there too, the role is not what is doing the work and the mechanism is something else.

## Next step is a spike, not the feature

`telecom is-non-ui-in-call-service-bound com.baba.callvault` returns **false** on both, which proves
nothing yet — we have no `InCallService` declared, so there is nothing for Telecom to bind. The
decisive test is a manifest entry plus an empty service: if Telecom binds it, E1 is worth building
properly; if not, an hour was spent instead of days. **Do that before writing any of the feature.**

### 🔵 Remove the "Transcribe again" button — agreed 2026-08-29

Drop the retranscribe action from the transcript sheet before the release.

**Why.** It exists because the model or the language pin might have been wrong, which was a real
worry while transcription was being built and the defaults were still moving. Once the version ships
with a settled model and a pinned language it stops earning its place: it costs minutes of CPU and
battery, it is one of several actions on a sheet the user reaches to *read* something, and the failure
it repairs is one they will now almost never hit.

🚨 **Do not simply delete the call.** `TranscriptRepository.retry` is also how a FAILED transcript is
retried, and the nightly queue deliberately skips FAILED so an undecodable file is not attempted every
night for ever. Removing the button must leave a way to retry a *failed* one — otherwise a recording
that failed once can never be transcribed again by any route. The likely shape is: keep retry where
the row shows FAILED, drop it where the transcript is DONE.

Agreed with the maintainer on 2026-08-29: "it won't be necessary once we release the version."

### 🟢 Seven calls recorded zero audio on the OP12 — DOWNGRADED 2026-08-29, likely self-inflicted

**The maintainer's assessment, 2026-08-29:** these almost certainly came from a period of active
experimentation with capture code, not from a defect in a shipped build. *"Except for cases where we
have been playing around with features and code, the app is really stable — it records both cell and
VoIP reliably and I haven't gotten any issues about that in a long time."*

Kept rather than deleted, because the log evidence below is real and would matter if it recurred on a
build nobody was changing. But it should **not** be cited as an open field defect, and it was — the
capture fallback ladder was argued for partly on its strength.

### 🔵 Original report — kept for the evidence


Found while verifying the 2.1.0 install on the maintainer's daily driver. Seven consecutive **carrier**
calls, 11:43 → 13:16 on 2026-08-25, each produced a file of **exactly 98 bytes**:

```
11:43  in   גבריאל 2b     98 B      13:02  out  יצחק 2b לוי   98 B
11:56  in   גבריאל 2b     98 B      13:09  out  גבריאל 2b     98 B
12:36  out  גבריאל 2b     98 B      13:16  in   גבריאל 2b     98 B
12:37  in   גבריאל 2b     98 B
```

98 bytes is `OpusHead` + `OpusTags` and then end-of-file — both Opus headers (mono, 48 kHz, pre-skip
312) and **not one audio packet**. `ffprobe` reports `End of file`; the playback screen draws a flat
line and the row shows `0 KB`. The encoder was initialised and the container opened; no PCM ever
reached it.

**The window is clean and bracketed.** `11:32` before it is 1.5 MB and healthy; `16:08`, `16:10`
(WhatsApp) and `16:12` after it are all healthy. Nothing outside 11:43–13:16 is affected, on any day.
The device is in **standalone** mode — Shizuku is not installed on it — so the wrong-host and
stale-service failure modes in [[only-one-recorder-host]] and [[shizuku-mode-capture-rules]] are ruled
out as written.

**Why it is not diagnosed.** The debug log for that window was deleted before anyone looked, so the
decisive evidence is gone — the same way it was gone for the stuck-microphone report above. The two
candidate stories the artefacts cannot separate:

1. **Collateral from that morning's testing.** 11:43–13:16 is exactly when the mode-switch, keep-alive
   and WD-lease bugs were being worked, and this phone was the far end of those test calls. A daemon
   killed or a host torn down underneath a live capture would look precisely like this.
2. **A real capture failure that the ~16:01 install happened to clear** — in which case it can ship.

Nothing in a 98-byte file distinguishes those, and guessing between them is how two wrong conclusions
got reached earlier the same day.

**The plan, agreed with the maintainer 2026-08-25:** logging is switched back on on the OP12 and this
waits for a recurrence. It is **not** a release gate — three later calls on the same phone, carrier and
VoIP, recorded, transcribed and summarised correctly.

**If it recurs, the log now answers it** — which is what the diagnostics pass that day was for. Look for,
in order: the `CaptureAudit` line for the capture (was a microphone opened at all, and what did
`release()` actually do), the daemon's own merged `CV:RecorderServer` / `CV:DirectCapture` lines (did
the `AudioRecord` ever reach RECORDING), the config header (mode, resilient, transport), and whether a
teardown or mode switch lands inside the call. See [[diagnosing-a-user-report]].

### ✅ Refuse to transcribe a recording over 15 minutes — DONE 2026-08-25, on every path

Decided while closing the test matrix: the long-call OOM is **not** a release gate, because shipping as
it stands is acceptable — but transcribing a call over ~15 minutes decodes the whole file and can
exhaust the heap, so the app should stop rather than try and die.

**Agreed for now:** a hard refusal with a modal explaining why, on any recording longer than 15 minutes.
Not a silent skip and not a spinner that ends in a crash — the user should be told the recording is too
long to transcribe on-device yet.

**Deliberately deferred:** actually making long transcription work (streaming or chunked decode rather
than whole-file). That is the real fix and it needs its own investigation; see
[[long-call-decode-oom]] for the two known causes and the costed options.

The English-language transcription gate is **done** — confirmed working on a real call.

**Done, and wider than agreed.** The refusal first landed at the manual tap only; it now sits in
`TranscriptionRunner.runOne` and `TranscriptionQueue.pending`, so the nightly sweep and the per-call
automatic runs inherit it too. Those two previously walked straight into the OOM this entry exists to
prevent — and a too-long recording also held the oldest slots of the nightly batch for ever, starving
every call behind it. See the entry below for the one part still missing.

### 🔵 Transcribing long calls — the blow-up is our decoder, not whisper — researched 2026-08-26

The 15-minute refusal exists because `AudioDecoder` builds the **entire** file in memory, four times over,
before whisper sees a single sample (`AudioDecoder.kt`, `decodePcm`):

```
sink.write(chunk)               // whole file accumulates in a ByteArrayOutputStream
val bytes = sink.toByteArray()  // full copy #1 — both alive at once
val shorts = ShortArray(...)    // full copy #2
pcm16ToMonoFloat  → FloatArray  // full copy #3
resampleTo16k     → FloatArray  // full copy #4
```

A 60-minute call at 48 kHz mono 16-bit is ~346 MB of raw PCM, and `ByteArrayOutputStream` doubles its
buffer as it grows, so the peak is ~700 MB before the copies begin; the float array is another ~690 MB.
At 15 minutes it is already ~170 MB with copies, which is exactly why the limit sits there.

**Whisper is not the constraint.** It works on 30-second windows natively and never needs the whole
file. We hand it everything because our decoder produces everything.

**The fix (Option A): stream the decode and call whisper per window.** No runtime change, no model
change, no re-download, and speaker labels survive — they are built on our own two-channel capture and
would not survive a runtime move. Care needed on window boundaries so words are not cut in half;
overlap or VAD-aligned cuts are the usual answer.

**Rejected: switching to sherpa-onnx / ONNX Runtime.** Investigated properly because a user pointed at
[anti-vocale](https://github.com/RisorseArtificiali/anti-vocale), which advertises selectable NNAPI and
long-audio support. Both claims dissolve on inspection:

- **It caps at 10 minutes** (`AudioPreprocessor.kt:49-50`) — tighter than ours. It has not solved this.
- **Hebrew is the killer.** sherpa's Whisper path has an unfixed preprocessing bug (#2900), and its
  non-Whisper models do not speak Hebrew. Already settled in
  `docs/dev-notes/2026-08-16-on-device-transcription-design.md:76-81`.
- It would cost every user a 326–988 MB re-download and add a second runtime beside llama.cpp.

**On NNAPI specifically, so nobody re-litigates it:** it *is* reachable through ORT, and ORT on F-Droid
is a solved problem — `dev.davidv.translator` builds it from source and reproduces bit-for-bit on the
buildserver. But ORT's own docs warn the NNAPI provider falls back to `nnapi-reference` for unsupported
ops, which is **slower** than ORT's optimised CPU kernels; NNAPI was deprecated in Android 15; and
Google states it expects most devices to use the CPU backend in future. **Qualcomm QNN is closed to us
outright** — the Maven artifact declares `Qualcomm AI Hub Model License` / `scm:not_public`, and F-Droid
explicitly rejects the "but it is on Maven Central" defence.

### ✅ Enable the ARM CPU features in the ggml build — SHIPPED 2026-08-26 (`2ce45c8`)

**Done.** Measured end to end in the app on the OP9: summarisation 153.2 s → 88.2 s (1.74×),
transcription 88.0 s → 75.1 s (1.17×). Seven CPU variants ship in the APK and one is chosen from HWCAP
at load — verified selecting `armv8.2_2` on the OP9 and `armv8.6_1` on the OP12. +2.3 MB packaged.

**Repack was kept ON after measuring it, and the first estimate was wrong.** On a 484 MB model it added
252 MB and looked like "half the model size again"; on a 2,871 MB model it added 245 MB. The cost is
**flat, not proportional** — roughly 250 MB whatever the model, about 6.5% of the projected peak with
the real summariser, for 63% of the speed gain. The CPU variants themselves cost exactly zero: peak was
identical to the byte with repack off and with the stock build. The one-line lever to disable it is
documented in `CMakeLists.txt` if a phone ever refuses to load the model. The real 3.46 GB model's peak
is still unmeasured — it was not on any test phone — so that remains an extrapolation (~3.85 GB
projected, range 3.7–4.2, on a 7.4 GB phone).

Original entry below, kept for the reasoning.



The native build compiles whisper.cpp and llama.cpp with **no `-march` flags**, so ggml's dotprod, fp16
and i8mm kernels are all `#ifdef`'d out and `GGML_CPU_REPACK` is inert — the hot loop runs a
six-instruction emulation of a single `SDOT`. Verified from the generated CMake cache, not inferred.

Measured on the OP12 at the app's production thread count:

| | today | with ARM flags |
|---|---|---|
| Summarisation prefill | 97.5 t/s | **309.7 t/s** |
| Summarisation generation | 69.2 t/s | 84.1 t/s |
| Transcription encode | 4848 ms | 4073 ms |

Transcription gains less because Q5_0/Q5_1 have no i8mm path; the summariser's Q4_K does.

**Ship it as one APK** with `-DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON`: seven Android CPU
variants, selected from HWCAP at load. Verified picking `armv8.6_1` on the OP12 and `armv8.2_2` on the
OP9 (which correctly declined i8mm). No ABI floor raise, no SIGILL on older phones, ~4.5 MB.

Wiring: `llamacv.cpp` must call `ggml_backend_load_all_from_path(nativeLibraryDir)` rather than
`ggml_backend_load_all()`, and `whispercv.cpp` needs the call added. `useLegacyPackaging = true` is
already set, which this depends on. Invalidates the stored RTF calibration.

**GPU is not worth it on this SoC:** Qualcomm's own Adreno 750 numbers are 1.27× prefill and **2.1×
slower** generation than CPU, and whisper.cpp on OpenCL currently asserts and dies on Adreno. Generation
is memory-bandwidth-bound and the 8 Gen 3's ~77 GB/s is shared by CPU, GPU and NPU alike.

**Three incidental findings while measuring:** the comment at `CMakeLists.txt:17` claiming the NDK ships
no OpenMP runtime is factually wrong (it does; the real trap is that it resolves the *shared* libomp,
which is not in the APK — keep the flag off, fix the comment); `GGML_LLAMAFILE` lands off by accident
because whisper's ggml is added first, which happens to match upstream's Android policy but should be
commented so nobody "fixes" it; and `TranscriptionEngine.kt` carries two contradictory KDoc blocks about
thread count, one of which is wrong.

### 🅿️ In-app bug report / feature request → GitHub — designed 2026-08-26, PARKED

Asked for, costed, then parked in the same session. Park is deliberate: it depends on the log
pseudonymisation landing first, because the whole premise is that reports go to a **public** repo.

**Three shapes, and only one is worth building.**

| | Cost | Verdict |
|---|---|---|
| **A. Prefilled issue link** — open `github.com/<repo>/issues/new` with an Issue Form template and query parameters filling the fields | ~1 day | **This one.** No auth, no shipped secret, no backend, no new dependency, nothing for F-Droid to object to. |
| **B. OAuth device flow** — user signs in, issue posted as them; device flow needs only a public `client_id`, so no secret ships | several days | Buys little over A, which already lands them on a filled-in form. Still cannot carry the log — GitHub caps an issue body at 65,536 chars. |
| **C. Backend proxy holding a token** | highest, ongoing | Only if you want reports from people without GitHub accounts. Needs hosting, rate limiting and real abuse control — an unauthenticated endpoint that creates public issues **will** be found and spammed — and the report would transit our server, undercutting the privacy story. |

**Build two entry points, not one:**

- **Request a feature** — carries no user data at all, just app version and device.
- **Report a bug** — prefills only the **config header** (mode, app version, Android version, device, and
  the settings that change the capture path). A few hundred bytes, fits comfortably in a URL, and it is
  exactly the block that answers the first three questions of any report. The log stays a deliberate,
  separate attachment that the user chooses.

**Non-negotiables, whichever shape:** never auto-submit; show exactly what will be sent before sending;
default to *not* including the log. An app that silently uploads diagnostics contradicts its own README,
which states analytics and crash reports "None exist".

**What already exists** and does not need rebuilding: the report export writes
`cacheDir/logs/callvault_debug_report.txt` and is shared through the FileProvider, and the app already
talks to `api.github.com` unauthenticated for release checks.

**The dependency:** phone numbers are redacted, but **contact names were not** — they ride into the log
inside recording filenames (`<timestamp>_<direction>_<contact name>.ogg`). Pseudonymisation is being
implemented separately. Do not ship a button that encourages users to attach logs until that has landed.

**Known limitation of option A:** it needs a GitHub account, so some users will file nothing. Ship A
first and find out whether that is actually a problem before paying for C.

### 🔵 F-Droid reproducibility traps that already apply — noted 2026-08-26

These bite any app shipping native code, and CallVault already does:

- **`.so` files are stripped by Gradle by default**, which breaks reproducible builds — needs
  `packagingOptions { doNotStrip '**/*.so' }`.
- **Pin the NDK exactly.** The same NDK version on different host platforms still produces differing
  binaries.
- **16 KB page alignment** — `zipalign --page-size 16 --pad-like-apksigner`.

The existing CMake/submodule setup is otherwise already on the right side of F-Droid's scanner, because
anything compiled in the `build:` phase runs after the scan and never needs a `scanignore`.

### 🔵 A call skipped for being too long says so only in the log — found 2026-08-25

The 15-minute transcription limit is now enforced in `TranscriptionRunner.runOne` and
`TranscriptionQueue.pending`, so every route inherits it — nightly sweep, per-call, manual tap and
queue drain alike. Before that it lived at the manual tap alone, and automatic transcription walked
straight into the OOM the limit exists to prevent.

**What is still missing is the user's side of it.** The tap raises a dialog, but that dialog only
fires when the **call log** knows the duration. When the call log has no duration and the container
does — which is exactly the case for app calls — the recording is skipped with nothing but a `W`
line to show for it, and the row simply never transcribes. No status, no explanation.

There is no cheap surface to reuse. `TranscriptEntry.errorMessage` is stored but rendered nowhere,
and `TranscriptStatus`/`TranscriptRowAction` carry only NONE/BUSY/OPEN/RETRY. Reusing `FAILED` would
be worse than the gap: a red retry icon with no reason, **and** it would bar those calls from every
future automatic run — including the ones that become possible the moment the limit is lifted.

**The fix** is a `TOO_LONG` status, a row action for it and one string — roughly half a day. It
disappears entirely once long calls are transcribed in pieces, so weigh it against just doing that.

Related: the skip is deliberately *not* recorded as a failure, so nothing has to be un-marked later.

### 🔴 Shell process left with the microphone on after a carrier call — reported 2026-08-25, UNDIAGNOSED

A user on **1.5.8** reported that after an ordinary carrier call (not VoIP) the shell process stayed
alive with the microphone on. A force-stop cleared it; no reboot needed.

**What the two reports do show.** At 10:58:02–04 the system log has `AudioPolicyService:
updateUidStates_l() current->uid=2000 current->pid=26306 allowCapture=1`, repeatedly — a **shell-uid
process in an active capture state four minutes after the last call ended** (10:54:02). That is
consistent with the report.

**What they cannot show, and why.** The app-side teardown is complete and identical for both recent
calls: `stop requested` → `HandoffEncoder finished` → `handoff encode DONE` → `handoff capture input
released`, with **no `stopHandoff` failure logged**. But whether the *daemon* released its own
`AudioRecord` is invisible:

- The debug export is **app-process only** — it contains no `CV:RecorderServer`, `CV:HandoffSource` or
  `CV:DirectCapture` lines at all, so `releaseHeld` could not appear in it either way.
- The system report *would* keep those tags (the filter passes everything `CV:`), but it is truncated to
  the most recent 69 matching lines and covers **10:58:02 → 10:58:19** — seventeen seconds, ending four
  minutes after the call. The teardown had already rotated out of the logcat ring.

So the decisive evidence was gone before the report was taken. **Fixing that is the first job**, not
guessing at the cause.

**Fixed already, because it is provable without the missing logs:** `RecorderConnection.service?.
stopHandoff()` on a null binder was a silent no-op that `runCatching` reported as success — "the daemon
released its microphone" and "there was nobody to ask" wrote identical logs. Both outcomes are now
logged distinctly, in the handoff and daemon paths alike. That does not explain the report, but it
removes one way the logs could hide it.

**Next, in order:**
1. Make the daemon's teardown visible in the app's own debug export, or make the system report cover
   enough history to include the call — the ring was grown to 8M at 09:01 and the export still scanned
   only 9,865 lines, so the growth needs verifying rather than assuming.
2. Ask the reporter whether it is reproducible, and for a report captured **within a minute** of the
   call while the mic indicator is still lit.

### 🔵 Direction for VoIP calls — investigated 2026-08-25, parked

A phone call shows incoming/outgoing; an app call shows only which app it came from. Whether the
direction is obtainable at all was investigated rather than guessed:

**The call log cannot answer it.** Measured on the OP9: 3,000 rows, and *every one* written by
`com.android.phone/…TelephonyConnectionService`. WhatsApp writes nothing there, so there is no entry to
read a direction from. (The same fact is why a VoIP recording had no duration until it was read from the
file container instead.)

**The one real route is the calling app's notification.** Android 12+ `CallStyle` notifications carry
`android.callType` — 1 incoming, 2 ongoing — and `VoipCallerName` already parses that dump for the
caller name, so reading one more field is nearly free.

**What blocks it is timing, not access.** A VoIP call is detected when the audio mode becomes
`MODE_IN_COMMUNICATION`, which for an *incoming* call is **after the user answers** — by which point the
notification has flipped from incoming to ongoing. Sampled there, both directions report "ongoing".

Getting it right means sampling while the phone is still ringing, and each way of doing that has a cost:

| Approach | Cost |
|---|---|
| Hook the earlier `MODE_RINGTONE` transition | cheap — **if** the calling apps set it, which is unverified |
| Poll the notification dump while idle | the daemon shells out per dump; battery, and it does not work in Shizuku mode |
| `NotificationListenerService` | exact and real-time, but needs notification access — a heavy permission for a call recorder to ask for |

**The experiment that decides it** takes five minutes: ring the phone on WhatsApp and, *before
answering*, capture `dumpsys notification --noredact` and the audio mode. If `android.callType=1` is
present and the mode passes through `MODE_RINGTONE`, the cheap option works. If not, drop the idea.

Until then the current behaviour is honest: the app badge says where the call happened, and no direction
is invented that we cannot know.

### 🔵 The release gate, agreed 2026-08-24

Judged by **which failures are silent**, since a call recorder's worst outcome is losing a call
without anyone noticing.

| # | Item | Why it blocks |
|---|---|---|
| 1 | ~~Silent VoIP failure~~ | **DONE 2026-08-24.** See below. |
| 2 | **B8 — the speaker tap must not harm recording** | Highest severity left. This exact failure already happened once: the downlink probe silently took the near side off a recording and looked completely normal in the logs, the file size and the waveform. The tap now runs on every call. |
| 3 | **B5 — deletion and privacy** | Reading it on 2026-08-24 already found one: the retention sweep's *untracked* half deleted files without cascading, so transcripts outlived the recordings. Fixed (`UntrackedCascade`); the device checks are still unrun, and it is the privacy promise, not a nicety. Transcripts are full searchable text of private calls in a *separate* database with the cascade enforced by code rather than a foreign key. The retention-sweep path matters most — that is how calls actually expire. |
| 4 | **Transcription re-verified broadly** | Reduced scope: **one long call and one non-Hebrew language**. The last device pass found that every transcript came back empty for every user by default (`detect_language` means *exit after detecting*). Nineteen months of upstream whisper change deserves better than ten-second clips. |

**Explicitly NOT blocking: re-measuring transcription speed.** The estimate recalibrates itself from
real runs, so it heals in the field, and being wrong costs a misleading figure on a dialog that
already says you can stop at any time. The long call in #4 re-measures it for free.

**Also done 2026-08-24, not on the original list:** B7 rewritten (it still tested the deleted
ringback detector), and the CHANGELOG corrected — it told users the attribution came from the
network's ringback tone, which is both deleted and never true on this phone.

### ✅ Silent VoIP failure — fixed 2026-08-24

An app call that could not be recorded is now reported: a notification while the call is still
remembered, and a status-card entry that survives to tomorrow. Split by cause — a missing folder is
the user's and is recorded as an *excused* miss against that prerequisite; an absent daemon is ours
and is recorded as an *unexplained gap*. Silent until the setup has recorded at least one call
successfully, matching the carrier path's gate. Rule extracted to `VoipMissPolicy`, unit-tested.

**There is no retry, and there cannot be.** Capture depends on a dynamic audio policy the daemon
registers, and Android fixes a track's routing when the track is *created* — `startVoipRecording`
refuses with "policy was not armed before the call" for exactly this reason. Arming is already re-done
on every fresh daemon binder, which is what keeps the window small. A call landing inside it is lost
and no amount of waiting recovers it. **Do not re-propose a retry here.**

### 🔵 App calls get no speaker labels — found 2026-08-24, not scheduled

`VoipCaptureSession` is a **third** capture path beside the daemon's direct one and the app's handoff
one, and the only one with no `SpeakerTurnDetector`. So VoIP transcripts cannot be labelled at all.

Galling, because VoIP is the *easy* case: the two directions arrive as separate streams interleaved
LEFT near, RIGHT far, so the mapping is known by construction with nothing to infer. Adding the
detector is small. The real question it raises: the trusted mapping is currently **one global value
per device**, while VoIP's is fixed and the carrier's is an OEM detail — the two cannot share one
value on a phone where they disagree.

### 🔵 "A call was not recorded" may be a false positive

On 2026-08-21 the status card warned **"A call was not recorded — 13:06"**, and
`20260821_130658.098+0300_in_גבריאל 2b.ogg` (689 KB) was sitting in the recordings folder the whole
time. Either the warning fires on a recording that succeeded, or the recording finished without the
app registering it.

Worth chasing because this warning is the one the user is meant to trust: it is how a genuinely
missed call gets noticed at all, and a warning that cries wolf is worse than no warning. Observed
once, not reproduced, cause unknown.

Two other list oddities seen the same day, possibly the same root: a recording present on disk did
not appear in the list at all (today's 08:54), and the saved count moved 61 → 60 → 59 across a
session without anything being deleted.

### 🔵 Known gaps, agreed but not done

- **Per-recording summaries** — SHIPPED in 2.0.0. Plan at
  `docs/dev-notes/2026-08-21-summarisation-ui-plan.md`.

- **A Hebrew whisper fine-tune (`ivrit`) — RESEARCHED, PARKED 2026-08-23 at the maintainer's
  request.** Likely the largest remaining transcription-quality lever, and the research is done, so
  picking it up again is a decision rather than an investigation.

  The sibling project at `~/Desktop/Projects/AIDashboard` (`src/lib/calls/transcribe.ts`) documents
  that generic whisper on auto-detect **returns Arabic on Hebrew audio**, and that `ivrit` +
  `--language he` produced 6564 characters of clean Hebrew from a call the other paths mangled.

  | Candidate | Size | Licence |
  |---|---|---|
  | `ivrit-ai/whisper-large-v3-turbo-ggml` (official, fp16) | 1.62 GB | Apache-2.0 |
  | `Ibrerhim/ivrit-whisper-v3-turbo-q5_0-ggml` | 574 MB | **none stated** |

  **APK cost is zero** — models are downloaded, never bundled. The q5_0 build is byte-identical in
  size to the generic turbo we already ship and has a different SHA-256 (`6c1da92e…` against
  `39422170…`), so it is a real fine-tune rather than a re-upload. The open question is which to
  depend on: an unlicensed third-party re-quantisation, or three times the download.

  **Two constraints on "make it seamless".** The model has to be chosen at *download* time, not at
  transcribe time, so auto-detect cannot select it — the language is not known until after the
  transcription. And `ivrit`'s own language detection is degraded by the fine-tuning, which is why
  the sibling pins the language explicitly. Realistically: pinning Hebrew in Settings gets `ivrit`,
  auto-detect keeps generic.
- **Estimates are still one number times a length.** A short recording pays a fixed cost — loading a
  model, decoding the audio — that a real-time factor cannot express, so short calls are quoted
  optimistically. A two-part estimate (fixed overhead plus a per-second factor) would fit reality
  better. Not started; raised on 2026-08-21 after a 1:17 call was quoted two minutes.

---

## Current state — 2026-08-05, status reconciled against the tags 2026-08-14

Kept at the top so a session can start from disk instead of from recall. **Update it whenever a
release is cut, a branch lands, or something starts or stops being blocked.**

**Released:** `v1.5.7` is the latest release users can get (versionCode **10720**, tag `v1.5.7`, asset
`CallVault.apk`, published 2026-08-04 and verified byte-identical to the locally built artifact). Also
published, both **pre-releases** invisible to the in-app updater and **never to be merged**:
`v1.5.2-diag-scrcpy` (issue #18, branch `diag/scrcpy-only`) and `v1.5.7-loopbackdiag` (issue #22,
branch `diag/loopback-oneui`).

**What 1.5.7 shipped:**

- **Daemon + system log collection** (`2026-07-28-daemon-and-system-logs-design.md`). Ring grows on
  logging-enable and restores on disable; Share attaches a filtered, redacted logcat slice. Verified
  on the OP12.
- **CodeQL triage** of 2026-08-01: ten alerts dismissed with rationale on the alert itself, three
  relative-path-command **fixed** (`sh`/`pkill` by absolute path).
- **Retention actually deletes what it promises** — four faults, all found by measuring the OP12 on
  2026-08-04 and all fixed and device-verified the same day. See below.
- **The USB-mode warning is no longer hidden behind readiness**, `UNKNOWN` is surfaced instead of
  silently treated as safe, One UI 8's "Debugging only" is recognised as safe, the mode is resolved
  from `sys.usb.config` where `dumpsys usb` omits it, and the picker no longer spins on a confirming
  read-back that could not work. Device-verified except the `COULD_NOT_CHECK` message, which needs a
  phone whose mode was never read.

**Open:** issue **#22** (Galaxy S25, One UI 8.5) — the reporter holds `v1.5.7-loopbackdiag` and has not
yet sent logs. Nothing in 1.5.7 addresses their loopback failure. **1.5.7 will be offered to them as a
normal update, which replaces the diagnostic build and loses its instrumentation** — tell them not to
update if those logs are still wanted. Issue **#18 is closed**: the reporter found the cause himself
(Meta Ray-Ban glasses), written up in `2026-08-04-bluetooth-headsets-and-silent-recordings.md`.

**Waiting for the next release — this is the whole list, one item:** `fix/voip-carrier-collision`
(`75868ca`) — a carrier call could be mistaken for an app call on ROMs that route calls over IMS.
Merged to `main`, unit-tested, **not yet run on a device**; see the section below for what to check
when it ships. Note it is on **local `main` only** — `main` sits 4 commits ahead of `origin/main`.

**What each recent section actually shipped in, verified with `git ls-tree` on 2026-08-14.** The
sections below are the detail; this table is the truth about *release membership*:

| Entry (implementing file) | Shipped in |
|---|---|
| Keep-alive rewarm latch (`RewarmGate.kt`) | **v1.5.5** |
| Upload schedule in Settings, issue #20 (`SyncScheduleLabels.kt`) | **v1.5.5** |
| Settings "General" restructure (`SettingsSidebar.kt`) | **v1.5.5** |
| Resilient-recording ring + guard fix (`handoff/HandoffGeometry.kt`, `GUARD_FRAMES = 960`) | **v1.5.3** |
| No install while recording (`CallInProgressGate.kt`) | **v1.5.6** |
| Encoder validation (`EncoderLimits.kt`) | **v1.5.6** |
| VoIP/carrier collision (`VoipTelephonyGate.kt`) | **unreleased — `main` only** |

**The retention story, because it cost a day and the shape recurs.** With retention set to 7 days the
app showed a convincing 64 recordings going back exactly 7 days while **131 files had outlived the
window** (8 device, 123 Drive, oldest by 48 days). Four independent faults:

1. `deleteFile` cleared the catalog entry even when the delete failed, so a failed Drive delete made
   the file invisible *and* unreachable for ever. Fixed: the entry survives a failed delete.
2. The sweep walked only the catalog, so anything missing from it was exempt regardless of age. Fixed:
   it reads the folders too, gated on `RetentionPolicy.isEligible` (only names CallVault writes) and
   never deleting a file whose age is unknown.
3. `ExistingPeriodicWorkPolicy.UPDATE` ignored the new initial delay, so changing **Run at** moved
   nothing for up to a day. Fixed: `CANCEL_AND_REENQUEUE`.
4. **Google Drive renumbers the account slot inside its SAF URIs** (`acc=1` → `acc=4` here), which
   invalidates every stored Drive URI — uploads, deletes and listings all throw SecurityException, and
   re-picking the folder does not repair the URIs already stored. Fixed: `DriveCatalogRepair`
   re-points them against the live listing before each sweep. **This is a recurring hazard, not a
   one-off** — it will happen again whenever the user's Drive accounts change.

Device-verified 2026-08-04 across three sweeps: `deletedLocal=9`, then `deletedDrive=124`, then
`63 re-pointed, 1 forgotten`. The one pre-cutoff file the sweep deliberately left alone was
`callvault-signing.keystore`, sitting in the same Drive folder — the eligibility gate earning its keep
on its first real run.

**Hard constraint on the next release:** versionCode must exceed **10720** — what 1.5.7 shipped as, and
what the maintainer's OP12 now carries. The floor climbs faster than the version number: 1.5.6 shipped
as 10670, then test builds and a published diagnostic pre-release took it through 10678, 10680, 10690
and 10700-10714 before 1.5.7 was cut at 10720. Anything at or below the floor installs for most users
and silently fails on the devices that matter most. See the `release-version-bump` memory, and read the
phone before choosing.

**Device-verified 2026-07-31 (OnePlus 12, build `1.5.6-encoder` / 10661):** encoder validation does
*not* divert recording to the scrcpy fallback — output was mono 48 kHz, full duration, −16.9 dB mean.
The mid-call guard's device path is covered by unit tests only. **Known gap:** the new
`CV:EncoderLimits` line runs in the *daemon*, so it never reaches the app's debug log; it does reach
logcat, but logcat's default 256 KiB ring holds barely a minute on this phone (measured 2026-07-31:
123 KiB consumed in 26 s), so it had aged out before it could be read. An earlier note here blamed
ColorOS for filtering third-party logs — that was wrong, and re-tested: our lines are present. The
fix is the ring growth in `2026-07-28-daemon-and-system-logs-design.md`.

**Known limitation, parked, not planned:** a Bluetooth headset or smart glasses can make a carrier
recording silent — right size, right duration, no audio. This was the real cause of issue #18 (Meta
Ray-Ban glasses), found by the reporter after about a week. See
`2026-08-04-bluetooth-headsets-and-silent-recordings.md`. **If a silent-recording report ever arrives
again, ask what the audio was routed to before anything else.**

**Blocked on other people:** nothing.

**Written but unplanned:** `2026-07-28-daemon-and-system-logs-design.md` — a design for getting daemon
diagnostics into a bug report, with no implementation plan yet. Issue #18 is the standing argument for
it: twice, the answer lived in the daemon's process where no bug report can reach.

**Also argued for by issue #18, not yet scheduled:** `SILENT` detection (an all-zeros check on the
daemon's PCM, cheap on the direct path) and a settings snapshot in the log-export header (the export
carries device and version but not the toggles, which is why the VoIP question above needs a manual
test at all).

---

## 🟡 A carrier call could be mistaken for an app call — FIXED, THE ONE ITEM AWAITING RELEASE

**Branch `fix/voip-carrier-collision`, commit `75868ca`. Fold into the next release and device-test it
there.** Not device-tested: the fixed path cannot fire on the OP12 (see below), so the only thing a
device run proves is the absence of a regression — one WhatsApp call and one carrier call, both
recording as before.

App-call detection recognises a VoIP call by one signal, the audio mode being
`MODE_IN_COMMUNICATION`. Nothing enforced that a carrier call could not set the same mode; the only
thing separating the two paths was a comment in `VoipCallDetector` asserting they "cannot collide".
Wi-Fi calling and some VoLTE stacks carry the call over IMS and can present as
`MODE_IN_COMMUNICATION` — and there the app-call path would record a phone call the carrier path is
already recording, holding a plain `MIC` capture that contends with the dialer, made worse by the
1.5.5 microphone-reclaim logic taking it back mid-call.

`VoipTelephonyGate` makes the telephony call state the authority: no start while ringing or off-hook
(the ring matters — the mode moves around during call setup), and a running capture stops once a
carrier call is answered, signalled by the telephony broadcast because on such a ROM the mode never
changes and the mode listener never fires. Fails open on an unrecognised state, like
`CallInProgressGate`.

**Measured on the OP12, 2026-08-05, and worth keeping:** every carrier call went to `MODE_IN_CALL` set
by `com.android.server.telecom`; only WhatsApp used `MODE_IN_COMMUNICATION`. So the collision is real
in principle and absent on this hardware — which is exactly why it survived unnoticed.

**Where this came from:** a user report that "the mic is always on during a cell call", which turned
out not to be a bug at all. Measured on the OP12 mid-call: a live `AudioIn` thread, `Standby: no`,
`AUDIO_SOURCE_VOICE_CALL` on `AUDIO_DEVICE_IN_TELEPHONY_RX`, reading continuously for the duration of
the call and gone afterwards — the carrier recorder doing its job, attributed to `com.android.shell`
because that is the uid the daemon runs as. Android shows the privacy indicator for any active
capture and it cannot be suppressed. The reporter's belief that 1.5.6 did not do this was checked and
dropped: 1.5.6-era recordings exist, so the capture — and the indicator — was running then too.
**If this is reported again: the indicator is the recording. Ask whether recordings exist for the
period they think was quiet.** There is nothing to fix short of not recording, and the gap is
documentation — nothing in onboarding warns that the indicator appears on every call and is
attributed to Shell rather than CallVault.

**Checked against history, 2026-08-07, so nobody re-derives it.** There is no release in which a call
was recorded without a shell-uid capture. At `v1.4.7` the carrier path is `DirectAudioRecorderSession`
opening `AudioRecord` itself, annotated *"shell uid holds `CAPTURE_AUDIO_OUTPUT`; the daemon is not an
app"*. Direct capture only arrived in `v1.4.0` (`7df300a`); before it everything went through
scrcpy-server, which `ScrcpyConfig.kt:21` at **`v1.1.0`** — the earliest tag — describes as running
"with `app_process` … the shell user (UID 2000)". The app has **never** declared `RECORD_AUDIO`, so
shell was always the only possible attribution. Capture code between `v1.4.7` and `v1.5.7` moved only
for `EncoderLimits` bit-rate validation and 1.5.5's mic re-take, neither of which changes the source,
the uid, or how long the capture is held.

**What that check cannot cover:** it proves our capture never changed, not that the OS always
*displayed* it the same way. A ROM update altering how shell-uid captures are surfaced would look
exactly like a CallVault regression and leave no trace in this repo. If a second user reports it and
their recordings also check out, look there.

## 🔵 Our captures do not register with `AudioService`'s record tracking

Noticed while investigating the above, unexplained, and left alone deliberately. During a live carrier
call on 2026-08-05 the HAL-level capture was plainly running (`AudioIn_5C6`, frames read climbing)
while `dumpsys audio`'s record-activity log carried **no matching `rec start`** — and the same log
shows `rec stop` events at 10:22, 12:22 and 13:58 with no starts either. Starts register sometimes
(11:50 that day, and the 08:59 VoIP `MIC` capture) and not others.

It causes no stuck microphone and no lost audio, so it is not urgent. It does mean the OS's view of
who is recording disagrees with reality, which would affect anything keyed off record-configuration
callbacks. Worth understanding before relying on that API for anything.

---

## ✅ Don't install an update while a call is being recorded — SHIPPED in v1.5.6 (`4e46948`, merged `2e5c0dc`)

`CallInProgressGate.mayInstall()` is checked in `UpdateInstaller.installSilentlyViaShell` *before* the
heavy-operation lock; a blocked attempt returns `ShellResult.DEFERRED_CALL_IN_PROGRESS`, which clears
the pending tag and cancels the progress notification **without** falling back to the interactive
installer. It guards VoIP as well as telephony, because a VoIP call never sets the telephony call
state. Six unit tests. Known limit: a call that *starts* mid-install is not covered — aborting the
installer would leave a half-written APK, which is worse.

**Found the hard way, 2026-07-30.** An APK installed over the running app kills the app process, and
any recording in flight dies with it. Observed on the OP12: one call became two files, and the second
was mislabelled `out_` with no contact name, because on restart the app saw the already-running call
as a fresh outgoing one. Nothing was lost — the first file closed cleanly and the second picked up —
but the call is split, and the seam is silent for as long as the app takes to come back.

**Why it is not just an adb mishap.** `UpdateInstaller` installs over ADB exactly the same way. It is
tap-to-install, so a user initiates it, but nothing stops them tapping Install mid-call — and an
update notification is precisely the kind of thing people poke at idly. Sideloads and Obtainium
updates do it too. So the app can truncate the recording it exists to make.

**What.** Before `UpdateInstaller` takes `AdbShell.heavyOperationLock` and starts streaming the APK,
check whether a call is in progress; if it is, defer and say so ("Update will install after your
call"). Checking, downloading and notifying are unaffected — only the install step waits.

**Notes.** Small: a state check plus a deferral. The app already tracks call state in
`CallSessionManager`, so no new permission is needed. Re-trigger the deferred install from the IDLE
transition `PhoneStateReceiver` already sees.

**Known limit, worth stating rather than pretending otherwise.** A call that starts *during* an
install is a few seconds a pre-check cannot close. Closing it properly would mean aborting the
installer mid-stream, which is worse than the problem — a half-written APK is a broken app.

---

## ✅ VoIP near-party drops out on One UI — FIXED by re-taking the mic, SHIPPED in v1.5.5

On a Galaxy S24 FE, One UI **silences** our shell-uid MIC capture intermittently while the VoIP app
holds the mic — logged by the platform itself as `rec update uid:2000 src:MIC silenced
pack:com.android.shell`. The loser of that arbitration keeps receiving buffers full of zeros, so the
recording gets silent holes rather than failing. Measured: ~6 s lost from a 21 s call, matching a flat
-107 dB stretch in the file to within a second. The OnePlus 12 records the same call cleanly.

The platform's own bypass permission (`BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION`) is
`signature|privileged` and `pm grant` refuses it, so shell access cannot open it.

**Minimum viable response: report it.** `VoipCaptureSession` has `farPartyHeard` and no near-party
equivalent, and `substituted` counts only *missing* chunks — a silenced capture delivers present,
zero-filled ones, so the log reads `farPartyHeard=true, 2 silence-filled chunks` on a recording with
a six-second hole. A near-side silence check turns an invisible failure into a visible one.

**HOTWORD was tried and is dead** — it constructs, reports `STATE_INITIALIZED`, then `read()` returns
0 and the platform never registers the capture at all. Reverted.

**Separate bug it exposed, worth fixing on its own:** when the near feeder dies, `captureLoop` is
paced by `CHUNK_WAIT_MS` poll timeouts and encodes slower than real time — an 11 s call became ~6 s of
audio. Losing one source should cost that source, not the recording's length.

Full evidence: `2026-07-30-voip-near-party-silenced-on-one-ui.md`.

---

## 🟡 VoIP-only mode SHIPPED in 1.5.6; Shizuku coexistence still open

Investigated 2026-07-30, no code changed. Full write-up:
`2026-07-30-voip-only-mode-and-shizuku-coexistence.md`.

**✅ Done in 1.5.6.** The naming became a real switch instead: `CARRIER_RECORDING_ENABLED`, off =
phone calls ignored end to end. That turned out to be necessary rather than cosmetic — the
combination this entry described (both auto-record toggles off) is the **Ask me** state, and
`CallSessionManager` still sent `ACTION_STANDBY`, so an app-calls-only user was prompted on every
phone call. One new preference each side (`VOIP_AUTO_START` too), both defaulting to the old
behaviour; no existing state was duplicated.

**Shizuku does not crash; we kill it.** Both apps' helpers are children of an `adbd` shell, and any
`adbd` restart kills every process started over ADB — established here already and quoted from
Shizuku's maintainer in `transport-and-daemon-architecture.md`. CallVault restarts `adbd` in exactly
two places that matter: arming loopback (`tcpip:`, always destructive, once per boot) and enabling
Wireless debugging. The routine WD-off after each daemon launch is harmless *when USB debugging is on*
(pid measured unchanged), which is why users will report it as intermittent.

Cheapest honest response: detect Shizuku and warn before arming offline recording.

**Full Shizuku support researched 2026-07-30** — `2026-07-30-shizuku-support-feasibility.md`. It fits
better than expected (Shizuku's `UserService` *is* our daemon: our code, uid 2000, and the daemon
already runs its own shell commands via `ProcessBuilder`, so it needs no ADB), and the app touches the
daemon through one AIDL from five files, so most of the work is in how it starts.

**But the reliability argument fails.** Shizuku's server is itself an `adbd` child, so a screen-off
`adbd` restart kills it exactly as it kills ours — this repo already quotes Shizuku's maintainer on
that. Shizuku stops CallVault *causing* churn; it does not survive it. And it costs hands-free
operation after reboot, which is a stated differentiator.

Recommendation: warn first, extract a `PrivilegedProvider` abstraction regardless (worth it alone),
and treat full support as a product decision rather than a fix.

**Not chasing the reporters.** A set of diagnostic questions used to live here (USB debugging on?
offline recording on? does Shizuku stop once or repeatedly?). Dropped 2026-07-31: when Shizuku is
actually picked up, it gets tested properly on our own devices rather than reconstructed from
second-hand answers. The analysis above stands as a hypothesis until then.

---

## 🔵 README is out of date after 1.5.5

Screenshots predate the Settings panel and the two new wizard steps; **"Settings ▸ Experimental"** is
referenced throughout but the path is now **Settings ▸ General ▸ Experimental**; and the VoIP
compatibility table does not know Samsung/One UI works as of 1.5.5. Screenshots were already stale
before today — regenerating them needs care about real contacts in a public repo.

---

## ✅ Validate the encoder before recording into it — SHIPPED in v1.5.6 (`1ee77af`, merged `2e5c0dc`)

`EncoderLimits.resolveBitRate()` clamps the requested rate into the encoder's advertised range and
logs `encoder=… bitrate=[min..max] requested=… resolved=… sampleRate=… supported=… channels=…
supported=…`; `supports()` additionally requires `EncoderLimits.supportsFormat()`. Six unit tests.
Verified on the OP12 (`1.5.6-encoder`): the direct path is still chosen — mono output proves it, since
the scrcpy fallback is stereo. **But the log line is written by the daemon**, so it reaches neither the
app debug log nor a shareable report; it reaches logcat, but the default ring ages it out within about
a minute of a busy phone. Until the
daemon-logging design lands, this diagnostic cannot answer the bug reports it was built for.

`DirectAudioRecorderSession.hasEncoder()` checks only that an encoder for the MIME **exists** —
nothing verifies it supports our sample rate, channel count or bitrate, and `KEY_BIT_RATE` is set to
whatever the user picked. `MediaCodec` given an out-of-range combination does not reliably throw: it
can clamp or emit frames that decode to nothing, i.e. a correctly-sized file that plays silent.

Measured on a OnePlus 12: the software AAC encoder allows 8000-960000 bps and ≤6 channels, the
Qualcomm hardware one 4000-192000 and ≤2. The hardware encoder is gated behind
`special-codec required`, so it is not selected here — but a vendor that does not gate it would hand
us an encoder with different limits and nothing would notice.

**What.** Query the selected encoder's `AudioCapabilities`: clamp the bitrate into range, refuse the
direct path when the sample rate or channel count is unsupported (so it falls back to scrcpy rather
than producing silence), and **log the encoder name and its ranges once per recording**.

Raised by issue #18, which closed unexplained — see
`2026-07-28-issue-18-silent-carrier-recordings.md`. That log line would have answered it on day one.

---

## 🔵 Manual "Check for updates" in Settings

**Why.** A release only surfaces two ways: a check when the app opens, throttled to once per 6 hours
(`UpdateScheduler.checkNowIfDue`), and a 24-hour periodic worker. Open the app shortly *before* a
release lands and you cannot see that release until the throttle expires or the daily worker runs —
with no way to ask. This happened for real while testing 1.4.7: the phone checked at 14:21, the release
was published at 14:29, and the only way to force a check was toggling the update switch off and on,
which is not something a user would ever guess.

**What.** A row under Settings ▸ Updates that runs a check immediately and reports the outcome in
place — "You're up to date" / "Version X is available". Bypasses the 6-hour throttle, since the user
asked explicitly; keep the throttle for the automatic path so relaunches cannot hammer the GitHub API.

**Notes.** `UpdateScheduler` already has everything needed — a one-time `UpdateCheckWorker` request is
what `checkNowIfDue` enqueues. The work is the UI row, the in-place result, and not letting repeated
taps stack (unique work + KEEP, as the install path does).

---

## ✅ Keep-alive rewarm latch can permanently stop recording — SHIPPED in v1.5.5

**Found 2026-07-30 on the OP12: the daemon had been dead ~21 hours and the app never retried.**
`DaemonKeepAliveService.maybeRewarm` guards on a `rewarming` flag that only the worker thread clears.
The worker calls `ensureServerRunning` → `AdbShell.ensureConnected`, which is unbounded and blocks
forever on a half-dead (`CLOSE_WAIT`) connection. One hung attempt latches the flag true for the life
of the process, so the 60 s watchdog returns at its first line forever. Recording is dead until the
app is force-stopped — which no user will ever think to do.

Shipped in **v1.4.0** (`a2b248e`); any adbd churn triggers it (cable, screen-off restart, toggling a
debugging switch). Likely behind field reports of "it just stopped recording".

**Fixed 2026-07-30** by `RewarmGate` (expiring latch, unit-tested) plus a bounded relaunch that drops
the half-dead connection so the abandoned thread frees `heavyOperationLock`.

**Shipped in v1.5.5** (`RewarmGate.kt` is present in the v1.5.5, v1.5.6 and v1.5.7 trees), so the
exposure window was v1.4.0 → v1.5.3 and only users still on **v1.5.3 or older** are affected. This
entry read "Still to do: run it on a device, and ship it" until 2026-08-14, three releases after it
shipped — the mistake this file's header warning now exists to prevent. Still true: **no device run
was ever recorded for it**, so it shipped verified by unit tests alone.

Full diagnosis and the fix's shape: `2026-07-30-keepalive-rewarm-latch-wedge.md`.
The `ensureConnected` entry below is the same bug's root and is still open — layers 1-2 contain it
rather than remove it.

---

## ✅ Upload schedule is built but stranded in the wizard — issue #20, SHIPPED in v1.5.5

**Issue:** https://github.com/madkongo/CallVault/issues/20 (CathaEdulis, 2026-07-29)

**The feature already exists and works end to end.** `SyncScheduleMode` is `IMMEDIATE | DAILY |
WEEKLY`, with `SYNC_TIME_HOUR`/`SYNC_TIME_MINUTE`/`SYNC_DAY_OF_WEEK` alongside it.
`StorageRouter.route()` checks the mode and, for DAILY/WEEKLY, skips the immediate copy entirely and
hands off to `SyncScheduler`'s periodic sweep. Verified in code, not assumed.

**The bug is reachability.** The picker is rendered *only* in `WizardScreen`, and there is no way to
re-run the wizard. So the mode is decided once, during onboarding, defaulting to `IMMEDIATE` — and
after that no user can ever change it. That is exactly what the reporter hit: "I couldn't find it
anywhere."

**What.** Surface the existing picker in Settings. Cheap, because nothing new has to be built:

- The composable and its `scheduleModeTitleRes`/`scheduleModeDescRes` helpers exist in `WizardScreen`
  — extract them to a shared component rather than duplicating.
- The strings exist too (`wizard_schedule_*`, `wizard_ui_schedule_*`) and are **already translated in
  all ten locales**, so `checkTranslations` will not bite. Reusing a `wizard_`-prefixed key outside
  the wizard is slightly off; renaming means re-translating, so prefer keeping the keys and noting why.
- `SettingsScreen` already has the time-picker pattern (it borrows those same wizard strings for the
  retention sweep, around `SettingsScreen.kt:687`).
- Call `SyncScheduler.apply(context)` on change — `WizardViewModel:137` is the reference.

**Done 2026-07-30.** Exposed as a sub-section of **Storage**, alongside **Retention**, which moved from
its own top-level accordion into the same section. Zero new strings — `wizard_schedule_title` ("When to
upload to Drive") and `wizard_schedule_mode_label` ("Upload schedule") already existed in all ten
locales, so `checkTranslations` stayed green with no translation round. The mode/day labels were
extracted to `ui/common/SyncScheduleLabels.kt` so the wizard and Settings cannot drift. The old
`SECTION_RETENTION` key now opens Storage, so a user whose saved open-section was Retention lands on
the section that contains it instead of a collapsed screen. The schedule rows are hidden when the
storage target is LOCAL, where there is no upload to schedule.

**Fits with** the General-section restructure below; do them together if that one lands first.

**Note for the reply.** Deferring uploads batches the Drive app's "uploaded" notifications into one
run per day/week rather than one per call. It reduces the noise, it does not remove it — say so
plainly rather than letting him expect silence.

---

## ✅ Settings restructure: a "General" section — SHIPPED in v1.5.5

**Why.** Settings has grown top-level sections that are really peers of each other, so the screen reads
as a flat list of everything rather than a shape.

**What.** A new top-level **General** section, with today's sections becoming sub-sections inside it:

- General
  - Visual settings
  - Experimental *(keeps its own Resilience / VoIP sub-grouping)*
  - Updates

**Done 2026-07-30** on `feat/settings-sidebar`, together with opening Settings as a right-side panel
instead of a destination. Nine top-level sections became six. `SettingsSubHeader` gained a quieter
nested variant, because General ▸ Experimental ▸ Resilience is three levels deep and the inner
grouping otherwise rendered at the same weight as its parent.

**Notes.** `SettingsScreen` already has `SettingsSubHeader`, used for the Resilience/VoIP split inside
Experimental, so the nesting pattern exists. Section expand-state is persisted by key — keep the
existing keys where a section keeps its identity, or the user's expanded/collapsed state resets. That
is why `SECTION_EXPERIMENTAL` is still the string `"reliability"` after that rename.

---

## 🔵 Split `AppPreferences` into per-domain interfaces

**Why.** `AppPreferences` is 686 lines, 60 keys and 107 accessors, and every subsystem in the app
reaches into it: the knowledge-graph build on 2026-07-27 measured 188 edges and a betweenness of
0.190, bridging 33 of 243 communities — from `DaemonKeepAliveService` to `Theme` to
`VoipRecordingCoordinator`. That number is a symptom, not the finding.

The finding is *how* it clustered. Community detection split the class's own members into six groups,
and the dividing line is the **storage primitive, not the subject**: boolean writers in one group,
string writers in another, int accessors in a third, `getStringSet`/`setLong` in a fourth. Only the
storage/sync accessors clustered by meaning. There is no domain structure inside the file for the
algorithm to find, because the file has none below the comment headers.

**What.** Keep one `SharedPreferences` instance and the `Key` enum. Expose them through roughly nine
narrow interfaces — `RecordingPrefs`, `StoragePrefs`, `TransportPrefs`, `UpdatePrefs`,
`AppearancePrefs`, `FilterPrefs`, `DebugPrefs`… — each 5–12 methods, with `AppPreferences` as the
single implementation satisfying all of them. Consumers depend on the slice they actually use.

**The boundaries are already written in the file.** `Key` has 13 comment-delimited groups, and they
map nearly one-to-one onto communities that formed independently elsewhere in the graph: Storage
Routing / Sync Schedule, Retention, ADB, In-app updates, Persistent recorder server, Automation +
Filters, Developer & Debug, Audio quality, UI & Appearance. Those comments are doing an interface's
job. Cut along them and the split needs no new judgement.

**What this does not buy.** It does not decouple anything — `DaemonKeepAliveService` still needs its
settings. The wins are narrower and worth stating honestly: each consumer's dependency becomes
legible, tests can fake a 6-method interface instead of a 107-method class, and the "General" section
restructure above gets a seam to cut along. It is a wide, mechanical diff across most of the app, on
a file that has caused none of the recent failures — real value, no urgency. Do it *with* the Settings
restructure, not on its own.

---

## ⛔ Switching Wireless debugging off — NOT POSSIBLE, and why

Three attempts, all failed, all for the same underlying reason. **Do not try routes 1 or 2 again.**

**`adbd` only runs while USB debugging or Wireless debugging is enabled.** `service.adb.tcp.port` says
*where* `adbd` listens — it is not a reason for `adbd` to exist. With both switches off there is no
`adbd`, so there is nothing to launch the daemon over and nothing to keep it alive.

Measured on a OnePlus 12 (1.5.0-wdoff3, USB debugging off throughout):

```
11:02:15  Dropping Wireless debugging before launch
11:03:18  shell not ready within 60000ms (150 probes)   <- a full minute, never returned
11:03:20  Loopback self-healed after 1500ms             <- 1.5s AFTER WD was switched back on
```

The apparent success later in that log (11:04:25) is confounded — it is the exact moment USB debugging
was enabled, which starts `adbd`. **Every "it worked" observation in this investigation turned out to
have a second debugging switch on somewhere**, which is the single lesson most worth keeping.

- **Route 2 (make the daemon outlive `adbd`)** — impossible without root. Init kills the service's POSIX
  process group AND its cgroup on stop, explicitly so `setsid` cannot escape. Shizuku dies the same way
  ([#311](https://github.com/RikkaApps/Shizuku/issues/311)); its community's workaround is `adb tcpip`
  ([#864](https://github.com/RikkaApps/Shizuku/issues/864)), i.e. exactly our loopback — which does not
  solve it either.
- **Route 1 (launch over the loopback after dropping WD)** — cannot work, per the above. It also cost
  **two minutes of delayed readiness** at boot, since each attempt burns the full timeout. Reverted.

**One device difference:** on a Galaxy S24 FE the listener *did* return ~1.5 s after WD was dropped with
USB debugging off, so its `adbd` behaves differently from the OnePlus. If this is ever revisited, the
only defensible shape is **opportunistic and remembered**: after the daemon is up and idle (never on the
launch path), try the drop once, poll briefly, record the answer for that device — success keeps WD off,
failure re-enables it and never retries. That still leaves OnePlus-class devices with WD on.

**The real escape is to stop needing the daemon at call time** — Track A in
`capture-research-directions.md`. That is the only route that removes the debugging switch entirely.

---

## ✅ Control over what gets recorded — SHIPPED in 1.5.6

Three related asks, all about the user deciding rather than the rules deciding:

- **Decide per call, including app calls.** These were listed as two items ("manual VoIP recording" and
  "choose when to record") until 2026-07-31; the code says they are one. **Carrier already has it:**
  `RecordingNotificationHelper` posts a standby notification whose action is `ACTION_MANUAL_START`,
  plus pause/resume once running. **VoIP has nothing:** `VoipRecordingCoordinator` exposes only
  `onCallStarted`/`onCallEnded`, which are lifecycle callbacks the detector fires — there is no
  user-invocable entry point and no arm-without-starting mode. (An earlier version of this entry named
  the plumbing `VoipRecordingCoordinator.start/stop`. Those symbols do not exist.) The work is the
  arm-without-starting mode plus the carrier control surface extended to VoIP.
- **Undecided, deliberately kept separate:** a *prompt* at the start of a call. A dialog over a live
  call screen is a much more intrusive interaction than a notification you can ignore, it applies to
  both paths equally, and nothing has established it is wanted.
- **~~Turn cellular recording off independently~~ — already possible; see the VoIP-only entry above.**
  Both auto-record switches off with VoIP on *is* an app-calls-only recorder today, and
  `CallGapDetector` respects those flags so the health card will not nag. What is missing is only the
  naming. Careful when naming it: it must not silently disable recording for someone who expected it —
  default on, and state clearly what it does.

---

## ✅ Resilient recording on One UI — ring fix CONFIRMED, crackle fixed, SHIPPED in v1.5.3

**Root cause, confirmed against AOSP source rather than guessed.** The handoff sized the ring from
`AudioRecord.getBufferSizeInFrames()`. That returns `cblk->mBufferSizeInFrames`, a **logical value the
client rewrites on every attach** — it is *not* the field that sizes the ring. The physical ring is
`roundup(frameCount) * frameSize`, allocated immediately after the control block by
`AudioFlinger::TrackBase::TrackBase`. The two agree in stock AOSP only because both are seeded from the
same `frameCount`; nothing enforces it, and on a Galaxy S24 FE they diverged — 8192 reported against a
4096-frame allocation.

So this was never a Samsung quirk: **we were reading the wrong quantity**, and the OnePlus happened to
agree. Refusing the delivery was correct — trusting the report would have read 12 KB past the end.

**Fix:** derive the ring from the mapping, which is ground truth on every device —
`wrapFrames = min(roundup(reported), largest power of two fitting in (ashmemSize - dataOff) / frameSize)`.
Self-correcting, no device table, cannot over-read by construction.

**Also fixed alongside it:** `DATA_OFF` was hardcoded to 232, which is `sizeof(audio_track_cblk_t)` on
Android **13+** only — it is 228 on Android 12 and 224 on Android 11, and `minSdk` is 30. On those two
releases the ring was being read misaligned rather than failing loudly. Now derived from
`Build.VERSION.SDK_INT`.

**Trap for whoever tests this:** the native drain rounds whatever frame count it is given up to a power
of two and masks with it, so it must be passed `wrapFrames`, **not** `frameCount` — otherwise it
recreates the oversized ring and reads out of bounds, with the Java-side check now passing. Both call
sites were updated; a third caller added later would reintroduce the bug silently.

## Tested on a Galaxy S24 FE, 2026-07-30 — two results

**The ring-geometry fix is confirmed.** A 46 s carrier call with Resilient recording ON produced a
full-duration Opus file, mean −39.7 dB / peak −12.1 dB, and **no periodic dropouts**: silence-interval
`stdev/mean = 1.00` (irregular, i.e. conversational pauses), and click phase concentration `R = 0.03`
to `0.18` against every candidate wrap (1024/2048/4096/8192 frames) — a geometry error would cluster
near `R = 1.0`. The v1.5.2 fix works on the device it was written for.

**But the audio crackles, and the handoff is the cause.** Confirmed by A/B on the device: same phone,
same call type, same codec, one toggle.

| | crackle | mean | transients/s (>10x local) |
|---|---|---|---|
| Resilient ON | **yes** | −33.8 dB | 25.2 |
| Resilient OFF | no | −30.7 dB | 17.6 |

### Root cause: GUARD_FRAMES was smaller than one HAL write burst

`GUARD_FRAMES` held back **32 frames = 0.67 ms at 48 kHz**. But AudioFlinger's record thread does not
advance `mRear` sample by sample — it publishes a whole HAL period at a time, and while that copy is
in flight the frames just below `mRear` are **partially written**. HAL periods are typically 4-20 ms
(192-960 frames), so the guard was 6x to 30x too small and the drain read half-written frames.

That explains every property of the symptom: crackle rather than gaps (corrupt samples, not missing
ones), **aperiodic** (it depends where the 5 ms read cycle lands inside the write burst, which is why
the phase-concentration test against every candidate ring wrap came back R = 0.03-0.18), and
**vendor-specific** (a OnePlus 12 was clean throughout while every S24 FE call crackled).

Raised to **960 frames (20 ms)**, covering the largest common period. Cost: 20 ms more latency before
a frame reaches the encoder — irrelevant for call recording.

### Verified on the device, 2026-07-30

| build | transients/s (>10x local) | 3-6 kHz energy |
|---|---|---|
| handoff ON, guard 32 | 25.2 | 2.25% |
| handoff OFF (baseline) | 17.6 | 1.52% |
| **handoff ON, guard 960** | **16.3** | **0.39%** |

The fixed handoff now measures at or below the no-handoff baseline on both.

**A measurement lesson worth keeping.** With only the first two files, transient counts differed by
~40% (both swamped by speech consonants) and the spectra by a single point — the ear separated them
instantly and the metrics nearly did not. What discriminated cleanly was **3-6 kHz band energy**, and
only once a third file gave the scale. Do not read a flat two-way measurement here as "no defect".

### Daemon-kill test passed on One UI, 2026-07-30

The premise of the feature, demonstrated on Samsung for the first time. Mid-call, the daemon that
created the capture was `kill -9`'d:

- app pid **unchanged**, and the original `cv-handoff-drai` / `cv-handoff-enco` threads carried on
- the file grew straight through the kill with no stall (281 KB → 344 KB over the following 12 s)
- the keep-alive relaunched a fresh daemon behind it, as designed
- finalised as a valid 106 s Opus file
- **no seam**: only 7 of 300 windows within ±3 s of the kill dipped near the silence floor, and
  scattered rather than contiguous — ordinary conversational gaps, not a dropout

**A measurement trap, recorded so it is not re-walked.** A first pass compared 3-6 kHz energy before
(1.06%) and after (15.13%) the kill and looked alarming. It is an artefact: that metric is a
*proportion*, so it tracks level, not defects — loud passages read 0.1-1.6%, quiet ones 40-89%. A
per-5s time series showed the spikes at 10-20 s and 25-40 s, long before the kill, with the kill
window itself at 3.9%. Always plot the metric against time before attributing a difference to an
event.

**Follow-up worth doing:** derive the guard from the observed `mRear` step (the burst size the device
actually uses) rather than hardcoding 960.

---

## 🔵 "Test my setup" — prove the whole path works, before it matters

> **Superseded (2026-07-28) by [the setup-health design](2026-07-28-setup-health-status-design.md).**
> The button is gone: a test the user has to remember to press is not read by the people who need it
> most. The status card reports what real calls proved instead, and a call-log sweep catches calls
> CallVault never saw. The reasoning below still holds — only the shape of the answer changed.

**Why.** This app fails *silently*, and the failure is discovered after the call you needed. Every
recovery mechanism shipped so far (screen-lock USB fix, resilient recording, fast daemon recovery)
reduces the chance of a failure without ever telling the user whether their setup works **right now**.
Every naming bug in the VoIP feature was found by making real phone calls, because there is no other
way to exercise the path.

**What.** One action that runs the real pipeline end to end and reports which step failed:

1. ADB connection alive (and how long it took — this is where the ~75 s stall would show).
2. Daemon reachable, binder responsive.
3. Capture starts on the configured audio source.
4. Encoder produces non-silent frames.
5. File is created in the chosen SAF folder, catalogued, and appears in the list.
6. Clean teardown, and the test file removed.

Report per step, with the failing step named in plain language and a link to the setting that fixes it.

**Notes.** Do not fake it end-to-end: a test that stubs any step will pass while the real path is
broken, which is worse than no test. The daemon already exposes what is needed; a `VOICE_CALL` source
cannot be exercised outside a call, so the test should use `MIC` and say so, or capture briefly from
the configured source and report "could not verify without a live call" rather than implying more than
it checked. Reuse `voipFarPartyHeard`'s honesty pattern — report what was actually observed.

---

## 🔵 Per-app VoIP support, checked at runtime

**Why.** Settings currently says VoIP recording is experimental and "some apps block recording, and
this cannot be known until a call is under way". Half of that is now avoidable: whether an app opts out
of capture is readable **before** a call, from the audio flags its playback carries.

**What.** A per-app list under Settings ▸ Experimental ▸ VoIP calls — installed calling apps with a
real status each: verified working, not yet tried, or blocks capture. Turns a vague warning into a
fact, and tells the user *which* of their apps will work rather than leaving them to discover it.

**Notes.** The distinction that matters: `FLAG_NO_MEDIA_PROJECTION` (0x800) is bypassable by this
route and is what WhatsApp, Telegram and Signal all set; `FLAG_NO_SYSTEM_CAPTURE` (0x1000,
`ALLOW_CAPTURE_BY_NONE`) is checked before any permission and is **not** bypassable. Reading the flag
requires an active playback track, so it cannot be sampled at rest for an app that is not in a call —
expect "not yet tried" to be a real state, and record the observed result after each call instead of
promising a prediction. Feeds the README's tested-devices table.

---

## 🔵 `AdbShell.ensureConnected` is unbounded on the recording-start path

**Why.** It can block for ~75 s while a recording is trying to start. 1.4.6 capped one such read at
1.5 s after it caused calls to be missed entirely; this is the same class of problem, not yet fixed.
It was the agreed next priority before VoIP took over.

---

## 🔵 Smaller, known, and worth not forgetting

- **✅ Translations are complete and now enforced (2026-07-29).** Ten locales — pt-BR added, the other
  nine backfilled — at 445 translatable strings each. `./gradlew :app:checkTranslations` fails the
  build on a missing string, an orphaned one, or a placeholder that differs from the base; it gates
  `check` and `assembleRelease`, with `-PallowMissingTranslations=true` as the deliberate override.
  **This entry is the reason the check exists:** it previously read "three VoIP strings are
  untranslated" while eight locales were actually 46 behind and the three keys it named were in zero
  locales. Counting by hand is what failed, twice. Do not replace the check with a note.
- **Stale screenshots** in `docs/screenshots` (21 June) — predate the current UI.
- **`WD_DISABLE_WHEN_IDLE` is a dead preference** — read by nothing.
- **Wrong contact label** on some recordings (reported, not diagnosed).
- **CI release workflow is broken**: its `SIGNING_KEYSTORE` is a *different* key from the release key,
  so it fails at signing. Releases are built locally with `signing/callvault-signing.keystore`
  (cert `c875ffd0…`). Left broken deliberately — fixing it means putting the real key in CI.
  It also still names its artifact `ShizuCallRecorder-<version>.apk` and titles the release the same,
  while the in-app updater only accepts an asset named exactly `CallVault.apk`
  (`GitHubReleases.APK_ASSET_NAME`). A release published by this workflow would therefore be **invisible
  to the updater** — fix the naming at the same time as the signing, or the first CI release silently
  reaches nobody.
- **No instrumentation tests at all** (`app/src/androidTest` does not exist). 16 unit-test files cover
  parsing, version comparison and policy decisions; everything device-shaped is verified by hand on a
  real call. That is the honest state, and it is why regressions here are found by making phone calls.

## A VoIP recording ends with no confirmation at all

**Found 2026-08-27**, while checking whether the decode-memory fixes had broken something. They had not
— this is pre-existing and has always been the case.

The end-of-recording toast and vibration come from `RecordingNotificationHelper.handleStateChangeToasts`,
which is called from exactly one place: `RecordingForegroundService`. `VoipRecordingCoordinator` carries
an explicit comment that the VoIP path **deliberately does not go through** that service, so it never
drives the state machine those toasts hang off. VoIP can raise an *error* notification and nothing else.

So a VoIP call records successfully and tells the user nothing. The maintainer noticed the absence
himself and assumed he had misremembered.

This matters more than a missing toast normally would, because it sits on the failure mode the
2026-08-27 research found users punish hardest: **a recording that silently does not happen looks
exactly like one that silently does.** With no positive confirmation, the only way to learn a VoIP call
was not captured is to go looking for it later.

Options, cheapest first:

1. Post a success notification from `VoipRecordingCoordinator` where it already posts errors, and
   vibrate through the same helper (which already honours the user's vibration preference). Smallest
   change; keeps the deliberate architectural split intact.
2. Give the VoIP path its own lightweight state notion and reuse `handleStateChangeToasts`. More
   consistent, more surface area.
3. Fold VoIP into `RecordingForegroundService`. Largest, and undoes a split that exists for reasons.

Related: the live input-level meter (`10-product-ux.md` D1) attacks the same problem from the other end
— confirmation *during* the call rather than after it.

## Store recordings as Matroska `.mka` (issue #36)

**Requested 2026-09-11 by mirror176.** One container per call holding both sides as separate audio
streams, the transcript as a subtitle track, and caller/date/summary/notes as metadata — so the data
is portable and readable by other software instead of living only in CallVault's database, and a
merge becomes a container operation rather than a re-encode.

The idea is sound. The blocker is the platform: **Android cannot write Matroska.** `MediaMuxer`
offers MPEG-4, 3GPP, WEBM, OGG and HEIF (checked 2026-09-11), none of which is `.mka`; WEBM is a
Matroska subset but the platform muxer exposes no subtitle track and no general metadata. Media3's
muxer package is `Mp4Muxer`/`FragmentedMp4Muxer` only. Reading is fine — the platform demuxes `.mkv`
for common codecs — so this is entirely a writing problem.

Shipping it therefore means bundling or writing a Matroska muxer (libmatroska or ffmpeg: a new native
dependency, its size, its licence, its build) and then changing every path that touches a recording —
the three capture paths, playback, merge/un-merge, Drive backup, retention, the catalog — plus a
migration for every recording that already exists. Largest single change the app has made, and it
would also make the F-Droid picture harder (see the F-Droid readiness note).

If it is ever taken up, the cheap half is worth separating: writing a **sidecar** (the transcript as
`.srt`/`.ass` and the metadata as JSON next to the audio) gets most of the portability with none of
the container work, and would be a day rather than a month.

Full reasoning: `docs/dev-notes/2026-09-11-issues-34-35-36.md`.

## Transcript lines whisper invented from noise (issue #32)

**Reported 2026-09-10 by mirror176**, S20 FE, resilient recording on. A call's transcript opened with a
line that had **no speaker label**, stamped at 1 s, followed by a real `You:` "hello" at the same 1 s;
tapping the unlabelled line played nothing audible. On another call a "hello" was stamped on a moment
that held only the phone's vibration. Analysed from code on 2026-09-11, **not reproduced**.

**What the code says is happening**

- Speaker labels come from our own capture: `SpeakerTurnDetector` classifies every 100 ms as A, B, both
  or silence (`SILENCE_FLOOR` 300), and `SpeakerLabeller` returns null when nobody was voiced during a
  line, or when neither side reached 66%. **An unlabelled line is our own audio saying nobody spoke
  there** — whisper wrote text on noise that the VAD had kept (`speech_pad_ms` 400, threshold 0.4).
- Both lines land at 1 s because of the #25 fix: `SpeechGapSnap.snapForward` moves any start that falls
  before the first kept speech onto that speech's start, so the invented line is moved onto the real
  "hello" and the two share a timestamp.
- Our whisper settings leave `suppress_nst` at its default (off) and `no_speech_thold` at 0.6.
  whisper.cpp still has an open request for proper no-speech detection (#1026).

**Options, none decided**

1. Hide (or grey) a line when the captured turns say both sides were silent for its whole span.
   Language-independent, which matters: an English hallucination word list is already measured-negative
   in `transcription-quality-ceiling`.
2. Try `suppress_nst = true` and/or a stricter `no_speech_thold`, measured on the desktop harness first.
3. Don't snap a line forward onto the start of a line with the same text.

**Risks before choosing:** option 1 would also hide genuinely quiet speech below the silence floor, and a
Hebrew test can only falsify a quality change, never clear it. Option 1 only works where turns exist —
the handoff path with resilient recording on. Needs a real affected recording, or a clip with a vibration
before the first word, run through the whisper-cli harness with the app's exact settings.

## One notification during a recorded call (issue #31 follow-up) — ✅ VERIFIED 2026-09-11, out of the backlog

> Taken out of the backlog the same day at the maintainer's request and built with the shared-id design below
> (`SharedStatusNotice`). Kept here because the risks listed are exactly what a real call has to check.

**Found 2026-09-11 by the maintainer on a real call:** a carrier recording shows **two** notifications —
the recording one and the keep-alive's "Ready to record calls", which is moot while a call is recorded.
VoIP calls already avoid this: the keep-alive notification itself switches to a recording title.

**Why it isn't simply hidden:** that notification belongs to `DaemonKeepAliveService`, a foreground service,
and AOSP `NotificationManagerService` raises an app's MIN/NONE foreground notification back to LOW unless the
user changed the channel. The service also owns VoIP detection and the #30/USB observers, so stopping it is
not an option either.

**The supported fix:** let `RecordingForegroundService` post under the keep-alive's notification id. AOSP
`ActiveServices.cancelForegroundNotificationLocked` skips the cancel while another foreground service in the
package holds the same id, so the shade shows one notification throughout.

**Why it was deferred (maintainer: only if very simple and low risk):**
- While a carrier recording is active the keep-alive must not re-post (watchdog flips, VoIP detector callbacks,
  USB refresh) or it replaces Pause/Mark/Stop — the only in-call controls — with "Ready".
- When the recording ends the notification is *not* cancelled (shared id), so the keep-alive must re-post
  "Ready" at the right moment or "Recording call" lingers after hang-up.
- Needs a shared "carrier recording active" signal (none exists; `isCurrentlyRecording` is private) and a
  refresh trigger on start and end.
- Every change must be checked on a real call, including a failed start and a call that ends mid-startup.

Cheaper half-measure if wanted: have the keep-alive show a recording title during carrier recordings, as it
already does for VoIP. Still two notifications, but they would stop contradicting each other.

## Import an audio file recorded elsewhere (issue #37)

**Requested 2026-09-10 by mirror176.** Bring recordings made by other apps — including ShizuCallRecorder,
which CallVault is a fork of — into CallVault, so they sit in the same list and can be transcribed.

**What exists today**

- The list is CallVault's own catalog (Room). It is seeded from a folder scan only when it is empty
  (`RecordingCatalog.importIfEmpty`); after that only CallVault's own recordings are added. A file copied into
  the recordings folder does **not** appear — seen on the OP9 on 2026-09-11.
- Names are read from CallVault's filename grammar (`RecordingsRepository.parseName`: date, direction, contact,
  or `voip-App`). ShizuCallRecorder shares the ancestry, but its current naming has to be checked before relying on it.
- Transcription decodes whatever Android's `MediaExtractor` can read (`AudioDecoder`, stereo averaged to mono), so
  an imported file transcribes like any recording once it is in the catalog.

**Shape of the work**

1. A picker (`OpenDocument`, `audio/*`).
2. Copy the file into the recordings folder under a CallVault-style name, or catalogue it where it is. Copying
   survives the other app deleting its file; referencing avoids doubling storage.
3. A catalog row (`RecordingCatalog.recordLocal`), named from the file when it matches the grammar, otherwise from
   its timestamp and original name; contact, direction and date editable afterwards.

**Open questions:** copy or reference; whether Drive backup and retention should treat imports like recordings;
and whether a "find recordings in my folder that aren't in the list" action would serve ShizuCallRecorder users more
cheaply than a picker. Related: #36, which would let an import carry its own metadata.

## Speaker labels for Shizuku recordings (issue #38) — ✅ VERIFIED 2026-09-20

**✅ VERIFIED 2026-09-20 by the maintainer on the OP9** (Shizuku mode, real carrier call, branch
`feat/shizuku-speaker-labels`, not merged or released): after "Transcribe again" on the 11:52 call, both
lines carry a speaker label. Built as the opening below describes — labels at transcription time, from the
decode. Two bugs were found and fixed on the way, both from his reports:

- **No labels at all** — the turns were stored *after* `labelled()` had read them (`ef2dc3b9`).
- **One label of two** — Shizuku's channels are not the ~60 dB isolated pair the direct path gives. One
  voice reaches the other channel at about half level, those windows read as BOTH, and a segment only one
  person spoke in fell to 59% < 66%. Dominance is now judged between the sides' time alone (`8d79713e`).

Not verified: a long real conversation with genuine double-talk, a second Shizuku phone, and whether the
bleed was acoustic (both test phones in one room — never confirmed). A missing *line* is a different thing
from a missing label: see `2026-09-05-greedy-vad-drops-an-utterance.md` before blaming this feature.


**Requested 2026-09-10 by mirror176.** In Shizuku mode the app says the two sides cannot be told apart in
transcripts — yet his Shizuku recordings are stereo with the mic on the left and the other party on the right, so the
sides are already separate in the file.

**Why there are no labels today** (analysed as #28d on 2026-09-05): labels come from `SpeakerTurnDetector`, which
runs on raw stereo PCM *before* our mono downmix, on the handoff path only. Shizuku cannot host an `AudioRecord`, so
it records through scrcpy, which hands over **already-encoded** stereo. No PCM reaches us, so no turns are produced.

**The opening:** the stereo survives into the file, and `AudioDecoder` already reads the channel count before
averaging the channels for whisper. For a two-channel recording, that same decode pass could feed the left and right
samples to `SpeakerTurnDetector` and store turns the way the handoff path does — labels at transcription time, with no
change to capture.

**Cautions before building**

- Verify the channel order on a real Shizuku recording rather than trusting the report.
- Old stereo recordings from before v1.4.4 would be labelled too; check their layout matches before trusting it.
- The MUST-NOT-UNDO mono-encode rule concerns our own encoder and is not touched by this.
- His other point — a non-Shizuku line holding both voices under one label — is the labeller's 66% dominance rule and
  whisper merging turns into one segment, which belongs with #32, not with Shizuku.
