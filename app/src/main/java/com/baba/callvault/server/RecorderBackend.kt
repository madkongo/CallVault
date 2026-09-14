/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.content.Context
import android.os.SystemClock
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.services.recording.VoipCaptureController
import com.baba.callvault.utils.AppLogger

/**
 * The one way to get a recorder running, whichever backend the user chose.
 *
 * Everything that needs the daemon — app start, a boot, an incoming call, the offline path, a
 * post-update relaunch — calls [ensureRunning] and does not care how it is served. The mode lives in
 * [AppPreferences.getPrivilegedMode]; the two implementations are [RecorderServerLauncher] (our own
 * ADB daemon) and [ShizukuBackend].
 *
 * Deliberately the same shape as `RecorderServerLauncher.ensureServerRunning`: blocking, returns
 * whether a usable binder is now in [RecorderConnection]. The call sites predate Shizuku and should not
 * have to be restructured to gain it.
 */
object RecorderBackend {

    private const val TAG = "CV:RecorderBackend"

    /** How long to wait for Shizuku to hand back a binder. Its bind is asynchronous. */
    private const val SHIZUKU_BIND_TIMEOUT_MS = 10_000L

    private const val POLL_MS = 100L

    /**
     * How long to wait for a torn-down recorder's binder to actually die before starting the next one.
     * Generous: getting this wrong reports the wrong backend as ready, which is worse than a slow switch.
     */
    private const val TEARDOWN_TIMEOUT_MS = 5_000L

    /**
     * Makes sure a recorder is running and its binder is in [RecorderConnection].
     *
     * @return true when a binder is available, false when it could not be obtained — a normal outcome
     *   for Shizuku mode on a phone where Shizuku is not running, and never an exception.
     */
    fun ensureRunning(context: Context, timeoutMs: Long = 24_000): Boolean {
        val mode = AppPreferences(context).getPrivilegedMode()
        return when (BackendChoice.of(mode)) {
            BackendChoice.ADB -> RecorderServerLauncher.ensureServerRunning(context, timeoutMs).also { up ->
                // Symmetric with the Shizuku branch: our daemon clears any Shizuku-hosted recorder left
                // over from a previous mode. Shizuku's own removal cannot reach a service started by an
                // older app version (its args no longer match), so this is the only thing that can.
                if (up) {
                    runCatching { RecorderConnection.service?.killStaleRecorders() }
                        .onFailure { AppLogger.w(TAG, "Could not clear stale Shizuku recorders: ${it.message}") }
                }
            }
            BackendChoice.SHIZUKU -> ensureShizukuRunning(context)
        }
    }

    /**
     * Mirrors the user's logging preference into the recorder host.
     *
     * The host is another process running as shell: it cannot read the app's preferences, so it cannot
     * decide this for itself and collects nothing until told. Called on every fresh binder — a daemon
     * that relaunched mid-session starts from scratch and would otherwise stay silent — and whenever
     * the user toggles logging.
     *
     * Never throws: an older host predating the method, or one that just died, must not take an app
     * path down with it.
     */
    fun syncDiagnostics(context: Context) {
        val wanted = runCatching { AppPreferences(context).isLoggingEnabled() }.getOrDefault(false)
        runCatching { RecorderConnection.service?.setDiagnosticsEnabled(wanted) }
            .onFailure { AppLogger.d(TAG, "Could not set diagnostics on the recorder host: ${it.message}") }
    }

    /**
     * Throws away whatever the recorder host has collected.
     *
     * For the user deleting their log: "delete" has to mean the host's copy too, or lines the user
     * believed they had erased would surface in the next report. Draining and discarding does it
     * without a second AIDL method, since draining already empties the ring.
     */
    fun clearHostDiagnostics() {
        runCatching { RecorderConnection.service?.drainDiagnostics() }
            .onFailure { AppLogger.d(TAG, "Could not clear the recorder host's diagnostics: ${it.message}") }
    }

