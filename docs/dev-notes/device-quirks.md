# CallVault device quirk registry (2.5.2 review)

> This file is the source of truth for quirk ids. The guard test `PhoneRulesCoverageTest` reads it: every `### Qnn` heading and every `| Un |` row must have an entry in `PhoneRules` (a detection kind) or an explicit not-detectable reason. Add the registry entry in the same change as a new quirk.

📐 Written 2026-11-19 from reading code and notes. Nothing here was run on a phone for this file. Code is the
2.5.2 review worktree at `f32e2d35`. Every `file:line` is relative to `app/src/main/java/com/baba/callvault/`
unless it starts with `docs/` or `CHANGELOG`.

## Why this exists

The 2.5.2 review (`combined.json`, 186 issues) found many fallbacks: code that tries something else when a step
fails. Some exist because a real phone behaves oddly, and those must stay. Others hide our own bugs. Before a
fix removes or changes a fallback, look the code path up here.

## Rules for using it

1. **A quirk is listed only with evidence**: a field log, a measurement on our phones, a tester's report or a
   source read recorded in the maintainer's notes. General Android knowledge appears only as a 📐 note.
2. **Markers are copied from the notes, not upgraded.** ✅ = the maintainer confirmed it on a device. 🧪 = built
   or observed but not confirmed. ❌ = tried and failed. 📐 = from reading code or source, not measured.
   "Measured" means one of our phones showed it; the marker still says whether the fix is confirmed.
3. **⚠️ BRAND** / **⚠️ SDK** flags a check on `Build.MANUFACTURER` or `SDK_INT` (or a package name) instead of on
   the behaviour itself. Those checks are fragile: a ROM can change the behaviour without changing the label.
4. **"Review C-ids"** lists the review issues whose fix would change this code path, with a verdict:
   **BREAKS** (the fix as written would undo the quirk handling), **RISK** (breaks only if built a certain way),
   **OK** (consistent with the quirk), **DECISION** (an owner decision already covers it).
5. Before removing a fallback that is **not** in this file, check "Fallbacks that are NOT quirks" and "Unclear"
   at the end. If it is in neither, treat it as unclassified and look for evidence before deleting it.
6. No reporter names, handles or contact details in this file. Phones are named by model only.

## The key result: review fixes that would break a quirk

