/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.RecentActors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.onboarding.OnboardingStatus
import com.baba.callvault.system.openAppSettings
import com.baba.callvault.system.openDeveloperSettings
import com.baba.callvault.system.openDeviceInfoSettings
import com.baba.callvault.system.openWirelessDebugging
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvHero
import com.baba.callvault.ui.common.CvPrimaryButton
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.common.CvStatusPill
import com.baba.callvault.ui.common.CvTone
import com.baba.callvault.ui.theme.CallVaultTheme
import com.baba.callvault.ui.theme.LocalCvBrand
import com.baba.callvault.ui.viewmodels.PermissionsViewModel

/**
 * Stateful wrapper that connects [PermissionsViewModel] to [PermissionsContent].
 *
 * It owns the Android-specific launchers ([rememberLauncherForActivityResult]) that must live
 * inside a composable and passes them into [PermissionsViewModel.onGrantAccess] as lambdas so
 * the ViewModel stays free of Compose and Activity references.
 *
 * @param status              The current [OnboardingStatus.Status] snapshot, observed by the
 *                            router in [AppNavigationScreen] via [collectAsState].
 * @param onPermissionGranted Called after any grant action completes so the router can refresh state.
 * @param modifier            Optional size/position modifier forwarded to [PermissionsContent].
 * @param viewModel           The "Brain" that decides which permission to request next.
 */
@Composable
fun PermissionsScreen(
    status: OnboardingStatus.Status,
    onPermissionGranted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = viewModel()
) {

    val activityContext = LocalContext.current

    // Permission launchers must live inside a composable - the system dialog can only be
    // triggered from a composable context.  We pass these into the ViewModel as lambdas so
    // the ViewModel never needs to import Compose or hold a UI reference.
    val permissionRequestLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        // false = permission denied or permanently blocked by the OS.
        // In the blocked case we open App Info so the user can grant it manually.
        if (!result) {
            activityContext.openAppSettings()
        }
        onPermissionGranted()
    }
    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // takePersistableUriPermission locks in long-term read/write access so the
            // folder URI remains valid after a device reboot.
            activityContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            AppPreferences(activityContext).setRecordingFolderUri(uri)
        }
        onPermissionGranted()
    }
    // Battery-optimization exemption opens a system screen; using StartActivityForResult brings the
    // user back to CallVault when they're done so the UI refreshes (vs. a fire-and-forget startActivity).
    val batteryExemptionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onPermissionGranted()
        // The Doze modal grants the standard battery-optimization exemption (what `batteryExempted`
        // detects). On OEMs like OxygenOS that is NOT enough — the per-app battery mode stays
        // "Intelligent/Smart", which still throttles the background daemon — and no public API can set
        // it to "Allow background activity / Unrestricted". So guide the user to App Info (where OnePlus
        // exposes that mode) with a one-time hint. Best-effort; harmless if the OEM lacks the setting.
        runCatching {
            Toast.makeText(activityContext, R.string.permission_battery_oem_hint, Toast.LENGTH_LONG).show()
            activityContext.openAppSettings()
        }
    }

    // No external shell-permission check needed: CallVault's embedded ADB shell runs as uid 2000
    // (shell), which already holds the audio-capture privileges scrcpy-server needs.

    PermissionsContent(
        status = status,
        onGrantAccessButtonClick = {
            viewModel.onGrantAccess(
                status = status,
                onPermissionGranted = onPermissionGranted,
                requestRuntimePermission = { permission -> permissionRequestLauncher.launch(permission) },
                launchFolderPicker = { folderPickerLauncher.launch(null) },
                onRequestBatteryExemption = {
                    batteryExemptionLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData("package:${activityContext.packageName}".toUri())
                    )
                },
            )
        },
        modifier = modifier
    )
}

