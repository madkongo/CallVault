/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.transcription.model.DownloadNetwork
import com.baba.callvault.transcription.model.DownloadNetworkChoice

/**
 * "You're not on Wi-Fi": asked when Download is tapped on mobile data.
 *
 * Model downloads were Wi-Fi only with no say in it, so on mobile data they sat at "Waiting for
 * Wi-Fi" and nothing explained why. Waiting stays the filled, default answer: these files are
 * 190 MB to 3.5 GB, and that is a real share of many monthly plans. Dismissing the dialog starts
 * nothing.
 *
 * Both colours are stated: left to M3, the filled button renders in this app's coral.
 */
@Composable
fun MobileDataDownloadDialog(
    sizeText: String,
    onWaitForWifi: () -> Unit,
    onUseMobileData: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.SignalCellularAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.mobile_data_download_title)) },
        text = { Text(stringResource(R.string.mobile_data_download_body, sizeText)) },
        confirmButton = {
            Button(
                onClick = onWaitForWifi,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(stringResource(R.string.mobile_data_download_wait))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onUseMobileData) {
                Text(
                    text = stringResource(R.string.mobile_data_download_now),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}

/**
 * Wraps a download start with the mobile-data question.
 *
 * Returns `ask(sizeText, start)`: on Wi-Fi (or offline) it calls `start(WIFI_ONLY)` at once, exactly
 * as before; on a metered network it shows [MobileDataDownloadDialog] and calls `start` with the
 * user's answer. The answer is for this download only and is not remembered.
 *
 * The network is read at the tap, not when the screen opened: people walk out of Wi-Fi with
 * Settings open.
 */
@Composable
fun rememberMobileDataDownloadPrompt(): (sizeText: String, start: (DownloadNetwork) -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PendingDownload?>(null) }

    pending?.let { request ->
        MobileDataDownloadDialog(
            sizeText = request.sizeText,
            onWaitForWifi = {
                pending = null
                request.start(DownloadNetwork.WIFI_ONLY)
            },
            onUseMobileData = {
                pending = null
                request.start(DownloadNetwork.ANY)
            },
            onDismiss = { pending = null }
        )
    }

    return remember(context) {
        { sizeText, start ->
            when (DownloadNetworkChoice.current(context)) {
                DownloadNetworkChoice.Decision.ASK -> pending = PendingDownload(sizeText, start)
                DownloadNetworkChoice.Decision.QUEUE_UNMETERED -> start(DownloadNetwork.WIFI_ONLY)
            }
        }
    }
}

private data class PendingDownload(val sizeText: String, val start: (DownloadNetwork) -> Unit)
