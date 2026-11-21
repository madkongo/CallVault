/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.waveform

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** C74 (sidecar write off the caller and folded per recording) and R39 (a failed draw is remembered). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingExtrasFixesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val originalWriter = RecordingExtrasRepository.sidecarWriter

    @After
    fun restoreWriter() {
        RecordingExtrasRepository.sidecarWriter = originalWriter
    }

    @Test
    fun a_note_save_writes_the_sidecar_off_the_main_thread() = runBlocking {
        val onMain = CompletableDeferred<Boolean>()
        RecordingExtrasRepository.sidecarWriter = { _, _ -> onMain.complete(Looper.myLooper() == Looper.getMainLooper()) }

        RecordingExtrasRepository.saveNote(context, "c74-thread.ogg", "a note")

        assertFalse("the sidecar was written on the main thread", onMain.await())
    }

    @Test
    fun the_note_is_stored_before_the_sidecar_is_written() = runBlocking {
        var noteSeenByWriter: String? = null
        RecordingExtrasRepository.sidecarWriter = { ctx, name ->
            noteSeenByWriter = TranscriptDatabase.get(ctx).noteDao().observeNote(name).first()?.text
        }

        RecordingExtrasRepository.saveNote(context, "c74-order.ogg", "kept first")

        assertEquals("kept first", noteSeenByWriter)
    }

    @Test
    fun a_burst_of_note_saves_rewrites_the_sidecar_at_most_twice() = runBlocking {
        val writes = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        RecordingExtrasRepository.sidecarWriter = { _, _ ->
            if (writes.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                release.await()
            }
        }

        val first = async(Dispatchers.Default) { RecordingExtrasRepository.saveNote(context, "c74-burst.ogg", "v1") }
        firstStarted.await()
        val rest = (2..10).map { n ->
            async(Dispatchers.Default) { RecordingExtrasRepository.saveNote(context, "c74-burst.ogg", "v$n") }
        }
        rest.awaitAll()
        release.complete(Unit)
        first.await()

        assertEquals("one write for the first save, one more for everything saved while it ran", 2, writes.get())
    }

    @Test
    fun writes_for_one_recording_never_overlap_and_other_recordings_are_not_held_up() = runBlocking {
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        RecordingExtrasRepository.sidecarWriter = { _, name ->
            if (name == "c74-same.ogg") {
                maxRunning.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
                gate.await()
                running.decrementAndGet()
            }
        }
        val slow = launch(Dispatchers.Default) { RecordingExtrasRepository.saveNote(context, "c74-same.ogg", "x") }
        val other = async(Dispatchers.Default) { RecordingExtrasRepository.saveNote(context, "c74-other.ogg", "y") }

        other.await() // finishes while the slow one is still held
        gate.complete(Unit)
        slow.join()

        assertEquals(1, maxRunning.get())
    }

    @Test
    fun a_throwing_write_does_not_leave_the_recording_stuck() = runBlocking {
        val writes = AtomicInteger()
        RecordingExtrasRepository.sidecarWriter = { _, _ ->
            if (writes.incrementAndGet() == 1) throw IllegalStateException("provider went away")
        }

        runCatching { RecordingExtrasRepository.saveNote(context, "c74-throws.ogg", "x") }
        RecordingExtrasRepository.saveNote(context, "c74-throws.ogg", "y")

        assertEquals("a later save still writes after an earlier write threw", 2, writes.get())
    }

    @Test
    fun a_drawn_shape_settles_the_predraw_for_good() {
        assertTrue(RecordingExtrasRepository.isPredrawSettled("10,20,30", 1L, now = Long.MAX_VALUE))
    }

    @Test
    fun a_recording_never_tried_is_drawn() {
        assertFalse(RecordingExtrasRepository.isPredrawSettled(null, null, now = 1_000L))
    }

    @Test
    fun a_failed_draw_is_left_alone_for_a_day_then_tried_once_more() {
        val failedAt = 1_000_000L
        val day = 24L * 60 * 60 * 1000

        assertTrue(RecordingExtrasRepository.isPredrawSettled("", failedAt, now = failedAt + 60_000L))
        assertTrue(RecordingExtrasRepository.isPredrawSettled("", failedAt, now = failedAt + day - 1))
        assertFalse(RecordingExtrasRepository.isPredrawSettled("", failedAt, now = failedAt + day))
    }

    @Test
    fun a_failure_marker_dated_in_the_future_is_retried() {
        assertFalse(RecordingExtrasRepository.isPredrawSettled("", 5_000L, now = 1_000L))
    }

    @Test
    fun an_unreadable_recording_is_decoded_once_not_on_every_refresh() = runBlocking {
        val name = "r39-unreadable.ogg"
        val uri = Uri.parse("content://callvault.test/does-not-exist")

        RecordingExtrasRepository.precomputeWaveform(context, name, uri)

        val dao = TranscriptDatabase.get(context).noteDao()
        assertNotNull("no failure marker was left, so the next refresh decodes again", dao.waveform(name))
        assertEquals("", dao.waveform(name))
        assertTrue(
            RecordingExtrasRepository.isPredrawSettled(dao.waveform(name), dao.waveformUpdatedAt(name), System.currentTimeMillis())
        )
    }
}
