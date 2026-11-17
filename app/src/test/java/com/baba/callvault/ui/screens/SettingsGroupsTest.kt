/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.ui.navigation.HomeSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which groups each page's Settings shows (round 2, grouped by what you want to do). One table, so the pages
 * cannot drift apart, and so a setting cannot fall off the "everything" view when it moves between groups.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsGroupsTest {

    private val all = SettingsGroups.forScope(SettingsScope.ALL)

    @Test
    fun `a shortcut to a group opens the page that holds it, and that page shows the group`() {
        // The speech-model and summariser shortcuts (BabApps item 128) rely on this: the panel is switched to
        // pageOf(group), so the group must be one of that page's cards or it opens onto nothing.
        SettingsGroup.entries.forEach { group ->
            val page = SettingsGroups.pageOf(group)
            assertTrue("$group is not shown on $page", group in SettingsGroups.forScope(page))
        }
        assertEquals(SettingsScope.TRANSCRIPTS, SettingsGroups.pageOf(SettingsGroup.TRANSCRIPTION_AND_SUMMARIES))
    }

    @Test
    fun `recordings shows the six recording groups in order`() {
        assertEquals(
            listOf(
                SettingsGroup.CALLS_TO_RECORD,
                SettingsGroup.CALLS_TO_SKIP,
                SettingsGroup.SOUND_AND_NAMES,
                SettingsGroup.SAVE_AND_SYNC,
                SettingsGroup.CLEANING_UP,
                SettingsGroup.CONNECTION,
            ),
            SettingsGroups.forScope(SettingsScope.RECORDINGS),
        )
    }

    @Test
    fun `transcripts and summaries are one group (BabApps item 135)`() {
        assertEquals(
            listOf(SettingsGroup.TRANSCRIPTION_AND_SUMMARIES),
            SettingsGroups.forScope(SettingsScope.TRANSCRIPTS),
        )
        // Both pages open the same one section.
        assertEquals(SettingsGroups.forScope(SettingsScope.TRANSCRIPTS), SettingsGroups.forScope(SettingsScope.SUMMARIES))
    }

    @Test
    fun `the merged group holds the transcription rows, the summary rows and the summary language`() {
        val rows = SettingsGroups.rowsOf(SettingsGroup.TRANSCRIPTION_AND_SUMMARIES)
        listOf(
            "TranscriptionLanguageRows", "TranscriptionScheduleRows", "TranscriptionConfirmRow",
            "SummaryLanguageRow", "SummarySettingsRows",
        ).forEach { assertTrue("$it is not in the merged group", it in rows) }
        val keys = SettingsGroups.settingKeysOf(SettingsGroup.TRANSCRIPTION_AND_SUMMARIES)
        listOf("transcription_language", "transcription_mode", "summary_language", "summary_confirm_requirements")
            .forEach { assertTrue("$it is not in the merged group", it in keys) }
    }

    @Test
    fun `all shows every group, ending with app and help`() {
        assertEquals(
            SettingsGroups.forScope(SettingsScope.RECORDINGS) +
                SettingsGroups.forScope(SettingsScope.TRANSCRIPTS) +
                listOf(SettingsGroup.APP, SettingsGroup.HELP_AND_ABOUT),
            all,
        )
        assertEquals(SettingsGroup.entries.toSet(), all.toSet())
    }

    @Test
    fun `all is a superset of every other scope`() {
        SettingsScope.entries.forEach { scope ->
            val missing = SettingsGroups.forScope(scope).toSet() - all.toSet()
            assertTrue("$scope has groups that All settings lacks: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `no group appears twice within a scope`() {
        SettingsScope.entries.forEach { scope ->
            val groups = SettingsGroups.forScope(scope)
            assertEquals("duplicate in $scope: $groups", groups.size, groups.toSet().size)
        }
    }

    @Test
    fun `no row and no setting has two homes`() {
        val rows = all.flatMap { SettingsGroups.rowsOf(it) }
        assertEquals("a row in two groups: $rows", rows.size, rows.toSet().size)
        val keys = all.flatMap { SettingsGroups.settingKeysOf(it) }
        assertEquals("a setting in two groups: $keys", keys.size, keys.toSet().size)
    }

    @Test
    fun `every exportable setting and every gated switch has a home in all settings`() {
        // Not rows on either screen: the two legacy gated switches have had no Settings row since before the
        // split. (The summary language has its own row since BabApps item 135.)
        val noRowOnEitherScreen = setOf("persistent_server", "wd_disable_when_idle")
        val prefs = AppPreferences(ApplicationProvider.getApplicationContext())
        val required = AppPreferences.EXPORTABLE_KEYS + prefs.gatedSwitchCapabilities().keys - noRowOnEitherScreen
        val placed = all.flatMap { SettingsGroups.settingKeysOf(it) }.toSet()
        val missing = required - placed
        assertTrue("Settings with no group on the new screen: $missing", missing.isEmpty())
    }

    @Test
    fun `the device-only switches have a home too`() {
        val placed = all.flatMap { SettingsGroups.settingKeysOf(it) }.toSet()
        listOf(
            "wd_enforced", "storage_target", "recording_folder_uri", "drive_folder_uri",
            // classic_settings_screen has no row: the classic screen it chose was removed (key kept for a revert).
            "logging_enabled",
        ).forEach { assertTrue("$it has no group", it in placed) }
    }

    @Test
    fun `the old reliability, app calls and general groups are gone`() {
        val keys = SettingsGroup.entries.map { it.key }
        assertTrue(keys.none { it in setOf("group_app_calls", "group_reliability", "group_general", "group_recording") })
    }

    @Test
    fun `the debug report and the attribution stay reachable from all settings`() {
        // GPLv3 section 7 attribution lives in About; the debug report is what support asks for.
        val help = SettingsGroups.rowsOf(SettingsGroup.HELP_AND_ABOUT)
        assertTrue("AboutSection" in help)
        assertTrue("BugReportSection" in help)
        assertTrue(SettingsGroup.HELP_AND_ABOUT in all)
    }

    @Test
    fun `group keys are unique and do not reuse the removed classic screen's section keys`() {
        val keys = SettingsGroup.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        val classic = setOf(
            "recording", "storage", "retention", "audio", "transcription", "general",
            "visual", "reliability", "bug_report", "updates", "about",
        )
        assertTrue(keys.none { it in classic })
        SettingsGroup.entries.forEach { assertEquals(it, SettingsGroup.fromKey(it.key)) }
        assertNull(SettingsGroup.fromKey("reliability"))
        assertNull(SettingsGroup.fromKey(null))
    }

    @Test
    fun `each page's settings icon opens its own scope, and the main screen opens everything`() {
        assertEquals(SettingsScope.ALL, SettingsGroups.scopeFor(HomeSection.Hub))
        assertEquals(SettingsScope.RECORDINGS, SettingsGroups.scopeFor(HomeSection.Recordings))
        assertEquals(SettingsScope.TRANSCRIPTS, SettingsGroups.scopeFor(HomeSection.Transcripts))
        assertEquals(SettingsScope.SUMMARIES, SettingsGroups.scopeFor(HomeSection.Summaries))
    }
}
