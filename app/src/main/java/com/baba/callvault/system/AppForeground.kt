/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Process-wide foreground/background tracker, driven by activity start/stop counts. Used by the
 * auto-updater to avoid installing (which kills the app) while the user is actively looking at it:
 * the silent install runs only while [isForeground] is false, and [register]'s onBackground callback
 * fires the moment the last activity stops, so a pending auto-update installs right after the user
 * leaves the app.
 */
object AppForeground {

    @Volatile
    var isForeground: Boolean = false
        private set

    private var startedActivities = 0

    fun register(app: Application, onEnterBackground: () -> Unit) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                isForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    isForeground = false
                    onEnterBackground()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
