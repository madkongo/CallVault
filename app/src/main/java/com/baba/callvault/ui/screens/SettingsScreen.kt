/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.baba.callvault.system.diagnostics.ReportBundle
import java.time.LocalDateTime
import java.io.File
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import com.baba.callvault.R
import com.baba.callvault.system.PersistentFolderPickerContract
import com.baba.callvault.system.diagnostics.SystemLogCollector
import com.baba.callvault.system.shareLogFiles
import com.baba.callvault.transcription.model.DownloadNetwork
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.data.TranscriptionMode
import com.baba.callvault.summary.SummaryModel
import com.baba.callvault.transcription.model.DownloadableModel
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.takePersistableFolderPermission
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.ui.common.ContactSelectionDialog
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.OemGateNotice
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.viewmodels.ContactPickerType
import com.baba.callvault.ui.viewmodels.ContactPickerViewModel
import com.baba.callvault.ui.viewmodels.SettingsActions
import com.baba.callvault.ui.viewmodels.SettingsViewModel
import com.baba.callvault.ui.viewmodels.ContactPickerState
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import android.net.Uri
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save

/**
 * Stateful wrapper for the Settings screen that connects [SettingsViewModel] to [SettingsContent].
 *
 * @param viewModel Handles saving whenever the user changes a setting.
 * @param onBack    Called when the user taps the top-bar back affordance; the router maps this to
 *                  [com.baba.callvault.ui.viewmodels.AppNavViewModel.navigateBack].
 * @param modifier  Optional modifier for the root scaffold.
 * @param settingsScope The page Settings was opened from; only the new screen uses it.
 * @param onScopeChange Called when the new screen's links move to another page's settings.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    settingsScope: SettingsScope = SettingsScope.ALL,
    onScopeChange: (SettingsScope) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Shown while the debug report is being built. Building it makes several ADB round-trips, and on
    // a phone with a flaky transport that is slow — during which the old code showed NOTHING, so the
    // button read as broken, people tapped again, and leaving the screen cancelled the whole thing
    // (the scope above is the composable's). Field-reported by a tester on 2026-09-03 as "the share
    // debug function doesn't work", reproduced as "works only on the second visit".
    var preparingReport by remember { mutableStateOf(false) }


    /**
     * Builds the app's debug report and, within [SYSTEM_REPORT_BUDGET_MS], the system report. Shared by Share and
     * Save, so both hand over exactly the same thing. The first file is null only when there is nothing to report; the
     * second when the system half could not be collected in time.
     */
    suspend fun buildReports(): Pair<File?, File?> {
        val report = withContext(Dispatchers.IO) { AppLogger.buildShareableReport(context) }
        // The system slice carries the daemon's lines and the platform's — the half no bug
        // report has ever contained. Null when logcat gave nothing usable, in which case the
        // app's own report still goes on its own.
        //
        // BOUNDED, and that is the whole point. It makes up to seven ADB round-trips, each
        // retrying with a forced reconnect, so on a phone whose transport is unhealthy it
        // can outlast any patience. Unbounded, a hang here threw away the app report too —
        // which was already built and sitting in the variable above — and the user got
        // nothing at all. Now the optional half can fail and the report still goes out.
        //
        // Run on a scope of its own and time out the AWAIT, not the work. buildReport is
        // one long blocking block inside withContext(Dispatchers.IO) with no suspension
        // points, and coroutine cancellation is cooperative — a timeout wrapped straight
        // around it cannot interrupt a blocked socket read, so it would look like a bound
        // and be none. await() IS a suspension point, so this returns on time and abandons
        // the stuck thread instead of waiting for it. A detached scope keeps that
        // abandoned job from holding up this coroutine's completion as a child would.
        val collector = CoroutineScope(Dispatchers.IO)
        val pending = collector.async { SystemLogCollector.buildReport(context) }
        val systemReport = withTimeoutOrNull(SYSTEM_REPORT_BUDGET_MS) { pending.await() }
        if (systemReport == null) {
            collector.cancel()
            AppLogger.w(
                "CV:Settings",
                "System report not attached (empty or over ${SYSTEM_REPORT_BUDGET_MS}ms); " +
                    "going on with the app report alone"
            )
        }
        return report to systemReport
    }

    // Save as. The reports live in the app's private cache, where no file manager can see them and `adb pull` cannot
    // reach them on a release build, so Share was the only way out — and a user who will not send logs through email
    // or a messenger could not get them at all (mirror176, #28 and #29). Android's own save dialog lets them pick the
    // folder and the name, and needs no storage permission. Both reports go into the one file it creates.
    val saveReportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null || preparingReport) return@rememberLauncherForActivityResult
        scope.launch {
            preparingReport = true
            try {
                val (report, systemReport) = buildReports()
                if (report == null) {
                    Toast.makeText(context, R.string.settings_bugreport_share_empty, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val bundle = ReportBundle.combine(report.readText(), systemReport?.readText())
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bundle.toByteArray()) } != null
                    }.onFailure { AppLogger.w("CV:Settings", "Saving the report failed: ${it.message}") }
                        .getOrDefault(false)
                }
                Toast.makeText(
                    context,
                    if (saved) R.string.settings_bugreport_saved else R.string.settings_bugreport_save_failed,
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                preparingReport = false
            }
        }
    }

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
                // A new folder starts unhidden; carry the setting over to it.
                viewModel.preferences.setNomediaCreatedByUs(false)
                com.baba.callvault.system.storage.HideRecordings.applyAsync(context)
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

    // Export settings to a JSON file the user names — the system save dialog picks place + name, no
    // permission needed. Only device-independent preferences are written (see AppPreferences.EXPORTABLE_KEYS).
    val exportSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")
                        ?.use { it.write(viewModel.exportSettingsJson().toByteArray()) } != null
                }.onFailure { AppLogger.w("CV:Settings", "Settings export failed: ${it.message}") }
                    .getOrDefault(false)
            }
            Toast.makeText(
                context,
                if (saved) R.string.settings_export_done else R.string.settings_export_failed,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // Import settings: pick a JSON file, read it, then confirm before overwriting.
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    val importSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
                    .getOrNull()
            }
            if (text.isNullOrBlank()) {
                Toast.makeText(context, R.string.settings_import_failed, Toast.LENGTH_LONG).show()
            } else {
                pendingImportJson = text
            }
        }
    }

    // Every greyed-out row reads the mode through this, so a mode switch re-greys the whole screen at once.
    // Read straight from preferences inside each row, the rows kept the old mode's state until Settings
    // was reopened (OP9, 2026-10-19) — leaving offline recording looking available in Shizuku mode.
    val privilegedMode = remember(updateTrigger) { viewModel.preferences.getPrivilegedMode() }
    // Which screen opens. Re-read on every refresh, so the Debug switch takes effect at once. Rollback layer 1
    // of the settings-per-page plan: the classic screen is untouched; the new one is the default since 2026-10-21.
    val useClassicScreen = remember(updateTrigger) { viewModel.preferences.isClassicSettingsScreen() }

    // Seed each picker with its OWN current folder so it opens there, instead of letting
    // Android's DocumentsUI reopen at the last-browsed location (which, after setting Drive,
    // made re-picking the local folder open at the Drive path).
    val onSelectFolder = { folderPickerLauncher.launch(viewModel.preferences.getRecordingFolderUri()) }
    val onSelectDriveFolder = { driveFolderPickerLauncher.launch(viewModel.preferences.getDriveFolderUri()) }
    val onOpenContactsIncoming = { contactPickerViewModel.openContactPicker(ContactPickerType.INCOMING) }
    val onOpenContactsOutgoing = { contactPickerViewModel.openContactPicker(ContactPickerType.OUTGOING) }
    val onConfirmContacts: (Set<String>) -> Unit = { numbers ->
        contactPickerViewModel.confirmContactPicker(numbers)
        // Refresh the screen so the new contact list information is shown immediately after confirming and closing the dialog.
        viewModel.refresh()
    }
    val onDismissContacts = { contactPickerViewModel.dismissContactPicker() }
    // Build the report off the main thread, then hand it to the system share-sheet. The Share
    // entry point is only shown when a valid log file exists, so the null branch is a safety net.
    val onShareLogs: () -> Unit = {
        if (!preparingReport) scope.launch {
            preparingReport = true
            try {
                val (report, systemReport) = buildReports()
                if (report != null) {
                    context.shareLogFiles(listOfNotNull(report, systemReport))
                } else {
                    Toast.makeText(context, R.string.settings_bugreport_share_empty, Toast.LENGTH_LONG).show()
                }
            } finally {
                preparingReport = false
            }
        }
    }
    // The save dialog opens first, named with the date and time; the report is built once a place is chosen.
    val onSaveLogs: () -> Unit = {
        if (!preparingReport) {
            val now = LocalDateTime.now()
            saveReportLauncher.launch(
                ReportBundle.suggestedFileName(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute)
            )
        }
    }
    val onExportSettings = { exportSettingsLauncher.launch("CallVault-settings-${java.time.LocalDate.now()}.json") }
    val onImportSettings = { importSettingsLauncher.launch(arrayOf("application/json", "text/*")) }

    CompositionLocalProvider(LocalPrivilegedMode provides privilegedMode) {
        if (useClassicScreen) {
            SettingsContent(
                preferences = viewModel.preferences,
                updateTrigger = updateTrigger,
                actions = viewModel,
                contactPickerState = contactPickerState,
                onBack = onBack,
                onSelectFolder = onSelectFolder,
                onSelectDriveFolder = onSelectDriveFolder,
                onOpenContactsIncoming = onOpenContactsIncoming,
                onOpenContactsOutgoing = onOpenContactsOutgoing,
                onConfirmContacts = onConfirmContacts,
                onDismissContacts = onDismissContacts,
                onShareLogs = onShareLogs,
                onSaveLogs = onSaveLogs,
                onExportSettings = onExportSettings,
                onImportSettings = onImportSettings,
                modifier = modifier
            )
        } else {
            ScopedSettingsContent(
                preferences = viewModel.preferences,
                updateTrigger = updateTrigger,
                actions = viewModel,
                contactPickerState = contactPickerState,
                onBack = onBack,
                onSelectFolder = onSelectFolder,
                onSelectDriveFolder = onSelectDriveFolder,
                onOpenContactsIncoming = onOpenContactsIncoming,
                onOpenContactsOutgoing = onOpenContactsOutgoing,
                onConfirmContacts = onConfirmContacts,
                onDismissContacts = onDismissContacts,
                onShareLogs = onShareLogs,
                onSaveLogs = onSaveLogs,
                onExportSettings = onExportSettings,
                onImportSettings = onImportSettings,
                scope = settingsScope,
                onScopeChange = onScopeChange,
                modifier = modifier
            )
        }
    }

    // Modal on purpose: it gives the tap an immediate answer, and it stops a second tap or a walk
    // back to the previous screen from cancelling work that is already under way.
    if (preparingReport) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.settings_bugreport_preparing))
                }
            },
        )
    }

    // Confirm before overwriting the current settings with an imported file.
    pendingImportJson?.let { json ->
        AlertDialog(
            onDismissRequest = { pendingImportJson = null },
            title = { Text(stringResource(R.string.settings_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_import_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportJson = null
                    val applied = viewModel.importSettings(json)
                    Toast.makeText(
                        context,
                        if (applied >= 0) context.getString(R.string.settings_import_done, applied)
                        else context.getString(R.string.settings_import_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }) { Text(stringResource(R.string.settings_import_confirm_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportJson = null }) {
                    Text(stringResource(R.string.general_cancel))
                }
            },
        )
    }
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
 * @param onSaveLogs             Called to save diagnostic logs to a file the user picks (Debug section).
 * @param modifier               Optional size/position modifier.
 */
/** Hard ceiling on the optional system-log half of a debug report. See the share handler. */
private const val SYSTEM_REPORT_BUDGET_MS = 45_000L

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
    onSaveLogs: () -> Unit,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showLicensesDialog by remember { mutableStateOf(false) }

    // Accordion: at most one section open at a time; Recording & storage is open on entry. State is
    // hoisted here (above the LazyColumn) so it is shared across all sections. Tapping the open section
    // closes it (null = none open); tapping any other section opens it and closes the previous one.
    // Everything closed on entry: the panel then opens on a short list of section names, which is
    // navigable at a glance. Auto-opening one buried the other five under a screenful of its rows.
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
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
                // Above every section and outside all of them, so it is the first thing on the screen.
                //
                // It decides which of two completely different recording engines runs — and therefore
                // which features exist at all — so burying it three taps down under GENERAL >
                // Privileges made the single most consequential setting the hardest one to find. It is
                // also the first thing worth checking when something is not working, which is exactly
                // when nobody wants to go hunting for it.
                PrivilegedModeSubSection(onModeChanged = { actions.refreshSettings() })
                // The wizard cannot be re-run, so a phone whose OEM blocks the grant needs to be able to
                // find this out here too. Self-hiding when it does not apply.
                OemGateNotice(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            // The same card as at the top of the new screen, with its own wording: the one way between them.
            item { SettingsLayoutSwitchCard(preferences, actions) }
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
                    // SECTION_RETENTION also opens Storage: retention used to be its own accordion, and a
                    // user whose saved open-section is the old key should land on the section that now
                    // contains it rather than on a collapsed screen.
                    expanded = openSection == SECTION_STORAGE || openSection == SECTION_RETENTION,
                    onToggle = { onToggleSection(SECTION_STORAGE) },
                    onSelectFolder = onSelectFolder,
                    onSelectDriveFolder = onSelectDriveFolder
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
                // Summaries live inside this section rather than beside it. A summary is read from
                // a transcript and is useless without one, and — the reason this moved — they used
                // to have a language setting each. Setting the wrong one produced a transcript in
                // the wrong language with no hint as to why, which is exactly what happened.
                TranscriptionSection(
                    preferences, updateTrigger, actions,
                    expanded = openSection == SECTION_TRANSCRIPTION,
                    onToggle = { onToggleSection(SECTION_TRANSCRIPTION) },
                    summaryRows = { SummarySettingsRows(preferences, updateTrigger, actions) }
                )
            }
            item {
                GeneralSection(
                    preferences = preferences,
                    updateTrigger = updateTrigger,
                    actions = actions,
                    // Old keys still open General, so a user whose saved section was Visual,
                    // Experimental or Updates lands on the section that now contains it.
                    expanded = openSection in setOf(SECTION_GENERAL, SECTION_VISUAL, SECTION_EXPERIMENTAL, SECTION_UPDATES),
                    onToggle = { onToggleSection(SECTION_GENERAL) }
                )
            }
            // Debug section: always visible so anyone can enable logging and share logs to report an issue.
            item {
                BugReportSection(
                    preferences, updateTrigger, actions, onShareLogs, onSaveLogs,
                    expanded = openSection == SECTION_BUG_REPORT,
                    onToggle = { onToggleSection(SECTION_BUG_REPORT) }
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
            // Section-less, at the very bottom: back up or restore your settings.
            item {
                ExportImportRow(onExport = onExportSettings, onImport = onImportSettings)
            }
        }
    }

    if (showLicensesDialog) {
        LicensesDialog(onDismiss = { showLicensesDialog = false })
    }

    // The contact-picker dialog sits on top of the settings content.
    SettingsContactPicker(contactPickerState, onConfirmContacts, onDismissContacts)
}

// Settings accordion: stable keys identifying each section. At most one section is open at a time;
// [SECTION_RECORDING] is the one open when Settings is first entered.
private const val SECTION_RECORDING = "recording"
private const val SECTION_STORAGE = "storage"
private const val SECTION_RETENTION = "retention"
private const val SECTION_AUDIO = "audio"
private const val SECTION_TRANSCRIPTION = "transcription"
private const val SECTION_GENERAL = "general"

// Sub-section keys. Scoped to their parent section, so the same value can never collide across two.
internal const val SUB_FILE_NAME = "sub_file_name"
internal const val SUB_PHONE_CALLS = "sub_phone_calls"
internal const val SUB_INCOMING = "sub_incoming"
internal const val SUB_OUTGOING = "sub_outgoing"
internal const val SUB_WHERE = "sub_where"
internal const val SUB_UPLOAD = "sub_upload"
internal const val SUB_RETENTION = "sub_retention"
internal const val SUB_VISUAL = "sub_visual"
internal const val SUB_PRIVACY = "sub_privacy"
internal const val SUB_EXPERIMENTAL = "sub_experimental"
internal const val SUB_UPDATES = "sub_updates"
private const val SUB_PRIVILEGES = "privileges"
private const val SECTION_VISUAL = "visual"
private const val SECTION_EXPERIMENTAL = "reliability"   // key kept so a user's open-section state survives the rename
private const val SECTION_BUG_REPORT = "bug_report"
private const val SECTION_UPDATES = "updates"
private const val SECTION_ABOUT = "about"

// ── Settings sections ──────────────────────────────────────────────────────────────────────

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
            override fun refreshSettings() {}
            override fun setCarrierRecording(enabled: Boolean) {}
            override fun setAutoRecordIncoming(enabled: Boolean) {}
            override fun setAutoRecordOutgoing(enabled: Boolean) {}
            override fun setRecordFromAnswer(enabled: Boolean) {}
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
            override fun setMinDurationSeconds(seconds: Int) {}
            override fun setWriteMetadataFileEnabled(enabled: Boolean) {}
            override fun setHideRecordingsEnabled(enabled: Boolean) {}
            override fun setWriteTranscriptSidecarEnabled(enabled: Boolean) {}
            override fun exportSettingsJson(): String = "{}"
            override fun importSettings(json: String): Int = 0
            override fun setStorageCapBytes(bytes: Long) {}
            override fun setRetentionLocalDays(days: Int) {}
            override fun setRetentionDriveDays(days: Int) {}
            override fun setRetentionTimeHour(hour: Int) {}
            override fun setRetentionTimeMinute(minute: Int) {}
            override fun setSyncScheduleMode(mode: SyncScheduleMode) {}
            override fun setTranscriptionMode(mode: TranscriptionMode) {}
            override fun setTranscriptionHour(hour: Int) {}
            override fun setTranscriptionMinute(minute: Int) {}
            override fun setTranscriptionRequiresCharging(required: Boolean) {}
        override fun setTranscriptionConfirmBeforeRun(confirm: Boolean) {}
            override fun setTranscriptionBatchLimit(limit: Int) {}
            override fun setTranscriptionModelId(id: String) {}
            override fun setTranscriptionLanguage(language: String?) {}
            override fun setTranscriptionAskLanguage(ask: Boolean) {}
            override fun downloadTranscriptionModel(model: TranscriptionModel, network: DownloadNetwork) {}
            override fun cancelTranscriptionModelDownload(model: TranscriptionModel) {}
            override fun deleteTranscriptionModel(model: TranscriptionModel) {}
            override fun downloadSummaryModel(model: SummaryModel, network: DownloadNetwork) {}
            override fun cancelSummaryModelDownload(model: SummaryModel) {}
            override fun deleteSummaryModel(model: SummaryModel) {}
            override fun importModel(model: DownloadableModel, source: Uri) {}
            override fun cancelModelImport(model: DownloadableModel) {}
            override fun backUpModel(model: DownloadableModel, target: Uri) {}
            override fun cancelModelBackup(model: DownloadableModel) {}
            override fun setSyncTimeHour(hour: Int) {}
            override fun setSyncTimeMinute(minute: Int) {}
            override fun setSyncDayOfWeek(day: Int) {}
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
            onShareLogs = {},
            onSaveLogs = {},
            onExportSettings = {},
            onImportSettings = {}
        )
    }
}
