/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault

import android.content.Intent
import android.os.Bundle
import com.baba.callvault.ui.navigation.NotificationDestination
import com.baba.callvault.ui.navigation.OpenRecordingRequest
import com.baba.callvault.ui.navigation.OpenTranscriptRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C75 (hostile extras) and C160 (a handled request must not fire again on recreation). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaunchRequestsTest {

    /** An Intent whose extras Bundle cannot be read, as when another app sends an unknown Parcelable. */
    private class PoisonedIntent : Intent() {
        override fun getStringExtra(name: String?): String? =
            throw android.os.BadParcelableException("ClassNotFoundException when unmarshalling")
    }

    private fun intentWith(
        destination: String? = null,
        recording: String? = null,
        transcript: String? = null,
    ) = Intent().apply {
        destination?.let { putExtra(NotificationDestination.EXTRA, it) }
        recording?.let { putExtra(OpenRecordingRequest.EXTRA, it) }
        transcript?.let { putExtra(OpenTranscriptRequest.EXTRA, it) }
    }

    @Test
    fun `unreadable extras read as no request instead of crashing (C75)`() {
        val requests = LaunchRequests.fromIntent(PoisonedIntent())

        assertEquals(LaunchRequests.NONE, requests)
    }

    @Test
    fun `well-formed extras are read exactly as before (C75)`() {
        val requests = LaunchRequests.fromIntent(
            intentWith(destination = "status", recording = "call.ogg", transcript = "other.ogg")
        )

        assertEquals(NotificationDestination.Status, requests.destination)
        assertEquals("call.ogg", requests.recording)
        assertEquals("other.ogg", requests.transcript)
    }

    @Test
    fun `a null intent is no request`() {
        assertEquals(LaunchRequests.NONE, LaunchRequests.fromIntent(null))
    }

    @Test
    fun `a relaunch from history is no request`() {
        val intent = intentWith(recording = "call.ogg").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        assertEquals(LaunchRequests.NONE, LaunchRequests.fromIntent(intent))
    }

    @Test
    fun `a recreation does not read the launch intent again (C160)`() {
        // The Intent still carries the request the first creation already acted on.
        val intent = intentWith(destination = "status", recording = "call.ogg")
        val saved = Bundle() // what onSaveInstanceState wrote after the request was handled: nothing

        val requests = LaunchRequests.forCreate(saved, intent)

        assertEquals(LaunchRequests.NONE, requests)
    }

    @Test
    fun `a first creation reads the launch intent (C160)`() {
        val intent = intentWith(transcript = "call.ogg")

        val requests = LaunchRequests.forCreate(null, intent)

        assertEquals("call.ogg", requests.transcript)
    }

    @Test
    fun `a request still waiting survives a recreation (C160)`() {
        val waiting = LaunchRequests(NotificationDestination.Update, "a.ogg", "b.ogg")
        val saved = Bundle().also { waiting.writeTo(it) }

        val requests = LaunchRequests.forCreate(saved, intentWith(destination = "status"))

        assertEquals(waiting, requests)
    }

    @Test
    fun `nothing is written for an empty request`() {
        val saved = Bundle().also { LaunchRequests.NONE.writeTo(it) }

        assertTrue(saved.isEmpty)
        assertNull(LaunchRequests.readFrom(saved).recording)
    }
}
