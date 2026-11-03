/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager

/**
 * What to do with an app-call recording when the audio mode changes.
 *
 * Extracted from [VoipCallDetector] because it is the logic that decides whether one conversation
 * becomes one recording or two, it has four states and three modes, and none of it can be exercised
 * through a real [AudioManager]. Same shape as [VoipTelephonyGate] beside it.
 *
 * The case that drove it: an app call is running, a phone call is answered, the user takes it and
 * then goes back. Before this, the app call was ended and a second one started on return — two files
 * for one conversation.
 */
object VoipSwitchPolicy {

    enum class Action {
        /** No app call is up and one has appeared. */
        START,

        /** A held app call is audible again — continue into the same file. */
        RESUME,

        /** A phone call is still up. Wait; do not end anything. */
        HOLD,

        /** The app call is over. Finalise the recording. */
        END,

        /**
         * An app call is up and the mode still says so. Cancel any end that is pending: the mode
         * dipped and came back, which Telegram does for about a second while a call connects.
         */
        STAY,

        /** Nothing to do. */
        NOTHING,
    }

    /**
     * @param mode                the current [AudioManager] mode.
     * @param callActive          whether an app call is believed to be up (a HELD one still counts).
     * @param suspendedForCarrier whether that app call is currently held open across a phone call.
     */
    fun decide(mode: Int, callActive: Boolean, suspendedForCarrier: Boolean): Action {
        val inVoipCall = mode == AudioManager.MODE_IN_COMMUNICATION

        // Before START, always. The same event means "resume" when a call was held and "a new call"
        // when it was not, and testing START first is exactly how one conversation became two files.
        if (inVoipCall && callActive && suspendedForCarrier) return Action.RESUME

        // A phone call is up. The mode reads IN_CALL whether the app call is still holding or the
        // user hung it up while on the phone, and those cannot be told apart from here — only from
        // what the mode becomes next. Waiting is the only answer that is not a guess.
        if (suspendedForCarrier && mode == AudioManager.MODE_IN_CALL) return Action.HOLD

        if (inVoipCall && !callActive) return Action.START
        if (!inVoipCall && callActive) return Action.END
        if (inVoipCall && callActive) return Action.STAY
        return Action.NOTHING
    }

    /**
     * Asked before acting on a START: does the mode as it is NOW still say an app call is up?
     *
     * The listener hands over the mode it was posted with, and it runs later on the main thread. On a
     * POCO X5 Pro (#68) WhatsApp held MODE_IN_COMMUNICATION for 80 ms; by the time the START ran the mode
     * was back to normal, and a call that was already over got recorded.
     */
    fun stillStarting(liveMode: Int, callActive: Boolean, suspendedForCarrier: Boolean): Boolean =
        decide(liveMode, callActive, suspendedForCarrier) == Action.START

    /**
     * Asked again when the end debounce runs out: does the mode still say the app call is over?
     *
     * The debounce exists for a mode that wobbles, so it must look at the mode as it is NOW, not as
     * it was when the end was scheduled. Without this a 0.7 s Telegram dip ended the recording while
     * the call went on (2026-11-01, OnePlus CPH2747, Android 17).
     */
    fun stillEnded(mode: Int, callActive: Boolean, suspendedForCarrier: Boolean): Boolean =
        decide(mode, callActive, suspendedForCarrier) == Action.END
}
