/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.ui.common.rememberResumeCount
import com.baba.callvault.utils.AppLogger

private const val TAG = "CV:PopUpPermission"

/**
 * Asking for "Display over other apps", which only the keep-or-delete pop-up needs (maintainer's spec,
 * 2026-11-07): first a short window saying what it is for, with Allow; Allow opens Android's page for it; back
 * in CallVault with it granted, [onAllowed] runs, so whatever was being turned on turns on by itself.
 *
 * [request] runs [onAllowed] at once when the permission is already there. [isWaiting]: Allow was tapped and
 * the permission is not granted yet. [isGranted] is read again on every return to the app, so a row can show
 * the permission's real state even when it was changed in Android's settings.
 */
internal class PopUpPermissionRequest(
    val request: () -> Unit,
    val cancel: () -> Unit,
    val isWaiting: Boolean,
    val isGranted: Boolean,
)

@Composable
internal fun rememberPopUpPermissionRequest(onAllowed: () -> Unit = {}): PopUpPermissionRequest {
    val context = LocalContext.current
    var isDialogShown by remember { mutableStateOf(false) }
    var isWaiting by remember { mutableStateOf(false) }
    val resumes = rememberResumeCount()
    val isGranted = remember(resumes) { Settings.canDrawOverlays(context) }
    LaunchedEffect(resumes) {
        if (isWaiting && isGranted) {
            isWaiting = false
            onAllowed()
        }
    }
    if (isDialogShown) {
        PopUpPermissionDialog(
            onAllow = {
                isDialogShown = false
                isWaiting = true
                openOverlaySettings(context)
            },
            onDismiss = { isDialogShown = false },
        )
    }
    return PopUpPermissionRequest(
        request = {
            if (Settings.canDrawOverlays(context)) onAllowed() else isDialogShown = true
        },
        cancel = { isWaiting = false },
        isWaiting = isWaiting,
        isGranted = isGranted,
    )
}

@Composable
private fun PopUpPermissionDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    // Button colours stated: Material's defaults render coral in this theme.
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.PictureInPicture, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.popup_permission_title)) },
        text = { Text(stringResource(R.string.popup_permission_text)) },
        confirmButton = {
            TextButton(onClick = onAllow, colors = buttonColors) { Text(stringResource(R.string.popup_permission_allow)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = buttonColors) { Text(stringResource(R.string.popup_permission_not_now)) }
        },
    )
}

/** Android's page for the permission: CallVault's own where the phone allows it (ColorOS shows the full list). */
private fun openOverlaySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { AppLogger.w(TAG, "Could not open the overlay permission page: ${it.message}") }
}
