/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * How an app call's recording ended, as one always-on line (review N8).
 *
 * A vivo phone (Android 17, China firmware) started 23 app-call recordings and saved none; the always-on log had
 * each "capture path: VoIP" start and nothing after it, because every discard path wrote only to the debug log,
 * which was off. [VoipRecordingCoordinator] now writes exactly one of these for every app call it acts on, through
 * [com.baba.callvault.system.diagnostics.CallEndLog]. No names and no numbers: the reason, the recorded length and
 * the file size only.
 */
object VoipOutcome {

    enum class Kind(val word: String) { SAVED("SAVED"), DISCARDED("DISCARDED"), FAILED("FAILED"), NOT_STARTED("NOT STARTED") }

    enum class Reason(val kind: Kind, val why: String) {
        SAVED(Kind.SAVED, "published to the recordings folder"),

        // Not started: nothing was recorded.
        MASTER_SWITCH_OFF(Kind.NOT_STARTED, "the master recording switch is off"),
        RECORDER_NOT_CONNECTED(Kind.NOT_STARTED, "the recorder host is not connected"),
        APP_SWITCHED_OFF(Kind.NOT_STARTED, "this app is switched off for recording"),
        CALLER_IGNORED(Kind.NOT_STARTED, "the caller is on the ignore list"),
        NO_FOLDER(Kind.NOT_STARTED, "the recordings folder is missing or not writable"),
        FILE_NOT_CREATED(Kind.NOT_STARTED, "the output file could not be created"),
        RECORDER_REFUSED(Kind.NOT_STARTED, "the recorder refused the app-call capture"),
        ENDED_DURING_START_CHECK(Kind.NOT_STARTED, "the call ended while its start waited for the leftover-session check"),

        // Started, then deliberately not kept.
        IGNORED_LATE(Kind.DISCARDED, "the caller, found during the call, is on the ignore list"),
        APP_SWITCHED_OFF_LATE(Kind.DISCARDED, "the app, found during the call, is switched off for recording"),
        NEVER_CONNECTED(Kind.DISCARDED, "the call never connected (ring-time mode dip, no call-voice player)"),
        ENDED_BEFORE_CAPTURE(Kind.DISCARDED, "the call ended before the capture got going (under 3 s, far side never heard)"),
        UNANSWERED(Kind.DISCARDED, "\"Start when they answer\": the call ended while still held for the answer"),
        TOO_SHORT(Kind.DISCARDED, "under the minimum duration setting"),

        // Started, and lost or left behind by a fault.
        NO_AUDIO(Kind.FAILED, "the capture wrote no audio (it never started in the recorder)"),
        STAGING_MISSING(Kind.FAILED, "the staged recording's details are missing; nothing to publish"),
        NOT_PUBLISHED(Kind.FAILED, "could not publish to the recordings folder; kept in staging for the rescue"),
        NOT_STOPPED(Kind.FAILED, "the recorder did not confirm it stopped; left to the orphan check and the rescue"),
    }

    /**
     * The always-on line. [recordedMs] and [sizeBytes] are null when unknown, never guessed. Both carry their unit:
     * the report's redaction hides a bare run of seven or more digits as a phone number.
     */
    fun line(reason: Reason, recordedMs: Long?, sizeBytes: Long?): String =
        "app call outcome: ${reason.kind.word} (${reason.name.lowercase()}: ${reason.why}) " +
            "recorded=${recordedMs?.let { "$it ms" } ?: "?"} size=${sizeBytes?.let { "$it bytes" } ?: "?"}"
}
