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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.SearchLine
import com.baba.callvault.ui.common.OemGateNotice
import com.baba.callvault.ui.viewmodels.ContactPickerState
import com.baba.callvault.ui.viewmodels.SettingsActions

/**
 * The new Settings screen (design option C): the groups for [scope] as folding cards, each with a one-line
 * summary, the Shizuku mode row above them, and two links at the foot. The only Settings screen since the
 * classic one was removed (`docs/dev-notes/rollback/classic-settings-removal.md`). See
 * `docs/dev-notes/2026-10-20-settings-per-page-plan.md`.
 *
 * Takes the screen's callbacks plus the scope and a way to change it (the "All settings" link, and the
 * Summaries language row's link to Transcripts).
 */
@Composable
fun ScopedSettingsContent(
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
    scope: SettingsScope,
    onScopeChange: (SettingsScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLicensesDialog by remember { mutableStateOf(false) }

    // The search line (option B): while it holds text, every group with a matching row opens and shows only
    // those rows, across all of Settings rather than only this page; the rest are dimmed.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchTexts = rememberSettingsSearchTexts()
    val searchRows = remember(searchQuery, searchTexts) {
        if (searchQuery.isBlank()) null else SettingsSearch.matchingRows(searchTexts, searchQuery)
    }

    // The open group, remembered per scope (scope name -> group key), so Recordings does not reopen on
    // whatever was open under Transcripts. Its own keys, apart from the removed classic screen's section state.
    // A HashMap so it saves into the Bundle as it is.
    // A page with a single group (Transcripts, Summaries) opens it straight away: a lone folded card is
    // one tap of nothing. Closing it is remembered as "" so it stays closed.
    var openGroups by rememberSaveable { mutableStateOf(HashMap<String, String>()) }
    val groups = SettingsGroups.forScope(scope)
    val openGroup = openGroups[scope.name]
        ?.let { SettingsGroup.fromKey(it) }
        ?: if (openGroups[scope.name] == null) groups.singleOrNull() else null
    fun setOpenGroup(target: SettingsScope, group: SettingsGroup?) {
        openGroups = HashMap(openGroups).apply { put(target.name, group?.key ?: "") }
    }

    // Bumped by the links, so the list scrolls to what they opened once the new scope has been composed.
    var scrollRequest by remember { mutableIntStateOf(0) }
    fun jumpTo(target: SettingsScope, group: SettingsGroup?) {
        if (group != null) setOpenGroup(target, group)
        if (target != scope) onScopeChange(target)
        scrollRequest++
    }

    val showsModeRow = scope == SettingsScope.RECORDINGS || scope == SettingsScope.ALL
    val listState = rememberLazyListState()
    // Also on a scope change: the panel stays composed between openings, so without this a page's settings
    // could open scrolled to wherever the previous page's were left.
    LaunchedEffect(scope, scrollRequest) {
        val groupIndex = openGroup?.let { groups.indexOf(it) }?.takeIf { it >= 0 }
        // Items above the groups: the mode row, where shown.
        val itemsAbove = if (showsModeRow) 1 else 0
        val index = if (groupIndex == null) 0 else groupIndex + itemsAbove
        listState.animateScrollToItem(index)
    }

    val callbacks = GroupBodyCallbacks(
        onSelectFolder = onSelectFolder,
        onSelectDriveFolder = onSelectDriveFolder,
        onOpenContactsIncoming = onOpenContactsIncoming,
        onOpenContactsOutgoing = onOpenContactsOutgoing,
        onShareLogs = onShareLogs,
        onSaveLogs = onSaveLogs,
        onExportSettings = onExportSettings,
        onImportSettings = onImportSettings,
        onShowLicenses = { showLicensesDialog = true },
        // Transcripts and summaries share ONE language on purpose; Summaries only links to it.
        onChangeLanguage = {
            val target = if (scope == SettingsScope.ALL) SettingsScope.ALL else SettingsScope.TRANSCRIPTS
            jumpTo(target, SettingsGroup.MODEL_AND_LANGUAGE)
        },
    )

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(titleOf(scope)),
        onBack = onBack,
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                SearchLine(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.settings_search_hint),
                )
            }
            if (searchRows != null) {
                settingsSearchResults(searchRows, searchQuery, preferences, updateTrigger, actions, callbacks)
                return@LazyColumn
            }
            if (showsModeRow) {
                item(key = "mode") {
                    // Kept on purpose (maintainer, 2026-10-21): it decides which recording engine runs, so it
                    // stays the first thing on the recording pages.
                    Column {
                        PrivilegedModeSubSection(onModeChanged = { actions.refreshSettings() })
                        OemGateNotice(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            items(groups, key = { it.key }) { group ->
                val expanded = openGroup == group
                SettingsGroupCard(
                    icon = iconOf(group),
                    title = stringResource(titleOf(group)),
                    summary = groupSummaryText(group, preferences, updateTrigger, expanded, actions),
                    explanation = groupExplanationText(group, preferences, updateTrigger),
                    expanded = expanded,
                    onToggle = { setOpenGroup(scope, if (expanded) null else group) },
                ) {
                    GroupBody(group, preferences, updateTrigger, actions, callbacks)
                }
            }
            item(key = "footer") {
                Column {
                    SettingsFooterLink(
                        text = stringResource(R.string.settings_link_share_debug_report),
                        onClick = { jumpTo(SettingsScope.ALL, SettingsGroup.HELP_AND_ABOUT) },
                    )
                    if (scope != SettingsScope.ALL) {
                        SettingsFooterLink(
                            text = stringResource(R.string.settings_link_all_settings),
                            onClick = { jumpTo(SettingsScope.ALL, null) },
                        )
                    }
                }
            }
        }
    }

    if (showLicensesDialog) {
        LicensesDialog(onDismiss = { showLicensesDialog = false })
    }
    SettingsContactPicker(contactPickerState, onConfirmContacts, onDismissContacts)
}

private fun titleOf(scope: SettingsScope): Int = when (scope) {
    SettingsScope.RECORDINGS -> R.string.settings_title_recordings
    SettingsScope.TRANSCRIPTS -> R.string.settings_title_transcripts
    SettingsScope.SUMMARIES -> R.string.settings_title_summaries
    SettingsScope.ALL -> R.string.general_settings
}

private fun titleOf(group: SettingsGroup): Int = when (group) {
    SettingsGroup.CALLS_TO_RECORD -> R.string.settings_group_calls_to_record
    SettingsGroup.CALLS_TO_SKIP -> R.string.settings_group_calls_to_skip
    SettingsGroup.SOUND_AND_NAMES -> R.string.settings_group_sound_and_names
    SettingsGroup.SAVE_AND_SYNC -> R.string.settings_group_save_and_sync
    SettingsGroup.CLEANING_UP -> R.string.settings_group_cleaning_up
    SettingsGroup.CONNECTION -> R.string.settings_group_connection
    SettingsGroup.MODEL_AND_LANGUAGE -> R.string.settings_group_model_and_language
    SettingsGroup.WHEN_TO_TRANSCRIBE -> R.string.settings_group_when_to_transcribe
    SettingsGroup.SUMMARIES -> R.string.home_summaries_title
    SettingsGroup.APP -> R.string.settings_group_app
    SettingsGroup.HELP_AND_ABOUT -> R.string.settings_group_help_and_about
}

private fun iconOf(group: SettingsGroup): ImageVector = when (group) {
    SettingsGroup.CALLS_TO_RECORD -> Icons.Filled.Call
    SettingsGroup.CALLS_TO_SKIP -> Icons.Filled.PhoneDisabled
    SettingsGroup.SOUND_AND_NAMES -> Icons.Filled.GraphicEq
    SettingsGroup.SAVE_AND_SYNC -> Icons.Filled.Folder
    SettingsGroup.CLEANING_UP -> Icons.Filled.CleaningServices
    SettingsGroup.CONNECTION -> Icons.Filled.Cable
    SettingsGroup.MODEL_AND_LANGUAGE -> Icons.Filled.Translate
    SettingsGroup.WHEN_TO_TRANSCRIBE -> Icons.Filled.Schedule
    SettingsGroup.SUMMARIES -> Icons.Filled.Summarize
    SettingsGroup.APP -> Icons.Filled.Tune
    SettingsGroup.HELP_AND_ABOUT -> Icons.AutoMirrored.Filled.HelpOutline
}

/**
 * The search view: every group of all Settings with a matching row, opened and narrowed to those rows; then the
 * groups with none, dimmed. The mode row and the footer links step aside while a search is on.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.settingsSearchResults(
    rows: Set<String>,
    query: String,
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    callbacks: GroupBodyCallbacks,
) {
    val all = SettingsGroups.forScope(SettingsScope.ALL)
    val (hits, misses) = all.partition { g -> SettingsGroups.rowsOf(g).any { it in rows } }
    if (hits.isEmpty()) {
        item(key = "search-none") {
            androidx.compose.material3.Text(
                text = stringResource(R.string.settings_search_none, query.trim()),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            )
        }
    }
    items(hits, key = { "hit-" + it.key }) { group ->
        // What the group will show, not what matched: a folded match shows as its switch (SettingsFolds).
        val switches = remember(updateTrigger, rows) { SettingsFolds.Switches.of(preferences) }
        val count = SettingsFolds.shownRows(SettingsGroups.rowsOf(group).filter { it in rows }.toSet(), switches).size
        SettingsGroupCard(
            icon = iconOf(group),
            title = stringResource(titleOf(group)),
            summary = androidx.compose.ui.res.pluralStringResource(R.plurals.settings_search_matches, count, count),
            expanded = true,
            onToggle = {},
        ) {
            WithSettingsSearchMatches(rows) {
                GroupBody(group, preferences, updateTrigger, actions, callbacks)
            }
        }
    }
    items(misses, key = { "miss-" + it.key }) { group ->
        androidx.compose.foundation.layout.Box(Modifier.alpha(0.45f)) {
            SettingsGroupCard(
                icon = iconOf(group),
                title = stringResource(titleOf(group)),
                summary = stringResource(R.string.settings_search_no_matches),
                expanded = false,
                onToggle = {},
            ) {}
        }
    }
}
