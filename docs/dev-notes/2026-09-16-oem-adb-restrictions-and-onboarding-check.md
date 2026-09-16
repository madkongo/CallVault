# The OEM "shell can't grant" gate — what it is, who has it, and what an onboarding check should do

Status: 📐 INVESTIGATION + 🧪 MEASURED. **Built 2026-09-16** (commit 17d9890, branch
`fix/adb-open-lock-wedge`) — see §7 at the end; 🧪 until the maintainer sees it on a device. Device measurements are ours (OP9 daabf34f,
OxygenOS V14.0.0, and OP12 6011b07e, OxygenOS V16.1.0). Everything else is sourced research; each claim
says how strong it is.

Trigger: the maintainer asked for an onboarding check for ColorOS/OxygenOS's **"Disable system
optimization"** (older name: **"Disable permission monitoring"**), which nothing in the app checks today.

---

## 1. What we proved on our own phones (2026-09-16)

**Polarity — settled by flipping the toggle on the OP9 and re-reading (was 📐 inferred until today):**

| UI toggle | `persist.sys.permission.enable` | Meaning |
|---|---|---|
| ON ("Disable permission monitoring" enabled — what CallVault needs) | `false` | monitoring off, shell may grant |
| OFF (shipped default) | `true` | monitoring on, shell is blocked |

**What the gate actually blocks** (OP9, toggle OFF, i.e. monitoring active — measured):

| Operation as shell (uid 2000) | Result |
|---|---|
| `pm grant … WRITE_SECURE_SETTINGS` | ❌ `SecurityException: … GRANT_RUNTIME_PERMISSIONS` |
| `pm revoke …` | ❌ `SecurityException: … REVOKE_RUNTIME_PERMISSIONS` |
| `appops set … RECORD_AUDIO allow` | ❌ `SecurityException: uid 2000 does not have MANAGE_APP_OPS_MODES` |
| `settings put global …` | ❌ `SecurityException: must have … WRITE_SECURE_SETTINGS` |

**What still works with monitoring active** (same session, measured):

- An **existing** grant stays granted (`WRITE_SECURE_SETTINGS: granted=true` throughout).
- The **app** (its own uid, holding the permission) still writes secure settings: killing the daemon made
  the keep-alive switch Wireless debugging on, relaunch, and switch it off again — recovered in ~19 s.
- An **install-over kept the grant** (`grant survived=true`), so no re-grant was needed and the recorder
  came back normally.
- An already-running shell-uid daemon keeps running, and Shizuku's own server starts fine.
- `dumpsys package com.android.shell` still lists `GRANT_RUNTIME_PERMISSIONS: granted=true` — ColorOS
  intercepts the **check** for uid 2000; it does not revoke the shell package's permissions.

**So the gate is grant-time only.** A user who completed setup once keeps recording. The failure appears
at first setup, or any time the grant has to be made again (fresh install; `reinstall-drops-write-secure-settings`).

**Readability:** an ordinary app can read the property — `run-as <app> getprop persist.sys.permission.enable`
returned the value on the OP12 (label `u:object_r:system_prop:s0`). Absent on the AOSP emulator.
Not settable by shell (uid 1000 writes it at boot).

## 2. Is it required — the three questions asked

**a. For CallVault.** Built-in mode: **yes, to set up** (the grant is exactly what is blocked), **no, to keep
running**. Shizuku mode: **yes** — see (b); without it Shizuku never shows its permission prompt, so
CallVault can never be authorised. In both modes the block is at authorisation, not at recording.

