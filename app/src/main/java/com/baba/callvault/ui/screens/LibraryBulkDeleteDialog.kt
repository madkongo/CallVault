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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.transcripts.LibraryRowActions

/**
 * Confirms deleting the text of several library rows at once.
 *
 * The same shape and the same tone as the recordings list's bulk delete — an icon, a count in the
 * title, what survives in the body — because it is the same gesture on a different page and a user
 * who has learned one should not have to read the other.
 *
 * **What it says is decided away from here**, by [LibraryRowActions.bulkPrompt], because a mixed
 * selection is the case that goes wrong silently: "the recordings are kept" is the ordinary sentence
 * and is *false* for any selected transcript whose recording is already gone. One such row in forty
 * is enough to make it a lie, so the warning is added whenever there is one and says how many.
 *
 * @param count how many rows will lose their text.
 * @param textOnlyCount how many of them have no recording behind them, so their words are all there
 *   is. Always zero on the Summaries page, where deleting never touches audio.
 */
@Composable
internal fun LibraryBulkDeleteDialog(
    page: LibraryRowActions.Page,
    count: Int,
    textOnlyCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val prompt = LibraryRowActions.bulkPrompt(page, textOnlyCount)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) },
        title = {
            Text(
                text = when (page) {
                    LibraryRowActions.Page.Transcripts -> pluralStringResource(
                        R.plurals.library_bulk_delete_transcripts_title, count, count
                    )

                    LibraryRowActions.Page.Summaries -> pluralStringResource(
                        R.plurals.library_bulk_delete_summaries_title, count, count
                    )
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = when (prompt) {
                        LibraryRowActions.BulkPrompt.Summaries ->
                            stringResource(R.string.library_bulk_delete_summaries_message)

                        else -> stringResource(R.string.library_bulk_delete_transcripts_message)
                    }
                )
                if (prompt == LibraryRowActions.BulkPrompt.TranscriptsSomeAllThatIsLeft) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.library_bulk_delete_text_only_warning,
                            textOnlyCount,
                            textOnlyCount,
                        ),
                        // Stated, and the error role on purpose: this is the half of the batch that
                        // cannot be got back. Every other colour in this dialog is the ordinary one.
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.home_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
        },
    )
}
