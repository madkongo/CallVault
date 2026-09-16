/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hub's counts on a phone that has never transcribed anything.
 *
 * What is being defended: the hub is the first screen of the app and asks these questions on every
 * open. If asking created `transcripts.db`, every install would grow a transcripts database whether
 * or not the user ever transcribed a call, and the next `exists()` check anywhere in the app — the
 * delete cascade, the row statuses — would start doing real work for nothing.
 *
 * The state this needs is "no database at all", which is process-wide rather than per-test: Robolectric
 * shares one sandbox classloader between test classes with the same config, so a sibling class that
 * opens the database leaves [TranscriptDatabase] holding an instance and its file on disk. Both are
 * cleared below rather than assumed, so this passes whatever else the suite ran first and in whatever
 * order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryCountsUntouchedDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun forgetAnyDatabaseAnotherTestOpened() {
        val instance = TranscriptDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }
        (instance.get(null) as? TranscriptDatabase)?.close()
        instance.set(null, null)
        context.getDatabasePath("transcripts.db").delete()
    }

    @Test
    fun counting_does_not_create_a_transcripts_database() = runBlocking {
        assertFalse("the test starts before any database exists", TranscriptDatabase.exists(context))

        assertEquals(0, LibraryCounts.transcribed(context).first())
        assertEquals(0, LibraryCounts.summarised(context).first())
        assertEquals(emptyList<String>(), LibraryCounts.transcribedNames(context).first())
        assertEquals(emptyList<String>(), LibraryCounts.summarisedNames(context).first())

        assertFalse("asking for the counts must not materialise one", TranscriptDatabase.exists(context))
    }
}
