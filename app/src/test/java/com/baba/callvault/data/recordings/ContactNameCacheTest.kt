/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The process-level contact-name cache and the failed-probe marks behind Home's list pass (R46, C149). */
class ContactNameCacheTest {

    @Test
    fun `a number is looked up once while contacts are unchanged`() {
        val cache = ContactNameCache()
        var lookups = 0
        repeat(5) { cache.resolve("+972500000000", 1L) { lookups++; "Dana" } }
        assertEquals(1, lookups)
        assertEquals("Dana", cache.resolve("+972500000000", 1L) { error("cached") })
    }

    @Test
    fun `no contact is remembered as an empty name, not looked up again`() {
        val cache = ContactNameCache()
        var lookups = 0
        repeat(3) { assertEquals("", cache.resolve("123", 1L) { lookups++; null }) }
        assertEquals(1, lookups)
    }

    @Test
    fun `a contacts change drops every cached answer`() {
        val cache = ContactNameCache()
        assertEquals("", cache.resolve("+972500000000", 1L) { null })
        assertEquals("Dana", cache.resolve("+972500000000", 2L) { "Dana" })
    }

    @Test
    fun `a lookup that throws is not cached`() {
        val cache = ContactNameCache()
        runCatching { cache.resolve("+972500000000", 1L) { error("provider said no") } }
        assertEquals("Dana", cache.resolve("+972500000000", 1L) { "Dana" })
    }

    @Test
    fun `the cache is bounded`() {
        val cache = ContactNameCache(maxEntries = 3)
        for (i in 1..10) cache.resolve("n$i", 1L) { "name$i" }
        assertTrue(cache.size() <= 3)
    }

    @Test
    fun `a failed duration read is not retried until the file changes or the wait passes`() {
        val marks = FailedProbeMarks(retryAfterMs = 1_000)
        assertFalse(marks.shouldSkip("a.m4a", 0L, 10L, nowMs = 0))
        assertTrue(marks.markFailed("a.m4a", 0L, 10L, nowMs = 0))
        assertTrue(marks.shouldSkip("a.m4a", 0L, 10L, nowMs = 500))
        // size changed: the file is not the one that failed
        assertFalse(marks.shouldSkip("a.m4a", 4096L, 10L, nowMs = 500))
        // modified time changed
        assertFalse(marks.shouldSkip("a.m4a", 0L, 11L, nowMs = 500))
        // waited long enough: one more try
        assertFalse(marks.shouldSkip("a.m4a", 0L, 10L, nowMs = 1_000))
    }

    @Test
    fun `marking the same failure twice reports it as new only once`() {
        val marks = FailedProbeMarks(retryAfterMs = 1_000)
        assertTrue(marks.markFailed("a.m4a", 0L, 10L, nowMs = 0))
        assertFalse(marks.markFailed("a.m4a", 0L, 10L, nowMs = 10))
    }

    @Test
    fun `clearing a mark lets the file be probed again`() {
        val marks = FailedProbeMarks(retryAfterMs = 1_000)
        marks.markFailed("a.m4a", 0L, 10L, nowMs = 0)
        marks.clear("a.m4a")
        assertFalse(marks.shouldSkip("a.m4a", 0L, 10L, nowMs = 1))
    }
}
