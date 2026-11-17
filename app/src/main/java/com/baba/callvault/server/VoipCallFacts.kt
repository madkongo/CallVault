/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * The structure of an app's call notification, with none of its text: what [VoipDirection] reads to
 * tell an incoming app call from an outgoing one (BabApps item 113). No title, no text, so no name
 * or number can reach a log or the binder through this.
 *
 * @param category      `Notification.category` ("call"), or null.
 * @param actionCount   how many action buttons it carries.
 * @param callType      `android.callType` of a CallStyle (1 incoming, 2 ongoing, 3 screening), or null.
 * @param showChronometer `android.showChronometer`: false while it rings, true once answered; null = not set.
 * @param ongoing       the ONGOING_EVENT flag.
 * @param incomingFlag  an app-specific "this is an incoming call" extra (`IS_INCOMING_CALL`), or null.
 */
data class VoipCallFacts(
    val category: String?,
    val actionCount: Int,
    val callType: Int?,
    val showChronometer: Boolean?,
    val ongoing: Boolean,
    val incomingFlag: Boolean? = null,
) {
    /** One `key=value;` line for the binder; [parse] reads it back. */
    fun encode(): String = listOf(
        "cat=${category.orEmpty()}",
        "actions=$actionCount",
        "type=${callType ?: ""}",
        "chrono=${showChronometer ?: ""}",
        "ongoing=$ongoing",
        "incoming=${incomingFlag ?: ""}",
    ).joinToString(";")

    /** The same facts as a log fragment. */
    fun describe(): String =
        "category=${category ?: "none"} actions=$actionCount callType=${callType ?: "none"} " +
            "showChronometer=${showChronometer ?: "unset"} ongoing=$ongoing incomingFlag=${incomingFlag ?: "unset"}"

    companion object {
        fun parse(line: String?): VoipCallFacts? {
            if (line.isNullOrBlank()) return null
            val map = line.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }.toMap()
            return VoipCallFacts(
                category = map["cat"]?.ifBlank { null },
                actionCount = map["actions"]?.toIntOrNull() ?: return null,
                callType = map["type"]?.toIntOrNull(),
                showChronometer = map["chrono"]?.toBooleanStrictOrNull(),
                ongoing = map["ongoing"] == "true",
                incomingFlag = map["incoming"]?.toBooleanStrictOrNull(),
            )
        }
    }
}
