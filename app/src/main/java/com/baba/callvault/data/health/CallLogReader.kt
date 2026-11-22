/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import android.content.Context
import android.provider.CallLog
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger

/**
 * Reads answered calls out of the system call log for [CallGapDetector].
 *
 * Never throws and never partially reports: any failure — missing permission, a provider that throws,
 * a null cursor — yields an empty list, which the detector turns into zero gaps. A check that could not
 * run must not render as a failure.
 */
object CallLogReader {

    private const val TAG = "CV:CallLogReader"

    fun entriesSince(context: Context, watermark: Long): List<CallLogEntry> {
        if (!PermissionChecks.hasCallLogPermission(context)) {
            AppLogger.i(TAG, "No call-log permission; the setup sweep claims nothing this time")
            return emptyList()
        }
        return entriesSinceOrNull(context, watermark) ?: emptyList()
    }

    /**
     * [entriesSince] for a caller that must tell "no calls" from "could not read": null on a missing permission, a
     * failed query or a null cursor. A claim built on the log (the orphan's "saved in full") must not read a failure as
     * an empty log.
     */
    fun entriesSinceOrNull(context: Context, watermark: Long): List<CallLogEntry>? {
        if (!PermissionChecks.hasCallLogPermission(context)) return null
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(watermark.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val type = cursor.getInt(2)
                        if (type != CallLog.Calls.INCOMING_TYPE && type != CallLog.Calls.OUTGOING_TYPE) continue
                        add(
                            CallLogEntry(
                                startedAt = cursor.getLong(0),
                                durationSeconds = cursor.getLong(1),
                                isIncoming = type == CallLog.Calls.INCOMING_TYPE,
                                label = cursor.getString(3)?.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
            }
        }.getOrElse { e ->
            AppLogger.w(TAG, "Call log unreadable (${e.message}); the setup sweep claims nothing this time")
            null
        }
    }
}
