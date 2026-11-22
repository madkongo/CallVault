/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.data.RecordingState
import com.baba.callvault.services.recording.SharedStatusNotice
import com.baba.callvault.ui.theme.LocalCvBrand
import com.baba.callvault.ui.viewmodels.HomeViewModel.HomeStatus
import kotlinx.coroutines.delay

private const val DOT_CYCLE_MS = 1400
private const val BAR_CYCLE_MS = 1100
private const val BAR_STILL_SCALE = 0.25f
private const val BAR_COUNT = 6
private const val TIMER_TICK_MS = 1000L
private val BAR_DELAYS_MS = intArrayOf(0, 150, 300, 450, 600, 250)

/**
 * Home's status card while a call is being recorded ("B · RECORDING RED"): a blinking red dot, the title, a subtitle
 * with the running time, and decorative bars. [status] must be RECORDING or RECORDING_PAUSED; paused keeps the same
 * card with a solid dot and still bars. A tap does what the normal hero card's tap does ([onClick]). The mode pill and
 * the master Stop control stay on the card, as on the normal hero.
 */
@Composable
fun RecordingStatusCard(
    status: HomeStatus,
    mode: PrivilegedMode,
    onClick: (() -> Unit)?,
    onSetRecordingState: ((RecordingState) -> Unit)? = null,
) {
    val brand = LocalCvBrand.current
    val context = LocalContext.current
    // Read on every composition (a cheap settings read) so a changed "remove animations" setting shows up at once.
    val animationsOn = animationsEnabled(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    val style = recordingCardStyle(status, animationsOn) ?: return
    val elapsedMs by recordingElapsedMs(running = status == HomeStatus.RECORDING)
    val elapsed = formatRecordingElapsed(elapsedMs)
    val title = stringResource(status.titleResId)
    // Paused: just the frozen time under the title; the long "nothing is being saved" sentence wrapped.
    val subtitle = if (status == HomeStatus.RECORDING) stringResource(status.suggestionResId) + " · " + elapsed else elapsed
    val description = "$title, $elapsed"

    CvCard(
        color = brand.recordingContainer,
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
    ) {
        // The description covers the header only, so the Stop control below keeps its own semantics.
        Row(
            Modifier.semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(brand.recording.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                RecordingDot(blink = style.blinkDot)
            }
            Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = brand.onRecordingMuted)
            }
            RecordingBars(animate = style.animateBars)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CvStatusPill(
                text = stringResource(if (mode.needsShizuku) R.string.home_mode_badge_shizuku else R.string.home_mode_badge_standalone),
                tone = CvTone.Info,
            )
            Spacer(Modifier.weight(1f))
            if (onSetRecordingState != null) {
                Surface(
                    onClick = { onSetRecordingState(RecordingState.STOPPED) },
                    shape = CircleShape,
                    color = brand.recording.copy(alpha = 0.16f),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.StopCircle, contentDescription = null, tint = brand.recording, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_recording_stop), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingDot(blink: Boolean) {
    val alpha = if (blink) {
        val t = rememberInfiniteTransition(label = "recDot")
        val a by t.animateFloat(
            initialValue = 1f, targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(DOT_CYCLE_MS / 2, easing = LinearEasing), RepeatMode.Reverse),
            label = "recDotAlpha",
        )
        a
    } else 1f
    Box(Modifier.size(14.dp).alpha(alpha).clip(CircleShape).background(LocalCvBrand.current.recording))
}

@Composable
private fun RecordingBars(animate: Boolean) {
    val color = LocalCvBrand.current.recording
    val transition = if (animate) rememberInfiniteTransition(label = "recBars") else null
    Row(Modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(BAR_COUNT) { i ->
            val scale = transition?.animateFloat(
                initialValue = BAR_STILL_SCALE, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(BAR_CYCLE_MS / 2, easing = LinearEasing),
                    RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(BAR_DELAYS_MS[i]),
                ),
                label = "recBar$i",
            )?.value ?: BAR_STILL_SCALE
            Box(
                Modifier.width(3.dp).fillMaxHeight().graphicsLayer { scaleY = scale }
                    .clip(RoundedCornerShape(2.dp)).background(color)
            )
        }
    }
}

/** The recording's running time, re-read every second while [running]; frozen (one read) while paused. */
@Composable
private fun recordingElapsedMs(running: Boolean) = produceState(SharedStatusNotice.liveElapsedMs(SystemClock.elapsedRealtime()), running) {
    value = SharedStatusNotice.liveElapsedMs(SystemClock.elapsedRealtime())
    while (running) {
        delay(TIMER_TICK_MS)
        value = SharedStatusNotice.liveElapsedMs(SystemClock.elapsedRealtime())
    }
}
