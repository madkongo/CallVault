/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The name an imported audio file is stored under, and how one is told apart from a recording
 * afterwards: `{stamp}_import[_{label}]{ext}`, mirroring the VoIP grammar next to it.
 *
 * An import is a file the user already owned — a voice note, a meeting, something a friend sent —
 * that CallVault is only asked to transcribe. It lives in the recordings folder so it survives the
 * catalog's destructive re-seed and needs no second folder grant, and that is exactly what makes
 * this name load-bearing: every sweep that walks that folder uploads or deletes what it finds
 * there. The name is the only thing telling those sweeps that this file is not ours to move.
 *
 * **Recognition is by position, not by search.** [isImported] reads the marker slot — the token
 * straight after the timestamp — and nothing else, and it only reads it for a name that opens with
 * a CallVault date token. Scanning the whole name for the word instead would hand the exemption to
 * a call with a contact called "Important", to a VoIP app named "Import", and to any stranger file
 * with the word in it; and an exemption handed out by accident is a recording that quietly stops
 * being backed up and never expires.
 */
object ImportedRecording {

    /** The marker that says the user brought this file in rather than CallVault recording it. */
    const val TOKEN = "import"

    /**
     * Where the marker sits among the underscore-separated tokens: the timestamp itself contains an
     * underscore (`20260916_101010.123+0300`), so it occupies slots 0 and 1 and the marker is slot 2.
     */
    private const val MARKER_SLOT = 2

    /** Same stamp the carrier and VoIP recorders write, so one sort orders the whole folder. */
    private const val STAMP_PATTERN = "yyyyMMdd_HHmmss.SSSZ"

    /**
     * How much of the source name a label keeps. Long enough to tell two voice notes apart, short
     * enough that the result stays inside the name-length limits of a SAF provider we do not own.
     */
    private const val MAX_LABEL_LENGTH = 40

    /**
     * Characters a label may not carry. The underscore is in here for the same reason as in the
     * VoIP name: a label with one in it would open a slot of its own and the name would parse back
     * as something other than what was written.
     */
    private val FORBIDDEN_IN_LABEL = Regex("""[/\\:*?"<>|_\p{Cntrl}]""")

    /** A trailing extension on the raw label, so an import is never named after its source verbatim. */
    private val SOURCE_EXTENSION = Regex("""\.[A-Za-z0-9]{1,5}$""")

    /** The date token CallVault puts at the front of every name it writes. */
    private val STAMP_HEAD = Regex("""\d{8}""")

    /**
     * The file name for audio imported at [importedAtMillis].
     *
     * [extension] is the container extension, with or without its dot. [label] is a hint from the
     * source — its file name, usually — and is best-effort: an unusable one simply drops out rather
     * than being guessed at, exactly as an absent VoIP caller does.
     */
    fun nameFor(importedAtMillis: Long, label: String?, extension: String): String {
        val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.CANADA).format(Date(importedAtMillis))
        val suffix = labelFor(label)?.let { "_$it" } ?: ""
        val ext = when {
            extension.isBlank() -> ""
            extension.startsWith('.') -> extension
            else -> ".$extension"
        }
        return "${stamp}_$TOKEN$suffix$ext"
    }

    /**
     * The usable part of [raw] as a name label, or null when nothing usable is left.
     *
     * The source's extension goes first: a label is a hint for the user's eye, and carrying
     * "note.m4a" into a name that already ends in `.ogg` would both read wrongly and put a second
     * extension where a parser looks for the first. A leading dot goes too — it would make the
     * import a hidden file in the user's own folder.
     */
    fun labelFor(raw: String?): String? {
        val source = raw?.trim().orEmpty()
        if (source.isEmpty()) return null
        return source
            .replace(SOURCE_EXTENSION, "")
            .replace(FORBIDDEN_IN_LABEL, "")
            .trim()
            .trimStart('.')
            .trim()
            .take(MAX_LABEL_LENGTH)
            .trim()
            .ifBlank { null }
    }

    /**
     * Whether [displayName] is a file the user imported.
     *
     * Every sweep that can upload or delete asks this before it acts, so a wrong "no" here loses
     * somebody's audio. It answers from the marker slot alone — see the note on this object for why
     * anything looser is unsafe.
     */
    fun isImported(displayName: String): Boolean {
        // The extension is only the tail after the LAST dot when that dot comes after the last
        // underscore. The timestamp carries dots of its own ("101010.123+0300"), so chopping at the
        // last dot unconditionally would eat half the name of anything stored without an extension.
        val base = if (displayName.lastIndexOf('.') > displayName.lastIndexOf('_')) {
            displayName.substringBeforeLast('.')
        } else {
            displayName
        }
        val parts = base.split('_')
        if (parts.size <= MARKER_SLOT) return false
        if (!STAMP_HEAD.matches(parts[0])) return false
        return parts[MARKER_SLOT] == TOKEN
    }
}
