/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.transcripts.db.RecordingLabelEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.transcription.TranscriptionEngine
import com.baba.callvault.transcription.TranscriptionInFlight
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cascade: deleting a recording must delete its transcript.
 *
 * A cross-database foreign key is impossible (transcripts deliberately live in their own database),
 * so this is enforced by code rather than by SQLite — which is exactly why it needs tests. Leaving a
 * transcript behind is not untidiness: the app would promise a recording was deleted while retaining
 * a searchable text of the conversation, which is more exposing than the audio was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class TranscriptCascadeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearInFlight() {
        TranscriptionInFlight.releaseAll()
        TranscriptionEngine.clearAbortForTest()
    }

    /**
     * Deleting the recording that is being transcribed right now must stop the run (issue #35).
     *
     * Without this, whisper goes on for minutes producing a transcript of a call that no longer
     * exists, and because the engine serialises on one thread, every recording queued behind it
     * waits for a result that is thrown away.
     */
    @Test
    fun deleting_the_recording_being_transcribed_stops_the_run() = runBlocking {
        // Arrange
        val name = "in-flight.ogg"
        RecordingCatalog.recordLocal(context, name, "content://local/9".toUri(), 10L, 0L)
        seedTranscript(name)
        TranscriptionInFlight.claim(name)

        // Act
        RecordingCatalog.removeName(context, name)

        // Assert
        assertTrue("the run was left going after its recording was deleted", TranscriptionEngine.wasAborted())
    }

    /** Deleting anything else must leave a run in progress alone. */
    @Test
    fun deleting_another_recording_leaves_the_run_alone() = runBlocking {
        // Arrange
        val running = "keep-running.ogg"
        val deleted = "unrelated.ogg"
        RecordingCatalog.recordLocal(context, deleted, "content://local/10".toUri(), 10L, 0L)
        seedTranscript(deleted)
        TranscriptionInFlight.claim(running)

        // Act
        RecordingCatalog.removeName(context, deleted)

        // Assert
        assertFalse("an unrelated delete stopped the run", TranscriptionEngine.wasAborted())
    }

    @Test
    fun deleting_a_recording_by_name_deletes_its_transcript() = runBlocking {
        // Arrange
        val name = "cascade-by-name.ogg"
        RecordingCatalog.recordLocal(context, name, "content://local/1".toUri(), 10L, 0L)
        seedTranscript(name)
        assertNotNull("precondition: transcript was seeded", transcript(name))

        // Act
        RecordingCatalog.removeName(context, name)

        // Assert
        assertNull("transcript outlived the recording", transcript(name))
    }

    @Test
    fun deleting_the_last_copy_of_a_recording_deletes_its_transcript() = runBlocking {
        // The retention sweep deletes per copy, not by name, so this is the path that expiring
        // recordings actually take.
        val name = "cascade-last-copy.ogg"
        val uri = "content://local/2".toUri()
        RecordingCatalog.recordLocal(context, name, uri, 10L, 0L)
        seedTranscript(name)

        // Act
        RecordingCatalog.removeCopyByUri(context, uri)

        // Assert
        assertNull("transcript outlived its last copy", transcript(name))
    }

    @Test
    fun deleting_one_copy_of_a_recording_that_still_exists_keeps_the_transcript() = runBlocking {
        // Removing the device copy of a synced recording leaves the Drive copy — and the recording,
        // so its transcript must survive.
        val name = "cascade-one-of-two.ogg"
        val localUri = "content://local/3".toUri()
        RecordingCatalog.recordLocal(context, name, localUri, 10L, 0L)
        RecordingCatalog.markDrive(context, name, "content://drive/3".toUri(), 10L, deleteLocalAfter = false)
        seedTranscript(name)

        // Act
        RecordingCatalog.removeCopyByUri(context, localUri)

        // Assert
        assertNotNull("transcript was destroyed while the recording still exists", transcript(name))
    }

    @Test
    fun deleting_a_recording_deletes_its_cached_label() = runBlocking {
        // R48: the label row holds the contact name and date; it must not outlive the call.
        val name = "cascade-label.ogg"
        val other = "cascade-label-kept.ogg"
        RecordingCatalog.recordLocal(context, name, "content://local/20".toUri(), 10L, 0L)
        seedTranscript(name)
        val labels = TranscriptDatabase.get(context).labelDao()
        labels.upsertAll(
            listOf(
                RecordingLabelEntry(name, "Dana", "20/09/26 12:25"),
                RecordingLabelEntry(other, "Sam", "21/09/26 09:00"),
            )
        )

        // Act
        RecordingCatalog.removeName(context, name)

        // Assert
        val left = labels.all().map { it.displayName }
        assertFalse("the contact name and date outlived the call", name in left)
        assertTrue("an unrelated label was removed", other in left)
    }

    @Test
    fun deleting_a_recording_that_was_never_transcribed_is_harmless() = runBlocking {
        val name = "cascade-never-transcribed.ogg"
        RecordingCatalog.recordLocal(context, name, "content://local/4".toUri(), 10L, 0L)

        // Act / Assert — must not throw.
        RecordingCatalog.removeName(context, name)
        assertTrue(true)
    }

    private suspend fun seedTranscript(name: String) {
        val dao = TranscriptDatabase.get(context).transcriptDao()
        dao.upsertTranscript(
            TranscriptEntry(displayName = name, state = TranscriptState.DONE, modelId = "small-q5_1")
        )
        dao.replaceSegments(
            name,
            listOf(TranscriptSegmentEntry(displayName = name, startMs = 0, endMs = 1, text = "hello"))
        )
    }

    private suspend fun transcript(name: String) =
        TranscriptDatabase.get(context).transcriptDao().observe(name).first()
}
