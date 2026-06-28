/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * MainActivity is the single Android Activity entry point for CallVault.
 * This is called when Android want to show the application UI to the user.
 *
 * Its **only** responsibility is to attach the Compose content tree to the window
 * and draw edge-to-edge (the navy background extends behind the transparent system bars).
 *
 * `android:launchMode="singleTop"` (set in the manifest) ensures that tapping the
 * DialerLauncherAlias while the app is already open delivers the intent via [onNewIntent]
 * rather than creating a new instance, so the dialpad routing works in both cold and warm starts.
 */
class MainActivity : AppCompatActivity() {

    /**
     * Reactive flag: true while the app should route to the dialpad.
     * Reset to false by [AppNavigationScreen] after it has acted on the signal,
     * so a subsequent alias tap re-fires the effect correctly.
     */
    private var openDialer by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openDialer = isDialerLaunch(intent)
        setContent {
            AppNavigationScreen(
                openDialer = openDialer,
                onDialerHandled = { openDialer = false }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isDialerLaunch(intent)) openDialer = true
    }

    /** Returns true when [intent] was fired from the DialerLauncherAlias component. */
    private fun isDialerLaunch(intent: Intent?): Boolean =
        intent?.component?.shortClassName?.endsWith("DialerLauncherAlias") == true
}
