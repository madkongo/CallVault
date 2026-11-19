/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/** Tells the recorder each time a Wi-Fi network comes up, until stopped. */
interface WifiNetworkWatch {
    /** Registers, unless already registered. Throws when Android refuses. [onAvailable] gets a network id. */
    fun start(onAvailable: (String) -> Unit)

    /** Unregisters. Safe when not registered. */
    fun stop()
}

/** Runs one task later; scheduling again replaces the task not yet run. */
interface DelayedRunner {
    fun runAfter(delayMs: Long, task: () -> Unit)
    fun cancelPending()
}

/**
 * Shizuku mode's Wi-Fi join watch, in the recorder (review N15).
 *
 * The recorder is the process that runs as shell, stays up in Shizuku mode, and is not frozen by OEM app freezers,
 * so it notices joins itself and approves there ([KeptSwitchApprover]); the app is only told what happened. The app
 * arms it on every recorder connection, so a recorder started again (after a crash, an update, a reboot) watches
 * again. One registration per process, however often it is armed.
 *
 * A join is checked [JOIN_SETTLE_MS] after Android reports it (Android writes its own "Wi-Fi changed" switch-off
 * first); joins closer together than that are checked once, after the last. A callback registered while the phone
 * is on Wi-Fi hears about that network at once, so arming also checks the access point the phone is on.
 *
 * Every outcome is passed to [emit]: armed, stopped, a failed registration, each join, and each check's answer.
 */
/**
 * What the app says, asked right before each approval: whether approving is still wanted (Shizuku mode and the
 * setting on), the access point keys it remembers, and its salt.
 */
data class ApprovalConfig(val wanted: Boolean, val knownKeys: Set<String>, val salt: String)

class WifiJoinMonitor(
    private val watch: WifiNetworkWatch,
    private val runner: DelayedRunner,
    private val approve: (knownKeys: Set<String>, salt: String) -> KeptSwitchResult,
    private val emit: (WifiJoinEvent) -> Unit,
    /**
     * Asks the app, right before an approval, whether it is still wanted. Null when the app cannot be reached or does
     * not answer: then nothing is approved. A setting switched off while the recorder was not connected is honoured.
     */
    private val confirm: () -> ApprovalConfig?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Monotonic time, for the spacing of checks and lines. */
    private val elapsed: () -> Long = android.os.SystemClock::elapsedRealtime,
) {

    private var watching = false
    private var knownKeys: Set<String> = emptySet()
    private var salt: String = ""
    private var lastCheckAt: Long? = null
    private var lastNoteAt: Long? = null
    private var joinsSinceNote = 0

    /**
     * Starts ([on]) or stops watching. [knownKeys] (added to those already known) and [salt] are the app's, used when
     * Android's trusted list cannot be read. @return whether the recorder is watching afterwards.
     */
    @Synchronized
    fun arm(on: Boolean, knownKeys: Set<String>, salt: String): Boolean {
        this.knownKeys = this.knownKeys + knownKeys
        if (salt.isNotEmpty()) this.salt = salt
        if (!on) {
            if (watching) stopWatching("stopped watching Wi-Fi joins in the recorder")
            return false
        }
        if (watching) {
            note(warn = false, "still watching Wi-Fi joins in the recorder (armed again; one registration)")
            return true
        }
        return runCatching { watch.start(::onWifiAvailable) }
            .onSuccess {
                watching = true
                note(warn = false, "watching Wi-Fi joins in the recorder (pid ${android.os.Process.myPid()})")
            }
            .onFailure { note(warn = true, "could not watch Wi-Fi joins in the recorder: ${it.javaClass.simpleName}: ${it.message}") }
            .isSuccess
    }

    /**
     * Android's "available" for a Wi-Fi network. Checked [JOIN_SETTLE_MS] later, and never sooner than
     * [MIN_CHECK_INTERVAL_MS] after the last check (later joins fold into one check). At most one "available" line per
     * [MIN_CHECK_INTERVAL_MS]; the next says how many joins it stands for. Interval, not per network: Android gives a
     * new network id on every connection, so a flapping access point is a new "network" each time.
     */
    @Synchronized
    fun onWifiAvailable(network: String) {
        if (!watching) return
        val now = elapsed()
        val delay = lastCheckAt?.let { maxOf(JOIN_SETTLE_MS, it + MIN_CHECK_INTERVAL_MS - now) } ?: JOIN_SETTLE_MS
        val lastNote = lastNoteAt
        if (lastNote == null || now - lastNote >= MIN_CHECK_INTERVAL_MS) {
            val folded = if (joinsSinceNote > 0) " ($joinsSinceNote more Wi-Fi joins since the last line)" else ""
            note(warn = false, "Wi-Fi network available (net $network); checking its access point in ${delay / 1000} s$folded")
            lastNoteAt = now
            joinsSinceNote = 0
        } else {
            joinsSinceNote++
        }
        runner.runAfter(delay, ::check)
    }

    private fun check() {
        synchronized(this) {
            if (!watching) return
            lastCheckAt = elapsed()
        }
        val config = runCatching { confirm() }.getOrNull()
        if (config == null) {
            note(warn = true, "skipped: could not confirm with the app that approving is still wanted (app not reachable, or no answer); nothing approved")
            return
        }
        if (!config.wanted) {
            synchronized(this) {
                if (watching) stopWatching("skipped: the app says approving is no longer wanted (setting off, or not Shizuku mode); nothing approved, and the recorder stopped watching")
            }
            return
        }
        val (keys, salt) = synchronized(this) {
            knownKeys = knownKeys + config.knownKeys
            if (config.salt.isNotEmpty()) salt = config.salt
            knownKeys to salt
        }
        val answer = runCatching { approve(keys, salt) }.getOrElse {
            note(warn = true, "failed: the check threw ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        val trustedKey = answer.bssidKey.takeIf { answer.outcome.trusted && it.isNotBlank() }.orEmpty()
        if (trustedKey.isNotEmpty()) synchronized(this) { knownKeys = knownKeys + trustedKey }
        val (warn, text) = KeptSwitchReport.describe(answer)
        emit(WifiJoinEvent(clock(), warn, text, trustedKey))
    }

    /** Caller holds the lock. */
    private fun stopWatching(line: String) {
        watching = false
        runner.cancelPending()
        runCatching { watch.stop() }
            .onFailure { note(warn = true, "could not unregister the Wi-Fi watch: ${it.javaClass.simpleName}: ${it.message}") }
        note(warn = false, line)
    }

    private fun note(warn: Boolean, text: String) = emit(WifiJoinEvent(clock(), warn, text))

    companion object {
        /** After a join, before checking: Android writes its own "Wi-Fi changed" switch-off first. */
        const val JOIN_SETTLE_MS = 4_000L

        /** Checks (each forks `cmd wifi`, `dumpsys adb`, `settings`) and "available" lines are at least this far apart. */
        const val MIN_CHECK_INTERVAL_MS = 30_000L
    }
}
