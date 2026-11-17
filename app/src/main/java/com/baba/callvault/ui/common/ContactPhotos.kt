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
import android.util.LruCache
import com.baba.callvault.system.permissions.PermissionChecks
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

    private val NO_CONTACT = Info(null, null)

    /** About 6 MB of thumbnails: roughly 150 photos at the usual 96 px, dropped oldest-first. */
    private val cache = object : LruCache<String, Info>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Info): Int =
            (value.photo?.allocationByteCount ?: 0) + 256
    }

    private val gate = Semaphore(2)

    /** The cached answer, or null when this number has not been looked up yet. Cheap; any thread. */
    fun peek(number: String): Info? = cache.get(number)

    /** Looks [number] up (off the main thread) unless it is cached. Never throws. */
    suspend fun load(context: Context, number: String): Info {
        cache.get(number)?.let { return it }
        val app = context.applicationContext
        if (!PermissionChecks.hasContactsPermission(app)) return NO_CONTACT
        return gate.withPermit {
            cache.get(number)?.let { return@withPermit it }
            withContext(Dispatchers.IO) { query(app, number) }.also { cache.put(number, it) }
        }
    }

    private fun query(context: Context, number: String): Info = runCatching {
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
    }.getOrDefault(NO_CONTACT)
}
