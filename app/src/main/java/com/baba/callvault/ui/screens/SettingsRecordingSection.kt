/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.baba.callvault.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.scrcpy.AUDIO_BIT_RATE_OPTIONS
import com.baba.callvault.integrations.scrcpy.RECOMMENDED_AUDIO_BIT_RATE
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioSource
import com.baba.callvault.ui.common.ContactSelectionDialog
import com.baba.callvault.ui.common.FileNameFormatDialog
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import com.baba.callvault.ui.viewmodels.ContactPickerViewModel
import com.baba.callvault.ui.viewmodels.SettingsActions
import com.baba.callvault.utils.fileNameTemplateExample
import com.baba.callvault.utils.presetForTemplateOrFirst
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import com.baba.callvault.data.voip.VoipAppPolicy
import androidx.compose.ui.platform.LocalInspectionMode

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
internal fun RecordingSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenContactsIncoming: () -> Unit,
    onOpenContactsOutgoing: () -> Unit
) {
    // Read here as well as in the rows: they decide which sub-sections show.
    val carrierRecording = remember(updateTrigger) { preferences.isCarrierRecordingEnabled() }
    val autoRecordIncoming = remember(updateTrigger) { preferences.isAutoRecordIncomingEnabled() }
    val autoRecordOutgoing = remember(updateTrigger) { preferences.isAutoRecordOutgoingEnabled() }

    // One open sub-section at a time, within this section only, and nothing open on entry.
    var openSub by rememberSaveable { mutableStateOf<String?>(null) }

    // The rows are in SettingsRecordingRows.kt, shared with the new Settings screen's groups.
    SettingsSection(
        title = stringResource(R.string.settings_section_recording),
        expanded = expanded,
        onToggle = onToggle,
        wrapInCard = false,
    ) {
      SettingsSubSection(
        title = stringResource(R.string.settings_file_name_template),
        expanded = openSub == SUB_FILE_NAME,
        onToggle = { openSub = if (openSub == SUB_FILE_NAME) null else SUB_FILE_NAME },
      ) {
        FileNameTemplateRow(preferences, updateTrigger, actions)
      }

      // The master switch for phone calls, in its own sub-section above the per-direction ones it
      // governs. Turning the two direction switches off is NOT the same thing: that is "ask me", and
      // it still puts a Record prompt on every call. This is the "app calls only" mode.
      SettingsSubSection(
        title = stringResource(R.string.settings_subsection_phone_calls),
        expanded = openSub == SUB_PHONE_CALLS,
        onToggle = { openSub = if (openSub == SUB_PHONE_CALLS) null else SUB_PHONE_CALLS },
      ) {
        CarrierRecordingRow(preferences, updateTrigger, actions)
      }

      AnimatedVisibility(
          visible = carrierRecording,
          enter   = fadeIn() +  expandVertically(),
          exit    = fadeOut() + shrinkVertically()
      ) {
        Column {
      SettingsSubSection(
        title = stringResource(R.string.settings_subsection_incoming),
        expanded = openSub == SUB_INCOMING,
        onToggle = { openSub = if (openSub == SUB_INCOMING) null else SUB_INCOMING },
      ) {
        AutoRecordIncomingRow(preferences, updateTrigger, actions)
        AnimatedVisibility(
            visible = autoRecordIncoming,
            enter   = fadeIn() +  expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            NestedGroup {
                IgnoreAnonymousIncomingRow(preferences, updateTrigger, actions)
                IgnoreCrossCountryIncomingRow(preferences, updateTrigger, actions)
                IgnoreContactsIncomingRow(preferences, updateTrigger, actions, onOpenContactsIncoming)
            }
        }
      }

      SettingsSubSection(
        title = stringResource(R.string.settings_subsection_outgoing),
        expanded = openSub == SUB_OUTGOING,
        onToggle = { openSub = if (openSub == SUB_OUTGOING) null else SUB_OUTGOING },
      ) {
        AutoRecordOutgoingRow(preferences, updateTrigger, actions)
        RecordFromAnswerRow(preferences, updateTrigger, actions)
        AnimatedVisibility(
            visible = autoRecordOutgoing,
            enter   = fadeIn() +  expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            NestedGroup {
                IgnoreCrossCountryOutgoingRow(preferences, updateTrigger, actions)
                IgnoreContactsOutgoingRow(preferences, updateTrigger, actions, onOpenContactsOutgoing)
            }
        }
      }
        }
      }
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
internal fun AudioSection(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions, expanded: Boolean, onToggle: () -> Unit) {

    val audioSource = remember(updateTrigger) { preferences.getAudioSource() }
    val audioCodec = remember(updateTrigger) { preferences.getAudioCodec() }
    val savedBitRate = remember(updateTrigger) { preferences.getAudioBitRate() }

    SettingsSection(title = stringResource(R.string.settings_section_audio), expanded = expanded, onToggle = onToggle) {
        val currentSdk = Build.VERSION.SDK_INT

        // Build the source list from the enum, always hiding debug-only entries.
        // Items that require an API level not available on this device are shown as disabled.
        // `voice-call` is flagged in the list the same way the recommended bit rate is, because the
        // four sources are not peers however evenly the dropdown presents them. Measured on a OnePlus 9
        // Pro, 2026-08-25, same call and same clip: voice-call captures two channels at -42 dB with
        // speaker attribution; `mic-voice-communication` captures ONE channel at -51.7 dB, is barely
        // audible, and silently produces no speaker turns at all — there is nothing to attribute. The
        // others exist for devices where voice-call is unavailable, which is worth keeping and worth
        // saying at the point where someone picks one.
        val recommendedSourceLabel = stringResource(R.string.general_recommended)
        val audioSourceOptions = ScrcpyAudioSource.entries
            .filter { !it.isDebugOnly }
            .map { source ->
                val title = stringResource(source.titleResId)
                OptionItem(
                    key         = source.cliKey,
                    label       = if (source == ScrcpyAudioSource.VOICE_CALL) {
                        "$title ($recommendedSourceLabel)"
                    } else {
                        title
                    },
                    description = stringResource(source.descriptionResId),
                    // Enabled only when the current SDK is within the source's API range.
                    enabled     = currentSdk >= source.minApi &&
                                  (source.maxApi == null || currentSdk <= source.maxApi)
                )
            }

        val selectedAudio = audioSourceOptions.find { it.key == audioSource }
            ?: audioSourceOptions.first()

        WithInfo(stringResource(R.string.settings_audio_source), selectedAudio.description) {
        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_audio_source),
                selected = selectedAudio,
                options  = audioSourceOptions,
                onOptionSelected = { actions.setAudioSource(it.key) }
            )
            // Show the description of the currently selected audio source below the dropdown.
            selectedAudio.description?.let { desc ->
                HintExplanation(desc)
            }
        }
        }

        val codecOptions = ScrcpyAudioCodec.entries
            .map { OptionItem(it.cliKey, stringResource(it.titleResId)) }

        val showsAacAdvice = !LocalInspectionMode.current && audioCodec != ScrcpyAudioCodec.AAC.cliKey
        val aacAdvice = stringResource(R.string.settings_audio_bitrate_recommendation)
        WithInfo(stringResource(R.string.settings_audio_codec), aacAdvice.takeIf { showsAacAdvice }) {

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
            if (showsAacAdvice) {
                HintExplanation(aacAdvice)
            }
        }
        }

        val recommendedLabel = stringResource(R.string.general_recommended)
        val bitrateOptions = AUDIO_BIT_RATE_OPTIONS
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
internal fun IgnoreContactsOptions(
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
        // selectableGroup + selectable rows: TalkBack reads each choice as "Radio button, selected/not
        // selected, n of 4", and the whole row (circle and text) is one target and one stop.
        Column(modifier = Modifier.selectableGroup()) {
            enumEntries.forEach { ignoreContactMode ->
                val isSelected = selectedEnum == ignoreContactMode
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelected(ignoreContactMode) },
                        )
                        .padding(vertical = 4.dp)
                ) {
                    // Display only; the row above owns the tap and the semantics. The minimum size keeps
                    // the 48dp slot the clickable button had, so the rows keep their height.
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                    Text(
                        text = when (ignoreContactMode) {
                            AppPreferences.IgnoreContactsMode.NONE -> stringResource(R.string.settings_ignore_contacts_none)
                            AppPreferences.IgnoreContactsMode.ALL -> stringResource(R.string.settings_ignore_contacts_all)
                            AppPreferences.IgnoreContactsMode.SELECTED   -> stringResource(R.string.settings_ignore_contacts_selected)
                            AppPreferences.IgnoreContactsMode.ONLY_SELECTED -> stringResource(R.string.settings_record_only_selected_contacts)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Both list modes pick contacts; each mode keeps its own list (see IgnoreContactsMode.ONLY_SELECTED).
        val usesList = selectedEnum == AppPreferences.IgnoreContactsMode.SELECTED ||
            selectedEnum == AppPreferences.IgnoreContactsMode.ONLY_SELECTED
        if (selectedEnum == AppPreferences.IgnoreContactsMode.ONLY_SELECTED) {
            // Said plainly, because it is the opposite of every other choice here: a call that cannot be
            // matched — a withheld number — is not recorded automatically.
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_record_only_selected_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (usesList) {
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
 * Which apps' calls get recorded — one switch per installed app that could place a call.
 *
 * Every switch is ON by default. Underneath, only the apps switched OFF are stored: an allow-list
 * kept literally would start empty, and an empty allow-list means "record nothing", so every
 * existing user would silently stop recording app calls the moment they upgraded. See
 * [com.baba.callvault.data.voip.VoipAppPolicy].
 *
 * The list is loaded off the main thread. Enumerating installed packages with their permissions is
 * tens of milliseconds on a full phone, which is enough to stutter a screen that is opening.
 */
@Composable
internal fun VoipAppPicker(prefs: AppPreferences) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<CallingApp>?>(null) }
    var excluded by remember { mutableStateOf(prefs.getVoipExcludedPackages()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { VoipAppList.installedCallingApps(context) }
    }

    val loaded = apps
    SettingsToggleRow(
        label = stringResource(R.string.settings_voip_apps_label),
        description = when {
            loaded == null -> stringResource(R.string.settings_voip_apps_loading)
            excluded.isEmpty() -> stringResource(R.string.settings_voip_apps_all)
            // pluralStringResource, not stringResource: the English text used to read "app(s)",
            // which four translators copied verbatim and Polish and Russian could not express at all
            // — they need three and four forms respectively for the same sentence.
            else -> pluralStringResource(
                R.plurals.settings_voip_apps_some, excluded.size, excluded.size
            )
        },
        checked = expanded,
        enabled = loaded != null,
        onCheckedChange = { expanded = it },
        // Which apps are recorded: a value, not an explanation.
        keepDescriptionInline = true,
        infoExtra = stringResource(R.string.settings_voip_apps_hint),
    )

    AnimatedVisibility(
        visible = expanded && loaded != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        NestedGroup {
            SettingsExplanation(stringResource(R.string.settings_voip_apps_hint))
            loaded?.forEach { app ->
                SettingsToggleRow(
                    label = app.label,
                    checked = app.packageName !in excluded,
                    onCheckedChange = { record ->
                        excluded = VoipAppPolicy.withRecording(excluded, app.packageName, record)
                        prefs.setVoipExcludedPackages(excluded)
                    }
                )
            }
        }
    }
}
