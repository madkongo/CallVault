/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.Executor

/**
 * Calls [onEvent] on the main thread whenever the audio mode or the active players change, while a
 * carrier recording runs. A Samsung Wi-Fi call reaches IN_COMMUNICATION ~350 ms after OFFHOOK, so
 * the route is usually decided here rather than at START (#45).
 */
class WifiCallRouteWatcher(context: Context, private val onEvent: () -> Unit) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    // Tracked apart, so a failure registering one never leaves the other behind.
    /** Android 12+ only, and never a field initializer: Android 11 cannot build the listener ([ModeChangeSubscription]). */
    private var modeSubscription: ModeChangeSubscription? = null
    private var playbackRegistered = false
    @Volatile private var started = false

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = onEvent()
    }

    fun start() {
        val am = audioManager ?: return
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { ModeChangeSubscription(am, executor) { onEvent() } }
                .onSuccess { modeSubscription = it }
                .onFailure { AppLogger.w(WifiCallRoute.TAG, "Could not watch the audio mode: ${it.message}") }
        }
        runCatching { am.registerAudioPlaybackCallback(playbackCallback, handler) }
            .onSuccess { playbackRegistered = true }
            .onFailure { AppLogger.w(WifiCallRoute.TAG, "Could not watch playback: ${it.message}") }
        // The mode may have changed while the capture was starting.
        handler.post { if (started) onEvent() }
    }

    fun stop() {
        val am = audioManager ?: return
        started = false
        val subscription = modeSubscription
        if (subscription != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { subscription.cancel() }
            modeSubscription = null
        }
        if (playbackRegistered) {
            runCatching { am.unregisterAudioPlaybackCallback(playbackCallback) }
            playbackRegistered = false
        }
    }
}
