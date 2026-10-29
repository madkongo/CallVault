/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub #55: the Persian-tuned tier is listed for Persian, and never strands a user who chose it. */
class TranscriptionModelOfferTest {

    private val persian = TranscriptionModel.LARGE_V3_TURBO_PERSIAN_Q5_0
    private val general = TranscriptionModel.entries.filter { it.language == null }

    @Test
    fun persian_pinned_lists_the_persian_tier() {
        val offered = TranscriptionModelOffer.offered("fa", TranscriptionModel.DEFAULT.id, emptySet())

        assertTrue(persian in offered)
    }

    @Test
    fun other_languages_and_auto_detect_do_not_list_it() {
        listOf("he", "en", "ar", null).forEach { language ->
            val offered = TranscriptionModelOffer.offered(language, TranscriptionModel.DEFAULT.id, emptySet())

            assertFalse("listed for $language", persian in offered)
            assertEquals("general tiers for $language", general, offered)
        }
    }

    @Test
    fun a_selected_persian_tier_stays_listed_after_the_language_changes() {
        val offered = TranscriptionModelOffer.offered("en", persian.id, emptySet())

        assertTrue(persian in offered)
    }

    @Test
    fun an_installed_persian_tier_stays_listed_so_it_can_be_deleted() {
        val offered = TranscriptionModelOffer.offered("en", TranscriptionModel.DEFAULT.id, setOf(persian.id))

        assertTrue(persian in offered)
    }

    @Test
    fun every_general_tier_is_always_listed_including_the_default() {
        val offered = TranscriptionModelOffer.offered("fa", persian.id, setOf(persian.id))

        assertTrue(offered.containsAll(general))
        assertTrue(TranscriptionModel.DEFAULT in offered)
    }

    @Test
    fun the_default_is_a_general_tier() {
        // The dropdown falls back to DEFAULT for an unknown id, so DEFAULT must always be listed.
        assertEquals(null, TranscriptionModel.DEFAULT.language)
    }
}
