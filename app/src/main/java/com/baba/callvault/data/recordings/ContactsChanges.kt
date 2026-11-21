/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * "The address book changed", for the caches that remember contact answers (C149, R46).
 *
 * One app-wide [ContentObserver] on the contacts provider, registered once per process, bumps
 * [version]. The contact-name cache and the contact-photo cache drop their answers when it moves, and
 * a recording row keyed on it looks its contact up again.
 *
 * If the observer cannot be registered, [ensureRegistered] says so and the callers keep their old
 * behaviour (no process-level name cache), so a failure here can only cost speed, never stale names.
 */
object ContactsChanges {

    private const val TAG = "CV:ContactsChanges"

    private val versionFlow = MutableStateFlow(0L)

    /** Moves every time the address book changes. */
    val version: StateFlow<Long> = versionFlow

    private val registered = AtomicBoolean(false)
    private val failureLogged = AtomicBoolean(false)

    /** Kept so nothing can collect the observer while it is registered. */
    @Volatile
    private var heldObserver: ContentObserver? = null

    internal fun bump() {
        versionFlow.update { it + 1 }
    }

    /** Registers the observer once; true when contact changes are being watched. Never throws. */
    fun ensureRegistered(context: Context): Boolean {
        if (registered.get()) return true
        return runCatching {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = bump()
            }
            context.applicationContext.contentResolver
                .registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            heldObserver = observer
            registered.set(true)
            true
        }.getOrElse { e ->
            if (failureLogged.compareAndSet(false, true)) {
                AppLogger.w(TAG, "Could not watch contact changes; contact answers are not kept between list passes: ${e.message}")
            }
            false
        }
    }
}
