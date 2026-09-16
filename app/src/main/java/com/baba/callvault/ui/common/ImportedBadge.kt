/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baba.callvault.R

/**
 * Says that a row is a file the user brought in, not a call CallVault recorded.
 *
 * It is worth a word of its own on every list. An import has no number, no direction and no
 * contact, so it arrives in a list of calls looking like a call whose details all failed to parse —
 * which is a bug the user would reasonably report. It also behaves differently, and in ways that
 * matter: it is never copied to Drive, never aged out by retention, and never evicted by the
 * storage cap, so this phone holds the only copy there is.
 *
 * Text rather than a glyph. There is no icon for "you gave us this one", and the badge next to it —
 * the VoIP app's own mark — has already used up the vocabulary of small round pictures.
 *
 * Colours are stated. Several of M3's own roles resolve to CoralDeep in this scheme, and a default
 * tonal pill would render an ordinary label in the colour the app uses for trouble.
 */
@Composable
fun ImportedBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.home_row_imported),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(PaddingValues(horizontal = 6.dp, vertical = 2.dp)),
    )
}
