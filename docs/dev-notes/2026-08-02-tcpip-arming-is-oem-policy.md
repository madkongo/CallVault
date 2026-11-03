# Arming classic tcpip is an OEM policy decision, not an adb feature

**Date:** 2026-08-02 · **Context:** issue #22 (Galaxy S25, One UI 8.5 / Android 16), where three
attempts to arm the loopback listener all ended `Loopback arm result = false`.

## What the `tcpip:` service actually does

Read from AOSP `packages/modules/adb` (main), not inferred:

`daemon/restart_service.cpp` — the whole of `restart_tcp_service`:

```cpp
void restart_tcp_service(unique_fd fd, int port) {
    if (port <= 0) { WriteFdFmt(fd.get(), "invalid port %d\n", port); return; }
    LOG(INFO) << "adbd restarting in TCP mode (port = " << port << ")";
    android::base::SetProperty("service.adb.tcp.port", android::base::StringPrintf("%d", port));
    WriteFdFmt(fd.get(), "restarting in TCP mode port: %d\n", port);
}
```

It sets one property and says it restarted. **It does not restart adbd.** The cheerful "restarting in
TCP mode" it writes back is a claim about intent, not about anything that happened.

`daemon/main.cpp:269-292` reads `service.adb.tcp.port` (falling back to `persist.adb.tcp.port`) exactly
once, **at startup**, to decide what to listen on. A running adbd never re-reads it.

So between "the property is set" and "the port is open" sits a restart that has to come from somewhere
else. `daemon/services.cpp` has **no guard** on which transport the `tcpip:` request arrived over —
arming over Wireless debugging is legitimate, so that earlier hypothesis is dead.

## Where the restart comes from: the vendor, not AOSP

AOSP `system/core/rootdir/init.usb.rc:14`, verbatim:

```
# adbd is controlled via property triggers in init.<platform>.usb.rc
```

None of AOSP's generic rootdir `.rc` files carry an `on property:service.adb.tcp.port=* → restart adbd`
trigger; a code search finds it only in custom ROMs (CyanogenMod, LineageOS-derived trees). The trigger
lives in the **platform-specific** rc file each OEM ships and nobody outside Samsung can read theirs.

**Therefore "does `adb tcpip` work" is an OEM question, and a device where arming silently does nothing
is behaving within spec.** That matches issue #22 exactly: the arm fired with no error, and two seconds
later nothing was listening. It also explains why the same code arms fine on a OnePlus 12.

## What this predicts, and what the diagnostic build will show

| Property flips | Socket opens | Meaning |
|---|---|---|
| yes | yes, slowly | our 2 s wait was simply too short — widen it |
| **yes** | **never** | the ROM has no restart trigger; the port opens on the *next* adbd restart, whenever that is |
| no | never | adbd's `SetProperty` was denied (Knox/SELinux) — arming can never work here |

## The fix this suggests

If the middle row is what we see, we do not need Samsung to add a trigger — **we can cause the adbd
restart ourselves.** Writing `adb_wifi_enabled` restarts adbd; that is the exact churn we have spent
weeks avoiding, and here it becomes the mechanism:

1. fire `tcpip:<port>` (sets the property),
2. toggle Wireless debugging off/on to force adbd to restart,
3. the fresh adbd reads the property at startup and opens the port.

Only ever at idle, which is already the only time we arm. Worth pairing with `persist.adb.tcp.port`
(`main.cpp:277`) — adbd reads it at startup too, and being a `persist.` property it would survive a
reboot, removing the re-arm-after-reboot step entirely. Whether either property is writable from our
shell is what the diagnostic build's `setprop` read-back answers.

## Prior art check

No report of `adb tcpip` failing on Samsung exists in Shizuku, LADB, AppManager/libadb-android, or
upstream ShizuCallRecorder (which has no ADB code at all). Shizuku #2044 asks for exactly our feature
and calls tcpip mode *"extremely stable as long as the device does not reboot"* — but every account of
it working, including Shizuku #864's Termux walkthrough, arms it **from a machine over USB**, on
hardware whose vendor rc happens to carry the trigger. Nobody has published the failure case because
nobody else automates the arming step.
