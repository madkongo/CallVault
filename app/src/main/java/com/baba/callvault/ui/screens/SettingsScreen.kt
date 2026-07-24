/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.baba.callvault.R
import com.baba.callvault.system.PersistentFolderPickerContract
import com.baba.callvault.system.copyToClipboard
import com.baba.callvault.system.openOriginalProjectRepo
import com.baba.callvault.system.openKofi
import com.baba.callvault.system.shareLogFile
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.StringRes
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.integrations.adb.UsbDefaultMode
import com.baba.callvault.data.RetentionPeriod
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.integrations.scrcpy.RECOMMENDED_AUDIO_BIT_RATE
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioSource
import com.baba.callvault.integrations.scrcpy.ScrcpyConfig
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.takePersistableFolderPermission
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.ui.common.ContactSelectionDialog
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvSecondaryButton
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.common.FileNameFormatDialog
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import com.baba.callvault.ui.viewmodels.ContactPickerType
import com.baba.callvault.ui.viewmodels.ContactPickerViewModel
import com.baba.callvault.ui.viewmodels.SettingsActions
import com.baba.callvault.ui.viewmodels.SettingsViewModel
import com.baba.callvault.ui.viewmodels.ContactPickerState
import com.baba.callvault.utils.fileNameTemplateExample
import com.baba.callvault.utils.presetForTemplateOrFirst
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.WifiOff
import com.baba.callvault.ui.common.OfflineDialogMode
import com.baba.callvault.ui.common.OfflineRecordingDialog
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * Stateful wrapper for the Settings screen that connects [SettingsViewModel] to [SettingsContent].
 *
 * @param viewModel Handles saving whenever the user changes a setting.
 * @param onBack    Called when the user taps the top-bar back affordance; the router maps this to
 *                  [com.baba.callvault.ui.viewmodels.AppNavViewModel.navigateBack].
 * @param modifier  Optional modifier for the root scaffold.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Trigger recomposition when settings change by viewmodel.refresh()
    val updateTrigger by viewModel.updateTrigger.collectAsState()

    // ContactPickerViewModel owns the contact-loading logic and dialog state.
    val contactPickerViewModel: ContactPickerViewModel = viewModel()
    val contactPickerState by contactPickerViewModel.contactPickerState.collectAsState()

    // Folder picker — PersistentFolderPickerContract keeps access alive after a reboot.
    val folderPickerLauncher = rememberLauncherForActivityResult(PersistentFolderPickerContract()) { uri ->
        if (uri != null) {
            if (SafHelper.isCloudFolder(uri)) {
                // Cloud folders (Google Drive, …) reject "rw" and report length asynchronously, which
                // breaks live capture. Refuse it here; the Drive backup option handles cloud copies.
                Toast.makeText(context, context.getString(R.string.folder_cloud_rejected), Toast.LENGTH_LONG).show()
            } else {
                context.takePersistableFolderPermission(uri)
                viewModel.preferences.setRecordingFolderUri(uri)
            }
        }
        viewModel.refresh()
    }

    // Drive folder picker — same contract; persists READ + WRITE access across reboots.
    val driveFolderPickerLauncher = rememberLauncherForActivityResult(PersistentFolderPickerContract()) { uri ->
        if (uri != null) {
            context.takePersistableFolderPermission(uri)
            viewModel.setDriveFolderUri(uri)
        }
        viewModel.refresh()
    }

    SettingsContent(
        preferences = viewModel.preferences,
        updateTrigger = updateTrigger,
        actions = viewModel,
        contactPickerState = contactPickerState,
        onBack = onBack,
        // Seed each picker with its OWN current folder so it opens there, instead of letting
        // Android's DocumentsUI reopen at the last-browsed location (which, after setting Drive,
        // made re-picking the local folder open at the Drive path).
        onSelectFolder = { folderPickerLauncher.launch(viewModel.preferences.getRecordingFolderUri()) },
        onSelectDriveFolder = { driveFolderPickerLauncher.launch(viewModel.preferences.getDriveFolderUri()) },
        onOpenContactsIncoming = { contactPickerViewModel.openContactPicker(ContactPickerType.INCOMING) },
        onOpenContactsOutgoing = { contactPickerViewModel.openContactPicker(ContactPickerType.OUTGOING) },
        onConfirmContacts = { numbers ->
            contactPickerViewModel.confirmContactPicker(numbers)
            // Refresh the screen so the new contact list information is shown immediately after confirming and closing the dialog.
            viewModel.refresh()
        },
        onDismissContacts = { contactPickerViewModel.dismissContactPicker() },
        // Build the report off the main thread, then hand it to the system share-sheet. The Share
        // entry point is only shown when a valid log file exists, so the null branch is a safety net.
        onShareLogs = {
            scope.launch {
                val report = withContext(Dispatchers.IO) { AppLogger.buildShareableReport(context) }
                if (report != null) {
                    context.shareLogFile(report)
                } else {
                    Toast.makeText(context, R.string.settings_bugreport_share_empty, Toast.LENGTH_LONG).show()
                }
            }
        },
        modifier = modifier
    )
}

/**
 * Stateless visual layer for the Settings screen, redesigned on the "Signal" design system.
 *
 * Each section is a [CvSectionHeader] followed by a [CvCard] grouping its rows. Every existing
 * setting, action, dialog, and the hidden developer-unlock gesture is preserved; only the layout
 * is restyled.
 *
 * @param preferences            The [AppPreferences] instance to read data from.
 * @param updateTrigger          Trigger value to force/detect recomposition when settings change.
 * @param actions                Implementation of [SettingsActions] to handle user interaction.
 * @param contactPickerState     Current state of the contact picker dialog.
 * @param onBack                 Called when the user taps the top-bar back affordance.
 * @param onSelectFolder         Called when the user taps the recording-folder row.
 * @param onSelectDriveFolder    Called when the user taps the Drive-folder row; opens the SAF picker.
 * @param onOpenContactsIncoming Called to open picker for incoming contacts.
 * @param onOpenContactsOutgoing Called to open picker for outgoing contacts.
 * @param onConfirmContacts      Called when contacts are confirmed from the dialog.
 * @param onDismissContacts      Called when we want to close the dialog without confirmation/saving.
 * @param onShareLogs            Called to share diagnostic logs via the system share-sheet (Debug section).
 * @param modifier               Optional size/position modifier.
 */
