# CallVault Unified — Plan 2: Embedded-ADB Recording Transport (replace Shizuku)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record a real phone call (both sides) through CallVault's **embedded ADB** connection instead of Shizuku — proving scrcpy-server audio can be captured and streamed over ADB, then wiring it into the existing recording pipeline.

**Architecture:** The spike (Plan 1) proved CallVault can pair/connect/run `uid=2000(shell)` over Wireless debugging. This plan turns that into the recording transport: launch scrcpy-server over the ADB shell with `tunnel_forward=true`, connect to its abstract audio socket via `openStream("localabstract:scrcpy_<scid>")`, and feed that stream into the **existing** `ScrcpyClient`/`ScrcpyAudioMuxer`. The Shizuku `ShellService`/`IShellService` path is bypassed (left in the tree; full removal + settings UI + storage routing is Plan 3).

**Tech Stack:** Kotlin + Compose · libadb-android 3.1.1 + Conscrypt · scrcpy-server (version from `BuildConfig.SCRCPY_VERSION`) · Android SDK (compileSdk 36, minSdk 30) · JDK 17.

---

## Decomposition (Plan 2 of 4)

- **Plan 1 (done)** — embedded-ADB spike. GREEN: pair/connect/`uid=2000`, survives reboot. See `docs/superpowers/specs/2026-06-10-adb-spike-findings.md`.
- **Plan 2 (this doc)** — record a real call over ADB (no Shizuku in the recording path). Shizuku code stays but is bypassed.
- **Plan 3** — remove Shizuku entirely (delete `ShellService`/`IShellService.aidl`/`ShizukuConnectionManager`/Shizuku deps & provider), replace Shizuku settings UI, and add **StorageRouter** (Local/Drive/Both — the original ask).
- **Plan 4** — hands-free boot (`BootBridge`/watchdog/whitelisting/QS-tile fallback).

**Task 1 is a hard GATE:** do not build the production wiring until scrcpy audio is proven to flow over the ADB socket on the device.

## Key facts established (don't re-derive)

