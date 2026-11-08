/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.calls

import android.content.Context
import android.provider.CallLog
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger

/**
 * Reads calls of every kind from the system call log, newest first.
 *
 * Separate from [com.baba.callvault.data.health.CallLogReader], which keeps only answered calls for the
 * setup sweep and whose tests pin its exact columns. Never throws: no permission or an unreadable log is
 * an empty list.
 */
object PhoneCallReader {

    private const val TAG = "CV:PhoneCallReader"

    private val PROJECTION = arrayOf(
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
        CallLog.Calls.TYPE,
        CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
        CallLog.Calls.PHONE_ACCOUNT_ID,
    )

    /** Calls after [since] (epoch ms), newest first, at most [limit]. */
    fun since(context: Context, since: Long, limit: Int = DEFAULT_LIMIT): List<PhoneCall> {
        if (!PermissionChecks.hasCallLogPermission(context)) return emptyList()
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, PROJECTION,
                "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext() && size < limit) {
                        add(
                            PhoneCall(
                                startedAt = c.getLong(0),
                                durationSeconds = c.getLong(1),
                                kind = kindOf(c.getInt(2)),
                                number = c.getString(3)?.takeIf { it.isNotBlank() },
                                cachedName = c.getString(4)?.takeIf { it.isNotBlank() },
                                accountComponent = c.getString(5)?.takeIf { it.isNotBlank() },
                                accountId = c.getString(6)?.takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }
            }.orEmpty()
        }.getOrElse { e ->
            AppLogger.w(TAG, "Call log unreadable: ${e.message}")
            emptyList()
        }
    }

    internal fun kindOf(type: Int): CallKind = when (type) {
        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallKind.INCOMING
        CallLog.Calls.OUTGOING_TYPE -> CallKind.OUTGOING
        CallLog.Calls.MISSED_TYPE -> CallKind.MISSED
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> CallKind.REJECTED
        else -> CallKind.OTHER
    }

    private const val DEFAULT_LIMIT = 1000
}
