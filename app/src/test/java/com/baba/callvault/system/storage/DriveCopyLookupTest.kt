/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C28: the after-call copy resolves its destination from ONE listing of the Drive folder, the way the
 * sweep does, instead of two findFile searches (one provider query per entry each).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveCopyLookupTest {

    private fun doc(id: String) = "content://com.google.android.apps.docs.storage/document/$id".toUri()

    private val listing = DriveCopyProtocol.Listing(
        names = listOf("other.ogg", "call_1.ogg", "call_1.cvpart.ogg", "settling.ogg", "Call.m4a", "call.m4a", "Only.ogg", "only.cvpart.ogg"),
        uris = listOf(doc("o"), doc("c"), doc("s"), doc("z"), doc("upper"), doc("lower"), doc("only"), doc("onlyPart")),
        sizes = listOf(5L, 4096L, 100L, 0L, 10L, 4096L, 10L, 3L),
    )

    @Test
    fun `the recording and its staging leftover are found in the listing`() {
        val known = DriveCopyHandler.knownDestFrom(listing, "call_1.ogg")

        assertEquals(doc("c"), known.existingUri)
        assertEquals(4096L, known.existingSize)
        assertEquals(doc("s"), known.stagingUri)
    }

    @Test
    fun `with names differing only in case, each recording gets its own exact-case document`() {
        val lower = DriveCopyHandler.knownDestFrom(listing, "call.m4a")
        val upper = DriveCopyHandler.knownDestFrom(listing, "Call.m4a")

        assertEquals(doc("lower"), lower.existingUri)
        assertEquals(4096L, lower.existingSize)
        assertEquals(doc("upper"), upper.existingUri)
        assertEquals(10L, upper.existingSize)
    }

    @Test
    fun `a case-only match is unknown, logged, and can never lead to the truncated-copy delete`() {
        val logged = ArrayList<String>()

        val known = DriveCopyHandler.knownDestFrom(listing, "only.ogg") { logged += it }

        assertNull(known.existingUri) // not "Only.ogg", 10 bytes, which the copy would have judged PARTIAL and deleted
        assertEquals(-1L, known.existingSize)
        assertEquals(ExistingCopyVerdict.COMPLETE, CloudCopyPolicy.verdict(known.existingSize, 4096L))
        assertEquals(doc("onlyPart"), known.stagingUri) // "only.cvpart.ogg" is its exact staging name
        assertEquals(1, logged.size)
        assertTrue(logged.single(), logged.single().contains("Only.ogg"))
    }

    @Test
    fun `a recording not in the listing has no existing document and no staging leftover`() {
        val known = DriveCopyHandler.knownDestFrom(listing, "new.ogg")

        assertNull(known.existingUri)
        assertEquals(-1L, known.existingSize)
        assertNull(known.stagingUri)
    }

    @Test
    fun `a document Drive lists at 0 bytes is still settling and is never treated as truncated`() {
        // Device quirk Q47: Drive reports 0 bytes while an upload settles; deleting on it lost recordings.
        val known = DriveCopyHandler.knownDestFrom(listing, "settling.ogg")

        assertEquals(0L, known.existingSize)
        assertEquals(ExistingCopyVerdict.COMPLETE, CloudCopyPolicy.verdict(known.existingSize, 4096L))
    }
}
