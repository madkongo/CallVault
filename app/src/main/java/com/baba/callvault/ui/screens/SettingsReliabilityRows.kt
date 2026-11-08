/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import com.baba.callvault.ui.common.rememberResumeCount
import android.provider.Settings
import com.baba.callvault.integrations.adb.WirelessDebuggingMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.system.openWirelessDebugging
import com.baba.callvault.integrations.adb.AdbPairingService
import java.io.File
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.services.recording.VoipCaptureController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import com.baba.callvault.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.annotation.StringRes
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.ModeCapability
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.UsbDebuggingOff
import com.baba.callvault.integrations.adb.UsbDebuggingOffGuard
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.integrations.adb.UsbDefaultMode
import com.baba.callvault.integrations.adb.UsbSetResult
import com.baba.callvault.integrations.adb.UsbDebuggingState
import com.baba.callvault.integrations.adb.WifiState
import com.baba.callvault.integrations.adb.WirelessDebuggingRelease
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.WifiOff
import com.baba.callvault.ui.common.OfflineDialogMode
import com.baba.callvault.ui.common.OfflineRecordingDialog

/**
 * Opt-in "Resilient recording" toggle (audio-capture handoff, Option B). Default OFF — the recording
 * path is byte-identical to daemon mode. When ON (and the source is handoff-compatible) the daemon hands
 * its live capture to the always-alive app, so a recording keeps going and finalises even if the
 * background helper (daemon) is killed mid-call (e.g. screen-lock restarts adbd). A plain preference with
 * no arming side effects, so — unlike the offline toggle — it writes straight from the switch.
 */
@Composable
internal fun HandoffPersistToggle() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.isHandoffPersistEnabled()) }

    // The daemon hands its live AudioRecord to the app; a Shizuku-hosted process cannot start one.
    val supported = capabilityAvailable(ModeCapability.RESILIENT_RECORDING)

    SettingsToggleRow(
        icon = Icons.Filled.Shield,
        label = stringResource(R.string.settings_handoff_persist_label),
        description = unavailableReason(ModeCapability.RESILIENT_RECORDING)
            ?: stringResource(R.string.settings_handoff_persist_description),
        checked = enabled && supported,
        enabled = supported,
        onCheckedChange = { turnOn ->
            enabled = turnOn
            prefs.setHandoffPersistEnabled(turnOn)
        },
    )
}


/**
 * Turns on **USB debugging**, which is what lets CallVault switch Wireless debugging off.
 *
 * `adbd` runs only while USB debugging or Wireless debugging is enabled, and CallVault's helper lives
 * inside it — so with neither, there is no helper (measured on a OnePlus 12 and a Galaxy S24 FE; see the
 * README). USB debugging is the better of the two to leave on: no cable is needed, and unlike Wireless
 * debugging it opens **no network port**. Shizuku makes the same trade, its manager just does it
 * silently; asking is better.
 *
 * Turning it back off is allowed and honest about the consequence — Wireless debugging comes back on,
 * because otherwise the helper would have no way in at all.
 */
/**
 * Whether CallVault may switch Wireless debugging back on after the user turned it off themselves.
 *
 * Off by default (decided 2026-09-14). Measured on the OP9 before this existed: CallVault switched it back on
 * 50 ms after the user's tap, so the switch could not be turned off at all while USB debugging was off. With
 * this off, a switch the user turned off stays off and the notification says recording is paused, with a
 * button to turn it back on. Shared with onboarding, so both places say the same thing.
 */
@Composable
internal fun WirelessDebuggingEnforceToggle() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var enforced by remember { mutableStateOf(prefs.isWirelessDebuggingEnforced()) }
    // Shizuku mode does not touch the switches at all, so the choice means nothing there.
    val usesEmbeddedAdb = !currentPrivilegedMode().needsShizuku
    SettingsToggleRow(
        icon = Icons.Filled.Wifi,
        label = stringResource(R.string.settings_wd_enforce_label),
        description = stringResource(R.string.settings_wd_enforce_description),
        checked = enforced,
        enabled = usesEmbeddedAdb,
        onCheckedChange = { on ->
            prefs.setWirelessDebuggingEnforced(on)
            enforced = on
        },
    )
}

/**
 * Whether CallVault answers Android's "Allow wireless debugging on this network?" itself, through its recorder.
 *
 * Off by default (2026-10-21): the prompt is Android's guard against opening the debugging port on a network the
 * owner did not choose. Built-in mode only; Shizuku mode does not manage the debugging switches. See
 * [com.baba.callvault.integrations.adb.NetworkApprovalPolicy].
 */
