/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.HandlerThread
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioSource
import com.baba.callvault.services.recording.handoff.AudioHandoffNative
import com.baba.callvault.services.recording.handoff.HandoffSource
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The privileged recorder itself: everything the app can ask a shell-uid process to do.
 *
 * **Deliberately separate from who started it.** Two things can host this class:
 *
 *  - [RecorderServer] — our own `app_process` daemon, launched over the embedded ADB shell.
 *  - [RecorderUserService] — the Shizuku path. Shizuku instantiates that subclass directly in a
 *    shell-uid process it owns (see `docs/dev-notes/2026-08-24-shizuku-support-plan.md`).
 *
 * Both hosts run as **shell, uid 2000**, so everything below behaves identically under either. Keeping
 * one implementation is the whole point: two would drift, and audio capture drift is the kind that is
 * invisible until someone plays back a call and hears only one side.
 *
 * All state lives here rather than in the host, so a second host cannot accidentally share or reset it.
 *
 * @param apkPath the daemon's own APK (`applicationInfo.sourceDir`), needed to extract scrcpy and to
 *   load `libaudiohandoff.so` from inside the APK.
 */
@Keep
open class RecorderServiceImpl(private val apkPath: String) : IRecorderService.Stub() {

    /** Guards single-session recording (binder threads + worker may race). */
    private val recordingActive = AtomicBoolean(false)

    /** The active session, owned by the worker thread; null between recordings. */
    @Volatile private var session: RecordingSession? = null

    /**
     * Speaker turns from the last finished recording, retained because the app asks for them after
     * [stopRecording] has already discarded the session.
     */
    @Volatile private var lastSpeakerTurns: String = ""
    @Volatile private var lastCaptureDiagnostics: String = ""

    /** Last VoIP session, kept only so the app can ask afterwards whether the far party was audible. */
    @Volatile private var lastVoipSession: VoipCaptureSession? = null

    /**
     * Serialises scrcpy launch/teardown OFF the binder thread (binder transactions must NOT block on
     * a multi-second scrcpy launch / 2s stop grace). Mirrors the engine's dedicated IO scope.
     */
    private val worker = HandlerThread("recorder-worker").apply { start() }
    private val workerHandler = android.os.Handler(worker.looper)

    init {
        // Which package this process presents to the audio stack, which is NOT obvious and differs
        // between hosts. AudioFlinger authorises a capture against (uid, package): our own daemon has
        // no ActivityThread, so it presents nothing and the framework resolves uid 2000 to
        // "com.android.shell" — which AudioService's own recording log shows on every successful
        // capture. A host that has initialised an ActivityThread for OUR package presents
        // "com.baba.callvault" under uid 2000 instead, which is a pairing no app owns.
        AppLogger.i(TAG, "recorder host: ${AudioAttribution.describe()}")
    }

