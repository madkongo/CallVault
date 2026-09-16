/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.export

import com.baba.callvault.data.SpeakerNames
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.summary.CallSummary
import com.baba.callvault.ui.common.TranscriptTimestamp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Everything an export needs, gathered before rendering starts.
 *
 * A plain value rather than a database handle so [TranscriptExport] stays a pure function of its
 * input: the interesting failures here are a malformed cue or a lost speaker label, and those are
 * only cheap to test when producing the text needs no Android and no I/O.
 *
 * @param title what the call is called on screen — the contact, or the file name when there is none.
 * @param speakerNames how to render each segment's channel key, or null when nobody is named.
 * @param summary the stored summary, if one exists. Only [TranscriptFormat.MARKDOWN] and
 *   [TranscriptFormat.JSON] carry it; a subtitle file has nowhere to put it.
 * @param note the user's own note, on the same terms.
 *
 * 🚨 **No field here has a default, deliberately.** `note` was added with one, rendered, and unit
 * tested — and then never passed at the single production call site, so the Markdown note section was
 * unreachable for a release while its test passed happily. A test that builds this object cannot
 * notice that nobody builds it that way in the app. Requiring every field makes the compiler ask the
 * question instead, which is the only thing here that catches an omission rather than a mistake.
 */
data class ExportDocument(
    val title: String,
    val segments: List<TranscriptSegmentEntry>,
    val speakerNames: SpeakerNames?,
    val summary: CallSummary?,
    val note: String?,
    /** The user's own labels for this call. Carried by the formats that have somewhere to put them. */
    val tags: List<String>,
    val language: String?,
    val model: String?
)

/**
 * Renders a transcript into each of the formats in [TranscriptFormat].
 *
 * **Segments with no text are dropped everywhere.** Whisper occasionally emits an empty segment, and
 * an empty subtitle cue is not merely ugly — it is invalid, and some players stop reading the file at
 * the first one, which would silently truncate an export rather than blemish it.
 */
object TranscriptExport {

    /**
     * The shortest a subtitle cue may last.
     *
     * Whisper can report a segment whose end is at or before its start. A cue like that either never
     * displays or is rejected outright depending on the player, so an end time is nudged forward
     * instead. A tenth of a second is below the threshold of noticing and above zero, which is the
     * only property that matters.
     */
    private const val MIN_CUE_MS = 100L

    private const val MILLIS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L

    /** The transcript in [format]. */
    fun render(format: TranscriptFormat, doc: ExportDocument, labels: ExportLabels): String = when (format) {
        TranscriptFormat.TXT -> renderText(doc)
        TranscriptFormat.MARKDOWN -> renderMarkdown(doc, labels)
        TranscriptFormat.SRT -> renderSubtitles(doc, srt = true)
        TranscriptFormat.VTT -> renderSubtitles(doc, srt = false)
        TranscriptFormat.JSON -> renderJson(doc)
    }

    /**
     * A file name for [displayName] in [format].
     *
     * The recording's own extension is replaced rather than appended, so a call saved as `call.ogg`
     * exports as `call.srt` and not `call.ogg.srt` — the latter opens in nothing, because the system
     * types a file by its last extension.
     */
    fun fileName(format: TranscriptFormat, displayName: String): String =
        displayName.substringBeforeLast('.', displayName) + "." + format.extension

    /**
     * The transcript as it reads on screen.
     *
     * This is also what the Copy button puts on the clipboard, so the two can never drift into
     * disagreeing about what the transcript says.
     */
    fun renderText(doc: ExportDocument): String = plainText(doc.segments, doc.speakerNames)

    /**
     * The primitive behind both the TXT export and the Copy button.
     *
     * Shared rather than duplicated so the clipboard and the exported file can never come to
     * disagree about what the transcript says — a difference nobody would notice until two people
     * compared a pasted quote against an attached file.
     */
    fun plainText(segments: List<TranscriptSegmentEntry>, names: SpeakerNames?): String =
        segments.filter { it.text.isNotBlank() }.joinToString("\n") { segment ->
            val speaker = names?.of(segment.speaker)?.let { "$it: " }.orEmpty()
            "[${TranscriptTimestamp.format(segment.startMs)}] $speaker${segment.text.trim()}"
        }

