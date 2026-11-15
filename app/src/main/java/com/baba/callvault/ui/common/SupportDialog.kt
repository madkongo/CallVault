/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.system.openKofi
import com.baba.callvault.system.openPayPal

/**
 * Where to donate, asked once rather than assumed.
 *
 * Support used to open Ko-fi the moment it was tapped. The links are not interchangeable: Ko-fi's
 * card processing is unavailable or awkward in some countries, many people already hold a PayPal
 * balance. Both open in the browser — no payment SDK is in the app, and nothing about the user leaves the device.
 *
 * One Donate button opens a short menu, so a fourth option never needs a wider row.
 *
 * **One dialog, wherever it is opened from.** An earlier version showed a shorter, businesslike
 * variant when the user tapped Support and the fuller note only after a release, on the reasoning
 * that someone who went looking needs a destination rather than a pitch. Two dialogs saying the same
 * thing in two voices is worse than one: whichever route you arrive by, the reason for asking is the
 * same, and the person tapping Support deliberately is the one most likely to want to read it.
 */
@Composable
fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.support_appeal_title)) },
        text = { Text(stringResource(R.string.support_appeal_body)) },
        confirmButton = {
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text(stringResource(R.string.support_donate))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    SUPPORT_OPTIONS.forEach { option ->
                        val label = stringResource(
                            when (option) {
                                SupportOption.KOFI -> R.string.support_kofi
                                SupportOption.PAYPAL -> R.string.support_paypal
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuOpen = false
                                onDismiss()
                                when (option) {
                                    SupportOption.KOFI -> context.openKofi()
                                    SupportOption.PAYPAL -> context.openPayPal()
                                }
                            },
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.support_not_now)) }
        },
    )
}
