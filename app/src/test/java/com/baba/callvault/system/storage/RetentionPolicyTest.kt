/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that now decide permanent deletion for files the catalog never knew about. Both of them
 * failing open costs a user their recordings, so both are pinned here rather than left to the sweep.
 */
class RetentionPolicyTest {

    private val dayMs = 24L * 60L * 60L * 1000L
    private val now = 1_754_300_000_000L

    // ---- cutoffFor

    @Test
    fun `keep-forever has no cutoff, so nothing can expire against it`() {
        assertNull(RetentionPolicy.cutoffFor(days = 0, now = now))
        assertNull(RetentionPolicy.cutoffFor(days = -1, now = now))
    }

    @Test
    fun `a period of N days puts the cutoff N days back`() {
        assertEquals(now - 7 * dayMs, RetentionPolicy.cutoffFor(days = 7, now = now))
    }

    // ---- isExpired

    @Test
    fun `expires a copy older than the cutoff`() {
        val cutoff = RetentionPolicy.cutoffFor(7, now)
        assertTrue(RetentionPolicy.isExpired(now - 8 * dayMs, cutoff))
    }

    @Test
    fun `keeps a copy inside the period, and the boundary itself`() {
        val cutoff = RetentionPolicy.cutoffFor(7, now)
        assertFalse(RetentionPolicy.isExpired(now - 6 * dayMs, cutoff))
        assertFalse(RetentionPolicy.isExpired(now - 7 * dayMs, cutoff))
    }

    @Test
    fun `expires nothing when the period is keep-forever, however old`() {
        assertFalse(RetentionPolicy.isExpired(now - 500 * dayMs, RetentionPolicy.cutoffFor(0, now)))
    }

    @Test
    fun `never expires a copy whose age is unknown`() {
        // An undated file is not "very old" — it is one we know nothing about. Deleting on that basis
        // would turn a metadata gap into data loss.
        assertFalse(RetentionPolicy.isExpired(0L, RetentionPolicy.cutoffFor(7, now)))
        assertFalse(RetentionPolicy.isExpired(-1L, RetentionPolicy.cutoffFor(7, now)))
    }

    // ---- isEligible: the gate that stops us deleting somebody else's audio

    @Test
    fun `a name carrying CallVault's timestamp is ours to sweep`() {
        assertTrue(RetentionPolicy.isEligible("20260804_101010.123+0300_in_5551234.ogg", now))
        assertTrue(RetentionPolicy.isEligible("20260804_101010.123+0300_voip-WhatsApp_Dana.ogg", now))
    }

    @Test
    fun `a name the template could not parse is left alone`() {
        // e.g. music the user keeps in the same folder: old, audio, and none of our business.
        assertFalse(RetentionPolicy.isEligible("my favourite album.mp3", startedAtMillis = null))
    }

    @Test
    fun `an imported file is never deleted by age, timestamp or not`() {
        // It carries our timestamp — we wrote the name — so the template gate alone would hand it to
        // the sweep. A voice note someone imported to transcribe is not a call, and the retention
        // period they chose for their calls was never an instruction to destroy it.
        assertFalse(RetentionPolicy.isEligible("20260804_101010.123+0300_import.m4a", now))
        assertFalse(RetentionPolicy.isEligible("20260804_101010.123+0300_import_Standup.m4a", now))
    }

    @Test
    fun `a call with a contact called Important is still swept`() {
        // The import exemption reads the marker slot, not the whole name; a looser test would quietly
        // exempt this call from retention for ever.
        assertTrue(RetentionPolicy.isEligible("20260804_101010.123+0300_in_Important.ogg", now))
    }

    @Test
    fun `a catalogued import is exempt from age retention too`() {
        // The catalogued pass needs no filename test — CallVault put the row there — so it asks this
        // question instead. Stamping an import with the import time only postpones the problem: once
        // the retention period elapses the pass would delete it like a call, except that a call keeps
        // its Drive copy and an import has none.
        assertFalse(RetentionPolicy.agesOut("20260804_101010.123+0300_import_Standup.m4a"))
    }

    @Test
    fun `a catalogued call still ages out`() {
        assertTrue(RetentionPolicy.agesOut("20260804_101010.123+0300_in_0501234567.ogg"))
        assertTrue(RetentionPolicy.agesOut("20260804_101010.123+0300_voip-WhatsApp_Dana.ogg"))
    }
}