@Composable
internal fun WifiNetworkAutoApproveToggle() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var on by remember { mutableStateOf(prefs.isWifiNetworkAutoApproveEnabled()) }
    val supported = capabilityAvailable(ModeCapability.WIRELESS_DEBUGGING_CONTROL)
    SettingsToggleRow(
        icon = Icons.Filled.Wifi,
        label = stringResource(R.string.settings_wd_auto_approve_label),
        description = unavailableReason(ModeCapability.WIRELESS_DEBUGGING_CONTROL)
            ?: stringResource(R.string.settings_wd_auto_approve_description),
        checked = on && supported,
        enabled = supported,
        onCheckedChange = { turnOn ->
            prefs.setWifiNetworkAutoApproveEnabled(turnOn)
            on = turnOn
        },
    )
}

/**
 * "Wireless debugging": one choice instead of the two switches "Keep Wireless debugging on for recording" and
 * "Never turn Wireless debugging off", whose names said nearly the same thing (maintainer, 2026-11-03). Stored in
 * the same two keys; see [WirelessDebuggingMode]. Built-in mode only. The checks are in
 * [com.baba.callvault.integrations.adb.AdbShell] (`releaseWirelessDebugging`, the enforce path).
 *
 * Under it, the selected choice is explained, and a live line says when CallVault is leaving Wireless debugging on.
 */
@Composable
internal fun WirelessDebuggingModeRow() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var mode by remember {
        mutableStateOf(WirelessDebuggingMode.of(prefs.isWirelessDebuggingEnforced(), prefs.isWirelessDebuggingNeverTurnOff()))
    }
    val supported = capabilityAvailable(ModeCapability.WIRELESS_DEBUGGING_CONTROL)
    val options = WirelessDebuggingMode.entries.map { m ->
        OptionItem(m.name, stringResource(
            when (m) {
                WirelessDebuggingMode.WHEN_NEEDED -> R.string.settings_wd_mode_when_needed
                WirelessDebuggingMode.KEEP_ON -> R.string.settings_wd_mode_keep_on
                WirelessDebuggingMode.KEEP_ON_ALWAYS -> R.string.settings_wd_mode_keep_on_always
            }
        ))
    }
    // Each choice explained behind the ⓘ beside the dropdown, as for the other dropdowns.
    val explanation = unavailableReason(ModeCapability.WIRELESS_DEBUGGING_CONTROL) ?: listOf(
        R.string.settings_wd_mode_when_needed to R.string.settings_wd_mode_when_needed_desc,
        R.string.settings_wd_mode_keep_on to R.string.settings_wd_mode_keep_on_desc,
        R.string.settings_wd_mode_keep_on_always to R.string.settings_wd_mode_keep_on_always_desc,
    ).map { (name, desc) -> stringResource(name) + ": " + stringResource(desc) }.joinToString("\n\n")
    DropdownRow {
        WithInfo(stringResource(R.string.settings_wd_mode_label), explanation) {
            M3DropdownField(
                label = stringResource(R.string.settings_wd_mode_label),
                selected = options.first { it.key == mode.name },
                options = options,
                enabled = supported,
                onOptionSelected = { picked ->
                    val m = WirelessDebuggingMode.valueOf(picked.key)
                    prefs.setWirelessDebuggingEnforced(m.enforced)
                    prefs.setWirelessDebuggingNeverTurnOff(m.neverTurnOff)
                    mode = m
                },
            )
        }
    }
    if (supported) WirelessDebuggingLeftOnHint(settingOn = mode.neverTurnOff)
}

/** One line under the row while Wireless debugging is on and CallVault is choosing to leave it on. */
@Composable
private fun WirelessDebuggingLeftOnHint(settingOn: Boolean) {
    val context = LocalContext.current
    val wdOn = rememberWirelessDebuggingOn()
    val decision = remember(settingOn, wdOn) {
        if (wdOn) runCatching { AdbShell.releaseDecision(context) }.getOrNull() else null
    }
    when (decision) {
        WirelessDebuggingRelease.LEAVE_SETTING_ON -> SettingsHint(stringResource(R.string.settings_wd_left_on_by_setting))
        WirelessDebuggingRelease.LEAVE_USERS_SWITCH -> SettingsHint(stringResource(R.string.settings_wd_left_on_by_user))
        else -> Unit
    }
}

