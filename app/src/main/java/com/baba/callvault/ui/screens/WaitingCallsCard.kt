/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.system.storage.WaitingPolicy
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.theme.LocalCvBrand

/**
 * "N calls waiting to be saved" (review N5): calls recorded while the recording folder was not usable, kept inside
 * CallVault until a folder is chosen. Shown under the status card while any wait. Tapping opens Settings.
 */
@Composable
internal fun WaitingCallsCard(count: Int, bytes: Long, onOpenSettings: () -> Unit) {
    val accent = LocalCvBrand.current.warning
    val tinted = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
    val text = when (WaitingPolicy.level(bytes)) {
        WaitingPolicy.Level.NORMAL -> R.string.waiting_calls_text
        WaitingPolicy.Level.NEARLY_FULL -> R.string.waiting_calls_nearly_full_text
        WaitingPolicy.Level.FULL -> R.string.waiting_calls_full_text
    }
    CvCard(color = tinted, onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = pluralStringResource(R.plurals.waiting_calls_title, count, count),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
