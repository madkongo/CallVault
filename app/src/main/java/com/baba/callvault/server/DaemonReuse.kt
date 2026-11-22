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
     * (just after boot), when the process is old enough that the hand-over would already have come, or when this
     * process saw its recorder die (review C17): the hand-over is sent once, when our process starts, by the daemon
     * that was running then, and that daemon is the one that died. Waiting for it put a flat 1.5 s in front of the
     * relaunch a ringing call is blocked on.
     */
    fun waitForRedeliveryMs(processAgeMs: Long, uptimeMs: Long, sawRecorderDie: Boolean = false): Long = when {
        sawRecorderDie -> 0L
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

    /**
     * Whether a recorder death was seen by THIS process, i.e. we held a binder and it died. The flag it feeds skips
     * the hand-over wait, so it must not be set by a cleanup that merely found nothing to clean: a restarted app
     * runs `ShizukuBackend.stop()` in standalone mode, holds no binder, and used to set the flag 15 ms after its
     * start, which hid the running daemon's hand-over and let the launcher kill a recorder mid-call (2026-11-22).
     */
    fun recorderDeathEarned(heldABinder: Boolean): Boolean = heldABinder

    /** What the launcher does about daemons it finds before launching its own. */
    enum class StaleAction { KILL_THEN_LAUNCH, LAUNCH, SPARE }

    /**
     * A daemon found while a call is in progress is assumed to be recording it: killing it loses the rest of the
     * call (the staging file stops growing), and launching a second one beside it would put two recorders on the
     * audio input. So with a call going and a daemon present (or the probe failed, [daemonRunning] null) the launcher
     * touches nothing: no kill and no launch. With no call, stale daemons are replaced as always. With a call and
     * no daemon there is nothing to spare, and the launch is the call's only way to be recorded.
     */
    fun staleAction(callInProgress: Boolean, daemonRunning: Boolean?): StaleAction = when {
        !callInProgress -> StaleAction.KILL_THEN_LAUNCH
        daemonRunning == false -> StaleAction.LAUNCH
        else -> StaleAction.SPARE
    }

    /** AudioManager.MODE_IN_CALL (carrier) and MODE_IN_COMMUNICATION (VoIP): the app-visible truth about a live call. */
    fun isCallAudioMode(mode: Int): Boolean = mode == 2 || mode == 3

    /** True when `pgrep` printed at least one pid. */
    fun pgrepFoundDaemon(output: String): Boolean = output.lineSequence().any { line -> line.trim().let { it.isNotEmpty() && it.all(Char::isDigit) } }
}