/**
 * Stateless visual layer for the permissions checklist screen, redesigned on the "Signal" system.
 *
 * Renders a hero + a branded checklist of [PermissionCard] rows (leading icon disc, label,
 * one-line description, trailing [CvStatusPill]) based on the [OnboardingStatus.Status] snapshot.
 * The ADB step reads as the hero step — a teal-tinted card with a pairing hint when not connected.
 * A persistent bottom CTA fires [onGrantAccessButtonClick]; its label follows the original logic.
 * Contains no logic — all decisions live in [PermissionsViewModel].
 *
 * Accepting [OnboardingStatus.Status] directly (instead of a separate mapping type) ensures
 * that adding a new prerequisite to [OnboardingStatus] is reflected here automatically,
 * without maintaining a redundant parallel data structure.
 *
 * @param status                 The current "Snapshot" of every permission and setup step.
 * @param onGrantAccessButtonClick Forwarded to [PermissionsViewModel.onGrantAccess] by the
 *                               stateful [PermissionsScreen] wrapper.
 * @param modifier               Optional size/position modifier for the root [Surface].
 */
@Composable
fun PermissionsContent(
    status: OnboardingStatus.Status,
    onGrantAccessButtonClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Developer Options + Wireless Debugging are device/global states (not part of OnboardingStatus).
    // Read both here so the ADB card AND the bottom CTA can react to them, and re-evaluate on every
    // ON_RESUME so returning from the system Developer Options / Wireless debugging screen reflects
    // the new state immediately.
    var devOptionsEnabled by remember { mutableStateOf(isDeveloperOptionsEnabled(context)) }
    var wirelessDebuggingEnabled by remember {
        mutableStateOf(AdbShell.isWirelessDebuggingEnabled(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                devOptionsEnabled = isDeveloperOptionsEnabled(context)
                wirelessDebuggingEnabled = AdbShell.isWirelessDebuggingEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Is the next needed step the ADB step? (notifications already granted, ADB not yet connected).
    // Only in that moment does the bottom CTA become Wireless-debugging / Developer-Options aware.
    val isAdbStep = !status.isComplete() && status.notificationsGranted && !status.adbConnected

    Surface(
        modifier = modifier.navigationBarsPadding().fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    top = 28.dp,
                    bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.permissions_ui_eyebrow).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    CvHero(
                        title = stringResource(R.string.permissions_ui_hero_title),
                        subtitle = stringResource(R.string.permissions_ui_hero_subtitle)
                    )
                    Spacer(Modifier.height(6.dp))
                    CvSectionHeader(text = stringResource(R.string.permissions_ui_section_label))
                }

                // Notifications FIRST: ADB pairing is driven entirely through a notification, so
                // notifications must be granted before ADB pairing — the card order mirrors that.
                item {
                    PermissionCard(
                        icon = Icons.Default.Notifications,
                        label = stringResource(R.string.permission_notifications_label),
                        description = stringResource(R.string.permission_notifications_description),
                        granted = status.notificationsGranted
                    )
                }

                // ADB is the hero step: it gets its OWN full-width layout (title row + full-width
                // description + hint + Developer Options status/shortcut) so the long content reads well.
                item {
                    AdbPermissionCard(
                        adbConnected = status.adbConnected,
                        devOptionsEnabled = devOptionsEnabled,
                        wirelessDebuggingEnabled = wirelessDebuggingEnabled
                    )
                }

                // Remaining runtime permissions, in the original fixed order. Built inside an item so
                // each stringResource call runs in composable context.
                // Recording-folder selection moved out of onboarding into the in-app settings wizard.
                item {
                    PermissionCard(
                        icon = Icons.Default.RecentActors,
                        label = stringResource(R.string.permission_contacts_label),
                        description = stringResource(R.string.permission_contacts_description),
                        granted = status.contactsGranted
                    )
                }
                item {
                    PermissionCard(
                        icon = Icons.Default.Phone,
                        label = stringResource(R.string.permission_phone_state_label),
                        description = stringResource(R.string.permission_phone_state_description),
                        granted = status.phoneStateGranted
                    )
                }
                item {
                    PermissionCard(
                        icon = Icons.Default.History,
                        label = stringResource(R.string.permission_call_log_label),
                        description = stringResource(R.string.permission_call_log_description),
                        granted = status.callLogGranted
                    )
                }
                item {
                    PermissionCard(
                        icon = Icons.Default.BatteryStd,
                        label = stringResource(R.string.permission_battery_label),
                        description = stringResource(R.string.permission_battery_description),
                        granted = status.batteryExempted
                    )
                }
            }

            // Persistent bottom CTA.
            //
            // When the next needed step is the ADB step (notifications granted, not yet connected),
            // the button adapts to Wireless-Debugging / Developer-Options state so the user is guided
            // to the right system screen instead of always seeing "Authorize":
            //   - Dev Options OFF  → "Open device info"        → openDeviceInfoSettings()
            //   - Dev ON, WD OFF   → "Open Wireless debugging"  → openDeveloperSettings()
            //   - WD ON (unpaired) → "Authorize"                → onGrantAccessButtonClick (setupAdb)
            // Every other step keeps the original logic: Continue / Grant Access → onGrantAccessButtonClick.
            // The ADB step is now a single tap toward pairing regardless of WD/dev-options state
            // (the action below adapts), so the label is just "Pair" throughout.
            val adbStepLabel: String? = if (isAdbStep) stringResource(R.string.permission_adb_pair) else null
            val adbStepAction: (() -> Unit)? = when {
                !isAdbStep             -> null
                !devOptionsEnabled     -> { { context.openDeviceInfoSettings() } }
                // WD off → arm the pairing service (it waits on the adb_wifi_enabled setting and
                // auto-starts discovery the moment WD is toggled on), THEN deep-link to the Wireless
                // debugging toggle. The user never has to return to CallVault to tap Authorize.
                !wirelessDebuggingEnabled -> {
                    {
                        com.baba.callvault.integrations.adb.AdbPairingService.start(context)
                        context.openWirelessDebugging()
                    }
                }
                // WD on → start pairing AND open the Wireless debugging screen so the user can tap
                // "Pair device with pairing code" and read the code to enter in the notification.
                else                   -> { { onGrantAccessButtonClick(); context.openWirelessDebugging() } }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = 16.dp)
            ) {
                CvPrimaryButton(
                    text = adbStepLabel ?: when {
                        status.isComplete()  -> stringResource(R.string.general_continue)
                        !status.adbConnected -> stringResource(R.string.permission_adb_setup)
                        else                 -> stringResource(R.string.permissions_grant_access)
                    },
                    onClick = adbStepAction ?: onGrantAccessButtonClick
                )
            }
        }
    }
}