/** Whether Wireless debugging is on, kept live while the row is on screen. */
@Composable
private fun rememberWirelessDebuggingOn(): Boolean {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AdbShell.isWirelessDebuggingEnabled(context)) }
    DisposableEffect(Unit) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                on = AdbShell.isWirelessDebuggingEnabled(context)
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observer)
        }
        onDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
    return on
}

/**
 * Pairs CallVault with Wireless debugging again, from anywhere, without reinstalling (issue #43).
 *
 * Before this row the pairing flow lived only in onboarding, so when Android forgot the pairing the
 * reporter's only way back was to uninstall — losing settings along the way. It runs the same flow as
 * setup (the notification that takes the code) and opens Wireless debugging's page, where "Pair device
 * with pairing code" is. Recordings and settings are untouched. Built-in mode only: Shizuku pairs itself.
 */
@Composable
internal fun PairAgainRow() {
    val context = LocalContext.current
    if (!capabilityAvailable(ModeCapability.WIRELESS_DEBUGGING_CONTROL)) return
    SettingsDivider()
    NavigationRow(
        icon = Icons.Filled.Wifi,
        label = stringResource(R.string.settings_pair_again_label),
        value = stringResource(R.string.settings_pair_again_value),
        supporting = stringResource(R.string.settings_pair_again_hint),
        onClick = {
            AdbPairingService.start(context)
            context.openWirelessDebugging()
        },
    )
}

@Composable
internal fun UsbDebuggingToggle() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AdbShell.usbDebuggingState(context).isOn) }
    var failed by remember { mutableStateOf(false) }
    // Follow the real setting, not just our own taps: it is changed from Developer options, by Android
    // itself, and by recovery — and a switch showing "on" while USB debugging is off offers the wrong move.
    //
    // But only a PROVEN reading may move it. On a build that redacts `adb_enabled` (Android 17) the
    // read-back after our own successful write returns 0, so this observer used to flip the switch
    // straight back off in front of the user — the write had worked, and the app said it had not. An
    // unprovable reading now leaves the switch where it is, which for a tap the user just made is the
    // value they chose, and for a passive refresh is the last thing we actually knew.
    DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                when (AdbShell.usbDebuggingState(context)) {
                    UsbDebuggingState.ON -> enabled = true
                    UsbDebuggingState.OFF -> enabled = false
                    UsbDebuggingState.UNKNOWN -> Unit
                }
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor("adb_enabled"), false, observer)
        }
        onDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
    // The cost of switching it off, while the user decides; null when no question is open.
    var pendingOff by remember { mutableStateOf<UsbDebuggingOff?>(null) }
    val writeUsbDebugging: (Boolean) -> Unit = { turnOn ->
        val ok = runCatching {
            Settings.Global.putInt(context.contentResolver, "adb_enabled", if (turnOn) 1 else 0)
        }.isSuccess
        failed = !ok
        if (ok) enabled = turnOn
    }
    // Shizuku's server is a shell-uid process hosted by adbd, so turning USB debugging off takes it
    // down with adbd — the same mechanism measured for the Default USB Configuration on 2026-09-05
    // (issue #28g). This row also recommends turning it ON, which is advice for the embedded-ADB
    // backend and means nothing to a Shizuku user. Locked, with the reason, rather than hidden: it
    // still reports the true state, and a control that quietly vanishes teaches nothing.
    val usesEmbeddedAdb = !currentPrivilegedMode().needsShizuku

    SettingsToggleRow(
        icon = Icons.Filled.Usb,
        label = stringResource(R.string.settings_usb_debugging_label),
        description = stringResource(R.string.settings_usb_debugging_description),
        checked = enabled,
        enabled = usesEmbeddedAdb,
        onCheckedChange = { turnOn ->
            // Say what turning it off costs BEFORE the switch moves. AOSP stops adbd when both debugging
            // flags are off and clears Wireless debugging itself off Wi-Fi, so this toggle could leave the
            // recorder with no way back in — #39 sat on "starting up" after doing exactly this.
            val cost = UsbDebuggingOffGuard.decide(
                turnOn = turnOn,
                offlineRecordingOn = AppPreferences(context).isOfflineRecordingEnabled(),
                wifi = WifiState.of(context),
            )
            if (cost == UsbDebuggingOff.PROCEED) writeUsbDebugging(turnOn) else pendingOff = cost
        },
    )
    pendingOff?.let { cost ->
        AlertDialog(
            onDismissRequest = { pendingOff = null },
            // Tinted explicitly: the dialog's default icon colour resolves to coral in this app's scheme.
            icon = { Icon(imageVector = Icons.Filled.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text(stringResource(R.string.usb_debugging_off_warn_title)) },
            text = {
                Text(
                    stringResource(
                        if (cost == UsbDebuggingOff.WARN_NO_WAY_IN) R.string.usb_debugging_off_warn_no_way_in
                        else R.string.usb_debugging_off_warn_offline,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingOff = null }) {
                    Text(stringResource(R.string.usb_debugging_off_keep))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingOff = null
                    writeUsbDebugging(false)
                }) {
                    Text(stringResource(R.string.usb_debugging_off_confirm))
                }
            },
        )
    }
    when {
        !usesEmbeddedAdb -> SettingsHint(stringResource(R.string.settings_usb_debugging_shizuku_hint))
        failed -> SettingsHint(stringResource(R.string.settings_usb_debugging_failed))
        enabled -> SettingsHint(stringResource(R.string.settings_usb_debugging_on_hint))
        else -> SettingsHint(stringResource(R.string.settings_usb_debugging_recommended))
    }
}

