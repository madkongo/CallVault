/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import com.baba.callvault.ui.viewmodels.HomeViewModel.HomeStatus
import java.util.Locale

/** How Home's red recording card moves. The look is the same for both states; only the motion differs. */
data class RecordingCardStyle(val blinkDot: Boolean, val animateBars: Boolean)

/** Null for every status that is not a live recording (those keep the normal hero card). */
fun recordingCardStyle(status: HomeStatus, animationsEnabled: Boolean): RecordingCardStyle? = when (status) {
    HomeStatus.RECORDING -> RecordingCardStyle(blinkDot = animationsEnabled, animateBars = animationsEnabled)
    HomeStatus.RECORDING_PAUSED -> RecordingCardStyle(blinkDot = false, animateBars = false)
    else -> null
}

/** The system's "remove animations" switch sets the animator duration scale to 0. */
fun animationsEnabled(animatorDurationScale: Float): Boolean = animatorDurationScale > 0f

/** "02:41" under an hour, "1:02:41" beyond it. Latin digits, like the call durations elsewhere in the app. */
fun formatRecordingElapsed(elapsedMs: Long): String {
    val total = (elapsedMs.coerceAtLeast(0L)) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}