@Composable
fun SettingsContent(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    contactPickerState: ContactPickerState?,
    onBack: () -> Unit,
    onSelectFolder: () -> Unit,
    onSelectDriveFolder: () -> Unit,
    onOpenContactsIncoming: () -> Unit,
    onOpenContactsOutgoing: () -> Unit,
    onConfirmContacts: (Set<String>) -> Unit,
    onDismissContacts: () -> Unit,
    onShareLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showLicensesDialog by remember { mutableStateOf(false) }

    // Accordion: at most one section open at a time; Recording & storage is open on entry. State is
    // hoisted here (above the LazyColumn) so it is shared across all sections. Tapping the open section
    // closes it (null = none open); tapping any other section opens it and closes the previous one.
    var openSection by rememberSaveable { mutableStateOf<String?>(SECTION_RECORDING) }
    val onToggleSection: (String) -> Unit = { id -> openSection = if (openSection == id) null else id }

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.general_settings),
        subtitle = stringResource(R.string.settings_ui_subtitle),
        onBack = onBack
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                RecordingSection(
                    preferences = preferences,
                    updateTrigger = updateTrigger,
                    actions = actions,
                    expanded = openSection == SECTION_RECORDING,
                    onToggle = { onToggleSection(SECTION_RECORDING) },
                    onOpenContactsIncoming = onOpenContactsIncoming,
                    onOpenContactsOutgoing = onOpenContactsOutgoing
                )
            }
            item {
                StorageSection(
                    preferences = preferences,
                    updateTrigger = updateTrigger,
                    actions = actions,
                    expanded = openSection == SECTION_STORAGE,
                    onToggle = { onToggleSection(SECTION_STORAGE) },
                    onSelectFolder = onSelectFolder,
                    onSelectDriveFolder = onSelectDriveFolder
                )
            }
            item {
                RetentionSection(
                    preferences = preferences,
                    updateTrigger = updateTrigger,
                    actions = actions,
                    expanded = openSection == SECTION_RETENTION,
                    onToggle = { onToggleSection(SECTION_RETENTION) }
                )
            }
            item {
                AudioSection(
                    preferences, updateTrigger, actions,
                    expanded = openSection == SECTION_AUDIO,
                    onToggle = { onToggleSection(SECTION_AUDIO) }
                )
            }
            item {
                VisualSection(
                    preferences, updateTrigger, actions,
                    expanded = openSection == SECTION_VISUAL,
                    onToggle = { onToggleSection(SECTION_VISUAL) }
                )
            }
            item {
                ReliabilitySection(
                    expanded = openSection == SECTION_RELIABILITY,
                    onToggle = { onToggleSection(SECTION_RELIABILITY) }
                )
            }
            // Debug section: always visible so anyone can enable logging and share logs to report an issue.
            item {
                BugReportSection(
                    preferences, updateTrigger, actions, onShareLogs,
                    expanded = openSection == SECTION_BUG_REPORT,
                    onToggle = { onToggleSection(SECTION_BUG_REPORT) }
                )
            }
            item {
                UpdatesSection(
                    preferences, updateTrigger, actions,
                    expanded = openSection == SECTION_UPDATES,
                    onToggle = { onToggleSection(SECTION_UPDATES) }
                )
            }
            // About moved to the bottom; the fork attribution stays visible (GPLv3 §7 requirement).
            item {
                AboutSection(
                    versionString = actions.getAppVersion(),
                    onShowLicenses = { showLicensesDialog = true },
                    expanded = openSection == SECTION_ABOUT,
                    onToggle = { onToggleSection(SECTION_ABOUT) }
                )
            }
        }
    }

    if (showLicensesDialog) {
        Dialog(
            onDismissRequest = { showLicensesDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.general_licenses),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )

                    val libraries by produceLibraries(R.raw.aboutlibraries)
                    LibrariesContainer(libraries,Modifier
                        .fillMaxSize()
                        .weight(1f),
                        showAuthor = true, showLicenseBadges = true, showFundingBadges = false, showVersion = true, showDescription = true)
                    TextButton(
                        onClick = { showLicensesDialog = false },
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(8.dp)
                    ) {
                        Text(stringResource(R.string.general_close))
                    }
                }
            }
        }
    }

    // The contact-picker dialog sits on top of the settings content.
    contactPickerState?.let { picker ->
        ContactSelectionDialog(
            title = when (picker.type) {
                ContactPickerType.INCOMING -> stringResource(R.string.settings_select_contacts_incoming)
                ContactPickerType.OUTGOING -> stringResource(R.string.settings_select_contacts_outgoing)
            },
            contacts = picker.contacts,
            initialSelection = picker.selectedNumbers,
            onConfirm = onConfirmContacts,
            onDismiss = onDismissContacts
        )
    }
}

// Settings accordion: stable keys identifying each section. At most one section is open at a time;
// [SECTION_RECORDING] is the one open when Settings is first entered.
private const val SECTION_RECORDING = "recording"
private const val SECTION_STORAGE = "storage"
private const val SECTION_RETENTION = "retention"
private const val SECTION_AUDIO = "audio"
private const val SECTION_VISUAL = "visual"
private const val SECTION_RELIABILITY = "reliability"
private const val SECTION_BUG_REPORT = "bug_report"
private const val SECTION_UPDATES = "updates"
private const val SECTION_ABOUT = "about"