/**
 * Lets the user pick the device's **Default USB Configuration** (what USB does when the screen unlocks)
 * from inside the app, applied over the embedded ADB shell. **"Charging only" is recommended**: on many
 * OEMs a data default (File transfer, etc.) makes the USB gadget renegotiate on every screen on/off,
 * restarting adbd and killing the recorder daemon — which stops a recording if you lock the phone
 * mid-call. The other modes are offered for people who rely on them (e.g. USB tethering); picking one
 * just trades that reliability. Reads the live value on open (falls back to the cached value), and shows
 * a spinner-style "applying" hint while the shell command runs.
 */
@Composable
internal fun UsbDefaultConfigRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(UsbDefaultConfig.cached(context)) }
    var applying by remember { mutableStateOf(false) }
    var refusedWhileRecording by remember { mutableStateOf(false) }
    val privilegedMode = currentPrivilegedMode()

    // The nudge is pure embedded-ADB machinery — reading it needs our shell, and applying it needs the
    // shell privilege. Greyed in Shizuku mode for the same reason the offline toggle is: it would look
    // as if it had worked and change nothing.
    val supported = capabilityAvailable(ModeCapability.WIRELESS_DEBUGGING_CONTROL)

    // Refresh the live value once when shown (readViaShell connects+retries internally); falls back to
    // the shown cached value on failure. Not even attempted when unsupported: readViaShell now refuses
    // it anyway, but a probe nobody can act on has no business spending a coroutine either.
    // And again on coming back, since the setting is changed in Developer options, outside the app.
    val resumes = rememberResumeCount()
    LaunchedEffect(supported, resumes) {
        if (!supported) return@LaunchedEffect
        withContext(Dispatchers.IO) { UsbDefaultConfig.readViaShell(context) }?.let { mode = it }
    }

    val recommendedLabel = stringResource(R.string.general_recommended)
    // Nothing is "recommended" here in Shizuku mode. Changing the Default USB Configuration restarts
    // adbd, and Shizuku's server — a shell-uid process hosted by it — dies with it. Measured on the OP9,
    // 2026-09-05: one change, adbd came back with a new pid, shizuku_server was gone and stayed gone.
    // Issue #28's reporter hit exactly this and read our label as advice. See [UsbDefaultConfigRow].
    val options = UsbDefaultConfig.SELECTABLE.map { m ->
        val base = stringResource(usbModeLabelRes(m))
        OptionItem(m.name, if (supported && m == UsbDefaultConfig.RECOMMENDED) "$base ($recommendedLabel)" else base)
    }
    // When the current value is UNKNOWN (never read), don't force a wrong selection — show recommended.
    val selected = options.find { it.key == mode.name } ?: options.first()

    DropdownRow {
      // The advice ("Charging only" keeps the recorder alive) goes behind ⓘ in the new screen; it only
      // applies when the row can be used, and the warnings below stay on screen.
      val usbAdvice = if (supported) stringResource(R.string.settings_usb_default_hint) else null
      WithInfo(stringResource(R.string.settings_usb_default_label), usbAdvice) {
        M3DropdownField(
            label = stringResource(R.string.settings_usb_default_label),
            selected = selected,
            options = options,
            enabled = supported && !applying,
            onOptionSelected = { opt ->
                val target = runCatching { UsbDefaultMode.valueOf(opt.key) }.getOrNull() ?: return@M3DropdownField
                if (target == mode || applying) return@M3DropdownField
                applying = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { UsbDefaultConfig.setViaShell(context, target) }
                    refusedWhileRecording = result == UsbSetResult.BUSY_RECORDING
                    if (result == UsbSetResult.APPLIED) mode = target
                    applying = false
                }
            },
        )
      }
        // While the change is being applied + verified, grey the field (above) and show a live spinner,
        // so the (few-second) delay before the value updates can't be mistaken for "nothing happened".
        if (applying) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_usb_default_applying),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (refusedWhileRecording) {
            HintText(stringResource(R.string.settings_usb_default_busy))
        } else {
            // The Shizuku answer is not "you cannot change this here" but "do not change this
            // at all, including from system Settings" — which is what the reporter did. It and the
            // unavailable reason are warnings and stay on screen; the plain advice is an explanation.
            val warning = when {
                !supported && privilegedMode.needsShizuku -> stringResource(R.string.settings_usb_default_shizuku_hint)
                else -> unavailableReason(ModeCapability.WIRELESS_DEBUGGING_CONTROL)
            }
            if (warning != null) HintText(warning)
            else HintExplanation(usbAdvice ?: stringResource(R.string.settings_usb_default_hint))
        }
    }
}

