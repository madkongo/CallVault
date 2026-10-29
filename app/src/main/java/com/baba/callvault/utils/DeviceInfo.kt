/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import android.os.Build

/** Device-family checks that gate device-specific behaviour or user hints. */
object DeviceInfo {

    /**
     * True on vivo and iQOO phones (iQOO is a vivo sub-brand and reports `Build.MANUFACTURER = "vivo"`).
     *
     * Used to tailor the one-sided-app-call hint: on vivo the far side is silenced by the phone until the
     * user turns on vivo's own "In-app call recording" (Recorder app), so vivo users get a message that
     * points them to that toggle instead of the generic "the other app blocks capture" wording.
     *
     * [manufacturer] is a parameter so the mapping is unit-testable without touching [Build].
     */
    fun isVivo(manufacturer: String = Build.MANUFACTURER): Boolean =
        manufacturer.equals("vivo", ignoreCase = true)

    /** Whether this vivo build ships vivo's "In-app call recording" option (Recorder app). */
    enum class VivoInAppRecording { AVAILABLE, ABSENT, UNKNOWN }

    /** vivo's own switch for the option: "true" on builds that have it (China firmware), else false/missing. */
    const val VIVO_VOIP_RECORD_PROP = "persist.vivo.audio.feature.voip_bbkrecord.enable"

    /** "yes" on overseas (non-China) vivo/iQOO firmware. */
    const val VIVO_OVERSEAS_PROP = "ro.vivo.product.overseas"

    /**
     * Decides from the two vivo properties whether the "In-app call recording" option exists.
     *
     * Research 2026-10-28 (firmware code + public getprop dumps, not device-confirmed): the option exists
     * only on China-market firmware, where [VIVO_VOIP_RECORD_PROP] is "true". Overseas builds report it
     * false or not at all, and [VIVO_OVERSEAS_PROP] = "yes".
     *
     * ABSENT needs BOTH signs, so an unreadable property can never tell a phone that has the option that
     * it does not; anything else is UNKNOWN and keeps the existing "turn it on" message.
     */
    fun vivoInAppRecording(voipRecordProp: String, overseasProp: String): VivoInAppRecording {
        val voip = voipRecordProp.trim().lowercase()
        val overseas = overseasProp.trim().lowercase()
        return when {
            voip == "true" -> VivoInAppRecording.AVAILABLE
            (voip.isEmpty() || voip == "false") && overseas == "yes" -> VivoInAppRecording.ABSENT
            else -> VivoInAppRecording.UNKNOWN
        }
    }

    /**
     * True only on a vivo/iQOO phone whose firmware definitely lacks the "In-app call recording" option,
     * so the far side of app calls cannot be recorded there without root. False when unsure.
     */
    fun isVivoWithoutInAppRecording(): Boolean =
        isVivo() && vivoInAppRecording(
            systemProperty(VIVO_VOIP_RECORD_PROP),
            systemProperty(VIVO_OVERSEAS_PROP),
        ) == VivoInAppRecording.ABSENT

    /** Reads a system property via the hidden `SystemProperties.get` (reflection); "" when unreadable. */
    private fun systemProperty(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, key) as? String ?: ""
    }.getOrDefault("")
}
