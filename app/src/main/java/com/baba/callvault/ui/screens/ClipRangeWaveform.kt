/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.clips.ClipRange
import kotlin.math.abs
import kotlin.math.max

/** Which end of the selection a drag is moving. */
private enum class ClipEdge { START, END }

/** The stretch of the call the waveform shows, in ms. */
internal data class ClipView(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(1)

    companion object {
        /** Up to this long, the whole call is shown; longer calls zoom in on the part. */
        const val WHOLE_UP_TO_MS = 3 * 60_000L
        private const val MIN_VIEW_MS = 60_000L
        private const val CONTEXT_FACTOR = 3

        /**
         * What to show for [range]: the whole call when it is short, otherwise the part with as much again on each
         * side (at least a minute) — 30 s of a 57-minute call was 1% of the width, and the two handles sat on top of
         * each other (OP9, 2026-11-04).
         */
        fun around(range: ClipRange, durationMs: Long): ClipView {
            if (durationMs <= WHOLE_UP_TO_MS) return ClipView(0, durationMs.coerceAtLeast(1))
            val length = (range.lengthMs * CONTEXT_FACTOR).coerceIn(MIN_VIEW_MS, durationMs)
            val centre = (range.startMs + range.endMs) / 2
            val start = (centre - length / 2).coerceIn(0, durationMs - length)
            return ClipView(start, start + length)
        }
    }
}

/**
 * The recording's waveform with the part to share highlighted between two handles.
 *
 * Dragging anywhere moves whichever handle is nearer the finger, so a handle never has to be hit exactly. A handle
 * dropped near a marked moment snaps to it. TalkBack users get the times read out and "a second earlier / later"
 * actions on each end, since dragging is not available to them.
 */