/** Maps a [UsbDefaultMode] to its user-facing label string resource. */
@StringRes
private fun usbModeLabelRes(mode: UsbDefaultMode): Int = when (mode) {
    UsbDefaultMode.CHARGING -> R.string.usb_mode_charging
    UsbDefaultMode.DEBUGGING_ONLY -> R.string.usb_mode_debugging_only
    UsbDefaultMode.FILE_TRANSFER -> R.string.usb_mode_file_transfer
    UsbDefaultMode.PTP -> R.string.usb_mode_ptp
    UsbDefaultMode.TETHERING -> R.string.usb_mode_tethering
    UsbDefaultMode.MIDI -> R.string.usb_mode_midi
    UsbDefaultMode.UNKNOWN -> R.string.usb_mode_charging
}

/**
 * Opt-in "Offline recording (no Wi-Fi)" toggle. Turning it ON pops a security-warning modal
 * (Cancel / Continue anyway) because it arms a local `adb tcpip` debugging port; only on "Continue"
 * do we persist the opt-in, arm the loopback listener, and re-warm the daemon. Turning it OFF clears
 * the opt-in and best-effort closes the port (reverts adbd to USB mode).
 *
 * `internal` because the wizard renders this very composable: it used to hand-roll its own offline card,
 * which drifted out of sync and offered "Enable" in Shizuku mode, where the next mode switch turns it
 * straight back off again.
 */
@Composable
internal fun OfflineRecordingToggle() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.isOfflineRecordingEnabled()) }
    // Non-null while the enable/disable dialog is walking the user through the ADB work with live feedback.
    var dialogMode by remember { mutableStateOf<OfflineDialogMode?>(null) }

    // Pure embedded-ADB machinery, and Shizuku mode has no embedded ADB connection at all. The switch
    // was already turned off on entering that mode, but it stayed tappable — so it could be turned back
    // on, look on, and do nothing. Greying it says why instead.
    val supported = capabilityAvailable(ModeCapability.OFFLINE_RECORDING)

    SettingsToggleRow(
        icon = Icons.Filled.WifiOff,
        label = stringResource(R.string.settings_offline_recording_label),
        description = unavailableReason(ModeCapability.OFFLINE_RECORDING)
            ?: stringResource(R.string.settings_offline_recording_desc),
        checked = enabled && supported,
        enabled = supported && dialogMode == null,
        onCheckedChange = { turnOn ->
            dialogMode = if (turnOn) OfflineDialogMode.ENABLE else OfflineDialogMode.DISABLE
        },
        infoExtra = if (enabled && supported) stringResource(R.string.settings_offline_recording_reboot_note) else null,
    )

    // Stated up front, and only to the people it applies to. The listener does not survive a reboot
    // and cannot be re-armed until the phone associates with Wi-Fi once — four escapes were tested
    // and every one is shut in AOSP, so this is a platform limit and not a gap we are going to close.
    // Left unsaid it looks like an oversight the first time a call after a reboot is missed; said
    // here it is a known condition with a ten-second fix.
    if (enabled && supported) {
        SettingsExplanation(stringResource(R.string.settings_offline_recording_reboot_note))
    }

    dialogMode?.let { mode ->
        OfflineRecordingDialog(
            mode = mode,
            onResult = { nowEnabled -> enabled = nowEnabled },
            onClose = { dialogMode = null },
        )
    }
}
