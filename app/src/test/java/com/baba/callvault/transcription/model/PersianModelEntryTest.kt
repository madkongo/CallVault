/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Persian-tuned tier (GitHub #55) comes from a third-party repository, so its URL is pinned to a
 * commit: a rewritten `main` would otherwise fail every download on the digest with no visible cause.
 */
class PersianModelEntryTest {

    private val model = TranscriptionModel.LARGE_V3_TURBO_PERSIAN_Q5_0

    @Test
    fun url_is_pinned_to_a_forty_hex_commit() {
        val pinned = Regex("^https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[^/]+$")

        assertTrue(model.url, pinned.matches(model.url))
        assertTrue(model.url.endsWith("/" + model.fileName))
    }

    @Test
    fun digest_and_size_are_the_published_values() {
        assertTrue(Regex("^[0-9a-f]{64}$").matches(model.sha256))
        assertEquals("d832fe5cd81c756bc1962765cff4a33cacde00a7701809837875245870b6d5a8", model.sha256)
        assertEquals(574_041_195L, model.sizeBytes)
    }

    @Test
    fun is_persian_only_with_turbo_q5_0_speed() {
        assertEquals("fa", model.language)
        assertEquals(TranscriptionModel.LARGE_V3_TURBO_Q5_0.realTimeFactor, model.realTimeFactor, 0.0)
    }

    @Test
    fun ids_and_file_names_are_unique_across_the_catalogue() {
        val catalogue = ModelTransfer.catalogue()

        assertEquals(catalogue.size, catalogue.map { it.id }.toSet().size)
        assertEquals(catalogue.size, catalogue.map { it.fileName }.toSet().size)
        assertEquals(catalogue.size, catalogue.map { it.sha256 }.toSet().size)
    }
}
