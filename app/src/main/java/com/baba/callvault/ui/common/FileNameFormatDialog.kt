/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.baba.callvault.utils.CustomFileNameTemplate
import com.baba.callvault.utils.RecordingFileNameFormatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.ui.theme.CallVaultTheme
import com.baba.callvault.utils.FILE_NAME_TEMPLATE_PRESETS
import com.baba.callvault.utils.HONOR_DIALER_TEMPLATE
import com.baba.callvault.utils.fileNameTemplateExample
import com.baba.callvault.utils.presetForTemplateOrFirst

/** The dropdown key of the "Custom" entry; never a real template (no braces). */
private const val CUSTOM_KEY = "custom"

/**
 * Dialog for choosing the file-name format: one of the presets, or "Custom" — a template the user types, with the
 * tags listed below the field (tap one to add it) and a live example. ShizuCallRecorder had this editor; it was
 * replaced by the presets in June 2026 and brought back for 2.5.0 on request. A custom template is checked
 * ([CustomFileNameTemplate]) before OK is allowed, because the list and merging read the date back from names.
 *
 * @param initialFormat The saved template. A preset preselects itself; anything else opens as Custom.
 * @param onConfirm Called with the template to save when the user taps "OK".
 * @param onDismiss Called when the user taps "Cancel" or outside the dialog.
 */
@Composable
fun FileNameFormatDialog(
    initialFormat: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val startsCustom = !CustomFileNameTemplate.isPreset(initialFormat)
    var selectedKey by remember {
        mutableStateOf(if (startsCustom) CUSTOM_KEY else presetForTemplateOrFirst(initialFormat).template)
    }
    // Opening Custom from a preset starts from that preset, so there is something to edit.
    var customText by remember { mutableStateOf(initialFormat) }
    val isCustom = selectedKey == CUSTOM_KEY
    val template = if (isCustom) customText.trim() else selectedKey
    val problem = if (isCustom) CustomFileNameTemplate.problemOf(customText) else null

    // Each option shows the friendly preset label as primary text and a resolved example as the
    // secondary/preview line — never the raw "{token}" template.
    val options = FILE_NAME_TEMPLATE_PRESETS.map {
        OptionItem(
            key = it.template,
            label = stringResource(it.labelRes),
            description = stringResource(
                R.string.settings_file_name_template_example,
                fileNameTemplateExample(it.template)
            )
        )
    } + OptionItem(
        key = CUSTOM_KEY,
        label = stringResource(R.string.settings_file_name_preset_custom),
        description = stringResource(R.string.settings_file_name_custom_desc),
    )

    AlertDialog(
        title = { Text(stringResource(R.string.settings_file_name_template)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                M3DropdownField(
                    label = stringResource(R.string.settings_file_name_template_preset),
                    selected = options.firstOrNull { it.key == selectedKey } ?: options.first(),
                    options = options,
                    onOptionSelected = {
                        if (it.key == CUSTOM_KEY && selectedKey != CUSTOM_KEY && !startsCustom) customText = selectedKey
                        selectedKey = it.key
                    }
                )

                if (isCustom) CustomTemplateEditor(customText, problem) { customText = it }

                if (problem == null) {
                    Text(
                        text = stringResource(
                            R.string.settings_file_name_template_example,
                            fileNameTemplateExample(template)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (template == HONOR_DIALER_TEMPLATE) HonorDialerHint()
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.general_cancel))
                }
                Button(onClick = { onConfirm(template) }, enabled = problem == null) {
                    Text(stringResource(R.string.general_ok))
                }
            }
        }
    )
}

/** The text field, what is wrong with it (if anything), and the tags to tap in — ShizuCallRecorder's list. */
@Composable
private fun CustomTemplateEditor(text: String, problem: CustomFileNameTemplate.Problem?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        singleLine = true,
        isError = problem != null,
        supportingText = problem?.let { { Text(problemText(it, text)) } },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = stringResource(R.string.settings_file_name_custom_tap_tag),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold
    )
    RecordingFileNameFormatter.FileNamePlaceholder.entries.forEach { tag ->
        Text(
            text = buildAnnotatedString {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary))
                append(tag.tag)
                pop()
                append(" — ")
                append(stringResource(tag.descriptionResId))
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = tag.tag) { onChange(appendTag(text, tag.tag)) }
                .padding(vertical = 6.dp)
        )
    }
}

/** [tag] added at the end, with an underscore in between so the list can still split the name. */
private fun appendTag(text: String, tag: String): String {
    val base = text.trimEnd()
    return if (base.isEmpty() || base.endsWith("_") || base.endsWith("-") || base.endsWith(" ")) base + tag else "${base}_$tag"
}

@Composable
private fun problemText(problem: CustomFileNameTemplate.Problem, text: String): String = when (problem) {
    CustomFileNameTemplate.Problem.EMPTY -> stringResource(R.string.settings_file_name_error_empty)
    CustomFileNameTemplate.Problem.NEEDS_DATE -> stringResource(R.string.settings_file_name_error_needs_date)
    CustomFileNameTemplate.Problem.BAD_CHARACTER -> stringResource(R.string.settings_file_name_error_bad_character)
    CustomFileNameTemplate.Problem.UNKNOWN_TAG -> stringResource(
        R.string.settings_file_name_error_unknown_tag,
        CustomFileNameTemplate.firstUnknownTag(text) ?: "{ }"
    )
    CustomFileNameTemplate.Problem.TOO_LONG -> stringResource(
        R.string.settings_file_name_error_too_long,
        CustomFileNameTemplate.MAX_LENGTH
    )
}

/**
 * Dialog Preview.
 */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    CallVaultTheme(darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize()) {
            FileNameFormatDialog(
                initialFormat = AppPreferences.DefaultsValue.FILE_NAME_TEMPLATE,
                onConfirm = {},
                onDismiss = {}
            )
        }
    }
}
