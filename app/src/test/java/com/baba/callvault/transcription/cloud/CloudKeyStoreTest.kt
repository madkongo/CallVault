/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.utils.LogRedaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The user's key (issue #48): stored sealed, kept out of the settings export, and scrubbed from logs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudKeyStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Robolectric has no AndroidKeyStore; a reversible scramble stands in, so "sealed" is checkable. */
    private val fakeCipher = object : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray().reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private val store get() = CloudKeyStore(context, fakeCipher)

    @Before
    fun clear() {
        context.getSharedPreferences(CloudKeyStore.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `a saved key reads back, trimmed, per service`() {
        store.set(TranscriptionService.GROQ, "  gsk_abcdefghijklmnop  ")
        assertEquals("gsk_abcdefghijklmnop", store.get(TranscriptionService.GROQ))
        assertNull(store.get(TranscriptionService.OPENROUTER))
        assertTrue(store.has(TranscriptionService.GROQ))
    }

    @Test
    fun `a blank key removes the saved one`() {
        store.set(TranscriptionService.GROQ, "gsk_abcdefghijklmnop")
        store.set(TranscriptionService.GROQ, "   ")
        assertNull(store.get(TranscriptionService.GROQ))
        assertFalse(store.has(TranscriptionService.GROQ))
    }

    @Test
    fun `the key is never stored in clear, and never in the main settings file`() {
        store.set(TranscriptionService.GROQ, "gsk_abcdefghijklmnop")
        val secrets = context.getSharedPreferences(CloudKeyStore.FILE_NAME, Context.MODE_PRIVATE).all
        assertTrue(secrets.values.none { it.toString().contains("gsk_abcdefghijklmnop") })
        val main = context.getSharedPreferences("callvault_prefs", Context.MODE_PRIVATE).all
        assertTrue(main.values.none { it.toString().contains("gsk_") })
    }

    @Test
    fun `a key that cannot be decrypted reads as no key`() {
        store.set(TranscriptionService.GROQ, "gsk_abcdefghijklmnop")
        val broken = CloudKeyStore(context, object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(sealed: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException()
        })
        assertNull(broken.get(TranscriptionService.GROQ))
    }

    @Test
    fun `settings export carries neither the key nor any where-to-transcribe setting`() {
        val prefs = AppPreferences(context)
        prefs.setTranscriptionService(TranscriptionService.CUSTOM)
        prefs.setTranscriptionCustomBaseUrl("https://private.example/v1")
        prefs.setTranscriptionCustomModel("whisper-1")
        prefs.setTranscriptionCloudMobileData(true)
        prefs.recordCloudConsent(TranscriptionService.CUSTOM)
        store.set(TranscriptionService.CUSTOM, "sk-abcdefghijklmnopqrst")

        val exported = prefs.exportToJson()

        listOf(
            "transcription_service", "transcription_cloud_mobile_data", "transcription_cloud_consent",
            "transcription_custom_base_url", "transcription_custom_model",
        ).forEach {
            assertFalse("$it must stay on this phone", it in AppPreferences.EXPORTABLE_KEYS)
            assertFalse("$it was exported", exported.contains(it))
        }
        assertFalse(exported.contains("sk-abcdefghijklmnopqrst"))
        assertFalse(exported.contains("private.example"))
    }

    @Test
    fun `api keys are scrubbed from log lines`() {
        val salt = "salt"
        assertEquals("key [KEY_REDACTED] set", LogRedaction.redact("key gsk_AbCdEf0123456789xyz set", salt))
        assertEquals("[KEY_REDACTED]", LogRedaction.redact("sk-or-v1-0123456789abcdef0123", salt))
        assertEquals("Authorization: [KEY_REDACTED]", LogRedaction.redact("Authorization: Bearer abc.def-123456", salt))
        // Idempotent, and ordinary words survive.
        assertEquals("[KEY_REDACTED]", LogRedaction.redact("[KEY_REDACTED]", salt))
        assertEquals("asked 12 ms, skipped", LogRedaction.redact("asked 12 ms, skipped", salt))
    }
}
