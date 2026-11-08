/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.RecentActors
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.DeveloperOptions
import com.baba.callvault.onboarding.OnboardingStatus
import com.baba.callvault.server.ModeSwitchResult
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.ShizukuBackend
import com.baba.callvault.server.ShizukuStatus
import com.baba.callvault.system.openAppSettings
import com.baba.callvault.system.openDeviceInfoSettings
import com.baba.callvault.system.openWirelessDebugging
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvHero
import com.baba.callvault.ui.common.CvPrimaryButton
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.common.CvStatusPill
import com.baba.callvault.ui.common.CvTone
import com.baba.callvault.ui.common.ModeSwitchDialog
import com.baba.callvault.ui.common.OemGateNotice
import com.baba.callvault.ui.theme.CallVaultTheme
import com.baba.callvault.ui.theme.LocalCvBrand
import com.baba.callvault.ui.viewmodels.PermissionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        if (uri != null && SafHelper.isCloudFolder(uri)) {
            // Reject a cloud folder (Google Drive, …) as the capture destination — it breaks live
            // recording. Don't advance onboarding, so the user picks on-device storage instead.
            Toast.makeText(activityContext, activityContext.getString(R.string.folder_cloud_rejected), Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
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
        // The Doze modal grants the standard battery-optimization exemption (what `batteryExempted`
        // detects). On OEMs like OxygenOS that is NOT enough — the per-app battery mode stays
        // "Intelligent/Smart", which still throttles the background daemon — and no public API can set
        // it to "Allow background activity / Unrestricted". So guide the user to App Info (where OnePlus
        // exposes that mode) with a one-time hint. Best-effort; harmless if the OEM lacks the setting.
        //
        // Open App Info FIRST, THEN refresh the onboarding state: this way the step advancing happens
        // hidden BEHIND the App Info page, so the user lands on App Info (not on a wizard step that
        // visibly jumped ahead before the settings page appeared).
        runCatching {
            Toast.makeText(activityContext, R.string.permission_battery_oem_hint, Toast.LENGTH_LONG).show()
            activityContext.openAppSettings()
        }
        onPermissionGranted()
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
        modifier = modifier,
        onStatusChanged = onPermissionGranted,
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
    modifier: Modifier = Modifier,
    onStatusChanged: () -> Unit = {},
) {
    val context = LocalContext.current

    // Developer Options + Wireless Debugging are device/global states (not part of OnboardingStatus).
    // Read both here so the ADB card AND the bottom CTA can react to them, and re-evaluate on every
    // ON_RESUME so returning from the system Developer Options / Wireless debugging screen reflects
    // the new state immediately.
    var devOptionsEnabled by remember { mutableStateOf(isDeveloperOptionsEnabled(context)) }
    // Distinct from `!devOptionsEnabled`: a phone that will not say is neither on nor off, and must not
    // be sent to About phone. See [shouldOfferBuildNumberStep] and issue #40.
    var offerBuildNumberStep by remember { mutableStateOf(shouldOfferBuildNumberStep(context)) }
    // Re-read after any change to the backend choice; Shizuku's server can also stop on its own.
    var backendRefresh by remember { mutableIntStateOf(0) }
    val privilegedMode = remember(backendRefresh) { AppPreferences(context).getPrivilegedMode() }
    val shizukuStatus = remember(backendRefresh) { RecorderBackend.shizukuStatus(context) }

    // The Shizuku fork is a question, and a question has to be answerable. Standalone is already the
    // mode for anyone who has not chosen otherwise, and RecorderBackend.switchTo returns early when the
    // mode is unchanged — so "Use CallVault" changed nothing at all and the card could never be sent
    // away. Remembering the answer for this screen is enough: the card exists to ask once.
    var shizukuOfferDeclined by remember { mutableStateOf(false) }

    // A mode switch is seconds of blocking work — a teardown that polls for the old binder to die, then
    // a wait for the new one — and it used to run straight off the button's onClick, on the main
    // thread. Same shape as Settings' PrivilegedModeSubSection now: off the main thread, behind the
    // uninterruptible dialog, through completeSwitch, which is the one place that decides a switch is
    // actually finished (a live binder is not the finish line; VoIP arming lands after it).
    var switching by remember { mutableStateOf<PrivilegedMode?>(null) }
    var switchResult by remember { mutableStateOf<ModeSwitchResult?>(null) }
    val scope = rememberCoroutineScope()

    fun startSwitch(target: PrivilegedMode) {
        switching = target
        switchResult = null
        scope.launch {
            switchResult = withContext(Dispatchers.IO) { RecorderBackend.completeSwitch(context, target) }
            backendRefresh++
        }
    }

    OnShizukuPermissionAnswer { backendRefresh++ }

    var wirelessDebuggingEnabled by remember {
        mutableStateOf(AdbShell.isWirelessDebuggingEnabled(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                devOptionsEnabled = isDeveloperOptionsEnabled(context)
                offerBuildNumberStep = shouldOfferBuildNumberStep(context)
                wirelessDebuggingEnabled = AdbShell.isWirelessDebuggingEnabled(context)
                // Shizuku can be stopped, started or allowed from its own app while we are away.
                backendRefresh++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Is the next needed step the ADB step? (notifications already granted, ADB not yet connected).
    // Only in that moment does the bottom CTA become Wireless-debugging / Developer-Options aware.
    val isAdbStep = !status.isComplete() && status.notificationsGranted && !status.adbConnected

    // Rows show a name and a value; the longer explanation sits behind (i), as on the new Settings screen.
    // Status that needs action now (Required, Not connected, Wireless debugging is off, ...) stays on the row.
    CompositionLocalProvider(LocalSettingsDescriptionsAsInfo provides true) {
        Surface(
            modifier = modifier.navigationBarsPadding().fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // The activity draws edge to edge: without the status-bar inset the SETUP eyebrow sat under
            // the clock. The Surface still paints behind the bar; only the content moves down.
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
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
                        Row(verticalAlignment = Alignment.Top) {
                            CvHero(
                                title = stringResource(R.string.permissions_ui_hero_title),
                                subtitle = stringResource(R.string.permissions_ui_hero_line),
                                modifier = Modifier.weight(1f)
                            )
                            SettingsInfoButton(
                                label = stringResource(R.string.permissions_ui_hero_title),
                                explanation = stringResource(R.string.permissions_ui_hero_subtitle)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        CvSectionHeader(text = stringResource(R.string.permissions_ui_section_label))
                    }

                    // Notifications FIRST: ADB pairing is driven entirely through a notification, so
                    // notifications must be granted before ADB pairing — the card order mirrors that.
                    item {
                        PermissionRow(
                            icon = Icons.Default.Notifications,
                            label = stringResource(R.string.permission_notifications_label),
                            explanation = stringResource(R.string.permission_notifications_description),
                            granted = status.notificationsGranted
                        )
                    }

                    // Offered ONLY to someone who already runs Shizuku. For everyone else this card does
                    // not exist and setup is exactly what it always was — CallVault must never read as an
                    // instruction to install a second app.
                    if (shizukuStatus != ShizukuStatus.NOT_INSTALLED && !shizukuOfferDeclined) {
                        item {
                            ShizukuOfferCard(
                                status = shizukuStatus,
                                mode = privilegedMode,
                                onUseShizuku = { startSwitch(PrivilegedMode.SHIZUKU) },
                                onUseCallVault = {
                                    // Answering the question retires it either way; only leaving Shizuku is
                                    // an actual switch, and asking for one we are already in would spend the
                                    // whole ensureRunning wait to arrive back where we started.
                                    shizukuOfferDeclined = true
                                    if (privilegedMode.needsShizuku) startSwitch(PrivilegedMode.STANDALONE)
                                },
                                onGrant = {
                                    ShizukuBackend.requestPermission()
                                    backendRefresh++
                                },
                            )
                        }
                    }

                    // ADB is the hero step: it gets its OWN full-width layout (title row + full-width
                    // description + hint + Developer Options status/shortcut) so the long content reads well.
                    // Skipped entirely in Shizuku mode: there is nothing to pair, and a card demanding
                    // pairing would be asking for setup the user has just been told they can skip.
                    if (privilegedMode != PrivilegedMode.SHIZUKU) {
                        item {
                            AdbPermissionCard(
                                adbConnected = status.adbConnected,
                                devOptionsEnabled = devOptionsEnabled,
                                devOptionsProvenOff = offerBuildNumberStep,
                                wirelessDebuggingEnabled = wirelessDebuggingEnabled
                            )
                        }
                    }

                    // Remaining runtime permissions, in the original fixed order. Built inside an item so
                    // each stringResource call runs in composable context.
                    // Recording-folder selection moved out of onboarding into the in-app settings wizard.
                    item {
                        PermissionRow(
                            icon = Icons.Default.RecentActors,
                            label = stringResource(R.string.permission_contacts_label),
                            explanation = stringResource(R.string.permission_contacts_description),
                            granted = status.contactsGranted
                        )
                    }
                    item {
                        PermissionRow(
                            icon = Icons.Default.Phone,
                            label = stringResource(R.string.permission_phone_state_label),
                            explanation = stringResource(R.string.permission_phone_state_description),
                            granted = status.phoneStateGranted
                        )
                    }
                    item {
                        PermissionRow(
                            icon = Icons.Default.History,
                            label = stringResource(R.string.permission_call_log_label),
                            explanation = stringResource(R.string.permission_call_log_description),
                            granted = status.callLogGranted
                        )
                    }
                    item {
                        PermissionRow(
                            icon = Icons.Default.BatteryStd,
                            label = stringResource(R.string.permission_battery_label),
                            explanation = stringResource(R.string.permission_battery_description),
                            granted = status.batteryExempted
                        )
                    }
                    // Recommended, not required: with USB debugging on, CallVault can switch Wireless
                    // debugging off — `adbd` needs one of the two, and this is the one that opens no
                    // network port. Shown as "Recommended" rather than "Required" so nobody is misled
                    // into thinking recording depends on it.
                    item {
                        // Tri-state, and this card is OUTSIDE the Shizuku mode gate, so it is one of only two
                        // places the redaction reaches a Shizuku user. A phone that will not say is shown as
                        // granted rather than as a standing "Recommended" nag: on a redacting build the nag
                        // would be permanent and unfixable, and this step is advice, not a requirement.
                        val usb = AdbShell.usbDebuggingState(LocalContext.current)
                        val nag = usb.isOff
                        PermissionRow(
                            icon = Icons.Default.Usb,
                            label = stringResource(R.string.permission_usb_debugging_label),
                            explanation = stringResource(R.string.permission_usb_debugging_description),
                            granted = !nag,
                            pillText = if (nag) stringResource(R.string.general_recommended) else null,
                            pillTone = if (nag) CvTone.Neutral else null,
                        )
                    }
                    // Optional, and only for the keep-or-delete pop-up (maintainer, 2026-11-07): offered here so a
                    // new user meets every permission CallVault can ask for in one place, and can decide. Tapping
                    // explains it first, then opens Android's page; the pill follows the real state on return.
                    item {
                        val popUp = rememberPopUpPermissionRequest()
                        PermissionRow(
                            icon = Icons.Default.PictureInPicture,
                            label = stringResource(R.string.permission_overlay_label),
                            explanation = stringResource(R.string.permission_overlay_description),
                            granted = popUp.isGranted,
                            pillText = if (popUp.isGranted) null else stringResource(R.string.general_optional),
                            pillTone = if (popUp.isGranted) null else CvTone.Neutral,
                            onClick = if (popUp.isGranted) null else popUp.request,
                        )
                    }
                    // The same choice as in Settings: may CallVault switch Wireless debugging back on after the
                    // user turned it off? Asked here too, because it decides how the app treats a switch the
                    // user is about to be told to flip.
                    item {
                        CvCard(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            WirelessDebuggingEnforceToggle()
                        }
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
                    // Only a PROVEN off routes here. Anything else falls through to pairing, which is the
                    // step that can actually advance -- see [shouldOfferBuildNumberStep].
                    offerBuildNumberStep   -> { { context.openDeviceInfoSettings() } }
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

                // Owns its own window, so it sits at the end of the layout without taking part in it.
                switching?.let { target ->
                    ModeSwitchDialog(
                        target = target,
                        result = switchResult,
                        onDismiss = {
                            switching = null
                            switchResult = null
                            backendRefresh++
                            // [status] is the router's snapshot and is only re-read on a grant or a resume.
                            // A switch to Shizuku settles the ADB step, so without this the bottom button
                            // kept saying "Pair" (and opening Developer options) until the user left.
                            onStatusChanged()
                        },
                    )
                }
            }
        }
    }
}

/** Reads whether Developer Options is currently enabled. Delegates to the shared [DeveloperOptions]. */
private fun isDeveloperOptionsEnabled(context: android.content.Context): Boolean =
    DeveloperOptions.isEnabled(context)

/**
 * Whether to send the user to Settings ▸ About phone to tap Build number.
 *
 * Only a **proven** off. Asking `!isDeveloperOptionsEnabled()` instead is what made issue #40 fatal: on a
 * build that redacts `DEVELOPMENT_SETTINGS_ENABLED` the read is permanently `0`, so the ADB step's button
 * opened About phone every time and pairing could never be reached. The reporter had already tapped Build
 * number seven times; nothing he could do would change that read.
 */
private fun shouldOfferBuildNumberStep(context: android.content.Context): Boolean =
    DeveloperOptions.state(context).shouldOfferBuildNumberStep

/**
 * The ADB step: one row (icon, name, Connected / Not connected, (i)) and, until it is connected, ONE status
 * line saying what stands in the way: Developer options off, Wireless debugging off, or Wireless debugging on
 * and ready to pair. The how-to text (where Build number is, where the switch is, what to type where) is in
 * the (i): the status line already says what is wrong, and the bottom button opens the right system screen.
 *
 * Developer-Options and Wireless-debugging are device/global states (not part of
 * [OnboardingStatus.Status]); they are detected in [PermissionsContent] (re-evaluated on ON_RESUME)
 * and passed in here so the card stays coherent with the bottom CTA's action.
 */
@Composable
private fun AdbPermissionCard(
    adbConnected: Boolean,
    devOptionsEnabled: Boolean,
    /**
     * Whether Developer options are PROVEN off. Distinct from `!devOptionsEnabled`, which on a phone that
     * redacts the setting is true forever — and used to put a permanent, false "Developer options are
     * off" warning on the first screen a new user sees (issue #40).
     */
    devOptionsProvenOff: Boolean,
    wirelessDebuggingEnabled: Boolean
) {
    val primary = MaterialTheme.colorScheme.primary
    val brand = LocalCvBrand.current
    val label = stringResource(R.string.permission_adb_label)

    // Hero tint while not connected; plain surface once connected.
    val container: Color =
        if (!adbConnected) primary.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
        else MaterialTheme.colorScheme.surface

    CvCard(
        color = container,
        border = adbConnected,
        contentPadding = ROW_PADDING
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconDisc(Icons.Default.DeveloperMode, accent = if (adbConnected) primary else brand.warning)
            Spacer(Modifier.width(14.dp))
            LabelAndPill(label = label, modifier = Modifier.weight(1f)) {
                CvStatusPill(
                    text = if (adbConnected)
                        stringResource(R.string.permissions_status_adb_connected)
                    else stringResource(R.string.permissions_status_adb_not_connected),
                    tone = if (adbConnected) CvTone.Success else CvTone.Warning
                )
            }
            SettingsInfoButton(
                label = label,
                explanation = joinExplanations(
                    stringResource(R.string.permission_adb_desc_short),
                    // The next step's instructions, matching the status line below.
                    when {
                        adbConnected -> null
                        devOptionsProvenOff -> stringResource(R.string.permission_devmode_enable_hint)
                        !wirelessDebuggingEnabled -> stringResource(R.string.permission_wd_enable_hint)
                        devOptionsEnabled -> stringResource(R.string.permission_adb_hint_wd_on)
                        else -> null
                    },
                ).orEmpty()
            )
        }

        // One status line, only while not connected. The bottom CTA owns the matching action (open device
        // info / open Wireless debugging / pair), so no button is shown here.
        if (!adbConnected) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.padding(start = 56.dp)) {
                when {
                    // Can't pair without Developer Options, and the app can't auto-enable it: the line itself
                    // says how to turn them on (the bottom button opens About phone).
                    devOptionsProvenOff -> DevModeStatusLine(
                        text = stringResource(R.string.permission_status_devmode_off_tap_build),
                        color = brand.warning
                    )
                    !wirelessDebuggingEnabled ->
                        DevModeStatusLine(text = stringResource(R.string.permission_wd_off), color = brand.warning)
                    // Wireless debugging is on, so pairing is the next thing to do.
                    else -> DevModeStatusLine(
                        text = stringResource(R.string.permission_status_wd_on_enter_code),
                        color = brand.success
                    )
                }
            }
        }

        // Some OEMs refuse to let the shell hand us the privilege at all, whatever the switches above say.
        // Nothing else on this screen can explain that, and the user cannot get past it without a switch
        // only they can reach — see [OemGateNotice]. A warning, so it stays visible; self-hiding when it does
        // not apply.
        OemGateNotice(modifier = Modifier.padding(top = 10.dp, end = 8.dp))
    }
}

/**
 * A row's name with its status [pill] after it. A FlowRow, not a Row: when the two do not fit side by side
 * (a long word at a large font size), the pill drops under the name instead of squeezing the name into a
 * mid-word break or clipping it. SpaceBetween keeps the pill at the end while they share a line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelAndPill(label: String, modifier: Modifier = Modifier, pill: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 8.dp)
        )
        pill()
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

/** Card padding for a permission row: the (i) button brings its own touch margin on the end side. */
private val ROW_PADDING = PaddingValues(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)

/** The circular tinted icon at the start of a permission row. */
@Composable
private fun IconDisc(icon: ImageVector, accent: Color) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
    }
}

/**
 * One permission as a settings-style row: icon disc, name, its state as a pill (Granted / Required, or a
 * custom override), and an (i) holding [explanation]. The state is the row's value and is always visible:
 * "Required" is what tells the user this one still needs them.
 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    label: String,
    explanation: String,
    granted: Boolean,
    pillText: String? = null,
    pillTone: CvTone? = null,
    onClick: (() -> Unit)? = null,
) {
    val brand = LocalCvBrand.current
    // Disc accent: teal when granted, brand-warning while pending — reads as a clear at-a-glance state.
    val discAccent: Color = if (granted) MaterialTheme.colorScheme.primary else brand.warning

    CvCard(contentPadding = ROW_PADDING, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconDisc(icon, discAccent)
            Spacer(Modifier.width(14.dp))
            LabelAndPill(label = label, modifier = Modifier.weight(1f)) {
                CvStatusPill(
                    text = pillText ?: stringResource(
                        if (granted) R.string.permissions_status_granted else R.string.permissions_status_required
                    ),
                    tone = pillTone ?: if (granted) CvTone.Success else CvTone.Warning
                )
            }
            SettingsInfoButton(label = label, explanation = explanation)
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
