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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Saves a cloud key as it is typed: a moment after the last keystroke, and at once when the page that
 * holds the field goes away ([flush]) (review C27).
 *
 * The debounce used to live in the Settings composition, so closing Settings within it (animations off,
 * or a quick back) cancelled the save and the key was never stored. The work now runs in a scope that
 * outlives the page, one write at a time and in order, off the main thread (the Keystore is synchronous).
 *
 * Never logs the key, its length, or anything derived from it: only that a save happened or failed.
 */
class CloudKeySaver(
    private val write: (TranscriptionService, String) -> Unit,
    private val scope: CoroutineScope = saveScope,
    private val delayMs: Long = SAVE_DELAY_MS,
) {
    constructor(context: Context) : this(write = CloudKeyStore(context)::set)

    private val lock = Any()

    /** The newest key typed and not yet saved, or null when everything typed is stored. */
    private var unsaved: Pair<TranscriptionService, String>? = null
    private var pending: Job? = null

    /** The field changed: save it once the typing pauses. A blank key removes the stored one. */
    fun typed(service: TranscriptionService, key: String) = synchronized(lock) {
        unsaved = service to key
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            save()
        }
    }

    /** The page is going away: save what is unsaved now rather than after the pause. */
    fun flush() = synchronized(lock) {
        if (unsaved == null) return@synchronized
        pending?.cancel()
        pending = scope.launch { save() }
    }

    private fun save() {
        val entry = synchronized(lock) { unsaved } ?: return
        runCatching { write(entry.first, entry.second) }
            .onSuccess {
                synchronized(lock) { if (unsaved == entry) unsaved = null }
                AppLogger.i(TAG, "Saved the ${entry.first.key} key")
            }
            .onFailure { AppLogger.w(TAG, "Could not save the ${entry.first.key} key (${it.javaClass.simpleName})") }
    }

    private companion object {
        const val TAG = "CV:CloudKeys"
        const val SAVE_DELAY_MS = 400L

        /** Process-wide and serial, so writes land in the order they were typed. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    }
}
