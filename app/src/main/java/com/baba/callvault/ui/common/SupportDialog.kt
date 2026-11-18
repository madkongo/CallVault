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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.baba.callvault.R
import com.baba.callvault.system.USDT_TON_ADDRESS

private val UsdtGreen = Color(0xFF2E9E4F)
private val WarningOrange = Color(0xFFE07B00)

/**
 * Where to donate, asked once rather than assumed.
 *
 * The links are not interchangeable: Ko-fi's card processing is unavailable or awkward in some
 * countries, many people already hold a PayPal balance. The web ones open in the browser — no payment
 * SDK is in the app, and nothing about the user leaves the device.
 *
 * Every option is a row of its own (rows come from [SUPPORT_OPTIONS]); USDT switches this same dialog
 * to a wallet page, and Back returns to the rows.
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
    var showWallet by rememberSaveable { mutableStateOf(false) }

    // A Dialog reports system Back (and a tap outside) through onDismissRequest, so on the wallet
    // page that goes back to the rows instead of closing.
    Dialog(onDismissRequest = { if (showWallet) showWallet = false else onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showWallet) {
                UsdtTonPage(onBack = { showWallet = false })
            } else {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.support_appeal_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.support_appeal_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    SupportOptionRows(
                        onChoose = { entry ->
                            when (val action = entry.action) {
                                is SupportAction.Browser -> {
                                    onDismiss()
                                    action.open(context)
                                }
                                SupportAction.Wallet -> showWallet = true
                            }
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.support_not_now), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/**
 * The option rows, in a container of their own. There are four now and no scrolling. If the list
 * grows, add `verticalScroll(rememberScrollState())` and a max height to this one Column and
 * nothing else in the dialog changes.
 */
@Composable
private fun SupportOptionRows(onChoose: (SupportEntry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SUPPORT_OPTIONS.forEach { entry -> SupportOptionRow(entry, onClick = { onChoose(entry) }) }
    }
}

@Composable
private fun SupportOptionRow(entry: SupportEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(entry.tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(entry.icon, contentDescription = null, tint = entry.tint, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    stringResource(entry.label),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(entry.secondLine),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // AutoMirrored: the chevron points the other way in right-to-left languages.
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The wallet for USDT on the TON network. Money sent on another network (TRC20, ERC20) is lost, so
 * the warning comes before the address. Nothing here touches the network. No QR code: it would need
 * a new library.
 */
@Composable
private fun UsdtTonPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.support_crypto_copied)
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(UsdtGreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Paid, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.support_crypto_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(WarningOrange.copy(alpha = 0.16f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(24.dp))
            Text(
                stringResource(R.string.support_crypto_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    text = USDT_TON_ADDRESS,
                    fontFamily = FontFamily.Monospace,
                    // An address is never right-to-left text: in an RTL locale the trailing "-" would
                    // jump to the line start and the address would read wrongly.
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("usdt_ton_address"),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("USDT TON", USDT_TON_ADDRESS))
                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.support_crypto_copy)) }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.general_back), color = MaterialTheme.colorScheme.primary)
        }
    }
}
