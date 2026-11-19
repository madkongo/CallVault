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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C29: a cloud provider answers a folder it is still fetching with a partial cursor flagged
 * EXTRA_LOADING. The sweep must not take that as the folder's contents, or it uploads every missing name again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafHelperListingLoadingTest {

    /** First query: one entry and "still loading"; every later query: both entries, done. */
    class LoadingProvider : ContentProvider() {
        var queries = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            queries++
            val c = MatrixCursor(arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ))
            c.addRow(arrayOf<Any>("root/a", "a.ogg", 10L))
            if (queries == 1) {
                c.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            } else {
                c.addRow(arrayOf<Any>("root/b", "b.ogg", 20L))
            }
            c.setNotificationUri(context!!.contentResolver, uri)
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    /** Like [LoadingProvider], but its one change notification fires during the first query, before anyone listens. */
    class EarlyNotifyProvider : ContentProvider() {
        var queries = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            queries++
            val c = MatrixCursor(arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ))
            c.addRow(arrayOf<Any>("root/a", "a.ogg", 10L))
            if (queries == 1) {
                c.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
                context!!.contentResolver.notifyChange(uri, null) // lost: no observer is registered yet
            } else {
                c.addRow(arrayOf<Any>("root/b", "b.ogg", 20L))
            }
            return c // and no notification URI at all
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tree = "content://$AUTHORITY/tree/root".toUri()

    @Test
    fun `a listing still loading is waited out and read again, not used as it is`() {
        Robolectric.setupContentProvider(LoadingProvider::class.java, AUTHORITY)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root")
        Thread {
            Thread.sleep(300)
            context.contentResolver.notifyChange(children, null) // the provider has fetched the folder
        }.start()

        val (listing, why) = SafHelper.listChildrenWithReason(context, tree)

        assertEquals("reason: $why", listOf("a.ogg", "b.ogg"), listing?.names)
    }

    @Test
    fun `a notification missed before the observer was registered costs seconds, not the whole wait`() {
        Robolectric.setupContentProvider(EarlyNotifyProvider::class.java, EARLY_AUTHORITY)
        val started = System.nanoTime()

        val (listing, why) = SafHelper.listChildrenWithReason(context, "content://$EARLY_AUTHORITY/tree/root".toUri())

        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("reason: $why", listOf("a.ogg", "b.ogg"), listing?.names)
        assertTrue("took ${tookMs}ms", tookMs < 5_000)
    }

    @Test
    fun `a provider that never finishes loading gives no listing, with the reason`() {
        val reads = ArrayList<Boolean>()
        var clock = 0L
        val partial = DriveCopyProtocol.Listing(listOf("a.ogg"), listOf(tree), listOf(1L))

        val (listing, why) = SafHelper.listUntilLoaded(waitMs = 1_000L, now = { clock }, pollMs = 600L) {
            SafHelper.ChildrenRead(partial, loading = true, awaitChange = { left -> reads += true; clock += left }, close = {})
        }

        assertNull(listing)
        assertTrue(why!!, why.contains("still loading"))
        assertEquals(2, reads.size) // waited in two poll steps (600 + 400 ms) for the 1 s budget, then gave up
    }

    @Test
    fun `a listing that is not loading is used at once`() {
        val full = DriveCopyProtocol.Listing(listOf("a.ogg"), listOf(tree), listOf(1L))
        var closed = 0

        val (listing, _) = SafHelper.listUntilLoaded(waitMs = 1_000L, now = { 0L }) {
            SafHelper.ChildrenRead(full, loading = false, awaitChange = { error("must not wait") }, close = { closed++ })
        }

        assertEquals(full, listing)
        assertEquals(1, closed)
    }

    @Test
    fun `the wait for a loading folder fits inside the app's time limit for a listing`() {
        assertTrue(CloudCopyPolicy.LIST_LOADING_WAIT_MS < CloudCopyPolicy.LIST_TIMEOUT_MS)
    }

    private companion object {
        const val AUTHORITY = "com.baba.callvault.test.loadingdocs"
        const val EARLY_AUTHORITY = "com.baba.callvault.test.earlynotifydocs"
    }
}
