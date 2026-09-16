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
 *
 * ## Two kinds of import, and why the difference is a token of its own
 *
 * The user is asked what a file is *for*: keep it, or only read it. A [Kind.TRANSCRIBE_ONLY] file is
 * transcribed and then its audio is deleted, so it must never join the recordings list on the way
 * through — and that has to survive the catalog's destructive re-seed, which knows nothing but the
 * folder's file names. So the answer lives in the name, exactly as the import marker itself does.
 *
 * **It is a token AFTER the marker, not a variant of it**, and the reason is downgrades. Had this
 * been written `_import-transcribeonly`, a build from before this feature would read the marker slot,
 * fail to match `import`, and conclude the file was not an import at all — handing it straight to the
 * Drive upload and the retention sweep, which is somebody's private voice note uploaded and then
 * deleted. Written as a second token, every older build still sees `import` in the slot it looks at,
 * still exempts the file from every sweep, and merely shows an odd word in the label.
 */
object ImportedRecording {

    /** What the user said the file was for when they brought it in. */
    enum class Kind {
        /** Keep it: it joins the recordings list, like every other recording. */
        KEEP,

        /**
         * Only read it: transcribe it, then delete the audio.
         *
         * Never in the recordings list while it exists — see
         * [com.baba.callvault.data.recordings.TranscribeOnlyAudio] for when the audio may go.
         */
        TRANSCRIBE_ONLY,
    }

    /** The marker that says the user brought this file in rather than CallVault recording it. */
    const val TOKEN = "import"

    /**
     * The token that says the audio is wanted only until its transcript exists.
     *
     * One word with no separator on purpose: the label sanitiser strips underscores, so a two-word
     * token would be indistinguishable from a label, and a hyphen would read as a variant of the
     * marker rather than a token beside it.
     */
    const val TRANSCRIBE_ONLY_TOKEN = "transcribeonly"

    /**
     * Where the marker sits among the underscore-separated tokens: the timestamp itself contains an
     * underscore (`20260916_101010.123+0300`), so it occupies slots 0 and 1 and the marker is slot 2.
     */
    private const val MARKER_SLOT = 2

    /** Where [TRANSCRIBE_ONLY_TOKEN] sits when it is there at all: straight after the marker. */
    private const val KIND_SLOT = MARKER_SLOT + 1

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
    fun nameFor(
        importedAtMillis: Long,
        label: String?,
        extension: String,
        kind: Kind = Kind.KEEP,
    ): String {
        val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.CANADA).format(Date(importedAtMillis))
        val kindToken = if (kind == Kind.TRANSCRIBE_ONLY) "_$TRANSCRIBE_ONLY_TOKEN" else ""
        val suffix = labelFor(label)?.let { "_$it" } ?: ""
        val ext = when {
            extension.isBlank() -> ""
            extension.startsWith('.') -> extension
            else -> ".$extension"
        }
        return "${stamp}_$TOKEN$kindToken$suffix$ext"
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
            // A file the user really did call "transcribeonly" would otherwise land in the slot the
            // kind is read from, and a KEPT import would be read back as one whose audio may be
            // deleted the moment its transcript is stored. Dropped rather than mangled: the label is
            // a hint for the user's eye and losing it costs nothing, where misreading it costs audio.
            ?.takeUnless { it.equals(TRANSCRIBE_ONLY_TOKEN, ignoreCase = true) }
    }

    /**
     * Whether [displayName] is a file the user imported.
     *
     * Every sweep that can upload or delete asks this before it acts, so a wrong "no" here loses
     * somebody's audio. It answers from the marker slot alone — see the note on this object for why
     * anything looser is unsafe.
     */
    fun isImported(displayName: String): Boolean {
        val parts = tokensOf(displayName)
        if (parts.size <= MARKER_SLOT) return false
        if (!STAMP_HEAD.matches(parts[0])) return false
        return parts[MARKER_SLOT] == TOKEN
    }

    /**
     * The timestamp token of an import, in the raw `yyyyMMdd_HHmmss.SSSZ` form, or null.
     *
     * Given out rather than re-derived by the name parser, because the two must agree about where
     * the stamp ends: the parser finds a call's date by looking for the `in`/`out` anchor, and an
     * import has no anchor to find.
     */
    fun stampTokenOf(displayName: String): String? {
        if (!isImported(displayName)) return null
        return tokensOf(displayName).take(MARKER_SLOT).joinToString("_")
    }

    /**
     * The label an import was given, or null when it has none.
     *
     * This is the nearest thing an import has to a contact name — what the source file was called —
     * and it is what the UI shows where a call shows who it was with.
     */
    fun labelOf(displayName: String): String? {
        if (!isImported(displayName)) return null
        // Past the kind token where there is one, so a transcribe-only import is labelled with what
        // the user's file was called and not with our own bookkeeping word.
        val from = if (kindOf(displayName) == Kind.TRANSCRIBE_ONLY) KIND_SLOT + 1 else MARKER_SLOT + 1
        return tokensOf(displayName).drop(from).joinToString("_").ifBlank { null }
    }

    /**
     * What the user said [displayName] was for, or null when it is not an import at all.
     *
     * Read from the name on every occasion rather than remembered anywhere, for the same reason
     * [isImported] is: the catalog is a destructible cache and a re-seed knows nothing but the file
     * names in the folder. A kind stored in the database alone would be gone after a re-seed, and a
     * transcribe-only file would quietly rejoin the recordings list.
     */
    fun kindOf(displayName: String): Kind? {
        if (!isImported(displayName)) return null
        val parts = tokensOf(displayName)
        val isTranscribeOnly = parts.size > KIND_SLOT && parts[KIND_SLOT] == TRANSCRIBE_ONLY_TOKEN
        return if (isTranscribeOnly) Kind.TRANSCRIBE_ONLY else Kind.KEEP
    }

    /**
     * Whether [displayName] is an import whose audio is wanted only until its transcript exists.
     *
     * Asked by everything that decides where such a file may appear and when its audio may go, so
     * both a wrong "yes" (audio deleted that the user meant to keep) and a wrong "no" (a file the
     * user never wanted in their call list, sitting in it for ever) are answered in one place.
     */
    fun isTranscribeOnly(displayName: String): Boolean = kindOf(displayName) == Kind.TRANSCRIBE_ONLY

    /**
     * [displayName] split on underscores, with its extension removed.
     *
     * The extension is only the tail after the LAST dot when that dot comes after the last
     * underscore. The timestamp carries dots of its own (`101010.123+0300`), so chopping at the last
     * dot unconditionally would eat half the name of anything stored without an extension.
     */
    private fun tokensOf(displayName: String): List<String> {
        val base = if (displayName.lastIndexOf('.') > displayName.lastIndexOf('_')) {
            displayName.substringBeforeLast('.')
        } else {
            displayName
        }
        return base.split('_')
    }
}
