/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.transcription.TranscriptionLanguageChoice

/**
 * Asks which language to transcribe one recording in.
 *
 * Shown only when the user has turned the ask on (`AppPreferences.getTranscriptionAskLanguage`), and
 * the answer applies to that recording alone: a phone that takes calls in two languages should not
 * have to visit Settings twice a day, and should not have its default silently rewritten either.
 *
 * **A dropdown, not a list of radio rows.** Thirteen languages plus auto-detect drew a scrolling wall
 * inside a dialog — a question the size of a screen, asked before every import, to which the answer is
 * nearly always the one already selected. Collapsed to a single field it reads as what it is: a
 * setting shown for confirmation, with the whole list one tap away and auto-detect still in it.
 *
 * The **"(your usual)"** suffix that used to mark the pinned language is gone with the rows. It earned
 * its place in a list of fourteen, where it said which one to look for; in a field showing exactly one
 * value it only invites the reader to wonder what the other kind would be. The preselection says the
 * same thing without a word.
 *
 * @param setting the pinned Settings language, null meaning auto-detect.
 * @param onConfirm receives the pick already encoded by
 *   [com.baba.callvault.transcription.TranscriptionLanguageChoice.encode].
 */
@Composable
fun TranscribeLanguageDialog(
    title: String,
    setting: String?,
    onDismiss: () -> Unit,
    onConfirm: (language: String) -> Unit
) {
    // Through preselectedLanguageKey rather than encode() directly: a pin naming a language this
    // build does not offer would otherwise leave `picked` holding a code that is in no option, so the
    // field would show auto-detect while Confirm sent something else.
    var picked by remember(setting) {
        mutableStateOf(TranscriptionLabels.preselectedLanguageKey(setting))
    }

    val options = TranscriptionLabels.sortLanguageOptions(
        TranscriptionLabels.LANGUAGE_OPTIONS.map { code ->
            TranscriptionLanguageChoice.encode(code) to
                stringResource(TranscriptionLabels.languageOf(code))
        }
    ).map { (key, label) -> OptionItem(key, label) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Filled.Translate, contentDescription = null) },
        // Stated, because AlertDialog's own default for an icon is `secondary`, which this scheme
        // resolves to CoralDeep — so an ordinary question was being introduced by the colour the app
        // uses for trouble.
        iconContentColor = MaterialTheme.colorScheme.primary,
        title = { Text(stringResource(R.string.transcribe_language_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.transcribe_language_message, title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                M3DropdownField(
                    label = stringResource(R.string.transcription_language_label),
                    // Never null: the options always contain auto-detect, and `picked` is only ever
                    // set from a key that came out of this same list.
                    selected = options.first { it.key == picked },
                    options = options,
                    onOptionSelected = { picked = it.key },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) {
                Text(stringResource(R.string.transcribe_language_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
        }
    )
}
