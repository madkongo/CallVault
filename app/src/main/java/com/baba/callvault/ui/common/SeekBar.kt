/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.baba.callvault.R

/**
 * A thin, thumbless progress bar — cleaner than a Slider but still tap- and drag-seekable: tapping
 * or dragging anywhere along it scrubs to that position.
 *
 * Shared rather than copied. It began as the inline player's bar, and when the transcript sheet
 * needed a scrubber the choice was between a second bar that merely resembles this one and one bar
 * used twice. A Material [androidx.compose.material3.Slider] was rejected for both: its thumb is far
 * heavier than this, which is what made an early playback card look like a different app.
 *
 * @param enabled false leaves the track visible but inert, for when nothing is loaded to seek in.
 */
@Composable
fun SeekBar(
    positionMs: Int,
    durationMs: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val total = durationMs.takeIf { it > 0 } ?: 1
    val fraction = (positionMs.toFloat() / total).coerceIn(0f, 1f)
    val widthPx = remember { mutableStateOf(0) }
    fun seekToX(x: Float) {
        val w = widthPx.value
        if (w > 0) onSeek(((x / w).coerceIn(0f, 1f) * total).toInt())
    }
    // Read the same way as the Recordings list's bar (ListSeekBar): "Playback position, 1:12 of 4:23",
    // and adjustable with TalkBack's seek gestures while there is something loaded to seek in.
    val label = stringResource(R.string.home_player_seek)
    val spokenMs = rememberSpokenPositionMs(positionMs)
    val stateText = stringResource(
        R.string.home_player_position_of,
        TranscriptTimestamp.format(spokenMs.toLong()),
        TranscriptTimestamp.format(durationMs.toLong()),
    )
    Box(
        modifier = modifier
            .height(28.dp)
            // focusable: setProgress makes the node scrollable, and TalkBack 17 then treats it as a
            // container and will not land on it ("failed all focusability tests", OP9 2026-10-31).
            // Compose's own Slider is focusable for the same reason.
            .focusable()
            .semantics {
                contentDescription = label
                stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = spokenMs.coerceIn(0, total).toFloat(),
                    range = 0f..SeekSemantics.rangeEndMs(durationMs),
                )
                if (enabled) {
                    setProgress { target ->
                        onSeek(SeekSemantics.positionFor(target, durationMs))
                        true
                    }
                } else {
                    disabled()
                }
            }
            .onSizeChanged { widthPx.value = it.width }
            // Keyed on `enabled`: pointerInput caches its block, so a bar that started disabled would
            // stay deaf after the track loaded if the key never changed.
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { offset -> seekToX(offset.x) }
            }
            .pointerInput(enabled) {
                if (enabled) detectHorizontalDragGestures(
                    onDragStart = { offset -> seekToX(offset.x) },
                    onHorizontalDrag = { change, _ -> seekToX(change.position.x); change.consume() },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                ),
        )
    }
}
