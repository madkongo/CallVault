/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AfterCallDecisionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "20261106_101500_in_0501234567.ogg"
    private val uri = "content://test/tree/doc/$name".toUri()

    @Before
    fun clear() {
        AfterCallDecisions.pendingNames(context).forEach { AfterCallDecisions.release(context, it) }
    }

    @Test
    fun `a held recording can be read back whole`() {
        val stored = AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = true, askedAt = 1234L)

        val held = AfterCallDecisions.peek(context, name)

        assertNotNull(stored)
        assertEquals(AfterCallDecisions.Held(name, uri, "audio/ogg", 1234L, true, stored!!.notificationId), held)
        assertEquals(setOf(name), AfterCallDecisions.pendingNames(context))
    }

    @Test
    fun `release returns the entry once and then it is gone`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        val first = AfterCallDecisions.release(context, name)
        val second = AfterCallDecisions.release(context, name)

        assertEquals(name, first?.displayName)
        assertNull("a second answer must find nothing to act on", second)
        assertNull(AfterCallDecisions.peek(context, name))
        assertTrue(AfterCallDecisions.pendingNames(context).isEmpty())
    }

    @Test
    fun `an unknown name reads as not waiting`() {
        assertNull(AfterCallDecisions.peek(context, "never-held.ogg"))
        assertNull(AfterCallDecisions.release(context, "never-held.ogg"))
    }

    @Test
    fun `two recordings wait independently`() {
        AfterCallDecisions.hold(context, "a.ogg", uri, "audio/ogg", isVoip = false)
        AfterCallDecisions.hold(context, "b.m4a", uri, "audio/mp4a-latm", isVoip = true)

        AfterCallDecisions.release(context, "a.ogg")

        assertEquals(setOf("b.m4a"), AfterCallDecisions.pendingNames(context))
    }

    @Test
    fun `each hold gets the next notification id, never a shared one`() {
        val a = AfterCallDecisions.hold(context, "a.ogg", uri, "audio/ogg", isVoip = false)!!
        val b = AfterCallDecisions.hold(context, "b.ogg", uri, "audio/ogg", isVoip = false)!!

        assertEquals(AfterCallPolicy.nextNotificationId(a.notificationId), b.notificationId)
        assertTrue("the counter is not a held name", AfterCallDecisions.pendingNames(context) == setOf("a.ogg", "b.ogg"))
    }

    @Test
    fun `delete requested is recorded and cleared`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        assertTrue(AfterCallDecisions.setDeleteRequested(context, name, true)!!.deleteRequested)
        assertTrue(AfterCallDecisions.peek(context, name)!!.deleteRequested)
        assertFalse(AfterCallDecisions.setDeleteRequested(context, name, false)!!.deleteRequested)
    }

    @Test
    fun `a swiped question is remembered`() {
        AfterCallDecisions.hold(context, name, uri, "audio/ogg", isVoip = false)

        AfterCallDecisions.setDismissed(context, name)

        assertTrue(AfterCallDecisions.peek(context, name)!!.dismissed)
    }

    @Test
    fun `updating a recording that is not held does nothing`() {
        assertNull(AfterCallDecisions.setDeleteRequested(context, "never-held.ogg", true))
        assertTrue(AfterCallDecisions.pendingNames(context).isEmpty())
    }

    @Test
    fun `the pop-up asks about the oldest recording not already being deleted`() {
        AfterCallDecisions.hold(context, "newer.ogg", uri, "audio/ogg", isVoip = false, askedAt = 300L)
        AfterCallDecisions.hold(context, "oldest.ogg", uri, "audio/ogg", isVoip = false, askedAt = 100L)
        AfterCallDecisions.hold(context, "middle.ogg", uri, "audio/ogg", isVoip = false, askedAt = 200L)
        AfterCallDecisions.setDeleteRequested(context, "oldest.ogg", true)

        assertEquals("middle.ogg", AfterCallDecisions.nextToAsk(context)?.displayName)
    }

    @Test
    fun `nothing to ask when nothing is waiting`() {
        assertNull(AfterCallDecisions.nextToAsk(context))
    }

    @Test
    fun `a corrupt entry reads as not waiting rather than throwing`() {
        context.getSharedPreferences("cv_after_call_decisions", Context.MODE_PRIVATE)
            .edit().putString(name, "{not json").commit()

        assertNull(AfterCallDecisions.peek(context, name))
    }
}