// ── Settings sections ──────────────────────────────────────────────────────────────────────

/** Recording behaviour: filename template, plus auto-record incoming/outgoing with their
 * per-direction ignore filters. (Where files are saved lives in [StorageSection].)
 *
 * @param preferences            The [AppPreferences] instance to read data from.
 * @param updateTrigger          Trigger value to force recomposition when settings change.
 * @param actions                Implementation of [SettingsActions] to handle user interaction.
 * @param expanded               Whether this accordion section is open.
 * @param onToggle               Invoked when the section header is tapped.
 * @param onOpenContactsIncoming Called when the user wants to pick incoming contacts to ignore.
 * @param onOpenContactsOutgoing Called when the user wants to pick outgoing contacts to ignore.
 */
@Composable
private fun RecordingSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenContactsIncoming: () -> Unit,
    onOpenContactsOutgoing: () -> Unit
) {
    // Evaluate these here so they are fetched on every recomposition.
    val fileNameFormat = remember(updateTrigger) { preferences.getFileNameTemplate() }
    val autoRecordIncoming = remember(updateTrigger) { preferences.isAutoRecordIncomingEnabled() }
    val autoRecordOutgoing = remember(updateTrigger) { preferences.isAutoRecordOutgoingEnabled() }
    val ignoreAnonymousIncoming = remember(updateTrigger) { preferences.isIgnoreAnonymousIncomingEnabled() }
    val ignoreCrossCountryIncoming = remember(updateTrigger) { preferences.isIgnoreCrossCountryIncomingEnabled() }
    val ignoreContactsModeIncoming = remember(updateTrigger) { preferences.getIgnoreContactsModeIncoming() }
    val ignoreContactsModeOutgoing = remember(updateTrigger) { preferences.getIgnoreContactsModeOutgoing() }
    val ignoreCrossCountryOutgoing = remember(updateTrigger) { preferences.isIgnoreCrossCountryOutgoingEnabled() }
    val ignoredContactsIncomingCount = remember(updateTrigger) { preferences.getIgnoredContactsIncoming().size }
    val ignoredContactsOutgoingCount = remember(updateTrigger) { preferences.getIgnoredContactsOutgoing().size }

    var showFileNameFormatDialog by remember { mutableStateOf(false) }

    SettingsSection(title = stringResource(R.string.settings_section_recording), expanded = expanded, onToggle = onToggle) {
        NavigationRow(
            icon = Icons.Filled.DriveFileRenameOutline,
            label = stringResource(R.string.settings_file_name_template),
            value = stringResource(presetForTemplateOrFirst(fileNameFormat).labelRes),
            supporting = stringResource(
                R.string.settings_file_name_template_example,
                fileNameTemplateExample(fileNameFormat)
            ),
            onClick = { showFileNameFormatDialog = true }
        )

        SettingsDivider()

        SettingsToggleRow(
            icon = Icons.AutoMirrored.Filled.CallReceived,
            label = stringResource(R.string.settings_auto_record_incoming),
            checked = autoRecordIncoming,
            onCheckedChange = { actions.setAutoRecordIncoming(it) }
        )
        AnimatedVisibility(
            visible = autoRecordIncoming,
            enter   = fadeIn() +  expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            NestedGroup {
                SettingsToggleRow(
                    label           = stringResource(R.string.settings_ignore_anonymous_incoming),
                    checked         = ignoreAnonymousIncoming,
                    onCheckedChange = { actions.setIgnoreAnonymousIncoming(it) }
                )
                SettingsToggleRow(
                    label           = stringResource(R.string.settings_ignore_cross_country_incoming),
                    checked         = ignoreCrossCountryIncoming,
                    onCheckedChange = { actions.setIgnoreCrossCountryIncoming(it) },
                    enabled         = ignoreAnonymousIncoming
                )
                IgnoreContactsOptions(
                    label           = stringResource(R.string.settings_ignore_contacts_incoming),
                    selectedEnum     = ignoreContactsModeIncoming,
                    selectedCount    = ignoredContactsIncomingCount,
                    onSelected      = { actions.setIgnoreContactsModeIncoming(it) },
                    onSelectContacts = onOpenContactsIncoming
                )
            }
        }

        SettingsDivider()

        SettingsToggleRow(
            icon = Icons.AutoMirrored.Filled.CallMade,
            label = stringResource(R.string.settings_auto_record_outgoing),
            checked = autoRecordOutgoing,
            onCheckedChange = { actions.setAutoRecordOutgoing(it) }
        )
        AnimatedVisibility(
            visible = autoRecordOutgoing,
            enter   = fadeIn() +  expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            NestedGroup {
                SettingsToggleRow(
                    label           = stringResource(R.string.settings_ignore_cross_country_outgoing),
                    checked         = ignoreCrossCountryOutgoing,
                    onCheckedChange = { actions.setIgnoreCrossCountryOutgoing(it) }
                )
                IgnoreContactsOptions(
                    label           = stringResource(R.string.settings_ignore_contacts_outgoing),
                    selectedEnum     = ignoreContactsModeOutgoing,
                    selectedCount    = ignoredContactsOutgoingCount,
                    onSelected      = { actions.setIgnoreContactsModeOutgoing(it) },
                    onSelectContacts = onOpenContactsOutgoing
                )
            }
        }
    }

    if (showFileNameFormatDialog) {
        FileNameFormatDialog(
            initialFormat = fileNameFormat,
            onConfirm = { format ->
                actions.setFileNameTemplate(format)
                showFileNameFormatDialog = false
            },
            onDismiss = { showFileNameFormatDialog = false }
        )
    }
}

