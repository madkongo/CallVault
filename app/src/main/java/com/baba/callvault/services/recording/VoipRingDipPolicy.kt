/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * The call-mode dip while an outgoing Telegram / AyuGram call is still ringing (BabApps item 107).
 *
 * Measured on a Galaxy S25 Ultra: the app holds MODE_IN_COMMUNICATION for ~4.8 s at the start of the
 * ring, drops to NORMAL while the other phone is still ringing, and enters call mode again when it is
 * answered (5.9 s later in one log, 10 s in another). The 1.5 s end debounce took the dip for the end
 * of the call: a ~5 s stub was published and the answered call became a second file.
 *
 * The gate is narrow so normal calls are unchanged: only a call that is still young and in which no
 * call-voice player (usage VOICE_COMMUNICATION, "vcPlay") was ever seen. A call that has had a voice
 * player ends as before, after the usual debounce.
 */
object VoipRingDipPolicy {

    /** A call older than this when the mode drops is not a ring-time dip. */
    const val YOUNG_CALL_MS: Long = 15_000L

    /** How long the session stays pending after a ring-time dip, waiting for call mode to return. */
    const val WAIT_FOR_RETURN_MS: Long = 30_000L

    /** The usual end debounce, for every other call. */
    const val NORMAL_END_DEBOUNCE_MS: Long = 1_500L

    /** Whether this mode drop is treated as a possible ring-time dip. */
    fun isRingDip(callAgeMs: Long, voicePlayerEverSeen: Boolean): Boolean =
        !voicePlayerEverSeen && callAgeMs < YOUNG_CALL_MS

    /** How long to wait, from the mode drop, before ending the call. */
    fun endDelayMs(callAgeMs: Long, voicePlayerEverSeen: Boolean): Long =
        if (isRingDip(callAgeMs, voicePlayerEverSeen)) WAIT_FOR_RETURN_MS else NORMAL_END_DEBOUNCE_MS

    /**
     * At the end: was this a call that never connected? True when the end was deferred as a ring dip
     * and still no voice player showed up and the mode never came back. Such a recording is a ring, not
     * a conversation, and is discarded without a warning.
     */
    fun neverConnected(endWasDeferred: Boolean, voicePlayerEverSeen: Boolean): Boolean =
        endWasDeferred && !voicePlayerEverSeen
}
