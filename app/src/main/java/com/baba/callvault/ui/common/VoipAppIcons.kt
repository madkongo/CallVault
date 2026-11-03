/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.baba.callvault.utils.AppLogger

/**
 * Icons for the app a VoIP recording came from, loaded from the **installed app itself**.
 *
 * Deliberately not bundled artwork: WhatsApp, Signal and Telegram logos are trademarks, and shipping
 * them in a FOSS app is a licensing problem we do not need. Reading the icon the user already has
 * installed avoids that entirely, and stays correct when an app rebrands.
 *
 * VoIP recordings carry the app's *label* in their filename rather than its package (the daemon cannot
 * resolve a label — that needs a Context it must not obtain), so the label is matched back to an
 * installed package once and cached. Both the label→package map and the decoded icons are cached
 * because this is consulted while scrolling a list.
 */
object VoipAppIcons {
    private const val TAG = "CV:VoipIcons"
    private const val ICON_PX = 96

    /**
     * How long a miss is trusted. An app installed while CallVault runs (Telegram installed at 10:00, its
     * first call at 10:05, OP9 2026-11-02) is missing from a list built earlier; after this long a miss
     * rebuilds the list once and tries again, so the badge appears without a restart.
     */
    private const val MISS_RETRY_MS = 30_000L

    private val iconCache = HashMap<String, ImageBitmap?>()
    private val missedAt = HashMap<String, Long>()
    @Volatile private var labelToPackage: Map<String, String>? = null
    private var builtAt = 0L

    /** The installed app's icon for [label] (e.g. "WhatsApp"), or null if it can't be resolved. */
    @Synchronized
    fun iconFor(context: Context, label: String): ImageBitmap? {
        val key = label.lowercase()
        val now = SystemClock.elapsedRealtime()
        if (iconCache.containsKey(key)) {
            val cached = iconCache[key]
            val missAge = now - (missedAt[key] ?: now)
            if (cached != null || missAge < MISS_RETRY_MS) return cached
            // An old miss: the app may have been installed since. Rebuild the list if it predates the miss.
            iconCache.remove(key)
            if (now - builtAt >= MISS_RETRY_MS) labelToPackage = null
        }

        val icon = runCatching {
            val pkg = packageForLabel(context, key) ?: return@runCatching null
            val pm = context.packageManager
            pm.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        }.onFailure { AppLogger.d(TAG, "No icon for '$label': ${it.message}") }.getOrNull()

        iconCache[key] = icon   // cache misses too, so a missing app isn't re-scanned per frame
        if (icon == null) missedAt[key] = now else missedAt.remove(key)
        return icon
    }

    /**
     * Builds (once) a map of installed application labels to package names. The list is only walked on
     * the first miss; an app installed later is picked up after [invalidate] or a process restart,
     * which is an acceptable trade for not scanning every package on every row.
     */
    private fun packageForLabel(context: Context, lowercaseLabel: String): String? {
        val map = labelToPackage ?: runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA).associateBy(
                { pm.getApplicationLabel(it).toString().lowercase() },
                { it.packageName },
            )
        }.onFailure { AppLogger.d(TAG, "Could not enumerate installed apps: ${it.message}") }
            .getOrDefault(emptyMap())
            .also {
                labelToPackage = it
                builtAt = SystemClock.elapsedRealtime()
            }
        return map[lowercaseLabel]
    }

    /** Drops the caches, e.g. after an app is installed or removed. */
    @Synchronized
    fun invalidate() {
        iconCache.clear()
        missedAt.clear()
        labelToPackage = null
    }
}