    /**
     * The transcript with its summary and note, under headings in the reader's language.
     *
     * The summary comes **first**, before the transcript, because it is the part a reader wants and
     * the transcript can run to hundreds of lines. Sections with nothing in them are omitted rather
     * than left as empty headings.
     */
    private fun renderMarkdown(doc: ExportDocument, labels: ExportLabels): String = buildString {
        appendLine("# ${doc.title}")

        val tags = doc.tags.filter { it.isNotBlank() }
        if (tags.isNotEmpty()) {
            appendLine()
            // Directly under the title, where a reader looks for what a document is about. These are
            // the user's own words for the call and are worth more to them than the provenance line.
            appendLine(tags.joinToString(" ") { "`$it`" })
        }

        val provenance = listOfNotNull(
            doc.language?.takeIf { it.isNotBlank() }?.let { "${labels.language}: $it" },
            doc.model?.takeIf { it.isNotBlank() }?.let { "${labels.model}: $it" }
        )
        if (provenance.isNotEmpty()) {
            appendLine()
            // Italicised and kept together: it says how the text was produced, which is worth
            // carrying with an exported transcript and is not part of what anyone said.
            appendLine("*${provenance.joinToString(" · ")}*")
        }

        doc.summary?.let { summary ->
            appendLine()
            append(renderSummary(summary, labels, titleMark = "## ", sectionMark = "### "))
        }

        doc.note?.takeIf { it.isNotBlank() }?.let { note ->
            appendLine()
            appendLine("## ${labels.notes}")
            appendLine()
            appendLine(note.trim())
        }

        val body = renderText(doc)
        if (body.isNotEmpty()) {
            appendLine()
            appendLine("## ${labels.transcript}")
            appendLine()
            doc.segments.filter { it.text.isNotBlank() }.forEach { segment ->
                val speaker = doc.speakerNames?.of(segment.speaker)?.let { " **$it**" }.orEmpty()
                appendLine("`${TranscriptTimestamp.format(segment.startMs)}`$speaker ${segment.text.trim()}")
                appendLine()
            }
        }
    }.trimEnd() + "\n"

    /**
     * The summary on its own, under headings in the reader's language.
     *
     * Extracted from the Markdown export rather than written twice, because a summary is now shared
     * from a row as well as carried inside an exported document, and two renderings of one structure
     * would be two places for a heading to go missing.
     *
     * **The two heading marks are parameters because the destination decides them.** A `.md` file
     * wants `##` and `###`; a summary sent into a chat as plain text wants neither, and would
     * otherwise arrive with hash marks in front of every heading — visible punctuation that says
     * nothing about the call. Only the marks differ, so the sections, the order and the dropping of
     * empty ones cannot come apart between the two.
     *
     * Bullets stay `- ` in both: a leading dash reads as a list wherever it lands, unlike a `#`.
     */
    fun renderSummary(
        summary: CallSummary,
        labels: ExportLabels,
        titleMark: String,
        sectionMark: String,
    ): String = buildString {
        appendLine("$titleMark${labels.summary}")
        appendLine()
        appendLine(summary.intent)
        appendLine()
        appendLine(summary.summary)
        appendSection(sectionMark, labels.keyPoints, summary.keyPoints)
        appendSection(sectionMark, labels.decisions, summary.decisions)
        appendSection(sectionMark, labels.actionItems, summary.actionItems)
        appendSection(sectionMark, labels.keyFacts, summary.keyFacts)
    }.trimEnd() + "\n"

    private fun StringBuilder.appendSection(mark: String, heading: String, items: List<String>) {
        val entries = items.filter { it.isNotBlank() }
        if (entries.isEmpty()) return
        appendLine()
        appendLine("$mark$heading")
        appendLine()
        entries.forEach { appendLine("- ${it.trim()}") }
    }

