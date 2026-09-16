/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptSegmentEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import com.baba.callvault.data.waveform.RecordingExtrasRepository
import com.baba.callvault.system.storage.SafHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The delete that destroys the only copy of somebody's audio, run against a real SAF folder.
 *
 * The unit tests pin the *rule* ([TranscribeOnlyAudio.verdictFor]); this pins the *act*. Everything
 * that could go wrong here is invisible to a JVM test: whether a document in a tree the user granted
 * is actually removed, whether the catalog row goes with it, and — the one that matters most —
 * whether the transcript survives. Calling the ordinary delete would run [TranscriptCascade] and wipe
 * the words the deletion was made in exchange for, and nothing about that would look wrong until
 * somebody went to read them.
 *
 * **Run in the app's own process, deliberately** — that is, WITHOUT `-PisolateTestApp`. The folder
 * URI and its persisted permission belong to `com.baba.callvault`, and an isolated test application
 * has neither, so the copy would fail and every assertion below would pass vacuously. The test skips
 * itself rather than passing if no folder is configured.
 *
 * ⚠️ **Never run this on a phone somebody uses.** Gradle UNINSTALLS the app under test when a
 * connected run finishes, and an uninstall takes the recordings catalog, the transcripts database,
 * the ADB pairing and — the one that cannot be restored from a backup — the persisted SAF grant on
 * the user's recordings folder. Measured here on 2026-09-16: the run passed and left the emulator's
 * CallVault gone, its folder unchosen and its onboarding to be done again. That is the whole reason
 * `-PisolateTestApp` exists; this test is the exception that cannot use it, so it belongs on an
 * emulator and nowhere else.
 */
@RunWith(AndroidJUnit4::class)
class TranscribeOnlyAudioDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder: Uri? = AppPreferences(context).getRecordingFolderUri()

    /** Names this test created, removed however it ends so a failure leaves no litter behind. */
    private val created = mutableListOf<String>()

    @After
    fun cleanUp() = runBlocking {
        created.forEach { name ->
            documentFor(name)?.delete()
            RecordingCatalog.forgetName(context, name)
            TranscriptDatabase.get(context).transcriptDao().deleteFor(name)
        }
    }

    @Test
    fun a_stored_transcript_costs_the_audio_and_nothing_else() = runBlocking {
        assumeNotNull(folder)
        val name = importedFile(ImportedRecording.Kind.TRANSCRIBE_ONLY)
        storeTranscript(name, TranscriptState.DONE, words = listOf("שלום", "עולם"))
        RecordingExtrasRepository.saveNote(context, name, "my own note")

        val deleted = TranscribeOnlyAudio.deleteAfterTranscript(context, name)

        assertTrue("the audio should have been deleted", deleted)
        assertNull("the file is still in the recordings folder", documentFor(name))
        assertNull(
            "the catalog row dangles with no copy behind it",
            RecordingCatalog.all(context).firstOrNull { it.displayName == name },
        )
        // The whole point. TranscriptCascade would have taken all three.
        val dao = TranscriptDatabase.get(context).transcriptDao()
        assertEquals(TranscriptState.DONE, dao.findTranscript(name)?.state)
        assertEquals(listOf("שלום", "עולם"), dao.segmentsFor(name).map { it.text })
        assertEquals("my own note", RecordingExtrasRepository.note(context, name).first())
    }

    @Test
    fun a_failed_run_leaves_the_audio_where_it_was() = runBlocking {
        assumeNotNull(folder)
        val name = importedFile(ImportedRecording.Kind.TRANSCRIBE_ONLY)
        storeTranscript(name, TranscriptState.FAILED, words = emptyList())

        val deleted = TranscribeOnlyAudio.deleteAfterTranscript(context, name)

        assertFalse(deleted)
        assertNotNull("a failed run must leave something to retry", documentFor(name))
        assertNotNull(RecordingCatalog.all(context).firstOrNull { it.displayName == name })
    }

    @Test
    fun a_stopped_run_leaves_no_transcript_row_and_keeps_the_audio() = runBlocking {
        assumeNotNull(folder)
        val name = importedFile(ImportedRecording.Kind.TRANSCRIBE_ONLY)
        // What a Stop leaves behind: the runner removes the row entirely.

        val deleted = TranscribeOnlyAudio.deleteAfterTranscript(context, name)

        assertFalse(deleted)
        assertNotNull("a stopped run must leave something to retry", documentFor(name))
    }

    @Test
    fun an_import_the_user_asked_to_keep_is_never_deleted() = runBlocking {
        assumeNotNull(folder)
        val name = importedFile(ImportedRecording.Kind.KEEP)
        storeTranscript(name, TranscriptState.DONE, words = listOf("שלום"))

        val deleted = TranscribeOnlyAudio.deleteAfterTranscript(context, name)

        assertFalse("a kept import must survive being transcribed", deleted)
        assertNotNull(documentFor(name))
        assertNotNull(RecordingCatalog.all(context).firstOrNull { it.displayName == name })
    }

    /** Puts a real audio file in the recordings folder under an import name, and catalogues it. */
    private suspend fun importedFile(kind: ImportedRecording.Kind): String {
        val source = fixture()
        val name = ImportedRecording.nameFor(
            importedAtMillis = System.currentTimeMillis(),
            label = "probe-${System.nanoTime()}",
            extension = ".opus",
            kind = kind,
        )
        val copied = SafHelper.copyFileToFolder(
            context = context,
            srcUri = Uri.fromFile(source),
            destFolderUri = requireNotNull(folder),
            displayName = name,
            mimeType = "audio/ogg",
            sourceSize = source.length(),
        )
        val uri = when (copied) {
            is SafHelper.CopyResult.Copied -> copied.uri
            is SafHelper.CopyResult.AlreadyPresent -> copied.uri
            is SafHelper.CopyResult.Failed -> error("could not stage the fixture: ${copied.reason}")
        }
        created += name
        RecordingCatalog.recordLocal(context, name, uri, source.length(), System.currentTimeMillis())
        assertNotNull("the fixture is not in the folder, so nothing below means anything", documentFor(name))
        return name
    }

    private suspend fun storeTranscript(name: String, state: TranscriptState, words: List<String>) {
        val dao = TranscriptDatabase.get(context).transcriptDao()
        dao.upsertTranscript(
            TranscriptEntry(displayName = name, state = state, updatedAt = System.currentTimeMillis())
        )
        if (words.isNotEmpty()) {
            dao.replaceSegments(
                name,
                words.mapIndexed { index, text ->
                    TranscriptSegmentEntry(
                        displayName = name,
                        startMs = index * 1000L,
                        endMs = (index + 1) * 1000L,
                        text = text,
                        speaker = null,
                    )
                },
            )
        }
    }

    /** The document in the recordings folder with this name, or null when there is none. */
    private fun documentFor(name: String): DocumentFile? =
        folder?.let { DocumentFile.fromTreeUri(context, it) }
            ?.listFiles()
            ?.firstOrNull { it.isFile && it.name == name }

    /** One second of tone out of the test APK's assets — a few kilobytes, never in the shipped APK. */
    private fun fixture(): File {
        val out = File(context.cacheDir, "transcribe-only-probe.opus")
        InstrumentationRegistry.getInstrumentation().context.assets.open("import-probe/probe.opus")
            .use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        return out
    }
}
