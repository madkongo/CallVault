/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import android.os.Bundle
import android.os.Looper
import android.os.Message
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C15: a late answer from an abandoned copier call must never complete a later call, and a copy reply
 * for another recording must never lead to a delete or a catalog stamp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveCopyReplyMatchingTest {

    private val driveDoc = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Da".toUri()

    private fun copyReplyFor(name: String) =
        DriveCopyProtocol.copyReply(SafHelper.CopyResult.Copied(driveDoc, 4096L), CopyTimings(), 4096L, name)

    @Test
    fun `a reply carrying another call's sequence number is dropped`() {
        val done = CompletableDeferred<Bundle?>()
        val strays = ArrayList<Int>()
        val handler = DriveCopyClient.ReplyHandler(Looper.getMainLooper(), expectedSeq = 7, target = done) { strays += it }

        handler.handleMessage(Message.obtain(null, DriveCopyProtocol.MSG_COPY).also { it.arg1 = 6; it.data = copyReplyFor("a.ogg") })

        assertFalse(done.isCompleted)
        assertEquals(listOf(6), strays)
    }

    @Test
    fun `the reply with this call's sequence number completes it`() {
        val done = CompletableDeferred<Bundle?>()
        val handler = DriveCopyClient.ReplyHandler(Looper.getMainLooper(), expectedSeq = 7, target = done) {}

        handler.handleMessage(Message.obtain(null, DriveCopyProtocol.MSG_COPY).also { it.arg1 = 7; it.data = copyReplyFor("b.ogg") })

        assertTrue(done.isCompleted)
    }

    @Test
    fun `the copier echoes the request's sequence number in its reply`() {
        val request = Message.obtain(null, DriveCopyProtocol.MSG_COPY).also { it.arg1 = 42 }

        val reply = DriveCopyProtocol.replyTo(request)

        assertEquals(42, reply.arg1)
        assertEquals(DriveCopyProtocol.MSG_COPY, reply.what)
    }

    @Test
    fun `a copy reply for another recording is a failure, never a copy`() {
        val read = DriveCopyProtocol.readCopyReply(copyReplyFor("other.ogg"), expectedName = "mine.ogg")

        assertTrue(read.result is SafHelper.CopyResult.Failed)
        assertTrue((read.result as SafHelper.CopyResult.Failed).reason.contains("other.ogg"))
        assertEquals(-1L, read.driveSize) // no length to verify a delete against
    }

    @Test
    fun `a copy reply for this recording reads as the copy`() {
        val read = DriveCopyProtocol.readCopyReply(copyReplyFor("mine.ogg"), expectedName = "mine.ogg")

        assertEquals(SafHelper.CopyResult.Copied(driveDoc, 4096L), read.result)
        assertEquals(4096L, read.driveSize)
    }
}
