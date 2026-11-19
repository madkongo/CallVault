/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.ModeCapability
import com.baba.callvault.services.recording.VoipToggleEffects
import kotlinx.coroutines.launch

// App (VoIP) call rows. The classic screen and the wizard draw them together through [VoipRecordingToggle];
// the new Settings screen spreads them over "Calls to record" and "Calls to skip".

/**
 * @param onEnabledChange Notified whenever the opt-in is persisted. The wizard needs it to tell whether
 *   *anything* will be recorded once the user finishes; Settings has no such question and ignores it.
 */
@Composable
internal fun VoipRecordingToggle(onEnabledChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.isVoipRecordingEnabled()) }

    VoipRecordingSwitch(enabled = enabled, onEnabledChange = { turnOn ->
        enabled = turnOn
        onEnabledChange(turnOn)
    })

    // Auto-start vs "ask me", nested under the feature it qualifies: with VoIP recording off there is
    // no detection running, so there would be nothing to ask about.
    AnimatedVisibility(
        visible = enabled,
        enter   = fadeIn() +  expandVertically(),
        exit    = fadeOut() + shrinkVertically()
    ) {
        NestedGroup {
            VoipAutoStartRow()

            SettingsDivider()

            VoipAppPicker(prefs)

            VoipIgnoreContactsRow()
        }
    }
}

/**
 * The "Record VoIP calls" switch itself, with its arming status lines and the confirmation before turning
 * it on. [enabled] is held by the caller, so rows elsewhere on the screen can follow it.
 *
 * @param onEnabledChange Called once the new choice has been saved.
 */
@Composable
internal fun VoipRecordingSwitch(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showWarning by remember { mutableStateOf(false) }
    var arming by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }

    // VoIP capture runs two AudioRecords against a dynamic audio policy. Both modes can since the Shizuku
    // host presents the shell identity (ShellAttribution); below Android 14 neither can, so it is greyed
    // out there rather than left to fail silently.
    val supported = capabilityAvailable(ModeCapability.VOIP_RECORDING)
    val unsupportedNote = unavailableReason(ModeCapability.VOIP_RECORDING)

    // The choice is saved and detection started on the tap; the arm (which touches the daemon, and may launch
    // it) runs off the main thread in a scope that outlives this page (review C13, see VoipToggleEffects). The
    // policy has to be registered before any VoIP call starts, which is why this happens on the toggle rather
    // than when a call begins. This page's scope only follows the result for the status line.
    fun applyPreference(turnOn: Boolean) {
        unavailable = false
        arming = turnOn
        val armed = VoipToggleEffects.apply(context, turnOn)
        onEnabledChange(turnOn)
        scope.launch {
            val ok = armed.await()
            arming = false
            if (turnOn && !ok) unavailable = true
        }
    }

    SettingsToggleRow(
        icon = Icons.Filled.Groups,
        label = stringResource(R.string.settings_voip_recording_label),
        description = unsupportedNote ?: stringResource(R.string.settings_voip_recording_description),
        checked = enabled && supported,
        enabled = supported,
        onCheckedChange = { turnOn ->
            if (turnOn) showWarning = true  // confirm before enabling; never before turning OFF
            else applyPreference(false)
        },
        infoExtra = if (enabled && !arming && !unavailable) stringResource(R.string.voip_recording_names_hint) else null,
    )
    if (arming) {
        SettingsHint(stringResource(R.string.voip_recording_arming))
    } else if (unavailable) {
        SettingsHint(stringResource(R.string.voip_recording_unavailable))
    } else if (enabled) {
        // A calling app's ongoing-call notification is the only place the system holds the contact's
        // name, so an app denied notification permission produces correct but nameless recordings.
        // Worth saying out loud: it looks like a bug in CallVault and is not one.
        SettingsExplanation(stringResource(R.string.voip_recording_names_hint))
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text(stringResource(R.string.voip_recording_warning_title)) },
            text = { Text(stringResource(R.string.voip_recording_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showWarning = false
                    applyPreference(true)
                }) { Text(stringResource(R.string.voip_recording_warning_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) {
                    Text(stringResource(R.string.general_cancel))
                }
            },
        )
    }
}

/**
 * App calls: start recording by themselves, or ask first.
 *
 * @param label The row's name. The new Settings screen says "Record app calls automatically" so it cannot be
 *   mistaken for the phone calls' switches; the classic screen and the wizard keep "Start automatically".
 */
@Composable
internal fun VoipAutoStartRow(label: String = stringResource(R.string.settings_voip_auto_start_label)) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var autoStart by remember { mutableStateOf(prefs.isVoipAutoStartEnabled()) }
    SettingsToggleRow(
        label = label,
        description = stringResource(R.string.settings_voip_auto_start_description),
        checked = autoStart,
        onCheckedChange = { on ->
            autoStart = on
            prefs.setVoipAutoStartEnabled(on)
        }
    )
}

/** Issue #51. Beside the per-app choice: both narrow which app calls are recorded. */
@Composable
internal fun VoipIgnoreContactsRow() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var applyIgnored by remember { mutableStateOf(prefs.isVoipApplyIgnoredContactsEnabled()) }
    SettingsToggleRow(
        label = stringResource(R.string.settings_voip_ignore_contacts_label),
        description = stringResource(R.string.settings_voip_ignore_contacts_desc),
        checked = applyIgnored,
        onCheckedChange = { on ->
            applyIgnored = on
            prefs.setVoipApplyIgnoredContactsEnabled(on)
        },
        infoExtra = stringResource(R.string.settings_voip_ignore_contacts_hint),
    )
    SettingsExplanation(stringResource(R.string.settings_voip_ignore_contacts_hint))
}
