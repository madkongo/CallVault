/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * A registered [AudioManager.OnModeChangedListener], kept in a class of its own that only Android 12+ reaches.
 *
 * Android 11 (API 30, our minSdk) has no such listener type. [VoipCallDetector] and [WifiCallRouteWatcher] each made one
 * in a field initializer, so their constructors threw NoClassDefFoundError there; the keep-alive builds the detector on
 * every start, and 2.5.1 crash-looped on an Android 11 emulator as soon as the recorder was set up (2026-11-20).
 * Callers create this behind an SDK check, so the listener is never built on Android 11.
 * NewerApiListenersStayInGatedClassesTest keeps it that way.
 *
 * Registers on construction; throws whatever [AudioManager.addOnModeChangedListener] throws.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal class ModeChangeSubscription(
    private val audioManager: AudioManager,
    executor: Executor,
    onModeChanged: (Int) -> Unit,
) {
    private val listener = AudioManager.OnModeChangedListener { mode -> onModeChanged(mode) }

    init {
        audioManager.addOnModeChangedListener(executor, listener)
    }

    fun cancel() = audioManager.removeOnModeChangedListener(listener)
}
