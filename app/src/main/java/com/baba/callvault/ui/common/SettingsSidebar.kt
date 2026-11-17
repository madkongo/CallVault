/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Hosts Settings as a full page that slides in over the current screen (BabApps item 135).
 *
 * Settings used to be a destination (`AppNavigationViewModel.manualScreen` swapped Home out for it), then a
 * panel covering 92% of the width in a navigation drawer. It is now a whole page: the screen is Settings and
 * nothing else, with its own header and back arrow. Home stays composed underneath, so closing is instant and
 * nothing behind has to be rebuilt, and the page still arrives from the same edge as the gear that opens it.
 *
 * The page consumes touches, so nothing underneath can be tapped through it. Back closes it, and only while
 * it is open: an always-registered handler would swallow the gesture that leaves Home.
 *
 * @param open     Whether the page is showing, hoisted so the caller can open it from its own UI.
 * @param onClose  Invoked when the user dismisses the page (back).
 * @param settings The Settings UI to render in the page.
 * @param content  The screen that stays mounted underneath.
 */
@Composable
fun SettingsSidebar(
    open: Boolean,
    onClose: () -> Unit,
    settings: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = open) { onClose() }

    Box(Modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(
            visible = open,
            // Slides in from the end edge, where the gear is, in either layout direction.
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                // Match the app background so CvScaffold's own header sits on the colour it expects.
                color = MaterialTheme.colorScheme.background,
            ) {
                settings()
            }
        }
    }
}
