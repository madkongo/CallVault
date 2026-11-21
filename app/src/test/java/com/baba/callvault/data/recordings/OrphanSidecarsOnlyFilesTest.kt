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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Orphaned-sidecar cleanup deletes plain files only: a folder named like a sidecar is left alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrphanSidecarsOnlyFilesTest {

    class FolderProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val cols = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val c = MatrixCursor(cols)
            if (uri.path!!.endsWith("/children")) {
                ENTRIES.keys.forEach { id -> c.addRow(cols.map { col -> value(col, id) }.toTypedArray()) }
            } else {
                val id = DocumentsContract.getDocumentId(uri)
                if (id in ENTRIES) c.addRow(cols.map { col -> value(col, id) }.toTypedArray())
            }
            return c
        }
        private fun value(col: String, id: String): Any? = when (col) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME -> id
            DocumentsContract.Document.COLUMN_SIZE -> 10L
            DocumentsContract.Document.COLUMN_MIME_TYPE -> ENTRIES.getValue(id)
            DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            else -> null
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == "android:deleteDocument") {
                ENTRIES.remove(DocumentsContract.getDocumentId(extras!!.getParcelable<Uri>("uri")!!))
            }
            return Bundle()
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val base = "20240101_101010.000+0200_in_0501111111"

    @Before
    fun setUp() {
        ENTRIES.clear()
        ENTRIES["$base.md"] = "text/markdown"
        ENTRIES["$base.json"] = DocumentsContract.Document.MIME_TYPE_DIR
        Robolectric.setupContentProvider(FolderProvider::class.java, AUTHORITY)
        AppPreferences(context).apply {
            setRecordingFolderUri("content://$AUTHORITY/tree/root".toUri())
            setDriveFolderUri(null)
        }
    }

    @Test
    fun `a folder named like a sidecar is not deleted but the real file is`() = runBlocking {
        val deleted = RecordingSidecars.deleteOrphanedFor(context, listOf("$base.ogg"))

        assertEquals(1, deleted)
        assertEquals(setOf("$base.json"), ENTRIES.keys.toSet())
    }

    companion object {
        const val AUTHORITY = "com.example.b9.orphans"
        val ENTRIES = LinkedHashMap<String, String>()
    }
}
