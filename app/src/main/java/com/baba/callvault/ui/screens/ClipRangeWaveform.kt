/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

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

    val startLabel = stringResource(R.string.share_clip_handle_start, ClipRange.clock(range.startMs))
    val endLabel = stringResource(R.string.share_clip_handle_end, ClipRange.clock(range.endMs))
    val earlier = stringResource(R.string.share_clip_nudge_earlier)
    val later = stringResource(R.string.share_clip_nudge_later)

    fun msAt(x: Float, width: Float): Long =
        if (width <= 0f || durationMs <= 0) 0L else ((x / width).coerceIn(0f, 1f) * durationMs).toLong()

    fun snapped(ms: Long, width: Float): Long {
        val near = flags.minByOrNull { abs(it - ms) } ?: return ms
        val distancePx = abs(near - ms).toFloat() / durationMs.coerceAtLeast(1) * width
        return if (distancePx <= snapPx) near else ms
    }

    fun move(edge: ClipEdge, ms: Long) {
        change(
            if (edge == ClipEdge.START) current.withStart(ms, durationMs) else current.withEnd(ms, durationMs),
        )
    }

    Box(
        modifier = modifier
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
            val total = durationMs.coerceAtLeast(1).toFloat()
            val x0 = range.startMs / total * w
            val x1 = range.endMs / total * w

            drawRoundRect(shade, topLeft = Offset(x0, 0f), size = Size(max(1f, x1 - x0), h), cornerRadius = CornerRadius(6.dp.toPx()))

            if (peaks.isNotEmpty()) {
                val slot = w / peaks.size
                val barWidth = max(1f, slot * 0.6f)
                peaks.forEachIndexed { i, p ->
                    val barH = max(2.dp.toPx(), p.coerceIn(0f, 1f) * (h - 12.dp.toPx()))
                    val left = i * slot + (slot - barWidth) / 2f
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

            flags.forEach { at ->
                val x = at / total * w
                val tri = 5.dp.toPx()
                drawPath(Path().apply { moveTo(x - tri, 0f); lineTo(x + tri, 0f); lineTo(x, tri * 1.6f); close() }, flagColor)
            }

            playheadMs?.let { at ->
                val x = at / total * w
                drawRect(playhead, topLeft = Offset(x - 1.dp.toPx() / 2, 0f), size = Size(1.dp.toPx(), h))
            }

            listOf(x0, x1).forEach { x ->
                val bar = 3.dp.toPx()
                drawRoundRect(handle, topLeft = Offset(x - bar / 2, 0f), size = Size(bar, h), cornerRadius = CornerRadius(bar))
                drawCircle(handle, radius = 8.dp.toPx(), center = Offset(x, h - 8.dp.toPx()))
            }
        }
    }
}

private val HEIGHT = 96.dp
private val SNAP = 12.dp
private const val NUDGE_MS = 1_000L
