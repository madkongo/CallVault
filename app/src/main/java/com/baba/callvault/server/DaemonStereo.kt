/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Tells the recorder whether the start that follows should keep the two sides apart (#74).
 *
 * Sent before EVERY start, `false` included: the daemon consumes the flag on each start, so being
 * explicit costs one binder call and rules out any doubt about what a call will record.
 *
 * A recorder left running by an older APK does not have [IRecorderService.setEncodeStereo]. Depending on
 * the generated proxy that is a [android.os.RemoteException] or a silent no-op; either way that daemon
 * records mono, as it always did. This never throws — a missing upgrade must not cost the call.
 */
object DaemonStereo {

    /** @return true if the request reached the recorder. */
    fun request(service: IRecorderService, stereo: Boolean, warn: (String) -> Unit): Boolean =
        request({ service.setEncodeStereo(it) }, stereo, warn)

    internal fun request(send: (Boolean) -> Unit, stereo: Boolean, warn: (String) -> Unit): Boolean =
        runCatching { send(stereo) }
            .onFailure {
                if (stereo) {
                    warn("Recorder cannot keep the sides apart (older build still running?); this call records mono: ${it.message}")
                }
            }
            .isSuccess
}
