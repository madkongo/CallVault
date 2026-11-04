/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import androidx.annotation.StringRes
import com.baba.callvault.R

/**
 * A predefined file-name template offered to the user as a single dropdown choice.
 *
 * @property labelRes The string resource for the human-readable preset label.
 * @property template The literal template string persisted as the file-name template preference.
 */
data class FileNameTemplatePreset(
    @param:StringRes val labelRes: Int,
    val template: String
)

/**
 * `{name}@{number}_{yyyyMMddHHmmss}`: the shape Honor's own recorder writes, which its phone app links to the
 * call in its history. Only from `/storage/emulated/0/Sounds/CallRecord` (#56, confirmed by the reporter on
 * an Honor REA-NX9 by renaming a file by hand). The formatter writes the number as dialled and repeats it as
 * the name when there is no contact, and the date is local time to the second.
 */
const val HONOR_DIALER_TEMPLATE = "{contact_name}@{phone_number}_{date_compact}"

/** The folder Honor's and Huawei's phone app reads recordings from, as a user sees it in a file manager. */
const val HONOR_DIALER_FOLDER = "/storage/emulated/0/Sounds/CallRecord"

/**
 * The single source of truth for the selectable file-name template presets.
 * Shared by the Settings dialog and the setup wizard so both stay in sync.
 */
val FILE_NAME_TEMPLATE_PRESETS = listOf(
    FileNameTemplatePreset(R.string.settings_file_name_preset_date_direction_number, "{date}_{direction}_{phone_number}"),
    FileNameTemplatePreset(R.string.settings_file_name_preset_date_number, "{date}_{phone_number}"),
    FileNameTemplatePreset(R.string.settings_file_name_preset_date_contact, "{date}_{contact_name}"),
    FileNameTemplatePreset(R.string.settings_file_name_preset_date_direction_contact, "{date}_{direction}_{contact_name}"),
    // Contact/number-first orders, so a PC file listing groups by who the call was with rather than by time.
    FileNameTemplatePreset(R.string.settings_file_name_preset_contact_number_date, "{contact_name}_{phone_number}_{date}"),
    FileNameTemplatePreset(R.string.settings_file_name_preset_number_contact_date, "{phone_number}_{contact_name}_{date}"),
    FileNameTemplatePreset(R.string.settings_file_name_preset_contact_date, "{contact_name}_{date}"),
    FileNameTemplatePreset(R.string.settings_file_name_preset_number_date, "{phone_number}_{date}"),
    // Lets Honor's and Huawei's own phone app play the recording from the call history (#56).
    FileNameTemplatePreset(R.string.settings_file_name_preset_honor_dialer, HONOR_DIALER_TEMPLATE)
)

/**
 * Returns the preset whose [FileNameTemplatePreset.template] equals [template], or the first preset
 * when none matches (e.g. a legacy/custom stored value).
 */
fun presetForTemplateOrFirst(template: String): FileNameTemplatePreset =
    FILE_NAME_TEMPLATE_PRESETS.firstOrNull { it.template == template } ?: FILE_NAME_TEMPLATE_PRESETS.first()

/**
 * Renders a small, realistic, deterministic example for a template using fixed sample values
 * (e.g. "{date}_{direction}_{contact_name}" -> "20260611_143052_in_John Doe"). Intentionally a
 * lightweight local substitution so the preview is stable and free of the live timestamp/contacts
 * lookup the real formatter performs — and free of the raw "yyyyMMdd_HHmmss.SSSZ" date pattern.
 * No raw "{token}" should ever leak into the result.
 */
fun fileNameTemplateExample(template: String): String = template
    .replace(RecordingFileNameFormatter.DATE_COMPACT_TAG, "20260611143052")
    .replace(RecordingFileNameFormatter.FileNamePlaceholder.DATE.tag, "20260611_143052")
    .replace(RecordingFileNameFormatter.FileNamePlaceholder.DIRECTION.tag, "in")
    .replace(RecordingFileNameFormatter.FileNamePlaceholder.PHONE_NUMBER.tag, "+15551234")
    .replace(RecordingFileNameFormatter.FileNamePlaceholder.CONTACT_NAME.tag, "John Doe")
    .replace(RecordingFileNameFormatter.FileNamePlaceholder.CROSS_COUNTRY.tag, "false")

/**
 * A file-name template the user typed, as ShizuCallRecorder allowed (removed here in June 2026 for the presets,
 * brought back for 2.5.0 on request). Checked before it is saved, because other parts of the app read names back:
 * the list and merging find the date in the name, so `{date}` is required.
 */
object CustomFileNameTemplate {

    enum class Problem { EMPTY, NEEDS_DATE, BAD_CHARACTER, UNKNOWN_TAG, TOO_LONG }

    /** Long enough for any sensible pattern; the filled-in name (contact names) must stay under 255 bytes. */
    const val MAX_LENGTH = 120

    private val KNOWN_TAGS = RecordingFileNameFormatter.FileNamePlaceholder.entries.map { it.tag }.toSet() +
        RecordingFileNameFormatter.DATE_COMPACT_TAG

    /** Characters Android's file systems or SAF providers refuse, plus control characters. */
    private val BAD_CHARACTERS = Regex("""[/\\:*?"<>|\p{Cntrl}]""")

    private val TAG = Regex("""\{[^{}]*\}""")

    /** What is wrong with [template], or null when it can be saved. */
    fun problemOf(template: String): Problem? {
        val trimmed = template.trim()
        return when {
            trimmed.isEmpty() -> Problem.EMPTY
            trimmed.length > MAX_LENGTH -> Problem.TOO_LONG
            BAD_CHARACTERS.containsMatchIn(trimmed) -> Problem.BAD_CHARACTER
            hasUnknownTag(trimmed) -> Problem.UNKNOWN_TAG
            !trimmed.contains(RecordingFileNameFormatter.FileNamePlaceholder.DATE.tag) -> Problem.NEEDS_DATE
            else -> null
        }
    }

    /** The first `{…}` the app does not know, for the error message; null when there is none. */
    fun firstUnknownTag(template: String): String? = TAG.findAll(template).map { it.value }.firstOrNull { it !in KNOWN_TAGS }

    fun isPreset(template: String): Boolean = FILE_NAME_TEMPLATE_PRESETS.any { it.template == template }

    private fun hasUnknownTag(template: String): Boolean {
        if (firstUnknownTag(template) != null) return true
        // A stray brace left after removing every whole tag is a tag typed wrong ("{date" or "date}").
        return TAG.replace(template, "").any { it == '{' || it == '}' }
    }
}
