/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Stop for now" on the Drive copy notification.
 *
 * A flag the sweep reads between files, never a cancel: cancelling the work would end the periodic
 * schedule itself, and stopping mid-file is exactly what must not happen. The file being copied finishes
 * and is recorded, the run ends, and the next scheduled run picks up what is left along with new calls.
 */
object DriveCopyStop {

    private val requested = AtomicBoolean(false)

    /** Called at the start of every run, so an old press never stops a later one. */
    fun reset() = requested.set(false)

    fun request() = requested.set(true)

    fun isRequested(): Boolean = requested.get()

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, DriveCopyStopReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val REQUEST_CODE = 4301
}

/** Receives "Stop for now". Not exported: only our own notification can send it. */
class DriveCopyStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.i("CV:SyncSweep", "Stop for now pressed; the copy stops after the current file")
        DriveCopyStop.request()
    }
}
