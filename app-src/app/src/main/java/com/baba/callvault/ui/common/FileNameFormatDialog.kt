/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

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
import com.baba.callvault.utils.fileNameTemplateExample
import com.baba.callvault.utils.presetForTemplateOrFirst

/**
 * Dialog for selecting the file name format from a fixed set of presets (dropdown only).
 *
 * @param initialFormat The template string to show when the dialog opens, usually the currently saved
 *   user preference. The matching preset is preselected; if none matches, the first preset is used.
 * @param onConfirm Called with the selected preset's template string when the user taps "OK".
 * @param onDismiss Called when the user taps "Cancel" or outside the dialog.
 */
@Composable
fun FileNameFormatDialog(
    initialFormat: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTemplate by remember {
        mutableStateOf(presetForTemplateOrFirst(initialFormat).template)
    }

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
    }

    AlertDialog(
        title = { Text(stringResource(R.string.settings_file_name_template)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                M3DropdownField(
                    label = stringResource(R.string.settings_file_name_template_preset),
                    selected = options.firstOrNull { it.key == selectedTemplate } ?: options.first(),
                    options = options,
                    onOptionSelected = { selectedTemplate = it.key }
                )

                Text(
                    text = stringResource(
                        R.string.settings_file_name_template_example,
                        fileNameTemplateExample(selectedTemplate)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
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
                Button(onClick = { onConfirm(selectedTemplate) }) {
                    Text(stringResource(R.string.general_ok))
                }
            }
        }
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
