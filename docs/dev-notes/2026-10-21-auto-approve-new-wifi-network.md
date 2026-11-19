# Auto-approving "Allow wireless debugging on this network?" (2026-10-21)

🧪 VERIFYING (the untrusted-network case ✅ VERIFIED 2026-10-21 by the maintainer) — built 2026-10-21 on `feat/auto-approve-wifi-network` (opt-in switch "Approve new Wi-Fi networks
automatically", Settings ▸ General ▸ Experimental). Research read from AOSP (`android-16.0.0_r4`, `android-17.0.0_r1`)
and the six repos a user suggested. **Not yet tried on a network Android does not trust** (see "Tested" below).

Feature request (2026-10-21, user with screenshot): when Wireless debugging is switched on while the phone is on a Wi-Fi
network Android does not yet trust, Android shows "Allow wireless debugging on this network?" (SSID, BSSID,
"Always allow on this network", Cancel / Allow). Recording waits until someone taps Allow. The user asked whether
CallVault could approve it automatically.

## Verdict: yes, without root and without an accessibility service, while a shell process is alive

- The dialog's Allow button calls `IAdbManager.allowWirelessDebugging(alwaysAllow, bssid)` (Android 11-16) /
  `allowWirelessDebugging(alwaysAllow, bssid, ssid)` (Android 17). The only check is the `MANAGE_DEBUGGING`
  permission (`AdbService.java:316-320` on 16, `:323-327` on 17).
- **The shell uid holds `MANAGE_DEBUGGING`** (`packages/Shell/AndroidManifest.xml:540`, still there on 17). Our
  daemon and Shizuku's server both run as shell.
- **No prompt has to be showing.** `MSG_ADBWIFI_ALLOW` (`AdbDebuggingManager.java:850-873` on 16) only checks that
  wireless debugging is not already running and that the BSSID passed is the one the phone is connected to now.
  It then turns wireless debugging on, and with `alwaysAllow` adds the network to the trusted list. So the call can
  replace our `adb_wifi_enabled=1` write; Android then never asks.
- On Android 17 the network must match by SSID (`ADM17.java:1040`), and trust is stored as BSSID + SSID.

## What it needs

1. **A live shell process at the moment the network changes.** The daemon survives Wireless debugging going off
   as long as adbd keeps running, which it does while USB debugging is on (measured, [[debugging-switches-model]]).
   Shizuku's server is the same. If no shell process is alive (adbd dead, or USB debugging off), there is no way
   in, and the prompt stays: the user taps Allow once, as today.
2. **The current BSSID and SSID.** The app cannot read them in the background without location (KeepADB measured
   this: placeholders `02:00:00:00:00:00` / `<unknown ssid>` unless an activity is in the foreground). The daemon
   can: `cmd wifi status` as shell, or `WifiManager` from the shell process. Read them in the daemon.
3. **Two call shapes**, picked by reflection: `(boolean, String)` up to 16, `(boolean, String, String)` on 17.

## Decisions for the maintainer

- **Opt-in, off by default.** The prompt is Android's protection against opening the debugging port on a network
  the owner did not choose (a café, a hotel). Only paired keys can connect, so the risk is limited, but approving
  every network silently is a choice the user should make. It fits the planned Recordings ▸ Reliability group
  ([settings-per-page plan](2026-10-20-settings-per-page-plan.md)), next to "Keep Wireless debugging on".
- **Allow once, not always** (`alwaysAllow = false`), suggested. Android's own trusted list stays the user's; we
  approve again on the next visit. Cheaper to undo: turning our switch off restores Android's behaviour fully.
- Optional narrowing later: only networks the phone has saved (not open hotspots), or an in-app allowlist.

## Respect the user's own switch

Only call it where we would write Wireless debugging on today. `WirelessDebuggingEnableGate` already decides that
(`RESPECT_USER` when the user switched it off). Auto-approve must not get round that gate ([[only-undo-switches-we-set]]).

## The six suggested repos (read only, nothing built or run)

| Repo | What it does | Handles this prompt? |
|---|---|---|
| m00sfett/KeepADB | Keeps Wireless debugging on; its own trusted-networks list | **No.** README: "Android may show its own confirmation… KeepADB waits." |
| mouldybread/adb-auto-enable | On boot: Wireless debugging + legacy `tcpip 5555` | No |
| tangshui9527/adb-auto-enable | Copy of the one above | No |
| nozza87/Auto_ADB | Accessibility service that reads the **pairing code** dialog | No (different dialog) |
| Tarunswamy-Muralidharan/adb-quickstart | Quick Settings tile, PC scripts | No |
| project-SIMPLE/adb-auto-enable | Accessibility service that clicks "Always allow on this network" (`android:id/button3`) on Meta Quest's popup | **Yes, by clicking it** |

Clicking it with an accessibility service works only when the prompt is shown, depends on the vendor's dialog
layout, and needs a permission that Android 13+ restricts for sideloaded apps and that users rightly distrust. The
shell call above needs none of that, so it is the better route.

## Checks on a phone when built

1. OP9, built-in mode, USB debugging on: join a Wi-Fi never used with Wireless debugging. No prompt; recorder
   reconnects; Wi-Fi network not added to Android's list (with allow-once).
2. Same in Shizuku mode.
3. Switch off: prompt appears as before.
4. User turned Wireless debugging off themselves: stays off.
5. Android 17 phone (none yet): the three-argument call.

## Built (2026-10-21)

- Recorder side: `server/WirelessDebuggingApproval.kt` reads `cmd wifi status`, calls `allowWirelessDebugging`
  by reflection (2 or 3 arguments). New AIDL method `approveWirelessDebuggingNetwork(alwaysAllow)`, appended (id 28).
- App side: `NetworkApprovalPolicy` (setting on, built-in mode, recorder alive, gate says WRITE).
  `AdbShell.enableWirelessDebugging` tries the approval before the plain write. `DaemonKeepAliveService` approves
  4 s after the phone joins a Wi-Fi (`approveCurrentNetworkIfWanted`, never falls back to the plain write).
- **Design change from the research:** approving only when CallVault switches Wireless debugging on was not
  enough. That happens when the recorder has to be restarted, and then there is no shell process left. So the
  approval is made when the phone joins a Wi-Fi, while the recorder still runs.
- Approve once by default. "Always" only when "Wireless debugging off when idle" is on, because a once-only
  approval would be gone before the next restart.
- Mode-gated switch (`wd_auto_approve_network`, WIRELESS_DEBUGGING_CONTROL); Settings-only (decision row in
  `SettingDecisionsTest`). Strings in 15 languages.

## Tested (OP9, OxygenOS 14 / Android 14, built-in mode, 2026-10-21) — 🧪

- Wi-Fi off and on, home network (already trusted), Wireless debugging off before: back on 4 s after joining.
  Log: `CV:WdApproval: Approved Wi-Fi "Baba-5G" … (alwaysAllow=false)` from the recorder (shell pid), then
  `adbd: adbwifi started`. So OxygenOS lets the shell make the call. Before this build it stayed off.
- Switched off by hand (simulated with `settings put`), then Wi-Fi off and on: stayed off, `RESPECT_USER`.
- ✅ VERIFIED 2026-10-21 (maintainer, "nope it didnt come up"): OP9 moved from home Wi-Fi straight to the
  OP12 hotspot "Feroza" (never trusted). No prompt. Log: Android "Network disconnected. Disabling adbwifi" →
  CallVault reclassified it as Android's 3 s later → recorder approved "Feroza" 6.5 s after the switch-off →
  `adbwifi started`. The hotspot was not added to Android's trusted list (allow-once).
- **The first hotspot try found a bug, fixed in `25cf2162`:** moving straight between networks, Wi-Fi reads
  connected when Android writes the switch off, so CallVault took it for the user's tap and refused to switch
  it on again (`RESPECT_USER`). Pre-existing: it also blocked the recorder restart after any direct network
  switch. Now a switch-off within 6 s of a Wi-Fi loss is `ANDROID_NETWORK_CHANGED`. 🧪 beyond this one run.
- Shizuku mode: switch shown off and greyed, "Not available in Shizuku mode" (checked by Claude on the OP9).
  **Superseded 2026-11-19 by review N15, below: the switch now works in Shizuku mode.**
- **Not tested:** a real recorder restart on the new network (it should find Wireless debugging on), "WD off
  when idle" + always-allow, Android 17.

## Shizuku mode (review N15, 2026-11-19) — 🧪 VERIFYING

Built and unit-tested only; not tried on a phone. To settle it, on the OP9 in Shizuku mode with the switch on:
join the second access point (Wi-Fi off and on near it) while recording works, see the timeline line
`Wi-Fi approval (Shizuku): approved access point **:**:**:**:xx:xx (always allow); Wireless debugging restored off
after N ms`, then reboot onto that access point: Shizuku starts with no "Allow wireless debugging on this network?".

- **Why.** OP9 2026-11-19: after a reboot the phone joined a second access point (same SSID, other BSSID). Shizuku
  starts only over Wireless debugging, Android asked about the untrusted BSSID, and recording stayed down until
  the owner tapped Allow. At boot no shell process is alive, so the prompt cannot be answered then; it can only be
  prevented, by having the access point on Android's trusted list beforehand.
- **A first design was rejected (2026-11-19, not shipped).** It approved only when Wireless debugging went from on
  to off within 6 s of a Wi-Fi loss. On the OP9, Shizuku runs in its tcp mode (port 5555) and leaves Wireless
  debugging OFF once started, so that never fired; a time-proximity guess could also re-enable it against the
  user's off; and it ran the keep-alive (a new persistent notification) in Shizuku mode.
- **What it did first (rf4 build) — ❌ NOT WORKING 2026-11-19, replaced; see "Join detection, round 2" below.**
  - Trigger: a `ConnectivityManager` Wi-Fi request held by a PendingIntent (`WifiJoinWatch`,
    `WifiJoinApprovalReceiver`), delivered on each Wi-Fi "available" even with the app not running. No service,
    no notification. Registered at app start, on the switch, and on a mode switch; Shizuku mode + switch on only.
    **Wrong: Android delivers such a request once and then releases it** (below).
  - The receiver only queues a WorkManager job (`WifiJoinApprovalWorker`, not expedited: on Android 11 that would
    need a notification) and returns. The job starts 4 s after the join, waits up to 15 s for a cold-started app
    to reconnect to the recorder (logged when it does not), then asks the Shizuku-hosted recorder (new AIDL
    `approveWifiNetworkKeepingSwitch`, appended) with the keys of access points approved in the last 30 days.
    One run at a time per process; a second join queues behind the first.
  - The recorder (`KeptSwitchApprover`) reads the BSSID (`cmd wifi status`), and: skips a known access point;
    **skips unless adbd listens on a tcp port** (`service.adb.tcp.port` or `persist.adb.tcp.port` > 0, i.e.
    Shizuku's tcp mode), before any approval or write: elsewhere Wireless debugging may be adbd's only transport
    and switching it off could stop Shizuku and the recorder (Q01); reads Wireless debugging as shell
    (`settings get`) and skips if unreadable or on (Android ignores an approval then: `MSG_ADBWIFI_ALLOW`, AOSP
    14); reads it once more right before approving and leaves it alone if someone switched it on in between;
    otherwise approves always-allow, waits (≤ 3 s, 100 ms polls) to see Android switch it on, then writes it back
    off. That write is the only one and undoes the approval's own switch-on. Not seen on → nothing is written (a
    hidden "on" is never switched off). A user switching it on in the moments between that last read and the
    approval cannot be told apart from the approval, and would be switched off: the window is one binder call.
  - The app stores salted one-way keys (per-install salt, SHA-256, 64 bits), never addresses; logs show only the
    last two octets. An approval is trusted for 30 days, then the access point is approved again. It sets no
    "ours" / "turned off by user" flag.
  - Every decision and failure goes to the always-on transport timeline.
- **Measured by the coordinator on the OP9 (2026-11-19):** switching `adb_wifi_enabled` 1→0 and 0→1 while the
  Shizuku server and the Shizuku-hosted recorder ran left both alive (same PIDs). Not measured on other phones:
  where Wireless debugging is adbd's only transport, switching it off could stop adbd (device-quirks Q01).
- Setting gated by the new `ModeCapability.WIFI_NETWORK_APPROVAL` (both modes). A switch turned off by an earlier
  move to Shizuku mode comes back on at the next reconcile (the existing restore rule); it shows no notification.
- New text `settings_wd_auto_approve_description_shizuku` in English and 18 translations, written by Claude:
  **needs a native-speaker check.**

**Known limits (📐 from reading AOSP, not measured):**
- An access point first seen at boot, never joined while the recorder ran: Android still asks once.
- A roam between access points of one network produces no Wi-Fi "available": not approved until the next join.
- Shizuku not running at the join: nothing can approve (`RECORDER_DOWN`, logged).
- If Android's trusted list loses an access point CallVault approved (the user removed it), CallVault cannot see
  that: the reboot prompt can come back for it until its 30-day record expires and it is approved again.
- Not in Shizuku's tcp mode (Shizuku started over Wireless debugging only, or by root): nothing is approved,
  logged as "Shizuku not in tcp mode".
- If the shell cannot read the switch (an Android 17 that hides it from shell too), the join is skipped and logged.

## Join detection, round 2 (review N15, 2026-11-19) — 🧪 VERIFYING

**❌ What the OP9 showed (rf4 build, Shizuku mode, 2026-11-19).** 17:10:25-36 switch turned on → 17:10:43 timeline
`approved access point ..:40:18 (always allow); Wireless debugging restored off after 270 ms`. 17:12:55 joined the
OP12 hotspot (new BSSID ..:88:01): nothing (no timeline line, no job, no broadcast, not in `adb_temp_keys.xml`).
17:15:33 rejoin of Baba-5G after `svc wifi disable/enable`: nothing. `dumpsys connectivity` for the app's uid: no
PendingIntent request left. Also: 40:18 was already trusted by the user's own tap, and the approval added it a
second time (the app's keys cannot see the user's taps).

**Root cause (read from AOSP `packages/modules/Connectivity`, android14-release, `ConnectivityService.java`).** A
PendingIntent network request is one-shot: `sendPendingIntentForRequest` sends it only for `CALLBACK_AVAILABLE` and
only while `!nri.mPendingIntentSent`, and `onSendFinished` then calls `releasePendingNetworkRequestWithDelay`, which
releases the request after `CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS` (default 5 000 ms; "so the receiving
client has an opportunity to put in its own request"). The `registerNetworkCallback(…, PendingIntent)` javadoc does
not say so. Registered while the phone was already on Wi-Fi, it fired at once (that was the 17:10:43 approval of the
current access point), and 5 s later it was gone. Re-registering from the receiver does not fix it: the delayed
release matches by PendingIntent and would drop the new request too, and a request registered on Wi-Fi fires at once
again, a loop every ~5 s. Not an Oplus block and not WorkManager.

**What it does now.**
- The recorder watches joins itself (`server/WifiJoinMonitor`, `ShellWifiNetworkWatch`): an ordinary
  `NetworkCallback` for Wi-Fi, which stays registered until unregistered or the process dies. The recorder is the
  shell-uid process Shizuku keeps up, not frozen by OEM app freezers (Oplus Hans), and the one that approves anyway.
  Its ConnectivityManager is built on the `connectivity` binder with a Context presenting `com.android.shell`.
- Armed by the app on every recorder connection (`RecorderConnection.onDaemonReady`), at app start, on the switch
  and on a mode switch (`WifiJoinWatch.sync`, new AIDL `armWifiJoinWatch` / `drainWifiJoinEvents`). One
  registration per recorder process however often it is armed; a recorder started again (crash, update, reboot) is
  armed again when it connects. Arming on Wi-Fi also checks the current access point (Android reports the current
  network to a new callback at once).
- A join is checked 4 s later; joins closer than that are checked once, after the last.
- "Already approved" is now Android's own list: `dumpsys adb` (shell holds DUMP; system_server reads
  `adb_temp_keys.xml` in `AdbDebuggingManager.dump`, field `keystore`), parsed for addresses so binary XML reads
  too. Listed → `skipped: … is already on Android's trusted list`; not listed → approved even if the app has a key
  for it. The app's 30-day keys are only the fallback when the dump cannot be read.
- Every outcome goes to the always-on timeline with the prefix `Wi-Fi approval (Shizuku): `: `watching Wi-Fi joins
  in the recorder (pid N)`, `still watching … (armed again; one registration)`, `stopped watching …`, `could not
  watch …: <reason>`, `Wi-Fi network available (net N); checking its access point in 4 s`, then the check's answer
  (approved / skipped + why / failed + why). The recorder pushes its lines through the app's provider (starting the
  app if needed); lines the app could not take wait in the recorder (newest 64) for the app's next arm, and a late
  line ends with `(in the recorder at HH:mm:ss)`.
