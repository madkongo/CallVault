/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription.cloud

import android.content.Context
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Saves a cloud key as it is typed: a moment after the last keystroke, and at once when the page that
 * holds the field goes away ([flush]) (review C27).
 *
 * The debounce used to live in the Settings composition, so closing Settings within it (animations off,
 * or a quick back) cancelled the save and the key was never stored. The work now runs in a scope that
 * outlives the page, one write at a time and in order, off the main thread (the Keystore is synchronous).
 * One instance serves the process ([shared]), so a page rebuilt after a rotation [load]s the key the old
 * page is still saving rather than the stale one in the store.
 *
 * Never logs the key, its length, or anything derived from it: only that a save happened or failed.
 */
class CloudKeySaver(
    private val write: (TranscriptionService, String) -> Unit,
    private val read: (TranscriptionService) -> String?,
    private val scope: CoroutineScope = saveScope,
    private val delayMs: Long = SAVE_DELAY_MS,
) {
    private val lock = Any()

    /** Per service, the newest key typed and not yet saved. */
    private val unsaved = mutableMapOf<TranscriptionService, String>()
    private val pending = mutableMapOf<TranscriptionService, Job>()

    /** The field changed: save it once the typing pauses. A blank key removes the stored one. */
    fun typed(service: TranscriptionService, key: String) = synchronized(lock) {
        unsaved[service] = key
        pending.remove(service)?.cancel()
        pending[service] = scope.launch {
            delay(delayMs)
            save(service)
        }
    }

    /** The page is going away: save everything unsaved now rather than after the pause. */
    fun flush() = synchronized(lock) {
        for (service in unsaved.keys.toList()) {
            pending.remove(service)?.cancel()
            pending[service] = scope.launch { save(service) }
        }
    }

    /**
     * The key to show for [service]: one still waiting to be saved, else the stored one. The store is
     * read on the same serial queue as the writes, so it is read after any write already on its way.
     */
    suspend fun load(service: TranscriptionService): String? {
        synchronized(lock) { unsaved[service] }?.let { return it }
        return scope.async { synchronized(lock) { unsaved[service] } ?: read(service) }.await()
    }

    private fun save(service: TranscriptionService) {
        val key = synchronized(lock) { unsaved[service] } ?: return
        runCatching { write(service, key) }
            .onSuccess {
                synchronized(lock) { if (unsaved[service] == key) unsaved.remove(service) }
                AppLogger.i(TAG, "Saved the ${service.key} key")
            }
            .onFailure { AppLogger.w(TAG, "Could not save the ${service.key} key (${it.javaClass.simpleName})") }
    }

    companion object {
        private const val TAG = "CV:CloudKeys"
        private const val SAVE_DELAY_MS = 400L

        /** Process-wide and serial, so writes land in the order they were typed. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        @Volatile
        private var instance: CloudKeySaver? = null

        /** The one saver every Settings page uses, so a rebuilt page sees what the old one left unsaved. */
        fun shared(context: Context): CloudKeySaver = instance ?: synchronized(this) {
            instance ?: CloudKeyStore(context.applicationContext).let { store ->
                CloudKeySaver(write = store::set, read = store::get)
            }.also { instance = it }
        }

        /** What the field shows once the stored key has loaded: never over what the user already typed. */
        fun fieldAfterLoad(current: String?, loaded: String?): String = current ?: loaded.orEmpty()
    }
}
