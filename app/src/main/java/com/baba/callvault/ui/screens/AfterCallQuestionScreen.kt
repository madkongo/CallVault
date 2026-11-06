/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R

/** How much the screen behind the pop-up is dimmed: enough to read as "a question", not enough to hide it. */
private const val SCRIM_ALPHA = 0.32f
private val CARD_MAX_WIDTH = 400.dp

/**
 * The keep-or-delete pop-up's content: "Keep this recording?", who and how long, and Delete / Keep.
 *
 * Every colour is stated: this theme's M3 defaults render coral, which would make Keep look like a warning.
 * Delete is the outlined error-coloured action and Keep the filled primary one, so the safe answer is the
 * one that stands out. A tap on the dimmed area outside the card closes the window without answering.
 */
@Composable
internal fun AfterCallQuestionScreen(
    subtitle: String,
    isAnswering: Boolean,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface, contentColor = colors.onSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = CARD_MAX_WIDTH)
                .fillMaxWidth()
                // Swallows taps on the card itself, so only the dimmed area outside closes the window.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.after_call_popup_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = !isAnswering,
                        border = BorderStroke(1.dp, colors.error),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = colors.error,
                            disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
                        ),
                    ) { Text(stringResource(R.string.after_call_delete)) }
                    Button(
                        onClick = onKeep,
                        enabled = !isAnswering,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary,
                            disabledContainerColor = colors.onSurface.copy(alpha = 0.12f),
                            disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
                        ),
                    ) { Text(stringResource(R.string.after_call_keep)) }
                }
            }
        }
    }
}
