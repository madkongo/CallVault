/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CallLog
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallLogReaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `without the call log permission it reads nothing rather than throwing`() {
        // ShadowPackageManager has no denyPermissions overload on Robolectric 4.14; the shadow that
        // exposes it lives on the ContextWrapper (see VoicemailLabelTest for the same pattern).
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(android.Manifest.permission.READ_CALL_LOG)
        assertEquals(emptyList<CallLogEntry>(), CallLogReader.entriesSince(context, 0L))
    }

    @Test
    fun `an unreadable provider reads nothing rather than throwing`() {
        // Robolectric serves no call-log rows by default; the query returns an empty cursor or null.
        assertEquals(emptyList<CallLogEntry>(), CallLogReader.entriesSince(context, 0L))
    }

    /**
     * Robolectric serves no rows for the call-log authority by default, which is exactly why the test
     * above passes trivially against a broken implementation too. This one seeds a fake call-log
     * provider — honouring the DATE-selection args and DESC sort the real provider would apply — so a
     * reader that mis-maps columns, forgets the watermark, or fails to drop MISSED calls actually fails.
     */
    @Test
    fun `it maps incoming and outgoing rows, drops missed calls, and honours the watermark`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(android.Manifest.permission.READ_CALL_LOG)

        val rows = listOf(
            // date,   duration, type,                          name
            Quadruple(2_000L, 30L, CallLog.Calls.INCOMING_TYPE, "Alice"),
            Quadruple(3_000L, 45L, CallLog.Calls.OUTGOING_TYPE, "Bob"),
            Quadruple(4_000L, 12L, CallLog.Calls.MISSED_TYPE, "Carol"),
            Quadruple(500L, 10L, CallLog.Calls.INCOMING_TYPE, "TooOldToCount")
        )
        ShadowContentResolver.registerProviderInternal(
            CallLog.AUTHORITY,
            FakeCallLogProvider(rows).apply { attachInfo(context, null) }
        )

        val result = CallLogReader.entriesSince(context, watermark = 1_000L)

        assertEquals(
            listOf(
                CallLogEntry(startedAt = 3_000L, durationSeconds = 45L, isIncoming = false, label = "Bob"),
                CallLogEntry(startedAt = 2_000L, durationSeconds = 30L, isIncoming = true, label = "Alice")
            ),
            result
        )
    }

    @Test
    fun `the null-on-failure reader tells an unreadable log from an empty one`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(android.Manifest.permission.READ_CALL_LOG)
        assertEquals(null, CallLogReader.entriesSinceOrNull(context, 0L))

        shadowOf(app).grantPermissions(android.Manifest.permission.READ_CALL_LOG)
        listOf<ContentProvider>(StubProvider { throw IllegalStateException("provider died") }, StubProvider { null }).forEach {
            ShadowContentResolver.registerProviderInternal(CallLog.AUTHORITY, it.apply { attachInfo(context, null) })
            assertEquals(null, CallLogReader.entriesSinceOrNull(context, 0L))
        }

        val empty = StubProvider { MatrixCursor(arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME)) }
        ShadowContentResolver.registerProviderInternal(CallLog.AUTHORITY, empty.apply { attachInfo(context, null) })
        assertEquals(emptyList<CallLogEntry>(), CallLogReader.entriesSinceOrNull(context, 0L))
    }

    private class StubProvider(private val answer: () -> Cursor?) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = answer()
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private data class Quadruple(val date: Long, val durationSeconds: Long, val type: Int, val name: String)

    /**
     * Minimal stand-in for the system call-log provider: applies the "DATE > ?" selection and DESC sort
     * that [CallLogReader] requests, the same way the real provider would, so the test exercises the
     * reader's query contract rather than just a hard-coded fixture.
     */
    private class FakeCallLogProvider(private val rows: List<Quadruple>) : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val watermark = selectionArgs?.firstOrNull()?.toLongOrNull() ?: Long.MIN_VALUE
            val cursor = MatrixCursor(
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME)
            )
            rows.filter { it.date > watermark }
                .sortedByDescending { it.date }
                .forEach { cursor.addRow(arrayOf<Any>(it.date, it.durationSeconds, it.type, it.name)) }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ) = 0
    }
}
