/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.net.Uri
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review C166: the listing (rows, reasons, empty card) was re-ranked on every read, and every list row read
 * it. One state now computes it once; a changed state (copy) computes its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeListingComputedOnceTest {

    private val recordings = (1..5).map { item("$it.m4a", "Dana $it") }

    @Test
    fun `reading the listing twice from one state gives the same objects, not a second ranking`() {
        val state = HomeViewModel.HomeUiState(recordings = recordings, contactQuery = "dana")

        assertSame(state.filteredRecordings, state.filteredRecordings)
        assertSame(state.searchReasons, state.searchReasons)
        assertEquals(state.emptyState, state.emptyState)
    }

    @Test
    fun `a changed query is a new state and ranks again`() {
        val state = HomeViewModel.HomeUiState(recordings = recordings, contactQuery = "dana 1")
        val typedMore = state.copy(contactQuery = "dana 2")

        assertNotSame(state.filteredRecordings, typedMore.filteredRecordings)
        assertEquals(listOf("1.m4a"), state.filteredRecordings.map { it.displayName })
        assertEquals(listOf("2.m4a"), typedMore.filteredRecordings.map { it.displayName })
        assertEquals(setOf("2.m4a"), typedMore.searchReasons.keys)
    }

    private fun item(name: String, contactName: String) = RecordingItem(
        uri = Uri.parse("content://test/$name"),
        displayName = name,
        sizeBytes = 1_000L,
        lastModified = 0L,
        direction = null,
        displayDate = null,
        startedAtMillis = null,
        number = null,
        contactName = contactName,
    )
}
