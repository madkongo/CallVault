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
 * The single source of truth for the selectable file-name template presets.
 * Shared by the Settings dialog and the setup wizard so both stay in sync.
 */
val FILE_NAME_TEMPLATE_PRESETS = listOf(
    FileNameTemplatePreset(R.string.settings_file_name_preset_date_direction_number, "{date}_{direction}_{phone_number}"),
    FileNameTemplatePreset(R.string.settings_file_name_preset_date_number, "{date}_{phone_number}"),
    FileNameTemplatePreset(R.string.settings_file_name_preset_date_contact, "{date}_{contact_name}"),
    FileNameTemplatePreset(R.string.settings_file_name_preset_date_direction_contact, "{date}_{direction}_{contact_name}")
)

/**
 * Returns the preset whose [FileNameTemplatePreset.template] equals [template], or the first preset
 * when none matches (e.g. a legacy/custom stored value).
 */
fun presetForTemplateOrFirst(template: String): FileNameTemplatePreset =
    FILE_NAME_TEMPLATE_PRESETS.firstOrNull { it.template == template } ?: FILE_NAME_TEMPLATE_PRESETS.first()

/**
 * Renders a small, realistic, deterministic example for a template using fixed sample values
 * (e.g. "20260611_in_+15551234"). Intentionally a lightweight local substitution so the preview is
 * stable and free of the live timestamp/contacts lookup the real formatter performs.
 */
fun fileNameTemplateExample(template: String): String = template
    .replace(RecordingFileNameFormatter.FileNamePlaceholder.DATE.tag, "20260611")
    .replace(RecordingFileNameFormatter.FileNamePlaceholder.DIRECTION.tag, "in")
    .replace(RecordingFileNameFormatter.FileNamePlaceholder.PHONE_NUMBER.tag, "+15551234")
    .replace(RecordingFileNameFormatter.FileNamePlaceholder.CONTACT_NAME.tag, "Jane_Doe")
    .replace(RecordingFileNameFormatter.FileNamePlaceholder.CROSS_COUNTRY.tag, "false")
