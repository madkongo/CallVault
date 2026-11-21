/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import com.baba.callvault.data.recordings.ContactsChanges
import com.baba.callvault.system.permissions.PermissionChecks
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Contact photo and contact link for one phone number, for the recording card (BabApps item 137).
 *
 * The list can hold thousands of rows, so this never touches the contacts provider on the main
 * thread, asks it at most two questions at a time, and remembers every answer — including "no
 * contact" and "no photo" — in a memory cache bounded by bytes. A row scrolled off screen cancels its
 * lookup before it starts (the caller is a cancellable produceState), so fast scrolling through a long
 * library queues nothing.
 *
 * An answer given while READ_CONTACTS was missing is NOT cached: granting the permission afterwards
 * must show photos without restarting the app.
 */
object ContactPhotos {

    /** What the contacts app knows about a number. */
    class Info(
        /** The contact's thumbnail, or null when the contact has no photo. */
        val photo: Bitmap?,
        /** The contact's lookup URI, to open it; null when the number is in nobody's contacts. */
        val contactUri: Uri?,
    )

    private const val TAG = "CV:ContactPhotos"

    private val NO_CONTACT = Info(null, null)

    /** About 6 MB of thumbnails: roughly 150 photos at the usual 96 px, dropped oldest-first. */
    private val cache = ByteLruCache<Info>(6 * 1024 * 1024) { (it.photo?.allocationByteCount ?: 0) + 256 }

    /**
     * A small byte-bounded LRU. Not android.util.LruCache, whose stub does nothing in a unit test, so the
     * C149 behaviour could not be tested.
     */
    private class ByteLruCache<V : Any>(private val maxBytes: Int, private val sizeOf: (V) -> Int) {
        private val map = LinkedHashMap<String, V>(16, 0.75f, true)
        private var bytes = 0

        @Synchronized fun get(key: String): V? = map[key]

        @Synchronized fun put(key: String, value: V) {
            map.put(key, value)?.let { bytes -= sizeOf(it) }
            bytes += sizeOf(value)
            val it = map.entries.iterator()
            while (bytes > maxBytes && it.hasNext()) {
                val eldest = it.next()
                bytes -= sizeOf(eldest.value)
                it.remove()
            }
        }

        @Synchronized fun evictAll() {
            map.clear()
            bytes = 0
        }
    }

    private val gate = Semaphore(2)

    /** The contacts version the cache was filled under; a change drops everything in it (C149). */
    private var cacheVersion = ContactsChanges.version.value

    private fun dropIfContactsChanged() {
        val now = ContactsChanges.version.value
        synchronized(cache) {
            if (now != cacheVersion) {
                cache.evictAll()
                cacheVersion = now
            }
        }
    }

    /** The cached answer, or null when this number has not been looked up yet. Cheap; any thread. */
    fun peek(number: String): Info? {
        dropIfContactsChanged()
        return cache.get(number)
    }

    /** Looks [number] up (off the main thread) unless it is cached. Never throws. */
    suspend fun load(context: Context, number: String): Info {
        dropIfContactsChanged()
        cache.get(number)?.let { return it }
        val app = context.applicationContext
        if (!PermissionChecks.hasContactsPermission(app)) return NO_CONTACT
        ContactsChanges.ensureRegistered(app)
        return gate.withPermit {
            withContext(Dispatchers.IO) { resolve(number) { query(app, number) } }
        }
    }

    /**
     * The answer for [number], remembered unless [lookup] failed (returned null): a lookup that threw must
     * not be remembered as "no contact", or the photo would never appear for the life of the process (C149).
     */
    internal fun resolve(number: String, lookup: () -> Info?): Info {
        cache.get(number)?.let { return it }
        val version = ContactsChanges.version.value
        val found = lookup() ?: return NO_CONTACT
        // A contact change while the query ran makes this answer possibly stale: use it, do not keep it.
        if (version == ContactsChanges.version.value) cache.put(number, found)
        return found
    }

    internal fun clearForTest() = cache.evictAll()

    private fun query(context: Context, number: String): Info? = try {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.LOOKUP_KEY,
            ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use NO_CONTACT
            val id = c.getLong(0)
            val key = c.getString(1)
            val thumb = c.getString(2)
            val contactUri = if (key != null) ContactsContract.Contacts.getLookupUri(id, key) else null
            val bitmap = thumb?.let {
                context.contentResolver.openInputStream(Uri.parse(it))?.use { s -> BitmapFactory.decodeStream(s) }
            }
            Info(bitmap, contactUri)
        } ?: NO_CONTACT
    } catch (e: Exception) {
        // Not remembered (see [resolve]); the next look at this row asks again.
        AppLogger.w(TAG, "Contact photo lookup failed; not remembered: ${e.message}")
        null
    }
}
