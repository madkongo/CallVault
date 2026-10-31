/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.focusable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The seek bar along the bottom of the row that is playing on the Recordings list.
 *
 * It looks thin (a 4dp track) but takes touches across [TOUCH_HEIGHT], and anywhere along it, not
 * only on the dot. A tap jumps straight there. A drag shows where it will land, in a bubble above
 * the dot and through [onPreview], while the audio carries on; the jump happens on release, so
 * scrubbing does not stutter through every second in between.
 *
 * Not [SeekBar]: that one seeks on every move of the finger, which suits the transcript sheet's
 * scrubber but makes a drag across a long call audibly choppy.
 *
 * @param onPreview the position being dragged to, or null when no drag is in progress.
 * @param formatTime turns milliseconds into the clock text shown in the bubble.
 */
@Composable
fun ListSeekBar(
    positionMs: Int,
    durationMs: Int,
    label: String,
    stateText: String,
    onSeek: (Int) -> Unit,
    onPreview: (Int?) -> Unit,
    formatTime: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var widthPx by remember { mutableIntStateOf(0) }
    var dragMs by remember { mutableStateOf<Int?>(null) }
    // The gesture blocks are installed once; these keep them reading the current values.
    val duration by rememberUpdatedState(durationMs)
    val seek by rememberUpdatedState(onSeek)
    val preview by rememberUpdatedState(onPreview)

    fun at(x: Float) = ListPlayback.positionAt(x, widthPx, duration, rtl)
    fun drag(ms: Int?) {
        dragMs = ms
        preview(ms)
    }

    val shownMs = dragMs ?: positionMs
    val fraction = ListPlayback.fraction(shownMs, durationMs)
    val dragging = dragMs != null
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TOUCH_HEIGHT)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) { detectTapGestures { seek(at(it.x)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag(at(it.x)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        drag(at(change.position.x))
                    },
                    onDragEnd = {
                        dragMs?.let { seek(it) }
                        drag(null)
                    },
                    onDragCancel = { drag(null) },
                )
            }
            // focusable: setProgress makes the node scrollable, and TalkBack 17 then treats it as a
            // container and will not land on it ("failed all focusability tests", OP9 2026-10-31).
            // Compose's own Slider is focusable for the same reason.
            .focusable()
            .semantics {
                contentDescription = label
                stateDescription = stateText
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = positionMs.toFloat(),
                    range = 0f..durationMs.coerceAtLeast(1).toFloat(),
                )
                setProgress { target ->
                    seek(target.roundToInt().coerceIn(0, duration))
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            val startX = if (rtl) size.width else 0f
            val endX = if (rtl) 0f else size.width
            val thumbX = startX + (endX - startX) * fraction
            val stroke = TRACK_HEIGHT.toPx()
            drawLine(track, Offset(startX, y), Offset(endX, y), stroke, StrokeCap.Round)
            drawLine(primary, Offset(startX, y), Offset(thumbX, y), stroke, StrokeCap.Round)
            if (dragging) drawCircle(primary.copy(alpha = HALO_ALPHA), HALO_RADIUS.toPx(), Offset(thumbX, y))
            val thumb = if (dragging) THUMB_RADIUS_DRAGGING else THUMB_RADIUS
            drawCircle(primary, thumb.toPx(), Offset(thumbX, y))
        }
        if (dragging) {
            TimeBubble(
                text = formatTime(shownMs),
                fraction = if (rtl) 1f - fraction else fraction,
                widthPx = widthPx,
            )
        }
    }
}

/** The "2:40" above the dot while dragging, kept inside the bar's ends so it is never cut off. */
@Composable
private fun TimeBubble(text: String, fraction: Float, widthPx: Int) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Int.MAX_VALUE))
                layout(0, 0) {
                    val centre = (widthPx * fraction).roundToInt()
                    val x = (centre - placeable.width / 2).coerceIn(0, (widthPx - placeable.width).coerceAtLeast(0))
                    // This zero-size box sits at the bar's centre, so x is shifted back to the left
                    // edge. place(), not placeRelative(): x is already mirrored for right-to-left.
                    placeable.place(x - widthPx / 2, -placeable.height - BUBBLE_GAP.roundToPx())
                }
            }
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private val TOUCH_HEIGHT = 44.dp
private val TRACK_HEIGHT = 4.dp
private val THUMB_RADIUS = 7.dp
private val THUMB_RADIUS_DRAGGING = 10.dp
private val HALO_RADIUS = 18.dp
private const val HALO_ALPHA = 0.22f
/** Clears the enlarged dot, so the bubble sits just above it rather than on it. */
private val BUBBLE_GAP = 14.dp
