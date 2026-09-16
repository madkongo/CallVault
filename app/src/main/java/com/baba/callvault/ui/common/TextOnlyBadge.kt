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
 * Says that a row is words with no recording behind them.
 *
 * Worth a word because it changes what the row can do, and the change is otherwise only discoverable
 * by tapping: there is no player inside, no line to tap to hear, and no audio to share. Every
 * finished "Transcribe only" import is one of these — the user asked for exactly that — and so is any
 * transcript or summary whose recording has since been deleted.
 *
 * **Quieter than [ImportedBadge]**, deliberately. That one is a category ("this is not a call"); this
 * one is an absence, and a filled accent pill would announce a missing file as if it were a feature.
 * Surface variant is the app's colour for a neutral aside.
 *
 * Colours are stated. Several of M3's own roles resolve to CoralDeep in this scheme, so a default
 * tonal pill would render an ordinary label in the colour the app uses for trouble.
 */
@Composable
fun TextOnlyBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.home_row_text_only),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(PaddingValues(horizontal = 6.dp, vertical = 2.dp)),
    )
}
