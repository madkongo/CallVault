/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.services.recording.DaemonKeepAliveService

/**
 * MainActivity is the single Android Activity entry point for CallVault.
 * This is called when Android want to show the application UI to the user.
 *
 * Its **only** responsibility is to attach the Compose content tree to the window
 * and draw edge-to-edge (the navy background extends behind the transparent system bars).
 *
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppNavigationScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        // Anchor the recorder daemon whenever the app is opened. Modern Android requires a foreground
        // context to (re)start a foreground service, so we do it here rather than from Application.onCreate.
        // Idempotent — no-op if the keep-alive service is already running.
        if (AppPreferences(applicationContext).isAdbPaired()) {
            DaemonKeepAliveService.start(applicationContext)
        }
    }
}