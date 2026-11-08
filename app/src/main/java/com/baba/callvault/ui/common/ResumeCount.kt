/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Goes up each time the screen comes back to the front. Key a read on it to redo the read then: the user may
 * have been away changing exactly what it shows (started Shizuku, installed a calling app, switched USB mode).
 */
@Composable
fun rememberResumeCount(): Int {
    var count by remember { mutableIntStateOf(0) }
    // The first resume is the one the screen was composed in, and its reads have just run; count the returns.
    var composedIn by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (composedIn) composedIn = false else count++ }
    return count
}
