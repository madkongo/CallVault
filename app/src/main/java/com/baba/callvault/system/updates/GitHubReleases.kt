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

    /** Download attempts before giving up. Each attempt RESUMES from the bytes already on disk. */
    private const val MAX_DOWNLOAD_ATTEMPTS = 6
    /** Backoff before a resume attempt, so a flapping connection isn't hammered. */
    private const val RETRY_DELAY_MS = 2_000L

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
                // Refuse a cleartext download URL — the pinned-cert check is the integrity gate, but
                // the transport must still be TLS so the bytes can't be tampered in flight.
                if (!url.startsWith("https://")) return null
                // A missing/zero advertised size means we can't sanity-check the download; treat as
                // untrustworthy metadata rather than skipping the check.
                val size = asset.optLong("size")
                if (size <= 0L) return null
                return ReleaseInfo(tag = tag, apkUrl = url, apkSizeBytes = size)
            }
        }
        null
    }.getOrElse { e ->
        AppLogger.w(TAG, "Failed to parse latest-release JSON: ${e.message}")
        null
    }

    /**
     * Downloads [release]'s APK to [destination]. Blocking; call from a worker thread.
     *
     * RESUMABLE + retried: a large APK over a flaky mobile network commonly stalls or drops. Instead
     * of restarting the whole ~80 MB transfer, each of up to [MAX_DOWNLOAD_ATTEMPTS] attempts RESUMES
     * from the bytes already on disk via an HTTP `Range` request, so partial progress is never lost
     * (across a stall, a dropped connection, or even a worker rerun — the partial file persists).
     * A pre-existing file larger than the target, or a server that ignores `Range` (200 instead of
     * 206), triggers a clean restart. Verified against the advertised size on completion.
     *
     * @param onProgress invoked with the download percentage (0-100), throttled to whole-percent
     *                   changes; resumes report against bytes-already-on-disk so the bar never resets.
     * @return true when the file was fully written (and size-checked).
     */
    fun downloadApk(release: ReleaseInfo, destination: File, onProgress: (Int) -> Unit = {}): Boolean {
        val total = release.apkSizeBytes
        destination.parentFile?.mkdirs()
        // A stale partial larger than the target can't be resumed correctly — start clean.
        if (destination.exists() && destination.length() > total) {
            runCatching { destination.delete() }
        }

        repeat(MAX_DOWNLOAD_ATTEMPTS) { attempt ->
            val alreadyHave = if (destination.exists()) destination.length() else 0L
            if (alreadyHave == total) {
                onProgress(100)
                return true
            }
            val ok = runCatching { downloadAttempt(release, destination, alreadyHave, total, onProgress) }
                .getOrElse { e ->
                    AppLogger.w(TAG, "Download attempt ${attempt + 1}/$MAX_DOWNLOAD_ATTEMPTS failed: ${e.message}")
                    false
                }
            if (ok && destination.length() == total) return true
            // Keep the partial file for the next attempt to resume from; back off briefly first.
            if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) runCatching { Thread.sleep(RETRY_DELAY_MS) }
        }

        AppLogger.w(TAG, "APK download gave up after $MAX_DOWNLOAD_ATTEMPTS attempts (have ${destination.length()}/$total)")
        return false
    }

    /**
     * One download attempt, resuming from [alreadyHave] bytes. Returns true only if the transfer
     * reached [total]; throws on network errors (the caller retries). Appends on a 206 resume;
     * restarts (truncates) if the server returns 200 (ignored the Range) or [alreadyHave] is 0.
     */
    private fun downloadAttempt(
        release: ReleaseInfo,
        destination: File,
        alreadyHave: Long,
        total: Long,
        onProgress: (Int) -> Unit
    ): Boolean {
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (alreadyHave > 0L) setRequestProperty("Range", "bytes=$alreadyHave-")
        }
        try {
            val code = connection.responseCode
            // 206 = server honoured the Range (append); 200 = full body (must restart from 0).
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                AppLogger.w(TAG, "APK download HTTP $code")
                return false
            }
            var written = if (resuming) alreadyHave else 0L
            var lastPercent = -1
            if (total > 0L) onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
            connection.inputStream.use { input ->
                java.io.FileOutputStream(destination, resuming).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0L) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            connection.disconnect()
        }
        return destination.length() == total
    }
}
