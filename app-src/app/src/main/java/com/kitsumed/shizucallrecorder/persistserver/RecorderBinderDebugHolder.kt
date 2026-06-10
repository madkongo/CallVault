/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.persistserver

/**
 * THROWAWAY de-risk spike (CallVault Plan 5, Task 0c).
 *
 * Process-wide singleton holding the daemon binder the app received via
 * [RecorderBinderDebugProvider]. The provider runs on a binder thread and stores the interface here;
 * [PersistDebugReceiver] reads it later from a background thread to drive the command channel.
 *
 * Mirrors Shizuku's `Shizuku.onBinderReceived` / `Shizuku.getBinder` static holder pattern, but kept
 * trivially small for the spike (no listeners, no multi-process sharing).
 */
object RecorderBinderDebugHolder {

    /** The daemon-side interface, set by the provider on `sendBinder`, cleared on binder death. */
    @Volatile
    var service: IPersistDebugService? = null

    /** True once a binder has been received and is (was) alive. Cleared by [linkToDeath]. */
    @Volatile
    var isConnected: Boolean = false
        private set

    /** Set by the provider after wrapping the received binder. */
    fun onBinderReceived(service: IPersistDebugService) {
        this.service = service
        this.isConnected = true
    }

    /** Cleared when the daemon dies (binder death recipient) so callers see a stale channel. */
    fun onBinderDied() {
        this.service = null
        this.isConnected = false
    }
}
