/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.aftercall

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.baba.callvault.utils.AppLogger
import org.json.JSONObject

/**
 * The recordings still waiting for a keep-or-delete answer, keyed by display name.
 *
 * Its own SharedPreferences file rather than a table: the entries are few, short-lived, and must be readable
 * from a receiver or a worker in a cold process without opening a database. Every write is a `commit()`, not
 * an `apply()`: the hold is written as the recorder service is being torn down, and an entry lost to process
 * death would let the nightly sweep copy a recording to Drive that the user was about to delete.
 *
 * Holds nothing but what the held hand-offs need; the only contact text in it is the display name key, which
 * is already the file's name on disk. Never throws: a store that cannot be read reads as "nothing held", which
 * fails towards keeping (the pre-feature behaviour) rather than towards losing a recording.
 */
object AfterCallDecisions {

    private const val TAG = "CV:AfterCall"
    private const val PREFS_NAME = "cv_after_call_decisions"

    private const val FIELD_URI = "uri"
    private const val FIELD_MIME = "mime"
    private const val FIELD_ASKED_AT = "askedAt"
    private const val FIELD_VOIP = "voip"

    /** Serialises read-modify-write between the receiver thread, workers and the finalize coroutine. */
    private val lock = Any()

    /** One recording waiting for its answer. */
    data class Held(
        val displayName: String,
        val uri: Uri,
        val mimeType: String,
        val askedAt: Long,
        val isVoip: Boolean,
    )

    /** Marks [displayName] as waiting for an answer. Returns whether the entry was stored. */
    fun hold(
        context: Context,
        displayName: String,
        uri: Uri,
        mimeType: String,
        isVoip: Boolean,
        askedAt: Long = System.currentTimeMillis(),
    ): Boolean = runCatching {
        val value = JSONObject()
            .put(FIELD_URI, uri.toString())
            .put(FIELD_MIME, mimeType)
            .put(FIELD_ASKED_AT, askedAt)
            .put(FIELD_VOIP, isVoip)
            .toString()
        synchronized(lock) { commit(context) { putString(displayName, value) } }
    }.getOrElse {
        AppLogger.w(TAG, "Could not hold a recording for its answer: ${it.message}")
        false
    }

    /** The waiting entry for [displayName], or null when it is not waiting (or cannot be read). */
    fun peek(context: Context, displayName: String): Held? = runCatching {
        prefs(context).getString(displayName, null)?.let { parse(displayName, it) }
    }.getOrNull()

    /**
     * Ends the wait for [displayName] and returns the entry that was waiting, or null when there was none.
     *
     * Read and removed under one lock, so two answers arriving together (the notification and the pop-up, or
     * a tap racing the 24-hour check) cannot both act on the same recording: exactly one gets the entry.
     */
    fun release(context: Context, displayName: String): Held? = runCatching {
        synchronized(lock) {
            val held = peek(context, displayName) ?: return@synchronized null
            if (commit(context) { remove(displayName) }) held else null
        }
    }.getOrElse {
        AppLogger.w(TAG, "Could not release a held recording: ${it.message}")
        null
    }

    /** Every display name still waiting. Empty when none are, or the store cannot be read. */
    fun pendingNames(context: Context): Set<String> = runCatching {
        prefs(context).all.keys.toSet()
    }.getOrElse { emptySet() }

    private fun parse(displayName: String, raw: String): Held? = runCatching {
        val json = JSONObject(raw)
        Held(
            displayName = displayName,
            uri = json.getString(FIELD_URI).toUri(),
            mimeType = json.getString(FIELD_MIME),
            askedAt = json.optLong(FIELD_ASKED_AT, 0L),
            isVoip = json.optBoolean(FIELD_VOIP, false),
        )
    }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @SuppressLint("ApplySharedPref") // Deliberate: see the class KDoc — these must survive process death.
    private inline fun commit(
        context: Context,
        block: android.content.SharedPreferences.Editor.() -> Unit,
    ): Boolean = prefs(context).edit().apply(block).commit()
}
