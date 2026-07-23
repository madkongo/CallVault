/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioSource
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * CallVault Plan 5, Tasks 1–2 — PRODUCTION persistent privileged recorder daemon.
 *
 * A bare `app_process` entrypoint (shell uid 2000, NO Android Activity) launched DETACHED so it
 * survives Wireless debugging being turned OFF (proven by the spike's setsid-detach technique). It
 * exposes an [IRecorderService] over binder and PUSHES that binder to the app's exported
 * [RecorderBinderProvider] (authority [RecorderBinderProvider.AUTHORITY]) the same way the Shizuku
 * server pushes its binder to a client app. The app then drives recording over that binder with NO
 * ADB — even while WD is OFF.
 *
 * FQCN app_process invokes (its `static void main(String[])`):
 *   `com.baba.callvault.server.RecorderServer`
 *
 * Ported from the proven spike:
 *  • persistserver/BinderDebugDaemon — looper prep, [IRecorderService.Stub] pattern, binder delivery
 *    (extracted to [BinderDelivery]).
 *  • persistserver/AudioCaptureDaemon — scrcpy child + CLIENT LocalSocket + ScrcpyClient → muxer
 *    (extracted to [RecorderSession]).
 *  • Shizuku — RikkaApps/Shizuku — server/.../ShizukuService for the overall command-channel shape.
 *
 * Args (positional): `[apkPath, (optional) authority]`. `apkPath` is the daemon's own APK
 * (`applicationInfo.sourceDir`), used to self-extract scrcpy ([ScrcpyJarExtractor]).
 */
@Keep
object RecorderServer {

    private const val TAG = "CV:RecorderServer"

    /** Upper bound the synchronous stopRecording() waits for session teardown (muxer trailer). */
    private const val STOP_AWAIT_MS = 6000L

    /** Guards single-session recording (binder threads + worker may race). */
    private val recordingActive = AtomicBoolean(false)

    /** The active session, owned by the worker thread; null between recordings. */
    @Volatile private var session: RecordingSession? = null

    /** The daemon's own APK path (launch arg), used to (re)extract scrcpy if it goes missing. */
    @Volatile private var apkPath: String = ""

    /**
     * Serialises scrcpy launch/teardown OFF the binder thread (binder transactions must NOT block on
     * a multi-second scrcpy launch / 2s stop grace). Mirrors the engine's dedicated IO scope.
     */
    private val worker = HandlerThread("recorder-worker").apply { start() }
    private val workerHandler = android.os.Handler(worker.looper)

    /**
     * app_process entrypoint. Prepares a looper (binder transactions dispatch on it and the
     * system-context delivery fallback's ContentResolver expects a Looper thread — mirrors Shizuku's
     * server thread), self-extracts scrcpy, builds + delivers the stub, then loops until SIGTERM.
     *
     * @param args `[apkPath, (optional) authority]`.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val pid = Process.myPid()
        val uid = Process.myUid()

        // Pre-detach logs: visible in the launching adb shell BEFORE the pipe closes; after detach
        // stdio is /dev/null. AppLogger.* still reaches logcat while WD is ON.
        println("RecorderServer starting pid=$pid uid=$uid args=${args.joinToString(",")}")
        AppLogger.i(TAG, "RecorderServer starting pid=$pid uid=$uid args=${args.joinToString(",")}")

        if (args.isEmpty()) {
            AppLogger.e(TAG, "Expected at least 1 arg: <apkPath> [authority]")
            return
        }
        apkPath = args[0]
        val authority = args.getOrNull(1) ?: RecorderBinderProvider.AUTHORITY

        try {
            // A main looper is required (binder dispatch + system-context ContentResolver fallback).
            Looper.prepareMainLooper()

            // NOTE: scrcpy is NOT extracted here anymore — the direct AudioRecord path needs no jar, and
            // the scrcpy fallback re-extracts on demand ([startWithFallback]). Keeps daemon boot fast so a
            // relaunch after an Athena reap is ready sooner (the cold-start that a call races).

            val stub = createStub()
            val delivered = BinderDelivery.deliverBinderToApp(stub.asBinder(), authority)
            AppLogger.i(TAG, "Binder delivery finished ok=$delivered; entering Looper.loop()")

            // Keep the process + binder alive so the app can call us back over IPC, possibly while WD
            // is OFF. Ends on SIGTERM or destroy().
            Looper.loop()
        } catch (t: Throwable) {
            AppLogger.e(TAG, "RecorderServer fatal: ${t.message}", t)
        }
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
        val freshJar = ScrcpyJarExtractor.ensureScrcpyJar(apkPath) // scrcpy needs its extracted jar
        val scrcpy = RecorderSession(source, codec, bitRate, outFd, freshJar)
        scrcpy.start()
        AppLogger.i(TAG, "Recording via scrcpy — source=${source.cliKey} codec=${codec.cliKey}")
        return scrcpy
    }

    /**
     * The daemon-side [IRecorderService] implementation. Binder transactions are short and never block
     * on capture setup: [startRecording]/[stopRecording] post the heavy work to [workerHandler].
     */
    private fun createStub(): IRecorderService.Stub = object : IRecorderService.Stub() {

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
    }
}
