/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.baba.callvault.system.storage.RetentionSweepWorker
import com.baba.callvault.utils.AppLogger

/**
 * DEBUG-ONLY throwaway trigger to run the [RetentionSweepWorker] on demand (instead of waiting for the
 * daily schedule), so the auto-delete logic can be validated. Uses the current retention prefs. Remove
 * before release.
 *
 * Trigger:
 *   adb shell am broadcast -n com.baba.callvault/com.baba.callvault.debug.RetentionTestReceiver \
 *       -a com.baba.callvault.debug.RUN_RETENTION
 */
class RetentionTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_RETENTION) return
        AppLogger.i(TAG, "RUN_RETENTION triggered; enqueuing a one-time retention sweep")
        val request = OneTimeWorkRequestBuilder<RetentionSweepWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }

    companion object {
        private const val TAG = "CV:RetentionSweep"
        const val ACTION_RUN_RETENTION = "com.baba.callvault.debug.RUN_RETENTION"
    }
}
