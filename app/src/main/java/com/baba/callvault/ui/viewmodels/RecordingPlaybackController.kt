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
     * @param speed        Playback rate; 1f is normal. Survives a seek, not a new track.
     */
    data class PlaybackState(
        val activeUri: Uri? = null,
        val phase: Phase = Phase.IDLE,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val speed: Float = 1f
    )

    private val _state = MutableStateFlow(PlaybackState())

    /** Observable playback state for the active track. */
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: MediaPlayer? = null

    /** Kept so a Play tap in [Phase.ERROR] can load the same track again (review C154). */
    private var appContext: Context? = null

    /** A seek asked for while [Phase.LOADING]: applied in the prepared callback, never on an unprepared player. */
    private var pendingSeekMs: Int? = null

    /**
     * Starts (or restarts) playback of [uri]. Releases any previously playing track first, then
     * prepares asynchronously and auto-starts on completion. Errors transition to [Phase.ERROR].
     *
     * @param context Application context used to open the SAF content URI.
     * @param uri     The recording's content URI.
     */
    fun play(context: Context, uri: Uri, startAtMs: Int = 0) {
        releasePlayer()
        appContext = context.applicationContext
        pendingSeekMs = null
        _state.update { PlaybackState(activeUri = uri, phase = Phase.LOADING, speed = it.speed) }

        runCatching {
            MediaPlayer().apply {
                setDataSource(context.applicationContext, uri)
                setOnPreparedListener { mp ->
                    val duration = mp.duration.coerceAtLeast(0)
                    // Seek here rather than at the call site: until this callback runs the duration
                    // is unknown, and seekTo clamps against it — so an earlier seek would silently
                    // land at zero instead of at the moment the user asked for.
                    val start = (pendingSeekMs ?: startAtMs).coerceIn(0, duration)
                    pendingSeekMs = null
                    if (start > 0) mp.seekTo(start)
                    _state.update {
                        it.copy(phase = Phase.PLAYING, positionMs = start, durationMs = duration)
                    }
                    mp.start()
                    applySpeed(_state.value.speed)
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

    /**
     * Resumes a paused track. While [Phase.LOADING] it does nothing (the track starts by itself when
     * prepared; start() on a preparing player is a native error). In [Phase.ERROR] the player is gone,
     * so it loads the same track again from where it was instead of touching a null player (review C154).
     */
    fun resume() {
        val s = _state.value
        when (s.phase) {
            Phase.PLAYING, Phase.PAUSED -> runCatching {
                player?.let {
                    it.start()
                    _state.update { cur -> cur.copy(phase = Phase.PLAYING) }
                    applySpeed(_state.value.speed)
                }
            }
            Phase.ERROR -> {
                val ctx = appContext
                val uri = s.activeUri
                if (ctx != null && uri != null) {
                    AppLogger.i(TAG, "resume in ERROR: loading $uri again from ${s.positionMs} ms")
                    play(ctx, uri, s.positionMs)
                }
            }
            Phase.LOADING, Phase.IDLE -> Unit
        }
    }

    /**
     * Seeks the active track to [positionMs] (clamped to the known duration). While [Phase.LOADING] the
     * seek is held and applied once the track is prepared; in any other unprepared phase it is ignored.
     */
    fun seekTo(positionMs: Int) {
        when (_state.value.phase) {
            Phase.LOADING -> {
                val wanted = positionMs.coerceAtLeast(0)
                pendingSeekMs = wanted
                _state.update { s -> s.copy(positionMs = wanted) }
            }
            Phase.PLAYING, Phase.PAUSED -> runCatching {
                player?.let {
                    val clamped = positionMs.coerceIn(0, _state.value.durationMs)
                    it.seekTo(clamped)
                    _state.update { s -> s.copy(positionMs = clamped) }
                }
            }
            Phase.ERROR, Phase.IDLE -> Unit
        }
    }

    /** Moves [PlaybackControls.SKIP_MS] in either direction, clamped inside the recording. */
    fun skip(deltaMs: Int) {
        val state = _state.value
        // The duration is unknown until prepared, so a skip before then has nothing to be clamped against.
        if (state.phase != Phase.PLAYING && state.phase != Phase.PAUSED) return
        seekTo(PlaybackControls.skipTo(state.positionMs, deltaMs, state.durationMs))
    }

    /**
     * Advances the playback rate to the next step in the cycle.
     *
     * Applied only while actually playing: `setPlaybackParams` on a paused MediaPlayer *starts* it,
     * which would turn a speed tap into an unexpected resume. Paused, the rate is remembered and takes
     * effect on the next resume.
     */
    fun cycleSpeed() {
        val next = PlaybackControls.nextSpeed(_state.value.speed)
        _state.update { it.copy(speed = next) }
        applySpeed(next)
    }

    private fun applySpeed(speed: Float) {
        runCatching {
            player?.takeIf { it.isPlaying }?.let { it.playbackParams = it.playbackParams.setSpeed(speed) }
        }.onFailure { AppLogger.w(TAG, "Could not set playback speed to $speed: ${it.message}") }
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