| C-id | Quirk | Verdict | Why |
|---|---|---|---|
| **C64** | Q08 | **BREAKS** | Makes the stuck-mic heal's `canRelaunch` use the keep-alive's readiness rule. That rule has no `tcpPortOpen` term, so on the Pixel 8 (ROM's own adb tcp port, no Wi-Fi) the heal is refused again. That is the 2.5.1 fix for a stuck "Shell" mic dot. Keep `tcpPortOpen` as an override. |
| **C50** | Q47 | **BREAKS** | Turns a 0-byte existing Drive file into `PARTIAL`, and `SafHelper.kt:330-335` then **deletes** that Drive document and uploads again. Drive reports 0 bytes for a file it is still uploading. The code comment at `CloudCopyPolicy.kt:108-112` says deleting on this signal "made recordings disappear from Drive". |
| **C2** | Q25 | **RISK** | "Right after accepting a re-delivery, if the daemon is recording, stop it." If the OEM killed the app **during** a call and it restarted while the call was still up (seen on a OnePlus), this stops a live recording. The stop needs a "no call in progress" condition. |
| **C102** | Q30 | **RISK** | Would refuse an app-call capture when "every live voice-comm player predates the arm". The Samsung Wi-Fi-call route also calls `startVoipRecording`, and on the Galaxy Z Fold2 the far side plays from a system_server track that is **never listed as a player** (vcPlay=0). If zero players counts as "all predate", the Fold2 route is refused and Wi-Fi calls lose the far side again. The rule must require at least one player. |
| C23 | Q49 | RISK | The owner-scoped notice slot is fine. Any re-post of the "Ready" or remaining notice must still go through `startForeground` (`DaemonKeepAliveService.kt:142-160`), not `notify()`, or the stuck "call in progress" notice comes back on Android 17 Pixels. |
| C118 | Q21 | RISK | Forwarding the late post-boot broadcast's number into the live session is fine only if the number is matched to the same call. The broadcast can arrive ~9 s late, after that call ended, and must not rename the next call. |
| C120 (add-on) | Q37 | RISK | The fix itself is OK. Its optional add-on, "gate the re-take on `isClientSilenced`" (backlog #9), is unproven on Samsung, the phone the re-take exists for. It needs a Samsung log first. |
| C38 (later part) | Q39 | RISK | "Bounded re-open" of a failed capture: re-opening a second voice capture while a `VOICE_CALL` one is still held silently drops the user's own side. Close first, then re-open. |
| C63 (suggestion) | Q23, Q43, Q44 | RISK | "Make Resilient recording the default on phones that reap the daemon" conflicts with phones where resilient recording cannot work: Honor pause, Android 12, the One UI geometry refusal. Keep it per-phone. |
| item-108 revert | Q33 | DECISION | `combined.md`'s fallback plan (revert LINE normal-mode start) removes Q33's handling. Acceptable because Q33 is 🧪 unproven, but say so in the release notes. |

Every other C-id that touches a quirk path is marked OK in its entry.

---

## A. ADB transport, debugging switches and the shell recorder

### Q01 — adbd stops when USB debugging goes off, or when its last transport is removed
| | |
|---|---|
| Other apps | A4 (see section F) |
| Behaviour | `adbd` (and every shell-uid process it started, including our recorder) stops when USB debugging is switched off, even while Wireless debugging reads on (stock `init.usb.configfs.rc`). Switching Wireless debugging off when it is adbd's only transport also stops adbd, and the recorder dies 50–90 ms later. Writing WD on within ms of a USB change loses a race (24 ms window). The recovery that works: wait ~2 s, then cycle WD off→on. |
| Phones | Galaxy S24 FE SM-S721B (One UI 8.5, A16); OnePlus 12 (A16); OnePlus 9 Pro (A14); AOSP emulator. |
| Evidence | memory `adbd-transport-and-oem-quirks.md` (proven by experiment), `debugging-switches-model.md` (measured 2026-09-14; fixes 🧪); `docs/dev-notes/2026-09-14-debugging-switches-model.md`. A real call was lost on a OnePlus (18.3 s cold start for a 15 s call). Fixes: 🧪. |
| Detects | Behaviour, not brand: `WirelessDebuggingPolicy.kt:74-81` (`plan(isUsbDebuggingEnabled, …)`); `adb_enabled` observer `DaemonKeepAliveService.kt:503`, proven-off wait `:240-245,270-275`; `AdbShell.kt:375` `reviveAdbdIfStopped`. |
| Code does | Keeps WD on unless USB debugging is on (`DROP_USB_KEEPS_ADBD` / `KEEP_ONLY_TRANSPORT`); when USB debugging goes off, waits for it to settle, then revives adbd and re-runs the launch (`DaemonKeepAliveService.kt:247-265`). |
| If removed | 2-minute daemon churn loops (6 cycles on the S24 FE); silent "no recorder" after USB debugging is turned off, so the next call is lost. |
| Review C-ids | C99 OK (clears a stale user-off flag only when WD reads on, the observer's own rule). C181 OK if `mayRelease` is truly dead; keep `plan()`. C35 OK. C56 DECISION (never "disarm when leaving standalone"). |

### Q02 — Some phones switch USB debugging off by themselves
| | |
|---|---|
| Other apps | A4 (see section F) |
| Behaviour | Honor/Huawei write `adb_enabled=0` when USB is in charge-only mode, unless "Allow ADB debugging in Charge only mode" (`allow_charging_adb`) is on. A Samsung reverted the switch repeatedly (Auto Blocker suspected). Off Wi-Fi, recording then stays down. |
| Phones | Honor BVL-N59 (MagicOS 10/A16, later MagicOS 11/A17); Galaxy S24 FE SM-S721B (One UI, A16). |
| Evidence | memory `usb-debugging-turned-off-by-phone.md` 🧪 (cause 📐 from Honor support thread; no off event in 27 h after the user turned `allow_charging_adb` on, so the restore never fired); S24 FE part 🧪 in `debugging-switches-model.md`. Rollback note `docs/dev-notes/rollback/usb-debugging-restore.md`. |
| Detects | Behaviour: the `adb_enabled` observer (Q01) plus a ≤5 s wait for adbd to stop. |
| Code does | `UsbDebuggingRestorer.kt:43` `onUsbDebuggingOff` writes it back on when the screen is off or locked (max 3 per 10 min). Otherwise it posts a button; `turnOn` `:68-76` also writes `allow_charging_adb=1`. Always-on event file in the report. |
| If removed | Users off Wi-Fi lose recording until they notice. |
| Review C-ids | None directly. Note: on Honor the app is paused exactly when "screen off" (Q23), so this restore may never run there. |

### Q03 — Android 17 hides `adb_enabled` and `development_settings_enabled` from apps
| | |
|---|---|
| Behaviour | Both settings read the literal "0" for any app uid ≥10000. Some Android 16 custom ROMs backport this with `SDK_INT` 36, and it is not CTS-enforced. |
| Phones | Android 17 (all); ProjectCiRCLE 16.2 / CharaROM (A16 custom ROMs, source read). |
| Evidence | memory `android-17-hides-debugging-settings.md` 📐 (source); fix shipped 2.4.0, 🧪 on A17 hardware. `docs/dev-notes/2026-09-19-android-17-adb-detection-issue-40.md`. |
| Detects | Behaviour, deliberately **not** `SDK_INT`: `UsbDebuggingPolicy.kt:59-94` and `DeveloperOptionsPolicy.kt:58-77` corroborate with `init.svc.adbd` (readable by apps). The raw read `DaemonKeepAliveService.kt:270` only starts a look. |
| Code does | Tri-state (ON/OFF/UNKNOWN); "0" alone never turns Home red or stops recovery. |
| If removed | Every Android 17 phone reads "debugging off": onboarding blocks, keep-alive declines to recover. |
| Review C-ids | C115 OK (applies the same tri-state idea to WD; see Q04). |

### Q04 — Android 17 QPR3 betas hide `adb_wifi_enabled`; A17 "ADB Wi-Fi 2.0" can read 1 while not serving
| | |
|---|---|
| Behaviour | On Android 17 QPR3 Beta 2/3, apps read 0 (or get a permission error) for `adb_wifi_enabled`, so pairing waits for a switch that never reads on. Separately, A17 can keep `adb_wifi_enabled=1` while adbd's TLS server is stopped. |
| Phones | Pixel 9 Pro (A17 QPR3 Beta 2, setup report); Pixel 7 (A17 QPR3 Beta 3, CHANGELOG 2.5.1 test). |
| Evidence | memory `report-pixel9pro-a17-qpr3-no-pairing-notification.md` 📐, ⏸️ parked 2026-10-24 (beta only); `android-17-call-recording-research.md` 📐. CHANGELOG 2.5.1 "keeps listening for the pairing service" 🧪. |
| Detects | Not handled at the read: `AdbShell.kt:918` `isWirelessDebuggingEnabled` is a bare read. Pairing now keeps listening regardless (2.5.1). |
| Code does | Only the pairing workaround. ~20 call sites still trust the read. |
| If removed | n/a (mostly unhandled). |
| Review C-ids | C115 OK in direction, but it touches the parked owner decision ("when we cannot tell, may CallVault switch WD on?"). Settle that decision before building. |

### Q05 — Android, not the user, switches Wireless debugging off
| | |
|---|---|
| Other apps | A4 (see section F) |
| Behaviour | WD needs Wi-Fi on a trusted network. Android writes `adb_wifi_enabled` back to 0 when it refuses (new network prompt) or when Wi-Fi drops. A direct Wi-Fi→Wi-Fi switch looks like a user tap because Wi-Fi still reads connected. Our own `tcpip:`/`usb:` adbd restart is followed by WD going off. |
| Phones | OnePlus 9 Pro, OnePlus 12 (hotspot test); POCO X7 Pro HyperOS 3 (after our adbd restart). |
| Evidence | memory `auto-approve-wifi-network-prompt.md` (✅ 2026-10-21 untrusted hotspot; network-change fix 🧪 one run); `reboot-deadlock-wd-off-by-user.md` ✅ 2026-09-19; `shizuku-fails-on-hyperos3-poco-x7-pro.md` 🧪. |
| Detects | Behaviour (timing): `WirelessDebuggingOffCause.kt:42-54` (`ANDROID_REFUSED`, `ANDROID_NETWORK_CHANGED` = off within 6 s of Wi-Fi loss, `AFTER_OUR_ADBD_RESTART` = within 20 s, else `USER`). Wi-Fi `onLost` `DaemonKeepAliveService.kt:445`. |
| Code does | Only `USER` blocks re-enabling. On Wi-Fi join it approves the network via the recorder (4 s settle, `DaemonKeepAliveService.kt:452`). |
| If removed | Either recording deadlocks (Android's write read as the user's; the reboot deadlock ✅ fixed) or CallVault overrides a real user choice. |
| Review C-ids | C99 OK. C48 OK (relaunch on Wi-Fi join). |

### Q06 — The off-Wi-Fi (`tcpip`) listener: cleared on reboot, `service.adb.tcp.port` lags, `tcpip:` restarts adbd
| | |
|---|---|
| Other apps | A5 (see section F) |
| Behaviour | `adb tcpip` clears on reboot; re-arming needs Wi-Fi + WD once, and `setprop persist.adb.tcp.port` is SELinux-denied for shell. adbd writes `service.adb.tcp.port` when it restarts and binds, not when it accepts `tcpip:`. Arming restarts adbd, which kills every shell child (our recorder, a Shizuku server). The first arms can fail before one sticks. |
| Phones | AOSP emulator, OnePlus 12, OnePlus 9 Pro; POCO X7 Pro HyperOS 3 (two failed arms, third worked); Galaxy Note20 Ultra A13 (reboot off Wi-Fi, 📐). |
| Evidence | memory `loopback-tcpip-offwifi.md` (reboot gap spiked 2026-08-29); `reboot-deadlock-wd-off-by-user.md` ✅ (boot time measured 73 s→11.4 s); `debugging-switches-model.md`. |
| Detects | Behaviour: `LoopbackArmWait.kt:57-66` (an unset port within the grace means "still restarting"; positive signal only). |
| Code does | Polls instead of a fixed 2 s sleep. Loopback first, WD as fallback (`AdbShell.kt:147`, comment `:165`). Never arms during a call. |
| If removed | Boot recovery back to 5–6 cycles; arming mid-call kills the capture. |
| Review C-ids | C35 OK (BUSY while recording). C56 DECISION. C61 OK. C24/C97 OK (Home must say "Needs Wi-Fi" truthfully). C134 OK (a readiness poll instead of a sleep matches "loopback shell not ready" seen on the OP9 after reboot). |

### Q07 — Another app moves adbd to its own tcp port and toggles WD
| | |
|---|---|
| Other apps | A5 (see section F) |
| Behaviour | A Shizuku fork with Watchdog + TCP mode restarts Shizuku after our arm kills it, runs its own `tcpip` (5555), toggles WD (~20× in 300 ms seen), and moves adbd off our port. The result is a tug-of-war. |
| Phones | POCO X7 Pro (HyperOS 3, A16); OnePlus 8T KB2001 (A14); repro on OnePlus 9 Pro (A14). |
| Evidence | memory `shizuku-fails-on-hyperos3-poco-x7-pro.md` (📐 log read; fix 🧪 held on OP9 across a reboot 2026-10-23); OnePlus 8T report note 2026-11-08 📐. |
| Detects | Behaviour: `LoopbackPorts.kt:33` (`candidates`: another app's port first), `:44` (`isUsable`: only in offline mode). |
| Code does | Uses the port adbd already listens on instead of re-arming; disarm leaves another app's listener alone; WD-off within 20 s of our restart is not the user's (Q05). |
| If removed | 3/3 arms fail, 45 s libadb wedge, recorder down. |
| Review C-ids | C35 OK ("early-return from disarm when !isListening"). C56 DECISION. |

### Q08 — The ROM's own adb tcp listener is open (no Wi-Fi needed)
| | |
|---|---|
| Behaviour | Some ROMs leave adbd listening on a tcp port (`persist.adb.tcp.port`, `service.adb.listen_addrs`, 5555) that CallVault did not arm. It is reachable with no Wi-Fi and no WD. |
| Phones | Pixel 8 (Android 17). |
| Evidence | CHANGELOG 2.5.x "A stuck microphone dot … cleared once Wi-Fi is back" 🧪; comment `MicOpHealPolicy.kt:113-114` ("seen on a Pixel 8"). |
| Detects | Behaviour: `AdbShell.kt:329` `isAdbdTcpPortReachable`; `LoopbackPorts.kt:71` `parseListenAddrs`, `:85` `alreadyOpenCandidates`; `AdbShell.kt:190,217-240`. |
| Code does | Connects through it; the stuck-mic heal counts it as a way back (`MicOpHealPolicy.kt:103-121`, `MicOpAutoHeal.kt:118,258`). |
| If removed | The Pixel 8's stuck "Shell" mic dot cannot be cleared off Wi-Fi. |
| Review C-ids | **C64 BREAKS**: it aligns `canRelaunch` with `ReadinessNoticeText` / `maybeRewarm`, which refuse on `NEEDS_WIFI*` and know nothing of `tcpPortOpen` (`ReadinessNotice.kt:93-94`, `DaemonKeepAliveService.kt:665-676`). Keep `tcpPortOpen` as an override, or teach the readiness rule about it. C34 OK. |

### Q09 — The ROM never advertises the WD connect service over mDNS
| | |
|---|---|
| Other apps | A11 (see section F) |
| Behaviour | Pairing is advertised, but `_adb-tls-connect._tcp` never appears (adbd's advert goes through `mdnsd`, which is missing or stopped). The recorder never starts. |
| Phones | Realme GT 7 RMX5061 (A16); Galaxy Note10+ (A12) suspected. |
| Evidence | memory `issue-65-no-tls-connect-service.md`: ✅ 2026-10-29 (the reporter's carrier call recorded on the fix build); Note10+ 🧪. `docs/dev-notes/2026-10-29-issue-65-connect-port-fallback.md`. |
| Detects | Behaviour: `ConnectPortFinder.kt:71-98` (last good port → mDNS → loopback port search, 5 s budget, once per WD session). Logs `init.svc.mdnsd`. |
| Code does | Finds the port without mDNS. |
| If removed | Those phones can never connect. |
| Review C-ids | C115 OK (puts `ConnectPortFinder` evidence first). C65 OK. |

### Q10 — adbd may bind only the Wi-Fi address (Xiaomi, suspected)
| | |
|---|---|
| Other apps | A11 (see section F) |
| Behaviour | Our probe "bind loopback, expect it to fail" succeeds on a ROM whose adbd binds only the Wi-Fi address, so a real service reads as fake. |
| Phones | Xiaomi (issue #23; model not recorded in notes). |
| Evidence | memory `issue-23-xiaomi-discovery.md` 🧪, cause unproven. |
| Detects | `MdnsServiceVerdict.kt:33-56` logs `NOTHING_LISTENING_ON_LOOPBACK` (diagnostic verdict). |
| Code does | Logs every discovery outcome; Q09's port search covers the case. |
| If removed | Discovery fails silently again. |
| Review C-ids | None. |

### Q11 — Screen lock restarts adbd when the default USB mode is a data mode
| | |
|---|---|
| Behaviour | Locking the screen during a call restarts adbd, which kills the recorder mid-call, if the Default USB Configuration is a data mode. "Charging only" (on Samsung One UI 8 also "Debugging only") prevents it. |
| Phones | OnePlus CPH2581 / OnePlus 12 (field, fixed by Charging only); Galaxy S25 / S25 Ultra and Xiaomi per Shizuku issue threads (📐). |
| Evidence | memory `screen-off-adbd-kill-and-mitigations.md`: confirmed 2026-07-24; reporter confirmed Charging only fixes it (shipped 1.4.5). |
| Detects | Behaviour: `UsbDefaultConfig` reads `dumpsys usb` (Q12). |
| Code does | Recommends and sets Charging only; refreshes the USB default at call start except on pausing phones (`RecorderServerLauncher.kt:164,171`); warns in Home and the notice (`DaemonKeepAliveService.kt:862-867`). |
| If removed | Mid-call recorder death on lock. |
| Review C-ids | C105 DECISION (keep `killStaleRecorders` + the USB refresh, move them off the critical path; keep the pause skip). C32/C33 OK (detection only). C63 RISK (see key table). |

### Q12 — The USB default reads wrong: line omitted on ColorOS, vendor default on Honor
| | |
|---|---|
| Behaviour | `dumpsys usb` prints `screen_unlocked_functions` only when a data mode is set, so Charging only reads as "nothing". On Honor, `sys.usb.config` always holds the vendor default `hisuite,mtp,mass_storage`. |
| Phones | OnePlus 12 (ColorOS, measured 2026-08-04); OnePlus 9 Pro; Honor BVL-N49 (A16). |
| Evidence | memory `screen-off-adbd-kill-and-mitigations.md` (measured); `honor-usb-default-misread.md` 🧪 (unit tests only); rollback note `usb-default-honor.md`. |
| Detects | Behaviour: `UsbDefaultConfig.kt:122-160` (`mayUseProperty`: the property only fills an UNKNOWN cache). |
| Code does | Trusts the dump; uses the property only as a last resort. |
| If removed | A false "USB is in data mode" warning on Honor, or a mode stuck UNKNOWN on ColorOS. |
| Review C-ids | None. |

### Q13 — An adbd restart (including Charging only) stops the Shizuku server
| | |
|---|---|
| Other apps | A5 (see section F) |
| Behaviour | A Shizuku server started over ADB is a shell child of adbd: switching USB to Charging only, or any `tcpip:` restart, kills it. |
| Phones | OnePlus 9 Pro (A14). |
| Evidence | memory `github-issues-25-28.md` (28g "mechanism MEASURED"), `debugging-switches-model.md` (arming off-Wi-Fi kills Shizuku's server). |
| Detects | Mode, not behaviour: USB advice only in ADB modes (`DaemonKeepAliveService.kt:104,862-867`); Shizuku heal refuses ADB work mid-call (`ShizukuHealPolicy.kt:60-73`). |
| Code does | Hides the Charging-only advice in Shizuku mode; restarts Shizuku only when no call is recorded. |
| If removed | The app's own advice stops Shizuku. |
| Review C-ids | C72 OK (skip the local-ADB fallback in Shizuku mode: it can write WD and restart adbd). C106 OK. |

### Q14 — OPPO-family shell-grant block ("Disable permission monitoring")
| | |
|---|---|
| Behaviour | ColorOS/OxygenOS strip `GRANT_RUNTIME_PERMISSIONS`, `MANAGE_APP_OPS_MODES` and `WRITE_SECURE_SETTINGS` from shell unless a developer toggle is on (renamed "Disable system optimization" on ColorOS 15/16). It is read from `persist.sys.permission.enable` (false = permissive). Grant-time only: an existing grant survives. Xiaomi has a similar property with opposite polarity. |
| Phones | OnePlus 9 Pro (ColorOS 14, polarity proven); OnePlus CPH2653 (OxygenOS 16); OnePlus 12 CPH2581 (shell grants blocked); OnePlus Ace 6 (ColorOS 16, Shizuku thread). |
| Evidence | memory `coloros-toggle-renamed-and-hidden.md` ✅ polarity 2026-09-16 (OP9); detection 🧪; `op9-test-device.md`. `docs/dev-notes/2026-09-16-oem-adb-restrictions-and-onboarding-check.md`. |
| Detects | Mostly behaviour: `ShellGrantGate.kt:76` reads the grant output and the permission back. `:63` reads the OPPO property where present. ⚠️ BRAND at `:88-93`, but only to pick the advice wording. |
| Code does | Self-hiding notice in onboarding/Settings; the missing grant counts only when nothing else can restart the recorder (✅ 2026-09-29 on OP9 in field notes). |
| If removed | Mysterious "grant refused"; a permanent false "regrant" card on blocked phones. |
| Review C-ids | None directly. |

### Q15 — An install-over stops the recorder and can drop `WRITE_SECURE_SETTINGS`
| | |
|---|---|
| Other apps | A15 (see section F) |
| Behaviour | An install-over stops the app's foreground services and the shell-uid recorder. On some phones it also drops the `WRITE_SECURE_SETTINGS` grant (seen after `adb install -r`). From Android 12 a foreground service may start only inside `MY_PACKAGE_REPLACED`'s short window. |
| Phones | OnePlus 12 (a 13-minute call was lost on 2026-09-06). |
| Evidence | memory `post-update-recorder-stays-down.md` ✅ 2026-09-06; `reinstall-drops-write-secure-settings.md` (observed 2026-06-15). |
| Detects | Event-based (`MY_PACKAGE_REPLACED`), `PostUpdateRecovery.plan()`. |
| Code does | Starts the keep-alive first on the broadcast thread, relaunches the recorder, re-grants (the updater chains `pm grant`). |
| If removed | The first call after an update records nothing. |
| Review C-ids | C3 OK (adds a call check before each install action). |

### Q16 — A Shizuku-hosted process cannot record unless it drops the app's identity
| | |
|---|---|
| Behaviour | Shizuku puts CallVault's `Application` into the user service's `ActivityThread`, so `AudioRecord` carries uid 2000 + `com.baba.callvault`, and AppOps denies `startInput`. On real hardware this shows as a failed start; on the emulator as a failed create. |
| Phones | OnePlus 9 Pro (A14). |
| Evidence | memory `shizuku-mode-capture-rules.md`: ✅ 2026-10-19 (WhatsApp and carrier calls recorded in Shizuku mode). |
| Detects | Unconditional: `ShellAttribution.kt:31` `detachFromApp()` in the user-service constructor. |
| Code does | Detaches the identity; the daemon still falls back to scrcpy if a direct start fails (`RecorderServiceImpl.kt:505-527`). |
| If removed | Shizuku mode records 0-byte files while every layer reports success. |
| Review C-ids | C131 OK (only adds the path to the diagnostics). C106 OK. Note: the comment at `AudioRecordingEngine.kt:406-409` still says handoff is ruled out in Shizuku mode; `HandoffPolicy.kt:40-58` no longer does. The comment is stale. |

### Q17 — Shizuku binds by name, a `daemon(true)` service outlives updates, and Shizuku 13.1.5 crashes on background binds
| | |
|---|---|
| Other apps | A5 (see section F) |
| Behaviour | Shizuku delivers a binder by service NAME, so an old process's late answer lands on the new binding. A `daemon(true)` service survives an update with a path to an APK that no longer exists. Two concurrent binds replace each other. Shizuku 13.1.5's `died()` walks a HashSet on main while our background bind/unbind mutates it (ConcurrentModificationException). |
| Phones | OnePlus 9 Pro (A14, Shizuku mode). |
| Evidence | memory `shizuku-stale-service-after-update.md` (✅ 2026-09-22 for fix 3; the 2026-11-03 serial-rebind fix 🧪); `shell-mic-open-first-evidence.md` (CME found 2026-11-07, 🧪). Rollback note `shizuku-serial-rebind.md`. |
| Detects | Behaviour: `RecorderBackend.kt:232-248` (`hostApkPath` vs installed APK); `RecorderServiceImpl.kt:528` (APK exists?); `ShizukuBackend.kt:314` `onShizukuThread`. |
| Code does | Retires a stale host (never one that is recording); serialises retire/bind; binds on main. |
| If removed | The first call after an update records nothing; the app crashes. |
| Review C-ids | C66 OK. C68 OK. C107 OK. C91 OK (an `isRecording` kill guard is compatible: a recording host is already left alone). |

### Q18 — Shizuku on HyperOS 3: "READY" without a recorder, and silent host death
| | |
|---|---|
| Behaviour | On HyperOS 3 the Shizuku user-service binder never arrives (READY + `removeUserService` permission denial). On other HyperOS phones the host dies between calls, or with Recents "clear all", while the binder looks nominally alive. |
| Phones | POCO X7 Pro 2412DPC0AG (HyperOS OS3.0.302.0, A16; 2 reporters); Redmi 23117RA68G "emerald" (HyperOS 3). |
| Evidence | memory `shizuku-fails-on-hyperos3-poco-x7-pro.md` 🧪; `hyperos-shizuku-recorder-death-research.md` (health-gated status fix 🧪, parked on a tag, not merged). |
| Detects | Timeout only (10 s bind wait in `RecorderBackend`); no liveness probe on main. |
| Code does | Reports no recorder after the timeout. Advice: use Standalone. |
| If removed | n/a. |
| Review C-ids | C66 OK, C68 OK (both make "ready" stricter). |

### Q19 — Renamed Shizuku forks and Sui (no manager app)
| | |
|---|---|
| Other apps | A5 (see section F) |
| Behaviour | A stealth-mode fork re-signs Shizuku under a random package name; Sui installs no manager app. A package-name check says "not installed". |
| Phones | OnePlus 12 (A16, fork in stealth mode, issue #24); emulator. |
| Evidence | memory `shizuku-detection-not-by-package-name.md` 🧪 (instrumented test passed on OP9 and emulator). |
| Detects | Behaviour: `ShizukuBackend.kt:102-108` (manager resolved from the API permission), `:120` `pingBinder`. Package name only as a fallback (`:38,97`). |
| Code does | Asks the binder first. |
| If removed | Shizuku mode greyed out for fork and Sui users. |
| Review C-ids | None. |

### Q20 — Stop from Active apps leaves the recorder alive, and the next app start kills it
| | |
|---|---|
| Behaviour | Android's Task Manager "Stop" ends the app process but not the shell recorder. The next call wakes the app, which killed that recorder and tried to launch a new one over ADB; that cannot work off Wi-Fi. |
| Phones | Pixel 8 (mobile data only). |
| Evidence | CHANGELOG 2.5.2 (BabApps item 115) 🧪 (emulator only). |
| Detects | Behaviour: `RecorderServerLauncher.kt:168-172` `awaitLiveDaemon()` (hand-over grace, `DaemonReuse.kt`). |
| Code does | Reuses the recorder that hands its binder over. |
| If removed | Calls after a Stop are lost off Wi-Fi. |
| Review C-ids | C17 OK (skip the grace only when this process saw its daemon die). C2 OK in idea (finalise an orphaned session), but see Q25 RISK. C39 OK. C91 OK. |

---

## B. Boot, app lifecycle and OEM process killing

### Q21 — The first call after a reboot: PHONE_STATE broadcast arrives ~9 s late
| | |
|---|---|
| Behaviour | On a freshly booted, busy system the static `PHONE_STATE` broadcast arrives ~9 s late, after a short call has ended. A registered telephony listener fires in real time but carries no number. |
| Phones | Not named in code; field notes point to the OnePlus 12 (📐). |
| Evidence | Code comment `CallMonitorService.kt:33-53` ("confirmed via the framework's own IMS logs"); memory `post-reboot-daemon-coldstart-latency.md` (OxygenOS also delays `BOOT_COMPLETED` without Auto-launch). No ✅ recorded. |
| Detects | Time window after boot (not brand): `CallMonitorService` runs a live listener for a bounded window. `PhoneStateReceiver.kt:63-70` defers to it. |
| Code does | State only; the number is filled afterwards from the call log (`RecordingForegroundService.kt:833-877`). |
| If removed | The first call after a reboot is missed. |
| Review C-ids | C118 RISK (see key table). C59 OK (require the call-log row's DATE ≥ start, which protects this same rename). C57 OK (`exported=false` still receives system boot broadcasts). C48 OK. |

### Q22 — Android 15+: BOOT_COMPLETED after a force-stop, and no call broadcasts while stopped
| | |
|---|---|
| Other apps | A3 (see section F) |
| Behaviour | Android 15+ sends `BOOT_COMPLETED` when an app leaves the force-stopped state, without a reboot. A force-stopped app receives no `PHONE_STATE`, so calls are missed until it is opened. Task-killer apps (`am force-stop` on every non-whitelisted app) cause this. |
| Phones | Galaxy Z Fold 7 (A16, Shizuku mode); repro on the A16 emulator. |
| Evidence | memory issue-#71 note: cause near-certain 📐, fixes 🧪 (emulator reproduced the "boot without reboot" tell). |
| Detects | Behaviour: `AppStopHistory.kt:26-67` (exit reasons), `:120-150`. ⚠️ SDK `≥35` for `ApplicationStartInfo` (an API that exists only there, so the gate is correct). |
| Code does | Names the stop in the report and on the "call not recorded" card. |
| If removed | Missed calls blamed on "the phone or another app". |
| Review C-ids | C141 OK, C142 OK (both refine the attribution; C142's `lastUpdateTime` window is public API). |

### Q23 — Honor pauses CallVault's process while the screen is dark in a call
| | |
|---|---|
| Behaviour | The app logs nothing from SCREEN_OFF (proximity sensor) until SCREEN_ON; the shell recorder keeps running. The app was also frozen for tens of seconds while WhatsApp was on screen. Battery "run in background" settings did not help. |
| Phones | Honor BVL-N59 (MagicOS 11, A17); Honor BKQ-N49 (A17, one of two phones). |
| Evidence | memory `honor-freezes-app-screen-off-in-call.md`: ✅ 2026-11-06 (8 calls through 140 pauses), then ❌ 2026-11-07 (a 0:00 file after a pause; cause unknown). `issue-53-honor-startrecording.md` 📐 (BKQ-N49 freeze). CHANGELOG "Honor phones pausing CallVault". |
| Detects | Behaviour, not brand: `AppPauseWatch.kt:31-46` (uptime gap ≥10 s, remembered 30 days). |
| Code does | No resilient handoff on a pausing phone (`AudioRecordingEngine.kt:410-420`, `HandoffPolicy.kt:56`); skips the call-start USB refresh (`RecorderServerLauncher.kt:164,171`). |
| If removed | Handoff file 4.4 s of a 43 s call; a call lost inside the USB refresh. |
| Review C-ids | C105 DECISION (keep the pause skip when moving the refresh). C63 RISK. C33 OK. C88 OK (its 1–2 s check reads the daemon's state, but it fires late on a paused app; don't let a late check undo a good start). |

### Q24 — Honor: `AudioRecord.startRecording()` needs an Application
| | |
|---|---|
| Behaviour | Honor's audit code at the top of `startRecording()` calls `requireNonNull(ActivityThread.currentApplication())`. Our hosts have none, so it throws an NPE. |
| Phones | Honor Magic 9 Pro Max (MagicOS 11.0.0.111); Honor Magic 8 Pro ×2 (standalone); Honor BVL-N59 (handoff path). |
| Evidence | memory `issue-53-honor-startrecording.md` 🧪 (log-confirmed on both Magic 8 Pro); `honor-freezes…` (handoff repair merged 2.5.0). `docs/dev-notes/2026-10-25-issue-53-honor-startrecording.md`. |
| Detects | Behaviour (exception shape): `HostAudioRecord.kt:63-74` (`VendorStartCrash.matches`). |
| Code does | Installs a shell `Application` (`ShellApplication`) and starts the same record again. |
| If removed | No app calls on Honor MagicOS 11; the first call after each recorder restart falls off handoff. |
| Review C-ids | None. |

### Q25 — OEM battery management kills the app mid-call, or reaps the idle recorder
| | |
|---|---|
| Other apps | A3 (see section F) |
| Behaviour | OnePlus battery management restarted the app ~12× in a day, once mid-call, so the staged file was left behind. OxygenOS reaped the idle recorder within ~3 min, and the Doze allow-list alone was not enough. |
| Phones | OnePlus CPH2653 (OxygenOS 16); OnePlus 12 (2026-06-15). |
| Evidence | memory `field-reports-2026-09-29.md` (📐 inference; cut-off rescue ✅ 2026-09-29 on OP9 by force-stop); `daemon-survival-and-coldstart.md`. |
| Detects | Behaviour: staged file + `StagingNote` (`CutOffRescueWorker`). |
| Code does | Publishes cut-off recordings; the keep-alive relaunches the recorder. |
| If removed | Mid-call app death loses the call silently. |
| Review C-ids | **C2 RISK** (see key table: gate the orphan stop on "no call in progress"). C12 OK (never touch a live file). C46 OK. |

---

## C. App-call (VoIP) detection and capture

### Q26 — vivo/iQOO: the public `AudioRecord` constructor crashes in vendor code
| | |
|---|---|
| Behaviour | `VivoAudioRecordImpl` NPEs on `getOpPackageName()` because our host builds with a null Context, which is load-bearing on AOSP. All four host `AudioRecord` sites fail. |
| Phones | iQOO V2507A (OriginOS 6, A16); iQOO Z10x I2404 (A16). |
| Evidence | memory `private-reports-2026-09-28.md` 🧪 (crash worked around on the reporter's phone: sink + mic `state=1`). |
| Detects | Behaviour (exception shape): `HostAudioRecord.kt:37-48`, `VoipAudioPolicy.kt:160-188` (`VendorConstructorCrash.matches`). |
| Code does | Builds the record through `BypassedAudioRecord` (a port of the scrcpy workaround). ⚠️ SDK branches `BypassedAudioRecord.kt:114,135` (<S, <U, else). 📐 Android 17 removed fields this relies on (`android-17-call-recording-research.md`); not handled. |
| If removed | No recording at all on vivo (phone calls fell back to scrcpy only). |
| Review C-ids | None. |

### Q27 — vivo/iQOO zero-fill the far side of app calls (China-firmware toggle)
| | |
|---|---|
| Behaviour | vivo's native audioserver zero-fills our REMOTE_SUBMIX capture during COMMUNICATION mode (`isClientSilenced=false`, yet zeros). vivo Recorder's "In-app call recording" lifts it; it exists only on China firmware. |
| Phones | iQOO V2507A (toggle present, worked); iQOO Z10x I2404 (no toggle); a third Z10x (#64). |
| Evidence | memory `private-reports-2026-09-28.md`: user-reported fix 🧪; China-only 📐 (firmware code + public getprop dumps). |
| Detects | ⚠️ BRAND: `utils/DeviceInfo.kt:25` `isVivo()`, `:61` `isVivoWithoutInAppRecording()`. |
| Code does | Wording only: the one-sided warning text (`VoipRecordingCoordinator.kt:882-883`, `HomeScreen.kt:2909-2910`). Also a vivo-only diagnostic on every app call (`VoipRecordingCoordinator.kt:157-161`, see Unclear U3). |
| If removed | Wrong advice ("the other app blocks capture") on vivo. |
| Review C-ids | C108 OK (the vivo diagnostic is a synchronous binder call before the exclusion check; moving or dropping it is fine). |

### Q28 — Device call state includes app calls (One UI), but not on the OnePlus
| | |
|---|---|
| Behaviour | `TelephonyManager` call state and `PHONE_STATE` are device-wide and include self-managed app calls (the AOSP contract). On One UI a WhatsApp call raises OFFHOOK; on the OnePlus WhatsApp registers nothing (`mCallState=0` during a live WhatsApp call). |
| Phones | Galaxy S24 FE SM-S721B (A16); OnePlus 12, OnePlus 9 Pro (the outliers). |
| Evidence | memory `adbd-transport-and-oem-quirks.md` §3 (guard verified by log: fired twice, 0 empty files); `install-gate-must-check-audio-mode.md`; issue #54 (stray "Press to start recording", 🧪). No ✅ recorded. |
| Detects | Behaviour: `VoipTelephonyGate.kt:55-85` (`TelecomManager.isInManagedCall`); `RecordingPolicy.kt:109-122` `standsDownForAppCall`; `RecordingForegroundService.kt:239`; carrier-answer signal `CallSessionManager.kt:254-277`. |
| Code does | The carrier path stands down while an app call records; only a *managed* call suspends an app-call recording. |
| If removed | A phantom empty second recording named after an unrelated contact (One UI), or app calls blocking their own recording (shipped regression 75868ca). |
| Review C-ids | C1 OK: the interim fix is fine, and the full fix (`voipRecording && !suspendedForCarrier`) is safe **only because** suspension is set from the managed-call check at `CallSessionManager.kt:263-266`. Keep that. C3 OK (the install gate reads telephony + `VoipRecordingCoordinator.isRecording`; an unrecorded OnePlus WhatsApp call passes, which loses nothing). C16 OK. C79 OK. C54 OK (`isInCall` / telephony state only widen a protective gate). |

### Q29 — One UI reports the audio-mode owner as uid 1000
| | |
|---|---|
| Behaviour | During a WhatsApp call `mAudioModeOwner` reads uid 1000 (system), so files were named "Device maintenance". |
| Phones | Galaxy S24 FE (A16). |
| Evidence | memory `adbd-transport-and-oem-quirks.md` §2 (fixed; no ✅ marker). |
| Detects | Behaviour: `server/VoipAppIdentity.kt:46-60` (reject uid <10000). |
| Code does | Falls through to the app's own playback track. |
| If removed | Wrong app name and icon. |
| Review C-ids | None. |

### Q30 — Samsung Wi-Fi Calling: the call tap is silent, the call runs in COMMUNICATION mode
| | |
|---|---|
| Other apps | A8 (see section F) |
| Behaviour | On some Samsungs a VoWiFi call's `VOICE_CALL` tap delivers silence or one side. The mode flips IN_CALL → IN_COMMUNICATION ~0.4–0.7 s after OFFHOOK. On the Fold2 the far side plays from a system_server native track that is never listed as a player (vcPlay=0). The app-call capture records both sides. On OnePlus/Qualcomm, VoWiFi records fine on the call tap, and forcing the app-call route there **loses** the far side. |
| Phones | Galaxy Z Fold2 SM-F976B (One UI 9, A17); Galaxy S24 SM-S921U (A17, own side only); S25 Ultra (upstream report). Counter-example: OnePlus 9 Pro (A14). |
| Evidence | memory `samsung-vowifi-not-recordable.md`: Fold2 auto-switch both sides 2026-11-08 and VoLTE stayed on call source 2026-11-09 (tester's ear, 🧪); OP9 VoWiFi recorded ✅ 2026-09-30 (maintainer); forced route on OP9 = far side silent (measured). |
| Detects | Mode timeline + ⚠️ BRAND: `WifiCallRoute.kt:83-102` (within 5 s; IN_COMMUNICATION; IN_CALL seen first when there is no player); `isSamsung` from `Build.MANUFACTURER` at `:131`. The brand gate is load-bearing: the OnePlus counter-example. |
| Code does | Switches the carrier call to the app-call capture (`AudioRecordingEngine.kt:329,551-561`, which falls back to the call source if not confirmed). |
| If removed | Silent Samsung Wi-Fi calls; without the brand gate, OnePlus VoWiFi loses the far side. |
| Review C-ids | **C102 RISK** (see key table). C184 OK (removing the `forced` plumbing keeps `isSamsung` and `inCallSeenFirst`). C101 OK (a held app call must not resume during a Wi-Fi-calling call). C1/C16 OK (keep the `isAppMixSession` exclusion, `CallSessionManager.kt:268`). |

### Q31 — Stale or out-of-order mode at the call's edges
| | |
|---|---|
| Other apps | A9 (see section F) |
| Behaviour | (a) `OnModeChangedListener` delivered a mode that was already over: START ran 290 ms after WhatsApp was back to NORMAL. (b) At a Wi-Fi-call hang-up Samsung drops the Telecom call before the mode leaves IN_COMMUNICATION, which left a phantom 1 s "app call" file. |
| Phones | (a) POCO X5 Pro 5G (HyperOS OS2.0.17, A14). (b) Galaxy S24 SM-S921U, Galaxy Z Fold2. |
| Evidence | memory `issue-68-voip-stale-start.md` 🧪 (unit tests); `samsung-vowifi-not-recordable.md` / `field-reports-2026-09-29.md` (side bug). Rollback note `voip-stale-start-68.md`. |
| Detects | Behaviour: `VoipSwitchPolicy.kt:79` `stillStarting` (re-reads the live mode); `VoipStubPolicy.kt:23-29` (<3 s and the far side never heard). |
| Code does | No START on a stale mode; discards the stub with no one-sided warning (`VoipRecordingCoordinator.kt:862`). |
| If removed | Stray 1 s files and false "only your side" cards. |
| Review C-ids | C49 OK (it changes the discard path for answered short calls; keep the stub rule for never-heard stubs). |

### Q32 — Audio-mode dips during app calls
| | |
|---|---|
| Behaviour | Telegram drops IN_COMMUNICATION → NORMAL for ~1 s mid-call and comes back. Outgoing Telegram/AyuGram calls hold the mode ~4.8 s while ringing, drop to NORMAL, and re-enter at answer. The mode also wobbles on route changes. |
| Phones | OnePlus CPH2747 (A17, Shizuku); Galaxy S25 Ultra. |
| Evidence | rollback note `docs/dev-notes/rollback/voip-mode-blip.md` ✅ 2026-11-02 (CPH2747, field); `VoipRingDipPolicy.kt:12-21` (S25 Ultra, BabApps item 107, 🧪). |
| Detects | Behaviour: `VoipSwitchPolicy.kt:54-89` (`STAY`, `stillEnded`); `VoipRingDipPolicy.kt:26-47` (young call, no voice player yet); 1.5 s end debounce. |
| Code does | Cancels the pending end when the mode returns; waits up to 30 s for a ring dip. |
| If removed | Every other Telegram call cut; a 5 s stub plus a second file. |
| Review C-ids | None directly (C14 is the LINE path). |

### Q33 — LINE never puts the phone into call mode
| | |
|---|---|
| Behaviour | A LINE call stays in MODE_NORMAL; only a voice-comm player and a voice-comm recorder show it. |
| Phones | POCO F8 Ultra (HyperOS 3). |
| Evidence | CHANGELOG 2.5.2 (#80) 🧪, test build only; "capturing in NORMAL is unproven on that phone". |
| Detects | Player/recorder ownership + ⚠️ PACKAGE allow-list: `VoipNormalModePolicy.kt:16-80` (`jp.naver.line.android` only, 2 s hold); `VoipCallDetector.kt:74-79,368-396,452,576-591`. |
| Code does | Starts and ends an app call without a mode change. |
| If removed | LINE calls on that phone unrecorded (as before 2.5.2). |
| Review C-ids | C9, C12, C14, C16, C26, C30, C31, C178: all OK. They fix bugs inside this path without removing the trigger. C14's "hand back to the normal-mode watcher" is the right shape. Item-108 revert = DECISION (key table). |

### Q34 — Which app: the mode owner first, the call track later (Telegram ~2.5 s)
| | |
|---|---|
| Behaviour | At detection, Telegram's playback track does not exist yet; the mode owner does. Telegram's call track appears ~2.5 s after the mode flip, so a 1.2 s lookup found no app. The app process sees anonymised playback configs; only the daemon sees usages. |
| Phones | OnePlus 12 (measured); OnePlus CPH2747 (A17). |
| Evidence | memory `voip-recording-feasibility.md` (measured); rollback note `voip-late-app-name.md` ✅ 2026-11-02 (field). |
| Detects | Behaviour: `server/VoipAppIdentity.kt` (mode owner, then players); late retries at 3/8/20 s (`VoipLateCaller`). |
| Code does | Names the file when the app is found late. |
| If removed | Nameless Telegram files. |
| Review C-ids | C102 OK for this case (Telegram's player is created after the arm). C128 OK. |

### Q35 — The caller's name only exists in the call notification, in version-dependent shapes
| | |
|---|---|
| Other apps | A10, A16 (see section F) |
| Behaviour | App calls are not in Telecom or the call log. WhatsApp puts the person in `android.title`; Telegram puts "Ongoing Telegram call" there and the person in `android.text`, with no `category`. Android 14 dumps flags as hex (`flags=0x62`), with no `ONGOING_EVENT` word; Spanned titles dump as `SpannableString`. |
| Phones | OnePlus 9 Pro (A14: 1/6 named before the fix); OnePlus 12 (A16). |
| Evidence | memory `voip-recording-feasibility.md`: ✅ 2026-09-22 (maintainer saw the name). |
| Detects | Behaviour (parses both forms): `server/VoipCallerName.kt:41-49,149,187-195`. |
| Code does | Hex-or-word ongoing check; title, then text. |
| If removed | No caller names on Android ≤14 or for Telegram. |
| Review C-ids | C108 DECISION (exclusions must still be checked before any file exists). |

### Q36 — WhatsApp's notification: a timer at pickup, removed before the mode drops
| | |
|---|---|
| Other apps | A9, A16 (see section F) |
| Behaviour | WhatsApp's call notification has `showChronometer=false` while ringing and `true` at pickup. At hang-up it removes the notification **before** the audio mode drops. Ringback reaches the far submix on outgoing calls, so far audio is no proof of answer. |
| Phones | OnePlus 9 Pro (A14). |
| Evidence | memory `record-from-answer.md`: ✅ OP9 (8.4 s file for a 22 s call). |
| Detects | Behaviour: `VoipAnswerHold.kt:26-55`; `VoipRecordingCoordinator.kt:270-273,392-432`. |
| Code does | Holds the encode until ANSWERED; discards an unanswered call; 5 s grace for a missing notification. |
| If removed | Stubs for unanswered calls; ringing recorded. |
| Review C-ids | C49 OK (and it rejects "far audio = answered" for the ringback reason). C31 OK (`NO_TIMER` releases at once for apps without a timer). C30 OK. |

### Q37 — Mic contention: our near-side record gets silenced; WhatsApp restarts its capture
| | |
|---|---|
| Other apps | A6, A2 (see section F) |
| Behaviour | On One UI, WhatsApp captures with `src:MIC`; the most recent starter wins, so our record is SILENCED and delivers zeros. On the OnePlus 9 Pro WhatsApp restarts capture ~every 400 ms while ringing. 📐 Our MIC may in turn mute the user's uplink on phones where the app uses plain MIC. |
| Phones | Galaxy S24 FE (A16); OnePlus 9 Pro (A14); Galaxy S21 Ultra (155 re-takes in one log). |
| Evidence | `docs/dev-notes/2026-07-30-voip-near-party-silenced-on-one-ui.md` (dumpsys shows `SILENCED` for shell; measured 8 s → 1.5 s); `docs/dev-notes/2026-09-23-voip-near-side-zeros.md` (OP9: 31 re-takes, all `silenced=false`); memory `voip-mic-capture-mutes-user.md` 📐. |
| Detects | Behaviour: `server/VoipCaptureSession.kt:531-553` (15 silent chunks → `retakeMic` `:592`; logs `isClientSilenced`). |
| Code does | Takes the mic back. |
| If removed | Long silent gaps in the user's own voice on Samsung. |
| Review C-ids | C120 OK; its backlog-#9 add-on is a RISK (key table). C38 OK for counting read errors. |

### Q38 — Two free-running captures drift apart
| | |
|---|---|
| Behaviour | Mic and far-sink chunks paired by arrival drifted +6.4 s over 120 s (speakerphone, WhatsApp Business), growing with each re-take. |
| Phones | Galaxy S21 Ultra; OnePlus 9 Pro (+96 → +650 ms). |
| Evidence | memory `voip-sync-offset-issue-41.md`: ✅ reporter confirmed the fix 2026-09-26. |
| Detects | Always on: `server/SlotPairer.kt` (20 ms real-time slots). |
| Code does | Each side fills its own time slot. |
| If removed | Far party early or late in the file. |
| Review C-ids | None. |

### Q39 — The HAL cannot serve a second voice capture during VOICE_CALL
| | |
|---|---|
| Other apps | A1 (see section F) |
| Behaviour | Opening a second voice-source capture (e.g. `VOICE_DOWNLINK`) while `VOICE_CALL` records silently drops the user's own side. No error anywhere. |
| Phones | OnePlus 12 (A/B by ear); another device in ShizuCallRecorder #130. |
| Evidence | memory `no-second-voice-capture-during-call.md` (proven by A/B on real calls 2026-08-23). |
| Detects | Nothing; it is a rule. |
| Code does | Never opens a second voice capture (VoIP uses MIC + a policy loopback, neither a voice input). |
| If removed | n/a. |
| Review C-ids | C38 later-part RISK (key table). |

### Q40 — Android 13 and older cannot capture app calls
| | |
|---|---|
| Behaviour | Before Android 14 the shell has no `CAPTURE_VOICE_COMMUNICATION_OUTPUT`: `registerAudioPolicy` is rejected (A13), and `setTargetMixRole` is missing on A12. |
| Phones | POCO F3 (MIUI, A13); Redmi 12 Pro 22101316C (MIUI 13, A12); Infinix X669D (A12). |
| Evidence | memory `issue-42-android13-voip.md` (gap confirmed on hardware); `field-reports-2026-09-29.md`. |
| Detects | ⚠️ SDK: `data/ModeCapability.kt:88-98` (`VOIP_RECORDING → 34`). Justified by the AOSP Shell manifest, but an OEM Shell manifest could differ. |
| Code does | Greys the setting out with "Needs Android 14". |
| If removed | Silent failure on every app call. |
| Review C-ids | None. |

### Q41 — Early-invalidated capture tracks and the leaked shell mic op
| | |
|---|---|
| Other apps | A2 (see section F) |
| Behaviour | AudioFlinger invalidates a capture track early in a call (`CBLK_INVALID`, 0.4–0.8 s in). The invalidated track's RECORD_AUDIO op is never finished, even after an accepted `stop()`, so the mic dot stays on until the shell process dies. |
| Phones | OnePlus 13 (tester); OnePlus CPH2747 (A17, Shizuku, new report); Pixel 8 (A17, "in use by Shell"). |
| Evidence | memory `shell-mic-open-first-evidence.md`: ❌ 2026-09-11 (stop does not clear it); ✅ closed 2026-10-12 on field silence; reopened ⏸️ 2026-11-07 (Shizuku gap fix 🧪). CHANGELOG Pixel 8 entry 🧪. |
| Detects | Behaviour: `HandoffReceiver.kt:338-440` (rebuild on invalidation, `MAX_REARMS` 3 at `:91`); `MicOpAutoHeal` (shell mic op held 8 s after the call with nothing recording) + `MicOpHealPolicy.kt:44-121`. |
| Code does | Rebuilds the capture mid-call; restarts the host to clear the op. |
| If removed | Truncated calls and a permanent mic dot. |
| Review C-ids | C54 OK. **C64 BREAKS** (via Q08). C116 OK. C117 DECISION (converter, not "pin the channel count"). C135 OK. C2 OK (schedules the heal). C38 OK. |

### Q42 — Handoff picks up the previous call's buffer
| | |
|---|---|
| Other apps | A2 (see section F) |
| Behaviour | The native scan returned the first matching ashmem block; the host's dup of the previous call's block was never closed, so every second call failed with `CBLK_INVALID` and 0 bytes. |
| Phones | Xiaomi M2101K9AG / Redmi Note 10 Pro (MIUI 14, A13). |
| Evidence | memory `private-reports-2026-09-28.md` 🧪 (not reproduced; the emulator cannot run handoff). |
| Detects | Behaviour: `HandoffSource.kt:180-205` `releaseHeld` (closes the dup, checks it is freed); the native scan skips invalid blocks (`AudioHandoffNative.kt:76`). |
| Code does | Lets go of each call's buffer. A fallback to direct capture on a dead handoff is **not** built. |
| If removed | Alternate calls lost with Resilient on. |
| Review C-ids | C116 OK. |

### Q43 — Handoff ring geometry differs per phone and per Android version
| | |
|---|---|
| Behaviour | `getBufferSizeInFrames` reported 8192 against a 4096-frame allocation on the Galaxy S24 FE, so handoff is refused there (safely). The control-block size is 224/228/232 bytes on Android 11/12/13+. |
| Phones | Galaxy S24 FE (One UI); OnePlus 12 (known-good offsets). |
| Evidence | memory `adbd-transport-and-oem-quirks.md` §4 (open); comment `HandoffGeometry.kt:56-60`. |
| Detects | Behaviour for size (`HandoffGeometry.kt:50-66`, ashmem size is ground truth); ⚠️ SDK for the struct offset `:78-82` (derived from AOSP, a valid gate). |
| Code does | Clamps or refuses; falls back to the daemon path. |
| If removed | Reads 12 KB past the mapping, or misaligned audio. |
| Review C-ids | C63 RISK. |

### Q44 — Handoff never fills the ring on Android 12
| | |
|---|---|
| Behaviour | Handoff was delivered but the ring was never written ("ring stalled", 140-byte file). |
| Phones | Redmi 12 Pro 22101316C (MIUI 13, A12). |
| Evidence | memory `field-reports-2026-09-29.md` (one log; closed by the maintainer for A12). |
| Detects | ⚠️ SDK: `data/ModeCapability.kt:91-97` (`RESILIENT_RECORDING → 33`). |
| Code does | Greys Resilient out below Android 13. |
| If removed | 140-byte files on Android 12. |
| Review C-ids | C63 RISK. |

### Q45 — AAC at a low rate is refused by some hardware encoders
| | |
|---|---|
| Behaviour | AAC-LC 48 kHz mono `configure()` is refused at low bit rates although the encoder advertises the range. "Changing from Opus to AAC failed to record the next call." |
| Phones | Galaxy S20 FE 5G (A13) (#28c). |
| Evidence | memory `github-issues-25-28.md`: 28c 🧪 ("the AAC cause still needs his phone"). Comment `DirectAudioRecorderSession.kt:163-176`. |
| Detects | Behaviour: `DirectAudioRecorderSession.kt:177-210` (create the encoder by name, retry at the codec's default rate); stereo refused → mono `:122-128`. |
| Code does | Records at the recommended rate instead of nothing. |
| If removed | Calls record nothing after switching to AAC. |
| Review C-ids | C119 OK (extends this to the VoIP and handoff paths). C180 DECISION (pre-warm only; do not reorder). |

### Q46 — After a recorded call, other apps record silence
| | |
|---|---|
| Other apps | A7 (see section F) |
| Behaviour | After any phone call, voice messages in other apps came out silent until CallVault was stopped. Stopping CallVault restored the mic at once on a second phone. |
| Phones | Galaxy A56 (A17); Galaxy S23+ (#77). |
| Evidence | CHANGELOG 2.5.1 "Other apps no longer record silence" 🧪 (test build 2.5.1-mic1); memory `issue-77-s23-mic-silenced.md` ⏸️ 📐. |
| Detects | Nothing phone-specific; `HandoffSource.kt:180-205` frees the host proxy and checks it after GC. |
| Code does | Releases the call's input properly at the end. |
| If removed | Other apps silenced after calls. |
| Review C-ids | C109/C110 OK (keep the temp file for rescue; don't release early). C120 OK. |

---

## D. Notifications, updates and storage

### Q47 — Cloud SAF providers reject `"rw"` and report 0 bytes while uploading
| | |
|---|---|
| Other apps | A17 (see section F) |
| Behaviour | Google Drive's provider (also Downloads, SD card and other cloud providers) rejects `openFileDescriptor(uri, "rw")` ("Unsupported mode: rw"). Right after a write it reports `length()=0` while the upload settles. |
| Phones | OnePlus CPH2653 (A16) with a Drive recording folder (any phone with that provider). |
| Evidence | memory `user-bug-reports-voicemail-devoptions.md`: validated on the reporter's device (v1.3.1). Code comment `CloudCopyPolicy.kt:108-112`. |
| Detects | Behaviour: `SafHelper.kt:72-133` (`rw` refused → stage in app storage); ⚠️ provider authority list `SafHelper.kt:223` `isCloudFolder`. |
| Code does | Stages to a temp file; trusts the captured byte count (`RecordingForegroundService.kt:1101-1110`); treats an unmeasurable Drive length as COMPLETE and never deletes on it (`CloudCopyPolicy.kt:98-118`). |
| If removed | A real recording deleted as "empty"; Drive copies deleted and re-uploaded. |
| Review C-ids | **C50 BREAKS** (key table). C147 OK. C55 OK (inode comparison). C28 OK. |

### Q48 — Advanced Protection blocks the updater's PackageInstaller path
| | |
|---|---|
| Behaviour | Android's Advanced Protection blocks installs from unknown sources, so the Shizuku-mode updater (PackageInstaller session) fails with "User rejected permissions" and keeps re-offering. |
| Phones | OnePlus CPH2749 (A17, Shizuku mode). |
| Evidence | `docs/dev-notes/2026-10-31-report-oneplus-cph2749-advanced-protection-updater.md` (report closed; fix not built). |
| Detects | Not detected. `system/updates/UpdateInstaller.kt:71,157` falls back to PackageInstaller in Shizuku mode. |
| Code does | Nothing yet. |
| If removed | n/a. |
| Review C-ids | C3 OK (adds a call check before `session.commit`; does not address Advanced Protection). |

### Q49 — Android 17: an app `notify()` on a foreground service's id does not replace it
| | |
|---|---|
| Behaviour | After a call, the recording notice stayed under id 4720: the keep-alive's `notify(Ready)` did not take; re-posting through `startForeground` did. A LAVA LXX508 (A14) showed the async `startForeground` race that started this. |
| Phones | Pixel 10 Pro XL (A17, Shizuku + app calls); LAVA LXX508 (A14). |
| Evidence | memory `issue-73-stuck-notification-shizuku-voip.md`: the test build fixed it for the reporter; ❌ 2026-11-10 still stuck in 2.5.0 (cause unknown). `stuck-call-in-progress-notification.md` 🧪. |
| Detects | Behaviour: `DaemonKeepAliveService.kt:142-160` (`healSharedNotice` looks at active notifications 1.5 s after release, then `repostAsForeground`). |
| Code does | Re-posts through `startForeground`. |
| If removed | A permanent "call in progress" notice on A17 Pixels. |
| Review C-ids | C23 RISK (key table). C36 OK. |

### Q50 — Pairing expires after 7 days without a key-authenticated connect
| | |
|---|---|
| Behaviour | Wireless-debugging TLS connects never reset `adb_allowed_connection_time` (default 7 days); only USB or `tcpip` loopback connects do. Without offline mode the pairing dies. |
| Phones | Reporter's phone (#43, model not in notes); AOSP source. |
| Evidence | memory `issue-43-pairing-expires.md`: 📐 source-read; detect + re-pair ✅ 2026-09-29 (OP9). |
| Detects | Behaviour: `PairingLoss` (3 refusals in a row). |
| Code does | "Pairing expired" card, re-pair flow. |
| If removed | Endless silent retries. |
| Review C-ids | C130 OK (adds `PAIRING_LOST` to the keep-alive stand-down). |

### Q51 — Voicemail apps take IN_COMMUNICATION; app calls are invisible to `isInCall`
| | |
|---|---|
| Other apps | A9 (see section F) |
| Behaviour | A voicemail app takes `MODE_IN_COMMUNICATION` to play through the earpiece, so it looks like an app call. `TelecomManager.isInCall()` is false for a whole WhatsApp call. `getActiveRecordingConfigurations()` is readable by apps (a source, not a count). 📐 This evidence looks unreliable on OnePlus A17. |
| Phones | Galaxy S20 FE (One UI 5.1, A13) (#29); OnePlus 12 (measured); OnePlus CPH2749 and CPH2747 (A17, gate gaps). |
| Evidence | memory `issue-29-voicemail-false-call.md` 🧪 (awaiting a real voicemail); CPH2749 dev-note (📐). |
| Detects | Behaviour: `CallEvidence.kt:65-85`; used only to gate the miss report, `VoipRecordingCoordinator.kt:585`. |
| Code does | Records anyway; reports a miss only with corroboration. |
| If removed | False "app call not recorded" cards for every voicemail. |
| Review C-ids | C54 OK (adds `isInCall` as an extra OR in a protective gate, not as call evidence). C113 OK. |

---

## Fallbacks that are NOT quirks

No phone evidence was found behind these. They are likely bug covers or plain safety nets. Fix or remove them on
their own merits.

| # | Where | What it does | Related C-id |
|---|---|---|---|
| N1 | `services/recording/AudioRecordingEngine.kt:432-460` | Local ADB + scrcpy recording in the **app** process when the daemon cannot come up. No note records it ever rescuing a call. In Shizuku mode it can write WD and restart adbd (Q13); the keep-alive can cut it mid-call. | C72, C89, C106 |
| N2 | `data/recordings/db/RecordingDatabase.kt:92` | `fallbackToDestructiveMigration`: a schema safety net, not a device issue. Review says keep it, test the migration path. | C136 |
| N3 | `data/clips/ClipCutter.kt:62` | One `runCatching` covers both "open the fd" and "do the cut", so any cut failure becomes the copy-to-cache path. The pipe case is legitimate; swallowing other failures is a bug cover. | C167 |
| N4 | `transcription/TranscriptionRunner.kt:236`, `TranscriptJunk.kt:91` | The junk retry repeats the wrong-script decodes and clears the abort. These are transcription heuristics, not device ones. | C177, C43, C144 |
| N5 | `summary/SummaryRunner.kt:143-153` | A truncated summary is "repaired" instead of joining the parts. | C71 |
| N6 | `ui/viewmodels/HomeViewModel.kt:632-643` | A failed playback is treated as "file gone" and prunes the row. | C5 |
| N7 | `data/recordings/DriveCatalogRepair.kt:52-90` | A failed or empty listing is treated as "deleted". | C8 |
| N8 | `services/DaemonKeepAliveService.kt:813-817`, `MicOpAutoHeal.kt:261` | Private `isWifiConnected` copies instead of `WifiState`. | C34 |
| N9 | `transcription/cloud/CloudTranscriber.kt:129`; `TranscriptionRunner.kt:363-375` | Restart from pass 1 / mark the backlog failed on retryable cloud errors. | C168, C70 |
| N10 | `system/updates/CallInProgressGate.kt:55-67` | Fail-open "safe to install" on unreadable telephony. A design choice, not a quirk; its blind spot is Q28 (OnePlus WhatsApp reads IDLE). | C3 |
| N11 | `server/RecorderServiceImpl.kt:180` (worker jobs) | No reset of the interrupt flag; a stray interrupt can fail the next scrcpy fallback. | C171 |
| N12 | `services/recording/RecordingForegroundService.kt:1020-1053` | Call-log number lookup with no time bound (the rename itself is needed for Q21; the missing bound is the bug). | C59 |

## Unclear

| # | Where | Why it can't be settled |
|---|---|---|
| U1 | `system/storage/SyncSweepWorker.kt:159-189` (`LocalDriveCopier`) | Born from an Honor field log where the `:drive` copier died (SIGKILL) and the in-app listing worked. The root cause found (a list reply over the binder limit) was fixed separately by sending the list through a file. Whether Honor kills `:drive` on its own is not shown. C11 wants it gone as a copy path. Probably safe to drop the in-app COPY; keep a bounded in-`:drive` retry. Needs that Honor phone's next report. |
| U2 | `server/BinderDelivery.kt:31,94` | The system-context delivery fallback uses `ActivityThread.systemMain()`, which the VoIP notes say breaks attribution. Copied from Shizuku; no note shows it was ever used. 📐 Transsion framework code could run there (Infinix, #70). |
| U3 | `services/recording/VoipRecordingCoordinator.kt:157-161` + `server/DiagnosticDumps.kt` (`vivo_verbose_arm`) | ⚠️ BRAND diagnostic that setprops vivo's audio log tag on every app call. Research found the decision lines never appear on retail builds (compiled out). Likely dead weight on the call-start path, but the vivo far-side matter is not closed. |
| U4 | `CallEvidence` gate on OnePlus A17 (Q51) | Two OnePlus A17 phones had real app calls the gate refused to report. It is unclear whether the gate is a quirk handler with a gap, or a bug cover. |
| U5 | `server/AppStartBinderSender.kt:39,83` (`UID_OBSERVER_ACTIVE_FALLBACK`) | Hidden-API constant fallback. No device evidence either way. |
| U6 | Stereo → mono "where the phone can only record one channel (e.g. Bluetooth)" (`DirectAudioRecorderSession.kt:122-128`) | Owner, 2026-11-19: stereo recordings over AirPods 4 were fine on his own phone back when stereo was the default. That is ONE phone + ONE headset (🧪 one data point), and the owner stresses different hardware behaves differently, so it does not prove other phones or headsets. Decision: KEEP the mono fallback, but treat it as a named quirk: it must log when it is taken (phone model, route, requested vs granted channels), so field reports show where it really fires. LE Audio untested. Other apps: A12. |
| U7 | A second shell-uid call recorder on the same phone (JemRec seen on an Infinix X6871 GT20 Pro, A16) | Seen in a log with no call (📐). `killStaleRecorders` (`RecorderServiceImpl.kt:434`) only kills ours. Whether two shell recorders interfere is unknown. Other apps: A2 (the same item, now catalogued there). |
| U8 | (superseded by Q68, 2026-11-20) `server/BypassedAudioRecord.kt:114-145` on Android 17 | 📐 desk research says A17 removed fields this vivo path relies on; the code branches only <S, <U and else. Unknown until a vivo on A17 reports. |

## 📐 Background notes (not quirks, no field evidence)

Some review issues describe Android-version behaviour that comes from reviewers' reading, not from a phone: C52
(SQLite's 999-variable cap on Android 11), C143 (`AppCompatDelegate` locales below Android 13), C161 (predictive
back on Android 16 overlays), C142 (pre-Android-14 update kills recorded as `USER_REQUESTED`). Treat them as
📐 until a phone shows them. They are not entries here.

HyperOS 3 / Android 12+ background limits (research only, `hyperos-shizuku-recorder-death-research.md`): the phantom-process monitor, MiuiSentinel's per-app RAM ceiling, PowerKeeper rewriting allow-lists. No code handles them; do not assume a fix covers them.

### Q52 — OnePlus (ColorOS/OxygenOS) freezes the app during background work
| Field | Value |
|---|---|
| Behaviour | `OplusHansManager: freeze uid <app>` a few seconds after a WorkManager job starts (retention sweep). The job stalls until the app is opened. AppPause logged pauses of 816 s, 893 s and 138 s in one afternoon. |
| Phones | OnePlus 9 Pro (LE2121), Android 14, Shizuku mode. Other OnePlus/OPPO/realme builds likely; unconfirmed. |
| Evidence | 🧪 OP9 check 2026-11-19 (~/Archive/callvault/op9-checks-2026-11-19/logcat-live.txt). |
| Code | Not handled. Tracked as N11. |
| Review C-ids | Any fix that moves work into WorkManager on these phones must expect a freeze mid-run. |

### Note (B3 gate review, 2026-11-19): stale "call began" after two consecutive kills
If CallVault is killed during call A, stays dead until call B goes off-hook, and is then restarted mid-B, the persisted "call began" is still A's. A's orphan is then adopted, B isn't recorded and goes into A's file. PHONE_STATE receivers usually restart the app at a ring or IDLE, so this is rare. Watch for it on phones that block those restarts (Q23 Honor, Q52 OnePlus freeze). Tracked under C2.

## E. Added 2026-11-20 from the real-use review, the tracker and the support KB (Q53-Q76)

📐 Read from code at `7caf1ccb`; nothing here was run on a phone. Detect codes: D = a setting/API tells us before a call is lost; B = behaviour seen on this phone; I = identity only (wording or an unprobeable case); Rule = always applied; R = report only. Priority P0 = calls lost and the user can fix it, P1 = calls lost and the app handles it, P2 = partial/wrong recordings, P3 = wording/diagnostics. Wording marked 📐 comes from third-party notes and is not checked on that phone.

### Q53 — Answering a call crashes the app (ForegroundServiceStartNotAllowedException); ca
| | |
|---|---|
| Tracker ref | N26 |
| Who | Android 12+ (seen A14, A17 emulator); Shizuku mode with app calls off (no keep-alive); battery exemption missing |
| Symptom | Answering a call crashes the app (`ForegroundServiceStartNotAllowedException`); call not recorded |
| Detect | **D**: `mode.needsShizuku && !voipOn && !PowerManager.isIgnoringBatteryOptimizations()`. **B**: catch the exception at the start |
| Current handling | none: `services/call/CallSessionManager.kt:525` unguarded `startForegroundService` |
| Path | no |
| Message (plain words) | Card + notification: "CallVault can't start recording in the background on this phone. Allow unrestricted battery use for CallVault, or calls will not be recorded." After a crash-caught call: "This call was NOT recorded: Android blocked CallVault in the background. Allow unrestricted battery use." |
| Action (intent) | `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:` URI (app holds `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, manifest:33) |
| Test | E (A14/A17, proven repro) |
| Priority | P0 |

### Q54 — After ~90 days unopened: phone/call-log/contacts permissions revoked, package st
| | |
|---|---|
| Other apps | A15 (see section F) |
| Tracker ref | R4 |
| Who | Android 11+ all makers; worst when no keep-alive runs at the scan (Shizuku + app calls off) |
| Symptom | After ~90 days unopened: phone/call-log/contacts permissions revoked, package stopped, calls not even delivered (PROOF.md, A14/A17 emulator) |
| Detect | **D**: `PackageManagerCompat.getUnusedAppRestrictionsStatus()` (androidx.core 1.18 is present) = `API_30/API_31/API_30_BACKPORT` → on |
| Current handling | none (grep empty) |
| Path | no |
| Message (plain words) | Setup + "Your phone": "If you don't open CallVault for a few months, Android may pause it and remove its phone permissions. Then calls stop being recorded with no warning. Turn off 'Pause app activity if unused'." (Samsung 📐: "Remove permissions if app is unused") |
| Action (intent) | `IntentCompat.createManageUnusedAppRestrictionsIntent(ctx, pkg)` via `startActivityForResult` |
| Test | E (recipe in PROOF.md) |
| Priority | P0 |

### Q55 — Battery exemption lost after setup; keep-alive/relaunch blocked; triggers Q53
| | |
|---|---|
| Tracker ref | R5 |
| Who | All; Samsung re-sleeps apps after days/updates; HyperOS PowerKeeper rewrites lists (📐) |
| Symptom | Battery exemption lost after setup; keep-alive/relaunch blocked; triggers Q53 |
| Detect | **D**: `isIgnoringBatteryOptimizations` was true, now false |
| Current handling | only at app open: `onboarding/OnboardingStatus.kt:85`, `AppNavigationScreen.kt:384` (back to Permissions); report `utils/AppLogger.kt:587` |
| Path | partial |
| Message (plain words) | "CallVault lost its battery permission. Calls may be missed. Allow it again." |
| Action (intent) | same as Q53 |
| Test | E (`dumpsys deviceidle whitelist -pkg`) |
| Priority | P0 |

### Q56 — Background starts refused (keep-alive, relaunch, call service)
| | |
|---|---|
| Tracker ref | R5 |
| Who | Android 11+ all; user or maker chose "Restricted" |
| Symptom | Background starts refused (keep-alive, relaunch, call service) |
| Detect | **D**: `ActivityManager.isBackgroundRestricted()` |
| Current handling | none |
| Path | no |
| Message (plain words) | "Battery use for CallVault is set to 'Restricted'. Calls won't be recorded. Set it to 'Unrestricted'." (Pixel/OnePlus wording; Samsung 📐 "Unrestricted" under App info ▸ Battery) |
| Action (intent) | `ACTION_APPLICATION_DETAILS_SETTINGS` (no public intent to the battery sub-page) |
| Test | E (`appops set pkg RUN_ANY_IN_BACKGROUND ignore`) |
| Priority | P0 |

### Q57 — Jobs/alarms deferred; Restricted blocks FGS starts
| | |
|---|---|
| Tracker ref | R5 |
| Who | Android 11+; bucket RESTRICTED (45) or RARE |
| Symptom | Jobs/alarms deferred; Restricted blocks FGS starts |
| Detect | **D**: `UsageStatsManager.appStandbyBucket` |
| Current handling | report only, `AppLogger.kt:589` |
| Path | partial |
| Message (plain words) | Same as Q56 when 45; RARE: log only |
| Action (intent) | as Q56 |
| Test | E (`am set-standby-bucket pkg restricted`) |
| Priority | P1 |

### Q58 — App not restarted after kills; no boot start; PowerKeeper resets lists (📐)
| | |
|---|---|
| Other apps | A3 (see section F) |
| Who | Xiaomi/Redmi/POCO (MIUI, HyperOS) |
| Symptom | App not restarted after kills; no boot start; PowerKeeper resets lists (📐) |
| Detect | **I** (`Build.MANUFACTURER`) for wording. State: hidden MIUI app-op 10008 (📐, from a public MIUI-autostart library; not probed by us). **B** after the fact: Q22 exit reasons, Q63 |
| Current handling | wrong advice: `ui/screens/PermissionsScreen.kt:159-173` gives OnePlus wording to all |
| Path | no |
| Message (plain words) | "On Xiaomi phones also: App info ▸ 'Autostart' ON, and Battery saver ▸ 'No restrictions'. Lock CallVault in Recents." 📐 |
| Action (intent) | Autostart screen `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity` (📐, wrap in runCatching, fall back to App info) |
| Test | R (no Xiaomi phone) |
| Priority | P1 |

### Q59 — "Sleeping"/"Deep sleeping apps" and "Put unused apps to sleep" undo the exemptio
| | |
|---|---|
| Other apps | A3 (see section F) |
| Who | Samsung One UI |
| Symptom | "Sleeping"/"Deep sleeping apps" and "Put unused apps to sleep" undo the exemption |
| Detect | **I** for wording; **D** effect via Q55/Q56/Q57 |
| Current handling | as Q58 (OnePlus wording) |
| Path | no |
| Message (plain words) | "On Samsung: Settings ▸ Battery ▸ Background usage limits ▸ add CallVault to 'Never sleeping apps', and turn off 'Put unused apps to sleep'." 📐 |
| Action (intent) | Samsung battery page (📐 component varies) → fallback App info |
| Test | R (or a Samsung emulator image, not available) |
| Priority | P1 |

### Q60 — "App launch" manager blocks restarts (separate from Q23 pause, which no setting
| | |
|---|---|
| Other apps | A3 (see section F) |
| Who | Honor / Huawei |
| Symptom | "App launch" manager blocks restarts (separate from Q23 pause, which no setting fixes) |
| Detect | **I** |
| Current handling | as Q58 |
| Path | no |
| Message (plain words) | "On Honor/Huawei: Settings ▸ Battery ▸ App launch ▸ CallVault ▸ 'Manage manually', turn on Auto-launch, Secondary launch and Run in background." 📐 |
| Action (intent) | Huawei startup manager component (📐) → App info |
| Test | R (two Honor testers) |
| Priority | P1 |

### Q61 — Background kills
| | |
|---|---|
| Other apps | A3 (see section F) |
| Who | vivo / iQOO (OriginOS, Funtouch) |
| Symptom | Background kills |
| Detect | **I** |
| Current handling | as Q58 |
| Path | no |
| Message (plain words) | "On vivo: Settings ▸ Battery ▸ Background power consumption ▸ CallVault ▸ allow high background use." 📐 |
| Action (intent) | App info |
| Test | R |
| Priority | P1 |

### Q62 — App killed or frozen mid-call (Q25), frozen during jobs (Q52)
| | |
|---|---|
| Other apps | A3 (see section F) |
| Who | OnePlus / OPPO / realme (ColorOS, OxygenOS) |
| Symptom | App killed or frozen mid-call (Q25), frozen during jobs (Q52) |
| Detect | **I** (current wording fits) + **B** Q52 AppPause |
| Current handling | wording exists: `PermissionsScreen.kt:159-173`, `strings.xml:100` |
| Path | partial |
| Message (plain words) | Keep current: "Battery ▸ 'Allow background activity'"; add "and 'Allow auto launch'; lock CallVault in Recents." |
| Action (intent) | App info |
| Test | OP9 |
| Priority | P1 |

### Q63 — After 2 crashes close together Android delays the keep-alive's sticky restart ~3
| | |
|---|---|
| Tracker ref | R3 |
| Who | All Android (seen A14 emulator) |
| Symptom | After 2 crashes close together Android delays the keep-alive's sticky restart ~30 min; nothing retries |
| Detect | **B**: `ApplicationExitInfo` shows ≥2 `REASON_CRASH` in a short time (`system/health/AppStopHistory.kt` already reads exit reasons) |
| Current handling | none (`DaemonKeepAliveService.kt:606-630` stops itself on refusal) |
| Path | no |
| Message (plain words) | none (silent); fix the crash cause. Log: `profile: crash-backoff suspected` |
| Action (intent) | n/a |
| Test | E (`am crash` twice) |
| Priority | P1 |

### Q64 — Calls missed while a saver was on, unexplained
| | |
|---|---|
| Tracker ref | R52 |
| Who | All; Pixel Extreme Battery Saver, Samsung Maximum, Xiaomi Ultra |
| Symptom | Calls missed while a saver was on, unexplained |
| Detect | **D** for normal Battery Saver (`isPowerSaveMode`); extreme modes **R** (no public API, 📐) |
| Current handling | report only `AppLogger.kt:588` |
| Path | partial |
| Message (plain words) | Missed-call card: "Battery Saver was on at the time of this call." |
| Action (intent) | `Settings.ACTION_BATTERY_SAVER_SETTINGS` |
| Test | E |
| Priority | P2 |

### Q65 — Notice says "You stopped CallVault" when the phone did
| | |
|---|---|
| Other apps | A3 (see section F) |
| Tracker ref | R53 |
| Who | Xiaomi/Honor/Huawei style power keepers (📐, no sample) |
| Symptom | Notice says "You stopped CallVault" when the phone did |
| Detect | **B** (📐): resolve the "from pid" in the exit description to a package via the daemon |
| Current handling | `system/health/AppStopHistory.kt:57-63`, `UserStopNotice.kt` |
| Path | partial |
| Message (plain words) | "Your phone stopped CallVault." + the Q58–Q62 maker advice |
| Action (intent) | as Q58–Q62 |
| Test | R |
| Priority | P2 |

### Q66 — "Allow wireless debugging on this network?" repeats; recorder stays down
| | |
|---|---|
| Other apps | A11 (see section F) |
| Tracker ref | N15 |
| Who | Shizuku mode, any phone, new Wi-Fi access point after reboot |
| Symptom | "Allow wireless debugging on this network?" repeats; recorder stays down |
| Detect | **B**: Shizuku mode + Shizuku not running + Wi-Fi network changed since last good start |
| Current handling | none (auto-approve only in built-in mode) |
| Path | no |
| Message (plain words) | "Shizuku needs Wireless debugging on this new network. Tap 'Always allow' on Android's prompt." (prevention = N15 fix: approve while the recorder runs) |
| Action (intent) | Wireless debugging settings |
| Test | OP9 (seen 2026-11-19) |
| Priority | P0 |

### Q67 — Crash loop once set up (API-31 listener class)
| | |
|---|---|
| Tracker ref | N25 |
| Who | Android 11 (API 30) |
| Symptom | Crash loop once set up (API-31 listener class) |
| Detect | **I** (SDK 30) |
| Current handling | fixed at `7caf1ccb` (listener only on 12+, guard test) |
| Path | yes |
| Message (plain words) | none |
| Action (intent) | n/a |
| Test | E (A11 AVD) |
| Priority | P1 |

### Q68 — Every app call lost (vendor ctor crash + A17 removed mChannelCount)
| | |
|---|---|
| Tracker ref | N21, supersedes U8 |
| Who | vivo, Android 17 |
| Symptom | Every app call lost (vendor ctor crash + A17 removed `mChannelCount`) |
| Detect | **B**: exception shape |
| Current handling | 2.5.2 rebuild for A17 layout + ShellApplication retry (🧪, reporter OK on test build) |
| Path | yes |
| Message (plain words) | none (silent); if it still fails: "This app call could not be recorded on this phone." |
| Action (intent) | n/a |
| Test | R (vivo A17 reporter) |
| Priority | P1 |

### Q69 — Reportedly not captured
| | |
|---|---|
| Other apps | A1 (see section F) |
| Tracker ref | N24 |
| Who | vivo A17, wired headphones on app calls |
| Symptom | Reportedly not captured |
| Detect | **R** (cause unknown; report logs route now) |
| Current handling | none |
| Path | no |
| Message (plain words) | none yet |
| Action (intent) | n/a |
| Test | R |
| Priority | P2 |

### Q70 — Notifications or the recording notification channel blocked: every warning is silent
| | |
|---|---|
| Other apps | A16 (see section F) |
| Who | Android 13+ all; any phone with notifications off for CallVault, or with the foreground-service (recording), error or keep-alive notification channel blocked (importance NONE) |
| Symptom | Every warning in this design is silent |
| Detect | **D**: `NotificationManagerCompat.areNotificationsEnabled()`; channel `importance == NONE` (`system/health/NotificationChannelsReport.kt`) |
| Current handling | onboarding requires the permission (`OnboardingStatus.kt:82`, re-checked only when the app is opened); report lists channels |
| Path | partial |
| Message (plain words) | Home card: "Notifications for CallVault are off, so it can't warn you when a call isn't recorded." |
| Action (intent) | `Settings.ACTION_APP_NOTIFICATION_SETTINGS` / `ACTION_CHANNEL_NOTIFICATION_SETTINGS` |
| Test | E |
| Priority | P0 |

### Q71 — Phantom-process monitor and ~3.5 GB per-app RAM ceiling kill the recorder or app
| | |
|---|---|
| Who | HyperOS (📐 research) |
| Symptom | Phantom-process monitor and ~3.5 GB per-app RAM ceiling kill the recorder or app |
| Detect | **R** before; **B** only as "recorder died" counts |
| Current handling | none |
| Path | no |
| Message (plain words) | none (feeds the "recorder keeps dying" message of R7) |
| Action (intent) | n/a |
| Test | R |
| Priority | P2 |

### Q72 — Secure-setting writes need "USB debugging (Security settings)" (needs a Xiaomi a
| | |
|---|---|
| Who | Xiaomi HyperOS |
| Symptom | Secure-setting writes need "USB debugging (Security settings)" (needs a Xiaomi account) |
| Detect | **B**: a `Settings.Global` write throws `SecurityException`; **I** for wording |
| Current handling | none specific (issue #23 notes) |
| Path | no |
| Message (plain words) | "On Xiaomi phones, turn on Developer options ▸ 'USB debugging (Security settings)'." |
| Action (intent) | `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS` |
| Test | R |
| Priority | P1 |

### Q73 — Settings.putInt fails silently
| | |
|---|---|
| Who | Transsion (Infinix/Tecno) (📐) |
| Symptom | `Settings.putInt` fails silently |
| Detect | **B**: read back after write |
| Current handling | none |
| Path | no |
| Message (plain words) | none (log only) |
| Action (intent) | n/a |
| Test | R |
| Priority | P3 |

### Q74 — Shizuku does not survive a reboot; nothing records until started
| | |
|---|---|
| Other apps | A5 (see section F) |
| Who | Shizuku mode, all phones |
| Symptom | Shizuku does not survive a reboot; nothing records until started |
| Detect | **D**: `RecorderBackend.shizukuStatus()` |
| Current handling | yes: `Prerequisite.SHIZUKU` (`data/health/SetupPrerequisites.kt:45`), Home `SHIZUKU_NOT_READY`, `ShizukuLifecycleWatcher` |
| Path | yes |
| Message (plain words) | existing text |
| Action (intent) | Shizuku app |
| Test | OP9 |
| Priority | P0 |

### Q75 — Near-mic start times out (-110) on ring-time WhatsApp calls
| | |
|---|---|
| Other apps | A6 (see section F) |
| Who | Redmi Note 8 (KB "Solved #4") |
| Symptom | Near-mic start times out (-110) on ring-time WhatsApp calls |
| Detect | **B** (error code) |
| Current handling | see KB doc 478f6d61 (solved; handling not re-read here) |
| Path | yes (per KB) |
| Message (plain words) | none |
| Action (intent) | n/a |
| Test | R |
| Priority | P2 |

### Q76 — Bank apps refuse to run while debugging is on
| | |
|---|---|
| Other apps | A4 (see section F) |
| Tracker ref | #118 |
| Who | Any phone, bank apps |
| Symptom | Bank apps refuse to run while debugging is on |
| Detect | **R** (we cannot see other apps' checks) |
| Current handling | KB answer only |
| Path | n/a |
| Message (plain words) | FAQ / "Your phone" page text only; never switch debugging off for them |
| Action (intent) | n/a |
| Test | n/a |
| Priority | P3 |


## F. Other apps that conflict with CallVault (A1-A19)

📐 Written 2026-11-20 from notes, code, store reviews and public issue trackers; nothing here was run on a phone.
Every entry is 🧪 PROPOSAL: no message below is built yet. Owner rule: other apps get the same treatment as phone
quirks (detected on the phone, a specific path or message naming the app, nothing leaves the phone, the debug report
lists only the conflicting packages found, never the installed-app list). Detect codes as in section E. The package
lists live in `KnownConflictingApps.kt`; a package is matched by presence only (read-only `PackageManager` lookup).
Visibility: the manifest `<queries>` LAUNCHER intent already makes every app with a launcher icon visible on Android 11+,
so no `QUERY_ALL_PACKAGES` and no extra `<package>` entry is needed. A launcher-less helper would read as absent.
Package ids are public application ids; AVault ships the same id as ShizuCallRecorder, so one id names both.

| Id | Who | Symptom | Detect | Cross-ref | Priority |
|---|---|---|---|---|---|
| A1 | Another call recorder on the same phone call (Cube ACR, ACR Phone, SKVALEX, Talker ACR, callX, ACR q4u, BCR, ShizuCallRecorder/AVault, JemRec, OEM dialer auto-record) | one side silent, or both empty | **I** package list; **B** (shell) another uid capturing VOICE_CALL/UPLINK/DOWNLINK during our call | Q39, U7, Q69 | P0 |
| A2 | A second shell/Shizuku-hosted recorder (JemRec, ShizuCallRecorder/AVault, SKVALEX/ACR Phone/Cube in Shizuku mode) | app-call far or near side lost; recorders take each other's audio | **B** classify foreign `app_process` hosts; **I** package list | U7, Q37, Q41, Q42 | P0 |
| A3 | Task killers and freezers (AppZuku, Greenify, Hail, Ice Box, Thanox, Brevent, App Manager freeze, Phone Master) | force-stopped state: no call broadcast until CallVault is opened | **B** force-stop history (built, 🧪); **D** `isPackageSuspended`; **I** package list to name the tool | Q22, Q25, Q58-Q62, Q65 | P0 |
| A4 | Apps that switch debugging or Developer options off (JemRec "When JemRec is off", DevSwitch, Tasker/MacroDroid/Automate flows) and Samsung Auto Blocker | recorder down, calls lost silently | **B** who wrote the setting (settings history, to verify on a phone); **D** Auto Blocker; **I** package list | Q01, Q02, Q05, Q76 | P0 |
| A5 | Other ADB/Shizuku clients that restart adbd or move its port (Shizuku forks with TCP mode/watchdog, JemRec, App Manager auto-connect, LADB, aShellYou, Termux) | recorder dies with adbd; arm fails; tug-of-war | **B** foreign port, WD off after our restart (exists); adbd pid per tick (new); **I** package list | Q07, Q06, Q13, Q17, Q19, Q74 | P1 |
| A6 | Apps holding the microphone during a call (accessibility recorders, mic blockers, Live Caption, voice typing, AI call assistants) | our near side silenced, or contention | **B** other active capture configs at a silenced re-take; **D** enabled accessibility services | Q37, Q39, Q75 | P1 |
| A7 | CallVault silencing other apps (reverse of A6) | voice notes and camera video record silence | **B** idle mic-op check, other client seen silenced | Q46, Q41 | P1 |
| A8 | Apps that turn Wi-Fi Calling / IMS on behind the user's back (Pixel IMS and "enable VoLTE/VoWiFi" apps) on Samsung | calls move to Wi-Fi Calling and record silent | **I** package list; **D** Wi-Fi Calling state | Q30 | P1 |
| A9 | Apps keeping the phone in call mode without a call (WhatsApp after hang-up, voicemail apps, JemRec answering machine, voice-chat apps) | false app-call starts, overruns, stubs | **B** mode owner is a known non-call app, or WhatsApp with no voice capture | Q31, Q36, Q51 | P2 |
| A10 | Caller-ID, dialer and call-screening apps (Truecaller, Hiya, Google Phone/Voice, Should I Answer) | screening audio recorded or not, wrong names, phantom actions blamed on CallVault | **D** default dialer and role holders; **I** package list | Q35, Q69 | P2 |
| A11 | VPN, firewall and DNS apps (NetGuard, RethinkDNS, AdGuard, WireGuard, Tailscale, ShizuWall) | Wireless debugging not found, pairing fails | **D** `TRANSPORT_VPN`, always-on VPN setting; **I** package list | Q09, Q10, Q66 | P2 |
| A12 | Bluetooth, car and hearing apps (Android Auto, car apps, LE Audio companions) | one-sided or mono recordings | **B** audio route at start and on change | U6 | P2 |
| A13 | Cloned or second-profile copies (Dual Apps, Secure Folder, Work profile, Island, Parallel Space) of CallVault or WhatsApp | two hosts kill each other; clone calls not named | **D** `pm list users`, `pm list packages --user all` (shell) | Q35 | P2 |
| A14 | Accessibility services and overlay apps (Truecaller overlay, auto-clickers, Tasker/MacroDroid) | win the mic, tap our UI, launch things blamed on us | **D** enabled accessibility services, overlay app-op | A6 | P2 |
| A15 | Permission and app-op managers (App Ops, Permission Pilot, App Manager, Thanox) | `WRITE_SECURE_SETTINGS`, mic or notification access revoked | **D** a grant disappeared without an install-over; **I** package list | Q15, Q54 | P2 |
| A16 | Notification managers and listeners (BuzzKill, FilterBox) | call notification dismissed: caller name missing | **D** enabled notification listeners (short list) | Q35, Q36, Q70 | P3 |
| A17 | Cleaners, file managers, cloud sync (SD Maid, CCleaner, Files junk clean, Drive/OneDrive/Syncthing) | staging files or recordings deleted, moved or locked | **I** package list only | Q47 | P3 |
| A18 | scrcpy, screen recorders, audio-effect apps | a leftover scrcpy server reads as a second CallVault recorder | **B** classify `app_process` by class name | A2 | P3 |
| A19 | Call-redirection role holders (SKVALEX, Google Voice) | only if CallVault ever takes the role: it displaces them | **D** `cmd role get-role-holders` (shell) | none | P3 |

Evidence per entry (own reports, public issue trackers, store reviews) is in the research file the review kept; the
decisions that matter here: A1 and A2 cannot be settled by the user alone, so the path is a Home warning that names
the app, never killing another app's process. Messages are proposals and are written when the Home-card phase starts.
Runtime probes (A6, A7, A9, A12, A13, A18) and role/setting reads (A4 Auto Blocker, A8 state, A10, A11, A14, A16, A19)
are not part of the package-presence groundwork.
