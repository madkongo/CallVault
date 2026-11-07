/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

/**
 * The number actually dialled for an outgoing call that a call redirection app (e.g. Google Voice) rerouted.
 *
 * A [CallRedirectionService](https://developer.android.com/reference/android/telecom/CallRedirectionService)
 * may place the call through a proxy number. Telecom then makes the proxy the call's handle, which is the number
 * the PHONE_STATE broadcast carries, while the call log keeps the original one
 * ([GatewayInfo.getOriginalAddress](https://developer.android.com/reference/android/telecom/GatewayInfo#getOriginalAddress())).
 * See AOSP Telecom: `CallsManager.placeOutgoingCall` (`call.setHandle(gatewayAddress)`),
 * `PhoneStateBroadcaster` (`call.getHandle()`) and `CallLogManager.getLogNumber` (`call.getOriginalHandle()`).
 *
 * The call-log entry is written only after the call ends, so the file is renamed afterwards. Pure, for testing.
 * Same idea as BCR's support for call redirection: https://github.com/chenxiaolong/BCR/pull/444
 */
object RedirectedCall {

    /** One outgoing call-log row, as much of it as matching needs. */
    data class LogEntry(val date: Long, val lastModified: Long, val number: String)

    /** How long before our recording started its call may have been placed: with record-from-answer, the ringing too. */
    const val MAX_CALL_LEAD_MS = 3 * 60_000L

    /** How far after our start a log DATE may be and still be this call: clocks and callbacks jitter. */
    const val CLOCK_SLACK_MS = 5_000L

    /**
     * The call-log entry of the call recorded from [recordingStartMillis], or null while it is not written yet.
     *
     * The latest outgoing entry placed at most [MAX_CALL_LEAD_MS] before the recording started and written
     * ([CallLog.Calls.LAST_MODIFIED](https://developer.android.com/reference/android/provider/CallLog.Calls#LAST_MODIFIED))
     * after it started: an entry is written when its call ends, so a previous call's entry never qualifies.
     */
    fun entryForCall(entries: List<LogEntry>, recordingStartMillis: Long): LogEntry? = entries
        .filter { it.date in (recordingStartMillis - MAX_CALL_LEAD_MS)..(recordingStartMillis + CLOCK_SLACK_MS) }
        .filter { it.lastModified >= recordingStartMillis }
        .maxByOrNull { it.date }

    /**
     * The dialled number when the call in [entry] was redirected, or null to keep the name as it is: the entry has
     * no number, or its number is the one we already have (a call that was not redirected).
     *
     * @param entry this call's entry, from [entryForCall].
     * @param handleNumber the number the PHONE_STATE broadcast gave for this call.
     * @param sameNumber the number comparison; on a phone, `PhoneNumberUtils.compare`.
     */
    fun dialledNumber(entry: LogEntry, handleNumber: String?, sameNumber: (String, String) -> Boolean): String? {
        if (handleNumber.isNullOrBlank() || entry.number.isBlank() || sameNumber(entry.number, handleNumber)) return null
        return entry.number
    }
}
