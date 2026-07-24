/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.integrations.adb.OfflineRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the dialog is turning off-Wi-Fi recording ON (with a warning gate) or OFF. */
enum class OfflineDialogMode { ENABLE, DISABLE }

private enum class OfflinePhase { WARNING, WORKING, SUCCESS, FAILED }

private const val SUCCESS_AUTO_CLOSE_MS = 3_000L

/**
 * Walks the user through enabling/disabling off-Wi-Fi (loopback) recording WITH live feedback, so the
 * ADB work (arming/disarming — a few seconds, incl. a brief Wireless-Debugging blip) is never a silent
 * wait. The dialog itself is the feedback — there is no toast.
 *
 *  - [OfflineDialogMode.ENABLE]:  security warning → a live "enabling…" spinner (no buttons, can't be
 *    dismissed mid-arm) → "it's on" with a Confirm button that also **auto-closes after 3 s**; or a
 *    failure message with OK.
 *  - [OfflineDialogMode.DISABLE]: a live "turning off…" spinner, then it closes on its own.
 *
 * @param onResult Reports the final enabled state (true after a successful arm, false otherwise) so the
 *                 caller can sync its toggle. Always called before the dialog closes.
 * @param onClose  Dismisses the dialog (clear the caller's "show dialog" flag).
 */
@Composable
fun OfflineRecordingDialog(
    mode: OfflineDialogMode,
    onResult: (enabled: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember {
        mutableStateOf(if (mode == OfflineDialogMode.ENABLE) OfflinePhase.WARNING else OfflinePhase.WORKING)
    }

    // DISABLE has no warning gate — start the work immediately and close when it's done.
    LaunchedEffect(Unit) {
        if (mode == OfflineDialogMode.DISABLE) {
            withContext(Dispatchers.IO) { OfflineRecording.disable(context) }
            onResult(false)
            onClose()
        }
    }

    // Auto-close a short moment after a successful enable; the Confirm button can close it sooner.
    LaunchedEffect(phase) {
        if (phase == OfflinePhase.SUCCESS) {
            delay(SUCCESS_AUTO_CLOSE_MS)
            onClose()
        }
    }

    val titleContent: (@Composable () -> Unit)? =
        if (phase == OfflinePhase.WARNING) {
            { Text(stringResource(R.string.offline_recording_warning_title)) }
        } else {
            null
        }

    // While WORKING the dialog is not dismissable — the arm/disarm must not be interrupted.
    val dismissable = phase != OfflinePhase.WORKING

    AlertDialog(
        onDismissRequest = { if (dismissable) onClose() },
        title = titleContent,
        text = {
            when (phase) {
                OfflinePhase.WARNING -> Text(stringResource(R.string.offline_recording_warning_message))
                OfflinePhase.WORKING -> WorkingRow(
                    if (mode == OfflineDialogMode.ENABLE) R.string.home_whatsnew_offline_enabling
                    else R.string.offline_recording_disabling,
                )
                OfflinePhase.SUCCESS -> Text(stringResource(R.string.offline_recording_on))
                OfflinePhase.FAILED -> Text(stringResource(R.string.home_whatsnew_offline_failed))
            }
        },
        confirmButton = {
            when (phase) {
                OfflinePhase.WARNING -> TextButton(onClick = {
                    phase = OfflinePhase.WORKING
                    scope.launch {
                        val armed = withContext(Dispatchers.IO) { OfflineRecording.enable(context) }
                        onResult(armed)
                        phase = if (armed) OfflinePhase.SUCCESS else OfflinePhase.FAILED
                    }
                }) { Text(stringResource(R.string.offline_recording_warning_continue)) }
                OfflinePhase.SUCCESS, OfflinePhase.FAILED ->
                    TextButton(onClick = onClose) { Text(stringResource(R.string.general_ok)) }
                OfflinePhase.WORKING -> Unit // no button while the work is in flight
            }
        },
        dismissButton = {
            if (phase == OfflinePhase.WARNING) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.general_cancel)) }
            }
        },
    )
}

@Composable
private fun WorkingRow(@StringRes textRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(textRes))
    }
}