/**
 * Reads whether Developer Options is currently enabled on this device.
 * Wrapped in [runCatching] (defaults to false) because the global setting may be absent on some ROMs.
 */
private fun isDeveloperOptionsEnabled(context: android.content.Context): Boolean =
    runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) == 1
    }.getOrDefault(false)

/**
 * The dedicated, full-width ADB hero card.
 *
 * Unlike the generic [PermissionCard] (icon-left / text-middle / pill-right), the ADB step uses its
 * own vertical layout so the description and hint can use the full card width:
 *  - Top row: leading icon disc + title + [CvStatusPill] (Connected / Not connected).
 *  - A full-width short description line.
 *  - The teal-tinted pairing hint box (only while not connected) — text adapts to WD state.
 *  - A Developer Options + Wireless-debugging status block.
 *
 * Developer-Options and Wireless-debugging are device/global states (not part of
 * [OnboardingStatus.Status]); they are detected in [PermissionsContent] (re-evaluated on ON_RESUME)
 * and passed in here so the card stays coherent with the bottom CTA's action.
 *
 * The bottom CTA already drives the "open Wireless debugging" / "open device info" actions, so this
 * card only shows status lines (no duplicate buttons) to keep one clear action on screen.
 */
@Composable
private fun AdbPermissionCard(
    adbConnected: Boolean,
    devOptionsEnabled: Boolean,
    wirelessDebuggingEnabled: Boolean
) {
    val primary = MaterialTheme.colorScheme.primary
    val brand = LocalCvBrand.current

    // Hero tint while not connected; plain surface once connected.
    val container: Color =
        if (!adbConnected) primary.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
        else MaterialTheme.colorScheme.surface
    val discAccent: Color = if (adbConnected) primary else brand.warning

    CvCard(
        color = container,
        border = adbConnected,
        contentPadding = PaddingValues(16.dp)
    ) {
        // Top row: icon disc + title + status pill. Title wraps to 2 lines; pill stays compact.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(discAccent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DeveloperMode,
                    contentDescription = null,
                    tint = discAccent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = stringResource(R.string.permission_adb_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            CvStatusPill(
                text = if (adbConnected)
                    stringResource(R.string.permissions_status_adb_connected)
                else stringResource(R.string.permissions_status_adb_not_connected),
                tone = if (adbConnected) CvTone.Success else CvTone.Warning
            )
        }

        // Full-width short description.
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.permission_adb_desc_short),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Pairing hint (full width) — only while not yet connected, AND only once Wireless debugging
        // is on (when WD is off the user can't pair yet, so the WD status line guides them instead).
        if (!adbConnected && devOptionsEnabled && wirelessDebuggingEnabled) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(primary.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.permission_adb_hint_wd_on),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Developer Options + Wireless-debugging status lines. The bottom CTA owns the matching
        // action (open device info / open Wireless debugging / authorize), so no button is shown here.
        Spacer(Modifier.height(12.dp))
        if (!devOptionsEnabled) {
            // Can't pair without Developer Options, and the app can't auto-enable it.
            DevModeStatusLine(text = stringResource(R.string.permission_devmode_off), color = brand.warning)
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.permission_devmode_enable_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            DevModeStatusLine(text = stringResource(R.string.permission_devmode_on), color = brand.success)
            Spacer(Modifier.height(8.dp))
            if (wirelessDebuggingEnabled) {
                DevModeStatusLine(text = stringResource(R.string.permission_wd_on), color = brand.success)
            } else {
                DevModeStatusLine(text = stringResource(R.string.permission_wd_off), color = brand.warning)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.permission_wd_enable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A subtle status line with a leading dot, tinted [color] (success/warning). */
@Composable
private fun DevModeStatusLine(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

/**
 * A branded permission row: a leading circular icon disc, the label + muted one-line description,
 * and a trailing [CvStatusPill] (Granted / Required, or a custom override). When [isHero] the card
 * is teal-tinted and an optional [hint] line is shown — used for the key ADB / wireless-debugging
 * step so it visually leads the checklist.
 */
@Composable
private fun PermissionCard(
    icon: ImageVector,
    label: String,
    description: String,
    granted: Boolean,
    isHero: Boolean = false,
    hint: String? = null,
    pillText: String? = null,
    pillTone: CvTone? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    val brand = LocalCvBrand.current

    // Hero step (ADB-not-connected) gets a confident teal tint; everything else uses the plain surface.
    val container: Color =
        if (isHero) primary.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
        else MaterialTheme.colorScheme.surface

    // Disc accent: teal when granted, brand-warning while pending — reads as a clear at-a-glance state.
    val discAccent: Color = if (granted) primary else brand.warning

    val tone = pillTone ?: if (granted) CvTone.Success else CvTone.Warning
    val statusText = pillText
        ?: if (granted) stringResource(R.string.permissions_status_granted)
        else stringResource(R.string.permissions_status_required)

    CvCard(
        color = container,
        border = !isHero,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(discAccent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = discAccent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            CvStatusPill(text = statusText, tone = tone)
        }

        if (hint != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(primary.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionsScreenPreview() {
    CallVaultTheme {
        PermissionsContent(
            status = OnboardingStatus.Status(
                disclaimerAccepted       = true,
                notificationsGranted     = false,
                contactsGranted          = true,
                phoneStateGranted        = false,
                callLogGranted           = false,
                batteryExempted          = false,
                storageSelected          = false,
                adbConnected             = false,
                wizardCompleted          = false
            ),
            onGrantAccessButtonClick = {}
        )
    }
}
