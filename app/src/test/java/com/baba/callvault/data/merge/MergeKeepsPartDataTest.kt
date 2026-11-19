/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.merge

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.transcripts.FavouriteRepository
import com.baba.callvault.data.transcripts.LibraryCounts
import com.baba.callvault.data.transcripts.TagRepository
import com.baba.callvault.data.transcripts.TranscriptRepository
import com.baba.callvault.data.transcripts.db.CallSummaryEntry
import com.baba.callvault.data.transcripts.db.RecordingFlagEntry
import com.baba.callvault.data.transcripts.db.RecordingNoteEntry
import com.baba.callvault.data.transcripts.db.RecordingTagEntry
import com.baba.callvault.data.transcripts.db.SpeakerTurnsEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.storage.StorageRouter
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * Merging with "keep originals" off (owner decision N2, option A): the parts leave the list, but their transcript,
 * notes, tags, star, marks, speaker turns and summary are kept, hidden, so un-merge brings them back. Deleting the
 * merged call for good deletes that hidden data too. The audio layer is stubbed, as in [MergeServiceSafetyTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MergeKeepsPartDataTest {

    private lateinit var context: Context
    private val partA = "20260901_143200.000+0300_out_5551234.m4a"
    private val partB = "20260901_144000.000+0300_in_5551234.m4a"
    private val folder = "content://com.android.externalstorage.documents/tree/primary%3ARec"
    private val word = "zanzibar"

    private val dao get() = RecordingDatabase.get(context).recordingDao()
    private val tdb get() = TranscriptDatabase.get(context)

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        val prefs = AppPreferences(context)
        prefs.setRecordingFolderUri(Uri.parse(folder))
        prefs.setKeepOriginalsAfterMerge(false)
        listOf(partA, partB).forEach { name ->
            val file = File(context.filesDir, name).apply { writeBytes(ByteArray(50)) }
            RecordingCatalog.recordLocal(context, name, Uri.fromFile(file), 50L, 1_000L)
            giveEverything(name)
        }

        mockkObject(SafHelper, AudioConcat, AudioSplit, MergeFormat, StorageRouter)
        every { SafHelper.createAudioFile(any(), any(), any(), any(), any()) } answers {
            val staging = File.createTempFile("merge", ".tmp", context.filesDir)
            val fd = ParcelFileDescriptor.open(staging, ParcelFileDescriptor.MODE_READ_WRITE)
            SafHelper.SafResult(uri = null, descriptor = fd, displayName = "Rec/x", stagingFile = staging)
        }
        every { AudioConcat.concat(any(), any(), any()) } answers {
            FileOutputStream(secondArg<FileDescriptor>()).write(ByteArray(100))
            listOf(
                AudioConcat.PartBoundary(0, 10, 0L, 1_000_000L, 0L, 0L),
                AudioConcat.PartBoundary(10, 10, 1_000_000L, 1_000_000L, 0L, 0L),
            )
        }
        every { AudioSplit.split(any(), any(), any(), any()) } answers {
            thirdArg<List<FileDescriptor>>().forEach { FileOutputStream(it).write(ByteArray(40)) }
        }
        every { MergeFormat.frameCount(any()) } returnsMany listOf(20, 10, 10)
        every { SafHelper.deleteDocument(any(), any()) } returns true
        justRun { StorageRouter.route(any(), any(), any(), any()) }
        // The merged file is published from staging; the parts come back from the un-merge cuts.
        every { SafHelper.publishStagedRecording(any(), any(), any(), any(), any()) } answers {
            val published = File(context.filesDir, "pub-" + thirdArg<String>())
            lastArg<File>().copyTo(published, overwrite = true)
            Uri.fromFile(published)
        }
        every { SafHelper.fileSize(any(), any()) } answers { File(secondArg<Uri>().path!!).length() }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        unmockkAll()
        val parts = RecordingDatabase.get(context).mergePartDao()
        dao.getAll().forEach {
            parts.deleteParts(it.displayName)
            RecordingCatalog.removeName(context, it.displayName)
        }
        listOf(partA, partB).forEach { com.baba.callvault.data.transcripts.TranscriptCascade.deleteFor(context, listOf(it)) }
    }

    private suspend fun giveEverything(name: String) {
        tdb.transcriptDao().upsertTranscript(TranscriptEntry(name, TranscriptState.DONE, "model", "en", 1L, null))
        tdb.transcriptDao().replaceSegments(name, listOf(TranscriptSegmentEntry(0, name, 0, 900, "we land in $word", null)))
        tdb.noteDao().upsertNote(RecordingNoteEntry(name, "note for $name"))
        tdb.tagDao().add(RecordingTagEntry(name, "lawyer"))
        tdb.favouriteDao().add(name)
        tdb.flagDao().upsertAll(listOf(RecordingFlagEntry(name, 300L)))
        tdb.speakerTurnsDao().upsert(SpeakerTurnsEntry(name, "0:0", outgoing = true, observedMap = "A", updatedAt = 1L))
        tdb.summaryDao().upsert(CallSummaryEntry(name, "{}", "model", createdAt = 1L, searchText = "summary $word"))
    }

    private suspend fun hasEverything(name: String) {
        assertEquals(TranscriptState.DONE, tdb.transcriptDao().findTranscript(name)?.state)
        assertEquals(1, tdb.transcriptDao().segmentsFor(name).size)
        assertNotNull(tdb.noteDao().note(name))
        assertEquals(listOf("lawyer"), tdb.tagDao().tagsFor(name))
        assertTrue(tdb.favouriteDao().isFavourite(name))
        assertEquals(1, tdb.flagDao().flagsFor(name).size)
        assertNotNull(tdb.speakerTurnsDao().turnsFor(name))
        assertNotNull(tdb.summaryDao().summary(name))
    }

    private suspend fun merge(): String {
        val outcome = MergeService.merge(context, partA, listOf(partB))
        assertTrue("$outcome", outcome is MergeService.Outcome.Merged)
        return (outcome as MergeService.Outcome.Merged).mergedName
    }

    @Test
    fun `merge then un-merge gives each part back everything it had`() = runBlocking {
        val merged = merge()
        assertNull("the parts leave the list", dao.findByName(partA))

        val outcome = MergeService.unMerge(context, merged)

        assertEquals(MergeService.Outcome.UnMerged(2), outcome)
        assertNotNull(dao.findByName(partA))
        hasEverything(partA)
        hasEverything(partB)
    }

    @Test
    fun `while merged the parts show in no list, count or search`() = runBlocking {
        val merged = merge()

        val transcripts = LibraryCounts.transcripts(context).first().map { it.displayName }
        assertFalse(partA in transcripts)
        assertTrue(merged in transcripts)
        assertEquals(1, LibraryCounts.transcribed(context).first())
        assertFalse(partA in LibraryCounts.summarisedNames(context).first())
        assertFalse(partA in FavouriteRepository.all(context).first())
        assertEquals(listOf(1), TagRepository.allTags(context).first().map { it.count })
        assertFalse(partA in TranscriptRepository.search(context, word).map { it.displayName })
        assertFalse(partA in TranscriptRepository.searchPage(context, TranscriptRepository.PageIndex.TRANSCRIPTS, word).keys)
        assertFalse(partA in TranscriptRepository.searchPage(context, TranscriptRepository.PageIndex.SUMMARIES, word).keys)
        assertFalse(partA in TranscriptRepository.searchContent(context, word).keys)
    }

    @Test
    fun `deleting the merged call deletes the hidden part data too`() = runBlocking {
        val merged = merge()

        RecordingCatalog.removeName(context, merged)

        listOf(partA, partB).forEach { name ->
            assertNull(tdb.transcriptDao().findTranscript(name))
            assertTrue(tdb.transcriptDao().segmentsFor(name).isEmpty())
            assertNull(tdb.noteDao().note(name))
            assertTrue(tdb.tagDao().tagsFor(name).isEmpty())
            assertFalse(tdb.favouriteDao().isFavourite(name))
            assertTrue(tdb.flagDao().flagsFor(name).isEmpty())
            assertNull(tdb.speakerTurnsDao().turnsFor(name))
            assertNull(tdb.summaryDao().summary(name))
        }
        assertTrue(RecordingDatabase.get(context).mergePartDao().partsOf(merged).isEmpty())
    }
}
