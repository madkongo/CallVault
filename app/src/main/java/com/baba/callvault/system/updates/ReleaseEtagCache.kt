/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.content.Context

/**
 * The last release reply and its ETag, so the update check can ask "has it changed?" (If-None-Match) and a 304 costs
 * nothing and does not count against GitHub's rate limit (review R26). Kept in its own preferences file, not
 * AppPreferences. Remembers the URL it was stored for: a test override URL never reuses the real one's validator.
 */
class ReleaseEtagCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Entry(val etag: String, val body: String)

    fun get(url: String): Entry? {
        if (prefs.getString(KEY_URL, null) != url) return null
        val etag = prefs.getString(KEY_ETAG, null) ?: return null
        val body = prefs.getString(KEY_BODY, null) ?: return null
        return Entry(etag, body)
    }

    fun put(url: String, etag: String, body: String) {
        prefs.edit().putString(KEY_URL, url).putString(KEY_ETAG, etag).putString(KEY_BODY, body).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "github_release_etag"
        const val KEY_URL = "url"
        const val KEY_ETAG = "etag"
        const val KEY_BODY = "body"
    }
}
