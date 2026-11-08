# Can the ADB bootstrap avoid Wi-Fi debugging entirely? — research 2026-10-23

**Status: 📐 CALCULATED / code-derived. NOT device-verified.** The central verdict (below) is read
line-by-line from AOSP source and matches the maintainer's prior OP9/OP12/emulator measurements, but
nothing here was re-run on a device in this session. Do not promote to VERIFIED without a device check
on the specific OEM firmware.

**Question asked:** find an innovative way to gain the non-root recording ability **without using Wi-Fi
debugging at all** — over USB debugging, by faking/spoofing a connection, or something entirely else.
**Added constraint (from the maintainer, mid-session): connecting the phone to a PC is NOT an acceptable
solution.** Priority = zero Wi-Fi ever; acceptable fallback = Wi-Fi once at first setup, on-device, no PC.

Six parallel research passes (official AOSP docs, GitHub, Reddit/XDA, USB/OTG, Shizuku/binder, novel
vectors) plus a focused AOSP-source deep-dive on the reset mechanism.

---

## THE VERDICT (read this first)

**Zero-Wi-Fi arming of the privileged ADB channel is impossible on stock non-root Android.** The one
arming door that does not need an external USB host (Wireless Debugging) is **Wi-Fi-AP-identity-keyed at
the framework level**, by design, unchanged Android 11 → current.

The hard invariant, stated so it stops being re-litigated:

> On stock non-root Android, `adbd` hands out a privileged shell only through **(a)** an external USB
> *host* that speaks adb (a PC, or a second phone/dongle acting as host), or **(b)** the Wireless
> Debugging framework, which requires association to a **trusted Wi-Fi BSSID**. `adb tcpip` needs you to
> already be through (a) or (b). The only reboot-surviving switch (`persist.adb.*`) is root-only. There
> is no third channel.

## Why VPN-tun and USB-Ethernet both fail (the crux, code-cited)

The maintainer measured that `adb_wifi_enabled` resets to 0 off Wi-Fi. The cause is in
`frameworks/base/services/core/java/com/android/server/adb/AdbDebuggingManager.java`, the
`MSG_ADBDWIFI_ENABLE` handler (`:1017-1051`):

```
AdbConnectionInfo currentInfo = getCurrentWifiApInfo();        // :1022
if (currentInfo == null) {
    Settings.Global.putInt(..., ADB_WIFI_ENABLED, 0);          // :1024-1025  → bails before starting
    break;
}
if (!verifyWifiNetwork(currentInfo.getBSSID(), ...)) {         // :1029
    Settings.Global.putInt(..., ADB_WIFI_ENABLED, 0);          // :1034-1035
    break;
}
```

- `getCurrentWifiApInfo()` (`:1315-1350`) reads `WifiManager.getConnectionInfo()` and returns **null**
  if there is no connected Wi-Fi AP (networkId == -1 or empty BSSID). Off Wi-Fi → null → reset to 0
  **before** the TLS listener thread starts.
- The gate uses **no** `ConnectivityManager.registerNetworkCallback` / `NetworkRequest` /
  `addTransportType` anywhere (grep over main/12/13: zero hits). It watches only `WifiManager`
  broadcasts and filters `type == TYPE_WIFI` (`:625`). **A VPN tun (`TRANSPORT_VPN`) or USB-Ethernet
  (`TRANSPORT_ETHERNET`) interface is invisible to it** — it neither triggers the receiver nor satisfies
  `getCurrentWifiApInfo()`.
- The trust store is **strictly BSSID-keyed** (`AdbKeyStore.mTrustedNetworks: List<String>` of BSSIDs,
  persisted under XML `"wifiAP"`/`bssid`, `:1783-1795`, `:2054-2057`). There is **no representation** for
  a VPN or Ethernet interface in the trust model, and `MSG_ADBDWIFI_ENABLE` will not start the thread
  unless `verifyWifiNetwork` passes (`:1029-1037`) — trust gates enabling, not just auto-connect.

**Therefore:** writing `adb_wifi_enabled=1` (via WRITE_SECURE_SETTINGS) while a VPN tun or USB-Ethernet
interface is up, Wi-Fi off, is **reset to 0 on the same message cycle**. A re-write watcher fails too —
every write is gated back instantly. Recommend **NOT building** a VpnService/Ethernet arming path.

Confirmed present and identical in `android12-release`, `android13-release`, and `main`. The maintainer's
Android-17 "ADB Wi-Fi 2.0 toggles off when not on a trusted network" note is this **same** BSSID
machinery (introduced Android 11), not a separate new subsystem — no distinct code location found on main.

