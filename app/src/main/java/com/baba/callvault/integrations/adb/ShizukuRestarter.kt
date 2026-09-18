/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import android.os.Build
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.ShizukuBackend
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.utils.AppLogger
import io.github.muntashirakon.adb.AdbStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Starts a Shizuku server again after CallVault's own `adbd` restart killed it, and says so.
 *
 * The decisions live in [ShizukuHealPolicy] and [ShizukuStarterPaths]; this is the part that needs a
 * `Context` and a privileged shell. It is driven from [AdbdChurnNotice.around], which is the one place
 * that knows whether a server was answering *before* the restart — afterwards the evidence is gone.
 *
 * **How the start works.** Shizuku's own manager starts its server by running
 * `lib/<abi>/libshizuku.so` out of its install directory from a shell; CallVault already has one of
 * those. No package name is hardcoded anywhere on this path — see [ShizukuStarterPaths].
 *
 * **Nothing here throws.** Every step fails into "could not start it", which is a notification the user
 * can act on. A phone with no Shizuku, an unresolvable install path, a fork with a different layout and
 * a shell that will not answer are all ordinary states, not errors to propagate into an ADB operation
 * that has already succeeded.
 */
object ShizukuRestarter {

    private const val TAG = "CV:ShizukuHeal"

    /** Hard cap on one shell round-trip, so a half-open adbd connection can never park this thread. */
    private const val SHELL_CAP_MS = 6_000L

    /**
     * How long to wait for the server to answer before calling the heal failed. The starter itself
     * returns in ~0.2 s on the OP9 and the server pid exists immediately, but the binder reaches *us*
     * through Shizuku's own delivery, which we do not drive.
     */
    private const val BACK_TIMEOUT_MS = 10_000L
    private const val BACK_POLL_MS = 250L

    /** How long one candidate gets before the next is tried. A real start answers well inside this. */
    private const val FIRST_LOOK_MS = 2_000L

    /**
     * One heal at a time. Arming the listener and closing it can land close together (the settings
     * screen turns off-Wi-Fi recording off and straight back on), and two heals racing is the two-hosts
     * hazard [ShizukuHealPolicy] exists to avoid, arriving by a different door.
     */
    private val healing = AtomicBoolean(false)

    /**
     * Puts Shizuku back after [what] restarted `adbd`. Call only when a server **was** answering before.
     *
     * Returns immediately: the work runs on a daemon thread that takes [AdbShell.heavyOperationLock]
     * first, so it queues behind whatever ADB operation is still in flight instead of racing it. That is
     * the "never at the cost of a call" rule expressed as a lock — the heal can only ever run *after*
     * the ADB work that needed `adbd`, and can never delay it.
     *
     * @param underDialog true when a dialog already warned the user before the restart.
     */
    fun healAfterAdbdRestart(context: Context, what: String, underDialog: Boolean) {
        val appContext = context.applicationContext
        if (!healing.compareAndSet(false, true)) {
            AppLogger.i(TAG, "A Shizuku restart is already running; not starting a second for $what")
            return
        }
        Thread {
            try {
                healNow(appContext, what, underDialog)
            } catch (t: Throwable) {
                // A heal that throws must not take anything with it: by here the ADB work the user asked
                // for has already succeeded, and this is a courtesy to another app.
                AppLogger.e(TAG, "Restarting Shizuku after $what failed outright: ${t.message}", t)
                runCatching { SilentFailureNotifier.warnShizukuRestartFailed(appContext) }
            } finally {
                healing.set(false)
            }
        }.apply { isDaemon = true; name = "cv-shizuku-heal" }.start()
    }

    private fun healNow(context: Context, what: String, underDialog: Boolean) {
        var verified = false
        val heal = synchronized(AdbShell.heavyOperationLock) {
            val decision = ShizukuHealPolicy.decide(
                wasRunningBefore = true,
                answersNow = shizukuAnswers(),
                recordingLive = isRecordingLive(),
            )
            AppLogger.i(TAG, "After $what: $decision")
            if (decision == ShizukuHeal.START) {
                // Under the ADB lease like every other connection user, or this one turns Wireless
                // debugging on and leaves it on — the leak asAdbUser exists to stop.
                verified = AdbShell.asAdbUser(context, "restarting Shizuku") { startAndVerify(context) }
            }
            decision
        }
        when (ShizukuHealPolicy.notice(heal, verifiedRunning = verified, underDialog = underDialog)) {
            ShizukuHealNotice.NONE -> AppLogger.i(TAG, "Nothing to tell the user after $what")
            ShizukuHealNotice.STOPPED -> SilentFailureNotifier.warnShizukuStopped(context)
            ShizukuHealNotice.RESTARTED -> SilentFailureNotifier.noteShizukuRestarted(context)
            ShizukuHealNotice.COULD_NOT_RESTART -> SilentFailureNotifier.warnShizukuRestartFailed(context)
        }
    }

