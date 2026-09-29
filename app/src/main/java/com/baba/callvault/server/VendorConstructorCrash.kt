/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Whether a throwable is the crash a vendor put into `AudioRecord`'s public constructor — vivo's
 * `VivoAudioRecordImpl.isSupportSubMixRecording()` dereferencing a Context the recorder host does not have.
 * Recognised by shape, not by brand: a NullPointerException raised inside code the constructor CALLED — the
 * frame that threw is not `AudioRecord`, and a frame below it is `AudioRecord.<init>`.
 */
internal object VendorConstructorCrash {

    fun matches(error: Throwable): Boolean {
        val root = rootCause(error)
        if (root !is NullPointerException) return false
        val frames = root.stackTrace
        val ctor = frames.indexOfFirst { it.className == "android.media.AudioRecord" && it.methodName == "<init>" }
        return ctor > 0 && frames[0].className != "android.media.AudioRecord"
    }

    fun rootCause(error: Throwable): Throwable {
        var e = error
        while (e.cause != null && e.cause !== e) e = e.cause!!
        return e
    }
}
