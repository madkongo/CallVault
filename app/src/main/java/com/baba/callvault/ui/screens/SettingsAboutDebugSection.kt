/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.baba.callvault.ui.common.verticalScrollbar
import com.baba.callvault.ui.common.horizontalScrollbar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.baba.callvault.R
import com.baba.callvault.system.copyToClipboard
import com.baba.callvault.system.openOriginalProjectRepo
import com.baba.callvault.ui.common.formatByteSize
import com.baba.callvault.ui.common.SupportDialog
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.scrcpy.ScrcpyConfig
import com.baba.callvault.ui.common.CvDestructiveButton
import com.baba.callvault.ui.common.CvPrimaryButton
import com.baba.callvault.ui.common.CvSecondaryButton
import com.baba.callvault.ui.viewmodels.SettingsActions
import com.baba.callvault.ui.viewmodels.SettingsViewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Save

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
 * @param onSaveLogs    Called to save the diagnostic log report to a file the user picks.
 */
@Composable
internal fun BugReportSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    onShareLogs: () -> Unit,
    onSaveLogs: () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val isLoggingEnabled = remember(updateTrigger) { preferences.isLoggingEnabled() }
    // Re-checked on every settings change (e.g. right after the toggle flips off) so the Share
    // button appears as soon as a capture is frozen on disk.
    val hasLogs = remember(updateTrigger) { AppLogger.hasLogs() }
    // The setup journal is written whether or not logging was ever switched on, so everything below
    // — sharing it, reading it, deleting it — has to be reachable without the opt-in log existing.
    // Gating on the opt-in log alone hid the journal on exactly the phones it is written for.
    val hasAnyDiagnostics = remember(updateTrigger) { AppLogger.hasAnyDiagnostics() }
    val logSize = remember(updateTrigger) { AppLogger.logSizeBytes() + AppLogger.setupJournalSizeBytes() }
    val hasJournal = remember(updateTrigger) { AppLogger.hasSetupJournal() }
    // Saveable: rotating the phone recreates the screen, and a plain remember closed the viewer mid-read.
    var showLogViewer by rememberSaveable { mutableStateOf(false) }
    var confirmClearLog by remember { mutableStateOf(false) }

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
        } else {
            // Offered whenever logging is off, with no precondition on there being a log: the report
            // always carries the configuration header, and the setup journal when there is one. The
            // old gate asked for a log file first, so the maintainer had to switch logging on and off
            // again just to reach Share — for a report whose useful half needed neither.
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // In ⓘ mode this is behind the "Help and about" card's ⓘ (see groupExplanationText).
                if (!LocalSettingsDescriptionsAsInfo.current) {
                    Text(
                        text = stringResource(R.string.settings_bugreport_share_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Side by side: Share hands the report to another app, Save writes it to a file on the phone.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Both teal: two equal ways to get the same report out. Cv buttons, because a plain Button
                    // is shorter than they are and the row sat uneven.
                    CvPrimaryButton(
                        text = stringResource(R.string.settings_bugreport_share),
                        onClick = onShareLogs,
                        modifier = Modifier.weight(1f),
                    )
                    CvPrimaryButton(
                        text = stringResource(R.string.settings_bugreport_save),
                        onClick = onSaveLogs,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        // The log file itself — visible whether or not logging is currently running. Until now the
        // file was invisible and the only way to clear it was to toggle logging off and on again,
        // which is a side effect nobody would guess at.
        if (hasAnyDiagnostics) {
            SettingsDivider()
            NavigationRow(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                label = stringResource(R.string.settings_debug_log_file),
                value = formatByteSize(logSize),
                supporting = if (hasJournal) stringResource(R.string.settings_debug_journal_hint)
                else stringResource(R.string.settings_debug_log_view_hint),
                onClick = { showLogViewer = true }
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // Red: this row only exists while there is a log or journal on disk, so the button always
                // removes something real.
                CvDestructiveButton(
                    text = stringResource(R.string.settings_debug_log_delete),
                    onClick = { confirmClearLog = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

    }

    if (showLogViewer) {
        DebugLogViewer(onDismiss = { showLogViewer = false })
    }

    if (confirmClearLog) {
        AlertDialog(
            onDismissRequest = { confirmClearLog = false },
            title = { Text(stringResource(R.string.settings_debug_log_delete_title)) },
            text = { Text(stringResource(R.string.settings_debug_log_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearLog = false
                    AppLogger.clearLogs()
                    // The host keeps its own copy in memory; deleting only the app's half would let
                    // lines the user thought they erased reappear in the next report.
                    RecorderBackend.clearHostDiagnostics()
                    // Logging may still be running, so the file is recreated immediately and empty.
                    // Refreshing re-reads hasLogs/size rather than leaving a stale figure on screen.
                    actions.refreshSettings()
                }) { Text(stringResource(R.string.settings_debug_log_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearLog = false }) {
                    Text(stringResource(R.string.general_cancel))
                }
            },
        )
    }
}

/** How much of the log the in-app viewer shows. The file self-trims at 1000 lines. */
private const val LOG_VIEW_LINES = 500

/**
 * Reads the tail of the debug log into a full-screen page.
 *
 * It used to be a small popup that cut every line off at the right edge, scrolled in both directions with no sign it
 * could, and closed when the phone was rotated (seen on the OP9 on 2026-09-11, while checking mirror176's log
 * feedback in #28 and #29). Now it fills the
 * screen, wraps lines by default with a switch to scroll sideways instead, shows scrollbars, and opens at the newest
 * lines — the end is what someone opens a log to read.
 */
@Composable
private fun DebugLogViewer(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    // Once per opening: jumping to the end again after a rotation would throw away where the reader had scrolled to.
    var openedAtEnd by rememberSaveable { mutableStateOf(false) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()

    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) { AppLogger.readTail(LOG_VIEW_LINES) }
        loaded = true
    }

    LaunchedEffect(loaded) {
        if (!loaded || openedAtEnd) return@LaunchedEffect
        // maxValue is Int.MAX_VALUE until the text is laid out, and 0 when it all fits.
        val end = snapshotFlow { vertical.maxValue }.first { it != Int.MAX_VALUE }
        if (end > 0) vertical.scrollTo(end)
        openedAtEnd = true
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.general_close),
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_debug_log_file),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    // Label and switch toggle together, so TalkBack reads "Wrap, Switch, On" as one stop.
                    Row(
                        modifier = Modifier.toggleable(
                            value = wrap,
                            role = Role.Switch,
                            onValueChange = { wrap = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_debug_log_wrap),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = wrap,
                            onCheckedChange = null,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        )
                    }
                }
                HorizontalDivider()
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val content = text
                    when {
                        !loaded -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        content.isNullOrBlank() -> Text(
                            text = stringResource(R.string.settings_debug_log_empty),
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        else -> {
                            val barColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                            Text(
                                text = content,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                softWrap = wrap,
                                // Scrollbars before the scrolls, so they are drawn at the size of the visible area.
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScrollbar(vertical, barColor)
                                    .then(if (wrap) Modifier else Modifier.horizontalScrollbar(horizontal, barColor))
                                    .verticalScroll(vertical)
                                    .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal))
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Shows the app version, server version, clipboard buttons, and a GitHub link.
 *
 * @param versionString         The formatted app-version string to display.
 * @param onShowLicenses        Called when the user taps "View Licenses".
 */
@Composable
internal fun AboutSection(
    versionString: String,
    onShowLicenses: () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val serverVersion = ScrcpyConfig.SCRCPY_VERSION
    var showSupportDialog by remember { mutableStateOf(false) }

    if (showSupportDialog) {
        SupportDialog(onDismiss = { showSupportDialog = false })
    }

    SettingsSection(title = stringResource(R.string.settings_section_about), expanded = expanded, onToggle = onToggle) {
        NavigationRow(
            icon = Icons.Filled.Save,
            label = stringResource(R.string.settings_ui_about_app),
            value = versionString,
            supporting = stringResource(R.string.settings_scrcpy_server, serverVersion),
            showChevron = false,
            // A version number: a value, not an explanation.
            keepSupportingInline = true,
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

        // Optional "support development" link. Offers Ko-fi and PayPal; both open in the browser.
        NavigationRow(
            icon = Icons.Filled.Favorite,
            label = stringResource(R.string.settings_support_label),
            value = stringResource(R.string.settings_support_value),
            supporting = stringResource(R.string.settings_support_supporting),
            onClick = { showSupportDialog = true }
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
