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