**b. For Shizuku alone, with CallVault out of the picture.** Shizuku **starts** fine; what it cannot do is
show its permission dialog: `RequestPermissionActivity` pre-flights
`Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS")` and, when that fails, shows
"The permission of adb is limited" and finishes. Shizuku's own setup doc names ColorOS ("Disable
'Permission monitoring'"), MIUI ("USB debugging (Security options)") and Flyme ("Disable Flyme payment
protection") — but was last edited 2023-06-21, so it does not know the ColorOS 15/16 rename. Two
independent user reports (Shizuku #374, #2149) say the toggle is now "Disable system optimization" and is
only visible with the phone's language set to English. RikkaW has never commented on the rename.

**c. What other projects say.**
- **ShizuCallRecorder (our upstream)**: tells users in issues to make sure "Disable permission monitoring
  or Disable System Optimizations" is enabled (#117, #41). Its own troubleshooting doc names no OEM and
  says "search engines are your friend". Reports get labelled `Type: OEM Limitation`.
- **BCR**: nothing — and correctly: it installs as a **system app** via Magisk/recovery, never issues a
  shell grant, so the gate cannot touch it. An architectural difference, not an oversight.
- **Ever-Call-Recorder**: nothing about OEM gates; its advice is to use the thedjchi Shizuku fork with a
  watchdog because stock Shizuku's service stops.
- **ShizuTools, PhoneProfilesPlus, scrcpy, AppManager, Tweaker, no_more_background**: all treat it as a
  support string. PhoneProfilesPlus has the clearest per-OEM line ("Xiaomi: USB debugging (Security
  settings). Oppo, OnePlus: Disable permission monitoring … Without this grant not working").
- **Nobody publishes a programmatic check**; AppManager's maintainer asked publicly for the backing key
  (#1829) and got no answer. Our property finding appears to be new.
- ⚠️ `alekseykidisyuk/CallMonitor-Android` is a **fork of this repo** — its README is ours, not evidence.

## 3. The same restriction on other manufacturers

Only three families gate shell privilege this way: **OPPO/OnePlus/Realme**, **Xiaomi**, **Meizu**.

| Family | Gate | Name | Detectable by | Notes |
|---|---|---|---|---|
| OPPO / OnePlus / Realme (ColorOS 3→16, OxygenOS ≥11, realmeUI 2→7) | yes | *Disable permission monitoring* → *Disable system optimization* / 禁用系统优化, Developer options ▸ **Apps**, last item, off by default | `persist.sys.permission.enable` (`true` = blocked) | Present in 26 of a 171-device dump corpus, OPPO-family only; pre-merger OxygenOS 9 has neither. No reboot needed after flipping (user report, OxygenOS 16). |
| Xiaomi / Redmi / POCO (MIUI 10 → HyperOS) | yes | *USB debugging (Security settings)* + *Install via USB* + *MIUI/System optimization* | `persist.security.adbinput` (`1` = permissive — **inverted** vs OPPO) | Blocks a **named list** of permissions for uid 2000 (includes GRANT_RUNTIME_PERMISSIONS, WRITE_SECURE_SETTINGS, UPDATE_APP_OPS_STATS, INJECT_EVENTS). Enabling it needs a Mi account and a server call to `srv.sec*.miui.com`; refusal text comes from Xiaomi's server (so the "wait 7/10 days" folklore is not a rule). Flipping the property restarts adbd. HyperOS 3 hides the optimization switch behind tapping "Reset to default values" repeatedly. |
| Meizu (Flyme) | yes | *Flyme payment protection* (must be off) | unknown | Same class, unexamined. |
| vivo / iQOO (Funtouch, OriginOS) | partial | 「USB模拟点击」 "USB simulated click" | `Settings.Secure` `vivo_adb_simulate_input` (1 = open) | Intercepts only **WRITE_SECURE_SETTINGS + INJECT_EVENTS** for uid 2000, so `pm grant` works but `settings put` from shell does not. **Also silently reverts some app-ops** on OriginOS 6 (our upstream's #41: `appops set` reports success, reads back `default`). Workaround used there: `cmd role add-role-holder android.app.role.COMPANION_DEVICE_WATCH <pkg>`. |
| Huawei / Honor (EMUI, HarmonyOS ≤4, MagicOS) | no gate | — | — | **Huawei EMUI/HarmonyOS has no Wireless debugging at all** (Huawei's own support page lists every version) → built-in mode is impossible; Honor MagicOS is normal Android and is fine. Also: on Huawei/Honor the "charge only" USB mode makes the system switch `adb_enabled` **off** — our "No data transfer" advice is inverted there. Shell may be denied `/sdcard/Android/...`. |
| Samsung One UI | no | — | — | No gate in the corpus, none in any guide, and our own Samsung field tester (#25–#28) never hit a refused grant. Samsung's problems are transport-shaped. |
| Transsion (Tecno/Infinix/itel) | none found (Android 13 source read) | — | `ro.tranos.type` identifies them | Their hazards: aggressive background killers, a framework injection that can kill a shell-uid `app_process` silently (Shizuku #2048), `Settings.putInt` from an app can return false silently, and "Allow restricted settings" is missing from App info. |
| Motorola, Nothing, Sony, ASUS, Pixel/AOSP | no | — | — | Property absent on our emulator (measured). |
| LineageOS / custom ROMs | opposite | *Rooted debugging* | — | An escalation, not a restriction. |

## 3b. The ColorOS mechanism, now source-proven

A ColorOS Android 10 framework decompile (`dstmath/OppoFramework`,
`com/android/server/am/OppoShellPermissionUtils.java`) contains the guard verbatim: a list of permissions
that are revoked when `uid == 2000` and `SystemProperties.getBoolean("persist.sys.permission.enable", true)`.
That confirms both the mechanism and the polarity (default `true` = revoking), and matches our measurements.

The full list is **eleven** permissions — the four we measured plus `WRITE_SETTINGS`, `SEND_SMS`,
`ADJUST_RUNTIME_PERMISSIONS_POLICY`, `UPDATE_APP_OPS_STATS`, `KILL_BACKGROUND_PROCESSES`,
`CLEAR_APP_USER_DATA` and `oppo.permission.OPPO_COMPONENT_SAFE`. So `am force-stop` and `pm clear` are
blocked too. **We use neither** — `RecorderServerLauncher.kt:81` kills stale daemons with `pgrep` + `kill`
inside our own shell uid, which the gate cannot touch. Worth knowing so a future change does not reach for
`am force-stop` and get mysteriously refused.

Two caveats: the list is **replaceable at runtime**, so OPPO can widen it in an update (another reason to
probe capabilities instead of hard-coding which operations should fail), and alpha/"forum" ROM builds skip
the guard entirely.

## 3c. Samsung: no permission gate, but a possible transport kill switch

Samsung's One UI `AdbService` watches `Settings.Secure` `rampart_blocked_adb_cmd`; when it reads 1 it forces
**both** `adb_enabled` and `adb_wifi_enabled` to 0 and logs
`AdbService: onChange : ADB is blocked by Auto Blocker`. It is an observer, so **any write we make to
`adb_wifi_enabled` is reverted immediately** — which on a Samsung would look exactly like the
Wireless-debugging flapping in #23/#24/#39, and would make our re-arm recovery loop forever. Auto Blocker
(Settings ▸ Security and privacy) is on by default from One UI 6.1.1.

⚠️ Unestablished: whether the ordinary consumer Auto Blocker switch ever sets that key (it may be
Knox-Guard-only). One `settings get secure rampart_blocked_adb_cmd`, with Auto Blocker on and off, from our
Samsung field tester (#25–#28) settles it. **Highest-value experiment here**, because it could already be
causing reports we have misattributed to our own transport code.

Also Samsung-specific: the One UI equivalent of the "No data transfer" trick is Default USB Configuration ▸
**"Debugging only"**, and that option only appears after toggling USB debugging off and on again.

## 4. What this means for the check (design, not built)

1. **Don't build an OPPO-only check.** The OPPO property doesn't exist elsewhere, and Xiaomi's equivalent
   has the opposite polarity. The portable test is to **ask what the shell can actually do** — our daemon
   *is* the shell, so it can check whether uid 2000 holds `GRANT_RUNTIME_PERMISSIONS`,
   `MANAGE_APP_OPS_MODES` and `WRITE_SECURE_SETTINGS` (or simply attempt the grant and read it back).
2. **Use the OEM property only to choose the wording** (`ro.build.version.oplusrom` / `ro.build.version.opporom`
   → ColorOS text; `ro.mi.os.version.name` / `ro.miui.ui.version.name` → Xiaomi text).
3. **Always verify a grant by reading it back.** A Xiaomi/HyperOS 3 report has `pm grant` succeeding while
   granting nothing. Same rule as `drive-health-false-positive`: a claim about the present needs evidence
   about the present.
4. **Onboarding step** (maintainer's decisions, 2026-09-16): shown when the check says the shell is blocked;
   explains the toggle, gives both names, mentions the English-language trick, opens Developer options, and
   **lets the user continue anyway**.
5. **Warn when it's off** — but only when it actually costs something. A user already granted keeps
   recording, so the warning belongs where the grant is missing or a re-grant failed, not as a standing nag.
6. **Wizard can't be re-run**, so the same check belongs in Settings/Home health, and in the debug-report
   header (`writeConfiguration`), where it would answer "the grant won't take" reports in one line.
7. **Re-assert app-ops on boot for Xiaomi** (Security Center reverts them) and **never trust an app-op
   exit code on vivo** — read it back. `PrivilegedGrants.grantAppOp` already reads back, which is why it
   would report honestly there.

## 4b. The probe to use (tested 2026-09-16)

- `cmd package check-permission` **does not exist** on any of our three targets (OP9, OP12, emulator) — so a
  "does the shell hold the permission?" query is not available from the shell.
- `dumpsys package com.android.shell` is **not a detector**: it still prints `GRANT_RUNTIME_PERMISSIONS:
  granted=true` while the gate is blocking, because ColorOS intercepts the check, not the grant record.
- **What works, and is OEM-agnostic:** re-grant a permission the app already holds and look at the outcome —
  `pm grant <our pkg> android.permission.WRITE_SECURE_SETTINGS`. Blocked → `SecurityException`; allowed →
  silent success, and nothing changes because the permission was already held. Verified silent-success on the
  OP9, OP12 and emulator with the gate open, and the SecurityException on the OP9 with it closed.
  At first-time setup the real grant *is* the probe — do it, then read it back with
  `dumpsys package <pkg> | grep WRITE_SECURE_SETTINGS` (the Xiaomi lesson: success can be a lie).

## 5. Open questions / experiments worth doing

- ~~"Hidden unless the system language is English"~~ — **REFUTED for this version, 2026-09-16**: the
  maintainer switched the OP12 (OxygenOS V16.1.0) to Hebrew and the setting was still visible in Developer
  options. So the claim is at most build- or language-specific, not general. Onboarding copy should still
  mention the English trick as a fallback ("if you cannot find it, switch the phone to English"), but must
  not state it as a requirement.
- ~~Does the gate also block `pm install` from shell?~~ **ANSWERED 2026-09-16 (OP9, gate closed): no.**
  `pm install -r` from the shell returned `Success`. Our in-app updater is unaffected on OPPO. The
  install-over also kept the grant (`grant survived=true`) and the recorder relaunched normally, so even
  updating while blocked is safe.
- ~~Does an existing grant survive a **reboot** with monitoring active?~~ **ANSWERED 2026-09-16: yes.** OP9
  rebooted with `persist.sys.permission.enable=true`; `WRITE_SECURE_SETTINGS: granted=true` afterwards. The
  "set up once and it keeps working" story holds even across reboots in the blocked state.
- Detector dry-run in the blocked state: the re-grant probe raised the SecurityException as expected
  (§4b), so the probe is confirmed in both directions on the same device.
- Does `SystemProperties.get` return the value from a **release** (non-debuggable) build, not just `run-as`?
- Dry-run the OEM-agnostic permission check on the OP9 in both toggle states, and on the emulator as a control.
- Whether vivo also reverts `pm grant`, and whether its i管家 blocks a permission at use time.
- Samsung `rampart_blocked_adb_cmd` under the plain Auto Blocker switch (see 3c) — ⏸️ PARKED 2026-09-16: no
  Samsung device or tester available. Ask when one is.

## 5b. Do not propose (checked, no evidence)

- `settings put secure adb_install_grant_all_permission 1` and
  `settings put global direct_control_permission_monitoring 0` — XDA folklore; a GitHub-wide search for the
  second returned one hit, an AI transcript. No corroboration in real code.
- AOSP hibernation / auto-revoke exemptions (`setAutoRevokeWhitelisted`): only the installing app may call
  it, and hibernation never touches `WRITE_SECURE_SETTINGS`. Not our failure mode.
- GrapheneOS's "broken ROM" label in Shizuku's bug template is an unexplained assertion; its shell manifest
  is identical to AOSP. LineageOS, CalyxOS and /e/OS have the full AOSP shell permission set.

## 6. Corrections to our own notes

- `coloros-toggle-renamed-and-hidden.md` said `settings put global` "works as shell" — that was measured on
  2026-08-30 with the toggle **ON**. With monitoring active it fails. Annotated in memory.
- `screen-off-adbd-kill-and-mitigations.md`'s "set Default USB Config to No data transfer" must be scoped
  **away from Huawei/Honor**, where that setting switches USB debugging off.
- We have **no detection code today**: nothing in `app/src/main` mentions the property or those permissions;
  the knowledge lives only in `README.md` and `docs/dev-notes/2026-08-24-shizuku-support-plan.md`.


## 7. What was built (🧪 VERIFYING, 2026-09-16, commit 17d9890)

- `integrations/adb/ShellGrantGate.kt` — pure decisions: read the OPPO property (`true` = blocked,
  `false` = allowed, absent = unknown); read a real grant attempt (held afterwards = allowed; the OEM's
  SecurityException = blocked; **silent failure = unknown**, never blamed on a switch); pick whose wording
  to show; and `shouldAdvise`, which stays quiet when the permission is already held. 13 tests.
- `AdbShell.grantSecureSettingsIfNeeded` now **reads** the command's output instead of draining it, reads
  the permission back, records the outcome in preferences and logs which of the three happened.
  `AdbShell.shellGrantState()` / `oemGate()` expose it (property first, remembered attempt second).
- `ui/common/OemGateNotice.kt` — a self-hiding warning with the OEM's own switch name, a button to
  Developer options, and a line saying setup can continue. Shown inside the ADB card in onboarding
  (`PermissionsScreen`) and under Privileged mode in Settings (the wizard cannot be re-run).
- Debug report header gains `Shell grants (OEM gate): <state> (<oem>)`.
- Strings in 11 locales. Full suite 1352/0.

Verified on the emulator by faking the blocked state (remembered state = BLOCKED, permission absent): the
notice appears in onboarding with the general wording and the button, and disappears when the state is
flipped back to ALLOWED. The OPPO and Xiaomi wordings have **not** been seen on a real blocked phone.

Not done: the Home screen says nothing about this (a blocked phone that is already granted keeps working,
and an ungranted one already shows its own "cannot record" state); vivo and Meizu get the general wording
because their exact switch names are unverified.
