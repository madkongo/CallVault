/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.utils

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.baba.callvault.data.merge.MergeNaming
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingMetadata
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.utils.CustomFileNameTemplate.Problem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `{day}`, `{day-}`, `{time}` and `{time-}` (#82): written, checked, read back, merged and redacted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DayTimeFileNameTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 2026-11-12 14:35:20 in the test JVM's own time zone, which is the zone the formatter and the parser both use. */
    private val startedAt: Date = Calendar.getInstance().apply {
        clear(); set(2026, Calendar.NOVEMBER, 12, 14, 35, 20)
    }.time

    private fun format(template: String): String = RecordingFileNameFormatter.formatFileName(
        context,
        RecordingMetadata(rawPhoneNumber = "0541234567", direction = RecordingDirection.INCOMING),
        ScrcpyAudioCodec.OPUS,
        customFormat = template,
        startedAt = startedAt,
    )

    // ---- formatting

    @Test
    fun `the four tags write the day and the time on their own`() {
        assertEquals("20261112.ogg", format("{day}"))
        assertEquals("2026-11-12.ogg", format("{day-}"))
        assertEquals("143520.ogg", format("{time}"))
        assertEquals("14-35-20.ogg", format("{time-}"))
    }

    @Test
    fun `the reporter's example reads as asked`() {
        assertEquals("2026-11-12_14-35-20_in_0541234567.ogg", format("{day-}_{time-}_{direction}_{phone_number}"))
        assertEquals("20261112_143520_in.ogg", format("{day}_{time}_{direction}"))
    }

    @Test
    fun `date is exactly what it was, so nobody's existing names change`() {
        val expected = SimpleDateFormat("yyyyMMdd_HHmmss.SSSZ", Locale.CANADA).format(startedAt)
        assertEquals("${expected}_in_0541234567.ogg", format("{date}_{direction}_{phone_number}"))
        assertEquals("20261112_143520", format("{date}").substringBefore('.'))
    }

    @Test
    fun `the preview shows every new tag filled in`() {
        assertEquals("2026-06-11_14-30-52_in", fileNameTemplateExample("{day-}_{time-}_{direction}"))
        assertEquals("20260611-143052", fileNameTemplateExample("{day}-{time}"))
        assertFalse(fileNameTemplateExample("{day}{time}{day-}{time-}").contains('{'))
    }

    // ---- validation

    @Test
    fun `a day followed by a time satisfies the date requirement`() {
        listOf(
            "{day}_{time}", "{day-}_{time-}", "{day} {time}", "{day-}-{time-}", "{day}T{time}", "{day-}_{time}",
            "{contact_name}_{day-}_{time-}", "{day}_{time}_{direction}_{phone_number}",
        ).forEach { assertNull(it, CustomFileNameTemplate.problemOf(it)) }
    }

    @Test
    fun `more than one joiner between the day and the time is refused, because the pair could not be found again`() {
        assertEquals(Problem.DAY_AND_TIME, CustomFileNameTemplate.problemOf("{day} - {time}"))
        assertEquals(Problem.DAY_AND_TIME, CustomFileNameTemplate.problemOf("{day}__{time}"))
    }

    @Test
    fun `a day or a time alone is refused with its own message`() {
        listOf("{day}", "{day-}_{contact_name}", "{time}", "{time-}_{contact_name}").forEach {
            assertEquals(it, Problem.DAY_AND_TIME, CustomFileNameTemplate.problemOf(it))
        }
    }

    @Test
    fun `the time before the day, or something between them, is refused`() {
        assertEquals(Problem.DAY_AND_TIME, CustomFileNameTemplate.problemOf("{time}_{day}"))
        assertEquals(Problem.DAY_AND_TIME, CustomFileNameTemplate.problemOf("{day}_{contact_name}_{time}"))
        assertEquals(Problem.DAY_AND_TIME, CustomFileNameTemplate.problemOf("{day}{time}"))
    }

    @Test
    fun `no date at all is still refused, and date still passes alone`() {
        assertEquals(Problem.NEEDS_DATE, CustomFileNameTemplate.problemOf("{contact_name}_{phone_number}"))
        assertNull(CustomFileNameTemplate.problemOf("{date}"))
        assertNull(CustomFileNameTemplate.problemOf("{date}_{day-}"))
    }

    @Test
    fun `the new tags are known, so they are not reported as unknown`() {
        assertNull(CustomFileNameTemplate.firstUnknownTag("{day}_{day-}_{time}_{time-}"))
        assertEquals("{hhmm}", CustomFileNameTemplate.firstUnknownTag("{day}_{hhmm}"))
    }

    // ---- reading names back (list, rescan, call-log match)

    private fun millis(pattern: String, text: String): Long =
        SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(text)!!.time

    @Test
    fun `every shape of the pair reads back to the same start and a date the list can show`() {
        val expected = millis("yyyyMMddHHmmss", "20261112143520")
        listOf(
            "20261112_143520", "2026-11-12_14-35-20", "20261112 143520", "2026-11-12 14-35-20",
            "20261112-143520", "2026-11-12-14-35-20", "20261112T143520", "2026-11-12_143520",
        ).forEach { stamp ->
            val parsed = RecordingsRepository.parseName("${stamp}_in_0541234567.ogg")
            assertEquals(stamp, expected, parsed.startedAtMillis)
            assertEquals(stamp, "2026-11-12 14:35", parsed.displayDate)
            assertEquals(stamp, RecordingDirection.INCOMING, parsed.direction)
            assertEquals(stamp, "0541234567", parsed.number)
        }
    }

    @Test
    fun `a name with no direction still gives its date, number and contact`() {
        val parsed = RecordingsRepository.parseName("Dana Levi_0541234567_2026-11-12_14-35-20.ogg")
        assertEquals(millis("yyyyMMddHHmmss", "20261112143520"), parsed.startedAtMillis)
        assertEquals("0541234567", parsed.number)
        assertEquals("Dana Levi", parsed.contactName)
        assertNull(parsed.direction)
    }

    @Test
    fun `a contact written before the date does not spoil the date when a direction follows`() {
        val parsed = RecordingsRepository.parseName("Dana Levi_20261112_143520_out_0541234567.ogg")
        assertEquals(millis("yyyyMMddHHmmss", "20261112143520"), parsed.startedAtMillis)
        assertEquals(RecordingDirection.OUTGOING, parsed.direction)
        assertEquals("0541234567", parsed.number)
    }

    @Test
    fun `a short number beside the stamp does not donate digits to it`() {
        val parsed = RecordingsRepository.parseName("087011_2026-11-12_14-35-20.ogg")
        assertEquals("087011", parsed.number)
        assertEquals(millis("yyyyMMddHHmmss", "20261112143520"), parsed.startedAtMillis)
    }

    @Test
    fun `names the formatter writes read back through the real formatter`() {
        listOf("{day-}_{time-}_{direction}_{phone_number}", "{contact_name}_{day}_{time}", "{phone_number} {day-} {time-}").forEach { template ->
            val parsed = RecordingsRepository.parseName(format(template))
            assertEquals(template, startedAt.time, parsed.startedAtMillis)
            assertEquals(template, "2026-11-12 14:35", parsed.displayDate)
        }
    }

    @Test
    fun `date names read back exactly as before`() {
        val parsed = RecordingsRepository.parseName("20261009_143052.123+0300_in_0541234567.ogg")
        assertEquals(1_791_545_452_123L, parsed.startedAtMillis)
        assertEquals("2026-10-09 14:30", parsed.displayDate)
    }

    @Test
    fun `app calls keep their own grammar, whatever the template`() {
        val parsed = RecordingsRepository.parseName("20261009_143052.123+0300_voip-WhatsApp_Dana.ogg")
        assertEquals("WhatsApp", parsed.voipApp)
        assertEquals("Dana", parsed.contactName)
    }

    // ---- merging

    @Test
    fun `merge nudges the second of a day-time stamp and keeps its shape`() {
        assertEquals("2026-11-12_14-35-21_in_0541234567", MergeNaming.bumpedStem("2026-11-12_14-35-20_in_0541234567", 1))
        assertEquals("Dana_20261112_143522", MergeNaming.bumpedStem("Dana_20261112_143520", 2))
        assertEquals("2026-11-13 00-00-05", MergeNaming.bumpedStem("2026-11-12 23-59-55", 10))
    }

    @Test
    fun `a merged name is different from the original and still reads as the same call to the minute`() {
        val original = "2026-11-12_14-35-20_in_0541234567"
        val bumped = MergeNaming.bumpedStem(original, 1)
        assertNotEquals(original, bumped)
        val parsed = RecordingsRepository.parseName("$bumped.ogg")
        assertEquals("2026-11-12 14:35", parsed.displayDate)
        assertNotNull(parsed.startedAtMillis)
    }

    // ---- logs must not print a contact

    @Test
    fun `log redaction hides the contact in a day-time name`() {
        listOf(
            "Saved 2026-11-12_14-35-20_in_Dana Levi.ogg ok",
            "Saved 20261112_143520_Dana Levi.ogg ok",
            "Saved 2026-11-12 14-35-20 Dana Levi.ogg ok",
        ).forEach { line ->
            val out = LogRedaction.redact(line, "salt")
            assertFalse(out, out.contains("Dana"))
            assertFalse(out, out.contains("Levi"))
            assertTrue(out, out.contains(".ogg"))
            assertEquals("not idempotent: $out", out, LogRedaction.redact(out, "salt"))
        }
    }

    @Test
    fun `text on both sides of a day-time pair counts as names around the date`() {
        assertTrue(LogRedaction.namesAroundDate("{contact_name} {day-} {time-} {direction}"))
        assertFalse(LogRedaction.namesAroundDate("{day-}_{time-}_{contact_name}"))
        assertTrue(LogRedaction.namesAroundDate("{contact_name} {date} {direction}"))
    }
}
