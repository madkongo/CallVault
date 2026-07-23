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
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource
import com.baba.callvault.integrations.adb.DeveloperOptions
import com.baba.callvault.system.updates.UpdateInstallWorker
import com.baba.callvault.system.updates.UpdateScheduler
import androidx.work.WorkManager
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
        DEV_OPTIONS_OFF(R.string.home_status_dev_options_off_title, R.string.home_status_dev_options_off_suggestion),
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
        val dateFilter: String? = null,
        /** Release tag of a known-newer version (drives the update banner), or null. */
        val availableUpdateTag: String? = null,
        /** True while the banner's Update action is downloading/dispatching the install. */
        val isUpdateInstalling: Boolean = false,
        /** Download percentage (0-100) while installing, or -1 before the download reports. */
        val updateProgressPercent: Int = -1,
        /** Version name to show a dismissable "updated successfully" banner for, or null. */
        val updatedToVersion: String? = null,
        /** Uris of recordings currently being deleted — drives an inline spinner on their row. */
        val deletingUris: Set<Uri> = emptySet()
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

    /**
     * Reacts to the available-update tag being written by a background worker, so the update banner
     * appears/disappears the instant an update is found or cleared — not only on the next screen
     * resume. Registered for the ViewModel's lifetime; removed in [onCleared].
     */
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AppPreferences.AVAILABLE_UPDATE_TAG_KEY) {
                _uiState.update { it.copy(availableUpdateTag = preferences.getAvailableUpdateTag()) }
            }
        }

    init {
        detectJustUpdated()
        refresh()
        observeInstallWork()
        observePlaybackErrors()
        preferences.registerChangeListener(prefsListener)
    }

    /** The last uri whose playback ERROR we handled, so we prune it at most once per failure. */
    private var lastHandledErrorUri: Uri? = null

    /**
     * When a recording fails to play, verify the file still exists; if it was deleted OUTSIDE the app
     * (e.g. removed directly in Google Drive, or a device file cleaned up externally), prune the now-stale
     * catalog entry and refresh so the dead row disappears instead of lingering with a "couldn't play"
     * error. Only prunes on a CONFIRMED-missing file — a transient/network error (exists() throws) leaves
     * the entry untouched, so a valid recording is never removed by a hiccup.
     */
    private fun observePlaybackErrors() {
        viewModelScope.launch {
            playbackController.state.collect { state ->
                val uri = state.activeUri
                if (state.phase == RecordingPlaybackController.Phase.ERROR && uri != null) {
                    if (uri != lastHandledErrorUri) {
                        lastHandledErrorUri = uri
                        pruneIfMissing(uri)
                    }
                } else {
                    lastHandledErrorUri = null // a fresh play — re-arm handling for a later failure
                }
            }
        }
    }

    private fun pruneIfMissing(uri: Uri) {
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) {
                runCatching { DocumentFile.fromSingleUri(appContext, uri)?.exists() == false }.getOrDefault(false)
            }
            if (missing) {
                AppLogger.i("CV:HomeViewModel", "Recording gone (deleted outside the app); pruning stale entry: $uri")
                withContext(Dispatchers.IO) { RecordingCatalog.removeCopyByUri(appContext, uri) }
                refresh()
            }
        }
    }

    /**
     * Detects that an update just landed by comparing the running [BuildConfig.VERSION_CODE] against
     * the versionCode seen on the previous launch. On a version bump (not a fresh install), records
     * the new version name so the Home screen shows a dismissable "updated successfully" banner. This
     * catches ALL updates — via CallVault's own updater or a manual sideload — not just ones that
     * fire [android.content.Intent.ACTION_MY_PACKAGE_REPLACED].
     */
    private fun detectJustUpdated() {
        val current = com.baba.callvault.BuildConfig.VERSION_CODE
        val lastSeen = preferences.getLastSeenVersionCode()
        if (lastSeen != 0 && current > lastSeen) {
            preferences.setUpdateSuccessBannerVersion(com.baba.callvault.BuildConfig.VERSION_NAME)
        }
        if (lastSeen != current) preferences.setLastSeenVersionCode(current)
    }

    /** Dismisses the "updated successfully" banner (clears its persisted state). */
    fun dismissUpdatedBanner() {
        preferences.setUpdateSuccessBannerVersion(null)
        _uiState.update { it.copy(updatedToVersion = null) }
    }

    /**
     * Recomputes the [HomeStatus] (synchronous, cheap) and reloads the recordings list off the main
     * thread. Safe to call on first composition and on every ON_RESUME.
     */
    fun refresh() {
        _uiState.update {
            it.copy(
                status = computeStatus(),
                isLoading = true,
                availableUpdateTag = preferences.getAvailableUpdateTag(),
                updatedToVersion = preferences.getUpdateSuccessBannerVersion()
            )
        }
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

    /**
     * Kicks off the update the banner advertises via a WorkManager job, so the download + install
     * survive the user leaving this screen (the outcome is reported through notifications). The
     * banner spinner is driven by [observeInstallWork] watching that job's state — never by this
     * call directly — so it can't get stuck if the ViewModel is torn down mid-install.
     */
    fun installAvailableUpdate() {
        if (_uiState.value.isUpdateInstalling) return
        // Arm the one-shot consent flag so the worker runs for THIS tap only; an interrupted re-run
        // won't silently reinstall (it no-ops and the banner reappears for a fresh tap).
        preferences.setUpdateInstallArmed(true)
        UpdateScheduler.enqueueInstallNow(appContext)
    }

    /** Mirrors the install job's RUNNING/ENQUEUED state into [HomeUiState.isUpdateInstalling]. */
    private fun observeInstallWork() {
        viewModelScope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(UpdateScheduler.INSTALL_WORK_NAME)
                .collect { infos ->
                    val running = infos.firstOrNull { info -> !info.state.isFinished }
                    val percent = running?.progress?.getInt(UpdateInstallWorker.KEY_PROGRESS, -1) ?: -1
                    _uiState.update {
                        it.copy(
                            isUpdateInstalling = running != null,
                            updateProgressPercent = percent,
                            availableUpdateTag = preferences.getAvailableUpdateTag()
                        )
                    }
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
     *  1. NO_FOLDER        — no device recording folder configured.
     *  2. NOT_PAIRED       — Wireless Debugging pairing was never completed in setup.
     *  3. DEV_OPTIONS_OFF  — the Developer options master toggle is disabled, so Wireless debugging
     *                        (and with it the recorder daemon) cannot function; recordings come out
     *                        empty while everything else still "looks" configured.
     *  4. READY            — everything looks good.
     *
     * By design, Wireless Debugging (ADB) is INTENTIONALLY transient: it is turned off between
     * calls and recording flows over the privileged daemon's binder, not over ADB. So a live
     * [AdbConnectionManager] disconnect is the NORMAL, HEALTHY state and is deliberately NOT
     * checked here. Likewise the daemon launches on demand, so an idle binder is fine and never
     * turns the card red.
     *
     * All checks are synchronous, cheap reads (AppPreferences + one Settings.Global int); none
     * launch the daemon or do I/O.
     */
    private fun computeStatus(): HomeStatus {
        if (preferences.getRecordingFolderUri() == null) return HomeStatus.NO_FOLDER
        if (!preferences.isAdbPaired()) return HomeStatus.NOT_PAIRED
        // isExplicitlyDisabled (not !isEnabled): an absent/unreadable global must not paint a
        // permanent red banner on ROMs that don't expose the setting.
        if (DeveloperOptions.isExplicitlyDisabled(appContext)) return HomeStatus.DEV_OPTIONS_OFF
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
        _uiState.update { it.copy(deletingUris = it.deletingUris + item.uri) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RecordingsRepository.deleteRecording(appContext, item) }
            refresh()
            _uiState.update { it.copy(deletingUris = it.deletingUris - item.uri) }
        }
    }

    /**
     * Deletes ONLY the single file at [uri] (one physical copy — e.g. just the Device or just the
     * Drive copy of a BOTH recording) off the main thread, then reloads the list. If that exact
     * [uri] is the track currently loaded in the inline player, playback is stopped first.
     */
    fun deleteUri(uri: Uri) {
        if (playback.value.activeUri == uri) playbackController.stop()
        _uiState.update { it.copy(deletingUris = it.deletingUris + uri) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RecordingsRepository.deleteFile(appContext, uri) }
            refresh()
            _uiState.update { it.copy(deletingUris = it.deletingUris - uri) }
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
        preferences.unregisterChangeListener(prefsListener)
        playbackController.release()
        super.onCleared()
    }
}
