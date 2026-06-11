/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Brain" of the [com.baba.callvault.ui.screens.HomeScreen].
 *
 * Owns two pieces of state observed by the Home UI:
 *  - [uiState]: the app [HomeStatus] (a non-blocking best-effort health check) plus the list of
 *    in-app [RecordingItem]s.
 *  - [playback]: delegated to [RecordingPlaybackController] for the inline player.
 *
 * Status detection is intentionally synchronous, cheap, and never launches the daemon (Home must
 * not block the UI thread or trigger ADB work). The recordings list is loaded off the main thread
 * via [RecordingsRepository].
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val preferences = AppPreferences(appContext)

    /** Inline-player controller; its state is exposed directly to the UI. */
    val playbackController = RecordingPlaybackController()

    /**
     * The best-effort app health status surfaced in the Home status card. The order of [HomeStatus]
     * declaration is the resolution order — the first matching condition wins.
     *
     * @param titleResId      Short status title.
     * @param suggestionResId One-line actionable suggestion.
     * @param isReady         Whether this is the "all good" state (drives the card's color/icon hint).
     */
    enum class HomeStatus(
        @param:StringRes val titleResId: Int,
        @param:StringRes val suggestionResId: Int,
        val isReady: Boolean = false
    ) {
        NO_FOLDER(R.string.home_status_no_folder_title, R.string.home_status_no_folder_suggestion),
        NOT_PAIRED(R.string.home_status_not_paired_title, R.string.home_status_not_paired_suggestion),
        READY(R.string.home_status_ready_title, R.string.home_status_ready_suggestion, isReady = true)
    }

    /**
     * Aggregate UI state for Home.
     *
     * @param status      The current app health status.
     * @param recordings  The merged, newest-first recordings list.
     * @param isLoading   Whether a recordings reload is in flight.
     */
    data class HomeUiState(
        val status: HomeStatus = HomeStatus.READY,
        val recordings: List<RecordingItem> = emptyList(),
        val isLoading: Boolean = false
    )

    private val _uiState = MutableStateFlow(HomeUiState())

    /** Observable Home UI state (status + recordings). */
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Convenience pass-through of the inline player's state for the UI. */
    val playback: StateFlow<RecordingPlaybackController.PlaybackState> = playbackController.state

    init {
        refresh()
    }

    /**
     * Recomputes the [HomeStatus] (synchronous, cheap) and reloads the recordings list off the main
     * thread. Safe to call on first composition and on every ON_RESUME.
     */
    fun refresh() {
        _uiState.update { it.copy(status = computeStatus(), isLoading = true) }
        viewModelScope.launch {
            val recordings = withContext(Dispatchers.IO) { RecordingsRepository.listRecordings(appContext) }
            _uiState.update { it.copy(recordings = recordings, isLoading = false) }
        }
    }

    /**
     * Derives the current [HomeStatus]. First match wins:
     *  1. NO_FOLDER   — no device recording folder configured.
     *  2. NOT_PAIRED  — Wireless Debugging pairing was never completed in setup.
     *  3. READY       — everything looks good.
     *
     * By design, Wireless Debugging (ADB) is INTENTIONALLY transient: it is turned off between
     * calls and recording flows over the privileged daemon's binder, not over ADB. So a live
     * [AdbConnectionManager] disconnect is the NORMAL, HEALTHY state and is deliberately NOT
     * checked here. Likewise the daemon launches on demand, so an idle binder is fine and never
     * turns the card red.
     *
     * All checks are synchronous reads of [AppPreferences]; none launch the daemon or do I/O.
     */
    private fun computeStatus(): HomeStatus {
        if (preferences.getRecordingFolderUri() == null) return HomeStatus.NO_FOLDER
        if (!preferences.isAdbPaired()) return HomeStatus.NOT_PAIRED
        return HomeStatus.READY
    }

    /**
     * Deletes [item] from disk (and any same-named copy in the other configured folder) off the main
     * thread, then reloads the recordings list. If [item] is the track currently loaded in the inline
     * player, playback is stopped first.
     */
    fun deleteRecording(item: RecordingItem) {
        if (playback.value.activeUri == item.uri) playbackController.stop()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RecordingsRepository.deleteRecording(appContext, item) }
            refresh()
        }
    }

    /** Starts inline playback of [item]. */
    fun play(item: RecordingItem) = playbackController.play(appContext, item.uri)

    /** Pauses the inline player. */
    fun pausePlayback() = playbackController.pause()

    /** Resumes the inline player. */
    fun resumePlayback() = playbackController.resume()

    /** Seeks the inline player to [positionMs]. */
    fun seekTo(positionMs: Int) = playbackController.seekTo(positionMs)

    /** Pushes the latest player position into state; called by a UI ticker while playing. */
    fun syncPlaybackPosition() = playbackController.syncPosition()

    /** True if [uri] is the track currently loaded in the inline player. */
    fun isActive(uri: Uri): Boolean = playback.value.activeUri == uri

    override fun onCleared() {
        playbackController.release()
        super.onCleared()
    }
}
