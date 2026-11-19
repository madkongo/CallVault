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
class WifiJoinMonitor(
    private val watch: WifiNetworkWatch,
    private val runner: DelayedRunner,
    private val approve: (knownKeys: Set<String>, salt: String) -> KeptSwitchResult,
    private val emit: (WifiJoinEvent) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var watching = false
    private var knownKeys: Set<String> = emptySet()
    private var salt: String = ""

    /**
     * Starts ([on]) or stops watching. [knownKeys] and [salt] are the app's, used when Android's trusted list cannot
     * be read. @return whether the recorder is watching afterwards.
     */
    @Synchronized
    fun arm(on: Boolean, knownKeys: Set<String>, salt: String): Boolean {
        this.knownKeys = knownKeys
        this.salt = salt
        if (!on) {
            if (watching) {
                watching = false
                runner.cancelPending()
                runCatching { watch.stop() }
                note(warn = false, "stopped watching Wi-Fi joins in the recorder")
            }
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

    /** Android's "available" for a Wi-Fi network. */
    @Synchronized
    fun onWifiAvailable(network: String) {
        if (!watching) return
        note(warn = false, "Wi-Fi network available (net $network); checking its access point in ${JOIN_SETTLE_MS / 1000} s")
        runner.runAfter(JOIN_SETTLE_MS, ::check)
    }

    private fun check() {
        val (keys, salt) = synchronized(this) {
            if (!watching) return
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

    private fun note(warn: Boolean, text: String) = emit(WifiJoinEvent(clock(), warn, text))

    companion object {
        /** After a join, before checking: Android writes its own "Wi-Fi changed" switch-off first. */
        const val JOIN_SETTLE_MS = 4_000L
    }
}
