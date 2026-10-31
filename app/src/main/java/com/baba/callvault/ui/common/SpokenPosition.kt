/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * The playback position a seek bar reports to TalkBack, throttled so a focused bar does not read the
 * time out every second while a call plays. It moves in [SeekSemantics.SPOKEN_STEP_MS] steps during
 * playback, follows a seek at once, and settles on the exact time once playback has stopped.
 * The bar itself still draws [positionMs]; only the spoken value is held back.
 */
@Composable
fun rememberSpokenPositionMs(positionMs: Int): Int {
    val spoken = remember { mutableIntStateOf(positionMs) }
    val previous = remember { mutableIntStateOf(positionMs) }
    LaunchedEffect(positionMs) {
        spoken.intValue = SeekSemantics.spokenPositionAfter(spoken.intValue, previous.intValue, positionMs)
        previous.intValue = positionMs
        // Still running when no newer position arrives in time: playback stopped, read the exact time.
        delay(SeekSemantics.SPOKEN_SETTLE_MS)
        spoken.intValue = positionMs
    }
    return spoken.intValue
}
