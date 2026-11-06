/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import com.baba.callvault.ui.common.rememberResumeCount
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.baba.callvault.ui.common.ModeSwitchDialog
import com.baba.callvault.server.ModeSwitchResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.ShizukuBackend
import com.baba.callvault.server.ShizukuStatus

/**
 * Where CallVault's privileges come from, and whether that source is working right now.
 *
 * Shown to everyone, but written for the person who has Shizuku: standalone is the default and needs no
 * explanation here — its readiness is what the setup wizard is about. What this adds is an honest
 * answer to "is the other way available, and is it working?", which is otherwise invisible.
 *
 * The status is read on each recomposition rather than observed: Shizuku's server can stop at any time
 * (it does not survive a reboot), and a cached "ready" would be a lie the moment it did.
 */
@Composable
fun PrivilegedModeSubSection(onModeChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }

    // Bumped to re-read after anything that could change the answer.
    var refresh by remember { mutableIntStateOf(0) }

    // The switch the modal is performing, and its outcome once known. A non-null target means the
    // dialog is up; a null result inside that means the work is still in flight.
    var switching by remember { mutableStateOf<PrivilegedMode?>(null) }
    var switchResult by remember { mutableStateOf<ModeSwitchResult?>(null) }
    val scope = rememberCoroutineScope()

    // Also on coming back: Shizuku is started (or stopped) in its own app, so leaving to do it is the usual case.
    val resumes = rememberResumeCount()
    val mode = remember(refresh, resumes) { prefs.getPrivilegedMode() }
    val status = remember(refresh, resumes) { RecorderBackend.shizukuStatus(context) }
    OnShizukuPermissionAnswer { refresh++ }

    SettingsToggleRow(
        icon = Icons.Filled.Link,
        label = stringResource(R.string.settings_shizuku_mode),
        description = stringResource(
            when (status) {
                // Only worth offering when it could actually work. Saying "use Shizuku" to someone who
                // does not have it is an instruction to go and install a second app, which is precisely
                // the trade CallVault exists to avoid — so it is described, not urged.
                ShizukuStatus.NOT_INSTALLED -> R.string.settings_shizuku_not_installed
                ShizukuStatus.NOT_RUNNING -> R.string.settings_shizuku_not_running
                ShizukuStatus.NO_PERMISSION -> R.string.settings_shizuku_no_permission
                ShizukuStatus.READY -> R.string.settings_shizuku_ready
            }
        ),
        // The status line is the longest description in Settings and it grows when Shizuku is
        // unhappy, so it gets the full width instead of the narrow column beside the switch.
        descriptionBelow = true,
        checked = mode == PrivilegedMode.SHIZUKU,
        enabled = status != ShizukuStatus.NOT_INSTALLED || mode == PrivilegedMode.SHIZUKU,
        onCheckedChange = { wantShizuku ->
            val target = if (wantShizuku) PrivilegedMode.SHIZUKU else PrivilegedMode.STANDALONE
            switching = target
            switchResult = null
            scope.launch {
                // Off the main thread: switchTo stops a backend and ensureRunning waits for a binder,
                // both of which block. The dialog stays up, uninterruptible, until this answers.
                // One call, because "the switch is finished" is one decision and it belongs in one
                // place — see RecorderBackend.completeSwitch. Assembling it here is how the dialog came
                // to report "Ready" while VoIP arming was still running on another thread.
                val outcome = withContext(Dispatchers.IO) { RecorderBackend.completeSwitch(context, target) }
                switchResult = outcome
                refresh++
                // The rest of the screen greys and un-greys by mode; tell it the mode changed.
                onModeChanged()
            }
        }
    )

    switching?.let { target ->
        ModeSwitchDialog(
            target = target,
            result = switchResult,
            onDismiss = {
                switching = null
                switchResult = null
                refresh++
            },
        )
    }

    // Only when it is the one thing standing in the way, and only in the mode that needs it.
    if (mode == PrivilegedMode.SHIZUKU && status == ShizukuStatus.NO_PERMISSION) {
        NavigationRow(
            icon = Icons.Filled.Key,
            label = stringResource(R.string.settings_shizuku_grant),
            value = "",
            supporting = stringResource(R.string.settings_shizuku_grant_description),
            onClick = {
                ShizukuBackend.requestPermission()
                refresh++
            }
        )
    }
}