    /**
     * Performs a whole mode switch and reports it — **the one definition of "the switch is finished".**
     *
     * A live binder is not the finish line. A switch tears things down, and the switch is not done until
     * everything it tore down is standing again; anything left running on its own thread afterwards is a
     * window in which the UI says "ready" and the app cannot do its job. That is not theoretical: VoIP
     * arming is a blocking IPC done on a separate thread, and a VoIP call landing before it completes is
     * **lost for good** — routing is fixed when the capture track is created, so there is no arming late
     * and no retry.
     *
     * In order, and synchronously:
     *  1. [switchTo] — tear the old backend down, reconcile the settings, set the mode.
     *  2. [ensureRunning] — bring the new backend up and wait for its binder.
     *  3. **Restart the keep-alive** in standalone. It is stopped on the way into Shizuku mode, and
     *     nothing restarted it on the way back until the next cold start of the app — and it *hosts VoIP
     *     detection*, so leaving it down silently costs detection as well as the daemon watchdog.
     *  4. **Wait for VoIP arming**, when the user has VoIP recording on.
     *
     * @return what the dialog should say, judged on the state that is true once all of that is done.
     */
    fun completeSwitch(context: Context, to: PrivilegedMode): ModeSwitchResult {
        switchTo(context, to)
        val connected = ensureRunning(context)

        if (!to.needsShizuku) {
            // Safe to call unconditionally: the service checks the mode itself and stands down in
            // Shizuku mode, and starting one that is already running is a no-op.
            runCatching { DaemonKeepAliveService.start(context) }
                .onFailure { AppLogger.w(TAG, "Could not restart the keep-alive after the switch: ${it.message}") }
        }

        val voipArmed = when {
            !connected -> false
            !AppPreferences(context).isVoipRecordingEnabled() -> true
            else -> runCatching { VoipCaptureController.sync(context) }
                .onFailure { AppLogger.w(TAG, "VoIP arming after the switch failed: ${it.message}") }
                .getOrDefault(false)
        }
        AppLogger.i(TAG, "Switch to $to complete: connected=$connected voipArmed=$voipArmed")
        // A recorder is up in the new mode, so a "cannot record" warning from the old one is now false — and
        // nothing else would clear it. Seen on the OP9: Shizuku died in Shizuku mode (warning correct), the
        // switch to standalone succeeded, and the Shizuku warning stayed up over a working recorder.
        if (connected) {
            runCatching { com.baba.callvault.system.health.SilentFailureNotifier.clearRecorderUnavailable(context) }
        }

        return ModeSwitchResult.of(to, connected, voipArmed, shizukuStatus(context))
    }

    /**
     * Moves to [to], stopping whatever the old mode had running first.
     *
     * The order is the point. Two shell-uid recorders would compete for the same audio input and the
     * loser is not predictable, so the old one is stopped and only then is the new one started.
     */
    fun switchTo(context: Context, to: PrivilegedMode) {
        val prefs = AppPreferences(context)
        val from = prefs.getPrivilegedMode()

        // Stop the keep-alive BEFORE tearing anything down, not after.
        //
        // It watches for binder death and relaunches our daemon *immediately* on that signal — which is
        // exactly what the teardown below produces. Measured on the OP9 entering Shizuku mode: the
        // daemon was destroyed at 47.350, the keep-alive saw the death and relaunched it at 47.466, our
        // daemon delivered its binder at 47.752, Shizuku's service started at 48.039, and our daemon's
        // killStaleRecorders then killed Shizuku's service at 48.098. The app settled in SHIZUKU mode
        // with our own ADB daemon serving it — the mirror image of the bug fixed yesterday, and just as
        // silent, because a recorder *was* running and everything reported success.
        //
        // Stopping it here (rather than after `setPrivilegedMode`, where it used to live) closes the
        // window entirely: there is nothing left to resurrect the daemon we are about to destroy.
        if (to.needsShizuku) {
            runCatching { DaemonKeepAliveService.stop(context) }
                .onFailure { AppLogger.w(TAG, "Could not stop the keep-alive before teardown: ${it.message}") }
        }

        when (BackendChoice.toTearDown(from, to)) {
            BackendChoice.ADB -> {
                AppLogger.i(TAG, "Leaving standalone mode; stopping our daemon")
                runCatching { RecorderConnection.service?.destroy() }
                    .onFailure { AppLogger.w(TAG, "Could not stop the daemon: ${it.message}") }
            }
            BackendChoice.SHIZUKU -> {
                AppLogger.i(TAG, "Leaving Shizuku mode; releasing the user service")
                // Ask the service ITSELF to exit first, exactly as the ADB branch above does.
                //
                // `stop(remove = true)` only asks *Shizuku* to destroy the service, and measured on the
                // OP9 it did not: the process was still alive 30s later, so its binder never died, the
                // teardown wait below ran out its full 5s, and ensureServerRunning then "reused" that
                // very binder — leaving the app in STANDALONE mode talking to a Shizuku-hosted recorder
                // while the switch dialog said "Ready — using CallVault". Silently, that costs every
                // standalone-only feature at once: handoff, VoIP arming and speaker attribution all
                // quietly do nothing, because capture is really going through scrcpy.
                //
                // We hold that binder and destroy() exits the process, so this is the one teardown that
                // does not depend on another app acting on our behalf.
                runCatching { RecorderConnection.service?.destroy() }
                    .onFailure { AppLogger.d(TAG, "The user service did not answer destroy(): ${it.message}") }
                runCatching { ShizukuBackend.stop(remove = true) }
                    .onFailure { AppLogger.w(TAG, "Could not stop the Shizuku service: ${it.message}") }
            }
            null -> {
                AppLogger.d(TAG, "Mode unchanged ($to); leaving the running recorder alone")
                return
            }
        }

        // Wait for the old recorder to actually be GONE before anyone asks whether one is running.
        //
        // destroy()/unbind only *ask*; the binder's death arrives asynchronously. Measured on the OP9:
        // the mode switch asked the ADB daemon to die at 16:11:56.731, and 3ms later ensureRunning saw
        // RecorderConnection still connected, reported "already connected; reusing existing binder",
        // and declared the switch ready — about the very daemon it had just killed. Shizuku was never
        // bound. The death landed 17ms after that, far too late to matter.
        val clearedBy = SystemClock.elapsedRealtime() + TEARDOWN_TIMEOUT_MS
        while (RecorderConnection.isConnected && SystemClock.elapsedRealtime() < clearedBy) {
            Thread.sleep(POLL_MS)
        }
        if (RecorderConnection.isConnected) {
            // Do NOT carry this binder into the new mode. It belongs to the host we just tore down, and
            // keeping it is how the app ended up in standalone mode recording through a Shizuku service
            // — reported as "Ready — using CallVault", with handoff, VoIP arming and speaker
            // attribution all silently absent. Dropping it makes the next ensureRunning start the
            // backend the user actually chose, or fail honestly.
            AppLogger.w(TAG, "The previous recorder is still connected after ${TEARDOWN_TIMEOUT_MS}ms")
            RecorderConnection.forceClear("it belongs to $from, and we are switching to $to")
        } else {
            AppLogger.i(TAG, "Previous recorder is gone; starting the $to backend")
        }

        prefs.setPrivilegedMode(to)

        // Entering Shizuku mode starts from a clean slate: drop any existing user-service record so the
        // next bind spawns a FRESH process running current code. A daemon(true) service survives app
        // updates, and an old one cannot clean up after itself — it predates the code that knows how —
        // so asking it to is useless. Done only on entering the mode, never on an ordinary start, which
        // would kill a warm recorder (possibly mid-call) for no reason.
        if (to.needsShizuku) {
            runCatching { ShizukuBackend.stop(remove = true) }
                .onFailure { AppLogger.d(TAG, "No previous Shizuku service to drop: ${it.message}") }

            // Stopped a second time, deliberately. The stop that matters happens before the teardown
            // above, so nothing can resurrect the daemon mid-switch; this one catches a keep-alive that
            // started again in between (it only checks the mode in onStartCommand, so one already
            // running when the switch happens never notices on its own). Stopping is idempotent, and
            // the cost of missing it is a foreground service polling for a daemon that must not exist.
            runCatching { DaemonKeepAliveService.stop(context) }
                .onFailure { AppLogger.w(TAG, "Could not stop the keep-alive: ${it.message}") }
        }

        // Turn off what the new mode cannot honour, rather than leaving switches on that promise
        // something they cannot deliver. Two of them (resilient recording, VoIP) previously produced
        // silent EMPTY recordings when the mode could not deliver, which is the worst possible shape
        // for a call recorder to fail in.
        // Put back what a previous switch took away, now that this mode can honour it again. Only ever
        // switches WE turned off, so the round trip is lossless without ever enabling something the user
        // turned off themselves. Before this, trying Shizuku once and coming straight back left resilient
        // recording, VoIP and offline recording off for good, and nothing said so.
        val restored = prefs.restoreWhatModeCanDoAgain(to)
        if (restored.isNotEmpty()) {
            AppLogger.i(TAG, "Turned back on (supported again in $to): ${restored.joinToString()}")
        }

        val turnedOff = prefs.disableWhatModeCannotDo(to)
        if (turnedOff.isNotEmpty()) {
            AppLogger.i(TAG, "Turned off (unsupported in $to): ${turnedOff.joinToString()}")
        }
        AppLogger.i(TAG, "Privileged mode is now $to")
    }

