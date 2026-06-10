# Plan 5 — Task 0 (de-risk spike) findings

De-risking the four hard risks of the persistent privileged recorder server (Shizuku-style)
*before* building production code. Device: OnePlus 12 (CPH2581), OxygenOS 16 / Android 16,
non-root, embedded ADB (libadb-android), over wireless ADB.

Throwaway harness lives in `app-src/.../persistserver/` (HeartbeatDaemon, PersistDaemonLauncher,
PersistDebugReceiver) + a debug receiver in the manifest. To be deleted once Task 0 concludes.

---

## Risk #1 — Daemonization survives Wireless-debugging OFF — ✅ GREEN (2026-06-10)

**Question:** Can a process launched through the app's *embedded* ADB shell detach into its own
session and outlive `adbd` when Wireless debugging is turned off?

**Method (spike 0a):** minimal `app_process` Java main (`HeartbeatDaemon`, FQCN
`com.kitsumed.shizucallrecorder.persistserver.HeartbeatDaemon`) that appends
`<seq> <epochMillis> pid uid` to `/data/local/tmp/cv_hb.txt` every 1s. Launched over
`AdbShell.openShell` (embedded ADB, shell uid 2000) with the Shizuku-cited detach command:

```
setsid sh -c 'CLASSPATH=<our.apk> exec app_process / <fqcn>' >/dev/null 2>&1 </dev/null &
```

Triggered via a debug broadcast. Then WD was toggled **OFF ~40s → ON** by the user (no reboot);
the file is the evidence (ADB is dead during the off-window, so it can't be polled).

**Result — conclusive survival:**
- Daemon launched as **pid 25811, uid 2000 (shell), PPID=1 (init)** — `setsid` reparented it to
  init immediately; it is not in adbd's process group.
- Heartbeat file: **158+ lines, every line `pid=25811`**, a single `START`, **zero `FATAL`**, no
  second pid. The per-second sequence runs **unbroken across the WD-off window** (last line before
  off `seq 9 @ 1781089203695`; continues 1s-apart through `seq 97 @ …301632` and onward) — i.e. the
  daemon kept writing ~1 line/s for the entire time WD/`adbd` was dead.
- After WD came back, the same `pid 25811` was still alive. Killed cleanly with `kill 25811`.

**Cited OSS:** RikkaApps/Shizuku native starter `manager/src/main/jni/starter.cpp` `start_server()`
(fork → `setsid(); chdir("/"); dup2(/dev/null → stdio); execvp(app_process …)`), and
`ServiceStarter.java` backgrounded-subshell form. We replicate the same detach over an adb shell.

**Conclusion:** The core Plan 5 mechanism is proven on this device — a `setsid`-detached
`app_process` launched via embedded ADB survives WD-off until reboot, exactly like Shizuku. Risk #1
does not block; proceed to spike 0b (audio + file-write from the detached daemon).

The benign `IOException: Stream closed` logged by `PersistDaemonLauncher.launch` is expected: the
detached daemon's stdio is `/dev/null`, so the launching shell exits immediately and the stdout
drain reads a closed stream. The daemon is unaffected.

---

## Risk #2 — Audio + file-write from the detached daemon (spike 0b) — ✅ GREEN (2026-06-10, pending user audio playback confirm)

**Question:** Can a detached daemon (shell uid 2000, no Activity) run scrcpy as a shell→shell
child and capture **voice-call audio** to a playable file **while WD is OFF**?

**Method (spike 0b):** `AudioCaptureDaemon` (in `persistserver/`) launched detached (setsid, same as
0a) reading the scrcpy jar, running scrcpy as a `ProcessBuilder` child, connecting to scrcpy's
abstract socket as a CLIENT, and muxing locally via the production `ScrcpyClient` + `ScrcpyAudioMuxer`
to `/data/local/tmp/cv_test.ogg`. A per-second status file records packet/byte counters.

**Result — real call audio captured with WD off the entire call:**
- The status byte-rate is the proof. Idle (no call) = ~150 B/s (3-byte Opus silence frames). During
  the call the rate jumped to a sustained **~16,050 B/s for ~17 s (elapsed 116–132)** = exactly
  128 kbps Opus = real voice. Before/after = silence.
- This run was CLEAN: WD stayed OFF the whole call and CallVault's own recorder never ran (we revoked
  `WRITE_SECURE_SETTINGS` + `am force-stop`-ed the app first — see interference note below). So the
  only thing capturing was the detached daemon.
