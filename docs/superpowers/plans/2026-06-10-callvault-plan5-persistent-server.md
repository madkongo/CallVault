# CallVault — Plan 5: Persistent Privileged Recorder Server (Shizuku-style)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or superpowers:executing-plans. Steps use `- [ ]`.

**Goal:** Run a **persistent privileged recorder daemon** (shell uid 2000) that survives Wireless debugging being turned off — so WD only needs to be on briefly (at first setup and on boot to (re)launch the daemon), not continuously. The app commands the daemon over **binder** (no ADB at record time), exactly like Shizuku.

**Why:** Today CallVault connects to `adbd` per recording, so WD must stay on. Shizuku instead launches a daemon that detaches from ADB and stays alive; apps talk to it via a binder delivered through a ContentProvider. Adopting that model lets WD be off between reboots.

## Reference implementation (mirror these — do not invent)
- **Shizuku** (`RikkaApps/Shizuku`, `Shizuku-API`): server launched via `app_process`, **daemonized** to survive the ADB shell closing; binder delivered to the app via `ContentProvider.call("sendBinder", Bundle{ EXTRA_BINDER = BinderContainer })`; app stores it and calls privileged APIs over binder. `ShizukuProvider.java` (`METHOD_SEND_BINDER="sendBinder"`, `EXTRA_BINDER`, `BinderContainer`).
- **Our deleted `ShellService.kt`** (in git history before `0d7636b`): the exact scrcpy-launch + LocalServerSocket relay + muxing logic — this becomes the daemon's recording core, run shell→shell (no ADB).
- **scrcpy** tunnel + audio (already vendored).

## Architecture
```
First setup / on boot (WD on):
  App → (over ADB) launches RecorderServer via app_process, daemonized (survives WD off)
  RecorderServer (shell uid 2000) → calls App's RecorderBinderProvider.call("sendBinder", IBinder)
  App stores IRecorderService binder.   →  (WD can now be turned off)

Per call (NO ADB):
  PhoneStateReceiver → App → IRecorderService.startRecording(metadata, opts)  [binder IPC]
  RecorderServer runs scrcpy-server as a shell child → captures voice-call audio →
     muxes to the SAF/local file (or streams a pfd back to the app to write).
  Call ends → App → IRecorderService.stopRecording()  [binder IPC]
```

## Hard risks (a spike must clear these FIRST)
1. **Daemonization:** can we launch a process via our embedded ADB shell that **keeps running after the ADB stream closes / WD is turned off**? (Shizuku uses a starter that detaches; we must replicate — e.g. `app_process` under `nohup`/`setsid`, or a small starter.)
2. **Audio from a detached daemon:** scrcpy's audio capture uses a foreground-shell workaround on Android 11+. Verify the daemon (no Activity) can still capture `voice-call` audio (it runs scrcpy as a child, which applies the workaround — but confirm on-device).
3. **Binder delivery from shell → app provider:** confirm the daemon (shell uid) can call our exported `ContentProvider` and pass an `IBinder` that the app can use (SELinux `shell`→app provider).
4. **File writing:** the daemon (shell uid) writes to the recording folder. It cannot use the app's SAF `content://` grant. Either write to a shell-readable path and have the app move it (we already do temp→permanent in Plan 3), or pass a `ParcelFileDescriptor` from the app to the daemon over binder to write into the SAF file.

---

## Task 0: GATE — de-risk spike (debug harness)
Prove the four risks on-device before building production code.
- [ ] Add a throwaway `persistserver/` debug area. Using the embedded ADB shell, launch a minimal `app_process` daemon (a tiny Java main) **daemonized**; confirm it **survives turning WD off** (check `ps` after WD off — needs the laptop reconnect or an on-device check).
- [ ] From that daemon, call the app's debug `ContentProvider.call("sendBinder", BinderContainer)`; confirm the app receives a usable `IBinder`.
- [ ] From the daemon, run scrcpy-server as a child (Runtime.exec / ProcessBuilder, shell→shell) and confirm it captures audio (codec header + bytes) via a LocalServerSocket — **with WD off**.
- [ ] Decide GREEN/medium/blocked. If daemonization or audio-from-daemon fails, record why; fallback is the WD-on-demand toggle (Plan 4 Option B).

## Task 1: AIDL + BinderContainer
- [ ] Re-enable `buildFeatures.aidl = true`. Create `IRecorderService.aidl` (`startRecording(in RecordingMetadata, String source, String codec, int bitRate, ParcelFileDescriptor outFd)`, `stopRecording()`, `isRecording()`, `destroy()`), and a `BinderContainer` Parcelable wrapping an `IBinder` (mirror Shizuku's).

## Task 2: RecorderServer (the daemon)
- [ ] Port the deleted `ShellService` recording core into a standalone `server/RecorderServer.kt` with a `main()` runnable by `app_process`: it exposes `IRecorderService.Stub`, runs scrcpy as a shell child, muxes audio (reuse `ScrcpyAudioMuxer`/`ScrcpyClient`/`ScrcpyConfig`), and writes to the `ParcelFileDescriptor` passed from the app. On start it delivers its binder to the app via the provider (Task 3). Daemonize so it survives ADB/WD.

## Task 3: Binder delivery + app-side provider/connection
- [ ] `RecorderBinderProvider` (exported ContentProvider) with `call("sendBinder", …)` → stores the `IRecorderService` binder (mirror `ShizukuProvider.handleSendBinder`).
- [ ] `RecorderConnection` singleton holding the binder + state (connected / dead), with a `linkToDeath` that marks it dead so we relaunch when needed.

## Task 4: Launcher / lifecycle
- [ ] `RecorderServerLauncher`: over the embedded ADB shell, push/extract the server dex (or reuse the APK's classes via `CLASSPATH=<apk>`), and launch `app_process … RecorderServer` daemonized. Used (a) at first setup and (b) on boot.
- [ ] On boot (`BootReceiver`): enable WD (WRITE_SECURE_SETTINGS) → launch the server → once the binder arrives, optionally turn WD back off.
- [ ] Recording path: replace `AdbShell.ensureConnected` + `ScrcpyLauncher` with `RecorderConnection.service.startRecording(...)`; if the binder is dead, (re)launch the server (needs WD; enable it transiently).

## Task 5: WD policy + settings
- [ ] Add a setting: "Keep Wireless debugging on" (default) vs "Turn off when not needed" (the persistent-server payoff). When off-when-not-needed, the app disables WD after the binder is connected, and re-enables transiently only to relaunch the server (e.g. after reboot).

## Task 6: On-device validation
- [ ] Setup once (WD on) → turn WD **off** → place incoming + outgoing calls → confirm both record (server alive, binder IPC, no ADB) with both sides audible, files land in the folder, 0 orphans.
- [ ] Reboot → confirm the boot path re-launches the server hands-free and recording works with WD off afterward.

**Exit criterion:** with Wireless debugging **off**, calls record via the persistent server; WD is only enabled transiently at boot to relaunch it. Fully hands-free.

## Honest scope note
This is the largest single feature in the project — effectively a tailored mini-Shizuku (daemon + AIDL + binder delivery + ContentProvider + lifecycle + boot relaunch). Task 0 (the spike) is mandatory and may surface blockers (daemonization, audio-from-daemon, SELinux). If a blocker is fatal, the **WD-on-demand toggle** (Plan 4 Option B) is the pragmatic fallback that still gives "WD off between calls" without a daemon.