@Composable
internal fun ClipRangeWaveform(
    peaks: FloatArray,
    durationMs: Long,
    range: ClipRange,
    /** Marked moments, ms into the call; handles snap to them. */
    flags: List<Long>,
    /** Where playback is, or null when this recording is not playing. */
    playheadMs: Long?,
    onRangeChange: (ClipRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = MaterialTheme.colorScheme.primary
    val unselected = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val shade = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    val handle = MaterialTheme.colorScheme.primary
    val flagColor = MaterialTheme.colorScheme.tertiary
    val playhead = MaterialTheme.colorScheme.onSurface

    val current by rememberUpdatedState(range)
    val change by rememberUpdatedState(onRangeChange)
    val snapPx = with(LocalDensity.current) { SNAP.toPx() }
    var dragging by remember { mutableStateOf<ClipEdge?>(null) }
    // Re-fitted whenever the user is not dragging, so the view never moves under a finger.
    var view by remember { mutableStateOf(ClipView.around(range, durationMs)) }
    LaunchedEffect(range, durationMs, dragging) {
        if (dragging == null) view = ClipView.around(range, durationMs)
    }
    val viewNow by rememberUpdatedState(view)

    val startLabel = stringResource(R.string.share_clip_handle_start, ClipRange.clock(range.startMs))
    val endLabel = stringResource(R.string.share_clip_handle_end, ClipRange.clock(range.endMs))
    val earlier = stringResource(R.string.share_clip_nudge_earlier)
    val later = stringResource(R.string.share_clip_nudge_later)

    fun msAt(x: Float, width: Float): Long =
        if (width <= 0f || durationMs <= 0) 0L
        else viewNow.startMs + ((x / width).coerceIn(0f, 1f) * viewNow.lengthMs).toLong()

    fun snapped(ms: Long, width: Float): Long {
        val near = flags.minByOrNull { abs(it - ms) } ?: return ms
        val distancePx = abs(near - ms).toFloat() / viewNow.lengthMs * width
        return if (distancePx <= snapPx) near else ms
    }

    fun move(edge: ClipEdge, ms: Long) {
        change(
            if (edge == ClipEdge.START) current.withStart(ms, durationMs) else current.withEnd(ms, durationMs),
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HEIGHT)
            .semantics {
                contentDescription = "$startLabel. $endLabel"
                customActions = listOf(
                    CustomAccessibilityAction("$startLabel: $earlier") { move(ClipEdge.START, current.startMs - NUDGE_MS); true },
                    CustomAccessibilityAction("$startLabel: $later") { move(ClipEdge.START, current.startMs + NUDGE_MS); true },
                    CustomAccessibilityAction("$endLabel: $earlier") { move(ClipEdge.END, current.endMs - NUDGE_MS); true },
                    CustomAccessibilityAction("$endLabel: $later") { move(ClipEdge.END, current.endMs + NUDGE_MS); true },
                )
            }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { at ->
                        val ms = msAt(at.x, size.width.toFloat())
                        dragging = if (abs(ms - current.startMs) <= abs(ms - current.endMs)) ClipEdge.START else ClipEdge.END
                    },
                    onDragEnd = {
                        val edge = dragging
                        if (edge != null) {
                            val w = size.width.toFloat()
                            val at = if (edge == ClipEdge.START) current.startMs else current.endMs
                            move(edge, snapped(at, w))
                        }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                ) { change, _ ->
                    change.consume()
                    dragging?.let { move(it, msAt(change.position.x, size.width.toFloat())) }
                }
            }
            .pointerInput(durationMs) {
                // A tap moves the nearer end there too, so a quick adjustment needs no drag.
                detectTapGestures { at ->
                    val w = size.width.toFloat()
                    val ms = snapped(msAt(at.x, w), w)
                    move(if (abs(ms - current.startMs) <= abs(ms - current.endMs)) ClipEdge.START else ClipEdge.END, ms)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(HEIGHT)) {
            val w = size.width
            val h = size.height
            val span = view.lengthMs.toFloat()
            fun xOf(ms: Long) = (ms - view.startMs) / span * w
            val x0 = xOf(range.startMs).coerceIn(0f, w)
            val x1 = xOf(range.endMs).coerceIn(0f, w)

            drawRoundRect(shade, topLeft = Offset(x0, 0f), size = Size(max(1f, x1 - x0), h), cornerRadius = CornerRadius(6.dp.toPx()))

            // Thin bars at a fixed spacing, each reading the stored shape at its own time. The shape of a long call
            // is coarse (one value per ~15 s on a 57-minute call), and stretching its few values across a zoomed view
            // drew a handful of blobs (OP9, 2026-11-04).
            if (peaks.isNotEmpty()) {
                val total = durationMs.coerceAtLeast(1).toFloat()
                val slot = BAR_SLOT.toPx()
                val count = (w / slot).toInt().coerceAtLeast(1)
                val barWidth = max(1f, slot * 0.6f)
                for (i in 0 until count) {
                    val atMs = view.startMs + (i + 0.5f) / count * view.lengthMs
                    val p = peaks[((atMs / total) * peaks.size).toInt().coerceIn(0, peaks.size - 1)]
                    val barH = max(2.dp.toPx(), p.coerceIn(0f, 1f) * (h - 12.dp.toPx()))
                    val left = i * (w / count) + (w / count - barWidth) / 2f
                    val inside = left + barWidth / 2f in x0..x1
                    drawRoundRect(
                        color = if (inside) selected else unselected,
                        topLeft = Offset(left, (h - barH) / 2f),
                        size = Size(barWidth, barH),
                        cornerRadius = CornerRadius(barWidth / 2f),
                    )
                }
            } else {
                drawRect(unselected, topLeft = Offset(0f, h / 2f - 1.dp.toPx()), size = Size(w, 2.dp.toPx()))
            }

            flags.filter { it in view.startMs..view.endMs }.forEach { at ->
                val x = xOf(at)
                val tri = 5.dp.toPx()
                drawPath(Path().apply { moveTo(x - tri, 0f); lineTo(x + tri, 0f); lineTo(x, tri * 1.6f); close() }, flagColor)
            }

            playheadMs?.takeIf { it in view.startMs..view.endMs }?.let { at ->
                val x = xOf(at)
                drawRect(playhead, topLeft = Offset(x - 1.dp.toPx() / 2, 0f), size = Size(1.dp.toPx(), h))
            }

            listOf(x0, x1).forEach { x ->
                val bar = 3.dp.toPx()
                drawRoundRect(handle, topLeft = Offset(x - bar / 2, 0f), size = Size(bar, h), cornerRadius = CornerRadius(bar))
                drawCircle(handle, radius = 8.dp.toPx(), center = Offset(x, h - 8.dp.toPx()))
            }
        }
    }
    // Zoomed in: a strip of the whole call under it, showing which stretch is in view and where the part is.
    if (durationMs > ClipView.WHOLE_UP_TO_MS) {
        val track = MaterialTheme.colorScheme.outlineVariant
        Canvas(modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(OVERVIEW_HEIGHT)) {
            val w = size.width
            val h = size.height
            val total = durationMs.toFloat()
            drawRoundRect(track, size = Size(w, h), cornerRadius = CornerRadius(h / 2))
            drawRoundRect(
                shade,
                topLeft = Offset(view.startMs / total * w, 0f),
                size = Size(max(2.dp.toPx(), view.lengthMs / total * w), h),
                cornerRadius = CornerRadius(h / 2),
            )
            drawRoundRect(
                handle,
                topLeft = Offset(range.startMs / total * w, 0f),
                size = Size(max(3.dp.toPx(), range.lengthMs / total * w), h),
                cornerRadius = CornerRadius(h / 2),
            )
        }
    }
    }
}

private val HEIGHT = 96.dp
private val OVERVIEW_HEIGHT = 6.dp
private val BAR_SLOT = 5.dp
private val SNAP = 12.dp
private const val NUDGE_MS = 1_000L
