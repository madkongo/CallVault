/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.ModeCapability
import com.baba.callvault.data.PrivilegedMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every user setting answers two questions, in writing, before it can ship:
 *
 *  1. **Onboarding** — is it offered in the setup wizard (which can never be re-run), and on which step;
 *     or why is it Settings-only?
 *  2. **Modes** — does it work in both privileged modes, or is it gated by a [ModeCapability] (greyed in
 *     Settings, turned off on the way into a mode that cannot honour it, restored on the way out)?
 *
 * The maintainer kept having to ask both after every feature. This makes forgetting fail the build: a new
 * exportable setting or a new gated switch with no row here fails; a row whose gate disagrees with what
 * the mode switch actually does fails; a row naming a wizard step that no longer exists fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingDecisionsTest {

    private sealed interface Onboarding
    private data class Wizard(val step: WizardStep) : Onboarding
    private data class SettingsOnly(val why: String) : Onboarding
    private data class Decision(val onboarding: Onboarding, val gate: ModeCapability? = null)

    /** Keyed by the exportable preference key, or by the gated switch's id for device-only switches. */
    private val decisions: Map<String, Decision> = mapOf(
        // Recording automation
        "carrier_recording_enabled" to Decision(Wizard(WizardStep.AUTO_RECORD)),
        "auto_record_incoming" to Decision(Wizard(WizardStep.AUTO_RECORD)),
        "auto_record_outgoing" to Decision(Wizard(WizardStep.AUTO_RECORD)),
        "record_from_answer" to Decision(SettingsOnly("a refinement of outgoing recording; the default is right for most")),
        // Who to record / ignore
        "ignore_anonymous_incoming" to Decision(SettingsOnly("per-person rules need the user to know their callers first")),
        "ignore_cross_country_incoming" to Decision(SettingsOnly("per-person rules")),
        "ignore_cross_country_outgoing" to Decision(SettingsOnly("per-person rules")),
        "ignore_contacts_mode_incoming" to Decision(SettingsOnly("per-person rules")),
        "ignore_contacts_mode_outgoing" to Decision(SettingsOnly("per-person rules")),
        "ignored_contacts_incoming" to Decision(SettingsOnly("per-person rules")),
        "ignored_contacts_outgoing" to Decision(SettingsOnly("per-person rules")),
        "voip_apply_ignored_contacts" to Decision(SettingsOnly("#51: only means something once an ignore list exists")),
        "record_only_contacts_incoming" to Decision(SettingsOnly("per-person rules")),
        "record_only_contacts_outgoing" to Decision(SettingsOnly("per-person rules")),
        // Audio + file naming
        "audio_source" to Decision(SettingsOnly("advanced; the default source is the measured best")),
        "audio_codec" to Decision(Wizard(WizardStep.AUDIO)),
        "audio_bitrate" to Decision(Wizard(WizardStep.AUDIO)),
        "file_name_template" to Decision(Wizard(WizardStep.FILE_NAME)),
        // UI
        "theme_mode" to Decision(SettingsOnly("appearance; visible and changeable any time")),
        "dynamic_color" to Decision(SettingsOnly("appearance")),
        "show_toasts" to Decision(SettingsOnly("appearance")),
        "vibration_enabled" to Decision(SettingsOnly("appearance")),
        // Retention / storage policy
        "retention_linked" to Decision(SettingsOnly("deleting recordings is never set up on day one")),
        "retention_local_days" to Decision(SettingsOnly("retention")),
        "retention_drive_days" to Decision(SettingsOnly("retention")),
        "retention_time_hour" to Decision(SettingsOnly("retention")),
        "retention_time_minute" to Decision(SettingsOnly("retention")),
        "storage_cap_bytes" to Decision(SettingsOnly("retention")),
        "min_duration_seconds" to Decision(SettingsOnly("a filter worth setting only once short calls annoy")),
        "write_metadata_file" to Decision(SettingsOnly("only for users of BCR-compatible tools")),
        "write_transcript_sidecar" to Decision(SettingsOnly("PC-backup users; changeable any time and back-fills")),
        "hide_recordings" to Decision(SettingsOnly("privacy opt-in; changeable any time and applies at once")),
        "keep_originals_after_merge" to Decision(SettingsOnly("only meaningful once merging is used")),
        // Sync schedule
        "sync_schedule_mode" to Decision(Wizard(WizardStep.SCHEDULE)),
        "sync_time_hour" to Decision(Wizard(WizardStep.SCHEDULE)),
        "sync_time_minute" to Decision(Wizard(WizardStep.SCHEDULE)),
        "sync_day_of_week" to Decision(Wizard(WizardStep.SCHEDULE)),
        // Transcription + summary policy
        "transcription_mode" to Decision(Wizard(WizardStep.TRANSCRIPTION)),
        "transcription_hour" to Decision(SettingsOnly("a refinement of the transcription mode chosen in the wizard")),
        "transcription_minute" to Decision(SettingsOnly("as above")),
        "transcription_requires_charging" to Decision(SettingsOnly("as above")),
        "transcription_batch_limit" to Decision(SettingsOnly("as above")),
        "transcription_confirm_before_run" to Decision(SettingsOnly("as above")),
        "transcription_model_id" to Decision(Wizard(WizardStep.TRANSCRIPTION)),
        "transcription_language" to Decision(Wizard(WizardStep.TRANSCRIPTION)),
        "transcription_ask_language" to Decision(SettingsOnly("for multilingual phones, after the pin is chosen")),
        "summary_confirm_requirements" to Decision(SettingsOnly("asked at the first summary instead")),
        "summary_language" to Decision(SettingsOnly("follows the transcription language")),
        // App (VoIP) calls
        "voip_recording_enabled" to Decision(Wizard(WizardStep.AUTO_RECORD), ModeCapability.VOIP_RECORDING),
        "voip_auto_start" to Decision(Wizard(WizardStep.AUTO_RECORD), ModeCapability.VOIP_RECORDING),
        "voip_excluded_packages" to Decision(SettingsOnly("per-app choice, once app calls are on")),
        // Updates
        "update_check_enabled" to Decision(Wizard(WizardStep.UPDATES)),
        // Device-only gated switches (not exported), by their GatedSwitch id
        "handoff_persist" to Decision(Wizard(WizardStep.EXPERIMENTAL), ModeCapability.RESILIENT_RECORDING),
        "voip_recording" to Decision(Wizard(WizardStep.AUTO_RECORD), ModeCapability.VOIP_RECORDING),
        "offline_recording" to Decision(Wizard(WizardStep.RELIABILITY), ModeCapability.OFFLINE_RECORDING),
        "persistent_server" to Decision(SettingsOnly("legacy keep-alive switch"), ModeCapability.DAEMON_KEEP_ALIVE),
        "wd_disable_when_idle" to Decision(SettingsOnly("transport detail"), ModeCapability.WIRELESS_DEBUGGING_CONTROL),
        // Where to transcribe (#48). Device-only (not exported): the wizard cannot be re-run and has no
        // place to paste a key, and sending call audio away is a choice made knowingly, in Settings.
        "transcription_service" to Decision(SettingsOnly("#48: needs the user's own key, pasted in Settings; never in the wizard")),
        "transcription_cloud_mobile_data" to Decision(SettingsOnly("#48: only shown under a chosen cloud service")),
        "transcription_cloud_consent" to Decision(SettingsOnly("#48: asked by the consent dialog when a service is first chosen")),
        "transcription_custom_base_url" to Decision(SettingsOnly("#48: Custom (advanced) only")),
        "transcription_custom_model" to Decision(SettingsOnly("#48: Custom (advanced) only")),
        "wd_auto_approve_network" to Decision(
            SettingsOnly("off by default; only matters to people who move between Wi-Fi networks, found next to Keep Wireless debugging on"),
            ModeCapability.WIRELESS_DEBUGGING_CONTROL,
        ),
    )

    private val prefs = AppPreferences(ApplicationProvider.getApplicationContext())
    private val gated = prefs.gatedSwitchCapabilities()

    /** User settings kept off the export on purpose, each still owed an onboarding decision. */
    private val deviceOnlySettings = setOf(
        "transcription_service", "transcription_cloud_mobile_data", "transcription_cloud_consent",
        "transcription_custom_base_url", "transcription_custom_model",
    )

    @Test
    fun `device-only settings are real keys and stay out of the export`() {
        val ids = AppPreferences.Key.entries.map { it.id }.toSet()
        deviceOnlySettings.forEach {
            assertTrue("$it is not a Key", it in ids)
            assertTrue("$it must not be exported", it !in AppPreferences.EXPORTABLE_KEYS)
        }
    }

    @Test
    fun `every user setting and every gated switch has a written decision`() {
        val required = AppPreferences.EXPORTABLE_KEYS + gated.keys + deviceOnlySettings
        val missing = required - decisions.keys
        val stale = decisions.keys - required
        assertTrue("Decide onboarding + modes for: $missing", missing.isEmpty())
        assertTrue("Decisions for settings that no longer exist: $stale", stale.isEmpty())
    }

    @Test
    fun `every declared mode gate is the one the mode switch enforces`() {
        gated.forEach { (id, capability) ->
            assertEquals("gate of $id", capability, decisions.getValue(id).gate)
        }
        decisions.filterValues { it.gate != null }.keys.filter { it in gated.keys }.forEach { id ->
            assertEquals("gate of $id", gated[id], decisions.getValue(id).gate)
        }
    }

    @Test
    fun `every wizard step named here is one the wizard shows in at least one mode`() {
        val shown = (wizardSteps(usesDrive = true, usesEmbeddedAdb = true) +
            wizardSteps(usesDrive = true, usesEmbeddedAdb = false)).toSet()
        decisions.forEach { (key, d) ->
            val step = (d.onboarding as? Wizard)?.step ?: return@forEach
            assertTrue("$key names wizard step $step, which is never shown", step in shown)
        }
    }

    @Test
    fun `a wizard step that Shizuku mode hides only carries settings Shizuku mode cannot use`() {
        // If the wizard drops a step in Shizuku mode, a setting on it that DOES work in Shizuku mode would
        // never be offered to a Shizuku user — the onboarding gap this test exists to catch.
        val shizukuSteps = wizardSteps(usesDrive = true, usesEmbeddedAdb = false).toSet()
        decisions.forEach { (key, d) ->
            val step = (d.onboarding as? Wizard)?.step ?: return@forEach
            if (step !in shizukuSteps) {
                val gate = d.gate
                assertTrue(
                    "$key is on $step, hidden in Shizuku mode, yet works there",
                    gate != null && !gate.isAvailableIn(PrivilegedMode.SHIZUKU),
                )
            }
        }
    }
}