- Backup: when the recorder answers that it is not watching, the app logs `the recorder is not watching Wi-Fi joins
  (<why>); checking the current access point from the app once instead` and runs the old app-side check once.
- The PendingIntent receiver is removed. A request the old build left registered fires at most once more, to no
  receiver, and Android releases it.

**Unit-tested only (Robolectric + JVM): a second and third join are delivered, re-arm on a fresh recorder binder,
no duplicate registration, skip when Android already trusts the access point.** 📐 Not measured: whether OxygenOS
lets the shell-uid process register a NetworkCallback (a `could not watch` line would say), and the `dumpsys adb`
keystore format on this phone (the `already on Android's trusted list` line for 40:18 would confirm it).

**To settle it on the OP9 (Shizuku mode, setting on, debug logging not needed):**
1. Install the build; open CallVault once. Timeline: `watching Wi-Fi joins in the recorder (pid N)`, then
   `Wi-Fi network available …` and, 4 s later, `skipped: **:**:**:**:40:18 is already on Android's trusted list`.
   (`could not watch …` instead means the shell cannot register; send that line.)
2. Join the OP12 hotspot "Feroza". Within ~5 s: `Wi-Fi network available …` then `approved access point
   **:**:**:**:88:01 (always allow); Wireless debugging restored off after N ms`. Check
   `/data/misc/adb/adb_temp_keys.xml` (root) lists 88:01 once, and `settings get global adb_wifi_enabled` is 0.
