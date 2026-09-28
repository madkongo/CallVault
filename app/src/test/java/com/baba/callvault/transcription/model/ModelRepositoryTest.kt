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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Model files are hundreds of megabytes fetched over the network, and ggml's failure mode on a
 * corrupt file is a crash inside native code rather than an exception. So a model is only ever
 * treated as usable once its digest matches, and a partial download must never be mistaken for one.
 */
class ModelRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun rejects_a_file_whose_digest_does_not_match() {
        // Arrange
        val file = tempFolder.newFile("model.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        // Act
        val ok = ModelRepository.verify(file, expectedSha256 = "de".repeat(32))

        // Assert
        assertFalse("a corrupt model must never be accepted", ok)
    }

    @Test
    fun accepts_a_file_whose_digest_matches() {
        // Arrange
        val bytes = "hello whisper".toByteArray()
        val file = tempFolder.newFile("model.bin").apply { writeBytes(bytes) }

        // Act / Assert
        assertTrue(ModelRepository.verify(file, expectedSha256 = sha256Of(bytes)))
    }

    @Test
    fun digest_comparison_ignores_case() {
        // Published digests are lower case; a pasted upper-case constant should still work rather
        // than failing every download for a reason nobody would guess.
        val bytes = "hello whisper".toByteArray()
        val file = tempFolder.newFile("model.bin").apply { writeBytes(bytes) }

        assertTrue(ModelRepository.verify(file, expectedSha256 = sha256Of(bytes).uppercase()))
    }

    @Test
    fun a_missing_file_does_not_verify_and_does_not_throw() {
        assertFalse(ModelRepository.verify(File(tempFolder.root, "absent.bin"), "de".repeat(32)))
    }

    @Test
    fun a_truncated_download_does_not_count_as_installed() {
        // The case this exists for: a killed download leaves a short file. Size is checked rather
        // than the digest because hashing 574 MB on every query would stall the UI.
        val dir = tempFolder.newFolder("models")
        File(dir, TranscriptionModel.SMALL_Q5_1.fileName).writeBytes(byteArrayOf(0))

        assertFalse(ModelRepository.isInstalled(dir, TranscriptionModel.SMALL_Q5_1))
    }

    @Test
    fun an_absent_model_is_not_installed() {
        val dir = tempFolder.newFolder("models")

        assertFalse(ModelRepository.isInstalled(dir, TranscriptionModel.SMALL_Q5_1))
        assertNull(ModelRepository.pathFor(dir, TranscriptionModel.SMALL_Q5_1))
    }

    @Test
    fun a_complete_model_is_installed_and_resolvable() {
        // Arrange — a stand-in of exactly the published length.
        val dir = tempFolder.newFolder("models")
        val model = TranscriptionModel.SMALL_Q5_1
        File(dir, model.fileName).writeBytes(ByteArray(model.sizeBytes.toInt().coerceAtMost(1024)))

        // Act / Assert — with a shrunken expectation so the test does not write 190 MB.
        assertTrue(ModelRepository.isInstalled(dir, model, expectedSize = 1024L))
        assertEquals(File(dir, model.fileName), ModelRepository.pathFor(dir, model, expectedSize = 1024L))
    }

    @Test
    fun finalising_a_good_download_installs_it_and_removes_the_part_file() {
        // Arrange
        val dir = tempFolder.newFolder("models")
        val model = TranscriptionModel.SMALL_Q5_1
        val bytes = "a complete model".toByteArray()
        val part = File(dir, model.fileName + ModelRepository.PART_SUFFIX).apply { writeBytes(bytes) }

        // Act
        val ok = ModelRepository.finalizeDownload(dir, model, expectedSha256 = sha256Of(bytes))

        // Assert
        assertTrue("finalise reported failure for a good download", ok)
        assertFalse("the .part file was left behind", part.exists())
        assertTrue(File(dir, model.fileName).isFile)
    }

    @Test
    fun finalising_a_corrupt_download_deletes_it_and_installs_nothing() {
        // The important one. A corrupt model must not be renamed into place, because ggml crashes in
        // native code on a bad file rather than returning an error we could report.
        val dir = tempFolder.newFolder("models")
        val model = TranscriptionModel.SMALL_Q5_1
        val part = File(dir, model.fileName + ModelRepository.PART_SUFFIX)
            .apply { writeBytes("truncated".toByteArray()) }

        // Act
        val ok = ModelRepository.finalizeDownload(dir, model, expectedSha256 = "de".repeat(32))

        // Assert
        assertFalse(ok)
        assertFalse("a corrupt download was kept for a later retry to trip over", part.exists())
        assertFalse(File(dir, model.fileName).exists())
    }

    @Test
    fun finalising_with_no_download_present_reports_failure() {
        val dir = tempFolder.newFolder("models")

        assertFalse(ModelRepository.finalizeDownload(dir, TranscriptionModel.SMALL_Q5_1))
    }

    @Test
    fun every_model_declares_a_plausible_digest_and_size() {
        // Guards against a placeholder digest shipping: a wrong constant makes every download fail
        // verification, and the symptom (endless retries) does not point at the cause.
        TranscriptionModel.entries.forEach { model ->
            assertEquals("${model.id} digest is not 64 hex chars", 64, model.sha256.length)
            assertTrue("${model.id} digest is not hex", model.sha256.all { it in "0123456789abcdef" })
            assertTrue("${model.id} has an implausible size", model.sizeBytes > 1_000_000L)
            assertTrue("${model.id} url does not end in its file name", model.url.endsWith(model.fileName))
        }
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- what a new attempt finds on disk ---------------------------------------------------------

    private val leftoverModel = object : DownloadableModel {
        override val id = "test-model"
        override val fileName = "test-model.bin"
        override val url = "https://example.invalid/test-model.bin"
        override val sha256 = sha256Of("0123456789".toByteArray())
        override val sizeBytes = 10L
    }

    private fun part(bytes: ByteArray) =
        File(tempFolder.root, leftoverModel.fileName + ModelRepository.PART_SUFFIX).apply { writeBytes(bytes) }

    @Test
    fun nothing_on_disk_starts_a_fresh_download() {
        assertEquals(ModelRepository.Leftover.None, ModelRepository.settleLeftover(tempFolder.root, leftoverModel))
    }

    @Test
    fun a_partial_file_is_resumed_from_its_length() {
        part("01234".toByteArray())
        assertEquals(ModelRepository.Leftover.Resume(5L), ModelRepository.settleLeftover(tempFolder.root, leftoverModel))
    }

    @Test
    fun a_leftover_longer_than_the_model_is_deleted() {
        val f = part("0123456789ABC".toByteArray())
        assertEquals(ModelRepository.Leftover.Discarded, ModelRepository.settleLeftover(tempFolder.root, leftoverModel))
        assertFalse(f.exists())
    }

    @Test
    fun a_complete_leftover_that_verifies_is_installed_without_downloading() {
        part("0123456789".toByteArray())
        assertEquals(ModelRepository.Leftover.Installed, ModelRepository.settleLeftover(tempFolder.root, leftoverModel))
        assertTrue(ModelRepository.isInstalled(tempFolder.root, leftoverModel))
    }

    @Test
    fun a_complete_leftover_that_is_damaged_is_deleted_before_the_new_download() {
        // The state the report describes: a full-length file with bad bytes in it. Resuming from it would
        // fetch nothing and fail the digest again, every time.
        val f = part("0123456780".toByteArray())
        assertEquals(ModelRepository.Leftover.Discarded, ModelRepository.settleLeftover(tempFolder.root, leftoverModel))
        assertFalse(f.exists())
    }
}

