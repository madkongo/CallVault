/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.transcripts.LibraryRowActions
import com.baba.callvault.data.transcripts.export.TranscriptFormat

/**
 * A library row's overflow menu: Share, Save, Delete.
 *
 * The same shape as the recordings list's own row menu — three dots in the trailing slot, a
 * `DropdownMenu`, the destructive item last — rather than a second pattern for the same gesture.
 * What differs is what the items act on: a recording's menu shares and deletes a *file*, and these
 * share and delete *text*. See [LibraryRowActions] for what each page offers and what its Delete
 * means.
 *
 * **Save opens the format list in place of the actions, not beside them.** A submenu hanging off a
 * menu is fiddly on a phone and, at the right-hand edge of a row, has nowhere to open; replacing the
 * contents keeps one popup anchored where the finger already is. It is the same list of formats the
 * reading view offers, in the same order, so the two doors onto one export cannot drift.
 *
 * Nothing is drawn at all for an empty [menu]: three dots that open onto nothing are worse than no
 * dots, and the page has rows with genuinely nothing to offer — a transcription still running, a
 * file merely waiting to be read.
 */
@Composable
internal fun LibraryRowMenu(
    menu: LibraryRowActions.Menu,
    formats: List<TranscriptFormat>,
    onShare: () -> Unit,
    onSave: (TranscriptFormat) -> Unit,
    onDelete: () -> Unit,
) {
    if (menu.isEmpty) return

    var open by remember { mutableStateOf(false) }
    // Reset whenever the menu closes, so reopening it starts at the actions rather than at the
    // format list somebody backed out of a minute ago.
    var showingFormats by remember { mutableStateOf(false) }

    val close = {
        open = false
        showingFormats = false
    }

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(MENU_BUTTON_SIZE)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.home_row_menu),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = close) {
            if (showingFormats) {
                formats.forEach { format ->
                    DropdownMenuItem(
                        text = { Text(format.label) },
                        onClick = {
                            close()
                            onSave(format)
                        },
                    )
                }
                return@DropdownMenu
            }

            if (menu.share) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_share)) },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = {
                        close()
                        onShare()
                    },
                )
            }
            if (menu.save) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_row_save)) },
                    leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null) },
                    // The only item that does not close the menu: it asks which format, in the
                    // popup that is already open and already pointing at the row it is about.
                    onClick = { showingFormats = true },
                )
            }
            if (menu.delete != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_delete)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        close()
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * Matches the row's trailing slot, so a row with a menu and a row with a progress ring put their
 * text in the same column.
 */
private val MENU_BUTTON_SIZE = 40.dp
