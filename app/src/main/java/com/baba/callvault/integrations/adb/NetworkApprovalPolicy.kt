/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/** What the Shizuku-mode approval decided before asking the recorder. See [NetworkApprovalPolicy.decideInShizukuMode]. */
enum class ShizukuNetworkApproval {
    ASK_RECORDER,
    SETTING_OFF,
    NOT_SHIZUKU_MODE,
    RECORDER_DOWN,

    /** Another join's approval is still running in this process; not decided by the policy. */
    ALREADY_RUNNING,
}

/**
 * When CallVault approves the current Wi-Fi for Wireless debugging through its recorder, instead of switching
 * Wireless debugging on and leaving Android to ask "Allow wireless debugging on this network?".
 *
 * The approval is made by the shell-uid recorder (`server/WirelessDebuggingApproval`), so it needs that
 * process alive. Once the recorder has died there is no shell left to ask, and the prompt comes back: that is
 * why the approval is also made as soon as the phone joins a network, while the recorder is still running.
 * See docs/dev-notes/2026-10-21-auto-approve-new-wifi-network.md.
 */
object NetworkApprovalPolicy {

    /** The recorder's name for a network it approved; see `server.NetworkApproval`. */
    private const val APPROVED = "APPROVED"

    /**
     * @param gate what [WirelessDebuggingEnableGate] says. Approving switches Wireless debugging on, so it is
     *   allowed only where CallVault would make that write anyway: never against the user's own "off".
     */
    fun mayApprove(
        settingOn: Boolean,
        builtInMode: Boolean,
        recorderAlive: Boolean,
        gate: WirelessDebuggingEnable,
    ): Boolean = settingOn && builtInMode && recorderAlive && gate == WirelessDebuggingEnable.WRITE

    /**
     * Whether to add the network to Android's own trusted list ("Always allow"), or approve this visit only.
     *
     * This visit is enough while Wireless debugging stays on, the default: the next restart of the recorder
     * finds it on. When CallVault switches it off between uses, a once-only approval is gone by then.
     */
    fun alwaysAllow(disableWhenIdle: Boolean): Boolean = disableWhenIdle

    fun approved(answer: String?): Boolean = answer == APPROVED

    // ---- Shizuku mode (review N15) ----
    //
    // Shizuku starts its server after a reboot over Wireless debugging. On an access point Android has not
    // trusted, Android asks "Allow wireless debugging on this network?" and nothing starts until someone taps
    // Allow: no shell process is alive yet to answer (OP9, 2026-11-19). The prevention is to have each access point
    // on Android's trusted list before that reboot, approved when the phone joins it, while the Shizuku-hosted
    // recorder runs. The recorder reads the access point, skips ones already approved, and puts Wireless debugging
    // back the way it found it (`server.KeptSwitchApprover`).

    /** Whether to ask the recorder at all. What the recorder then does is its own, logged, answer. */
    fun decideInShizukuMode(
        settingOn: Boolean,
        shizukuMode: Boolean,
        recorderAlive: Boolean,
    ): ShizukuNetworkApproval = when {
        !settingOn -> ShizukuNetworkApproval.SETTING_OFF
        !shizukuMode -> ShizukuNetworkApproval.NOT_SHIZUKU_MODE
        !recorderAlive -> ShizukuNetworkApproval.RECORDER_DOWN
        else -> ShizukuNetworkApproval.ASK_RECORDER
    }
}
