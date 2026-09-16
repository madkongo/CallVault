/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.viewmodels

import android.app.Application
import android.media.AudioManager
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.transcripts.FavouriteRepository
import com.baba.callvault.data.transcripts.TagRepository
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.data.health.CallGapDetector
import com.baba.callvault.data.health.CallLogReader
import com.baba.callvault.data.health.SetupFingerprint
import com.baba.callvault.data.health.SetupHealth
import com.baba.callvault.data.health.SetupHealthDeriver
import com.baba.callvault.data.health.SetupHealthStore
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.services.recording.RecordingPolicy
import com.baba.callvault.data.health.Prerequisite
import com.baba.callvault.data.health.SetupPrerequisites
import com.baba.callvault.data.recordings.AudioImport
import com.baba.callvault.data.recordings.ImportedRecording
import com.baba.callvault.data.recordings.RecordingDirection
import androidx.documentfile.provider.DocumentFile
import com.baba.callvault.data.recordings.RecordingCatalog
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.data.merge.MergeCandidates
import com.baba.callvault.data.merge.MergeService
import com.baba.callvault.data.recordings.db.RecordingDatabase
import com.baba.callvault.data.recordings.RecordingsRepository
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.ui.common.PlaybackJump
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.integrations.adb.UsbDefaultMode
import com.baba.callvault.integrations.adb.UsbSetResult
import com.baba.callvault.system.updates.UpdateInstallWorker
import com.baba.callvault.system.updates.UpdateScheduler
import androidx.work.WorkManager
import com.baba.callvault.data.waveform.RecordingExtrasRepository
import com.baba.callvault.transcription.TranscriptionEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
        UPDATE_REGRANT_NEEDED(R.string.home_status_update_regrant_title, R.string.home_status_update_regrant_suggestion),
        SHIZUKU_NOT_READY(R.string.home_status_shizuku_title, R.string.home_status_shizuku_suggestion),
        RECOVERY_STUCK(R.string.home_status_recovery_stuck_title, R.string.home_status_recovery_stuck_suggestion),
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
     * The post-update note, listing the last few releases together.
     *
     * Per-release rather than per-feature: someone who skips a couple of updates should still learn
     * what changed, instead of meeting one feature and never hearing about the others. Shown once per
     * version, so it never recurs on a later launch of the same build.
     */
    object WhatsNewNote

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
     * @param hasLoaded       Whether the list has finished loading at least once. Distinct from
     *   [isLoading]: an empty list means "there are none" only once this is true, and before then the
     *   honest answer is "not known yet" — which is not the same message.
     * @param sourceFilter    The active storage-source facet.
     * @param directionFilter The active call-direction facet.
     * @param contactFilter   The selected contact key, or null for "all contacts".
     * @param dateFilter      The selected day key, or null for "all dates".
     */
    data class HomeUiState(
        val status: HomeStatus = HomeStatus.READY,
        val recordings: List<RecordingItem> = emptyList(),
        /**
         * The transcribe-only imports on the device, newest-first, kept OUT of [recordings].
         *
         * A file shared with "Transcribe only" is not a recording and the user said so: it is audio
         * borrowed until its words exist. Splitting it off here, once, is what keeps it out of the
         * list, the facets, the selection, the merge candidates and the hub's count without any of
         * them having to remember — each of those reads [recordings] and [recordings] no longer has
         * it. What still needs it — the Transcripts page's join, the transcription dialogs' length
         * and estimate, the player — asks for both lists explicitly.
         *
         * Almost always empty: one exists only between a share and the end of its transcription.
         */
        val transcribeOnly: List<RecordingItem> = emptyList(),
        val isLoading: Boolean = false,
        val hasLoaded: Boolean = false,
        val sourceFilter: SourceFilter = SourceFilter.ALL,
        val directionFilter: DirectionFilter = DirectionFilter.ALL,
        val contactFilter: String? = null,
        val dateFilter: String? = null,
        /** The selected tag, or null for "all tags". */
        val tagFilter: String? = null,
        /** True when the list is narrowed to starred recordings only. */
        val favouritesOnly: Boolean = false,
        /**
         * The starred recordings, by display name.
         *
         * In the state for the same reason [tagsByRecording] is: it keeps [filteredRecordings] a
         * pure function of state rather than something that has to await a query mid-draw.
         */
        val favourites: Set<String> = emptySet(),
        /**
         * Which tags each recording carries.
         *
         * Held here rather than queried per tag so [filteredRecordings] stays a pure function of
         * state, exactly like the contact and date facets beside it.
         */
        val tagsByRecording: Map<String, Set<String>> = emptyMap(),
        /** Release tag of a known-newer version (drives the update banner), or null. */
        val availableUpdateTag: String? = null,
        /**
         * Which backend serves the recorder. In the state rather than read where it is drawn, so it
         * updates the moment the preference changes — the card previously kept whatever mode it first
         * composed with, because switching happens on another screen in the same activity and no
         * lifecycle event ever fires on the way back.
         */
        val privilegedMode: PrivilegedMode = PrivilegedMode.STANDALONE,
        /** True while the banner's Update action is downloading/dispatching the install. */
        val isUpdateInstalling: Boolean = false,
        /** Download percentage (0-100) while installing, or -1 before the download reports. */
        val updateProgressPercent: Int = -1,
        /** Version name to show a dismissable "updated successfully" banner for, or null. */
        val updatedToVersion: String? = null,
        /** True when the post-update release note is due (shown once per version). */
        val showWhatsNew: Boolean = false,
        /** True when the USB default is a data mode → locking the screen mid-call can stop recording. */
        val usbScreenLockRisk: Boolean = false,
        /** True while the one-tap "set USB to Charging only" fix is running. */
        val usbFixInProgress: Boolean = false,
        /** The one-tap USB fix was declined because a recording is live — see [setUsbChargingOnly]. */
        val usbFixBlockedByRecording: Boolean = false,
        /** Uris of recordings currently being deleted — drives an inline spinner on their row. */
        val deletingUris: Set<Uri> = emptySet(),
        /** What real calls have proved about this setup — drives the status card's second line. */
        val setupHealth: SetupHealth = SetupHealth.Unverified,
        /** True while a chosen file is being copied into the recordings folder and checked. */
        val isImporting: Boolean = false,
        /**
         * The name an import landed under, waiting to be shown to the user, or null.
         *
         * State rather than a callback because the work runs in this ViewModel's scope and must
         * survive the rotation that would cancel a screen-scoped job half way through a copy. The
         * screen clears it with [importShown] once it has acted on it.
         */
        val importedName: String? = null,
        /** Why the last import was refused, or null. Cleared by [importRefusalSeen]. */
        val importRefusal: AudioImport.Reason? = null,
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
         * Every tag in use, most-used first then alphabetically.
         *
         * Ordered by use because the filter row is read constantly and a tag applied to one call
         * years ago should not sit in front of the one applied to forty.
         *
         * Deliberately derived from every assignment rather than only from the *listed* recordings:
         * narrowing the offered tags as other facets narrow the list would make a tag vanish from
         * the row at the moment somebody went looking for it.
         */
        val availableTags: List<String>
            get() = tagsByRecording.values.flatten()
                .groupingBy { it }.eachCount()
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                .map { it.key }

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
                    (dateFilter == null || RecordingsRepository.dayKey(item) == dateFilter) &&
                    (tagFilter == null || tagsByRecording[item.displayName]?.contains(tagFilter) == true) &&
                    (!favouritesOnly || item.displayName in favourites)
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
            if (key == AppPreferences.PRIVILEGED_MODE_KEY) {
                _uiState.update { it.copy(privilegedMode = preferences.getPrivilegedMode()) }
            }
        }

    init {
        detectJustUpdated()
        refresh()
        observeInstallWork()
        observePlaybackErrors()
        observeTags()
        observeFavourites()
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
    /**
     * Keeps the tag facet in step with the database.
     *
     * A tag applied on the playback screen has to reach the Home filter row without a reload, and
     * removing the last recording carrying a tag has to take that tag out of the row — otherwise the
     * row offers a filter that can only ever produce an empty list.
     */
    private fun observeTags() {
        viewModelScope.launch {
            TagRepository.assignments(appContext).collect { assignments ->
                _uiState.update { state ->
                    state.copy(
                        tagsByRecording = assignments,
                        // Drop a filter whose tag has just stopped existing. Without this, deleting
                        // the last recording carrying it leaves the list filtered to nothing with no
                        // chip selected to explain why.
                        tagFilter = state.tagFilter?.takeIf { tag ->
                            assignments.values.any { tag in it }
                        }
                    )
                }
            }
        }
    }

    /**
     * Keeps the starred set live, so a star applied on the playback screen reaches the Home filter
     * without a reload — the same contract [observeTags] has.
     */
    private fun observeFavourites() {
        viewModelScope.launch {
            FavouriteRepository.all(appContext).collect { names ->
                _uiState.update { state ->
                    state.copy(
                        favourites = names.toSet(),
                        // Unstarring the last one must also drop the filter. Otherwise the list sits
                        // empty with a chip selected that no longer has anything behind it.
                        favouritesOnly = state.favouritesOnly && names.isNotEmpty()
                    )
                }
            }
        }
    }

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

    /**
     * One-tap fix for the reliability advisory: sets the Default USB Configuration to "Charging only"
     * over the embedded shell, so locking the screen mid-call no longer kills the recorder. Re-derives
     * the risk flag afterwards so the advisory clears on success.
     */
    fun setUsbChargingOnly() {
        if (_uiState.value.usbFixInProgress) return
        _uiState.update { it.copy(usbFixInProgress = true, usbFixBlockedByRecording = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                UsbDefaultConfig.setViaShell(appContext, UsbDefaultMode.CHARGING)
            }
            _uiState.update {
                it.copy(
                    usbFixInProgress = false,
                    // Applying it restarts adbd, which would end a recording in progress. Saying so
                    // beats a tap that visibly does nothing — the card would otherwise just stay put.
                    usbFixBlockedByRecording = result == UsbSetResult.BUSY_RECORDING,
                    usbScreenLockRisk = UsbDefaultConfig.isScreenLockRisk(appContext),
                )
            }
        }
    }

    /** Dismisses the "updated successfully" banner (clears its persisted state). */
    fun dismissUpdatedBanner() {
        preferences.setUpdateSuccessBannerVersion(null)
        _uiState.update { it.copy(updatedToVersion = null) }
    }

    /**
     * Marks [note] as seen so it never reappears on later updates. Persists the flag; the small
     * "updated successfully" banner is dismissed separately.
     *
     * Another note may still be due (a user who skipped a release can have more than one unseen), so
     * this re-selects rather than simply clearing.
     */
    fun markWhatsNewSeen() {
        preferences.setWhatsNewSeenVersion(com.baba.callvault.BuildConfig.VERSION_NAME)
        _uiState.update { it.copy(showWhatsNew = false) }
    }

    /** Whether the release note is due after an update. */
    private fun pendingWhatsNew(updatedToVersion: String?): Boolean = isWhatsNewDue(
        justUpdated = updatedToVersion != null,
        currentVersion = com.baba.callvault.BuildConfig.VERSION_NAME,
        seenForVersion = preferences.getWhatsNewSeenVersion(),
    )

    /**
     * Recomputes the [HomeStatus] (synchronous, cheap) and reloads the recordings list off the main
     * thread. Safe to call on first composition and on every ON_RESUME.
     */
    /** The background draw pass; cancelled and restarted on every reload so it cannot pile up. */
    private var waveformJob: Job? = null

    /**
     * The list reload. Guarded for the same reason [precomputeWaveforms] is: [refresh] runs on every
     * ON_RESUME, so opening the app, leaving it and coming back used to stack several full passes over
     * the whole library, each competing with the others for the same disk and the same media codecs.
     * A pass already running is left to finish — it is reading the same rows this call would.
     */
    private var listJob: Job? = null
    private var listReloadPending = false
    private var watchingCatalog = false

    /**
     * Re-reads the list when something else changes the catalog.
     *
     * A Drive copy is stamped asynchronously — by the copy worker after a recording, a merge or an
     * un-merge, or by the scheduled sweep — and Home's list is a one-shot pass, so until now the
     * Drive badge only appeared after the app was restarted.
     *
     * Quiet on purpose: this reload does not raise the loading state, because a badge arriving in
     * the background should not flash a spinner over a list the user is reading. Debounced because a
     * sweep stamps many rows in quick succession and each one is a table write.
     */
    private fun watchCatalog() {
        viewModelScope.launch {
            RecordingDatabase.get(appContext).recordingDao().observeCopies()
                .map { rows -> rows.toSet() }
                .distinctUntilChanged()
                .drop(1) // the first emission is the state the current pass already rendered
                .debounce(CATALOG_SETTLE_MS)
                .collect { reloadListQuietly() }
        }
    }

    /**
     * Brings the audio file at [source] into the library as [kind]. See [AudioImport] for what that
     * involves, and [ImportedRecording] for what the kind decides.
     *
     * Runs in the ViewModel's scope, deliberately, not the screen's. Copying a long recording and
     * checking that it decodes takes a moment, and a rotation half way through a job scoped to the
     * composition would cancel it between the copy and the catalogue — leaving a file sitting in the
     * user's recordings folder that the app has no record of and no sweep will ever reconcile.
     *
     * The list is refreshed immediately on success rather than left to the catalog observer's
     * debounce: the user is standing in front of the screen that has to show what they just
     * imported, and a second of nothing reads as the import having failed.
     */
    fun importAudio(source: Uri, kind: ImportedRecording.Kind) {
        if (_uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true, importRefusal = null) }
        viewModelScope.launch {
            val outcome = AudioImport.import(appContext, source, kind)
            when (outcome) {
                is AudioImport.Outcome.Imported -> {
                    refresh()
                    _uiState.update { it.copy(isImporting = false, importedName = outcome.displayName) }
                }
                is AudioImport.Outcome.Refused ->
                    _uiState.update { it.copy(isImporting = false, importRefusal = outcome.reason) }
            }
        }
    }

    /** The screen has opened the imported recording; stop offering it again. */
    fun importShown() {
        _uiState.update { it.copy(importedName = null) }
    }

    /** The user has read why the import was refused. */
    fun importRefusalSeen() {
        _uiState.update { it.copy(importRefusal = null) }
    }

    private fun reloadListQuietly() {
        if (listJob?.isActive == true) {
            listReloadPending = true
            return
        }
        startListPass(_uiState.value.status)
    }

    fun refresh() {
        if (!watchingCatalog) {
            watchingCatalog = true
            watchCatalog()
        }
        val updatedTo = preferences.getUpdateSuccessBannerVersion()
        val status = computeStatus()
        _uiState.update {
            it.copy(
                status = status,
                isLoading = true,
                availableUpdateTag = preferences.getAvailableUpdateTag(),
                privilegedMode = preferences.getPrivilegedMode(),
                updatedToVersion = updatedTo,
                // Shown only after an update, and only once for a given version.
                showWhatsNew = pendingWhatsNew(updatedTo),
                // Advisory when the USB default is a data mode (cheap cached read; never blocks/forces ADB).
                usbScreenLockRisk = UsbDefaultConfig.isScreenLockRisk(appContext)
            )
        }
        // An install-over can drop WRITE_SECURE_SETTINGS while the daemon stays warm (recording still
        // works). Whenever the grant is missing, silently try to restore it over any transport that's
        // already up (WD still on / loopback armed) — no user action, no adbd churn — so a later daemon
        // death stays recoverable. Recompute the status afterwards so any banner reflects the heal.
        if (!AdbShell.hasWriteSecureSettings(appContext)) {
            viewModelScope.launch {
                val healed = withContext(Dispatchers.IO) { AdbShell.tryHealWriteSecureSettings(appContext) }
                if (healed) _uiState.update { it.copy(status = computeStatus()) }
            }
        }
        // Coalesce rather than skip. refresh() is also how a delete gets the row off the screen, so
        // dropping a request outright would leave a deleted recording visible until the next resume.
        // One more pass is queued instead, and it runs once the in-flight one finishes.
        if (listJob?.isActive == true) {
            listReloadPending = true
            return
        }
        startListPass(status)
    }

    private fun startListPass(status: HomeStatus) {
        listJob = viewModelScope.launch {
            val health = withContext(Dispatchers.IO) { sweepSetupHealth(status.isReady) }
            _uiState.update { it.copy(setupHealth = health) }
            val listed = withContext(Dispatchers.IO) { RecordingsRepository.listRecordings(appContext) }
            // Split once, here, from the name alone. The name is the only place the kind lives, so a
            // catalog re-seed cannot lose it and no row anywhere has to carry a flag that could be
            // stale. See HomeUiState.transcribeOnly for why the split belongs here and not at each
            // of the dozen places that read the list.
            val (recordings, transcribeOnly) =
                listed.partition { !ImportedRecording.isTranscribeOnly(it.displayName) }
            _uiState.update { state ->
                // Drop a contact/date selection that no longer exists in the reloaded set so the
                // user can never get stuck on an empty, un-clearable filter.
                val contacts = recordings.map { HomeUiState.contactKey(it) }.toSet()
                val days = recordings.map { RecordingsRepository.dayKey(it) }.toSet()
                state.copy(
                    recordings = recordings,
                    transcribeOnly = transcribeOnly,
                    isLoading = false,
                    hasLoaded = true,
                    contactFilter = state.contactFilter?.takeIf { it in contacts },
                    dateFilter = state.dateFilter?.takeIf { it in days }
                )
            }
            precomputeWaveforms(recordings)
            // Exactly one catch-up pass, whatever number of refreshes arrived while this one ran.
            if (listReloadPending) {
                listReloadPending = false
                startListPass(computeStatus())
            }
        }
    }

    /**
     * Draws the newest recordings ahead of time, so opening one has nothing to wait for.
     *
     * The shape has to be read off the audio, and until it has been, the playback screen shows a
     * resting line where the waveform goes — which looks like an empty control that later fills in
     * with a jolt. Doing the work here spends it while the user is still looking at the list.
     *
     * Only recordings made since the shape started being cached at call end have one; everything
     * recorded before that arrives here uncached, which is why this exists at all.
     *
     * Newest first, matching the list order, because that is the order they get opened in. Strictly
     * sequential: this is background work behind whatever the user is actually doing, and running
     * several decodes at once would compete with playback for the same cores.
     */
    private fun precomputeWaveforms(recordings: List<RecordingItem>) {
        // NOT cancel-and-restart. refresh() runs on every ON_RESUME, and restarting meant beginning
        // again at the top of the list every time the app came forward — so a long recording near
        // the top was decoded from scratch, interrupted, and decoded again, forever. That is minutes
        // of the media codec burning for a picture nobody is waiting for, and it competes with a
        // transcription for the same cores. A pass that is already going is left alone to finish.
        if (waveformJob?.isActive == true) return

        waveformJob = viewModelScope.launch(Dispatchers.IO) {
            val audio = appContext.getSystemService(AudioManager::class.java)
            recordings
                .asSequence()
                // Skip the long ones. Drawing a recording means decoding every sample of it, so a
                // ninety-minute call costs far more than any other and is the least likely to be
                // opened. The resting line covers those until someone actually asks.
                .filter { (it.durationSeconds ?: 0L) <= WAVEFORM_PRECOMPUTE_MAX_SECONDS }
                .take(WAVEFORM_PRECOMPUTE_LIMIT)
                .forEach { item ->
                // Cheap and cached-checked inside, so a second pass over a drawn library costs one
                // indexed lookup each. ensureActive so leaving the screen actually stops the work.
                ensureActive()

                // Never while a call is up. Drawing means running the audio decoder, and the one
                // job this app must not disturb is the recording of the call happening right now.
                // Abandoning the pass rather than pausing it: the next reload starts it again, and
                // the recording that just ended gets drawn by the call-end path anyway.
                if (audio?.mode != AudioManager.MODE_NORMAL) {
                    AppLogger.i(TAG, "Not drawing recordings while a call is in progress")
                    return@launch
                }

                // Transcription owns the CPU while it runs. Drawing alongside it does not merely
                // make both slower — it inflates the time the estimate learns from, so every future
                // "this will take about N minutes" inherits the interference.
                if (TranscriptionEngine.isRunning) {
                    AppLogger.i(TAG, "Not drawing recordings while a transcription is running")
                    return@launch
                }

                RecordingExtrasRepository.precomputeWaveform(appContext, item.displayName, item.uri)

                // Breathe between recordings. This is a picture nobody has asked for yet; it must
                // never feel like the phone is busy.
                delay(WAVEFORM_PRECOMPUTE_GAP_MS)
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

    /** Selects a tag to filter to, or null for "all tags". */
    fun setTagFilter(tag: String?) {
        _uiState.update { it.copy(tagFilter = tag) }
    }

    /** Narrows the list to starred recordings, or back to all of them. */
    fun setFavouritesOnly(on: Boolean) {
        _uiState.update { it.copy(favouritesOnly = on) }
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
     * Derives the current [HomeStatus] by delegating to [SetupPrerequisites.missing] — the single
     * definition of "is this setup capable of recording right now", shared with
     * [com.baba.callvault.services.call.CallSessionManager] so the two can never drift. First match
     * wins:
     *  1. NO_FOLDER        — no device recording folder configured.
     *  2. NOT_PAIRED       — Wireless Debugging pairing was never completed in setup.
     *  3. DEV_OPTIONS_OFF  — the Developer options master toggle is disabled, so Wireless debugging
     *                        (and with it the recorder daemon) cannot function; recordings come out
     *                        empty while everything else still "looks" configured.
     *  4. UPDATE_REGRANT_NEEDED — WRITE_SECURE_SETTINGS gone (an install-over dropped the grant) AND
     *                        the daemon is disconnected, i.e. the next call genuinely couldn't be
     *                        recorded (can't relaunch without the grant). While the daemon binder is
     *                        connected, recording already works right now regardless of the grant, so
     *                        this does NOT alarm — showing "recording paused" then was a false warning
     *                        a user hit. refresh() still tries a silent, non-churning self-heal whenever
     *                        the grant is missing.
     *  5. RECOVERY_STUCK   — everything is configured, but relaunching the recorder daemon has failed
     *                        repeatedly, so the next call would not be recorded. Gated on the failure
     *                        streak, NOT on the daemon being down: an idle daemon is normal (see below)
     *                        and must never alarm. Added after 2026-08-18, where a device sat with a
     *                        dead recorder across an app restart and a reboot and the app said nothing
     *                        at all — the outage was invisible until someone thought to check.
     *  6. READY            — everything looks good.
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
    private fun computeStatus(): HomeStatus = when (SetupPrerequisites.missing(appContext)) {
        Prerequisite.RECORDING_FOLDER -> HomeStatus.NO_FOLDER
        Prerequisite.ADB_PAIRING -> HomeStatus.NOT_PAIRED
        Prerequisite.DEVELOPER_OPTIONS -> HomeStatus.DEV_OPTIONS_OFF
        Prerequisite.SECURE_SETTINGS_GRANT -> HomeStatus.UPDATE_REGRANT_NEEDED
        // Shizuku has to be started again after every reboot, so this is the one a Shizuku user
        // will actually see — and it is fixable by them, in one tap, once they know.
        Prerequisite.SHIZUKU -> HomeStatus.SHIZUKU_NOT_READY
        // Everything is configured, so the only remaining question is whether the recorder can actually
        // be brought up. Gated on the recovery streak rather than on the daemon simply being down —
        // an idle daemon is the normal, healthy state and must never turn the card red.
        null -> if (DaemonKeepAliveService.isRecoveryStuck) HomeStatus.RECOVERY_STUCK else HomeStatus.READY
    }

    /**
     * Reconciles the call log against the calls CallVault observed, then derives what the card says.
     * IO-bound (a content-provider query), so callers must be off the main thread. Best-effort
     * throughout: anything unreadable yields Unverified rather than an invented failure.
     *
     * The newest gap this pass found is persisted via [SetupHealthStore.recordGap] BEFORE deriving,
     * and derive() reads a fresh copy of the facts afterwards. Deriving straight from the gap this pass
     * happened to find (and never persisting it) was the bug: the watermark advance a few lines above
     * moves past that same entry, so the very next resume's sweep no longer finds it, and a warning
     * that was never written down has nothing to survive on.
     *
     * [isReady] is [HomeStatus.isReady] from the status this same [refresh] call just computed. It is
     * threaded into [SetupHealthStore.observationWindowStart] on EVERY sweep (not only while ready):
     * a not-ready reading restarts the window forward so a call CallVault genuinely could not have
     * recorded is never later judged as a failure once the setup is fixed and status returns to READY.
     * While ready, an already-established window start is left alone — that is what keeps a daemon
     * that dies mid-call, despite the status card reading READY, still caught as a gap.
     */
    private fun sweepSetupHealth(isReady: Boolean): SetupHealth = runCatching {
        val store = SetupHealthStore(appContext)
        val facts = store.read()
        val windowStart = store.observationWindowStart(isReady, System.currentTimeMillis())
        val result = CallGapDetector.sweep(
            entries = CallLogReader.entriesSince(appContext, facts.sweepWatermark),
            observedCallEnds = facts.observedCallEnds,
            // Both are ANDed with the carrier master switch: the sweep's question is "did the user
            // expect this call recorded?", and in app-calls-only mode the answer is no for every
            // phone call, whatever the per-direction switches were left at. Without this, turning
            // phone recording off would fill the status card with gaps for calls deliberately
            // ignored.
            autoRecordIncoming = RecordingPolicy.expectsCarrierRecording(
                carrierEnabled = preferences.isCarrierRecordingEnabled(),
                autoRecordForDirection = preferences.isAutoRecordIncomingEnabled(),
            ),
            autoRecordOutgoing = RecordingPolicy.expectsCarrierRecording(
                carrierEnabled = preferences.isCarrierRecordingEnabled(),
                autoRecordForDirection = preferences.isAutoRecordOutgoingEnabled(),
            ),
            watermark = facts.sweepWatermark,
            ringCapacity = SetupHealthStore.RING_SIZE,
            windowStart = windowStart
        )
        if (result.newWatermark != facts.sweepWatermark) store.setSweepWatermark(result.newWatermark)
        result.gaps.maxByOrNull { it.startedAt }?.let { store.recordGap(it.startedAt, it.label) }
        SetupHealthDeriver.derive(store.read(), SetupFingerprint.of(preferences))
    }.getOrElse { e ->
        AppLogger.w(TAG, "Setup-health sweep failed (${e.message}); claiming nothing")
        SetupHealth.Unverified
    }

    /**
     * Deletes [item] from disk (and any same-named copy in the other configured folder) off the main
     * thread, then reloads the recordings list. If [item] is the track currently loaded in the inline
     * player, playback is stopped first.
     */
    // ---- Merging ------------------------------------------------------------------------------

    /**
     * The other calls with this one's number that could continue it, newest first.
     *
     * The rule itself lives in [MergeCandidates], where it can be proved at a desk: a merge deletes
     * the parts it consumed, so a row offered here by mistake is audio the user does not get back.
     */
    fun mergeCandidates(primary: RecordingItem): List<RecordingItem> =
        MergeCandidates.of(primary, _uiState.value.recordings)

    /**
     * How many calls each merged recording holds, as one query.
     *
     * One query for the whole list, never one per row. Asking per row is precisely the shape that
     * made the list slower the more recordings someone kept — see the duration cache — and it would
     * be no better here for being a cheaper query.
     */
    suspend fun mergedCounts(): Map<String, Int> =
        withContext(Dispatchers.IO) {
            RecordingDatabase.get(appContext).mergePartDao().partCounts()
                .associate { it.mergedName to it.partCount }
        }

    /** The parts of a merged recording, labelled for the un-merge confirmation. */
    suspend fun mergedPartLabels(displayName: String): List<String> =
        withContext(Dispatchers.IO) {
            RecordingDatabase.get(appContext).mergePartDao().partsOf(displayName).map { it.partName }
        }

    /**
     * Joins [primary] and [others] into one recording, in that order.
     *
     * [onDone] gets null on success or a message to show. The work is all in [MergeService], which
     * verifies the joined file before deleting anything.
     */
    fun merge(
        primary: String,
        others: List<String>,
        onPartProgress: (Int) -> Unit = {},
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                MergeService.merge(appContext, primary, others) { index ->
                    // Back to the main thread: this drives a dialog the user is watching.
                    viewModelScope.launch { onPartProgress(index) }
                }
            }
            refresh()
            onDone(
                when (outcome) {
                    is MergeService.Outcome.Merged -> null
                    is MergeService.Outcome.Refused -> outcome.reason
                    is MergeService.Outcome.Failed -> outcome.reason
                    is MergeService.Outcome.UnMerged -> null
                }
            )
        }
    }

    /** Cuts a merged recording back into the calls it was made from. */
    fun unMerge(
        mergedName: String,
        onPartProgress: (Int, Boolean) -> Unit = { _, _ -> },
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                MergeService.unMerge(appContext, mergedName) { index, finished ->
                    // Straight back to the main thread: this drives a list the user is watching.
                    viewModelScope.launch { onPartProgress(index, finished) }
                }
            }
            refresh()
            onDone(
                when (outcome) {
                    is MergeService.Outcome.UnMerged -> null
                    is MergeService.Outcome.Refused -> outcome.reason
                    is MergeService.Outcome.Failed -> outcome.reason
                    is MergeService.Outcome.Merged -> null
                }
            )
        }
    }

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

    /**
     * Deletes every file in [uris] — a bulk delete from a multi-selection — then reloads the list
     * once at the end rather than after each file.
     *
     * The whole set is marked as deleting up front so every affected row shows its spinner together;
     * refreshing per file would make the list jump under the user's finger while the rest run.
     */
    fun deleteUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (playback.value.activeUri in uris) playbackController.stop()
        _uiState.update { it.copy(deletingUris = it.deletingUris + uris) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                uris.forEach { RecordingsRepository.deleteFile(appContext, it) }
            }
            refresh()
            _uiState.update { it.copy(deletingUris = it.deletingUris - uris.toSet()) }
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

    /**
     * Plays [uri] from [positionMs], whether or not it is the track already loaded.
     *
     * This is what a timestamp is *for* — tapping a transcript line, or a search result, means "play
     * from there". A plain [seekTo] cannot do it: on any recording that is not already prepared it is
     * clamped to a duration of zero and silently starts from the beginning instead.
     */
    fun playFrom(uri: Uri, positionMs: Int) {
        when (PlaybackJump.planFor(playback.value.activeUri, playback.value.phase, uri)) {
            PlaybackJump.SEEK_NOW -> {
                playbackController.seekTo(positionMs)
                // Then play. Tapping a line asks to *hear* it, not to move a cursor — and a seek
                // that left a paused player paused read as the tap having done nothing at all.
                // Harmless while already playing: start() on a started MediaPlayer is a no-op.
                playbackController.resume()
            }
            PlaybackJump.LOAD_THEN_SEEK -> playbackController.play(appContext, uri, positionMs)
        }
    }

    /** Stops playback and unloads the track. Used when leaving the screen that started it. */
    fun stopPlayback() = playbackController.stop()

    /** Moves playback by [deltaMs], clamped inside the recording. */
    fun skipPlayback(deltaMs: Int) = playbackController.skip(deltaMs)

    /** Steps the playback rate to the next value in the cycle. */
    fun cyclePlaybackSpeed() = playbackController.cycleSpeed()

    /** Pushes the latest player position into state; called by a UI ticker while playing. */
    fun syncPlaybackPosition() = playbackController.syncPosition()

    /** True if [uri] is the track currently loaded in the inline player. */
    fun isActive(uri: Uri): Boolean = playback.value.activeUri == uri

    override fun onCleared() {
        preferences.unregisterChangeListener(prefsListener)
        playbackController.release()
        super.onCleared()
    }

    companion object {
        /** How long the catalog must stop changing before the list is re-read. */
        private const val CATALOG_SETTLE_MS = 400L

        private const val TAG = "CV:HomeViewModel"

        /**
         * How many recordings the background draw pass covers per reload.
         *
         * Five, not thirty. The first attempt at this swept the whole visible library and cost
         * minutes of sustained codec time on a phone doing nothing else — because drawing a
         * recording means decoding every sample of it, and a library is hours of audio.
         *
         * Five is what someone plausibly opens in the moments after the list appears. Everything
         * else keeps the behaviour it always had: drawn on demand, cached forever after, with a
         * resting line rather than a spinner in the meantime.
         */
        private const val WAVEFORM_PRECOMPUTE_LIMIT = 5

        /** Past a quarter of an hour, drawing ahead costs more than it can possibly save. */
        private const val WAVEFORM_PRECOMPUTE_MAX_SECONDS = 15 * 60L

        /** A pause between recordings, so background work stays in the background. */
        private const val WAVEFORM_PRECOMPUTE_GAP_MS = 500L

        /**
         * Whether the release note is due: only right after an update, and only once per version.
         *
         * Keyed on the version it was last shown for rather than a boolean, so the note returns for
         * the NEXT release without needing a new flag each time — the mistake the previous per-feature
         * flags made, where every new feature meant another preference.
         */
        fun isWhatsNewDue(
            justUpdated: Boolean,
            currentVersion: String,
            seenForVersion: String?,
        ): Boolean = justUpdated && seenForVersion != currentVersion
    }
}
