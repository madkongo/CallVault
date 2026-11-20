/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import com.baba.callvault.system.storage.FolderReading.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review C98 / N5: Home said "Ready" for a deleted recording folder, and the user lost three calls. The folder is
 * now measured, but a false "folder gone" would send a user to re-pick a folder that works, so a reading has to be
 * definite before it turns Home red or raises the alert.
 */
class FolderCheckPolicyTest {

    private val uri = "content://com.android.externalstorage.documents/tree/primary%3ARecordings"
    private fun reading(kind: Kind, u: String? = uri) = FolderReading(u, kind, "test")

    @Test
    fun `a usable folder is never unusable and asks for nothing`() {
        val step = FolderCheckPolicy.next(null, reading(Kind.OK), now = 1_000L)
        assertFalse(step.state.confirmedUnusable)
        assertFalse(step.alert)
        assertNull(step.recheckInMs)
    }

    @Test
    fun `a revoked grant is definite at once`() {
        val step = FolderCheckPolicy.next(null, reading(Kind.GRANT_MISSING), now = 1_000L)
        assertTrue(step.state.confirmedUnusable)
        assertTrue(step.alert)
    }

    @Test
    fun `a missing folder needs a second reading before it counts`() {
        val first = FolderCheckPolicy.next(null, reading(Kind.MISSING), now = 1_000L)
        assertFalse(first.state.confirmedUnusable)
        assertFalse(first.alert)
        assertEquals(FolderCheckPolicy.CONFIRM_DELAY_MS, first.recheckInMs)

        val second = FolderCheckPolicy.next(first.state, reading(Kind.MISSING), now = 1_000L + FolderCheckPolicy.CONFIRM_DELAY_MS)
        assertTrue(second.state.confirmedUnusable)
        assertTrue(second.alert)
    }

    @Test
    fun `two missing readings too close together are still one reading`() {
        val first = FolderCheckPolicy.next(null, reading(Kind.MISSING), now = 1_000L)
        val second = FolderCheckPolicy.next(first.state, reading(Kind.MISSING), now = 1_500L)
        assertFalse(second.state.confirmedUnusable)
        assertNotNull(second.recheckInMs)
    }

    @Test
    fun `a missing folder that comes back is never alerted`() {
        val first = FolderCheckPolicy.next(null, reading(Kind.MISSING), now = 1_000L)
        val back = FolderCheckPolicy.next(first.state, reading(Kind.OK), now = 30_000L)
        assertFalse(back.state.confirmedUnusable)
        assertFalse(back.alert)
    }

    @Test
    fun `a transient state (SD card spinning up, storage locked, slow provider) waits out its grace`() {
        val t0 = 1_000L
        val first = FolderCheckPolicy.next(null, reading(Kind.TRANSIENT), now = t0)
        assertFalse(first.state.confirmedUnusable)
        assertEquals(FolderCheckPolicy.TRANSIENT_GRACE_MS, first.recheckInMs)

        val early = FolderCheckPolicy.next(first.state, reading(Kind.TRANSIENT), now = t0 + FolderCheckPolicy.TRANSIENT_GRACE_MS - 1)
        assertFalse(early.state.confirmedUnusable)
        assertFalse(early.alert)

        val late = FolderCheckPolicy.next(early.state, reading(Kind.TRANSIENT), now = t0 + FolderCheckPolicy.TRANSIENT_GRACE_MS)
        assertTrue(late.state.confirmedUnusable)
        assertTrue(late.alert)
    }

    @Test
    fun `a transient reading after a missing one keeps the earliest bad time`() {
        val first = FolderCheckPolicy.next(null, reading(Kind.MISSING), now = 1_000L)
        val then = FolderCheckPolicy.next(first.state, reading(Kind.TRANSIENT), now = 1_000L + FolderCheckPolicy.TRANSIENT_GRACE_MS)
        assertTrue(then.state.confirmedUnusable)
    }

    @Test
    fun `once per loss - a confirmed loss alerts once, and again only after the folder was usable`() {
        val a = FolderCheckPolicy.next(null, reading(Kind.GRANT_MISSING), now = 1_000L)
        assertTrue(a.alert)
        val b = FolderCheckPolicy.next(a.state, reading(Kind.GRANT_MISSING), now = 60_000L)
        assertFalse(b.alert)
        assertTrue(b.state.confirmedUnusable)
        val fixed = FolderCheckPolicy.next(b.state, reading(Kind.OK), now = 120_000L)
        assertTrue(fixed.cleared)
        val lostAgain = FolderCheckPolicy.next(fixed.state, reading(Kind.GRANT_MISSING), now = 180_000L)
        assertTrue(lostAgain.alert)
    }

    @Test
    fun `a confirmed loss stays confirmed through a transient reading`() {
        val a = FolderCheckPolicy.next(null, reading(Kind.GRANT_MISSING), now = 1_000L)
        val b = FolderCheckPolicy.next(a.state, reading(Kind.TRANSIENT), now = 1_000L)
        assertTrue(b.state.confirmedUnusable)
        assertFalse(b.alert)
    }

    @Test
    fun `a different folder starts from scratch`() {
        val a = FolderCheckPolicy.next(null, reading(Kind.GRANT_MISSING), now = 1_000L)
        val other = FolderCheckPolicy.next(a.state, reading(Kind.MISSING, u = "content://x/tree/other"), now = 60_000L)
        assertFalse(other.state.confirmedUnusable)
        assertFalse(other.alert)
    }

    @Test
    fun `no folder set is the onboarding's case, not this alarm`() {
        val step = FolderCheckPolicy.next(null, reading(Kind.NOT_SET, u = null), now = 1_000L)
        assertFalse(step.state.confirmedUnusable)
        assertFalse(step.alert)
    }

    @Test
    fun `isUnusable trusts only a confirmed, recent measurement of the folder that is set now`() {
        val confirmed = FolderCheckPolicy.next(null, reading(Kind.GRANT_MISSING), now = 1_000L).state
        assertTrue(FolderCheckPolicy.isUnusable(confirmed, uri, now = 1_000L))
        assertFalse(FolderCheckPolicy.isUnusable(confirmed, "content://x/tree/other", now = 1_000L))
        assertFalse(FolderCheckPolicy.isUnusable(confirmed, uri, now = 1_000L + FolderCheckPolicy.MAX_AGE_MS + 1))
        assertFalse(FolderCheckPolicy.isUnusable(null, uri, now = 1_000L))
        assertFalse(FolderCheckPolicy.isUnusable(confirmed, null, now = 1_000L))
    }

    @Test
    fun `the state survives being stored and read back`() {
        val state = FolderCheckPolicy.next(null, reading(Kind.MISSING), now = 5_000L).state
        assertEquals(state, FolderCheckState.decode(state.encode()))
        assertNull(FolderCheckState.decode("garbage"))
    }
}
