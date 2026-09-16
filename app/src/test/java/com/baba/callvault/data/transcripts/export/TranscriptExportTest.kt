/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts.export

import com.baba.callvault.data.ChannelMap
import com.baba.callvault.data.SpeakerNames
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.server.speakers.SpeakerChannel
import com.baba.callvault.summary.CallSummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a transcript must still say after it leaves the app.
 *
 * An export is read somewhere CallVault cannot see — a subtitle editor, a text file, another
 * program's parser — so the failures worth guarding are the ones that produce a file which *looks*
 * fine and is rejected or misread elsewhere: a cue with no duration, an unescaped `<`, a timestamp
 * missing its hour field, a lost speaker label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptExportTest {
    /**
     * The English headings, spelled out here rather than defaulted in production code.
     *
     * [TranscriptExport.render] deliberately takes [ExportLabels] with no default: a default would
     * be English, and a caller that forgot to pass it would silently reintroduce the bug where a
     * French reader exported "Key points" from a screen showing "Points clés".
     */
    private val english = ExportLabels(
        summary = "Summary",
        notes = "Notes",
        transcript = "Transcript",
        keyPoints = "Key points",
        decisions = "Decisions",
        actionItems = "Action items",
        keyFacts = "Key facts",
        language = "Language",
        model = "Model",
    )


    private val names = SpeakerNames(
        map = ChannelMap.A_IS_FAR,
        you = "You",
        contact = "Dana",
        sideA = "A",
        sideB = "B"
    )

    private fun segment(start: Long, end: Long, text: String, speaker: String? = null) =
        TranscriptSegmentEntry(
            displayName = "call.ogg",
            startMs = start,
            endMs = end,
            text = text,
            speaker = speaker
        )

    private val doc = ExportDocument(
        title = "Dana",
        segments = listOf(
            segment(0, 2_500, "Hello there", SpeakerChannel.A.key),
            segment(2_500, 5_000, "Hi, how are you", SpeakerChannel.B.key)
        ),
        speakerNames = names,
        summary = null,
        note = null,
        tags = emptyList(),
        language = "he",
        model = "large-v3-turbo-q8_0"
    )

    // ---- naming ----

    @Test
    fun `the recording's own extension is replaced, not appended`() {
        // `call.ogg.srt` would be typed by its last extension by some systems and its first by
        // others; replacing removes the question.
        assertEquals("call.srt", TranscriptExport.fileName(TranscriptFormat.SRT, "call.ogg"))
    }

    @Test
    fun `a name with no extension still gets one`() {
        assertEquals("call.json", TranscriptExport.fileName(TranscriptFormat.JSON, "call"))
    }

    @Test
    fun `a name containing dots keeps all but the last segment`() {
        assertEquals(
            "2026-08-29_10.15.txt",
            TranscriptExport.fileName(TranscriptFormat.TXT, "2026-08-29_10.15.ogg")
        )
    }

    // ---- subtitles ----

    @Test
    fun `SRT timestamps carry an hours field and a comma`() {
        val srt = TranscriptExport.render(TranscriptFormat.SRT, doc, english)

        assertTrue(srt, "00:00:00,000 --> 00:00:02,500" in srt)
    }

    @Test
    fun `VTT timestamps use a full stop and the file declares itself`() {
        val vtt = TranscriptExport.render(TranscriptFormat.VTT, doc, english)

        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt, "00:00:00.000 --> 00:00:02.500" in vtt)
    }

    @Test
    fun `an hour-long call keeps its hours`() {
        val long = doc.copy(segments = listOf(segment(3_661_500, 3_663_000, "late in the call")))

        val srt = TranscriptExport.render(TranscriptFormat.SRT, long, english)

        assertTrue(srt, "01:01:01,500 --> 01:01:03,000" in srt)
    }

    @Test
    fun `a zero-length segment still produces a cue that can be displayed`() {
        // Whisper does emit these. A cue whose end is not after its start either never shows or is
        // rejected outright, and some players stop reading the file at the first bad cue — which
        // would silently truncate the export rather than blemish it.
        val degenerate = doc.copy(segments = listOf(segment(1_000, 1_000, "instant")))

        val srt = TranscriptExport.render(TranscriptFormat.SRT, degenerate, english)

        assertTrue(srt, "00:00:01,000 --> 00:00:01,100" in srt)
    }

    @Test
    fun `cues are numbered from one and in order`() {
        val srt = TranscriptExport.render(TranscriptFormat.SRT, doc, english)
        val numbers = srt.lines().filter { it.toIntOrNull() != null }

        assertEquals(listOf("1", "2"), numbers)
    }

    @Test
    fun `VTT escapes the characters it would otherwise read as markup`() {
        val risky = doc.copy(segments = listOf(segment(0, 1_000, "5 < 6 & 7 > 6")))

        val vtt = TranscriptExport.render(TranscriptFormat.VTT, risky, english)

        assertTrue(vtt, "5 &lt; 6 &amp; 7 &gt; 6" in vtt)
    }

    @Test
    fun `SRT does not escape, because there it would be shown literally`() {
        val risky = doc.copy(segments = listOf(segment(0, 1_000, "5 < 6")))

        assertTrue("5 < 6" in TranscriptExport.render(TranscriptFormat.SRT, risky, english))
    }

    @Test
    fun `a blank line inside a segment cannot split its cue`() {
        // A blank line ends a cue in both formats, so one inside the text would truncate the
        // subtitle and leave the remainder as a malformed cue of its own.
        val awkward = doc.copy(segments = listOf(segment(0, 1_000, "first\n\nsecond")))

        val srt = TranscriptExport.render(TranscriptFormat.SRT, awkward, english)

        assertFalse(srt, "first\n\nsecond" in srt)
        assertTrue(srt, "first\nsecond" in srt)
    }

    @Test
    fun `an empty segment produces no cue at all`() {
        val withBlank = doc.copy(
            segments = listOf(segment(0, 1_000, "said"), segment(1_000, 2_000, "   "))
        )

        val srt = TranscriptExport.render(TranscriptFormat.SRT, withBlank, english)

        assertEquals(listOf("1"), srt.lines().filter { it.toIntOrNull() != null })
    }

    @Test
    fun `speaker labels survive into the subtitle text`() {
        val srt = TranscriptExport.render(TranscriptFormat.SRT, doc, english)

        assertTrue(srt, "Dana: Hello there" in srt)
        assertTrue(srt, "You: Hi, how are you" in srt)
    }

    // ---- text and markdown ----

    @Test
    fun `plain text matches what the transcript shows on screen`() {
        val txt = TranscriptExport.render(TranscriptFormat.TXT, doc, english)

        assertEquals("[0:00] Dana: Hello there\n[0:02] You: Hi, how are you", txt)
    }

    @Test
    fun `markdown leads with the summary, because that is what a reader wants`() {
        val summarised = doc.copy(
            summary = CallSummary(
                intent = "Chasing an invoice",
                summary = "She agreed to send it Tuesday.",
                keyPoints = listOf("Three weeks late"),
                decisions = listOf("Pay by transfer"),
                actionItems = emptyList(),
                keyFacts = emptyList()
            )
        )

        val md = TranscriptExport.render(TranscriptFormat.MARKDOWN, summarised, english)

        assertTrue(md, md.indexOf("## Summary") < md.indexOf("## Transcript"))
        assertTrue(md, "Chasing an invoice" in md)
        assertTrue(md, "- Three weeks late" in md)
        // An empty list must not leave a heading with nothing under it.
        assertFalse(md, "### Action items" in md)
    }

    @Test
    fun `markdown omits every section it has nothing for`() {
        val md = TranscriptExport.render(TranscriptFormat.MARKDOWN, doc, english)

        assertFalse(md, "## Summary" in md)
        assertFalse(md, "## Notes" in md)
        assertTrue(md, "## Transcript" in md)
    }

    @Test
    fun `the note reaches markdown when there is one`() {
        val md = TranscriptExport.render(
            TranscriptFormat.MARKDOWN,
            doc.copy(note = "chase this on Monday"), english)

        assertTrue(md, "## Notes" in md)
        assertTrue(md, "chase this on Monday" in md)
    }

    @Test
    fun `markdown headings follow the reader's language, not the code`() {
        // Arrange — what a French reader has on screen: "Points clés", "Décisions", "À faire".
        val french = ExportLabels(
            summary = "Résumé",
            notes = "Note",
            transcript = "Transcription",
            keyPoints = "Points clés",
            decisions = "Décisions",
            actionItems = "À faire",
            keyFacts = "À retenir",
            language = "Langue",
            model = "Modèle",
        )

        val summarised = doc.copy(
            summary = CallSummary(
                intent = "Chasing an invoice",
                summary = "She agreed to send it Tuesday.",
                keyPoints = listOf("Three weeks late"),
                decisions = listOf("Pay by transfer"),
                actionItems = emptyList(),
                keyFacts = emptyList()
            )
        )

        // Act
        val md = TranscriptExport.render(TranscriptFormat.MARKDOWN, summarised, french)

        // Assert — the headings are the reader's, and no English one survives.
        //
        // Compared whole-line, not by `in`: "## Transcript" is a substring of "## Transcription",
        // so a contains-check would fail against a correctly translated file.
        val headings = md.lines().filter { it.startsWith("#") }
        assertTrue(headings.toString(), "## Résumé" in headings)
        assertTrue(headings.toString(), "## Transcription" in headings)
        assertTrue(headings.toString(), "### Points clés" in headings)
        assertTrue(headings.toString(), "### Décisions" in headings)
        val english = listOf("## Summary", "## Transcript", "## Notes", "### Key points", "### Decisions")
        assertTrue(headings.toString(), headings.none { it in english })
    }

    // ---- json ----

    @Test
    fun `tags reach markdown and json, and no subtitle file`() {
        // They belong to the reader, not to the audio: a cue file has nowhere to put them and a
        // player would render them as a spoken line.
        val tagged = doc.copy(tags = listOf("the flat", "insurance"))

        assertTrue("`the flat`" in TranscriptExport.render(TranscriptFormat.MARKDOWN, tagged, english))
        assertTrue("insurance" in TranscriptExport.render(TranscriptFormat.JSON, tagged, english))
        assertFalse("the flat" in TranscriptExport.render(TranscriptFormat.SRT, tagged, english))
        assertFalse("the flat" in TranscriptExport.render(TranscriptFormat.VTT, tagged, english))
    }

    @Test
    fun `no tags leaves no empty line under the title`() {
        val md = TranscriptExport.render(TranscriptFormat.MARKDOWN, doc, english)

        assertFalse(md, "``" in md)
    }

    @Test
    fun `json carries the channel key and the resolved name for each segment`() {
        // The key is what the app reasons about and survives the user re-deciding who is who; the
        // name is what a person reading the file expects. One without the other is either unstable
        // or unreadable.
        val root = JSONObject(TranscriptExport.render(TranscriptFormat.JSON, doc, english))
        val first = root.getJSONArray("segments").getJSONObject(0)

        assertEquals(SpeakerChannel.A.key, first.getString("speaker"))
        assertEquals("Dana", first.getString("speakerName"))
        assertEquals(0, first.getLong("startMs"))
        assertEquals(2_500, first.getLong("endMs"))
    }

    @Test
    fun `json survives speech containing quotes and newlines`() {
        val awkward = doc.copy(
            segments = listOf(segment(0, 1_000, "he said \"no\",\nthen left"))
        )

        val root = JSONObject(TranscriptExport.render(TranscriptFormat.JSON, awkward, english))

        assertEquals(
            "he said \"no\",\nthen left",
            root.getJSONArray("segments").getJSONObject(0).getString("text")
        )
    }

    @Test
    fun `json records an unattributed segment as null rather than omitting it`() {
        val anonymous = doc.copy(segments = listOf(segment(0, 1_000, "someone")), speakerNames = null)

        val root = JSONObject(TranscriptExport.render(TranscriptFormat.JSON, anonymous, english))
        val first = root.getJSONArray("segments").getJSONObject(0)

        assertTrue(first.isNull("speaker"))
        assertTrue(first.isNull("speakerName"))
    }

    @Test
    fun `every format tolerates a transcript with nothing in it`() {
        // Reachable: a call that transcribed to silence. None of these may throw, and the subtitle
        // formats must still be structurally valid files.
        val empty = doc.copy(segments = emptyList())

        TranscriptFormat.entries.forEach { format ->
            val rendered = TranscriptExport.render(format, empty, english)
            assertFalse(format.name, rendered.contains("-->"))
        }
        assertTrue(TranscriptExport.render(TranscriptFormat.VTT, empty, english).startsWith("WEBVTT"))
    }

    @Test
    fun `a summary shared as a message carries no heading marks`() {
        // The share path. A summary sent into a chat used to be impossible; now it is the same
        // renderer the Markdown export uses, with the marks left out — because hash marks in a chat
        // message are visible punctuation that says nothing about the call.
        val plain = TranscriptExport.renderSummary(
            summary = CallSummary(
                intent = "Chasing an invoice",
                summary = "She agreed to send it Tuesday.",
                keyPoints = listOf("Three weeks late"),
                decisions = emptyList(),
                actionItems = emptyList(),
                keyFacts = emptyList()
            ),
            labels = english,
            titleMark = "",
            sectionMark = "",
        )

        assertFalse(plain, plain.contains("#"))
        assertTrue(plain, plain.startsWith("Summary"))
        assertTrue(plain, plain.contains("Chasing an invoice"))
        assertTrue(plain, plain.contains("Key points"))
        assertTrue(plain, plain.contains("- Three weeks late"))
    }

    @Test
    fun `an empty section of a summary is left out rather than headed`() {
        // Same rule as the Markdown export it was extracted from, and worth pinning on the share
        // path too: a heading with nothing under it reads as content that went missing.
        val plain = TranscriptExport.renderSummary(
            summary = CallSummary(
                intent = "A short call",
                summary = "Nothing was decided.",
                keyPoints = emptyList(),
                decisions = emptyList(),
                actionItems = emptyList(),
                keyFacts = emptyList()
            ),
            labels = english,
            titleMark = "",
            sectionMark = "",
        )

        assertFalse(plain, plain.contains("Key points"))
        assertFalse(plain, plain.contains("Decisions"))
    }
}
