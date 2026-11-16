/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.baba.callvault.R

private fun roleManager(context: Context): RoleManager? = context.getSystemService(RoleManager::class.java)

/**
 * "Test: hold the Call redirecting role" (BabApps item 111). A diagnostic, off by default: the switch is ON only while
 * CallVault really holds the role, so it cannot disagree with the phone's Default apps screen.
 *
 * On asks the system for the role (one tap in the system's own dialog). Off cannot be done by an app, so it opens the
 * phone's Default apps screen, where the previous app is chosen again. Hidden when the phone has no such role.
 * CallVault's recording does not depend on it; the service behind it only ever lets the call through unchanged.
 */
@Composable
internal fun CallRedirectTestRow() {
    val context = LocalContext.current
    val manager = remember { roleManager(context) }
    if (manager == null || !runCatching { manager.isRoleAvailable(RoleManager.ROLE_CALL_REDIRECTION) }.getOrDefault(false)) return

    fun held() = runCatching { manager.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION) }.getOrDefault(false)
    var isHeld by remember { mutableStateOf(held()) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isHeld = held() }
    // Back from the Default apps screen (switching off there), or from the system dialog: read the phone again.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) isHeld = held() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    SettingsToggleRow(
        icon = Icons.Filled.Call,
        label = stringResource(R.string.settings_test_call_redirect),
        checked = isHeld,
        onCheckedChange = { on ->
            runCatching {
                if (on) request.launch(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION))
                else context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        description = stringResource(R.string.settings_test_call_redirect_description),
    )
}
