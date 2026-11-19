/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

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
import androidx.work.testing.TestListenableWorkerBuilder
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.StorageTarget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C28 follow-up, through the whole sweep: Drive holds "Call.m4a" (ANOTHER recording) and the phone has
 * "call.m4a". Matched ignoring case, the sweep handed the other recording's document to the copy as this one's
 * existing copy; with a different length the copy judged it truncated and deleted it, and with the same length
 * the sweep stamped this recording as backed up by the other one's document. Names must match exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncSweepExactNameTest {

    /** The device recording folder: a tree "root" holding the files in [FILES] (name to size). */
    class SourceProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val cols = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val c = MatrixCursor(cols)
            if (uri.path!!.endsWith("/children")) {
                FILES.keys.forEach { name -> c.addRow(cols.map { col -> value(col, name) }.toTypedArray()) }
            } else {
                val id = DocumentsContract.getDocumentId(uri)
                c.addRow(cols.map { col -> if (id == "root") rootValue(col) else value(col, id) }.toTypedArray())
            }
            return c
        }
        private fun value(col: String, name: String): Any? = when (col) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> name
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
            DocumentsContract.Document.COLUMN_SIZE -> FILES.getValue(name)
            DocumentsContract.Document.COLUMN_MIME_TYPE -> "audio/mp4"
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1_000L
            DocumentsContract.Document.COLUMN_FLAGS -> 0
            else -> null
        }
        private fun rootValue(col: String): Any? = when (col) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "root"
            DocumentsContract.Document.COLUMN_MIME_TYPE -> DocumentsContract.Document.MIME_TYPE_DIR
            DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            else -> null
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    /** Stands in for the `:drive` process: lists [driveListing] and records every copy request it gets. */
    private class FakeCopier(private val listing: DriveCopyProtocol.Listing) : DriveCopier {
        val copies = ArrayList<DriveCopyProtocol.Request>()
        override val where = "fake copier"
        override suspend fun call(what: Int, data: Bundle, timeoutMs: Long): DriveCopyClient.Outcome =
            if (what == DriveCopyProtocol.MSG_LIST) {
                DriveCopyClient.Outcome.Reply(DriveCopyProtocol.listReply(listing))
            } else {
                val r = DriveCopyProtocol.readCopyRequest(data)!!
                copies += r
                DriveCopyClient.Outcome.Reply(
                    DriveCopyProtocol.copyReply(SafHelper.CopyResult.Failed("test: not copied"), CopyTimings(), -1L, r.name),
                )
            }
        override fun close() = Unit
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun driveDoc(id: String) = "content://com.google.android.apps.docs.storage/document/$id".toUri()

    @After
    fun tearDown() {
        SyncSweepWorker.copierFactory = null
    }

    @Test
    fun `a Drive document whose name differs only in case is never this recording's copy`() = runBlocking {
        Robolectric.setupContentProvider(SourceProvider::class.java, SOURCE)
        AppPreferences(context).apply {
            setStorageTarget(StorageTarget.BOTH)
            setRecordingFolderUri("content://$SOURCE/tree/root".toUri())
            setDriveFolderUri("content://com.google.android.apps.docs.storage/tree/driveroot".toUri())
        }
        val drive = DriveCopyProtocol.Listing(
            names = listOf("Call.m4a", "Rec.m4a"),
            uris = listOf(driveDoc("otherCall"), driveDoc("otherRec")),
            sizes = listOf(10L, 10L),
        )
        val copier = FakeCopier(drive)
        SyncSweepWorker.copierFactory = { copier }

        TestListenableWorkerBuilder<SyncSweepWorker>(context).build().doWork()

        val byName = copier.copies.associateBy { it.name }
        assertEquals("both recordings go to the copier: neither is 'already there'", setOf("call.m4a", "rec.m4a"), byName.keys)
        for (r in byName.values) {
            assertNull("'${r.name}' was handed another recording's document", r.existing)
            assertEquals(-1L, r.existingSize)
        }
    }

    companion object {
        const val SOURCE = "com.baba.callvault.test.sweepsource"
        /** "call.m4a" differs in length from Drive's "Call.m4a" (the PARTIAL -> delete case); "rec.m4a" matches it. */
        val FILES = mapOf("call.m4a" to 4096L, "rec.m4a" to 10L)
    }
}
