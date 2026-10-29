/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.baba.callvault.utils.AppLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets. Substituted in tests: Robolectric has no AndroidKeyStore. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM under a key that lives in the Android Keystore and never leaves it.
 *
 * The output is `iv || ciphertext+tag`. No new dependency: androidx.security is deprecated and not in
 * this app, and the platform API is all it wrapped.
 */
class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES) { "Sealed secret is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "callvault_cloud_transcription_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}

/**
 * The user's cloud transcription keys, one per service, encrypted at rest (issue #48).
 *
 * In a SharedPreferences file of their own — never `callvault_prefs` — so no settings export, debug
 * dump or future "copy all prefs" can reach them: the export walks an allow-list of keys in the main
 * file, and this file is not in it at all. The app has backups off (`allowBackup="false"`), and a
 * restored blob would be undecryptable anyway, since the Keystore key does not travel.
 *
 * Nothing here ever logs a key or its length.
 */
class CloudKeyStore(
    context: Context,
    private val cipher: SecretCipher = KeystoreSecretCipher(),
) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The saved key for [service], or null when there is none or it can no longer be decrypted. */
    fun get(service: TranscriptionService): String? {
        val stored = prefs.getString(prefKey(service), null) ?: return null
        return runCatching {
            cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.onFailure {
            // The class only. A Keystore reset after a lock-screen change lands here; the user re-enters it.
            AppLogger.w(TAG, "Saved ${service.key} key could not be read (${it.javaClass.simpleName})")
        }.getOrNull()
    }

    fun has(service: TranscriptionService): Boolean = prefs.contains(prefKey(service))

    /** Saves [key] for [service]; blank removes it. Surrounding whitespace from a paste is dropped. */
    fun set(service: TranscriptionService, key: String?) {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.edit { remove(prefKey(service)) }
            return
        }
        val sealed = Base64.encodeToString(cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit { putString(prefKey(service), sealed) }
    }

    private fun prefKey(service: TranscriptionService) = "key_${service.key}"

    companion object {
        /** Separate from the main settings file on purpose — see the class comment. */
        const val FILE_NAME = "callvault_cloud_secrets"
        private const val TAG = "CV:CloudKeys"
    }
}