    /**
     * Routes Shizuku's out-of-band destroy to the same teardown [destroy] does.
     *
     * Shizuku shuts a user service down by transacting [SHIZUKU_DESTROY_TRANSACTION] straight at the
     * binder rather than by calling a method on our interface, so nothing dispatches it for us. Handling
     * it here — instead of declaring it in the AIDL — is what keeps our own transaction IDs untouched:
     * `IRecorderService.aidl` numbers methods by position, so adding one anywhere but the end would
     * renumber the rest and a daemon left running by the previous version would answer the wrong call.
     */
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == SHIZUKU_DESTROY_TRANSACTION) {
            AppLogger.i(TAG, "Shizuku asked this user service to stop")
            destroy()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun startRecording(
        source: String?,
        codec: String?,
        bitRate: Int,
        outFd: ParcelFileDescriptor?
    ) {
        if (outFd == null || source == null || codec == null) {
            AppLogger.e(TAG, "startRecording: null arg (source=$source codec=$codec fd=${outFd != null})")
            return
        }
        // Reject if already recording; only the winner of the CAS proceeds.
        if (!recordingActive.compareAndSet(false, true)) {
            AppLogger.w(TAG, "startRecording ignored: already recording")
            return
        }

        // Resolve cliKey strings to enums on the binder thread (cheap, validates input early).
        val sourceEnum = runCatching { ScrcpyAudioSource.fromKey(source) }.getOrNull()
        val codecEnum = runCatching { ScrcpyAudioCodec.fromKey(codec) }.getOrNull()
        if (sourceEnum == null || codecEnum == null) {
            AppLogger.e(TAG, "startRecording: bad keys source=$source codec=$codec")
            recordingActive.set(false)
            return
        }

        AppLogger.i(TAG, "startRecording source=$source codec=$codec bitRate=$bitRate")

        // Heavy work (AudioRecord/encoder init or scrcpy launch) OFF the binder thread.
        workerHandler.post {
            val active = runCatching { startWithFallback(sourceEnum, codecEnum, bitRate, outFd) }
                .onFailure { AppLogger.e(TAG, "startRecording failed (all paths): ${it.message}", it) }
                .getOrNull()
            if (active != null) {
                session = active
            } else {
                // Both paths failed and neither owns the fd now — close it so it isn't leaked, and
                // release the recording latch so a later attempt can run.
                runCatching { outFd.close() }
                session = null
                recordingActive.set(false)
            }
        }
    }

    override fun stopRecording() {
        // Only the CAS winner tears down; idempotent against repeat/late calls.
        if (!recordingActive.compareAndSet(true, false)) {
            AppLogger.d(TAG, "stopRecording ignored: not recording")
            return
        }
        AppLogger.i(TAG, "stopRecording requested")
        // BLOCK this (synchronous) binder call until teardown finishes, so when the app's
        // release() returns the .ogg trailer is written and the file is complete. Otherwise the
        // app could move/read a truncated recording (the daemon's MediaMuxer.close happens in
        // session.stop). session.stop has its own internal grace/joins (~up to 4s).
        val done = CountDownLatch(1)
        workerHandler.post {
            runCatching { session?.stop() }
                .onFailure { AppLogger.w(TAG, "stopRecording teardown error: ${it.message}") }
            // Collect the speaker turns before dropping the session — the app queries them after
            // this call returns, by which point there is nothing left to ask.
            lastSpeakerTurns = runCatching { session?.speakerTurns().orEmpty() }.getOrElse {
                AppLogger.w(TAG, "Could not read speaker turns: ${it.message}")
                ""
            }
            lastCaptureDiagnostics = runCatching { session?.captureDiagnostics().orEmpty() }.getOrElse { "" }
            session = null
            done.countDown()
        }
        runCatching {
            if (!done.await(STOP_AWAIT_MS, TimeUnit.MILLISECONDS)) {
                // Teardown is stuck (e.g. a scrcpy child wedged mid-flush blocking a join). Interrupt
                // the worker so the blocking join/waitFor throws and the runnable can finish — otherwise
                // the single worker thread stays blocked and every future recording would hang.
                AppLogger.w(TAG, "stopRecording teardown exceeded ${STOP_AWAIT_MS}ms; interrupting worker")
                runCatching { worker.interrupt() }
            }
        }
    }

    override fun isRecording(): Boolean = recordingActive.get()

    override fun destroy() {
        AppLogger.i(TAG, "destroy requested — stopping and exiting daemon")
        stopRecording()
        // Give the worker a beat to finish the teardown post before we exit the process.
        workerHandler.post { exitProcess(0) }
    }

    // "Resilient recording" (Option B): create the privileged AudioRecord for the requested source,
    // extract the IAudioRecord + cblk, and DELIVER them to the app. Runs on the binder thread so the
    // synchronous push completes (app is capturing) before this returns true. Fast (create+extract).
    override fun startHandoff(source: String?, sampleRate: Int, channels: Int): Boolean {
        if (source == null) { AppLogger.e(TAG, "startHandoff: null source"); return false }
        AppLogger.i(TAG, "startHandoff source=$source rate=$sampleRate channels=$channels")
        // Loaded lazily (not at daemon boot): a user who never enables Resilient recording should
        // not pay for the dlopen, and daemon boot is on the critical path of a call that is already
        // ringing. Idempotent, and cheap once loaded.
        if (!AudioHandoffNative.ensureLoadedFromApk(apkPath)) {
            AppLogger.e(TAG, "startHandoff: libaudiohandoff.so unavailable in the daemon")
            return false
        }
        return runCatching {
            HandoffSource.deliverToApp(
                RecorderBinderProvider.AUTHORITY, source, sampleRate, channels
            )
        }.onFailure { AppLogger.w(TAG, "startHandoff error: ${it.message}") }.getOrDefault(false)
    }

    // ---- VoIP capture (experimental) -------------------------------------------------------

    // Arms the loopback policy. Must happen BEFORE a VoIP call's audio track exists, so the app
    // calls this when the feature is switched on (and on daemon start), never at call time.
    override fun armVoipCapture(): Boolean {
        val ok = runCatching { VoipAudioPolicy.arm() }
            .onFailure { AppLogger.w(TAG, "armVoipCapture error: ${it.message}") }
            .getOrDefault(false)
        AppLogger.i(TAG, "armVoipCapture -> $ok")
        return ok
    }

    override fun voipCallAppUid(): Int =
        runCatching { VoipAppIdentity.currentVoiceCommUid() }
            .onFailure { AppLogger.d(TAG, "voipCallAppUid failed: ${it.message}") }
            .getOrDefault(VoipAppIdentity.UID_UNKNOWN)

    override fun voipCallerName(packageName: String?): String? =
        runCatching { packageName?.let { VoipCallerName.resolve(it) } }
            .onFailure { AppLogger.d(TAG, "voipCallerName failed: ${it.message}") }
            .getOrNull()

    override fun voipCallAnswered(packageName: String?): Int =
        when (packageName?.let { VoipCallerName.answerState(it) } ?: VoipCallerName.AnswerState.NO_NOTIFICATION) {
            VoipCallerName.AnswerState.ANSWERED -> VOIP_ANSWERED
            VoipCallerName.AnswerState.RINGING -> VOIP_RINGING
            VoipCallerName.AnswerState.NO_TIMER -> VOIP_NO_TIMER
            VoipCallerName.AnswerState.NO_NOTIFICATION -> VOIP_NO_NOTIFICATION
        }

    override fun voipFarPartyHeard(): Boolean = lastVoipSession?.farPartyHeard ?: false

    /**
     * Speaker turns from the recording that just stopped; "" when the capture could not produce
     * any (a mono route, or the scrcpy fallback). Read after [stopRecording], which is why the
     * value is kept rather than asked of a session that no longer exists.
     */
    override fun speakerTurns(): String = lastSpeakerTurns

    override fun captureDiagnostics(): String = lastCaptureDiagnostics

    override fun disarmVoipCapture() {
        AppLogger.i(TAG, "disarmVoipCapture requested")
        runCatching { VoipAudioPolicy.disarm() }
            .onFailure { AppLogger.w(TAG, "disarmVoipCapture error: ${it.message}") }
    }

    override fun startVoipRecording(codec: String?, bitRate: Int, outFd: ParcelFileDescriptor?): Boolean {
        if (outFd == null || codec == null) {
            AppLogger.e(TAG, "startVoipRecording: null arg (codec=$codec fd=${outFd != null})")
            return false
        }
        if (!VoipAudioPolicy.isArmed) {
            // Arming now would not help this call: routing was fixed when its track was created.
            AppLogger.e(TAG, "startVoipRecording refused: policy was not armed before the call")
            runCatching { outFd.close() }
            return false
        }
        if (!recordingActive.compareAndSet(false, true)) {
            AppLogger.w(TAG, "startVoipRecording ignored: already recording")
            runCatching { outFd.close() }
            return false
        }
        val codecEnum = runCatching { ScrcpyAudioCodec.fromKey(codec) }.getOrNull()
        if (codecEnum == null) {
            AppLogger.e(TAG, "startVoipRecording: bad codec key $codec")
            recordingActive.set(false)
            runCatching { outFd.close() }
            return false
        }
        AppLogger.i(TAG, "startVoipRecording codec=$codec bitRate=$bitRate")
        // Heavy setup off the binder thread, matching startRecording.
        workerHandler.post {
            val active = runCatching {
                VoipCaptureSession(codecEnum, bitRate, outFd).also { it.start(); lastVoipSession = it }
            }.onFailure { AppLogger.e(TAG, "startVoipRecording failed: ${it.message}", it) }.getOrNull()
            if (active != null) {
                session = active
            } else {
                runCatching { outFd.close() }
                session = null
                recordingActive.set(false)
            }
        }
        return true
    }

    // Releases the daemon's held handoff AudioRecord (the app owns capture after the handoff; this
    // just frees the daemon's now-unneeded input so the next call can create a fresh one).
    override fun startHandoffHeld(source: String?, sampleRate: Int, channels: Int): Boolean {
        AppLogger.i(TAG, "startHandoffHeld source=$source rate=$sampleRate channels=$channels")
        // Same lazy load the normal handoff does — the binder + cblk extraction is native, and
        // without this the whole path fails with UnsatisfiedLinkError at the first native call.
        if (!AudioHandoffNative.ensureLoadedFromApk(apkPath)) {
            AppLogger.e(TAG, "startHandoffHeld: libaudiohandoff.so unavailable in the daemon")
            return false
        }
        return runCatching {
            HandoffSource.deliverToApp(
                authority = RecorderBinderProvider.AUTHORITY,
                sourceCliKey = source ?: "voice-call",
                sampleRate = sampleRate,
                preferredChannels = channels,
                startTrack = false,
            )
        }.onFailure { AppLogger.w(TAG, "startHandoffHeld failed: ${it.message}") }.getOrDefault(false)
    }

    // ---- Privileged grants (shell-level, no root) -------------------------------------------

    override fun grantAppOp(packageName: String?, opName: String?, userId: Int): Boolean {
        if (packageName.isNullOrBlank() || opName.isNullOrBlank()) {
            AppLogger.e(TAG, "grantAppOp: null arg (package=$packageName op=$opName)")
            return false
        }
        return PrivilegedGrants.grantAppOp(packageName, opName, userId)
    }

    override fun grantRole(roleName: String?, packageName: String?, userId: Int): Boolean {
        if (roleName.isNullOrBlank() || packageName.isNullOrBlank()) {
            AppLogger.e(TAG, "grantRole: null arg (role=$roleName package=$packageName)")
            return false
        }
        return PrivilegedGrants.grantRole(roleName, packageName, userId)
    }

    override fun hostUid(): Int = android.os.Process.myUid()

    override fun hostApkPath(): String = apkPath

    override fun setDiagnosticsEnabled(enabled: Boolean) {
        AppLogger.setRingEnabled(enabled)
        // Logged after enabling so the very first line in the ring says when collection began — which is
        // the difference between "the daemon logged nothing" and "the daemon was not collecting".
        AppLogger.i(TAG, "Diagnostics ring ${if (enabled) "enabled" else "disabled"} in the recorder host")
        // Re-state who this process IS, every time collection starts.
        //
        // It is logged at daemon startup too, but the ring is switched on later — so in practice the
        // identity line was always older than the collection and never appeared in a report at all
        // (zero occurrences in the first real export). It matters more than it looks: this process runs
        // as uid 2000, which belongs to com.android.shell, so Android's microphone indicator attributes
        // ANY capture here to "Shell". A user reporting "the Shell process has the microphone on" is
        // describing this process, and the report should say which pid that was.
        if (enabled) AppLogger.i(TAG, "recorder host identity: ${AudioAttribution.describe()}")
    }

    /**
     * Pauses or resumes the VoIP encode.
     *
     * Routed to [lastVoipSession] rather than to the generic `session`, because only a VoIP session
     * has a pause: the carrier path pauses in the app's own encoder and never reaches the daemon.
     * A no-op when nothing is recording, which is what a stale notification action looks like.
     */
    override fun setVoipPaused(paused: Boolean) {
        val active = lastVoipSession
        if (active == null) {
            AppLogger.w(TAG, "setVoipPaused($paused) ignored: no VoIP session")
            return
        }
        active.setPaused(paused)
    }

    /** Holds the app-call recording open across a carrier call. See the AIDL for why it is not a pause. */
    override fun setVoipSuspended(suspended: Boolean) {
        val active = lastVoipSession
        if (active == null) {
            AppLogger.w(TAG, "setVoipSuspended($suspended) ignored: no VoIP session")
            return
        }
        active.setSuspended(suspended)
    }

    override fun drainDiagnostics(): Array<String> = AppLogger.drainRing().toTypedArray()

    /**
     * Runs one named diagnostic dump as the shell user.
     *
     * The whitelist lives in [DiagnosticDumps], on this side of the binder, so the app can only ask
     * for a dump by name and never say what should run. See that class for why the app stopped
     * collecting these over its own ADB shell.
     */
    override fun diagnosticDump(key: String?, arg: String?): String? {
        if (key == null) return null
        return DiagnosticDumps.run(key, arg)
    }

    override fun killStaleRecorders() {
        // Kills EVERY other CallVault recorder process, of either kind, leaving only this one.
        //
        // Both kinds have to go, and siblings as much as the other host:
        //  - the other host, because a mode switch must not leave the previous backend serving;
        //  - siblings of our own kind, because Shizuku starts a FRESH user service rather than reusing
        //    the old one after the app process is replaced, and a daemon(true) service outlives the app.
        //    Removing one through Shizuku only works while its args still match, so a service from an
        //    older app version cannot be reached that way at all.
        //
        // Measured on the OP9 across this work: an ADB daemon running in Shizuku mode, two sibling
        // user services, and a stale pair that survived an update, a switch and a restart.
        val me = android.os.Process.myPid()
        val victims = PROCESS_PATTERNS
            .flatMap { pattern -> pidsMatching(pattern) }
            .distinct()
            .filterNot { it == me }

        if (victims.isEmpty()) {
            AppLogger.d(TAG, "No other recorder processes to clear")
            return
        }
        AppLogger.i(TAG, "Clearing ${victims.size} other recorder process(es): $victims (I am $me)")
        victims.forEach { pid ->
            runCatching { android.os.Process.killProcess(pid) }
                .onFailure { AppLogger.w(TAG, "Could not kill $pid: ${it.message}") }
        }
    }

    /** Pids whose command line matches [pattern], excluding the pgrep we run to find them. */
    private fun pidsMatching(pattern: String): List<Int> = runCatching {
        val process = ProcessBuilder(PGREP, "-f", pattern).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(5, TimeUnit.SECONDS)
        output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.toList()
    }.getOrElse {
        AppLogger.w(TAG, "pgrep '$pattern' failed: ${it.message}")
        emptyList()
    }

    override fun stopHandoff() {
        AppLogger.i(TAG, "stopHandoff requested")
        runCatching { HandoffSource.releaseHeld() }
            .onFailure { AppLogger.w(TAG, "stopHandoff error: ${it.message}") }
    }

    /**
     * Starts capture, preferring the FAST direct AudioRecord pipeline and falling back to scrcpy.
     *
     * The direct path ([DirectAudioRecorderSession]) spawns no child process and needs no scrcpy jar, so
     * it begins capturing near-instantly — used whenever it can handle the source+codec on this device
     * ([DirectAudioRecorderSession.supports]). If it throws during setup it releases its own resources
     * WITHOUT closing [outFd], so the live fd can still be handed to scrcpy. Throws if BOTH paths fail.
     */
    private fun startWithFallback(
        source: ScrcpyAudioSource,
        codec: ScrcpyAudioCodec,
        bitRate: Int,
        outFd: ParcelFileDescriptor,
    ): RecordingSession {
        if (DirectAudioRecorderSession.supports(source, codec)) {
            val direct = DirectAudioRecorderSession(source, codec, bitRate, outFd)
            if (runCatching { direct.start() }
                    .onFailure { AppLogger.w(TAG, "Direct capture unavailable, falling back to scrcpy: ${it.message}") }
                    .isSuccess
            ) {
                AppLogger.i(TAG, "Recording via DIRECT AudioRecord — source=${source.cliKey} codec=${codec.cliKey}")
                return direct
            }
        }
        // Checked before the extractor opens it as a zip. A `daemon(true)` Shizuku service outlives the
        // app, and an app update moves the APK to a new hashed directory — so a surviving service holds
        // a path to a file that no longer exists. Left to ZipFile that surfaces as a NoSuchFileException
        // stack trace with nothing in it that names the actual problem.
        if (!java.io.File(apkPath).exists()) {
            throw java.io.IOException(
                "This recorder process is stale: its APK ($apkPath) no longer exists, so scrcpy cannot " +
                    "be extracted. The app was updated while the service kept running; the service must " +
                    "be restarted (Shizuku does that when the service version changes)."
            )
        }
        val freshJar = ScrcpyJarExtractor.ensureScrcpyJar(apkPath) // scrcpy needs its extracted jar
        val scrcpy = RecorderSession(source, codec, bitRate, outFd, freshJar)
        scrcpy.start()
        AppLogger.i(TAG, "Recording via scrcpy — source=${source.cliKey} codec=${codec.cliKey}")
        return scrcpy
    }

    companion object {
        private const val TAG = "CV:RecorderServer"

        /**
         * Absolute, because this runs in the privileged (shell-uid) daemon and PATH is inherited
         * rather than chosen. The sibling exec sites already spell out /system/bin — this one was
         * the odd one out, which is what CodeQL's java/relative-path-command picked up.
         */
        private const val PGREP = "/system/bin/pgrep"

        /** Upper bound the synchronous stopRecording() waits for session teardown (muxer trailer). */
        private const val STOP_AWAIT_MS = 6000L

        /**
         * The transaction Shizuku's server sends to shut a user service down.
         *
         * Not ours to choose: it is fixed by Shizuku, and confirmed against `RikkaApps/Shizuku-API`'s
         * demo `IUserService.aidl` (`void destroy() = 16777114;`) on 2026-08-24.
         */
        const val SHIZUKU_DESTROY_TRANSACTION = 16777114

        /** [voipCallAnswered] answers; the binder carries an int, so the enum stops here. */
        const val VOIP_ANSWERED = 1
        const val VOIP_RINGING = 0
        const val VOIP_NO_TIMER = -1
        const val VOIP_NO_NOTIFICATION = -2

        /**
         * How to recognise a CallVault recorder process, of either kind.
         *
         * The ADB daemon carries its main class on the command line; a Shizuku user service is named
         * `<package>:recorder`. Matching both, then excluding our own pid, is what makes the cleanup
         * safe to run from inside either one.
         */
        private val PROCESS_PATTERNS = listOf("com.baba.callvault.server.RecorderServer", ":recorder")
    }
}
