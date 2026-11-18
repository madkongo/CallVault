/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.baba.callvault.system.USDT_TON_ADDRESS
import com.baba.callvault.system.openBuyMeACoffee
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
    var cryptoOpen by remember { mutableStateOf(false) }

    if (cryptoOpen) {
        UsdtTonDialog(onDismiss = onDismiss)
        return
    }

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
                                SupportOption.BUYMEACOFFEE -> R.string.support_buymeacoffee
                                SupportOption.USDT_TON -> R.string.support_usdt_ton
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuOpen = false
                                when (option) {
                                    SupportOption.USDT_TON -> cryptoOpen = true
                                    else -> {
                                        onDismiss()
                                        when (option) {
                                            SupportOption.KOFI -> context.openKofi()
                                            SupportOption.PAYPAL -> context.openPayPal()
                                            SupportOption.BUYMEACOFFEE -> context.openBuyMeACoffee()
                                            SupportOption.USDT_TON -> Unit
                                        }
                                    }
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

/**
 * The wallet for USDT on the TON network. Money sent on another network (TRC20, ERC20) is lost, so
 * the network is the first thing said, then the address in full (selectable, monospace) and a Copy
 * button. Nothing here touches the network. No QR code: it would need a new library.
 */
@Composable
private fun UsdtTonDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.support_crypto_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.support_crypto_title)) },
        text = {
            Column {
                Text(stringResource(R.string.support_crypto_warning))
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    Text(
                        text = USDT_TON_ADDRESS,
                        fontFamily = FontFamily.Monospace,
                        // An address is never right-to-left text: in an RTL locale the trailing "-" would
                        // jump to the line start and the address would read wrongly.
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                        modifier = Modifier.fillMaxWidth().testTag("usdt_ton_address"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("USDT TON", USDT_TON_ADDRESS))
                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.support_crypto_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_close)) }
        },
    )
}