## Other vectors, and why each is out

| Vector | Avoids Wi-Fi? | Needs PC? | Reaches call audio? | Verdict |
|---|---|---|---|---|
| USB-C self-loopback (phone hosts its own adbd) | — | — | — | **Physically impossible** — one USB-C data role per port; adbd is always the gadget/peripheral end |
| App as adb host over USB to a **second phone** (`ADB-OTG`, Termux-ADB) | **Yes** | No (needs 2nd Android) | Yes (arms, then loopback) | **Works** but needs a second device as host — the only true zero-Wi-Fi/zero-PC arming |
| Qualcomm EUD (Embedded USB Debugger) | Yes | Yes (PC-side OpenOCD) | No | Dead — JTAG/SWD, not an adbd transport, root-gated sysfs |
| adbd local unix/abstract socket without a transport | Yes | — | No | Dead — no unauthenticated on-device adbd command endpoint exists |
| VpnService tun satisfies WD "network" gate | Yes | No | — | **Dead** — gate is Wi-Fi-AP-keyed (above) |
| USB-Ethernet/RNDIS satisfies WD gate | Yes | No | — | **Dead** — same gate |
| Phone's own hotspot / Wi-Fi Direct / Wi-Fi Aware | No (uses radio / wrong role) | No | — | Dead — WD needs client-on-AP; hotspot tried by hand, refused |
| Bluetooth / NFC as "trusted network" | Yes | No | — | Dead — no IP transport WD will bind/trust |
| Device Owner / Dhizuku (`dpm set-device-owner`) | Yes (after set) | Once, or QR/NFC on reset | **No** | Dead for audio — runs uid ≥10000; `CAPTURE_AUDIO_OUTPUT` is signature\|privileged; `setPermissionGrantState` only touches runtime perms |
| `setprop persist.adb.*` from shell | Yes | No | — | Dead — SELinux-denied to uid 2000 (root only) |
| CompanionDeviceManager / Quick-Settings tile | Yes | No | No | Dead — grant only background/foreground-service perms, no audio, no adb arming |

## Reddit/XDA corroboration (own harvest, 89k posts, 6 subs)

- *"You need to keep USB Debugging enabled to maintain Shizuku running once you disconnect from WiFi and
  wifi debugging gets automatically disabled"* — r/tasker `1m510i6`. Matches [[debugging-switches-model]].
- *"Termux → `adb connect 127.0.0.1:random_port` → `adb tcpip 5555` → Disable WiFi ADB. Voilà."* —
  r/tasker `1moo1ix`. The community's loopback bootstrap = our shipped path.
- *"I set `adb_wifi_enabled = 0`, see it's off in Developer Options, and ADB WiFi continues working.
  Why? I dunno. But it does."* — same thread. Confirms the **listener persists within a boot** once up,
  even as the setting reads 0; reboot is the wall.
- `thedjchi/Shizuku` fork auto-starts Shizuku **and** `adb tcpip 5555` on boot — a reboot-persistence
  pattern (still needs the first Wi-Fi arm).
- **Strategic risk:** *"Android May Soon Restrict On-Device ADB, Affecting Shizuku, libadb"* — r/fossdroid
  `1v5jtr6` (137 up), mirror r/degoogle `1v5m1rn`. A Google Issue Tracker discussion could restrict the
  exact on-device-ADB mechanism this app depends on. Worth tracking.

## Prior art for building blocks (if the fallback is pursued)

- In-app adb clients over loopback TCP: `mobile-dev-inc/dadb`, `flyfishxu/Kadb`, `cgutman/AdbLib`.
- Self-pair + self-grant + port discovery: `mouldybread/adb-auto-enable` (mDNS **or** a loopback
  socket-sweep of 32768–60999 — the sweep removes the multicast/Wi-Fi need for *discovery* specifically,
  worth folding in regardless).

## Second deep pass (2026-10-23 pm): cracked-tested the gate + non-ADB privilege + the ingest reframe

After the maintainer ruled out PC **and** a second phone and asked for genuinely innovative thinking,
two more focused passes:

**(i) Can `getCurrentWifiApInfo()` be forced non-null without an external AP? — NO, architecturally.**
`getConnectionInfo()` reports only the primary STA (`wlan0`) association. Wi-Fi Direct, LocalOnlyHotspot
and Wi-Fi Aware all live on *separate* interfaces it never reads; dual-STA's four sanctioned modes only
connect to *external* networks; no non-root API lets a phone's STA associate to an AP the same phone
hosts; no stale-cache window; no mock API below signature/`NETWORK_SETTINGS`. Still the deprecated
`getConnectionInfo()` on `main` (not migrated to `getTransportInfo()`), same null-off-Wi-Fi behavior.
All 8 candidate paths dead. (The "plain `adb tcpip` has no Wi-Fi gate" observation is **not** a bypass:
issuing `adb tcpip` needs an existing adb session, and setting `service.adb.tcp.port` needs shell/root —
you still reach that first session only via WD/Wi-Fi or a USB host. It is the loopback path we already
ship.)

**(ii) Non-ADB privileged execution — NONE.** `CAPTURE_AUDIO_OUTPUT` is `signature|privileged` (never
runtime-grantable), and every exec primitive (`app_process`, `am instrument`, test harnesses) runs with
the **invoker's uid** — a uid-10xxx app can only fork inside its own sandbox. The only on-device spawner
of a shell-uid (2000) process on a non-root phone is **adbd**. This is why Shizuku needs ADB. Confirmed
dead: default-dialer/`InCallService` (`CONTROL_INCALL_EXPERIENCE` is signature|privileged; grants call
*control*, not PCM), DevicePolicyManager (runtime perms only), accessibility, MediaProjection for
cellular (voice path is a HAL stream, not a capturable app AudioTrack; `CAPTURE_VOICE_COMMUNICATION_OUTPUT`
is privileged), carrier-privileges/IMS, telecom metadata APIs.

## ★ The one innovative zero-everything escape: INGEST MODE (don't capture — read the OS's own recordings)

On ROMs that already record calls to **shared storage**, skip privileged capture entirely:
- **Android 12 (API 31)** added the `Recordings/` MediaStore collection + `MediaStore.Audio.IS_RECORDING`.
  Any app with **`READ_MEDIA_AUDIO`** can query `IS_RECORDING=1` / `RELATIVE_PATH LIKE 'Recordings/%'`,
  read/copy the files, and watch via a `ContentObserver`. **Zero ADB, zero Wi-Fi, zero root.**
- **GrapheneOS** — Dialer writes to `Recordings/Call Recordings` → fully ingestable (strong case; overlaps
  our privacy audience). Manual record per call (no auto-record built in).
- **Samsung One UI** (region/CSC-gated) → `Recordings/Voice Recorder`; **older Xiaomi MIUI** →
  `MIUI/sound_recorder/call_rec`. Partial, device-check needed.
- **Reuses the existing transcription engine** — it is an *additive* mode, not a new pipeline.
- **Product shape:** startup probe of MediaStore; if readable native recordings exist → auto-offer
  "ingest mode" (no capture/ADB/Wi-Fi); else fall back to the ADB capture path.

