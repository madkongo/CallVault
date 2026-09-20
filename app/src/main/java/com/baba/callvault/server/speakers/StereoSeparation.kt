/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server.speakers

import kotlin.math.log10

/**
 * Whether a two-channel recording really carries two different parties — the gate in front of offline
 * speaker labelling (issue #38).
 *
 * ## Why the gate exists
 *
 * Shizuku recordings are captured by scrcpy as `VOICE_CALL` in `CHANNEL_IN_STEREO` — read out of
 * scrcpy's own bytecode, where `AudioConfig.createAudioFormat()` calls `setChannelMask(12)`. That is
 * the same call our own direct capture makes, and that one measured ~60 dB of channel isolation on the
 * OP12 (2026-09-12 probe: ch0 = the phone owner, ch1 = the far party).
 *
 * **But the file is then encoded at 24 kbps total**, roughly 12 kbps a channel. Low-bit-rate stereo
 * encoders commonly switch to intensity or mid/side coding, which deliberately throws channel
 * differences away. So a separated *capture* does not prove a separated *file*, and nobody has measured
 * the delivered file on any device yet.
 *
 * Rather than guess per device, per codec and per bit rate, every caller asks this first. Where the
 * channels were collapsed it answers [MIXED], nothing is labelled, and the feature is simply absent —
 * which is exactly where it stands today.
 *
 * ## The measure
 *
 * Mid is `0.5*(L+R)`, side is `0.5*(L−R)`. Two independent talkers put comparable energy in both. One
 * signal copied to both channels cancels in the side, leaving it far quieter. The 6 dB threshold is the
 * one the sibling project already uses for this decision (`AIDashboard`'s `isStereoSeparated()`), so the
 * two agree about a given file.
 *
 * Free of Android types so every branch is tested.
 */
enum class StereoSeparation {
    /** Two different sources. The channels may be trusted to say who spoke. */
    SEPARATED,

    /** One signal on both channels — a mono source, or an encoder that collapsed them. */
    MIXED,

    /** Too quiet to tell. Never treated as [MIXED]. */
    UNKNOWN;

    /**
     * Whether speaker labelling may run. Only a **proven** separation.
     *
     * A wrong label is worse than no label: no label is a missing feature, a wrong one is the app
     * telling the user the far party said something they said themselves.
     */
    val mayLabelSpeakers: Boolean get() = this == SEPARATED

    companion object {
        /** Within this much of the mid channel, the side channel is carrying a second voice. */
        private const val SEPARATION_WINDOW_DB = 6.0

        /**
         * Below this, the file is too quiet for the comparison to mean anything. Digital silence and a
         * far channel of −99…−120 dB both land here; the 2026-09-12 probe saw exactly that on the
         * silent half of a VoIP call.
         */
        private const val SILENCE_FLOOR_DB = -90.0

        /**
         * @param sumDb energy of the mid channel `0.5*(L+R)`, in dBFS.
         * @param diffDb energy of the side channel `0.5*(L−R)`, in dBFS.
         */
        fun of(sumDb: Double, diffDb: Double): StereoSeparation = when {
            // Nothing audible in EITHER channel. Checking only the mid was wrong and a test caught it:
            // two perfectly anti-phase channels cancel in the mid, so the most separated signal there
            // is read as "too quiet to tell". Saying MIXED here would be the same mistake as reading an
            // unreadable setting as "off" — see UsbDebuggingPolicy for the same rule.
            maxOf(sumDb, diffDb) < SILENCE_FLOOR_DB -> UNKNOWN
            // A side channel at or above (mid − 6 dB) means two sources. Louder than the mid is still
            // two sources: it just means they rarely talked over each other.
            diffDb >= sumDb - SEPARATION_WINDOW_DB -> SEPARATED
            else -> MIXED
        }

        /** RMS (0..1, full scale) to dBFS. Digital silence is −∞ rather than an exception or a zero. */
        fun dbOf(rms: Double): Double = if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms)
    }
}