/** Storage destinations: where recordings are saved — the storage target (device / Drive / both),
 * the on-device folder, and the Drive folder. (Recording behaviour lives in [RecordingSection].)
 *
 * @param preferences         The [AppPreferences] instance to read data from.
 * @param updateTrigger       Trigger value to force recomposition when settings change.
 * @param actions             Implementation of [SettingsActions] to handle user interaction.
 * @param expanded            Whether this accordion section is open.
 * @param onToggle            Invoked when the section header is tapped.
 * @param onSelectFolder      Called when the user taps the recording-folder row; opens the SAF picker.
 * @param onSelectDriveFolder Called when the user taps the Drive-folder row; opens the SAF picker.
 */
@Composable
private fun StorageSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectFolder: () -> Unit,
    onSelectDriveFolder: () -> Unit
) {
    val context = LocalContext.current

    val recordingFolderLabel = remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, preferences.getRecordingFolderUri()) }
    val recordingFolderIsCloud = remember(updateTrigger) { SafHelper.isCloudFolder(preferences.getRecordingFolderUri()) }
    val storageTarget = remember(updateTrigger) { preferences.getStorageTarget() }
    val driveFolderLabel = remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, preferences.getDriveFolderUri()) }

    val storageTargetOptions = StorageTarget.entries.map { target ->
        val labelRes = when (target) {
            StorageTarget.LOCAL -> R.string.storage_target_local
            StorageTarget.DRIVE -> R.string.storage_target_drive
            StorageTarget.BOTH  -> R.string.storage_target_both
        }
        OptionItem(target.key, stringResource(labelRes))
    }

    SettingsSection(title = stringResource(R.string.settings_section_storage), expanded = expanded, onToggle = onToggle) {
        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_storage_target_label),
                selected = storageTargetOptions.find { it.key == storageTarget.key } ?: storageTargetOptions.first(),
                options  = storageTargetOptions,
                onOptionSelected = { actions.setStorageTarget(StorageTarget.fromKey(it.key)) }
            )
        }

        SettingsDivider()

        NavigationRow(
            icon = Icons.Filled.Folder,
            label = stringResource(R.string.settings_recording_folder_label),
            value = recordingFolderLabel ?: stringResource(R.string.settings_tap_to_select_folder),
            // Surface a cloud folder (e.g. Google Drive) that was set before we started rejecting them —
            // otherwise it's indistinguishable from a local folder by name alone.
            supporting = if (recordingFolderIsCloud) stringResource(R.string.folder_cloud_warning) else null,
            onClick = onSelectFolder
        )

        NavigationRow(
            icon = Icons.Filled.Cloud,
            label = stringResource(R.string.settings_drive_folder_label),
            value = driveFolderLabel ?: stringResource(R.string.general_not_set),
            supporting = stringResource(R.string.settings_drive_folder_desc),
            onClick = onSelectDriveFolder
        )
    }
}

/** Retention: auto-delete recordings older than a chosen period. One shared period for device & Drive,
 * or a separate period for each. Destructive, so it defaults to "Keep forever" and a confirmation is
 * shown the first time a non-forever period is chosen.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 * @param expanded      Whether this accordion section is open.
 * @param onToggle      Invoked when the section header is tapped.
 */
