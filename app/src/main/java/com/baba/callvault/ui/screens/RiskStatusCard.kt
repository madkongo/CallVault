/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.data.RecordingState
import com.baba.callvault.system.health.RiskReason
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvPrimaryButton
import com.baba.callvault.ui.common.CvSecondaryButton
import com.baba.callvault.ui.common.CvStatusPill
import com.baba.callvault.ui.common.CvTone
import com.baba.callvault.ui.theme.LocalCvBrand

/**
 * The status card when everything is set up but the phone itself can stop a call being recorded (R5): the same
 * card as "Ready to record", tinted amber. Names the first reason with one button that opens the right system
 * screen; further reasons sit behind "Show", each with its own fix. [reasons] is never empty.
 */
@Composable
internal fun RiskStatusCard(
    reasons: List<RiskReason>,
    mode: PrivilegedMode,
    onFix: (RiskReason) -> Unit,
    onSetRecordingState: ((RecordingState) -> Unit)?,
) {
    val first = reasons.first()
    val others = reasons.drop(1)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val accent = LocalCvBrand.current.warning
    val tinted = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)

    CvCard(color = tinted, contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_hero_status_label).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.risk_card_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(first.lineTextRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        CvPrimaryButton(stringResource(first.fixTextRes), { onFix(first) })
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (others.size == 1) {
                        stringResource(R.string.risk_more_one, stringResource(others.first().shortTextRes))
                    } else {
                        stringResource(R.string.risk_more_many, others.size, stringResource(others.first().shortTextRes))
                    } + " ·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.risk_hide else R.string.risk_show))
                }
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    others.forEach { reason ->
                        Text(
                            text = stringResource(reason.lineTextRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CvSecondaryButton(stringResource(reason.fixTextRes), { onFix(reason) })
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        CvStatusPill(
            text = stringResource(
                R.string.home_hero_pill_with_mode,
                stringResource(R.string.risk_pill),
                stringResource(if (mode.needsShizuku) R.string.home_mode_badge_shizuku else R.string.home_mode_badge_standalone),
            ),
            tone = CvTone.Warning,
        )
        if (onSetRecordingState != null) {
            Spacer(Modifier.height(14.dp))
            CvSecondaryButton(
                stringResource(R.string.home_recording_stop),
                { onSetRecordingState(RecordingState.STOPPED) },
                leadingIcon = Icons.Filled.StopCircle,
            )
        }
    }
}
