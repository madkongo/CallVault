/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * When the app may reuse a recorder daemon that is already running, instead of killing it and launching a new one
 * over ADB (BabApps item 115).
 *
 * The daemon is a detached shell process and outlives the app: after Active apps -> Stop, the daemon was still alive,
 * the next call woke the app, and the app, holding no binder, killed that daemon and launched another over ADB. Away
 * from Wi-Fi that launch cannot happen, so the call was lost with a recorder running the whole time. The daemon now
 * hands its binder to the app again whenever the app's process starts (the pattern Shizuku's `BinderSender` uses), and
 * these are the two decisions around that.
 */
object DaemonReuse {

    /** How long a freshly started app process waits for a live daemon to hand its binder over, before going to ADB. */
    const val REDELIVERY_GRACE_MS = 1_500L

    /** Only a process this young can still be waiting for the hand-over; an older one has had its chance. */
    const val FRESH_PROCESS_MS = 20_000L

    /** Same meaning as the launcher's recent-boot grace: a reboot cleared every process, so no daemon survived it. */
    const val RECENT_BOOT_MS = 90_000L

    /**
     * How long to wait for a live daemon before launching one over ADB. Zero when no daemon can be there to answer
     * (just after boot) or when the process is old enough that the hand-over would already have come.
     */
    fun waitForRedeliveryMs(processAgeMs: Long, uptimeMs: Long): Long = when {
        uptimeMs < RECENT_BOOT_MS -> 0L
        processAgeMs >= FRESH_PROCESS_MS -> 0L
        else -> REDELIVERY_GRACE_MS
    }

    /** Why a handed-over binder is not taken. [ACCEPT] means it is. */
    enum class Verdict { ACCEPT, WRONG_MODE, RECORDING_STOPPED, ALREADY_HELD, STALE_APK }

    /**
     * Whether to take a binder a running daemon handed over on its own (not one we launched).
     *
     * - Only the standalone host: in Shizuku mode a leftover standalone daemon must not become the recorder
     *   (one recorder host at a time).
     * - Not when the user has recording switched off.
     * - Not the one already held: nothing to do, and the daemon-ready hooks must not run twice.
     * - Not a daemon running another APK than the installed one: that one is replaced as before (the launcher
     *   kills it and starts a current one). A daemon that does not say which APK it runs is treated the same.
     */
    fun judgeRedelivery(
        standaloneMode: Boolean,
        recordingStopped: Boolean,
        alreadyHeld: Boolean,
        hostApkPath: String?,
        installedApkPath: String,
    ): Verdict = when {
        !standaloneMode -> Verdict.WRONG_MODE
        recordingStopped -> Verdict.RECORDING_STOPPED
        alreadyHeld -> Verdict.ALREADY_HELD
        hostApkPath != installedApkPath -> Verdict.STALE_APK
        else -> Verdict.ACCEPT
    }
}