@Composable
private fun RetentionSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val linked = remember(updateTrigger) { preferences.isRetentionLinked() }
    val localDays = remember(updateTrigger) { preferences.getRetentionLocalDays() }
    val driveDays = remember(updateTrigger) { preferences.getRetentionDriveDays() }

    val options = RetentionPeriod.entries.map { OptionItem(it.days.toString(), stringResource(it.labelRes)) }
    fun optionFor(days: Int) =
        options.find { it.key == RetentionPeriod.fromDays(days).days.toString() } ?: options.first()

    // Enabling retention from OFF is destructive, so stash the apply-action and confirm first.
    var pendingConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun applyOrConfirm(wasOff: Boolean, newDays: Int, apply: () -> Unit) {
        if (wasOff && newDays > 0) pendingConfirm = apply else apply()
    }

    SettingsSection(title = stringResource(R.string.settings_section_retention), expanded = expanded, onToggle = onToggle) {
        SettingsToggleRow(
            label = stringResource(R.string.retention_linked_label),
            checked = linked,
            onCheckedChange = { nowLinked ->
                actions.setRetentionLinked(nowLinked)
                // When linking, unify the Drive period to the device one so they match.
                if (nowLinked) actions.setRetentionDriveDays(localDays)
            }
        )

        SettingsDivider()

        if (linked) {
            DropdownRow {
                M3DropdownField(
                    label = stringResource(R.string.retention_period_label),
                    selected = optionFor(localDays),
                    options = options,
                    onOptionSelected = { opt ->
                        val days = opt.key.toIntOrNull() ?: 0
                        applyOrConfirm(wasOff = localDays == 0 && driveDays == 0, newDays = days) {
                            actions.setRetentionLocalDays(days)
                            actions.setRetentionDriveDays(days)
                        }
                    }
                )
            }
        } else {
            DropdownRow {
                M3DropdownField(
                    label = stringResource(R.string.retention_local_label),
                    selected = optionFor(localDays),
                    options = options,
                    onOptionSelected = { opt ->
                        val days = opt.key.toIntOrNull() ?: 0
                        applyOrConfirm(wasOff = localDays == 0, newDays = days) { actions.setRetentionLocalDays(days) }
                    }
                )
            }
            DropdownRow {
                M3DropdownField(
                    label = stringResource(R.string.retention_drive_label),
                    selected = optionFor(driveDays),
                    options = options,
                    onOptionSelected = { opt ->
                        val days = opt.key.toIntOrNull() ?: 0
                        applyOrConfirm(wasOff = driveDays == 0, newDays = days) { actions.setRetentionDriveDays(days) }
                    }
                )
            }
        }

        // Sweep time — only relevant once retention is enabled. Two dropdowns (Hour/Minute) in the
        // device's LOCAL time zone, mirroring the sync-schedule picker.
        if (localDays > 0 || driveDays > 0) {
            SettingsDivider()
            val hour = remember(updateTrigger) { preferences.getRetentionTimeHour() }
            val minute = remember(updateTrigger) { preferences.getRetentionTimeMinute() }
            val hourOptions = (0..23).map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
            val minuteOptions = listOf(0, 15, 30, 45).map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
            Text(
                text = stringResource(R.string.retention_time_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            DropdownRow {
                M3DropdownField(
                    label = stringResource(R.string.wizard_schedule_hour_label),
                    selected = hourOptions.find { it.key == hour.toString() } ?: hourOptions.first(),
                    options = hourOptions,
                    onOptionSelected = { actions.setRetentionTimeHour(it.key.toIntOrNull() ?: 0) }
                )
            }
            DropdownRow {
                M3DropdownField(
                    label = stringResource(R.string.wizard_schedule_minute_label),
                    selected = minuteOptions.find { it.key == minute.toString() } ?: minuteOptions.first(),
                    options = minuteOptions,
                    onOptionSelected = { actions.setRetentionTimeMinute(it.key.toIntOrNull() ?: 0) }
                )
            }
        }

        Text(
            text = stringResource(R.string.retention_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }

    pendingConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text(stringResource(R.string.retention_confirm_title)) },
            text = { Text(stringResource(R.string.retention_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { confirm(); pendingConfirm = null }) {
                    Text(stringResource(R.string.retention_confirm_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        )
    }
}

/** Shows the audio source, codec, and bit-rate dropdowns.
 *
 * The audio-source list is generated from [ScrcpyAudioSource.entries] with debug-only entries
 * ([ScrcpyAudioSource.isDebugOnly]) always hidden. Items whose
 * [ScrcpyAudioSource.minApi]/[ScrcpyAudioSource.maxApi] range does not include the current
 * device's API level are shown grayed out and cannot be selected.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 */
@Composable
private fun AudioSection(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions, expanded: Boolean, onToggle: () -> Unit) {

    val audioSource = remember(updateTrigger) { preferences.getAudioSource() }
    val audioCodec = remember(updateTrigger) { preferences.getAudioCodec() }
    val savedBitRate = remember(updateTrigger) { preferences.getAudioBitRate() }

    SettingsSection(title = stringResource(R.string.settings_section_audio), expanded = expanded, onToggle = onToggle) {
        val currentSdk = Build.VERSION.SDK_INT

        // Build the source list from the enum, always hiding debug-only entries.
        // Items that require an API level not available on this device are shown as disabled.
        val audioSourceOptions = ScrcpyAudioSource.entries
            .filter { !it.isDebugOnly }
            .map { source ->
                OptionItem(
                    key         = source.cliKey,
                    label       = stringResource(source.titleResId),
                    description = stringResource(source.descriptionResId),
                    // Enabled only when the current SDK is within the source's API range.
                    enabled     = currentSdk >= source.minApi &&
                                  (source.maxApi == null || currentSdk <= source.maxApi)
                )
            }

        val selectedAudio = audioSourceOptions.find { it.key == audioSource }
            ?: audioSourceOptions.first()

        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_audio_source),
                selected = selectedAudio,
                options  = audioSourceOptions,
                onOptionSelected = { actions.setAudioSource(it.key) }
            )
            // Show the description of the currently selected audio source below the dropdown.
            selectedAudio.description?.let { desc ->
                HintText(desc)
            }
        }

        val codecOptions = ScrcpyAudioCodec.entries
            .map { OptionItem(it.cliKey, stringResource(it.titleResId)) }

        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_audio_codec),
                selected = codecOptions.find { it.key == audioCodec }
                    ?: codecOptions.first(),
                options  = codecOptions,
                onOptionSelected = { actions.setAudioCodec(it.key) },
            )
            // Show the AAC recommendation if the user has issues.
            // LocalInspectionMode.current is true in Android Preview, it prevents a preview compilation error.
            if (!LocalInspectionMode.current && audioCodec != ScrcpyAudioCodec.AAC.cliKey) {
                HintText(stringResource(R.string.settings_audio_bitrate_recommendation))
            }
        }

        val recommendedLabel = stringResource(R.string.general_recommended)
        val bitrateOptions = listOf(8000, 16000, 24000, 32000, 64000, 128000)
            .map { bps ->
                val kbpsLabel = stringResource(R.string.audio_bitrate_kbps, bps / 1000)
                // 24 kbps is the recommended sweet spot for voice — flag it right in the dropdown.
                val label = if (bps == RECOMMENDED_AUDIO_BIT_RATE) "$kbpsLabel ($recommendedLabel)" else kbpsLabel
                OptionItem(bps.toString(), label)
            }

        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_audio_bitrate),
                selected = bitrateOptions.find { it.key == savedBitRate.toString() }
                    ?: bitrateOptions.first(), // fallback gracefully if bitrate was removed from expected options
                options  = bitrateOptions,
                onOptionSelected = { actions.setAudioBitRate(it.key.toInt()) }
            )
        }
    }
}