3. Back to Baba-5G, then Wi-Fi off and on (`svc wifi disable` / `enable`), waiting over 30 s between joins (checks
   are spaced 30 s apart): a line pair for each join (`… already on Android's trusted list`), proving the second
   and later joins arrive. Joins closer together fold into one check, and the next line says `(N more Wi-Fi joins
   since the last line)`.
3b. Turn the setting off, join a network: no approval line (the watch was disarmed). If a line `skipped: the app says
   approving is no longer wanted …` ever appears, the recorder caught a setting change the app could not tell it.
4. Force-stop CallVault, join the hotspot again: lines still appear (the recorder pushes them and starts the app),
   or appear with `(in the recorder at …)` after opening the app.
5. Kill the Shizuku recorder by its pid (as root: find it with `ps -A | grep callvault:recorder`, then `kill <pid>`),
   open CallVault: a new `watching … (pid M)` line. Then reboot onto the hotspot: Shizuku starts with no "Allow
   wireless debugging on this network?".

**Review fixes (2026-11-19, same branch).**
- Wireless debugging is never left on by an approval: after the 3 s wait the recorder re-reads every 500 ms for 5 s
  more and switches a late "on" back off. If it is still not seen on, the access point is kept as this recorder's
  leftover, with the time of the approval, for 30 s (the waits plus a margin). A check within those 30 s that finds
  it trusted with Wireless debugging on switches the leftover off (`… left on by an earlier approval of ours, and is
  switched back off`). After 30 s it is never written: the "on" may be the user's own (to pair, say), so it is left
  alone with a warning (`… it may be left over from our approval of … N s ago, but was left alone because it may be
  the user's`). An "on" with no leftover is left alone with a warning too (`… not switched on by CallVault as far as
  this recorder knows; left on`). Any error after approving runs one guarded switch-back and logs the state found
  (`then an error stopped the switch-back; …`). A recorder started again forgets its leftovers.
- Trade-off: a user's own switch-on can still be undone if it lands in the moments right after an approval: within
  the 8 s of waiting (it cannot be told from Android's), or within the 30 s leftover window when that access point is
  checked again. Past 30 s, never. The merge-gate review (2026-11-19) found the first version kept leftovers with no
  expiry, which could switch off a user's "on" days later; fixed before merge.
- The recorder asks the app (its provider) before every approval whether approving is still wanted. No answer:
  nothing approved, `skipped: could not confirm with the app …`. Not wanted: nothing approved and the recorder stops
  its own watch.
- Checks and "available" lines at most every 30 s; pushes to the app coalesced (3 s) on their own thread; shell
  commands killed at their 10 s timeout; lines from the recorder capped (64 per hand-over, 512 characters).

**Still not covered:** a roam between access points of one SSID gives no new "available", so it is not checked
until the next join or recorder start.
