/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baba.callvault.R

/**
 * Minutes of the phone's CPU, in the trailing slot of a list row.
 *
 * Shared by the transcript button and the Summaries page rather than copied into each, because both
 * of the things it gets right are things that were got wrong first and are invisible afterwards:
 *
 *  - **Determinate as soon as there is a real number**, indeterminate only until then. A circle that
 *    spins for minutes without filling is indistinguishable from a hang, which is what a long call's
 *    row looked like. The ring filling is the difference between "still working" and "possibly
 *    stuck", read at a glance and with no room for text.
 *  - **Both colours stated, never defaulted.** M3 draws a track behind the moving arc and takes it
 *    from a container role, which in this scheme is CoralDeep — so the row showed a RED ring with a
 *    teal segment sweeping it, reading as an error on work that was going perfectly.
 *
 * @param percent How far through, 0-100. Zero means not known yet, and spins instead.
 */
@Composable
fun WorkProgressRing(percent: Int, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val track = accent.copy(alpha = TRACK_ALPHA)

    if (percent <= 0) {
        CircularProgressIndicator(
            modifier = modifier.size(SPINNER_SIZE),
            color = accent,
            strokeWidth = PROGRESS_STROKE,
            trackColor = track
        )
        return
    }

    // The ring takes the whole slot rather than the 20dp the spinner uses, because it now has to
    // hold a number. At 20dp there is no room for two digits at a legible size; at the full 40dp
    // the reading sits comfortably inside the arc that describes it.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { percent / PERCENT },
            modifier = Modifier.fillMaxSize(),
            color = accent,
            strokeWidth = PROGRESS_STROKE,
            trackColor = track
        )
        Text(
            text = stringResource(R.string.transcribing_pill_percent, percent),
            style = MaterialTheme.typography.labelSmall,
            fontSize = PERCENT_TEXT_SIZE,
            lineHeight = PERCENT_TEXT_SIZE,
            color = accent,
            maxLines = 1
        )
    }
}

private val SPINNER_SIZE = 20.dp
private val PROGRESS_STROKE = 2.dp
private const val PERCENT = 100f
private const val TRACK_ALPHA = 0.20f

/**
 * Small enough for "100%" to fit inside a 40dp ring, large enough to read at arm's length.
 *
 * Below Material's smallest label size on purpose: this is a number inside a glyph-sized control,
 * not body text, and the ring around it already says what kind of number it is.
 */
private val PERCENT_TEXT_SIZE = 9.sp
