/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics

/**
 * Makes a row the header of a section that folds open and shut.
 *
 * A plain clickable header told TalkBack users nothing about whether the section was open, so a tap
 * that hid half the screen sounded the same as one that revealed it. This adds the expand / collapse
 * accessibility actions on the same node as the click, so the role is set once.
 *
 * The actions alone are enough: TalkBack says "Expanded" or "Collapsed" from them, in the phone's
 * language. A stateDescription on top made it say the state twice ("collapsed. Collapsed. Recording"),
 * measured with TalkBack 17.1 on the OP9 (2026-10-31).
 */
fun Modifier.expandableHeader(
    expanded: Boolean,
    onToggle: () -> Unit,
): Modifier = this
    .clickable(role = Role.Button, onClick = onToggle)
    .semantics {
        if (expanded) {
            collapse { onToggle(); true }
        } else {
            expand { onToggle(); true }
        }
    }