- Output: valid, playable **Ogg/Opus, stereo, 48 kHz, 255 s** file (muxer trailer written on SIGTERM
  shutdown hook). 22 s call clip extracted and sent to the user to confirm voice content.
- The daemon (pid 12759, PPID=1) stayed alive across the WD-off call until we SIGTERM-finalised it.

**Conclusion:** Risk #2 and risk #4-lite are cleared — a detached, no-Activity, shell-uid daemon
captures voice-call audio and writes a playable .ogg with WD off. scrcpy's Android-11+ no-Activity
audio workaround fires fine from the detached daemon.

### Important production findings surfaced by 0b (feed into Tasks 2/4)
1. **The app's existing recorder FIGHTS the persistent daemon.** On a call, `PhoneStateReceiver →
   RecordingForegroundService → AdbShell.ensureConnected` (a) **re-enables WD**
   (`enableWirelessDebugging`, since the app holds `WRITE_SECURE_SETTINGS`) and (b) runs
   `ScrcpyLauncher.killStaleServers` = `pkill -f scrcpy.Server`, which **kills the daemon's scrcpy
   child**. The first 0b call was confounded this way (audio cut after ~4 s). → In the persistent-server
   world, the recording trigger must command the daemon over **binder** and must NOT run the old
   embedded-ADB `ScrcpyLauncher`/`killStaleServers` path. Task 4 must replace, not run alongside, it.
2. **Socket model:** the daemon must be the socket **CLIENT** (scrcpy serves the abstract socket via
   `tunnel_forward=true`), connecting a `LocalSocket` to `scrcpy_<scid>` with retry — exactly like the
   ADB-path `ScrcpyLauncher` does via `openLocalAbstract`. Creating our own `LocalServerSocket`
   collides with scrcpy ("Address already in use"). (Fixed in `AudioCaptureDaemon.runCapture`.)
3. **scrcpy jar location for the daemon is non-trivial:**
   - `/data/local/tmp/*.jar`/`*.dex` get **reaped** by OxygenOS (code-in-world-writable hardening);
     data files (`.ogg`, `.txt`) persist. Workaround: non-`.jar` name (`cvscrcpy.bin`) + launch
     immediately after placing it (scrcpy mmaps it before the reaper runs).
   - Reading the jar straight from `/sdcard/Android/data/<pkg>/files/` is **mount-namespace-flaky**
     for a detached daemon after WD/adbd restarts (worked once, then `File.exists()`=false; even
     `adb shell cp` from there failed after toggles).
   - Reliable for the spike: `adb push` the APK asset `assets/scrcpy-server` → `/data/local/tmp/cvscrcpy.bin`.
   - → Task 2/4 must choose a daemon-readable jar path deliberately (e.g. the app extracts it to a
     stable shell-readable path at daemon-launch, or hands it over the binder/pfd). Do NOT assume
     `/sdcard/Android/data` is readable by the detached daemon.
4. **Embedded ADB `openShell` is flaky** ("Stream closed") for rapid sequential commands over this
   wireless link — orthogonal to 0b but relevant to launcher reliability (needs retry).

## Risk #3 — Binder delivery shell→app ContentProvider, SELinux (spike 0c) — ✅ GREEN (2026-06-10)
## Risk #4 — File write via passed ParcelFileDescriptor (spike 0c) — ✅ GREEN (2026-06-10)

**Question:** Can a detached shell-uid daemon deliver a usable `IBinder` to the app's exported
`ContentProvider` (SELinux shell→app), and can the app then drive the daemon over binder IPC (no ADB)
and pass a `ParcelFileDescriptor` the shell-uid daemon writes into?

**Method (spike 0c):** `BinderDebugDaemon` (in `persistserver/`) exposes `IPersistDebugService`
(AIDL re-enabled) and pushes its stub to the app's exported `RecorderBinderDebugProvider`
(authority `com.kfir.callvault.persistdebug`) via Shizuku's `getContentProviderExternal` →
`provider.call("sendBinder", Bundle{BinderContainer})`. The app stores the binder; a debug broadcast
makes the app call `ping()`, `myUid()`, and `writeToPfd(pfd)` into an **app-private** file.

**Result — both passed first try:**
- **Risk #3:** daemon (pid 25350, uid 2000, PPID=1) delivered via `getContentProviderExternal` (no
  fallback needed). App provider (pid 25363, **uid 10512**) logged `received binder … alive=true`,
  `DELIVERED ok=true`. Hidden-API reflection worked (shell-uid `app_process` is exempt from the
  hidden-API blocklist, as for Shizuku). SELinux on OxygenOS 16 permits shell→app provider.
- **Risk #4:** app `ping` → `"pong from uid=2000 pid=25350"` (bidirectional binder IPC, app→daemon,
  no ADB). App passed a pfd to `/data/user/0/com.kfir.callvault/files/cv_pfd_test.txt` (app-private,
  shell uid cannot open directly); the daemon wrote 31 bytes into it via the fd; app + `run-as`
  both read back the exact payload. This is the production mechanism (app hands the daemon a SAF pfd).

**Note on "with WD off":** the binder channel is pure kernel binder IPC between app and daemon — it has
**zero dependency on adb/WD**. Only the daemon *launch* needs ADB (WD on, at setup/boot). Once the
binder is delivered, all commands flow WD-independent. So proving the mechanism (done here, WD on)
proves WD-off operation by construction.

**Cited OSS:** Shizuku-API `ShizukuProvider.java` (`METHOD_SEND_BINDER`, `EXTRA_BINDER`,
`handleSendBinder` + `extras.setClassLoader`), `BinderContainer.java`; Shizuku server
`ShizukuService.sendBinderToUserApp` (`getContentProviderExternal` → `BinderContainer` → `call` →
`removeContentProviderExternal`); `IContentProviderCompat` (SDK-31+ `AttributionSource` overload).

---

## Task 0 verdict — ✅ GREEN on all four risks. Plan 5 is feasible; proceed to build (Tasks 1–6).

The full Shizuku-style architecture is de-risked on the OnePlus 12 / OxygenOS 16 / Android 16:
1. ✅ `setsid` daemon survives WD-off (0a). 2. ✅ Detached daemon captures voice-call audio to a
playable .ogg with WD off (0b). 3. ✅ Shell→app binder delivery (0c). 4. ✅ App→daemon IPC + pfd write
(0c). No fatal blockers; the WD-on-demand fallback is NOT needed.

Carry-over design constraints for the build (from the 0b/0c findings above):
- Task 4 must **replace** the embedded-ADB `ScrcpyLauncher`/`killStaleServers` path with binder commands
  to the daemon — the old path re-enables WD and pkills the daemon's scrcpy.
- Daemon must be the scrcpy socket **client** (`tunnel_forward=true`).
- Choose a **daemon-readable scrcpy-jar path deliberately** (`/data/local/tmp/*.jar` is reaped;
  `/sdcard/Android/data` is namespace-flaky for the detached daemon). Push/extract to a stable path at
  launch, or hand it over binder. The throwaway harness in `persistserver/` is to be deleted once the
  production `server/`, AIDL, provider, and launcher land.