/** Shows the theme and dynamic colour settings.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 */
@Composable
private fun VisualSection(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions, expanded: Boolean, onToggle: () -> Unit) {
    val currentThemeMode = remember(updateTrigger) { preferences.getThemeMode() }
    val isDynamicColorEnabled = remember(updateTrigger) { preferences.isDynamicColorEnabled() }
    val isShowToastsEnabled = remember(updateTrigger) { preferences.isShowToastsEnabled() }
    val isVibrationEnabled = remember(updateTrigger) { preferences.isVibrationEnabled() }
    val context = LocalContext.current
    val resources = LocalResources.current

    // Read the current applied language without warnings
    val currentLanguage = remember {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.isEmpty) "" else currentLocales[0]?.toLanguageTag() ?: ""
    }

    // Fetch available languages from dynamically generated XML resource file.
    val languageOptions = remember(context) {
        val options = mutableListOf(OptionItem("", resources.getString(R.string.settings_language_system)))

        // Suppress the warning right here since AGP create this file dynamically at compile time
        @SuppressLint("DiscouragedApi")
        val resId = resources.getIdentifier("_generated_res_locale_config", "xml", context.packageName)

        try {
            val parser = resources.getXml(resId)
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    val localeName = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                    if (localeName != null) {
                        val locale = Locale.forLanguageTag(localeName)
                        val displayName = locale.getDisplayName(locale).replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(locale) else it.toString()
                        }
                        options.add(OptionItem(localeName, displayName))
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            options.add(OptionItem("en", "English (Provided as fallback)"))
        }
        options.distinctBy { it.key }
    }

    SettingsSection(title = stringResource(R.string.settings_section_visual), expanded = expanded, onToggle = onToggle) {
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.settings_language),
                selected = languageOptions.find { it.key == currentLanguage } ?: languageOptions.first(),
                options = languageOptions,
                onOptionSelected = { actions.setAppLanguage(it.key) }
            )
        }

        val themeOptions = AppPreferences.ThemeMode.entries.map { mode ->
            val labelRes = when (mode) {
                AppPreferences.ThemeMode.SYSTEM -> R.string.settings_theme_mode_system
                AppPreferences.ThemeMode.LIGHT -> R.string.settings_theme_mode_light
                AppPreferences.ThemeMode.DARK -> R.string.settings_theme_mode_dark
            }
            OptionItem(mode.key, stringResource(labelRes))
        }
        val defaultThemeMode = AppPreferences.DefaultsValue.THEME_MODE.key

        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_theme_mode),
                selected = themeOptions.find { it.key == currentThemeMode.key }
                    ?: themeOptions.find { it.key == defaultThemeMode }
                    ?: themeOptions.first(),
                options  = themeOptions,
                onOptionSelected = { actions.setThemeMode(AppPreferences.ThemeMode.fromKey(it.key)) }
            )
        }

        SettingsDivider()

        SettingsToggleRow(
            icon            = Icons.Filled.ColorLens,
            label           = stringResource(R.string.settings_dynamic_color),
            checked         = isDynamicColorEnabled,
            onCheckedChange = { actions.setDynamicColorEnabled(it) }
        )
        SettingsToggleRow(
            icon            = Icons.Filled.NotificationsActive,
            label           = stringResource(R.string.settings_show_toasts),
            checked         = isShowToastsEnabled,
            onCheckedChange = { actions.setShowToastsEnabled(it) }
        )
        SettingsToggleRow(
            icon            = Icons.Filled.Vibration,
            label           = stringResource(R.string.settings_vibration_enabled),
            checked         = isVibrationEnabled,
            onCheckedChange = { actions.setVibrationEnabled(it) }
        )
    }
}

/**
 * Debug section (always visible). The flow is: turn logging on, reproduce the issue, turn logging
 * off, then share the captured log.
 *
 * - While logging is **on**, we show a red reminder to turn it back off; sharing is intentionally
 *   hidden so the user isn't sharing a log that is still being written to.
 * - While logging is **off**, the Share button appears only if a valid (non-empty) log file from a
 *   previous session still exists — there is nothing to share otherwise.
 *
 * Logs stay redacted (phone numbers masked). Each time logging is turned on the previous capture is
 * cleared (see [SettingsViewModel.setLoggingEnabled]) so every report is a fresh, focused log.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 * @param onShareLogs   Called to share the diagnostic log report via the system share-sheet.
 */
@Composable
private fun BugReportSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    onShareLogs: () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val isLoggingEnabled = remember(updateTrigger) { preferences.isLoggingEnabled() }
    // Re-checked on every settings change (e.g. right after the toggle flips off) so the Share
    // button appears as soon as a capture is frozen on disk.
    val hasLogs = remember(updateTrigger) { AppLogger.hasLogs() }

    SettingsSection(title = stringResource(R.string.settings_section_debug), expanded = expanded, onToggle = onToggle) {
        SettingsToggleRow(
            icon            = Icons.Filled.BugReport,
            label           = stringResource(R.string.settings_debug_logging_enabled),
            checked         = isLoggingEnabled,
            onCheckedChange = { actions.setLoggingEnabled(it) },
            description     = stringResource(R.string.settings_debug_logging_enabled_description)
        )

        if (isLoggingEnabled) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_bugreport_active_warning),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        } else if (hasLogs) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_bugreport_share_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onShareLogs,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_bugreport_share))
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }

    }
}

/**
 * "Reliability" — controls that keep recording working in tricky conditions: recording without Wi-Fi
 * (loopback opt-in) and the Default USB Configuration that stops a screen-lock from killing the recorder
 * mid-call. Grouped together so users have one place to make recording robust.
 */
