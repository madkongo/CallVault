/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The recording, drawn from its own audio, with the played part filled in.
 *
 * Deliberately not an animation. A bar that moves regardless of what is playing tells the user
 * nothing and implies a measurement nobody took; this one shows where the talking actually is, so a
 * long pause looks like a long pause and a shouting match looks like one.
 *
 * Scrubbing works on it directly — a waveform you can see but not aim at is decoration.
 *
 * @param peaks    one value per bar in 0..1, or empty while it is still being computed.
 * @param progress how far through, 0..1.
 * @param onSeekTo fraction of the recording to jump to.
 * @param stateText what TalkBack says for the position, e.g. "1:12 of 4:23"; null reads a percentage.
 */
@Composable
fun WaveformBar(
    peaks: FloatArray,
    progress: Float,
    onSeekTo: (Float) -> Unit,
    modifier: Modifier = Modifier,
    stateText: String? = null,
) {
    val played = MaterialTheme.colorScheme.primary
    val remaining = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

    // The whole bar is the scrub target, bars or not. The gesture lives on the outer Box rather than on
    // the drawn bars, so the resting line — shown while the shape is still being computed, or when a
    // recording was too damaged to decode and left no peaks — can be tapped and dragged all the same.
    // The waveform is a picture of where to aim; losing the picture must never lose the control. A long
    // call whose shape failed to decode is exactly the case that made this matter — see issue #52.
    // rememberUpdatedState so the keyed-on-Unit detectors always call the current onSeekTo.
    val seek by rememberUpdatedState(onSeekTo)
    val label = stringResource(R.string.home_player_seek)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HEIGHT)
            // An adjustable control to TalkBack, like the other seek bars: the drawn bars are not
            // focusable on their own, so without this the scrubber did not exist for a screen reader.
            .semantics {
                contentDescription = label
                if (stateText != null) stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(SeekSemantics.fractionFor(progress), 0f..1f)
                setProgress { target ->
                    seek(SeekSemantics.fractionFor(target))
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> seek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    seek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (peaks.isEmpty()) {
            // A resting line, never a spinner. The shape has to be read off the audio, which takes a
            // moment on a long call — but a spinner turns that moment into something the user is made
            // to wait for, and it replaces the control rather than filling it in. The line is the same
            // width and in the same place as the bars that arrive, so nothing jumps when they do, and it
            // is just as seekable (the gesture is on the Box above).
            Canvas(modifier = Modifier.fillMaxWidth().height(HEIGHT)) {
                val centre = size.height / 2f
                drawRoundedBar(
                    left = 0f,
                    top = centre - MIN_BAR_PX / 2f,
                    width = size.width,
                    height = MIN_BAR_PX,
                    color = remaining
                )
            }
            return@Box
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(HEIGHT)
        ) {
            val slot = size.width / peaks.size
            val barWidth = max(1f, slot * BAR_FILL)
            val playedBars = (peaks.size * progress.coerceIn(0f, 1f)).roundToInt()
            val centre = size.height / 2f

            peaks.forEachIndexed { index, peak ->
                // A floor, so silence still draws a line: gaps in the bar would read as damage to the
                // recording rather than as quiet.
                val barHeight = max(MIN_BAR_PX, peak * size.height)
                val left = index * slot + (slot - barWidth) / 2f
                drawRoundedBar(
                    left = left,
                    top = centre - barHeight / 2f,
                    width = barWidth,
                    height = barHeight,
                    color = if (index < playedBars) played else remaining
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundedBar(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color
) {
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(left, top),
        size = androidx.compose.ui.geometry.Size(width, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f, width / 2f)
    )
}

private val HEIGHT = 64.dp

/** How much of each bar's slot the bar fills; the rest is the gap that makes them read as bars. */
private const val BAR_FILL = 0.6f

/** Silence still draws something, so a quiet stretch reads as quiet rather than as missing. */
private const val MIN_BAR_PX = 2f
