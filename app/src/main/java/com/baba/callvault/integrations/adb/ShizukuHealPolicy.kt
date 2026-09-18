/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/** What to do about a Shizuku server that was answering before CallVault restarted `adbd`. */
enum class ShizukuHeal {
    /** Nothing was running, so there is nothing of ours to put back. */
    NOTHING,

    /** It answers again already — Shizuku's own start-on-boot got there first. Leave it completely alone. */
    ALREADY_BACK,

    /** A recording is live. Say what happened; do not touch ADB until the call is over. */
    TELL_ONLY_RECORDING,

    /** We stopped it and it is still down: start it again. */
    START,
}

/** The one line the user is owed afterwards, id 4718. */
enum class ShizukuHealNotice {
    NONE,

    /** Stopped, and CallVault did not try to start it — the user has to. */
    STOPPED,

    /** Stopped and started again, verified answering. */
    RESTARTED,

    /** Stopped, CallVault tried to start it and could not. Needs starting by hand. */
    COULD_NOT_RESTART,
}

/**
 * Whether CallVault starts somebody else's Shizuku server again after killing it, and what it then says.
 *
 * **Why there is anything to heal.** Shizuku's server is a shell-uid process hosted by `adbd`, so every
 * operation that restarts `adbd` kills it and nothing brings it back — measured on the OP9 as A3/A4 in
 * `docs/dev-notes/2026-09-14-debugging-switches-model.md`. The worst of them is not even a user action:
 * with off-Wi-Fi recording opted in, the recorder launcher re-arms the listener **by itself** whenever it
 * is missing, which every reboot arranges. So a user who turned that on months ago loses Shizuku on every
 * boot, silently. [ShizukuChurnPolicy] made that visible; this puts it back.
 *
 * Kept free of `Context` so every branch is tested.
 */
object ShizukuHealPolicy {

    /**
     * @param wasRunningBefore whether a Shizuku server answered immediately *before* the restart. Only
     *   what we broke gets healed: starting a server nobody had running would be CallVault deciding to
     *   run another app's privileged service uninvited.
     * @param answersNow whether one answers again now, sampled after the restart.
     * @param recordingLive whether the daemon says it is recording right now.
     */
    fun decide(wasRunningBefore: Boolean, answersNow: Boolean, recordingLive: Boolean): ShizukuHeal = when {
        !wasRunningBefore -> ShizukuHeal.NOTHING
        // HAZARD: two privileged hosts at once. A second server started beside one that came back on its
        // own leaves two shell-uid recorder hosts alive, and the app talks to whichever bound last —
        // the class of bug that cost most of 2026-08-24 (memory: only-one-recorder-host). A server that
        // answers is proof there is already one; never add to it.
        answersNow -> ShizukuHeal.ALREADY_BACK
        // HAZARD: healing at the cost of the call. Starting Shizuku means a shell over the embedded ADB
        // connection, and ADB work during a capture is exactly what kills the daemon holding it (the same
        // reason UsbDefaultConfig refuses mid-call). A Shizuku server is two taps; a missed call cannot be
        // recovered. So the user is told it stopped and nothing is touched.
        recordingLive -> ShizukuHeal.TELL_ONLY_RECORDING
        else -> ShizukuHeal.START
    }

    /**
     * What to post, given the decision, whether the server was **verified** answering again, and whether
     * a dialog already warned the user before the restart.
     *
     * A successful heal is always announced, dialog or not: the dialog said Shizuku would stop, which is
     * now only half the story, and leaving that uncorrected would have the user open Shizuku to start
     * something that is already running. A heal that failed is announced for the opposite reason — the
     * dialog warned what *would* happen, and this says what *did*.
     *
     * The silent case is the one the dialog genuinely covers: nothing was attempted, so there is nothing
     * to add, and a notification repeating a dialog the user read seconds ago teaches them that
     * CallVault's warnings are noise.
     */
    fun notice(heal: ShizukuHeal, verifiedRunning: Boolean, underDialog: Boolean): ShizukuHealNotice =
        when (heal) {
            ShizukuHeal.NOTHING, ShizukuHeal.ALREADY_BACK -> ShizukuHealNotice.NONE
            ShizukuHeal.TELL_ONLY_RECORDING ->
                if (underDialog) ShizukuHealNotice.NONE else ShizukuHealNotice.STOPPED
            // Never on the strength of having *run* the starter: a claim about the present needs evidence
            // about the present. The starter exits 0 long before the server is up, and "Shizuku is back"
            // when it is not is the drive-health false positive all over again.
            ShizukuHeal.START ->
                if (verifiedRunning) ShizukuHealNotice.RESTARTED else ShizukuHealNotice.COULD_NOT_RESTART
        }
}