@Composable
private fun ReliabilitySection(expanded: Boolean, onToggle: () -> Unit) {
    SettingsSection(title = stringResource(R.string.settings_section_reliability), expanded = expanded, onToggle = onToggle) {
        OfflineRecordingToggle()
        SettingsDivider()
        UsbDefaultConfigRow()
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
private fun UsbDefaultConfigRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(UsbDefaultConfig.cached(context)) }
    var applying by remember { mutableStateOf(false) }

    // Refresh the live value once when shown (readViaShell connects+retries internally); falls back to
    // the shown cached value on failure.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { UsbDefaultConfig.readViaShell(context) }?.let { mode = it }
    }

    val recommendedLabel = stringResource(R.string.general_recommended)
    val options = UsbDefaultConfig.SELECTABLE.map { m ->
        val base = stringResource(usbModeLabelRes(m))
        OptionItem(m.name, if (m == UsbDefaultConfig.RECOMMENDED) "$base ($recommendedLabel)" else base)
    }
    // When the current value is UNKNOWN (never read), don't force a wrong selection — show recommended.
    val selected = options.find { it.key == mode.name } ?: options.first()

    DropdownRow {
        M3DropdownField(
            label = stringResource(R.string.settings_usb_default_label),
            selected = selected,
            options = options,
            enabled = !applying,
            onOptionSelected = { opt ->
                val target = runCatching { UsbDefaultMode.valueOf(opt.key) }.getOrNull() ?: return@M3DropdownField
                if (target == mode || applying) return@M3DropdownField
                applying = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { UsbDefaultConfig.setViaShell(context, target) }
                    if (ok) mode = target
                    applying = false
                }
            },
        )
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
        } else {
            HintText(stringResource(R.string.settings_usb_default_hint))
        }
    }
}

/** Maps a [UsbDefaultMode] to its user-facing label string resource. */
@StringRes
private fun usbModeLabelRes(mode: UsbDefaultMode): Int = when (mode) {
    UsbDefaultMode.CHARGING -> R.string.usb_mode_charging
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
 */
@Composable
private fun OfflineRecordingToggle() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.isOfflineRecordingEnabled()) }
    // Non-null while the enable/disable dialog is walking the user through the ADB work with live feedback.
    var dialogMode by remember { mutableStateOf<OfflineDialogMode?>(null) }

    SettingsToggleRow(
        icon = Icons.Filled.WifiOff,
        label = stringResource(R.string.settings_offline_recording_label),
        description = stringResource(R.string.settings_offline_recording_desc),
        checked = enabled,
        enabled = dialogMode == null,
        onCheckedChange = { turnOn ->
            dialogMode = if (turnOn) OfflineDialogMode.ENABLE else OfflineDialogMode.DISABLE
        },
    )

    dialogMode?.let { mode ->
        OfflineRecordingDialog(
            mode = mode,
            onResult = { nowEnabled -> enabled = nowEnabled },
            onClose = { dialogMode = null },
        )
    }
}

/** Shows the app version, server version, clipboard buttons, and a GitHub link.
 *
 * @param versionString         The formatted app-version string to display.
 * @param onShowLicenses        Called when the user taps "View Licenses".
 */
