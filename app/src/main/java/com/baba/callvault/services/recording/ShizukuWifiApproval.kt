/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.NetworkApprovalPolicy
import com.baba.callvault.integrations.adb.ShizukuNetworkApproval
import com.baba.callvault.integrations.adb.TransportTimeline
import com.baba.callvault.server.KeptSwitchOutcome
import com.baba.callvault.server.KeptSwitchResult
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku mode's "Approve new Wi-Fi networks automatically" (review N15), run on each Wi-Fi join
 * ([WifiJoinApprovalReceiver]).
 *
 * Asks the Shizuku-hosted recorder (shell uid) to approve the access point the phone is on, always-allow, so
 * Shizuku can start over Wireless debugging there after a reboot without Android asking first. The recorder skips
 * access points already approved and puts Wireless debugging back the way it found it
 * ([com.baba.callvault.server.KeptSwitchApprover]); its switch-off is the only write, and it undoes the approval's
 * own switch-on. Nothing here writes the switch or marks it as CallVault's (no "ours" / "turned off by user" flags).
 *
 * Every decision and every outcome goes to the always-on [TransportTimeline]; access points only ever masked.
 */
class ShizukuWifiApproval(
    private val context: Context,
    private val recorderAlive: () -> Boolean,
    /** The recorder's `approveWifiNetworkKeepingSwitch`: an encoded [KeptSwitchResult], null from an older one. */
    private val askRecorder: (knownKeys: Array<String>, salt: String) -> String?,
    /** Wall clock, for the 30-day trust of an approval. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** What one look did: the app-side decision, and the recorder's answer when it was asked. */
    data class Result(val decision: ShizukuNetworkApproval, val recorder: KeptSwitchResult?)

    /** Blocking (a binder call that may wait up to ~3 s in the recorder); call off the main thread. */
    fun approveIfWanted(): Result {
        val prefs = AppPreferences(context)
        val decision = NetworkApprovalPolicy.decideInShizukuMode(
            settingOn = prefs.isWifiNetworkAutoApproveEnabled(),
            shizukuMode = prefs.getPrivilegedMode().needsShizuku,
            recorderAlive = recorderAlive(),
        )
        if (decision != ShizukuNetworkApproval.ASK_RECORDER) {
            log(warn = decision == ShizukuNetworkApproval.RECORDER_DOWN, "skipped: ${describe(decision)}")
            return Result(decision, recorder = null)
        }
        if (!running.compareAndSet(false, true)) {
            log(warn = false, "skipped: an approval is already running")
            return Result(ShizukuNetworkApproval.ALREADY_RUNNING, recorder = null)
        }
        try {
            val known = prefs.getApprovedAccessPointKeys(clock()).toTypedArray()
            val raw = runCatching { askRecorder(known, prefs.getAccessPointKeySalt()) }
                .getOrElse {
                    log(warn = true, "failed: the recorder call threw ${it.javaClass.simpleName}: ${it.message}")
                    return Result(decision, recorder = null)
                }
            val answer = KeptSwitchResult.decode(raw)
            if (answer == null) {
                log(warn = true, "failed: no answer the app understands from the recorder ('${raw.orEmpty().take(MAX_RAW)}'; a recorder from an older version?)")
                return Result(decision, recorder = null)
            }
            if (answer.outcome.trusted) prefs.addApprovedAccessPointKey(answer.bssidKey, clock())
            report(answer)
            return Result(decision, answer)
        } finally {
            running.set(false)
        }
    }

    private fun report(answer: KeptSwitchResult) {
        val ap = answer.maskedBssid.ifEmpty { "?" }
        val (warn, text) = when (answer.outcome) {
            KeptSwitchOutcome.APPROVED_RESTORED_OFF -> false to
                "approved access point $ap (always allow); Wireless debugging restored off after ${answer.restoreMs} ms (undoing the approval's own switch-on)"
            KeptSwitchOutcome.APPROVED_RESTORE_FAILED -> true to
                "approved access point $ap (always allow); FAILED to switch Wireless debugging back off after ${answer.restoreMs} ms, it is left on"
            KeptSwitchOutcome.APPROVED_RESTORE_NOT_CONFIRMED -> true to
                "approval for $ap sent, but Wireless debugging was not seen on within ${answer.restoreMs} ms; nothing written, will try again on the next join"
            KeptSwitchOutcome.APPROVAL_FAILED -> true to "failed: the recorder could not approve $ap"
            KeptSwitchOutcome.ALREADY_APPROVED -> false to
                "skipped: $ap approved within the last 30 days (Android's own trusted list could not be read)"
            KeptSwitchOutcome.ALREADY_TRUSTED_BY_ANDROID -> false to
                "skipped: $ap is already on Android's trusted list for Wireless debugging"
            KeptSwitchOutcome.NOT_TCP_MODE -> false to
                "skipped: Shizuku not in tcp mode (adbd has no tcp port), so switching Wireless debugging back off could stop it; nothing approved or written"
            KeptSwitchOutcome.WIRELESS_DEBUGGING_TURNED_ON_MEANWHILE -> false to
                "skipped: Wireless debugging was switched on just before approving $ap; left on, nothing written"
            KeptSwitchOutcome.WIRELESS_DEBUGGING_ON -> false to
                "skipped: Wireless debugging is on at $ap (Android ignores an approval then)"
            KeptSwitchOutcome.WIRELESS_DEBUGGING_UNREADABLE -> true to
                "skipped: the recorder cannot read Wireless debugging, so it could not be put back"
            KeptSwitchOutcome.NO_WIFI_IDENTITY -> false to "skipped: the recorder sees no Wi-Fi network"
        }
        log(warn, text)
    }

    private fun describe(decision: ShizukuNetworkApproval): String = when (decision) {
        ShizukuNetworkApproval.SETTING_OFF -> "the setting is off"
        ShizukuNetworkApproval.NOT_SHIZUKU_MODE -> "not in Shizuku mode"
        ShizukuNetworkApproval.RECORDER_DOWN -> "the recorder is not running; Android may ask about this network after a restart"
        ShizukuNetworkApproval.ASK_RECORDER -> "asked the recorder"
        ShizukuNetworkApproval.ALREADY_RUNNING -> "an approval is already running"
    }

    private fun log(warn: Boolean, text: String) {
        val line = "Wi-Fi approval (Shizuku): $text"
        if (warn) AppLogger.w(TAG, line) else AppLogger.i(TAG, line)
        runCatching { TransportTimeline.event(context, line) }
            .onFailure { AppLogger.w(TAG, "Could not write the transport timeline: ${it.message}") }
    }

    companion object {
        private const val TAG = "CV:WifiApproveShizuku"
        private const val MAX_RAW = 60

        /** Process-wide: each join builds its own instance, and two must not ask the recorder at once. */
        private val running = AtomicBoolean(false)

        /** Wired to the real recorder connection. */
        fun forApp(context: Context): ShizukuWifiApproval = ShizukuWifiApproval(
            context = context.applicationContext,
            recorderAlive = { RecorderConnection.service?.asBinder()?.isBinderAlive == true },
            askRecorder = { keys, salt -> RecorderConnection.service?.approveWifiNetworkKeepingSwitch(keys, salt) },
        )
    }
}
