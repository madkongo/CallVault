/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.spike

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x509.X509Name
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * SpikeAdbManager provides a persisted cryptographic identity (RSA-2048 key + self-signed X.509
 * certificate) for in-app ADB wireless connections.
 *
 * On first use the keypair and certificate are generated and written to the app's private
 * [Context.getFilesDir]. On subsequent calls they are loaded from disk so the same identity is
 * presented to the ADB daemon across process death and reboots — ADB remembers authorised public
 * keys per client identity, so stability of the identity matters.
 *
 * Files created under [Context.getFilesDir]:
 *   - `adbkey`      — raw PKCS#8-encoded RSA private key bytes
 *   - `adbkey.pem`  — Base64-encoded DER certificate, PEM-wrapped
 *
 * Abstract methods required by [AbsAdbConnectionManager] (confirmed via javap on version 3.1.1):
 *   - [getPrivateKey]  → RSA private key used to sign ADB auth tokens
 *   - [getCertificate] → X.509 certificate wrapping the public key for TLS
 *   - [getDeviceName]  → human-readable label shown in the ADB authorisation dialog
 */
class SpikeAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        // Tell the library which Android API level we're running on; it selects TLS vs plain TCP.
        setApi(Build.VERSION.SDK_INT)

        val appContext = context.applicationContext
        val loadedKey = loadPrivateKey(appContext)
        val loadedCert = loadCertificate(appContext)

        if (loadedKey != null && loadedCert != null) {
            Log.d(TAG, "Loaded persisted ADB identity from ${appContext.filesDir}")
            privateKey = loadedKey
            certificate = loadedCert
        } else {
            Log.i(TAG, "No valid persisted identity found — generating new RSA-2048 keypair + self-signed cert")
            val (key, cert) = generateAndPersistIdentity(appContext)
            privateKey = key
            certificate = cert
            Log.i(TAG, "New ADB identity generated and persisted to ${appContext.filesDir}")
        }
    }

    // ---- AbsAdbConnectionManager abstract method implementations ----

    /** Returns the RSA private key used by the library to sign ADB authentication tokens. */
    override fun getPrivateKey(): PrivateKey = privateKey

    /**
     * Returns the X.509 certificate wrapping the RSA public key.
     * Used by the library during TLS handshake (Android 11+ / adb over WiFi).
     */
    override fun getCertificate(): Certificate = certificate

    /** Human-readable device name shown in the ADB authorisation dialog on the target device. */
    override fun getDeviceName(): String = DEVICE_NAME

    // ---- Singleton ----

    companion object {
        private const val TAG = "SCR:SpikeAdbManager"

        /** Name shown in the ADB "Allow USB debugging?" / wireless pairing dialog. */
        private const val DEVICE_NAME = "CallVault-Spike"

        private const val PRIVATE_KEY_FILE = "adbkey"
        private const val CERTIFICATE_FILE = "adbkey.pem"

        private const val CERT_SUBJECT = "CN=CallVault-Spike"
        private const val CERT_ALGO = "SHA512withRSA"

        /** 10-year validity so the same identity survives reinstalls / long gaps between uses. */
        private const val CERT_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: SpikeAdbManager? = null

        /**
         * Returns the thread-safe singleton [SpikeAdbManager].
         *
         * Uses double-checked locking; always stores [applicationContext] internally to avoid
         * Activity leaks.
         *
         * @param context Any [Context] — applicationContext is used internally.
         * @throws Exception if key/cert generation fails on first call.
         */
        fun getInstance(context: Context): SpikeAdbManager =
            instance ?: synchronized(this) {
                instance ?: SpikeAdbManager(context.applicationContext).also { instance = it }
            }

        // ---- Key + cert persistence helpers ----

        private fun loadPrivateKey(context: Context): PrivateKey? {
            val file = File(context.filesDir, PRIVATE_KEY_FILE)
            if (!file.exists()) return null
            return runCatching {
                val bytes = FileInputStream(file).use { it.readBytes() }
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
            }.getOrElse { e ->
                Log.w(TAG, "Failed to load persisted private key — will regenerate: ${e.message}")
                null
            }
        }

        private fun loadCertificate(context: Context): Certificate? {
            val file = File(context.filesDir, CERTIFICATE_FILE)
            if (!file.exists()) return null
            return runCatching {
                FileInputStream(file).use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            }.getOrElse { e ->
                Log.w(TAG, "Failed to load persisted certificate — will regenerate: ${e.message}")
                null
            }
        }

        /**
         * Generates a fresh RSA-2048 keypair and a self-signed X.509 certificate, then writes
         * both to [Context.getFilesDir].
         *
         * Uses BouncyCastle's [X509V3CertificateGenerator] — bcprov-jdk15to18 is guaranteed
         * present at runtime as a transitive dependency of libadb-android.
         *
         * @return a [Pair] of (privateKey, certificate).
         */
        private fun generateAndPersistIdentity(context: Context): Pair<PrivateKey, Certificate> {
            // 1. Generate RSA-2048 keypair.
            val keyPairGen = KeyPairGenerator.getInstance("RSA")
            keyPairGen.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val keyPair = keyPairGen.generateKeyPair()
            val privateKey = keyPair.private
            val publicKey = keyPair.public

            // 2. Build a self-signed X.509 v3 certificate using BouncyCastle.
            //    bcprov-jdk15to18 is a runtime transitive dep of libadb-android (v1.81).
            val now = Date()
            val notAfter = Date(System.currentTimeMillis() + CERT_VALIDITY_MS)
            @Suppress("DEPRECATION")
            val dn = X509Name(CERT_SUBJECT)
            @Suppress("DEPRECATION")
            val certGen = X509V3CertificateGenerator().apply {
                setSerialNumber(BigInteger.valueOf(SecureRandom().nextLong() and Long.MAX_VALUE))
                setIssuerDN(dn)
                setSubjectDN(dn)
                setNotBefore(now)
                setNotAfter(notAfter)
                setPublicKey(publicKey)
                setSignatureAlgorithm(CERT_ALGO)
            }
            @Suppress("DEPRECATION")
            val certificate = certGen.generate(privateKey)

            // 3. Persist private key as raw PKCS#8 bytes.
            FileOutputStream(File(context.filesDir, PRIVATE_KEY_FILE)).use {
                it.write(privateKey.encoded)
            }

            // 4. Persist certificate in PEM format (Base64-encoded DER with standard headers).
            val pemBody = Base64.encodeToString(certificate.encoded, Base64.DEFAULT)
            FileOutputStream(File(context.filesDir, CERTIFICATE_FILE)).use { os ->
                os.write("-----BEGIN CERTIFICATE-----\n".toByteArray(Charsets.UTF_8))
                os.write(pemBody.toByteArray(Charsets.UTF_8))
                os.write("-----END CERTIFICATE-----\n".toByteArray(Charsets.UTF_8))
            }

            return Pair(privateKey, certificate)
        }
    }
}
