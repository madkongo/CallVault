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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The per-page Settings screen, the only one since the classic screen was removed
 * (docs/dev-notes/rollback/classic-settings-removal.md): the rules the screen's source must keep. Read from source like [com.baba.callvault.ui.ModeAwareUiTest],
 * because the rules are about which composables are called, not about pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScopedSettingsScreenTest {

    private val screensDir = listOf(
        "src/main/java/com/baba/callvault/ui/screens",
        "app/src/main/java/com/baba/callvault/ui/screens",
    ).map(::File).first { it.isDirectory }

    private fun source(name: String) = File(screensDir, name).readText()

    @Test
    fun `the classic screen's preference key is kept for a revert, still device-only`() {
        // A revert of the removal must find each user's saved choice where it left it.
        assertEquals("classic_settings_screen", AppPreferences.Key.CLASSIC_SETTINGS_SCREEN.id)
        assertFalse(AppPreferences.EXPORTABLE_KEYS.contains("classic_settings_screen"))
    }

    /** The new screen's source: the screen and its group bodies. */
    private val newScreen get() = source("ScopedSettingsContent.kt") + source("ScopedSettingsGroupBodies.kt")

    /** The text of one composable's body, from its declaration to the next declaration. */
    private fun bodyOf(name: String, text: String): String {
        val start = Regex("""fun $name\(""").find(text)?.range?.first
            ?: throw AssertionError("no composable $name")
        val next = Regex("""\n(@Composable|/\*\*|internal |private )""").find(text, start + 1)?.range?.first
        return text.substring(start, next ?: text.length)
    }

    private fun calls(name: String, text: String) = Regex("""\b$name\(""").containsMatchIn(text)

    private fun bodyName(group: SettingsGroup) =
        group.name.lowercase().split('_').joinToString("") { it.replaceFirstChar(Char::uppercase) } + "Body"

    @Test
    fun `the classic screen, its Experimental section and the layout switch are gone`() {
        val all = settingsSources.values.joinToString("\n") + newScreen
        listOf("fun SettingsContent(", "ExperimentalSubSection", "GeneralSection(", "ResilienceRows",
            "SettingsLayoutSwitchCard", "isClassicSettingsScreen", "setClassicSettingsScreen").forEach {
            assertFalse("$it is back", all.contains(it))
        }
        assertFalse(File(screensDir, "SettingsLayoutSwitchCard.kt").exists())
        assertTrue(source("SettingsScreen.kt").contains("ScopedSettingsContent("))
    }

    @Test
    fun `the new screen offers no second language picker`() {
        // Transcripts and summaries share one language on purpose; two pickers once produced transcripts
        // in the wrong language. The new screen shows it read-only under Summaries and links to it.
        assertFalse(newScreen.contains("TranscriptionLanguageField"))
        assertEquals(1, Regex("""TranscriptionLanguageRows\(""").findAll(newScreen).count())
        val bodies = source("ScopedSettingsGroupBodies.kt")
        assertTrue(calls("TranscriptionLanguageRows", bodyOf("ModelAndLanguageBody", bodies)))
        assertFalse(calls("TranscriptionLanguageRows", bodyOf("SummariesBody", bodies)))
    }

    @Test
    fun `debug has no classic-screen switch, and the debug report is drawn once`() {
        val debug = source("SettingsAboutDebugSection.kt")
        assertFalse(debug.contains("ClassicSettingsScreenToggle"))
        assertFalse(debug.contains("setClassicSettingsScreen"))
        assertEquals(1, Regex("""BugReportSection\(""").findAll(newScreen).count())
    }

    @Test
    fun `each group body calls exactly the rows its table lists`() {
        val bodies = source("ScopedSettingsGroupBodies.kt")
        val everyRow = SettingsGroup.entries.flatMap { SettingsGroups.rowsOf(it) }.toSet()
        SettingsGroup.entries.forEach { group ->
            val body = bodyOf(bodyName(group), bodies)
            val listed = SettingsGroups.rowsOf(group).toSet()
            listed.forEach { assertTrue("${bodyName(group)} does not call $it", calls(it, body)) }
            val unlisted = (everyRow - listed).filter { calls(it, body) }
            assertTrue("${bodyName(group)} calls rows listed elsewhere: $unlisted", unlisted.isEmpty())
        }
    }

    @Test
    fun `connection holds every switch of the removed Experimental section`() {
        val general = source("SettingsGeneralSection.kt")
        val rows = listOf(
            "UsbDebuggingToggle", "OfflineRecordingToggle", "HandoffPersistToggle", "WirelessDebuggingModeRow",
            "WifiNetworkAutoApproveToggle", "UsbDefaultConfigRow", "PairAgainRow",
        )
        val connection = bodyOf("ConnectionRows", general)
        rows.forEach { assertTrue("ConnectionRows lacks $it", calls(it, connection)) }
        // The agreed order (round 2): USB debugging, offline, handoff, Wireless debugging (one choice since
        // 2026-11-03, was "keep WD on" + "never turn WD off" #30), approve networks, USB mode, pair.
        assertEquals(rows, rows.sortedBy { connection.indexOf("$it(") })
    }

    @Test
    fun `every settings row has a home in all settings`() {
        // Every row-like composable declared for Settings. A new one fails here until it is placed in a group
        // (SettingsGroups.rowsOf) or listed below as a container or a part of a listed row.
        val files = screensDir.listFiles { f -> f.name.startsWith("Settings") && f.extension == "kt" }!!.toList() +
            File(screensDir, "SummarySection.kt")
        val declared = files.flatMap { f ->
            Regex("""@Composable\s*\n(?:internal |private )?fun (\w+(?:Row|Rows|Toggle|Switch|Picker|Note|Section|SubSection))\(""")
                .findAll(f.readText()).map { it.groupValues[1] }.toList()
        }.toSet()
        val notRows = setOf(
            // Building blocks.
            "SettingsToggleRow", "NavigationRow", "DropdownRow", "SettingsSection",
            // The wizard's bundle of VoIP rows, which the screen places one by one.
            "VoipRecordingToggle",
            // Parts of a listed row: drawn inside BugReportSection, SummarySettingsRows, the download rows.
            "SummaryRows", "TranscriptionModelRows",
            // A dialog over either screen, not a row.
            "SettingsContactPicker",
            // Setup (onboarding) only since Settings shows WirelessDebuggingModeRow instead (2026-11-03).
            "WirelessDebuggingEnforceToggle",
        )
        val connection = listOf(
            "UsbDebuggingToggle", "OfflineRecordingToggle", "HandoffPersistToggle", "WirelessDebuggingModeRow",
            "WifiNetworkAutoApproveToggle", "UsbDefaultConfigRow", "PairAgainRow",
        )
        val placed = SettingsGroups.forScope(SettingsScope.ALL).flatMap { SettingsGroups.rowsOf(it) }.toSet() + connection
        val missing = declared - notRows - placed
        assertTrue("Settings rows with no group on the new screen: $missing", missing.isEmpty())
        // The wizard's VoIP bundle (the removed Experimental section's too) is exactly the four rows the screen places.
        val voip = bodyOf("VoipRecordingToggle", source("SettingsVoipRows.kt"))
        listOf("VoipRecordingSwitch", "VoipAutoStartRow", "VoipAppPicker", "VoipIgnoreContactsRow").forEach {
            assertTrue("VoipRecordingToggle lacks $it", calls(it, voip))
            assertTrue("$it is not placed", it in placed)
        }
    }

    // ── Round 3: no long text inline on the new screen ─────────────────────────────────────────────

    /** Live status or a warning the user must see before acting: stays inline on both screens. */
    private val inlineHints = setOf(
        "voip_recording_arming", "voip_recording_unavailable",
        "settings_usb_debugging_shizuku_hint", "settings_usb_debugging_failed",
        "settings_usb_debugging_on_hint", "settings_usb_debugging_recommended",
        "settings_usb_default_busy", "transcription_language_auto_hint",
        "settings_wd_left_on_by_setting", "settings_wd_left_on_by_user",
        // Keep or delete: why the choice did not take yet (a permission is still missing).
        "settings_after_call_needs_notifications", "settings_after_call_needs_overlay",
    )

    /** Explanations: inline in the wizard, behind an ⓘ on the Settings screen. */
    private val explanatoryHints = setOf(
        "settings_metadata_file_hint", "settings_transcript_sidecar_hint", "settings_hide_recordings_hint",
        "min_duration_caption", "storage_cap_caption", "retention_description",
        "voip_recording_names_hint", "settings_voip_ignore_contacts_hint", "settings_voip_apps_hint",
        "settings_offline_recording_reboot_note", "settings_usb_default_hint",
        "settings_audio_bitrate_recommendation", "settings_bugreport_share_hint",
        "summary_model_licence", "summary_requirements_body",
        "wizard_schedule_immediate_note", "wizard_schedule_subtitle",
        "transcription_note_manual", "transcription_note_after_each_call", "transcription_note_automatic",
    )

    private val settingsSources: Map<String, String>
        get() = (screensDir.listFiles { f -> f.name.startsWith("Settings") && f.extension == "kt" }!!.toList() +
            File(screensDir, "SummarySection.kt")).associate { it.name to it.readText() }

    @Test
    fun `the explanation helpers draw nothing in the new screen's info mode`() {
        val components = source("SettingsComponents.kt")
        listOf("SettingsExplanation", "HintExplanation", "ExplanationParagraph", "WithInfo").forEach {
            assertTrue("$it must check the info mode", bodyOf(it, components).contains("LocalSettingsDescriptionsAsInfo.current"))
        }
        // The group cards are what turn the mode on.
        assertTrue(source("SettingsGroupCard.kt").contains("LocalSettingsDescriptionsAsInfo provides true"))
    }

    @Test
    fun `every plain inline hint is a live status or a warning`() {
        val plain = Regex("""\b(SettingsHint|HintText)\(([^\n]*)""")
        settingsSources.filterKeys { it != "SettingsComponents.kt" }.forEach { (file, text) ->
            plain.findAll(text).forEach { m ->
                val arg = m.groupValues[2]
                val key = Regex("""R\.string\.(\w+)""").find(arg)?.groupValues?.get(1)
                val ok = if (key != null) key in inlineHints else arg.startsWith("warning")
                assertTrue("$file draws an explanation inline: ${m.value.trim()}", ok)
            }
        }
    }

    @Test
    fun `no explanatory text is drawn ungated`() {
        settingsSources.forEach { (file, text) ->
            val lines = text.lines()
            lines.forEachIndexed { i, line ->
                explanatoryHints.filter { line.contains("R.string.$it)") || line.contains("R.string.$it,") }.forEach { key ->
                    assertFalse("$file:${i + 1} draws $key as a plain hint", line.contains("SettingsHint(") || line.contains("HintText("))
                    if (line.trim().startsWith("text = stringResource(R.string.$key")) {
                        val before = lines.subList(maxOf(0, i - 3), i).joinToString("\n")
                        assertTrue("$file:${i + 1} draws $key without checking the info mode",
                            before.contains("LocalSettingsDescriptionsAsInfo.current"))
                    }
                }
            }
        }
    }

    @Test
    fun `every hidden row explanation reaches that row's info button`() {
        val explanation = Regex("""\b(SettingsExplanation|HintExplanation)\(stringResource\(R\.string\.(\w+)\)""")
        settingsSources.forEach { (file, text) ->
            explanation.findAll(text).forEach { m ->
                val key = m.groupValues[2]
                val routed = Regex("""(infoExtra = [^\n]*R\.string\.$key\b|WithInfo\([^\n]*R\.string\.$key\b)""")
                    .containsMatchIn(text)
                assertTrue("$file hides $key but no ⓘ carries it", routed)
            }
        }
    }

    @Test
    fun `group-wide explanations sit behind the group card's info button`() {
        val text = bodyOf("groupExplanationText", source("SettingsGroupSummaryText.kt"))
        listOf("retention_description", "transcriptionModeNoteRes", "summaryModelExplanation", "settings_bugreport_share_hint")
            .forEach { assertTrue("groupExplanationText lacks $it", text.contains(it)) }
        assertTrue(source("ScopedSettingsContent.kt").contains("explanation = groupExplanationText("))
    }

    // ── Round 4: Recording split into phone, app and all-call blocks ──────────────────────────────

    @Test
    fun `recording shows phone calls, then app calls, then all calls, each with its own rows`() {
        val body = bodyOf("CallsToRecordBody", source("ScopedSettingsGroupBodies.kt"))
        val headers = listOf("settings_subsection_phone_calls", "settings_subheader_app_calls", "settings_subheader_all_calls")
        val at = headers.map { body.indexOf("R.string.$it") }
        assertTrue("missing sub-header: $at", at.all { it >= 0 })
        assertEquals(at, at.sorted())
        fun blockOf(row: String): Int = at.count { it < body.indexOf("$row(") }
        listOf("CarrierRecordingRow", "AutoRecordIncomingRow", "AutoRecordOutgoingRow").forEach { assertEquals(it, 1, blockOf(it)) }
        listOf("VoipRecordingSwitch", "VoipAppPicker", "VoipAutoStartRow").forEach { assertEquals(it, 2, blockOf(it)) }
        listOf("RecordFromAnswerRow", "VibrationRow", "AfterCallModeRow", "AfterCallAskStyleRow", "AfterCallUnansweredRow")
            .forEach { assertEquals(it, 3, blockOf(it)) }
        // Keep or delete: after the existing all-calls rows, in the agreed order.
        val afterCall = listOf("VibrationRow", "AfterCallModeRow", "AfterCallAskStyleRow", "AfterCallUnansweredRow")
        assertEquals(afterCall, afterCall.sortedBy { body.indexOf("$it(") })
    }

    @Test
    fun `only the new screen calls the app-call automatic switch by its longer name`() {
        assertTrue(source("ScopedSettingsGroupBodies.kt").contains("VoipAutoStartRow(label = stringResource(R.string.settings_voip_auto_start_record_label))"))
        // The wizard uses the default label.
        assertTrue(bodyOf("VoipRecordingToggle", source("SettingsVoipRows.kt")).contains("VoipAutoStartRow()"))
        assertTrue(source("SettingsVoipRows.kt").contains("label: String = stringResource(R.string.settings_voip_auto_start_label)"))
    }
}
