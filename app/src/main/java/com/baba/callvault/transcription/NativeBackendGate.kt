/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.transcription

/**
 * Serialises the first backend load of the two native engines (C83).
 *
 * whispercv and llamacv each guard `ggml_backend_load_all_from_path` with their own once-flag, but
 * both link one shared libggml whose backend registry is not locked. A first transcription and a
 * first summary in the same process could therefore register the CPU backend at the same time.
 * Holding one lock around each engine's FIRST load closes that; it covers only the backend load, not
 * the multi-gigabyte model load that follows, so neither engine waits on the other's model.
 */
class NativeBackendGate {
    private val lock = Any()
    private val loaded = mutableSetOf<String>()

    /** Runs [load] once for [engine] (first success wins), never at the same time as another engine's. */
    fun ensureLoaded(engine: String, load: () -> Unit) {
        synchronized(lock) {
            if (engine in loaded) return
            load()
            loaded += engine
        }
    }

    companion object {
        /** The one gate for the process: both engines must use this instance. */
        val shared = NativeBackendGate()
    }
}