    /**
     * SRT or WebVTT, which differ in less than they appear to.
     *
     * The three real differences: WebVTT opens with a `WEBVTT` line, separates its timestamps with a
     * full stop where SRT uses a comma, and treats `<` as the start of a tag — so payload text is
     * escaped for VTT and left alone for SRT, where escaping it would show the entities literally.
     *
     * Cue numbering is kept for both. It is required by SRT and optional in VTT, and a numbered VTT
     * cue is easier to talk about when something is wrong with one.
     */
    private fun renderSubtitles(doc: ExportDocument, srt: Boolean): String = buildString {
        if (!srt) {
            appendLine("WEBVTT")
            appendLine()
        }

        doc.segments
            .filter { it.text.isNotBlank() }
            .forEachIndexed { index, segment ->
                val start = segment.startMs.coerceAtLeast(0L)
                val end = segment.endMs.coerceAtLeast(start + MIN_CUE_MS)
                val speaker = doc.speakerNames?.of(segment.speaker)?.let { "$it: " }.orEmpty()

                // Newlines inside a cue are legal, but a *blank* line ends the cue — so a segment
                // that happened to contain one would truncate its own subtitle and leave the rest as
                // a malformed cue of its own.
                val text = (speaker + segment.text.trim())
                    .replace(Regex("\\s*\\n\\s*\\n\\s*"), "\n")
                    .let { if (srt) it else escapeForVtt(it) }

                appendLine(index + 1)
                appendLine("${cueTime(start, srt)} --> ${cueTime(end, srt)}")
                appendLine(text)
                appendLine()
            }
    }

    /**
     * `HH:MM:SS,mmm` for SRT, `HH:MM:SS.mmm` for WebVTT.
     *
     * Hours are always written, with at least two digits, even for a two-minute call: SRT's grammar
     * requires the field, and players that accept a shortened form are being lenient rather than
     * correct.
     */
    private fun cueTime(millis: Long, srt: Boolean): String {
        val totalSeconds = millis / MILLIS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_MINUTE / MINUTES_PER_HOUR
        val minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val fraction = millis % MILLIS_PER_SECOND
        // Locale.ROOT so a locale with its own digits cannot produce a file no player can parse.
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d%s%03d",
            hours, minutes, seconds, if (srt) "," else ".", fraction
        )
    }

    /** `&` first, or the escapes introduced after it would themselves be escaped. */
    private fun escapeForVtt(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * Everything, for anything that wants to process it.
     *
     * Segment `speaker` keeps the stored channel key **and** the resolved name: the key is what the
     * app reasons about and survives the user re-deciding who is who, while the name is what a person
     * reading the file expects. Emitting only one of them would make the file either unstable or
     * unreadable.
     *
     * Built with `org.json`, the same parser the summary is stored through, so a quotation mark or a
     * newline in ordinary speech is escaped by code that already handles it rather than by a second
     * hand-rolled attempt.
     */
    private fun renderJson(doc: ExportDocument): String {
        val segments = JSONArray()
        doc.segments.filter { it.text.isNotBlank() }.forEach { segment ->
            segments.put(
                JSONObject()
                    .put("startMs", segment.startMs)
                    .put("endMs", segment.endMs)
                    .put("text", segment.text.trim())
                    .put("speaker", segment.speaker ?: JSONObject.NULL)
                    .put("speakerName", doc.speakerNames?.of(segment.speaker) ?: JSONObject.NULL)
            )
        }

        val root = JSONObject()
            .put("title", doc.title)
            .put("tags", JSONArray(doc.tags.filter { it.isNotBlank() }))
            .put("language", doc.language ?: JSONObject.NULL)
            .put("model", doc.model ?: JSONObject.NULL)
            .put("segments", segments)

        doc.summary?.let { root.put("summary", JSONObject(it.toJson())) }
        doc.note?.takeIf { it.isNotBlank() }?.let { root.put("note", it.trim()) }

        return root.toString(2)
    }
}
