# Issue #65 — finding Wireless debugging's connect port without mDNS

🧪 VERIFYING — built, JVM tests pass, **not run on any phone**. Branch `fix/adb-connect-port-fallback-65`.

## The report

Realme GT 7 (RMX5061, Android 16, RMX5061_16.0.10.500(EX01)), standalone mode. Pairing worked
(`_adb-tls-pairing._tcp` found and accepted at the phone's own IP), but `_adb-tls-connect._tcp` was never
found — not dropped, never seen — for 25 minutes. `AdbMdns.discoverPort` timed out (12 s) on every attempt,
`connectViaWirelessDebuggingWithReason` returned `NO_ADB_SERVICE`, the recorder never started, and "Record
without Wi-Fi" could not arm (arming needs one connection first). A Note10+ on Android 12 probably has the same
problem. `AdbMdns.kt` was unchanged since 2.4.5, so not our regression.

## Cause (📐 from AOSP source, not measured on the phone)

The two services are advertised by different processes. Pairing is advertised by system_server through
NsdManager. The connect service is advertised by **adbd** through `DNSServiceRegister`, which talks to the old
`mdnsd` daemon (started on demand, `ctl.start mdnsd`; failure is only a log line). A ROM that does not ship,
start, or let `mdnsd` answer on wlan0 pairs fine and is never found. `init.svc.mdnsd` is readable by apps, so it
is now in the debug report. `service.adb.tls.port` is not (system_internal_prop: adbd and system_server only).

## What was built

Maintainer decision (2026-10-29): fully automatic, **no** IP/port entry for users.

`ConnectPortFinder.find` (called from `AdbShell.connectViaWirelessDebuggingWithReason`, where mDNS's null used
to become `NO_ADB_SERVICE`), after Wireless debugging is known to read on:

1. **Last good port.** Every successful TLS connect stores its port (`adb_last_connect_port`, device-only, not
   in the export allow-list). It is probed first: plain adb `CNXN` to `127.0.0.1:<port>`, accept only `STLS`
   (200 ms connect / 400 ms read cap). adbd keeps its port until Wireless debugging or adbd restarts, so this
   usually skips the mDNS wait; a stale port is refused in about a millisecond.
2. **mDNS**, unchanged, 12 s timeout.
3. **Loopback search**, only if mDNS found nothing, `init.svc.adbd` is not `stopped`, and no search already
   failed in this Wireless-debugging session. Ports 32768–60999 (Linux's ephemeral range: adbd binds port 0),
   ordered last-good, then ±240 around it, then the rest low to high, de-duplicated. 48 daemon threads, 150 ms
   connect cap, 500 ms read cap, **5 s total budget** (📐 not measured; ~28k refused loopback connects should
   take well under that). Each open port gets the `CNXN` probe; `STLS` = the Wireless-debugging port, handed to
   the existing bounded TLS connect. `AUTH`/`CNXN` answers (classic `adb tcpip` listeners) are logged and not
   used. Our own loopback port and `service.adb.tcp.port` are excluded.

**Not repeating a failed search:** `ConnectPortScanGate` remembers a failed search keyed by the current Wi-Fi
network(s), until Wireless debugging changes (our own writes via `markOwnWirelessDebuggingWrite`, anyone's via
the keep-alive's `adb_wifi_enabled` observer), a successful connect, or 30 minutes pass.

**Locks:** the search runs inside the caller's locks (the launcher and arming hold `heavyOperationLock`). It is
bounded at 5 s and runs at most once per session, so it adds at most that to a wait mDNS already imposed. On
the call path the launcher reaches it only after mDNS has already failed, i.e. when the call would otherwise not
be recorded at all; the last-good probe makes the healthy path faster, not slower.

The probe approach (plain `CNXN`, accept `STLS`) follows Stardust's `AdbPortFinder.kt`; our code is written from
the adb protocol, not copied.

## Diagnostics

- Debug report, Standalone transport: `init.svc.mdnsd: <running|stopped|(unset)>` next to `init.svc.adbd`, and
  `Connect port search: <how the last search ended>`.
- When mDNS finds nothing, the log line names `init.svc.mdnsd` (`running` = mdnsd up but the service still
  not seen; `stopped`; `(unset)` = no such service on this ROM), so a report says which failure it is. It is
  printed with the search line, or once when a search is skipped, not on every retry.
- Ports are confirmed before use: the last good port by the `CNXN`→`STLS` probe; mDNS ports already by
  `MdnsServiceVerdict` (a bind on `127.0.0.1:<port>` must fail). A stale last-good port falls through at once.
- No Wireless-debugging off/on cycling was added (not decided).
- Log (`CV:ConnectPort`): `Connect port :N from the last good port` / `… from loopback search (K ports open, P
  asked, took X ms)`; mDNS successes keep their existing `Accepted …` line. A failed search logs once, with why
  (budget ran out / nothing answered as Wireless debugging), and one "not searching again" line afterwards.
- The mDNS "No … service accepted within 12000ms" line (and its "Discovering" lead-in) now appears at most once
  per 5 minutes while it keeps failing, with a count of the quiet repeats.

## Test switch

Settings → Debug → **"Skip mDNS when connecting (test the port search)"**, shown only while "Enable log
recording" is on, and honoured only then. Off by default, not exported (`debug_skip_mdns_connect`). When on,
the next Wireless-debugging connect skips the last good port and mDNS and runs the full search without the
last-good head start, ignoring the once-per-session rule.

## What to test on a device

1. **OP9, search finds the real port.** Standalone mode, Wireless debugging on. Settings → Debug: Enable log
   recording, then the Skip mDNS switch on. Force a fresh connect (e.g. kill the recorder or reboot the app's
   connection). Read the real port with `adb shell getprop service.adb.tls.port` from the PC. Log must show
   `Connect port :<that port> from loopback search (…took X ms)`; note X. The recorder must come up.
2. **OP9, last-good path.** Switch the test switch off, force another connect: log shows `from the last good
   port`, no mDNS wait.
3. **OP9, stale last-good.** Toggle Wireless debugging off/on (new port), force a connect: last-good probe
   refused, mDNS finds the new port as before.
4. **Debug report** shows `init.svc.mdnsd` and `Connect port search`.
5. **Reporter's Realme GT 7**: a build with this; expected log `from loopback search`, recorder starts, then
   "Record without Wi-Fi" can arm.
