/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * RecordingPlaybackController owns a single [MediaPlayer] and exposes its playback state as a
 * [StateFlow] for Compose.
 *
 * It is best-effort and never crashes the UI: a SAF content URI from Google Drive may need to buffer
 * or download before it can play, so we always [MediaPlayer.prepareAsync] with a LOADING state and an
 * [MediaPlayer.OnErrorListener] that surfaces a brief error instead of throwing.
 *
 * The caller (HomeViewModel) must call [release] in `onCleared`.
 */
class RecordingPlaybackController {

    private companion object {
        private const val TAG = "CV:PlaybackController"
    }

    /** Coarse playback phase for the active track. */
    enum class Phase { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    /**
     * Snapshot of playback state for the UI.
     *
     * @param activeUri    The URI currently loaded, or null when idle.
     * @param phase        The coarse playback phase.
     * @param positionMs   Current playback position in millis (0 unless playing/paused).
     * @param durationMs   Track duration in millis (0 until prepared / unknown).
     */
    data class PlaybackState(
        val activeUri: Uri? = null,
        val phase: Phase = Phase.IDLE,
        val positionMs: Int = 0,
        val durationMs: Int = 0
    )

    private val _state = MutableStateFlow(PlaybackState())

    /** Observable playback state for the active track. */
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: MediaPlayer? = null

    /**
     * Starts (or restarts) playback of [uri]. Releases any previously playing track first, then
     * prepares asynchronously and auto-starts on completion. Errors transition to [Phase.ERROR].
     *
     * @param context Application context used to open the SAF content URI.
     * @param uri     The recording's content URI.
     */
    fun play(context: Context, uri: Uri) {
        releasePlayer()
        _state.update { PlaybackState(activeUri = uri, phase = Phase.LOADING) }

        runCatching {
            MediaPlayer().apply {
                setDataSource(context.applicationContext, uri)
                setOnPreparedListener { mp ->
                    _state.update {
                        it.copy(phase = Phase.PLAYING, durationMs = mp.duration.coerceAtLeast(0))
                    }
                    mp.start()
                }
                setOnCompletionListener {
                    _state.update { it.copy(phase = Phase.PAUSED, positionMs = it.durationMs) }
                }
                setOnErrorListener { _, what, extra ->
                    AppLogger.w(TAG, "MediaPlayer error what=$what extra=$extra for $uri")
                    _state.update { it.copy(phase = Phase.ERROR) }
                    releasePlayer()
                    true // handled — prevents the default crash/reset path.
                }
                prepareAsync()
                player = this
            }
        }.onFailure { e ->
            AppLogger.w(TAG, "Failed to start playback for $uri: ${e.message}")
            _state.update { PlaybackState(activeUri = uri, phase = Phase.ERROR) }
            releasePlayer()
        }
    }

    /** Pauses the active track if playing. */
    fun pause() {
        runCatching {
            player?.takeIf { it.isPlaying }?.let {
                it.pause()
                _state.update { s -> s.copy(phase = Phase.PAUSED, positionMs = it.currentPosition) }
            }
        }
    }

    /** Resumes a paused track. */
    fun resume() {
        runCatching {
            player?.let {
                it.start()
                _state.update { s -> s.copy(phase = Phase.PLAYING) }
            }
        }
    }

    /** Seeks the active track to [positionMs] (clamped to the known duration). */
    fun seekTo(positionMs: Int) {
        runCatching {
            player?.let {
                val clamped = positionMs.coerceIn(0, _state.value.durationMs)
                it.seekTo(clamped)
                _state.update { s -> s.copy(positionMs = clamped) }
            }
        }
    }

    /** Refreshes [PlaybackState.positionMs] from the player; call on a UI ticker while playing. */
    fun syncPosition() {
        runCatching {
            player?.let { mp ->
                if (_state.value.phase == Phase.PLAYING) {
                    _state.update { it.copy(positionMs = mp.currentPosition) }
                }
            }
        }
    }

    /** Stops playback and resets to idle. */
    fun stop() {
        releasePlayer()
        _state.update { PlaybackState() }
    }

    /** Releases the underlying player. Call from `onCleared`. */
    fun release() {
        releasePlayer()
        _state.update { PlaybackState() }
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
    }
}