    /** Runs the starter and waits for a server to actually answer. False means "could not start it". */
    private fun startAndVerify(context: Context): Boolean {
        if (!AdbShell.ensureConnected(context)) {
            AppLogger.w(TAG, "No ADB connection to start Shizuku through")
            return false
        }
        val manager = ShizukuBackend.managerPackage(context)
        if (manager == null) {
            // Sui lands here by design: it installs no manager app, and it is root-hosted rather than
            // adbd-hosted, so it was never ours to stop or to start.
            AppLogger.i(TAG, "No Shizuku manager to start — nothing installed that declares the API permission")
            return false
        }
        val candidates = starterCandidates(context, manager)
        if (candidates.isEmpty()) {
            // A fork that lays its APK out in a way the package manager will not describe ends here, and
            // that is the intended landing: "could not start it", not a path CallVault made up.
            AppLogger.w(TAG, "Could not work out where $manager keeps its starter")
            return false
        }
        val startedAt = System.currentTimeMillis()
        for (starter in candidates) {
            AppLogger.i(TAG, "Starting Shizuku again via $starter")
            // What the starter PRINTS is a log line, never the verdict. It forks the server and exits, and
            // on the emulator on 2026-09-18 that closed the stream mid-read while the server came up
            // perfectly — reading that as a failure posted "could not start it" over a running Shizuku.
            // There is deliberately no "does this file exist" probe either: the same stale stream made one
            // fail, and a probe that cannot ask must never be read as an answer of "no".
            //
            // One attempt, not the retrying [shell]: the request reaches adbd before the stream dies, so
            // the starter has already run. Measured on the emulator, retrying it spent 12 s on a reconnect
            // that could not succeed while the server it had just started was answering all along.
            val output = shellOnce(context, "'$starter'")
            AppLogger.i(
                TAG,
                output?.trim()?.replace('\n', ' ')?.take(240)
                    ?.let { "Shizuku starter said: $it" }
                    ?: "The starter returned no output; waiting to see whether it worked anyway",
            )
            // A candidate for an ABI this phone does not have costs one short look, not the whole budget.
            if (waitForPing(FIRST_LOOK_MS)) return answered(startedAt)
        }
        if (waitForPing(BACK_TIMEOUT_MS)) return answered(startedAt)
        // Last resort, and a weaker claim: the binder reaches us through Shizuku's own delivery, which we
        // do not drive, so a server that is up but has not sent it yet would otherwise read as a failure.
        if (serverProcessIsUp(context)) return true
        AppLogger.w(TAG, "Shizuku did not come back within ${BACK_TIMEOUT_MS}ms")
        return false
    }

    private fun answered(startedAt: Long): Boolean {
        AppLogger.i(TAG, "Shizuku answered again ${System.currentTimeMillis() - startedAt}ms after the starter ran")
        return true
    }

    /**
     * Every path worth trying for [manager], best first.
     *
     * Asks the package manager — `nativeLibraryDir` already names the one ABI Android installed, which
     * settles the several-ABIs case without guessing — and falls back to `pm path` over the shell for a
     * manager the package manager will not describe to us.
     */
    private fun starterCandidates(context: Context, manager: String): List<String> {
        val info = runCatching { context.packageManager.getApplicationInfo(manager, 0) }.getOrNull()
        val apkLine = info?.sourceDir
            ?: shell(context, "pm path $manager")?.lineSequence()?.firstOrNull { it.startsWith("package:") }
        return ShizukuStarterPaths
            .candidates(info?.nativeLibraryDir, apkLine, Build.SUPPORTED_ABIS?.toList().orEmpty())
            .filter(ShizukuStarterPaths::isShellSafe)
    }

    /** Polls the binder until a server answers us, or [timeoutMs] runs out. */
    private fun waitForPing(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (shizukuAnswers()) return true
            Thread.sleep(BACK_POLL_MS)
        }
        return false
    }

    private fun serverProcessIsUp(context: Context): Boolean {
        // A fork that renames the process reads as "not up" here; that is why this is the fallback and
        // the binder is the primary. Getting it wrong this way only costs an over-cautious message.
        val pid = shell(context, "pidof shizuku_server")?.trim().orEmpty()
        if (pid.isEmpty()) return false
        AppLogger.i(TAG, "The Shizuku binder has not reached us, but its server is running (pid $pid)")
        return true
    }

    private fun shizukuAnswers(): Boolean = runCatching { ShizukuBackend.isRunning() }.getOrDefault(false)

    /**
     * Whether the daemon says it is recording. A daemon we cannot reach answers "no" — it is not
     * recording anything either.
     */
    private fun isRecordingLive(): Boolean =
        runCatching { RecorderConnection.service?.isRecording == true }.getOrDefault(false)

    /**
     * One shell round-trip, hard-capped, returning null on anything that is not a clean answer.
     *
     * The bound is not optional here: this runs right after an `adbd` restart, where a TCP connection can
     * half-open and the read never sees EOF — the stall AdbShell's own shell probe documents. Abandoning
     * the worker is safe because it is a separate thread holding no lock of ours.
     *
     * This one retries through a forced reconnect, because it is used for **questions** — `pm path`,
     * `pidof` — where a missing answer is useless and, measured on the emulator on 2026-09-18, the first
     * round trip after `usb:` failed with "Stream closed." against a connection that still called itself
     * connected. A **command** must not go through here: see the starter call, which has already taken
     * effect by the time its stream dies.
     */
    private fun shell(context: Context, command: String): String? {
        shellOnce(context, command)?.let { return it }
        if (!AdbShell.forceReconnect(context)) {
            AppLogger.w(TAG, "Could not rebuild the ADB connection to retry '$command'")
            return null
        }
        return shellOnce(context, command)
    }

    private fun shellOnce(context: Context, command: String): String? {
        val out = AtomicReference<String?>(null)
        val stream = AtomicReference<AdbStream?>(null)
        val worker = Thread {
            runCatching {
                val s = AdbShell.openShell(context, command)
                stream.set(s)
                s.use { out.set(String(it.openInputStream().readBytes())) }
            }.onFailure { AppLogger.d(TAG, "shell '$command' failed: ${it.message}") }
        }.apply { isDaemon = true; name = "cv-shizuku-shell" }
        worker.start()
        runCatching { worker.join(SHELL_CAP_MS) }
        if (worker.isAlive) {
            runCatching { stream.get()?.close() } // unblocks the hung read so the abandoned thread dies
            worker.interrupt()
            AppLogger.w(TAG, "shell '$command' did not answer within ${SHELL_CAP_MS}ms")
            return null
        }
        return out.get()
    }
}
