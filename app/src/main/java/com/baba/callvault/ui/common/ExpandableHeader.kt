/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.baba.callvault.R

/** The word TalkBack says after a fold-open header's name: "Expanded" or "Collapsed". */
@StringRes
fun expandStateRes(expanded: Boolean): Int =
    if (expanded) R.string.general_expanded else R.string.general_collapsed

/**
 * Makes a row the header of a section that folds open and shut.
 *
 * A plain clickable header told TalkBack users nothing about whether the section was open, so a tap
 * that hid half the screen sounded the same as one that revealed it. This adds the state as a
 * stateDescription ("Recording, Collapsed, Button") and the expand / collapse accessibility actions,
 * on the same node as the click, so the role is set once.
 *
 * @param stateText the localised state, from [expandStateRes].
 */
fun Modifier.expandableHeader(
    expanded: Boolean,
    stateText: String,
    onToggle: () -> Unit,
): Modifier = this
    .clickable(role = Role.Button, onClick = onToggle)
    .semantics {
        stateDescription = stateText
        if (expanded) {
            collapse { onToggle(); true }
        } else {
            expand { onToggle(); true }
        }
    }
