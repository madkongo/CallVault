/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context
import android.content.pm.PackageManager
import com.baba.callvault.utils.AppLogger
import java.io.File
import java.security.MessageDigest

/**
 * Verifies a downloaded update APK BEFORE it is handed to any installer:
 *  1. it is a readable APK for OUR package name,
 *  2. its versionCode is strictly higher than the running app's,
 *  3. its signing certificate matches the pinned release certificate.
 *
 * Android itself refuses mismatched-cert updates, but verifying first fails cleanly (with a clear
 * log + user-facing error) instead of a confusing system installer failure — and protects the
 * silent shell-install path, which would otherwise happily run `pm install` on anything.
 */
object ApkVerifier {

    private const val TAG = "CV:ApkVerifier"

    /**
     * SHA-256 digest of the CallVault release signing certificate — the same cert every published
     * release (v1.1.0+) is signed with. An update APK signed with anything else is rejected.
     */
    private const val PINNED_CERT_SHA256 =
        "c875ffd0122aa6baceca1826eeb6c6ecbabe023ed551f0be856e8b7a80f285ea"

    /** True when [apkFile] is a genuine CallVault update: right package, newer, right signature. */
    fun isValidUpdate(context: Context, apkFile: File): Boolean {
        if (!apkFile.isFile || apkFile.length() == 0L) {
            AppLogger.w(TAG, "Update APK missing or empty: ${apkFile.path}")
            return false
        }
        val packageManager = context.packageManager
        val info = runCatching {
            @Suppress("DEPRECATION") // (Path-based overload; the flags variant needs API 33+)
            packageManager.getPackageArchiveInfo(apkFile.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()
        if (info == null) {
            AppLogger.w(TAG, "Update APK could not be parsed")
            return false
        }

        if (info.packageName != context.packageName) {
            AppLogger.w(TAG, "Update APK package mismatch: ${info.packageName}")
            return false
        }

        val installedVersionCode = runCatching {
            packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(Long.MAX_VALUE)
        if (info.longVersionCode <= installedVersionCode) {
            AppLogger.w(TAG, "Update APK is not newer: ${info.longVersionCode} <= $installedVersionCode")
            return false
        }

        val signers = info.signingInfo?.apkContentsSigners
        if (signers.isNullOrEmpty()) {
            AppLogger.w(TAG, "Update APK has no readable signature")
            return false
        }
        val matches = signers.any { sha256Hex(it.toByteArray()) == PINNED_CERT_SHA256 }
        if (!matches) {
            AppLogger.e(TAG, "Update APK signing certificate does NOT match the pinned release cert — refusing to install")
        }
        return matches
    }

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