- `AbsAdbConnectionManager.openStream(String dest)` passes `dest` straight to adbd. `dest = "localabstract:scrcpy_<scid>"` connects to scrcpy-server's abstract socket; `dest = "shell:<cmd>"` runs a shell command (stdout/stderr on the returned `AdbStream`). (Verified via `javap`.)
- libadb-android 3.1.1 has **no file-push/sync API**. We do **not** need one: `ServerExtractor` already writes `scrcpy-server.jar` to the app's **external** files dir (`/storage/emulated/0/Android/data/<pkg>/files/scrcpy-<ver>-server.jar`), which is **readable by uid 2000(shell)** — confirmed by `ScrcpyConfig.getServerPath` docs. The ADB shell reads it via `CLASSPATH`.
- Current `ScrcpyConfig.buildServerArgs` uses `tunnel_forward=false` (scrcpy dials the app's `LocalServerSocket`). Over ADB there is no app-side server socket, so we use **`tunnel_forward=true`** (scrcpy creates `localabstract:scrcpy_<scid>` and we connect to it). With `tunnel_forward=true`, scrcpy emits a 1-byte dummy unless `send_dummy_byte=false`; keep `send_dummy_byte=false`.
- `ScrcpyClient(inputPfd: ParcelFileDescriptor, expectedCodec, listener)` only uses `inputPfd` to make a `FileInputStream`. Decoupling it to take an `InputStream` lets both transports feed it.
- Spike classes to build on (branch `feat/callvault-adb-spike`, package `…spike`): `SpikeAdbManager`, `AdbMdns`, `AdbPairingService`, `SpikeActions`, `SpikeLog`. The `CONNECT_SETTLE_MS` (2.5 s) settle-after-connect lesson applies.
- Audio format: 48 kHz stereo; Opus/AAC; FourCC header first (see `ScrcpyClient` doc).

## Proven references (don't design from scratch — mirror these)

Per project rule, the scrcpy-over-ADB transport is modeled on existing implementations, not invented:

- **Tango ADB** (`tangoadb.dev/scrcpy/start-server`, `/connect-server`) — runs scrcpy entirely over the **raw ADB protocol** (no `adb` binary, no PC), the exact analogue of our libadb approach. Confirmed mechanics we mirror:
  - Connect by opening a stream to `localabstract:scrcpy_<scid8hex>` — the protocol-level equivalent of `adb forward … localabstract:`. (= our `openStream("localabstract:…")`.)
  - **Socket readiness = retry the open** with ~100 ms backoff up to ~100 tries (raw-protocol: the open *fails* until the server has created the socket; retry-until-success — do NOT use a fixed sleep).
  - The ADB connection is **multiplexed**; you MUST continuously drain the shell (stdout) stream or it back-pressures and blocks every stream on the connection (incl. audio).
  - Socket order is video, audio, control; we disable video+control so audio is the only socket.
  - Forward-tunnel dummy byte: Tango enables it and `readExactly(1)` to skip it as a readiness signal. We instead keep `send_dummy_byte=false` (our `ScrcpyClient` expects the FourCC as the first bytes) and rely on the retry-open for readiness. If testing shows the open succeeds before the socket is truly serving (FourCC never arrives), switch to Tango's dummy-byte mode.
- **Genymobile/scrcpy** `doc/develop.md` — tunnel modes + `tunnel_forward=true` (device listens, client connects to `localabstract:scrcpy_<scid>`).
- **kitsumed/ShizuCallRecorder** (our upstream) — already the source of `ScrcpyConfig`/`ScrcpyClient`/`ScrcpyAudioMuxer`; the scrcpy *args + audio parsing* are unchanged, only the transport (Shizuku UserService → ADB socket) is swapped.

## File Structure

**New (production `integrations/adb/` package):**
- `integrations/adb/AdbConnectionManager.kt` — promoted `SpikeAdbManager` (persisted key/cert, singleton).
- `integrations/adb/AdbMdns.kt` — promoted from spike (unchanged logic).
- `integrations/adb/AdbPairingService.kt` — promoted from spike (notification pairing).
- `integrations/adb/AdbShell.kt` — thin facade: `ensureConnected(context): Boolean` (mDNS-discover `_adb-tls-connect._tcp` + connect + settle), `openShell(cmd): AdbStream`, `openLocalAbstract(name): AdbStream`, `isConnected`.
- `integrations/scrcpy/ScrcpyLauncher.kt` — launches scrcpy over ADB and returns an audio `InputStream` + a `stop()` handle. Replaces `ShellService.startRecording`.

**Modified:**
- `integrations/scrcpy/ScrcpyClient.kt` — constructor takes `InputStream` instead of `ParcelFileDescriptor`.
- `integrations/scrcpy/ScrcpyConfig.kt` — `buildServerArgs` switches to `tunnel_forward=true`.
- `services/recording/AudioRecordingEngine.kt` — `startPipeline` uses `ScrcpyLauncher` (ADB) instead of `IShellService.startRecording`.
- `services/recording/RecordingForegroundService.kt` — ensure ADB connection (via `AdbShell.ensureConnected`) instead of Shizuku `waitForServer()`/`getShellService()`.

**Untouched this plan:** `ShellService.kt`, `IShellService.aidl`, `ShizukuConnectionManager.kt`, Shizuku deps (removed in Plan 3).

---

## Task 1: GATE — prove scrcpy audio over the ADB socket (on-device)

Validate the single remaining unknown before any refactor: scrcpy-server, launched over ADB with `tunnel_forward=true`, streams a parseable audio header + packets over `localabstract:`.

**Files:**
- Modify: `spike/SpikeActions.kt` (add a `recordScrcpyTest` action)
- Modify: `spike/AdbSpikeActivity.kt` (add a "scrcpy test" button)

- [ ] **Step 1: Add a scrcpy smoke-test action**

Add to `SpikeActions` (reuses `ScrcpyConfig`, `ServerExtractor`, `SpikeAdbManager`):

```kotlin
// Pushes/extracts the server, launches it over ADB (tunnel_forward=true), connects the
// localabstract audio socket, and reports the first bytes (codec FourCC) + total bytes in ~5s.
suspend fun recordScrcpyTest(context: Context): String = withContext(Dispatchers.IO) {
    if (!connect(context)) return@withContext "connect failed"
    delay(CONNECT_SETTLE_MS)
    val serverPath = ScrcpyConfig.getServerPath(context)
    if (!ServerExtractor.ensureServerFile(context, serverPath)) return@withContext "server extract failed"
    val scid = ScrcpyConfig.getRandomSocketName()
    val args = ScrcpyConfig.buildServerArgs(
        scid, ScrcpyAudioSource.fromKey(/* default */ "output"),
        ScrcpyAudioCodec.fromKey("opus"), 16000,
    ).joinToString(" ")
    val mgr = SpikeAdbManager.getInstance(context)
    val shell = mgr.openStream(
        "shell:CLASSPATH=$serverPath app_process / ${ScrcpyConfig.SERVER_MAIN_CLASS} $args"
    )
    // REQUIRED: drain server stdout continuously or it blocks the multiplexed ADB connection (Tango).
    Thread { runCatching { shell.openInputStream().bufferedReader().forEachLine { SpikeLog.append("[srv] $it") } } }.apply { isDaemon = true }.start()
    // Readiness = retry the open until the server has created the socket (Tango: ~100ms backoff).
    val sockName = "localabstract:${ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX}$scid"
    val audio = run {
        var s: io.github.muntashirakon.adb.AdbStream? = null
        repeat(100) { if (s == null) runCatching { s = mgr.openStream(sockName) }.also { if (s == null) Thread.sleep(100) } }
        s ?: return@withContext "audio socket never became ready ($sockName)"
    }
    val ins = audio.openInputStream()
    val header = ByteArray(4); var n = 0
    while (n < 4) { val r = ins.read(header, n, 4 - n); if (r < 0) break; n += r }
    val fourcc = header.joinToString("") { "%02x".format(it) }
    var total = 0L; val buf = ByteArray(16 * 1024); val end = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < end) { val r = ins.read(buf); if (r < 0) break; total += r }
    runCatching { audio.close() }; runCatching { shell.close() }
    "scrcpy: header=0x$fourcc bytes=$total"
}
```

(Use the project's actual default `audio_source` key — check `ScrcpyAudioSource` entries; `"output"`/`"mic"`/`"voice-call"`. For a no-call smoke test, `"output"` or `"mic"` produces bytes.)

- [ ] **Step 2: Add a button** in `AdbSpikeActivity` that calls `scope.launch { SpikeActions.recordScrcpyTest(context) }`.

- [ ] **Step 3: Build, install, run on-device**

```bash
source ~/.callvault-env.sh && cd app-src && ./gradlew :app:installDebug
adb shell am start -n com.kfir.callvault/com.kitsumed.shizucallrecorder.spike.AdbSpikeActivity
```
Pair/connect if needed, then tap the scrcpy test button.
**Expected:** log shows a recognizable codec FourCC (`0x6f707573` = "opus") and `bytes=` a non-zero, growing count. Server logs `[srv]` show no fatal error.

- [ ] **Step 4: Record the result.** If audio bytes flow and the FourCC matches → GATE PASS, proceed. If scrcpy logs "Could not capture audio" / "stream explicitly disabled", record the exact error — that is an OEM audio-source issue to resolve before continuing (try other `audio_source` values; the call-audio source is validated in Task 7 with a real call).

- [ ] **Step 5: Commit**

```bash
git add app-src/app/src/main/java/com/kitsumed/shizucallrecorder/spike/
git commit -m "spike(plan2): validate scrcpy audio over ADB localabstract socket"
```

---

## Task 2: Promote the spike ADB classes into a production package

**Files:**
- Create: `integrations/adb/AdbConnectionManager.kt` (from `spike/SpikeAdbManager.kt`)
- Create: `integrations/adb/AdbMdns.kt` (copy of `spike/AdbMdns.kt`)
- Create: `integrations/adb/AdbPairingService.kt` (from `spike/AdbPairingService.kt`)
- Modify: `AndroidManifest.xml` (register production `AdbPairingService`)

- [ ] **Step 1: Copy `spike/AdbMdns.kt` → `integrations/adb/AdbMdns.kt`**, change package to `com.kitsumed.shizucallrecorder.integrations.adb`, keep logic identical.

- [ ] **Step 2: Copy `spike/SpikeAdbManager.kt` → `integrations/adb/AdbConnectionManager.kt`**, rename class to `AdbConnectionManager`, package to `…integrations.adb`. Keep the persisted key/cert + singleton. Update `getDeviceName()` to `"CallVault"`.

- [ ] **Step 3: Copy `spike/AdbPairingService.kt` → `integrations/adb/AdbPairingService.kt`**, package `…integrations.adb`, reference the production `AdbConnectionManager` and `AdbMdns`. Replace the `SpikeLog`/`SpikeActions` calls with `AppLogger`/no-op (the production pairing only pairs; the auto-chain stays a spike concept). Keep the notification + RemoteInput flow.

- [ ] **Step 4: Register the production service** in `AndroidManifest.xml` (mirror the spike entry: `foregroundServiceType="specialUse"` + the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property). Leave the spike entries for now.

- [ ] **Step 5: Compile**

Run: `source ~/.callvault-env.sh && cd app-src && ./gradlew :app:compileDebugKotlin` → `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit** `feat(adb): production AdbConnectionManager/AdbMdns/AdbPairingService`.

---

## Task 3: Decouple ScrcpyClient from ParcelFileDescriptor

**Files:**
- Modify: `integrations/scrcpy/ScrcpyClient.kt`
- Modify: `services/recording/AudioRecordingEngine.kt` (call site only)

- [ ] **Step 1: Change the constructor** of `ScrcpyClient` from
`private val inputPfd: ParcelFileDescriptor` to `private val input: java.io.InputStream`,
and replace the internal `FileInputStream(inputPfd.fileDescriptor)` with `input`. Remove the `ParcelFileDescriptor`/`FileInputStream` imports if now unused. In `close()`, close `input` instead of `inputPfd`.

- [ ] **Step 2: Fix the existing call site** in `AudioRecordingEngine.startPipeline` (the current Shizuku path) so it still compiles: wrap its pipe PFD as `ParcelFileDescriptor.AutoCloseInputStream(inputPfd)` when constructing `ScrcpyClient`. (This keeps the Shizuku path working until Plan 3 deletes it.)

- [ ] **Step 3: Compile** → `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit** `refactor(scrcpy): ScrcpyClient reads from InputStream (transport-agnostic)`.

---

## Task 4: ScrcpyLauncher — run scrcpy over ADB, return the audio stream

**Files:**
- Create: `integrations/scrcpy/ScrcpyLauncher.kt`
- Modify: `integrations/scrcpy/ScrcpyConfig.kt` (`tunnel_forward=true`)
- Create: `integrations/adb/AdbShell.kt`

- [ ] **Step 1: AdbShell facade**

```kotlin
package com.kitsumed.shizucallrecorder.integrations.adb
// ensureConnected: discover _adb-tls-connect._tcp via AdbMdns, connect, settle.
object AdbShell {
    private const val SETTLE_MS = 2500L
    fun ensureConnected(context: Context): Boolean {
        val mgr = AdbConnectionManager.getInstance(context)
        if (mgr.isConnected) return true
        val port = AdbMdns.discoverPort(context, AdbMdns.TLS_CONNECT, 25_000L) ?: return false
        val ok = mgr.connect("127.0.0.1", port)
        if (ok) Thread.sleep(SETTLE_MS)
        return ok
    }
    fun openShell(context: Context, cmd: String) =
        AdbConnectionManager.getInstance(context).openStream("shell:$cmd")
    fun openLocalAbstract(context: Context, name: String) =
        AdbConnectionManager.getInstance(context).openStream("localabstract:$name")
}
```

- [ ] **Step 2: Flip the scrcpy tunnel mode.** In `ScrcpyConfig.buildServerArgs`, change `"tunnel_forward=false"` to `"tunnel_forward=true"` (keep `send_dummy_byte=false`). Update the surrounding comment to explain the ADB `localabstract` connect model.

- [ ] **Step 3: ScrcpyLauncher**

```kotlin
package com.kitsumed.shizucallrecorder.integrations.scrcpy
// Launches scrcpy-server over ADB and exposes the audio InputStream. Replaces ShellService.startRecording.
class ScrcpyLauncher private constructor(
    private val shellStream: io.github.muntashirakon.adb.AdbStream,
    private val audioStream: io.github.muntashirakon.adb.AdbStream,
    val audioInput: java.io.InputStream,
) {
    fun stop() { runCatching { audioStream.close() }; runCatching { shellStream.close() } }

    companion object {
        private const val SOCKET_RETRY_COUNT = 100
        private const val SOCKET_RETRY_DELAY_MS = 100L
        // Throws on failure; caller wraps in PipelineInitializationException.
        fun start(context: Context, source: ScrcpyAudioSource, codec: ScrcpyAudioCodec, bitRate: Int): ScrcpyLauncher {
            val serverPath = ScrcpyConfig.getServerPath(context)
            require(ServerExtractor.ensureServerFile(context, serverPath)) { "scrcpy-server missing/invalid at $serverPath" }
            val scid = ScrcpyConfig.getRandomSocketName()
            val args = ScrcpyConfig.buildServerArgs(scid, source, codec, bitRate).joinToString(" ")
            val shell = AdbShell.openShell(context, "CLASSPATH=$serverPath app_process / ${ScrcpyConfig.SERVER_MAIN_CLASS} $args")
            // REQUIRED (Tango): continuously drain stdout or it back-pressures the multiplexed ADB connection.
            Thread {
                runCatching { shell.openInputStream().bufferedReader().forEachLine { AppLogger.i("ScrcpyLauncher", "[srv] $it") } }
            }.apply { isDaemon = true }.start()
            // Readiness via retry-open (Tango): the localabstract open fails until the server creates the socket.
            val sockName = "${ScrcpyConfig.SERVER_SOCKET_NAME_PREFIX}$scid"
            var audio: io.github.muntashirakon.adb.AdbStream? = null
            repeat(SOCKET_RETRY_COUNT) {
                if (audio == null) {
                    runCatching { audio = AdbShell.openLocalAbstract(context, sockName) }
                    if (audio == null) Thread.sleep(SOCKET_RETRY_DELAY_MS)
                }
            }
            val a = audio ?: run { runCatching { shell.close() }; throw java.io.IOException("scrcpy audio socket never ready: $sockName") }
            return ScrcpyLauncher(shell, a, a.openInputStream())
        }
    }
}
```

- [ ] **Step 4: Compile** → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit** `feat(scrcpy): ScrcpyLauncher runs scrcpy-server over ADB (tunnel_forward)`.

---

## Task 5: Wire the recording pipeline to ScrcpyLauncher

**Files:**
- Modify: `services/recording/AudioRecordingEngine.kt`
- Modify: `services/recording/RecordingForegroundService.kt`

- [ ] **Step 1: RecordingForegroundService — ensure ADB instead of Shizuku.** In the `ACTION_START_RECORDING` coroutine (currently `ShizukuConnectionManager.waitForServer()` + `shizukuManager.getShellService()` at `RecordingForegroundService.kt:201-205`), replace with:

```kotlin
if (!AdbShell.ensureConnected(this@RecordingForegroundService)) {
    throw IllegalStateException("ADB shell not connected (pair in setup; keep Wireless debugging ON)")
}
startNewRecordingSession(currentMeta) // no IShellService needed anymore
```
Keep the existing `catch (e: SecurityException)` / generic catch, but update the generic message away from "Shizuku not started" to an honest ADB message. (Honest-error fix from the original bug.)

- [ ] **Step 2: AudioRecordingEngine.startPipeline — use ScrcpyLauncher.** Replace the `service.startRecording(...)`/`audioReadPipePfd` block (`AudioRecordingEngine.kt:159-179`) with:

```kotlin
val launcher = try {
    ScrcpyLauncher.start(context, audioSourceEnum, codecEnum, bitRate)
} catch (e: Exception) {
    throw PipelineInitializationException(
        userFriendlyMessage = e.localizedMessage ?: context.getString(R.string.recording_error_start_failed),
        technicalLogMessage = "ScrcpyLauncher.start failed", cause = e,
    )
}
this.scrcpyLauncher = launcher
scrcpyClient = ScrcpyClient(inputStream = launcher.audioInput, expectedCodec = codecEnum, listener = …)
```
Change the engine's `release()`/`cancel()` to call `scrcpyLauncher?.stop()` instead of the `IShellService.stopRecording()`/pipe close. Remove the `IShellService` parameter threading from `startPipeline`/`release` (the engine no longer talks to a binder). Keep the SAF output-file logic (`SafHelper.createAudioFile`) unchanged.

- [ ] **Step 3: Build** → `BUILD SUCCESSFUL`. (Shizuku classes remain referenced elsewhere; that's fine for this plan.)

- [ ] **Step 4: Commit** `feat(recording): drive capture over embedded ADB (ScrcpyLauncher)`.

---

## Task 6: One-time pairing entry in the real app

So a real call can be recorded, the user must pair once from the app (not the spike screen).

**Files:**
- Modify: a settings/permissions screen (e.g. `ui/screens/PermissionsScreen.kt` or `SettingsScreen.kt`) — add a "Set up ADB (Wireless debugging)" action that starts `AdbPairingService` and a "Open Developer options" deep-link.

- [ ] **Step 1:** Add a button/section that calls `AdbPairingService.start(context)` and one that fires `Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)`. (Mirror the spike Activity wiring; reuse strings.) This is the minimum so Task 7 can run without the spike screen. Full settings-UI replacement of the Shizuku section is Plan 3.

- [ ] **Step 2: Build + install** → `BUILD SUCCESSFUL`, installs.

- [ ] **Step 3: Commit** `feat(ui): one-time ADB pairing entry point`.

---

## Task 7: On-device — record a real call over ADB (GATE)

- [ ] **Step 1:** On the OnePlus 12: open CallVault, pair once (code-only), confirm connected.
- [ ] **Step 2:** Place an **incoming** test call, let CallVault record, end the call. Repeat for an **outgoing** call.
- [ ] **Step 3:** Play back both recordings. **Expected:** both sides audible, file saved to the configured folder, no "Shizuku"/generic error. Use `adb logcat` filtered for `ScrcpyLauncher`/`SCR:`/`ScrcpyClient` to diagnose.
- [ ] **Step 4:** If one-sided or failing: try alternate `audio_source` (`voice-call`, `mic`, `output`) in `ScrcpyAudioSource`/settings; record which source captures both sides on OOS16. (Upstream warns OEM builds vary — this is the expected tuning step.)

**Exit criterion:** a saved recording with both parties audible, captured entirely over CallVault's embedded ADB, no Shizuku involved.

---

## Task 8: Findings + handoff to Plan 3

**Files:**
- Create: `docs/superpowers/specs/2026-06-10-adb-recording-findings.md`

- [ ] **Step 1:** Record: did scrcpy audio stream over ADB on the first try; which `audio_source` captured both call sides; any timing/settle tuning needed (server socket readiness, connect settle); stream stability over a multi-minute call; any `localabstract` quirks. Note what Plan 3 must clean up (delete `ShellService`/`IShellService.aidl`/`ShizukuConnectionManager`/Shizuku deps & provider; replace Shizuku settings; remove spike package) and that StorageRouter (Local/Drive/Both) is Plan 3's main feature.
- [ ] **Step 2: Commit** `docs: Plan 2 ADB-recording findings`.

---

## Risks

- **scrcpy audio over `localabstract` (Task 1)** — the main unknown; gated first. Validated by Tango ADB doing exactly this over the raw ADB protocol, so it's expected to work; `openStream("localabstract:…")` is the protocol equivalent of `adb forward … localabstract:`.
- **Server socket readiness** — handled the proven way (Tango): retry the `localabstract` open with 100 ms backoff until success, not a fixed sleep.
- **Multiplexed-connection back-pressure** — the shell stdout stream MUST be drained continuously (Tango), else audio stalls. `ScrcpyLauncher` drains it on a daemon thread.
- **OEM audio source** — which `audio_source` captures both call sides on OOS16 is empirical (Task 7).
- **Stream longevity** — a multi-minute call must not stall; the spike only read briefly. Validate in Task 7.
- **Shell stream lifetime** — closing the shell `AdbStream` must terminate scrcpy-server (no orphan). Verify in Task 7 / via `adb shell ps | grep app_process`.
