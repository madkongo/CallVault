/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import androidx.core.net.toUri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A merge joins two recordings into one and deletes the parts it consumed, so every row offered here
 * is audio the user can lose. The case this pins is the imported file: it was never a call, it has no
 * second half, and splicing it onto a conversation would destroy it.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14 tops out at SDK 35 while the app targets 36, same as RecordingSelectionTest.
@Config(sdk = [35])
class MergeCandidatesTest {

    private fun call(name: String, number: String?, onDevice: Boolean = true) =
        RecordingItem(
            uri = "content://dev/$name".toUri(),
            displayName = name,
            sizeBytes = 1,
            lastModified = 0,
            direction = null,
            displayDate = null,
            startedAtMillis = null,
            number = number,
            source = if (onDevice) RecordingSource.LOCAL else RecordingSource.DRIVE,
            localUri = if (onDevice) "content://dev/$name".toUri() else null,
            driveUri = if (onDevice) null else "content://drv/$name".toUri(),
        )

    private val first = call("20260916_101010.123+0300_out_5551234.ogg", "5551234")
    private val redial = call("20260916_101500.123+0300_out_5551234.ogg", "5551234")
    private val other = call("20260916_102000.123+0300_in_5559999.ogg", "5559999")
    private val import = call("20260916_103000.123+0300_import_Standup.m4a", null)

    @Test
    fun `the redial of the same number is the candidate`() {
        assertEquals(
            listOf(redial.displayName),
            MergeCandidates.of(first, listOf(first, redial, other)).map { it.displayName }
        )
    }

    @Test
    fun `a copy only in Drive IS offered now — the merge downloads it before joining`() {
        // Was excluded while merge needed local audio; a DRIVE-storage user has no local copy of any
        // call, so that rule hid every candidate (issue #49 sibling). The merge now pulls it down first.
        val inDriveOnly = call("20260916_101500.123+0300_out_5551234.ogg", "5551234", onDevice = false)
        assertEquals(
            listOf(inDriveOnly.displayName),
            MergeCandidates.of(first, listOf(first, inDriveOnly)).map { it.displayName }
        )
    }

    @Test
    fun `the same party matches across national and international number formats`() {
        // Contact lookup missed on both, so the raw numbers differ by country-code/leading-zero; the
        // last-nine-digit key still recognises them as one person. Previously these would not merge.
        val intl = call("20260916_101010.123+0300_in_356791234567.ogg", "+356791234567")
        val natl = call("20260916_101500.123+0300_out_0791234567.ogg", "0791234567")
        assertEquals(
            listOf(natl.displayName),
            MergeCandidates.of(intl, listOf(intl, natl)).map { it.displayName }
        )
    }

    @Test
    fun `short codes must match in full, not on a shared tail`() {
        // Fewer than nine digits means an exact compare — two different short codes are two different
        // parties even if they share trailing digits, so they are never offered to merge together.
        val a = call("20260916_101010.123+0300_in_800.ogg", "800")
        val b = call("20260916_101500.123+0300_out_4800.ogg", "4800")
        assertTrue(MergeCandidates.of(a, listOf(a, b)).isEmpty())
    }

    @Test
    fun `an import is never offered as something to join a call to`() {
        // Even were it to carry a matching number somehow: it is the user's own audio, not the second
        // half of a dropped call, and the merge would consume and delete it.
        val importWithNumber = call("20260916_103000.123+0300_import_Standup.m4a", "5551234")
        assertTrue(MergeCandidates.of(first, listOf(first, importWithNumber)).isEmpty())
    }

    @Test
    fun `an import has nothing to merge into it either`() {
        assertFalse(MergeCandidates.canMerge(import))
        assertTrue(MergeCandidates.of(import, listOf(import, first, redial)).isEmpty())
    }

    @Test
    fun `an ordinary call can still merge`() {
        assertTrue(MergeCandidates.canMerge(first))
        // Including one whose contact is called Important — the exemption reads the marker slot only.
        assertTrue(MergeCandidates.canMerge(call("20260916_101010.123+0300_in_Important.ogg", null)))
    }

