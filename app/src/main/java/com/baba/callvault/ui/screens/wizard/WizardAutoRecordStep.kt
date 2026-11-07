/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.ui.common.CvCard

@Composable
internal fun AutoRecordStep(
    carrier: Boolean,
    incoming: Boolean,
    outgoing: Boolean,
    onCarrierChange: (Boolean) -> Unit,
    onIncomingChange: (Boolean) -> Unit,
    onOutgoingChange: (Boolean) -> Unit,
    askAfterCall: Boolean,
    onAskAfterCallChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // The VoIP switch owns its own persistence (and its consent dialog), so this mirrors it only to
    // answer the one question this step now has to answer: will anything be recorded at all?
    var voip by remember { mutableStateOf(AppPreferences(context).isVoipRecordingEnabled()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Offered here and not only in Settings because the wizard cannot be re-run, and someone
        // installing CallVault for app calls alone wants this on day one — otherwise their first
        // week is a Record prompt on every phone call they take.
        CvCard {
            SettingsToggleRow(
                label = stringResource(R.string.settings_carrier_recording),
                checked = carrier,
                onCheckedChange = onCarrierChange,
                description = stringResource(R.string.settings_carrier_recording_description)
            )
        }
        if (carrier) {
            CvCard {
                SettingsToggleRow(
                    label = stringResource(R.string.settings_auto_record_incoming),
                    checked = incoming,
                    onCheckedChange = onIncomingChange,
                    description = stringResource(R.string.wizard_ui_auto_incoming_desc)
                )
                SettingsToggleRow(
                    label = stringResource(R.string.settings_auto_record_outgoing),
                    checked = outgoing,
                    onCheckedChange = onOutgoingChange,
                    description = stringResource(R.string.wizard_ui_auto_outgoing_desc)
                )
            }
        }

        // App calls belong beside phone calls: they are the same decision — what gets recorded — and
        // splitting them across two steps is exactly how someone reached Finish with both off. The very
        // switch Settings renders, so the consent confirmation and the Shizuku greying come with it.
        CvCard { VoipRecordingToggle(onEnabledChange = { voip = it }) }

        // Keep or delete after the call. Here and not only in Settings because it is a way of working a new
        // user would never go looking for. Switched on only once its question can be shown (notifications),
        // through the same enabler as the Settings row.
        if (carrier || voip) {
            val enabler = rememberAfterCallAskEnabler(onAllowed = { onAskAfterCallChange(true) })
            CvCard {
                SettingsToggleRow(
                    label = stringResource(R.string.wizard_after_call_ask_label),
                    checked = askAfterCall,
                    onCheckedChange = { on ->
                        if (on) {
                            enabler.request()
                        } else {
                            enabler.cancel()
                            onAskAfterCallChange(false)
                        }
                    },
                    description = stringResource(R.string.wizard_after_call_ask_desc)
                )
                if (enabler.isWaiting && !askAfterCall) SettingsHint(stringResource(R.string.settings_after_call_needs_notifications))
                // How to ask, once asking is on: the same row as in Settings, so choosing Pop-up explains and
                // asks for its permission here too (maintainer, 2026-11-07: let new users decide).
                if (askAfterCall) AfterCallAskStyleRow()
            }
        }

        // Said, not enforced. Finish stays enabled because in Shizuku mode the app-call switch cannot be
        // turned on at all, so gating here would strand that user on a step they cannot satisfy. With
        // both off nothing is ever recorded — a legitimate choice to make, but not one to make silently.
        if (!carrier && !voip) {
            WarningCard(stringResource(R.string.wizard_auto_record_nothing_warning))
        }
    }
}
