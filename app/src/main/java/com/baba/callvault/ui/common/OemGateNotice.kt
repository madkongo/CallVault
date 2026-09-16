/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.ShellGrantGate
import com.baba.callvault.ui.theme.LocalCvBrand
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tells the user their phone is refusing to hand CallVault its privilege, and which switch fixes it.
 *
 * Shown only when [ShellGrantGate.shouldAdvise] says so — i.e. the phone is blocking AND the permission is
 * not already held. A phone that blocked the grant but granted it once already keeps working (measured on
 * the OP9, 2026-09-16), so saying anything there would be a nag about a switch that costs the user nothing.
 *
 * It never blocks: the wording says setup can continue, because the reading can be wrong on a ROM we have
 * never seen, and a check we are not sure about must not stand between a user and their recorder.
 */
@Composable
fun OemGateNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val gate by produceState<ShellGrantGate.OemGate?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (ShellGrantGate.shouldAdvise(AdbShell.shellGrantState(context), AdbShell.hasWriteSecureSettings(context))) {
                    AdbShell.oemGate(context)
                } else {
                    null
                }
            }.getOrNull()
        }
    }
    val shown = gate ?: return
    val brand = LocalCvBrand.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(brand.warning.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = brand.warning,
                modifier = Modifier.size(18.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.oem_gate_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(bodyFor(shown)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        // Says outright that setup can continue: the check is a help, never a gate of our own.
        Text(
            text = stringResource(R.string.oem_gate_continue_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { openDeveloperOptions(context) }) {
                Text(stringResource(R.string.oem_gate_open_dev_options))
            }
        }
    }
}

/** vivo and Meizu block a narrower set and their wording is unverified, so they get the general text. */
private fun bodyFor(gate: ShellGrantGate.OemGate): Int = when (gate) {
    ShellGrantGate.OemGate.OPPO -> R.string.oem_gate_body_oppo
    ShellGrantGate.OemGate.XIAOMI -> R.string.oem_gate_body_xiaomi
    else -> R.string.oem_gate_body_other
}

private fun openDeveloperOptions(context: Context) {
    // Falls back to the top-level Settings app: some ROMs hide the developer-options action, and landing
    // somewhere useful beats a dead button.
    val intents = listOf(
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        val ok = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return
    }
    AppLogger.w("CV:OemGate", "Could not open Developer options from the OEM-gate notice")
}
