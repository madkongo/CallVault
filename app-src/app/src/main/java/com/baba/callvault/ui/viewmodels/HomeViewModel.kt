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
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource
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
     * Source filter for the recordings list.
     *
     *  - [ALL]:   every recording, regardless of where it lives.
     *  - [LOCAL]: recordings present locally (source LOCAL or BOTH).
     *  - [DRIVE]: recordings present on Drive (source DRIVE or BOTH).
     */
    enum class SourceFilter { ALL, LOCAL, DRIVE }

    /**
     * Call-direction filter for the recordings list.
     *
     *  - [ALL]:      every recording (default).
     *  - [INCOMING]: only INCOMING recordings.
     *  - [OUTGOING]: only OUTGOING recordings.
     */
    enum class DirectionFilter { ALL, INCOMING, OUTGOING }

    /**
     * Aggregate UI state for Home.
     *
     * Four independent filter facets — [sourceFilter], [directionFilter], [contactFilter] and
     * [dateFilter] — each default to "All" and combine with AND. The result is always sorted
     * newest-first (the repository's order is preserved).
     *
     * @param status          The current app health status.
     * @param recordings      The full merged, newest-first recordings list (unfiltered source of truth).
     * @param isLoading       Whether a recordings reload is in flight.
     * @param sourceFilter    The active storage-source facet.
     * @param directionFilter The active call-direction facet.
     * @param contactFilter   The selected contact key, or null for "all contacts".
     * @param dateFilter      The selected day key, or null for "all dates".
     */
    data class HomeUiState(
        val status: HomeStatus = HomeStatus.READY,
        val recordings: List<RecordingItem> = emptyList(),
        val isLoading: Boolean = false,
        val sourceFilter: SourceFilter = SourceFilter.ALL,
        val directionFilter: DirectionFilter = DirectionFilter.ALL,
        val contactFilter: String? = null,
        val dateFilter: String? = null
    ) {
        /**
         * The distinct contact keys present in [recordings], sorted A→Z case-insensitively. Each
         * recording maps to exactly one key via [contactKey], so these options always match what
         * [filteredRecordings] filters on.
         */
        val availableContacts: List<String>
            get() = recordings.map { contactKey(it) }
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)

        /**
         * The distinct day keys present in [recordings], newest day first. Derived via
         * [RecordingsRepository.dayKey]; ordering follows the recordings' newest-first order
         * (first occurrence wins), so the most recent day appears at the top.
         */
        val availableDates: List<String>
            get() = recordings.map { RecordingsRepository.dayKey(it) }.distinct()

        /**
         * The recordings to render: [recordings] narrowed by all four facets (AND), preserving the
         * repository's newest-first ordering. Derived on read so it always reflects the current
         * filters without a repo reload.
         */
        val filteredRecordings: List<RecordingItem>
            get() = recordings.filter { item ->
                matchesSource(item) &&
                    matchesDirection(item) &&
                    (contactFilter == null || contactKey(item) == contactFilter) &&
                    (dateFilter == null || RecordingsRepository.dayKey(item) == dateFilter)
            }

        private fun matchesSource(item: RecordingItem): Boolean = when (sourceFilter) {
            SourceFilter.ALL -> true
            SourceFilter.LOCAL ->
                item.source == RecordingSource.LOCAL || item.source == RecordingSource.BOTH
            SourceFilter.DRIVE ->
                item.source == RecordingSource.DRIVE || item.source == RecordingSource.BOTH
        }

        private fun matchesDirection(item: RecordingItem): Boolean = when (directionFilter) {
            DirectionFilter.ALL -> true
            DirectionFilter.INCOMING -> item.direction == RecordingDirection.INCOMING
            DirectionFilter.OUTGOING -> item.direction == RecordingDirection.OUTGOING
        }

        companion object {
            /** The single display key used for a recording's contact facet (name, else number, else file). */
            fun contactKey(item: RecordingItem): String =
                item.contactName ?: item.number ?: item.displayName
        }
    }

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
            _uiState.update { state ->
                // Drop a contact/date selection that no longer exists in the reloaded set so the
                // user can never get stuck on an empty, un-clearable filter.
                val contacts = recordings.map { HomeUiState.contactKey(it) }.toSet()
                val days = recordings.map { RecordingsRepository.dayKey(it) }.toSet()
                state.copy(
                    recordings = recordings,
                    isLoading = false,
                    contactFilter = state.contactFilter?.takeIf { it in contacts },
                    dateFilter = state.dateFilter?.takeIf { it in days }
                )
            }
        }
    }

    /** Updates the active storage-source facet; the derived list re-computes on the next read. */
    fun setSourceFilter(filter: SourceFilter) {
        _uiState.update { it.copy(sourceFilter = filter) }
    }

    /** Updates the active call-direction facet; the derived list re-computes on the next read. */
    fun setDirectionFilter(filter: DirectionFilter) {
        _uiState.update { it.copy(directionFilter = filter) }
    }

    /** Selects a specific contact key to filter to, or null for "all contacts". */
    fun setContactFilter(contactKey: String?) {
        _uiState.update { it.copy(contactFilter = contactKey) }
    }

    /** Selects a specific day key to filter to, or null for "all dates". */
    fun setDateFilter(dayKey: String?) {
        _uiState.update { it.copy(dateFilter = dayKey) }
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
        // Stop the inline player if ANY of this item's copies (primary, device, or Drive) is loaded,
        // since delete removes every same-named copy across the configured folders.
        val active = playback.value.activeUri
        if (active == item.uri || active == item.localUri || active == item.driveUri) {
            playbackController.stop()
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RecordingsRepository.deleteRecording(appContext, item) }
            refresh()
        }
    }

    /**
     * Deletes ONLY the single file at [uri] (one physical copy — e.g. just the Device or just the
     * Drive copy of a BOTH recording) off the main thread, then reloads the list. If that exact
     * [uri] is the track currently loaded in the inline player, playback is stopped first.
     */
    fun deleteUri(uri: Uri) {
        if (playback.value.activeUri == uri) playbackController.stop()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RecordingsRepository.deleteFile(appContext, uri) }
            refresh()
        }
    }

    /** Starts inline playback of [item]'s primary copy. */
    fun play(item: RecordingItem) = playbackController.play(appContext, item.uri)

    /**
     * Starts inline playback of a specific [uri]. Used by a BOTH item's expanded section so the
     * device and Drive copies are individually playable; playback state is keyed by this Uri so the
     * correct sub-entry highlights as active.
     */
    fun play(uri: Uri) = playbackController.play(appContext, uri)

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
