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
import android.content.SharedPreferences
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

    /** The notification-id counter lives apart, so every key of [PREFS_NAME] is a held display name. */
    private const val IDS_PREFS_NAME = "cv_after_call_ids"
    private const val KEY_LAST_ID = "last_notification_id"

    private const val FIELD_URI = "uri"
    private const val FIELD_MIME = "mime"
    private const val FIELD_ASKED_AT = "askedAt"
    private const val FIELD_VOIP = "voip"
    private const val FIELD_NOTIFICATION_ID = "notificationId"
    private const val FIELD_DELETE_REQUESTED = "deleteRequested"
    private const val FIELD_DISMISSED = "dismissed"

    /** Serialises read-modify-write between the receiver thread, workers and the finalize coroutine. */
    private val lock = Any()

    /** One recording waiting for its answer. */
    data class Held(
        val displayName: String,
        val uri: Uri,
        val mimeType: String,
        val askedAt: Long,
        val isVoip: Boolean,
        /** Allocated at hold time; the question and its "deleted" notice both use it. */
        val notificationId: Int = AfterCallPolicy.NOTIFICATION_ID_BASE,
        /** Delete was tapped and its Undo window is running: the 24-hour check must delete, never keep. */
        val deleteRequested: Boolean = false,
        /** The question was swiped away; startup does not post it again. */
        val dismissed: Boolean = false,
    )

    /**
     * Marks [displayName] as waiting for an answer, with a freshly allocated notification id.
     * Returns the stored entry, or null when it could not be stored (the caller then keeps the recording).
     */
    fun hold(
        context: Context,
        displayName: String,
        uri: Uri,
        mimeType: String,
        isVoip: Boolean,
        askedAt: Long = System.currentTimeMillis(),
    ): Held? = runCatching {
        synchronized(lock) {
            val held = Held(displayName, uri, mimeType, askedAt, isVoip, allocateNotificationId(context))
            if (write(context, held)) held else null
        }
    }.getOrElse {
        AppLogger.w(TAG, "Could not hold a recording for its answer: ${it.message}")
        null
    }

    /** The waiting entry for [displayName], or null when it is not waiting (or cannot be read). */
    fun peek(context: Context, displayName: String): Held? = runCatching {
        prefs(context).getString(displayName, null)?.let { parse(displayName, it) }
    }.getOrNull()

    /** Every waiting entry that can be read. */
    fun all(context: Context): List<Held> = pendingNames(context).mapNotNull { peek(context, it) }

    /**
     * Ends the wait for [displayName] and returns the entry that was waiting, or null when there was none.
     *
     * Read and removed under one lock, so two answers arriving together (the notification and the pop-up, or
     * a tap racing the 24-hour check) cannot both act on the same recording: exactly one gets the entry.
     */
    fun release(context: Context, displayName: String): Held? = runCatching {
        synchronized(lock) {
            val held = peek(context, displayName) ?: return@synchronized null
            if (commit(prefs(context)) { remove(displayName) }) held else null
        }
    }.getOrElse {
        AppLogger.w(TAG, "Could not release a held recording: ${it.message}")
        null
    }

    /** Sets or clears "Delete was tapped". Returns the updated entry, or null when it is not waiting. */
    fun setDeleteRequested(context: Context, displayName: String, requested: Boolean): Held? =
        update(context, displayName) { it.copy(deleteRequested = requested) }

    /** Records that the question was swiped away. Returns the updated entry, or null when it is not waiting. */
    fun setDismissed(context: Context, displayName: String): Held? =
        update(context, displayName) { it.copy(dismissed = true) }

    /**
     * The recording the pop-up should ask about: the oldest one still waiting for an answer. One with Delete
     * tapped is not waiting — it is in its Undo window, and the notification carries that.
     */
    fun nextToAsk(context: Context): Held? = all(context).filterNot { it.deleteRequested }.minByOrNull { it.askedAt }

    /**
     * Follows every change to the waiting set, so the pop-up closes when the notification answers first.
     * SharedPreferences holds listeners weakly: the caller must keep [listener] referenced while registered.
     */
    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)

    /** Every display name still waiting. Empty when none are, or the store cannot be read. */
    fun pendingNames(context: Context): Set<String> = runCatching {
        prefs(context).all.keys.toSet()
    }.getOrElse { emptySet() }

    private fun update(context: Context, displayName: String, change: (Held) -> Held): Held? = runCatching {
        synchronized(lock) {
            val held = peek(context, displayName) ?: return@synchronized null
            val updated = change(held)
            if (write(context, updated)) updated else null
        }
    }.getOrElse {
        AppLogger.w(TAG, "Could not update a held recording: ${it.message}")
        null
    }

    /** Called under [lock]. Committed before it is used, so a restart can never hand the same id out twice. */
    private fun allocateNotificationId(context: Context): Int {
        val ids = context.getSharedPreferences(IDS_PREFS_NAME, Context.MODE_PRIVATE)
        val last = if (ids.contains(KEY_LAST_ID)) ids.getInt(KEY_LAST_ID, 0) else null
        val next = AfterCallPolicy.nextNotificationId(last)
        commit(ids) { putInt(KEY_LAST_ID, next) }
        return next
    }

    private fun write(context: Context, held: Held): Boolean {
        val value = JSONObject()
            .put(FIELD_URI, held.uri.toString())
            .put(FIELD_MIME, held.mimeType)
            .put(FIELD_ASKED_AT, held.askedAt)
            .put(FIELD_VOIP, held.isVoip)
            .put(FIELD_NOTIFICATION_ID, held.notificationId)
            .put(FIELD_DELETE_REQUESTED, held.deleteRequested)
            .put(FIELD_DISMISSED, held.dismissed)
            .toString()
        return commit(prefs(context)) { putString(held.displayName, value) }
    }

    private fun parse(displayName: String, raw: String): Held? = runCatching {
        val json = JSONObject(raw)
        Held(
            displayName = displayName,
            uri = json.getString(FIELD_URI).toUri(),
            mimeType = json.getString(FIELD_MIME),
            askedAt = json.optLong(FIELD_ASKED_AT, 0L),
            isVoip = json.optBoolean(FIELD_VOIP, false),
            notificationId = json.optInt(FIELD_NOTIFICATION_ID, AfterCallPolicy.NOTIFICATION_ID_BASE),
            deleteRequested = json.optBoolean(FIELD_DELETE_REQUESTED, false),
            dismissed = json.optBoolean(FIELD_DISMISSED, false),
        )
    }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @SuppressLint("ApplySharedPref") // Deliberate: see the class KDoc — these must survive process death.
    private inline fun commit(prefs: SharedPreferences, block: SharedPreferences.Editor.() -> Unit): Boolean =
        prefs.edit().apply(block).commit()
}
