/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import com.baba.callvault.utils.AppLogger
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal GitHub Releases client for the in-app updater. Unauthenticated (public repo; the
 * 60 req/hour/IP limit is far above the daily check cadence), no extra dependencies.
 */
object GitHubReleases {

    private const val TAG = "CV:GitHubReleases"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/madkongo/CallVault/releases/latest"

    /** The release page users can open when an automatic path fails. */
    const val RELEASES_PAGE_URL = "https://github.com/madkongo/CallVault/releases/latest"

    private const val APK_ASSET_NAME = "CallVault.apk"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * @param tag          The release tag, e.g. "v1.2.4".
     * @param apkUrl       Direct download URL of the [APK_ASSET_NAME] asset.
     * @param apkSizeBytes Advertised asset size, used as a sanity check after download.
     */
    data class ReleaseInfo(val tag: String, val apkUrl: String, val apkSizeBytes: Long)

    /**
     * Fetches and parses the latest release. Blocking network I/O — call from a worker thread.
     * @return The release info, or null on any failure (network, parse, no APK asset).
     */
    fun fetchLatestRelease(): ReleaseInfo? = runCatching {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                AppLogger.w(TAG, "Latest-release query failed: HTTP ${connection.responseCode}")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { e ->
        AppLogger.w(TAG, "Latest-release query failed: ${e.message}")
        null
    }?.let { parseLatestRelease(it) }

    /**
     * Parses a GitHub "latest release" JSON payload. Drafts and prereleases return null (the
     * /latest endpoint should never serve them, but a manual check costs nothing).
     */
    fun parseLatestRelease(json: String): ReleaseInfo? = runCatching {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val tag = root.optString("tag_name").ifBlank { return null }
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") == APK_ASSET_NAME) {
                val url = asset.optString("browser_download_url").ifBlank { return null }
                return ReleaseInfo(tag = tag, apkUrl = url, apkSizeBytes = asset.optLong("size"))
            }
        }
        null
    }.getOrElse { e ->
        AppLogger.w(TAG, "Failed to parse latest-release JSON: ${e.message}")
        null
    }

    /**
     * Downloads [release]'s APK to [destination] (replacing any previous file). Blocking; call
     * from a worker thread. The advertised size is verified when the release reports one.
     * @return true when the file was fully written (and size-checked).
     */
    fun downloadApk(release: ReleaseInfo, destination: File): Boolean = runCatching {
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                AppLogger.w(TAG, "APK download failed: HTTP ${connection.responseCode}")
                return false
            }
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        val sizeOk = release.apkSizeBytes <= 0L || destination.length() == release.apkSizeBytes
        if (!sizeOk) {
            AppLogger.w(TAG, "APK download size mismatch: got ${destination.length()}, expected ${release.apkSizeBytes}")
            destination.delete()
        }
        sizeOk
    }.getOrElse { e ->
        AppLogger.w(TAG, "APK download failed: ${e.message}")
        runCatching { destination.delete() }
        false
    }
}