@Composable
private fun AboutSection(
    versionString: String,
    onShowLicenses: () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val serverVersion = ScrcpyConfig.SCRCPY_VERSION

    SettingsSection(title = stringResource(R.string.settings_section_about), expanded = expanded, onToggle = onToggle) {
        NavigationRow(
            icon = Icons.Filled.Save,
            label = stringResource(R.string.settings_ui_about_app),
            value = versionString,
            supporting = stringResource(R.string.settings_scrcpy_server, serverVersion),
            showChevron = false
        )

        SettingsDivider()

        // Required fork attribution under the upstream license (GPLv3 §7). Opens the upstream repo.
        NavigationRow(
            icon = Icons.Filled.Gavel,
            label = stringResource(R.string.settings_fork_attribution),
            value = stringResource(R.string.settings_fork_attribution_supporting),
            supporting = stringResource(R.string.settings_ui_open_repo_hint),
            onClick = { context.openOriginalProjectRepo() }
        )

        SettingsDivider()

        // Optional "support development" link. Opens the maintainer's Ko-fi page in the browser.
        NavigationRow(
            icon = Icons.Filled.Favorite,
            label = stringResource(R.string.settings_support_label),
            value = stringResource(R.string.settings_support_value),
            supporting = stringResource(R.string.settings_support_supporting),
            onClick = { context.openKofi() }
        )

        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CvSecondaryButton(
                text = stringResource(R.string.settings_copy_version),
                onClick = { context.copyToClipboard("Scrcpy-Server Version", ScrcpyConfig.SCRCPY_VERSION) },
                leadingIcon = Icons.Filled.ContentCopy,
                modifier = Modifier.weight(1f)
            )
            CvSecondaryButton(
                text = stringResource(R.string.settings_view_licenses),
                onClick = onShowLicenses,
                leadingIcon = Icons.Filled.Gavel,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── Internal helper composables ────────────────────────────────────────────────────────────

/** A branded, collapsible section: a tappable [CvSectionHeader] (with a rotating chevron) above a
 * [CvCard] grouping its rows. Tapping the header invokes [onToggle]; the body animates in and out.
 * The expand/collapse state is HOISTED to the parent so the Settings screen can run an accordion
 * (at most one section open at a time); this composable is stateless about expansion.
 *
 * @param title    Section heading shown above the card; the whole header row toggles the section.
 * @param expanded Whether this section's body is currently shown.
 * @param onToggle Invoked when the header is tapped (the parent decides the new open-section).
 * @param content  The slot for child rows rendered inside the [CvCard] when expanded.
 */
/**
 * In-app updates: a daily release check (on by default). A new release surfaces as a notification +
 * Home banner, and installs only on an explicit tap.
 */
@Composable
private fun UpdatesSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val isCheckEnabled = remember(updateTrigger) { preferences.isUpdateCheckEnabled() }

    SettingsSection(
        title = stringResource(R.string.settings_section_updates),
        expanded = expanded,
        onToggle = onToggle
    ) {
        SettingsToggleRow(
            icon = Icons.Filled.SystemUpdate,
            label = stringResource(R.string.settings_update_check_label),
            checked = isCheckEnabled,
            onCheckedChange = { actions.setUpdateCheckEnabled(it) },
            description = stringResource(R.string.settings_update_check_description)
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "settingsSectionChevron"
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            CvSectionHeader(text = title, modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(22.dp)
                    .rotate(chevronRotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            CvCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                content()
            }
        }
    }
}

/** A thin inset divider used to separate row clusters inside a [CvCard]. */
@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** Wraps a [M3DropdownField] (and optional hint) so it slots cleanly inside a [CvCard]. */
@Composable
private fun DropdownRow(content: @Composable ColumnScope.() -> Unit) {
    Column(content = content)
}

/** Indents and tints a nested option cluster revealed under an auto-record toggle. */
@Composable
private fun NestedGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        content = content
    )
}

/** A small muted helper line shown beneath a dropdown/field. */
@Composable
private fun HintText(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

/** Circular tinted leading-icon badge used by settings rows. */
@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * A tappable navigation/dialog row: leading icon, label + value (+ optional supporting hint), and a
 * trailing chevron. Used for folder pickers, the filename template, and the About rows.
 */
@Composable
private fun NavigationRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    showChevron: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * A switch row styled for the Signal cards: optional leading icon, label + supporting text, and a
 * teal [Switch]. Tapping anywhere on the row toggles it. Mirrors the behavior of the shared
 * ToggleListItem while matching the redesigned row anatomy.
 */
@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    description: String? = null,
    enabled: Boolean = true
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            RowIcon(icon)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * A radio-button group for choosing which contacts to ignore.
 * When "selected" is active, shows a text field and a "Pick Contacts" button.
 *
 * @param label           Label shown above the radio buttons.
 * @param selectedEnum     The currently active mode ("none", "all", or "selected").
 * @param selectedCount    The number of contacts currently selected
 * @param onSelected      Called with the new active mode when the user taps a radio button.
 * @param onSelectContacts Called when the user taps the "Select Contacts" button; opens the
 *                        [ContactSelectionDialog] via [ContactPickerViewModel].
 */
@Composable
private fun IgnoreContactsOptions(
    label: String,
    selectedEnum: AppPreferences.IgnoreContactsMode,
    selectedCount: Int,
    onSelected: (AppPreferences.IgnoreContactsMode) -> Unit,
    onSelectContacts: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))

        val enumEntries = AppPreferences.IgnoreContactsMode.entries
        enumEntries.forEach { ignoreContactMode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // This make the box/text next to the radio button clickable, not just the button itself, which is more user-friendly.
                    .clickable { onSelected(ignoreContactMode) }
                    .padding(vertical = 4.dp)
            ) {
                // Make the actual radio button (circle) clickable (it's quite small)
                RadioButton(selected = selectedEnum == ignoreContactMode, onClick = { onSelected(ignoreContactMode) })
                Text(
                    text = when (ignoreContactMode) {
                        AppPreferences.IgnoreContactsMode.NONE -> stringResource(R.string.settings_ignore_contacts_none)
                        AppPreferences.IgnoreContactsMode.ALL -> stringResource(R.string.settings_ignore_contacts_all)
                        AppPreferences.IgnoreContactsMode.SELECTED   -> stringResource(R.string.settings_ignore_contacts_selected)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (selectedEnum == AppPreferences.IgnoreContactsMode.SELECTED) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick  = onSelectContacts,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            ) { Text(stringResource(R.string.settings_select_contacts, selectedCount)) }
        }
    }
}

/**
 * Safe Compose Preview for Settings.
 */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        val mockContext = LocalContext.current
        val dummyPreferences = AppPreferences(mockContext)
        val dummyActions = object : SettingsActions {
            override fun setAutoRecordIncoming(enabled: Boolean) {}
            override fun setAutoRecordOutgoing(enabled: Boolean) {}
            override fun setVibrationEnabled(enabled: Boolean) {}
            override fun setIgnoreAnonymousIncoming(enabled: Boolean) {}
            override fun setIgnoreCrossCountryIncoming(enabled: Boolean) {}
            override fun setIgnoreCrossCountryOutgoing(enabled: Boolean) {}
            override fun setIgnoreContactsModeIncoming(modeEnum: AppPreferences.IgnoreContactsMode) {}
            override fun setIgnoreContactsModeOutgoing(modeEnum: AppPreferences.IgnoreContactsMode) {}
            override fun setAudioSource(source: String) {}
            override fun setAudioCodec(codec: String) {}
            override fun setAudioBitRate(bitRate: Int) {}
            override fun setThemeMode(mode: AppPreferences.ThemeMode) {}
            override fun setDynamicColorEnabled(enabled: Boolean) {}
            override fun setShowToastsEnabled(enabled: Boolean) {}
            override fun setAppLanguage(languageCode: String) {}
            override fun setLoggingEnabled(enabled: Boolean) {}
            override fun getAppVersion(): String = "Version 1.0.0 (Mock)"
            override fun setFileNameTemplate(template: String) {}
            override fun setStorageTarget(target: StorageTarget) {}
            override fun setDriveFolderUri(uri: android.net.Uri?) {}
            override fun setRetentionLinked(linked: Boolean) {}
            override fun setRetentionLocalDays(days: Int) {}
            override fun setRetentionDriveDays(days: Int) {}
            override fun setRetentionTimeHour(hour: Int) {}
            override fun setRetentionTimeMinute(minute: Int) {}
            override fun setUpdateCheckEnabled(enabled: Boolean) {}
        }

        SettingsContent(
            preferences = dummyPreferences,
            updateTrigger = 0,
            actions = dummyActions,
            contactPickerState = null,
            onBack = {},
            onSelectFolder = {},
            onSelectDriveFolder = {},
            onOpenContactsIncoming = {},
            onOpenContactsOutgoing = {},
            onConfirmContacts = {},
            onDismissContacts = {},
            onShareLogs = {}
        )
    }
}