    // A name parsed from a file via RecordingsRepository.parseName, as the catalog row would carry it.
    private fun parsedCall(name: String): RecordingItem {
        val p = com.baba.callvault.data.recordings.RecordingsRepository.parseName(name)
        return call(name, p.number).copy(contactName = p.contactName)
    }

    @Test
    fun `default template calls from one number are candidates for each other`() {
        val a = parsedCall("20261121_111219.379+0200_in_0541234567.ogg")
        val b = parsedCall("20261121_120000.100+0200_out_0541234567.ogg")
        val c = parsedCall("20261121_130000.100+0200_out_0549999999.ogg")
        assertEquals(listOf(b.displayName), MergeCandidates.of(a, listOf(a, b, c)).map { it.displayName })
    }

    private fun named(contact: String, time: String = "111219.379") =
        parsedCall("20261121_$time+0200_in_$contact.ogg")

    private fun pairs(a: RecordingItem, b: RecordingItem) =
        MergeCandidates.of(a, listOf(a, b)).map { it.displayName } == listOf(b.displayName)

    @Test
    fun `real names written by the contact_name template pair with themselves in any script`() {
        listOf("פרוזה", "Dana Levi", "বাংলা নাম", "Mom 2", "Joe \uD83D\uDE00", "Zoë").forEach { who ->
            assertTrue(who, pairs(named(who), named(who, "120000.100")))
        }
    }

    @Test
    fun `names match regardless of case and Unicode composition`() {
        assertTrue(pairs(named("DANA levi"), named("dana Levi", "120000.100")))
        assertTrue(pairs(named("Zo\u00EB"), named("Zoe\u0308", "120000.100")))
    }

    @Test
    fun `different names are not candidates`() {
        assertFalse(pairs(named("פרוזה"), named("פרוזה 2", "120000.100")))
        assertFalse(pairs(named("Mom 2"), named("Mom 3", "120000.100")))
    }

    @Test
    fun `a name never matches a number made of its digits`() {
        assertFalse(pairs(named("Mom 2"), named("2", "120000.100")))
    }

    @Test
    fun `labels for unknown or hidden callers are never a shared key`() {
        listOf("Private", "unknown", "ANONYMOUS", "Hidden", "Restricted", "Withheld", "Voicemail").forEach { w ->
            assertFalse(w, pairs(named(w), named(w, "120000.100")))
        }
        // The label the phone's own language writes is passed in by the caller.
        val a = named("\u05DE\u05E1\u05E4\u05E8 \u05D7\u05E1\u05D5\u05DD")
        val b = named("\u05DE\u05E1\u05E4\u05E8 \u05D7\u05E1\u05D5\u05DD", "120000.100")
        assertTrue(MergeCandidates.of(a, listOf(a, b), setOf("\u05DE\u05E1\u05E4\u05E8 \u05D7\u05E1\u05D5\u05DD")).isEmpty())
    }

    @Test
    fun `placeholder callers Unknown 5 and Unknown 7 are not candidates`() {
        assertFalse(pairs(named("Unknown 5"), named("Unknown 7", "120000.100")))
        assertFalse(pairs(named("Unknown 5"), named("Unknown 5", "120000.100")))
    }

    @Test
    fun `the placeholder number -1 is not a key`() {
        assertFalse(pairs(parsedCall("20261121_111219.379+0200_in_-1.ogg"), parsedCall("20261121_120000.100+0200_in_-1.ogg")))
    }

    @Test
    fun `a resolved contactName of Unknown or Voicemail is not a key`() {
        listOf("Unknown", "Voicemail", "Private number").forEach { w ->
            val a = call("a.ogg", "5551234").copy(contactName = w)
            val b = call("b.ogg", "5559999").copy(contactName = w)
            assertFalse(w, pairs(a, b))
        }
    }

    @Test
    fun `Mom 2 still pairs with Mom 2`() {
        assertTrue(pairs(named("Mom 2"), named("Mom 2", "120000.100")))
    }

    @Test
    fun `a contact literally named Hidden Valley Pizza is missed on purpose because names starting with a stoplist word are dropped`() {
        assertFalse(pairs(named("Hidden Valley Pizza"), named("Hidden Valley Pizza", "120000.100")))
    }
}