    /**
     * Whether the chosen mode can actually serve a recorder right now, without starting anything.
     *
     * What the status row on Settings reports. Standalone's readiness is a whole wizard's worth of
     * state and is reported elsewhere; this answers only the Shizuku half honestly.
     */
    fun shizukuStatus(context: Context): ShizukuStatus = ShizukuStatus.of(
        isRunning = ShizukuBackend.isRunning(),
        hasPermission = ShizukuBackend.hasPermission(),
        isInstalled = ShizukuBackend.isInstalled(context),
    )

    private fun ensureShizukuRunning(context: Context): Boolean {
        if (RecorderConnection.isConnected) {
            AppLogger.d(TAG, "Recorder already connected; reusing existing binder")
            return true
        }

        val status = shizukuStatus(context)
        if (status != ShizukuStatus.READY) {
            AppLogger.i(TAG, "Shizuku cannot serve a recorder right now: $status")
            return false
        }

        if (!ShizukuBackend.start()) return false

        // The bind is asynchronous: Shizuku starts the process, then calls back. Poll the holder the
        // callback fills, exactly as the ADB path polls for its pushed binder.
        val deadline = SystemClock.elapsedRealtime() + SHIZUKU_BIND_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (RecorderConnection.isConnected) {
                // Our own detached ADB daemon survives the app and is not stopped by anything here —
                // so without this, both backends run and either may hold the binder the app talks to.
                // Measured on the OP9: an app in Shizuku mode recorded through the leftover daemon.
                runCatching { RecorderConnection.service?.killStaleRecorders() }
                    .onFailure { AppLogger.w(TAG, "Could not clear stale ADB daemons: ${it.message}") }
                return true
            }
            Thread.sleep(POLL_MS)
        }
        AppLogger.w(TAG, "Shizuku did not hand back a recorder binder within ${SHIZUKU_BIND_TIMEOUT_MS}ms")
        return false
    }
}
