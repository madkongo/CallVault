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
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.baba.callvault.ui.common.CvCard

/**
 * The experimental opt-ins. In Settings they sit in the Connection and Recording groups (the classic
 * screen's General ▸ Experimental section, which this step once mirrored, was removed 2026-10-31).
 *
 * The toggle is the very same composable Settings renders — shared rather than reimplemented, so a
 * change to it reaches both screens at once. The wizard cannot be re-run, so a feature it never
 * mentions is one most users never discover.
 *
 * VoIP recording used to sit here too. It moved to the auto-record step: it is not an extra, it is one
 * of the two answers to "what gets recorded", and being two steps away from the other one is what let
 * people finish with neither.
 */
@Composable
internal fun ExperimentalStep() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CvCard { HandoffPersistToggle() }
    }
}
