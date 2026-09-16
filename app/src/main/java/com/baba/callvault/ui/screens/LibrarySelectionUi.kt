/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R

/**
 * Multi-select on a library page, as the page needs to draw it.
 *
 * One parameter rather than five, because a page taking a selected set, a toggle, a clear, a share
 * and a delete as loose arguments is five chances to wire one of them to the wrong section — and the
 * sections are two lists of display names that overlap. The state itself lives in the app shell,
 * above the section switch, which is where every other piece of restorable screen state lives; see
 * [com.baba.callvault.ui.navigation.LibrarySelection] for how it is kept from leaking between pages.
 *
 * @param selected the display names picked on **this** page. Empty means not selecting.
 * @param onToggle a tap or a long-press on a row.
 * @param onClear  leave selection without acting — the back arrow and the back gesture.
 * @param onShare  send every selected row's text, as one message.
 * @param onDelete ask to delete every selected row's text.
 */
data class LibrarySelectionUi(
    val selected: Set<String>,
    val onToggle: (String) -> Unit,
    val onClear: () -> Unit,
    val onShare: () -> Unit,
    val onDelete: () -> Unit,
) {
    /** Whether the page is in selection mode. Derived, so it cannot fall out of step with the set. */
    val active: Boolean get() = selected.isNotEmpty()

    companion object {

        /** A page with no selection wired up at all. */
        val NONE = LibrarySelectionUi(
            selected = emptySet(),
            onToggle = {},
            onClear = {},
            onShare = {},
            onDelete = {},
        )
    }
}

/**
 * The two bulk actions, in the app bar where the page's own actions were.
 *
 * Share and Delete and nothing else. **Save is deliberately not here**, and the reason is
 * mechanical rather than a matter of taste: the export cache holds exactly one file and is emptied
 * on every write, so that a share still being read when the chooser closes cannot be deleted out
 * from under it — several files at once would need that rule changed, on the path where a race
 * destroys a user's export. Bulk Share already answers "get all of this out of the app", and turns
 * itself into a single file when there is too much of it to hand over as text.
 */
@Composable
fun LibrarySelectionActions(selection: LibrarySelectionUi) {
    Row {
        IconButton(onClick = selection.onShare) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.home_share),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = selection.onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.home_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