**Honest limits — NOT universal, and NOT our own device:** dead for stock Pixel (Google Phone keeps
recordings app-private), **OnePlus/Oppo via Google Phone (so the maintainer's OP12 is NOT covered)**, any
Google-Phone-backed recorder, and all Android ≤11. No per-channel separation (mono → no stereo
diarization). OEM/GOS recorders may play an audible tone we cannot suppress. Confirming needs a
GrapheneOS device (we have none right now: OP12 = OnePlus, OP9 = rooted/ColorOS).

No named purpose-built "reads GrapheneOS recordings + transcribes" app was confirmed to exist; closest is
`org.scrib.transcriber` (Scrib, F-Droid, on-device Whisper, file-picker only — does not auto-watch the
folder). That gap is exactly what an ingest mode would fill.

## Third pass (2026-10-23, reframed): "start adb phone-alone, no Wi-Fi, no PC, no 2nd device, no root"

Maintainer sharpened the question to pure ADB startup (capture aside) and asked for a repo sweep. Two
agents: an exhaustive repo sweep + an AOSP inbound-channel deep-dive. **Verdict: NO on stock retail.**

**Repo sweep — nobody has done it.** The entire field converges on *automate Wireless Debugging pairing
on-device* (still Wi-Fi). Closest art: `xswl369/android-wireless-debug` (screen-OCR reads the pairing
code, pure-Java SPAKE2, auto-pairs, no PC/root — but needs Wi-Fi; reusable), `pabloajoinglez/AdbShellTool`,
`joaomgcd/ADBCommandCenter`, LADB. Zero-Wi-Fi only via a **2nd device/dongle USB host** (`KhunHtetzNaing/
ADB-OTG` = 2nd phone; `wolfxalpha4/esp32-adb-client` = ~$5 ESP32-S3 OTG host doing SPAKE2 — external, so
out). Clean no-Wi-Fi switch exists only with **root** (`sryze/wirebug` writes `service.adb.tcp.port`).
Client libs that connect-but-don't-bootstrap: `dadb`, `Kadb`, `cgutman/AdbLib`, `MuntashirAkon/libadb-android`.

**AOSP inbound-channel deep-dive — every door locked:**
- adbd's only local socket `/dev/socket/adbd` is `seqpacket 660 system system` + SELinux `adbd_socket`
  → blocked to apps by DAC (needs uid 0 / gid system) AND SELinux; and it speaks only framework opcodes
  (key-auth, TLS enable/port) — **no "run shell" verb**. `@jdwp-control` is the app-debug side channel,
  not an adb inbound transport.
- `service.adb.tcp.port` is `adbd_config_prop`; `property.te` `neverallow` permits only init/vendor_init/
  adbd/adbd_tradeinmode/system_server to set it — **shell and all apps blocked** (why `wirebug` needs root).
  `persist.adb.wifi.*`, `persist.adb.tls_server.enable`, `ctl.*$adbd` all non-app-writable.
- `MANAGE_DEBUGGING` (the `IAdbManager`/`AdbService` binder gate) is signature-level; CDM/RoleManager grant
  nothing here. `system_server` could set the port but exposes no app-reachable API that arms a non-Wi-Fi
  listener — only the Wi-Fi-gated WD path. **Chicken-and-egg unbroken.**
- Android 15/16/17 added no PC-free/Wi-Fi-free local path; A17 "adb Wi-Fi 2.0" is still Wi-Fi.

**The two device-specific slivers (NEEDS-DEVICE-TEST, free to probe):** `daemon/main.cpp` auto-opens
`tcp:5555`+`vsock:5555` **iff** no port property is set AND USB functionfs is inactive (the emulator case;
a phone's functionfs is always up when adbd runs, so it doesn't fire — and an app can't induce it). BUT a
minority of **OEM/IoT/TV-derived or eng/userdebug builds bake `persist.adb.tcp.port`/
`service.adb.listen_addrs`** or drop `ro.adb.secure` → adbd already listening on loopback with no Wi-Fi.
Worth a **free startup probe**: `getprop persist.adb.tcp.port / service.adb.tcp.port /
service.adb.listen_addrs`, and try `connect()` 127.0.0.1:5555 with Wi-Fi off. Most phones: nothing. Some:
a free foothold. (A15+ trade-in mode `persist.adb.tradeinmode` is a new restricted-shell adbd actor but
only reachable in the pre-provision setup-wizard flow, not by an installed app.)

**🚨 Strategic threat (two independent signals):** an AOSP issue-tracker proposal to **restrict on-device
adb to `wlan0`** would break the loopback access Shizuku/LADB/CallVault depend on; plus CVE-2026-0073
(network attacker skipping WD pairing) pushes Google to tighten. The mechanism the app relies on is under
active narrowing. See also the r/fossdroid "Android May Soon Restrict On-Device ADB" thread.

## What is actually left

1. **Zero-Wi-Fi, universal capture (priority): does not exist.** Both-sides cellular capture needs a
   shell-uid process; the only non-root spawner is adbd; adbd's only non-Wi-Fi arming is an external USB
   host (PC or a 2nd phone/dongle), all ruled out. This is a framework-design wall, exhaustively mapped.
2. **Zero-Wi-Fi WHERE THE ROM RECORDS: ingest mode** (above) — the one genuinely new, constraint-fitting
   path. GrapheneOS-first; device-limited; reuses our transcription.
3. **Fallback (Wi-Fi once at setup, no PC):** ≈ what we already ship. Pair once on-device over Wi-Fi →
   loopback listener; keep USB debugging on to hold adbd within a boot. The only unrecoverable gap is
   *reboot while off-Wi-Fi and never returning to Wi-Fi* — root-bound, already recorded unsolvable in
   [[loopback-tcpip-offwifi]]. The honest improvement there is **loud, not silent**: a post-boot
   notification when offline recording is armed but the listener is down.

Related: [[debugging-switches-model]], [[loopback-tcpip-offwifi]], [[capture-research-directions]],
[[shizuku-scr-vs-callvault]], [[android-17-call-recording-research]].

---

## 2026-11-05 — the "spam the setting" race (Shizuku-Next "Start without Wi-Fi"), measured on the OP9

**Why this came back:** issue #71 (chaoscreater, Samsung Galaxy Z Fold 7, Android 16) uses Shizuku-Next's
experimental "Start without Wi-Fi". This is a different method from the one-write test above, so the earlier
"race window does not exist" result did not cover it.

**The method** (`rushiranpise/Shizuku-Next` `manager/.../start/ForcedWirelessDebugging.kt`, read 2026-11-05):
- Bring up a local-only hotspot, which gives mDNS an interface to find the port on.
- Write `adb_wifi_enabled=1` 80 times at 20 ms, then every 20 ms for up to 2 minutes.
- It reads the setting before each write, and the fork calls the whole thing best-effort.

Origin: thedjchi/Shizuku#165 (nozza87's Reddit find, chaoscreater's MacroDroid macro). Reproduced there by thedjchi
(Galaxy S23) and kitsumed (S22 Ultra). Fails on a nubia NX721J, Android 15 (Shizuku-Next #79).

**OP9 measurement (OnePlus 9 Pro, OxygenOS, Android 14), detector = new listening port in `/proc/net/tcp{,6}`:**

| Run | Result |
|---|---|
| Positive control, Wi-Fi connected, one write | New port 41913 appeared; gone after writing 0. The detector works. |
| Wi-Fi off, no hotspot, 146 writes in 40 s | No port. The setting fell back to 0. |
| Wi-Fi off, hotspot up (`wlan2`, started as root), 132 writes in 60 s | No port. |
| Wi-Fi off, hotspot up, 12 writers in parallel for 45 s | No port. The setting **stuck at 1** with no server. |

Logcat for the parallel run:
- `AdbDebuggingManager: Not connected to any wireless network. Not enabling adbwifi.` The Wi-Fi check runs
  **before** adbd's TLS server starts, so on this build there is no window to win.
- OnePlus's `SettingsProvider` drops change notices closer than 10 ms apart (`notifyForSettingsChange ignore …
  minInterval: 10`). That is how the setting ends up stuck at 1 while nothing listens.

**Verdict:** 🧪/📐 works on some Samsung phones (third-party reports); ❌ does not work on the OP9 (measured
2026-11-05). This is an unpatched Android bug, and its maintainers expect Google to close it. It is not a base to
build a feature on. The verdict above still holds for OnePlus. A Samsung phone would be needed to measure it
ourselves.

## 2026-11-08 — fresh sweep of the Shizuku family + a candidate of our own

**Why again:** the maintainer asked for a new pass over Shizuku repos (forks and sub-forks) and the wider field,
to see if anyone has armed ADB without fully enabling Wi-Fi/USB debugging — or a bug like Shizuku-Next's that we
could use ourselves. Sources read this pass: Shizuku-Next #79, #119; RikkaApps/Shizuku #864, discussion #462;
kitsumed's teardown; thedjchi, rushiranpise and pixincreate fork wikis; AOSP/XDA guides.

### Verdict: nobody has broken the chicken-and-egg. The field is exactly where the 2026-10-23 pass put it.
Every method in every repo still needs **one** initial authorised adb handshake — a USB cable once, or a
Wireless-debugging pairing once (SPAKE2 over the local network, which itself needs Wi-Fi association). After that
first handshake they all converge on the same two tricks, both of which we already understand and the second of
which we already ship:

1. **Keep it alive within a boot via legacy `adb tcpip` + USB debugging left on.** thedjchi's own wiki states it
   plainly: with its "TCP mode" on, "Wi-Fi is needed only once per reboot". That is our loopback path
   ([[loopback-tcpip-offwifi]]): once adbd is listening on 127.0.0.1 it stays up for the whole boot with no Wi-Fi,
   as long as USB debugging stays on (the listener lives inside adbd — [[debugging-switches-model]]). The one
   unrecoverable gap is unchanged: a reboot taken off-Wi-Fi that never returns to Wi-Fi.
2. **The `adb_wifi_enabled=1` spam race (Shizuku-Next "Start without Wi-Fi").** Bring up a local-only hotspot (so
   mDNS has an interface), then write `adb_wifi_enabled=1` ~80× at 20 ms. The fork's own authors call it "a race
   condition exploit". We measured it ❌ dead on the OP9 (OnePlus throttles setting notices and checks Wi-Fi
   before starting the TLS server). Third parties report it ✅ on several Samsung models (S22U, S23, Z Fold 7),
   ❌ on a nubia (Android 15). So it is a real but **OEM-specific timing bug**, and it is being narrowed
   ([[wifi-free-adb-bootstrap-impossible]] strategic-threat note).

Nothing new on the "clean" side either: no non-root write to `/data/misc/adb/adb_keys` (can't pre-seed a trusted
key), `service.adb.tcp.port` still SELinux-neverallowed for apps, WD gate still BSSID-keyed to the primary STA
(`getConnectionInfo` reads only `wlan0`, so a self-hosted hotspot never satisfies it cleanly — which is *why* the
spam race is needed rather than a clean enable). No Android 16/17 change surfaced that opens a new channel; the
direction of travel is restriction, not relaxation.

Automation-app variants seen (all need `WRITE_SECURE_SETTINGS`, granted once via the first pairing): MacroDroid /
Tasker / SystemUI Tuner / AutoJs6 watchdogs that write `adb_wifi_enabled=1` (and sometimes `adb_enabled=1`) on a
timer and re-run a Termux `nmap`-scan + `adb connect localhost:<port>` + run `libshizuku.so` script. These are the
same lever (write the setting) wrapped in different timers; none is a new primitive.

### Our own candidate — OPT-IN "arm without Wi-Fi" for built-in mode, gated to devices where the race works
This is the one lever worth productising, and it fits the maintainer's "using a bug is not a bad thing" stance.
Our two liveliest Samsung testers — chaoscreater (Z Fold 7) and "the fbi" (S24 Ultra) — already rely on exactly
this race through Shizuku-Next. The gap is that it only helps **Shizuku** users today; a user on CallVault's
**built-in** mode gets nothing. We already hold `WRITE_SECURE_SETTINGS`, so we can run the same sequence ourselves.

Proposal (📐 design only, not built):
- An **experimental, off-by-default** toggle: "Try to re-arm recording without Wi-Fi (Samsung and some others)".
- When armed-but-down off Wi-Fi, and the toggle is on: bring up a local-only hotspot if we can, write
  `adb_wifi_enabled=1` on the measured cadence (~80× @ 20 ms), watch `/proc/net/tcp{,6}` for a new listener, then
  connect on loopback and stop writing. Give up quietly after the window and leave the setting as we found it
  ([[only-undo-switches-we-set]]).
- **Device gating by measurement, not guesswork.** Ship it reporting success/failure so we build an allowlist
  from the field rather than claiming it works where it doesn't. Honest label: "experimental; may not work on
  your phone; Android may close this".
- **Measurement first, before any of this.** We have only ever tested the race on OnePlus (dead). We need a real
  Samsung run to confirm the window exists and find the cadence. Cheapest path: a short on-device script the fbi
  or chaoscreater runs in **built-in** mode (not Shizuku), or a borrowed Samsung. Without that, this stays 📐.

### What is NOT worth re-trying (settled, don't re-derive)
VPN-tun / USB-Ethernet (WD gate ignores them), self-hosted hotspot as a *clean* WD network (gate reads only the
STA), pre-seeding an adb key (root-only write), `service.adb.tcp.port` (SELinux), Device Owner / Dhizuku for call
*audio* (runtime-perm only; `CAPTURE_AUDIO_OUTPUT` is signature|privileged). See the 2026-10-23 passes above.

### Still the strongest zero-everything escape for the users it fits: INGEST MODE
Unchanged and still unbuilt: read the OS's own call recordings via the Android 12+ `Recordings/` MediaStore
collection — zero ADB, zero Wi-Fi, zero root — on ROMs that record calls themselves (GrapheneOS Dialer, some
Samsung/Xiaomi). Not universal (dead on stock Pixel + OnePlus/Oppo), but it is the only path that needs no
debugging switch at all. See [[wifi-free-adb-bootstrap-impossible]] §2.

## 2026-11-08 (pm) — an original direction: a per-ROM "permanent foothold", not a per-boot re-arm

The maintainer rightly rejected productising the Shizuku-Next race (a bug Google is expected to close). New angle,
from the invariant rather than from a bug: everyone keeps trying to **re-arm adb every boot**. Flip it — use the
single pairing the user already does to install a foothold that **survives reboot by itself, no Wi-Fi ever again**,
the way rooted users do with `persist.adb.tcp.port` (`sryze/wirebug`). The untested question was whether a plain
**shell** session (which one pairing gives us) can write the reboot-surviving adbd properties, or whether that is
truly root-only.

### Read-only recon on the OP9 (OnePlus 9 Pro, OxygenOS, Android 14; shell uid 2000, `u:r:shell:s0`)
- `/data/misc/adb/` → **Permission denied to shell.** A shell session cannot plant a trusted key; the one-time
  pairing remains the only route to trust. (Confirms the earlier verdict.)
- Property contexts actually defined here: `persist.adb.wifi.*` → `adbd_prop`; `persist.adb.tls_server.enable` →
  `system_adbd_prop`; `service.adb.tcp.port` → `adbd_config_prop exact int`. **`persist.adb.tcp.port` is not a
  defined property on this ROM** — so the rooted "permanent adb over TCP" persist trick does not exist on OnePlus.
  It is ROM-specific (present on some AOSP-ish / IoT / TV builds, absent here).
- `service.adb.tcp.port` is already `5555` and is shell-settable — that is our within-boot loopback arming.

### The idea, stated precisely
At first setup, enumerate the adbd properties THIS ROM defines (`/property_contexts`), and if it defines a
reboot-surviving one the **shell** domain is allowed to set (`persist.adb.tcp.port` on ROMs that have it;
possibly `persist.adb.tls_server.enable`), set it once so adbd auto-listens on every future boot with **no Wi-Fi**.
Bind loopback-only where `service.adb.listen_addrs` allows it, to avoid exposing a network port. Then our in-app
adb client connects on loopback each boot against the key paired once. No race, no Wi-Fi after setup, and it is
intended ROM config rather than a bug awaiting a patch — so it does not rot the way the race will.

### Honest bounds (already known before building)
- **ROM-gated, not universal.** OnePlus lacks `persist.adb.tcp.port`; it will work only where the ROM both defines
  such a property and lets `shell` set it. We must probe per ROM and report success from the field, never assume.
- **Still needs the one pairing** (trust), and **USB debugging staying on within a boot** for the listener to live
  (`persist` makes adbd *start* a listener, but adbd itself still dies if USB debugging is turned off —
  [[debugging-switches-model]]). So this shrinks the reboot-off-Wi-Fi gap on ROMs that fit, it does not remove all
  switches.
- **Security surface.** A persistent adbd listener is what rooted users already accept; bind loopback-only and keep
  it opt-in and clearly labelled.

### The active test, NOT yet run (needs maintainer go-ahead — touches adb security properties)
On the OP9 only (test phone; never the OP12 daily driver), as **shell, non-root**:
1. `setprop persist.adb.tls_server.enable 1` and `setprop service.adb.listen_addrs 127.0.0.1:5555`; read back —
   does the shell domain accept the write at all?
2. Reboot; with Wi-Fi OFF, check `/proc/net/tcp` for a loopback adbd listener and try an in-app loopback connect.
3. Record which writes stuck and whether a listener came up with no Wi-Fi. Reconnect is automatic over USB.
If step 1 is refused for every reboot-surviving property, this ROM cannot do it and the idea is OnePlus-dead
(fall back to today's model). If it sticks and step 2 shows a listener, we have a real permanent-foothold path for
that ROM class.

### A second original direction (for ROMs with their own recorder): flip-and-ingest
Where a ROM has its own call recording that is off by default and togglable by a durable system setting (some
Xiaomi/vivo China builds; GrapheneOS/Samsung expose it in the dialer), the one shell session could **enable that
setting durably** and we then **read the ROM's own recordings** ([[wifi-free-adb-bootstrap-impossible]] §2 ingest
mode). That needs no adb after setup and no Wi-Fi ever, because we stop capturing audio ourselves entirely. Also
ROM-gated, but a genuinely different architecture from re-arming adb.

### RESULT 2026-11-08 — ❌ shell cannot set the reboot-surviving props on OxygenOS (measured, OP9)
Ran the write step as shell (non-root) on the OP9. Both writes refused by the property service (SELinux):
- `setprop persist.adb.tls_server.enable 1` → "Failed to set property … See dmesg" (readback empty).
- `setprop service.adb.listen_addrs 127.0.0.1:5555` → same (readback empty).
- `service.adb.tcp.port` was already `5555` and is the only adb property the shell may set here — the within-boot
  one, which does NOT survive reboot.

No reboot was needed: the write refusal settles it for this ROM. **The permanent-foothold idea is OnePlus-dead.**
`adbd_prop` / `system_adbd_prop` are set-able only by adbd/root, and OnePlus does not define `persist.adb.tcp.port`
at all. It could still pay off ONLY on a ROM that both (a) defines a reboot-surviving adbd port property and (b)
leaves `set_prop` on it open to the `shell` domain — rare AOSP/IoT/TV-derived builds. So it degrades to a per-ROM
probe (enumerate + try + report), never a mainstream win, and never on OnePlus/Oppo.

**Bottom line of the whole re-sweep:** the invariant is confirmed yet again from a new angle. Non-root, zero-Wi-Fi,
universal carrier-call capture does not exist, because the only non-root door to a shell-uid process is adbd and
every durable, no-Wi-Fi way to open it is root/adbd-gated. The real remaining wins are engineering, not a bypass:
(1) make within-boot loopback bulletproof and LOUD when it drops; (2) ship the two per-ROM probes (persist-port
where a ROM allows it; OEM-recorder flip-and-ingest) as additive bonuses; (3) VoIP/app calls need no adb at all —
keep that path frictionless. See [[wifi-free-adb-bootstrap-impossible]].

## 2026-11-08 — revisiting "spoof a USB connection so the phone enables adb" (measured on OP9 + OP12)

The maintainer asked to re-open the idea of faking a USB connection so the phone thinks a host is attached and
hands out adb. Grounded it with read-only USB-gadget recon on both phones (OnePlus 9 Pro + OnePlus 12, OxygenOS).

### The invariant that kills it: "connected" is not "authorised", and a phone can't host itself
adb-over-USB is a **two-party, authenticated** protocol. The phone is always the USB **gadget** (device) end; a
**host** must enumerate the adb interface, send its public key, pass the "Allow USB debugging?" trust check, and
then drive the bulk endpoints to carry the shell. So two things have to exist, and neither can be faked in
software on the phone alone:
1. **A host that speaks adb.** There is none, and the phone cannot be its own host over its own single USB-C port
   (one data role per port; DRP switches roles, it is never both at once). Self-host needs **external hardware**
   (OTG 2nd phone / dongle / ~$5 ESP32 adb-host — `KhunHtetzNaing/ADB-OTG`, `wolfxalpha4/esp32-adb-client`) — all
   ruled out by the maintainer — **or root-level UDC loopback.**
2. **Driving the gadget endpoints locally.** Root-only (see below).

Merely making the phone *think* it is plugged in (`sys.usb.state=adb`) changes nothing: that only makes the phone
**offer** the adb interface. With no host running the adb client and passing key auth, no shell is produced.

### Measured facts (read-only)
- Both phones already sit in adb gadget mode with USB debugging on: `sys.usb.state=adb`, `sys.usb.config=adb`,
  controller `a600000.dwc3`. So the "USB is connected in adb mode" state is **already true** and still yields no
  shell without a host — proving the state alone is not the lever.
- The gadget plumbing is **not writable/usable by shell or an app**:
  - `/config/usb_gadget/` → Permission denied (reconfiguring the gadget is root/system).
  - `/dev/socket/adbd` → Permission denied (adbd's control socket is `system system`, and exposes no "run shell"
    verb anyway).
  - `/dev/usb-ffs/adb/` → OP9 denied; OP12 `dr-xr-x--- shell system` (read/execute only, no write), and adbd
    already owns the endpoints — two processes can't both drive ffs `ep0`.
- OP9 exposes a `dummy_udc.0` (a virtual UDC that can accept a gadget with no physical host); OP12 does not. Even
  on the OP9 it is useless here: binding a gadget to it needs **root** configfs writes, and a dummy UDC still has
  **no host client** to run the adb protocol, so no shell comes out.
- No `/dev/vsock` on either phone — the emulator-only loopback adb transport does not exist on retail hardware.
- Even `setprop service.adb.tcp.port` is refused for shell; the port value we see was set by **adbd itself** via
  `adb tcpip`, not by us. The shell→property path is closed for every adb property.

### Verdict: ❌ dead, same wall as the rest
Spoofing the USB connection cannot produce an authenticated adb session without external host hardware or root.
It does not break the chicken-and-egg; it runs into the identical "only adbd grants a shell, and only a real host
or root can make adbd do it off Wi-Fi" invariant. Do not re-open without new facts.

### The one adjacent thing that IS real (different problem: keep adbd ALIVE, not enable it)
"USB connected" matters to us for a separate reason: while USB is in an adb config, the init rule keeps **adbd
running** (USB config→`none` stops adbd — [[debugging-switches-model]]), and our loopback listener lives inside
adbd. So a passive accessory that merely **holds the USB data role** (a cable/dongle that never drops to
`sys.usb.config=none`, e.g. charge-only that keeps data) would help the *screen-off / unplug kills adbd* class
([[screen-off-adbd-kill-and-mitigations]], [[usb-charging-only-drops-adb]]) — but that is external hardware, and
it only keeps an **already-armed** adbd alive; it never *enables* adb from cold. Not a bypass, just a survival aid.
