/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The delete walks (R44, C41) through a fake folder provider: only the named files go, the .json and .md go with the
 * audio, a same-looking name stays, and a folder is listed with a single children query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeleteWalksOnceTest {

    class FolderProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val cols = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val c = MatrixCursor(cols)
            if (uri.path!!.endsWith("/children")) {
                childrenQueries++
                FILES.forEach { name -> c.addRow(cols.map { col -> value(col, name) }.toTypedArray()) }
            } else {
                val id = DocumentsContract.getDocumentId(uri)
                if (id in FILES) c.addRow(cols.map { col -> value(col, id) }.toTypedArray())
            }
            return c
        }
        private fun value(col: String, name: String): Any? = when (col) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> name
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
            DocumentsContract.Document.COLUMN_SIZE -> 100L
            DocumentsContract.Document.COLUMN_MIME_TYPE -> "audio/ogg"
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1_000L
            DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            else -> null
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == "android:deleteDocument") {
                val uri = extras!!.getParcelable<Uri>("uri")!!
                val id = DocumentsContract.getDocumentId(uri)
                FILES.remove(id)
            }
            return Bundle()
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val a = "20240101_101010.000+0200_in_0501111111.ogg"
    private val b = "20240102_101010.000+0200_in_0502222222.ogg"

    @Before
    fun setUp() {
        FILES.clear()
        FILES += listOf(a, "20240101_101010.000+0200_in_0501111111.md", "20240101_101010.000+0200_in_0501111111.json", b,
            "20240102_101010.000+0200_in_0502222222.json", "other-app-note.json")
        childrenQueries = 0
        Robolectric.setupContentProvider(FolderProvider::class.java, AUTHORITY)
        AppPreferences(context).apply {
            setRecordingFolderUri("content://$AUTHORITY/tree/root".toUri())
            setDriveFolderUri(null)
        }
    }

    private fun item(name: String) = RecordingsRepository.RecordingItem(
        uri = "content://$AUTHORITY/tree/root/document/$name".toUri(), displayName = name, sizeBytes = 100L,
        lastModified = 1_000L, direction = null, displayDate = null, startedAtMillis = null,
    )

    @Test
    fun `a bulk delete removes the audio, the md and the json of the selection and nothing else`() = runBlocking {
        RecordingsRepository.deleteRecordings(context, listOf(item(a)))

        assertEquals(setOf(b, "20240102_101010.000+0200_in_0502222222.json", "other-app-note.json"), FILES.toSet())
    }

    @Test
    fun `a single delete lists the folder with one children query`() = runBlocking {
        val deleted = RecordingsRepository.deleteRecording(context, item(a))

        assertTrue(deleted)
        assertEquals(1, childrenQueries)
        assertEquals(setOf(b, "20240102_101010.000+0200_in_0502222222.json", "other-app-note.json"), FILES.toSet())
    }

    companion object {
        const val AUTHORITY = "com.example.b9.folder"
        val FILES = mutableListOf<String>()
        @Volatile var childrenQueries = 0
    }
}
