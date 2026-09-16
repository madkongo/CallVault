/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Surface
import com.baba.callvault.system.openWirelessDebugging
import com.baba.callvault.data.merge.MergeCandidates
import com.baba.callvault.data.recordings.AudioImport
import com.baba.callvault.data.recordings.DeleteScope
import com.baba.callvault.data.recordings.RecordingSelection
import com.baba.callvault.system.openKofi
import com.baba.callvault.system.openTelegramGroup
import com.baba.callvault.ui.common.DeleteScopeStateSaver
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import com.baba.callvault.ui.common.TranscribeRequest
import com.baba.callvault.ui.common.TranscribeRequestStateSaver
import com.baba.callvault.ui.common.UriSetStateSaver
import com.baba.callvault.ui.common.formatByteSize
import com.baba.callvault.ui.common.SupportDialog
import com.baba.callvault.system.shareRecordings
import com.baba.callvault.system.shareRecording
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.baba.callvault.ui.common.ReleaseHighlight
import com.baba.callvault.ui.common.ReleaseHighlights
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import com.baba.callvault.ui.common.OfflineDialogMode
import com.baba.callvault.ui.common.OfflineRecordingDialog
import com.baba.callvault.data.ChannelMap
import com.baba.callvault.data.SpeakerNames
import com.baba.callvault.data.transcripts.SpeakerTurnsRepository
import com.baba.callvault.data.transcripts.LibraryCounts
import com.baba.callvault.data.transcripts.SummariesPage
import com.baba.callvault.data.transcripts.TranscriptRepository
import com.baba.callvault.data.transcripts.TranscriptsPage
import com.baba.callvault.data.waveform.RecordingExtrasRepository
import com.baba.callvault.data.transcripts.TranscriptStatus
import com.baba.callvault.ui.common.TranscriptActionButton
import com.baba.callvault.transcription.model.ModelRepository
import com.baba.callvault.transcription.model.TranscriptionModel
import com.baba.callvault.transcription.AudioDecoder
import com.baba.callvault.transcription.TranscriptionEstimate
import com.baba.callvault.transcription.TranscriptionLengthLimit
import com.baba.callvault.ui.common.BidiText
import com.baba.callvault.ui.common.ImportedBadge
import com.baba.callvault.ui.common.RecordingLabel
import com.baba.callvault.ui.common.TranscribeConfirmDialog
import com.baba.callvault.ui.common.TranscribeLanguageDialog
import androidx.work.WorkManager
import com.baba.callvault.summary.SummaryScheduler
import com.baba.callvault.ui.common.rememberSummaryState
import com.baba.callvault.ui.common.TranscribingPill
import com.baba.callvault.ui.common.formatEstimate
import com.baba.callvault.ui.common.TranscribingSheet
import com.baba.callvault.ui.common.rememberTranscribingDisplay
import com.baba.callvault.ui.common.rememberTranscribingPillState
import com.baba.callvault.ui.common.TranscriptSearchSheet
import com.baba.callvault.ui.common.MergeCallsDialog
import com.baba.callvault.ui.common.UnMergeDialog
import com.baba.callvault.ui.common.MergeProgressState
import com.baba.callvault.ui.common.DeleteCopiesDialog
import com.baba.callvault.ui.common.DeleteRecordingDialog
import com.baba.callvault.ui.common.SeekBar
import com.baba.callvault.ui.common.TranscriptPresentation
import com.baba.callvault.ui.common.TranscriptView
import com.baba.callvault.system.copyToClipboard
import com.baba.callvault.system.sharePlainText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.ui.common.CallOriginBadge
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Groups
import com.baba.callvault.R
import com.baba.callvault.system.shareTranscriptFile
import com.baba.callvault.data.transcripts.FavouriteRepository
import com.baba.callvault.data.transcripts.FlagRepository
import com.baba.callvault.data.transcripts.TagRepository
import com.baba.callvault.data.transcripts.export.TranscriptExportFile
import com.baba.callvault.data.transcripts.export.TranscriptExport
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.data.health.FailureReason
import com.baba.callvault.data.health.Prerequisite
import com.baba.callvault.data.health.SetupHealth
import com.baba.callvault.data.health.isProblem
import com.baba.callvault.data.recordings.ImportedRecording
import com.baba.callvault.data.recordings.RecordingDirection
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingSource
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvStatusPill
import com.baba.callvault.ui.common.CvTone
import com.baba.callvault.ui.common.rememberExportLabels
import com.baba.callvault.ui.navigation.HomeSection
import com.baba.callvault.ui.theme.LocalCvBrand
import com.baba.callvault.ui.viewmodels.HomeViewModel
import com.baba.callvault.ui.viewmodels.HomeViewModel.DirectionFilter
import com.baba.callvault.ui.viewmodels.HomeViewModel.SourceFilter
import com.baba.callvault.ui.viewmodels.RecordingPlaybackController
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home, once onboarding and the setup wizard are complete: **the shell that holds every section**,
 * and the two things that may be open in place of one — a recording, or a transcript being read.
 *
 * ## Why one composable and not four
 *
 * [section] chooses what is drawn, but everything that would be lost by drawing something else is
 * held here, above the choice. That is the whole reason this is a shell rather than four screens the
 * router swaps between: a section switch that composed and decomposed screens would take the open
 * recording, the scroll position, the selection and every other saved field with it — exactly the
 * loss that issue #27 reported for a rotation, and then three times over, once per section.
 * `rememberSaveable` does not help there: it restores across an Activity recreation, not across a
 * subtree leaving composition for good.
 *
 * The same holds for the transcript being read: it replaces the section rather than sitting over it,
 * so which transcript that is, and the one playback controller behind it, are held here too. There
 * is exactly one [HomeViewModel] for the whole shell precisely so that a section, a recording and a
 * transcript can never each own a player.
 *
 * So the state placement is deliberate, and each field below says which it is:
 *  - **shell** — outlives every section switch (the open recording, the dialogs, the selection),
 *  - **section** — belongs to one section but is hoisted here for the same reason (each list's
 *    scroll position),
 *  - **row** — lives in the row that owns it, and is nowhere near this file.
 *
 * The dialogs and sheets are kept OUT of every section's scaffold, at the bottom of this function.
 * A scaffold is not composed while a recording is open, so a confirmation raised from the playback
 * screen was queued invisibly and only appeared once the user went back to the list — which is how
 * it was found. The same now holds per section: a dialog raised anywhere is drawn whatever section
 * is showing.
 *
 * @param section        Which section to draw. Owned by the router, so that back and the stored
 *                       "reopen where you were" have one place to be decided.
 * @param onSelectSection Navigates to another section; the router persists it.
 * @param onOpenSettings Opens the Settings panel, which slides over whatever section is showing.
 * @param openRecording  A recording to land on, sent in from outside the app (the share target's
 *                       Open button). Null means nothing was asked for.
 * @param onOpenRecordingHandled Acknowledges [openRecording], so one request navigates once.
 * @param modifier       Optional layout modifier.
 * @param viewModel      The Home "Brain"; defaults to a [viewModel]-scoped [HomeViewModel]. One
 *                       instance for the whole shell, so there is exactly one playback controller
 *                       and two sections can never each own a player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    section: HomeSection,
    onSelectSection: (HomeSection) -> Unit,
    onOpenSettings: () -> Unit,
    openRecording: String? = null,
    onOpenRecordingHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel()
) {
    val context = LocalContext.current
    // Where the Telegram invite currently lives. Read once and held, so the long-press moves it under
    // the user's finger instead of on the next recomposition that happens to come along.
    val preferences = remember(context) { AppPreferences(context) }
    var communityTucked by rememberSaveable { mutableStateOf(preferences.isCommunityTucked()) }
    val uiState by viewModel.uiState.collectAsState()

    /**
     * Every file the app can resolve a name to, including the transcribe-only imports the recordings
     * list deliberately never shows.
     *
     * `uiState.recordings` is the RECORDINGS LIST and nothing else; a transcribe-only import is kept
     * out of it on purpose (see [HomeViewModel.HomeUiState.transcribeOnly]). Everything that has to
     * work on a file by name regardless of which list it belongs to — the length and estimate before
     * a run, the player, the Transcripts page's join, a dialog's title — asks this instead. Looking
     * such a file up in `recordings` would silently find nothing, which is a transcription started
     * with no length check and a dialog headed with a raw file name.
     */
    val libraryRecordings = remember(uiState.recordings, uiState.transcribeOnly) {
        uiState.recordings + uiState.transcribeOnly
    }

    val playback by viewModel.playback.collectAsState()

    // Two separate flags, not one: the chooser is opened by the user tapping Support, the appeal
    // arrives on its own after a release note. Sharing a flag would let dismissing one suppress the
    // other in the same session.
    var showSupport by rememberSaveable { mutableStateOf(false) }
    var showSupportAppeal by rememberSaveable { mutableStateOf(false) }

    // Multi-selection, keyed by each row's primary Uri. Empty means normal browsing; the moment it
    // holds anything the screen is in selection mode, so there is no second flag to keep in step.
    var selection by rememberSaveable(stateSaver = UriSetStateSaver) {
        mutableStateOf<Set<Uri>>(emptySet())
    }
    var showBulkDelete by rememberSaveable { mutableStateOf(false) }

    // Transcript state for the rows currently listed — scoped to those names rather than the whole
    // table, because Home lists years of calls and only a handful of icons are ever on screen.
    val listedNames = uiState.filteredRecordings.map { it.displayName }
    val transcriptStatuses by remember(listedNames) {
        TranscriptRepository.statusesFor(context, listedNames)
    }.collectAsState(initial = emptyMap())

    /** Which recording's transcript is open **as a sheet**, over the list or the playback screen. */
    var transcriptFor by rememberSaveable { mutableStateOf<String?>(null) }

    /**
     * Which recording's transcript is open **as a page**, or null.
     *
     * A second field rather than a flag beside [transcriptFor], because the two differ in more than
     * how they are drawn: a sheet sits over a list that still shows the playing row, and a page
     * replaces the section entirely, so leaving one keeps the audio and leaving the other must stop
     * it. Keeping them apart means neither host can be reached with the other's rules.
     *
     * SHELL state, and saveable for the reason [playbackFor] is: rotation recreates the Activity, and
     * a plain `remember` would drop the reader back on the list having lost their place — and, worse,
     * would leave the recording playing with nothing on screen owning it, which is issue #27 exactly.
     *
     * Deliberately NOT part of the persisted "reopen where you were" section. A section is a place;
     * this is a place *plus a recording*, and reopening the app onto a transcript whose recording a
     * retention sweep deleted overnight would be a blank page with nothing to go back to.
     */
    var readingFor by rememberSaveable { mutableStateOf<String?>(null) }

    // Which list the reader came from. The page is the same either way, but the emphasis is not: a
    // summary tapped on the Summaries page must not open as a one-line strip above someone else's
    // words, which is what it did the first time this page was built.
    var readingFromSummary by rememberSaveable { mutableStateOf(false) }

    /** Which recording's transcript is awaiting a delete confirmation, or null. */
    var deleteTranscriptFor by rememberSaveable { mutableStateOf<String?>(null) }

    /**
     * Which transcript to reopen if the delete is cancelled.
     *
     * The sheet is closed before the confirmation so the dialog does not sit on top of the text it
     * is asking about — but a cancelled delete then left the reader back at the list, having lost
     * their place for saying no.
     */
    var reopenTranscriptAfterDelete by rememberSaveable { mutableStateOf<String?>(null) }

    /**
     * Which recording is open on the playback screen, or null for the list.
     *
     * Saveable, not remembered. Rotation recreates the Activity, and a plain `remember` came back as
     * null — which closed whatever the user was listening to and dropped them at the top of the list
     * (issue #27). It also broke an invariant: leaving this screen by hand goes through
     * `closePlayback`, which stops the audio, whereas rotation only reset the field, so playback
     * carried on with nothing on screen owning it. Restoring the name fixes both — the screen comes
     * back, and the audio it belongs to is on it again.
     */
    var playbackFor by rememberSaveable { mutableStateOf<String?>(null) }
    // SECTION state, hoisted deliberately — one per list, all of them above the section switch.
    // Each list leaves composition twice over: while a recording is open (`playbackFor == null`
    // below) and whenever another section is showing. Remembered inside, someone who scrolled to the
    // hundredth call and opened it came back to the top of the list; hoisted, their place survives
    // both, and a rotation besides (rememberLazyListState is itself saveable).
    val listState = rememberLazyListState()
    val hubGridState = rememberLazyGridState()
    val transcriptsListState = rememberLazyListState()
    val summariesListState = rememberLazyListState()
    // DELIBERATELY NOT saveable, unlike the rest of this block. A merge runs in `mergeScope`, a
    // rememberCoroutineScope, so rotation already cancels it half-done — that is a real bug, and a
    // bigger one than #27, but its fix is to hoist the merge into the ViewModel, not to restore its
    // dialog. Restoring these would put a merge dialog back on screen for an operation that no
    // longer exists, and offer to re-run it over recordings that may already be partly merged.
    // Leaving them to reset is the honest behaviour until the merge itself outlives the Activity.
    var mergeFor by remember { mutableStateOf<RecordingItem?>(null) }
    var unMergeFor by remember { mutableStateOf<RecordingItem?>(null) }
    var unMergeLabels by remember { mutableStateOf<List<String>>(emptyList()) }
    // Non-null while a merge or un-merge is running or has just finished.
    var mergeProgress by remember { mutableStateOf<MergeProgressState?>(null) }
    // Which recordings were made by merging, fetched once for the whole list rather than per row.
    var mergedCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    /** The recording whose delete is awaiting confirmation, raised from the playback screen. */
    var confirmDeleteFor by rememberSaveable { mutableStateOf<String?>(null) }

    /** Whether the search-across-transcripts sheet is open. */
    var showTranscriptSearch by rememberSaveable { mutableStateOf(false) }

    /** What transcription is doing right now, for the pill beside the title. Hidden when idle. */
    val transcribing by rememberTranscribingPillState()

    // whisper only reports at chunk boundaries, so the raw figure sits still for long stretches —
    // long enough to read as a hang. The gaps are filled by the run itself, which is what keeps the
    // figure steady across a rotation (issue #34); this only holds it at 100 when a run finishes.
    val transcribingShown = rememberTranscribingDisplay(transcribing)
    var showTranscribingSheet by rememberSaveable { mutableStateOf(false) }

    val transcriptScope = rememberCoroutineScope()
    val mergeScope = rememberCoroutineScope()
    val listScope = rememberCoroutineScope()
    LaunchedEffect(uiState.recordings.size) { mergedCounts = viewModel.mergedCounts() }

    /**
     * The SAF picker for an audio file to import.
     *
     * `OpenDocument`, filtered by [IMPORT_MIME_FILTER], and **no permission is declared or asked
     * for**. SAF
     * hands back a URI for the one file the user chose and nothing else; `READ_MEDIA_AUDIO` would
     * let a call recorder read every audio file on the phone, which is indefensible on its own terms
     * and would be flagged on F-Droid besides.
     *
     * The work runs in the ViewModel, not here — see [HomeViewModel.importAudio] for why a rotation
     * must not be able to cancel it.
     */
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importAudio(it) } }

    /**
     * A just-imported recording waiting for the list to catch up, so it can be opened on arrival.
     *
     * The catalog write and the list reload are two steps, and setting [playbackFor] straight away
     * would find no row and reset itself — the screen already guards against opening a recording
     * that is not there. So the name is held until the refreshed list contains it.
     *
     * A plain `remember`: this is a hand-off between two moments of the same interaction, and a
     * pending open restored after the process was killed would open a recording the user chose
     * before lunch.
     */
    var openWhenListed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.importedName) {
        uiState.importedName?.let {
            openWhenListed = it
            viewModel.importShown()
        }
    }
    // A recording named from outside the app — the share target's Open button — joins the same
    // queue, rather than setting playbackFor directly. A cold start arrives here before the first
    // list pass has finished, so the row is not there yet and opening it would find nothing and
    // reset itself. The section moves too: landing on the recording while the app still says it is
    // showing Summaries would leave back returning to a page that never mentioned this file.
    LaunchedEffect(openRecording) {
        val requested = openRecording ?: return@LaunchedEffect
        onSelectSection(HomeSection.Recordings)
        openWhenListed = requested
        onOpenRecordingHandled()
    }

    LaunchedEffect(openWhenListed, libraryRecordings) {
        val pending = openWhenListed ?: return@LaunchedEffect
        // The combined list: a transcribe-only import is never in uiState.recordings, so waiting
        // for it there would wait for ever and its screen would never open.
        if (libraryRecordings.any { it.displayName == pending }) {
            // Its own screen, which is where the file's length, its player and Transcribe all are.
            // A toast saying "imported" would be the app telling the user something happened
            // somewhere else; this shows them the thing itself.
            playbackFor = pending
            openWhenListed = null
        }
    }

    /** Raised when transcription is asked for but the model it needs is not installed. */
    var showModelMissing by rememberSaveable { mutableStateOf(false) }
    // Non-null while refusing to transcribe a recording that is too long; holds its length in minutes
    // so the dialog can say how long it actually was rather than only quoting the limit.
    var tooLongMinutes by rememberSaveable { mutableStateOf<Int?>(null) }

    /** The recording awaiting a "this will take N minutes" confirmation, with the language picked for
     *  it (null = use the setting), or null when nothing is waiting. */
    var confirmTranscribe by rememberSaveable(stateSaver = TranscribeRequestStateSaver) {
        mutableStateOf<TranscribeRequest?>(null)
    }

    /** The recording awaiting a "which language?" answer, or null. Only ever set when the user has
     *  turned that ask on; see AppPreferences.getTranscriptionAskLanguage. */
    var askLanguageFor by rememberSaveable { mutableStateOf<String?>(null) }

    /** Enqueues without asking. retry() clears the row and enqueues; clearing nothing is a no-op, so
     *  it serves a first transcription, a retry and "transcribe again" alike — and the queue skips
     *  FAILED and DONE rows, so leaving the row in place would make the tap do nothing. */
    val enqueueTranscription: (String, String?) -> Unit = { displayName, language ->
        transcriptScope.launch { TranscriptRepository.retry(context, displayName, language) }
    }

    /**
     * Everything after the language is settled: the estimate confirmation if it is on, otherwise straight
     * to the queue. [language] is a per-recording pick, null meaning "use the setting".
     */
    val continueTranscription: (String, String?) -> Unit = { displayName, language ->
        val prefs = AppPreferences(context)
        val model = TranscriptionModel.fromId(prefs.getTranscriptionModelId()) ?: TranscriptionModel.DEFAULT
        // An import ALWAYS confirms, whatever the setting says. The setting was turned off by
        // someone who had seen the estimate for their own calls, which are minutes long and in the
        // language they speak; an imported file is neither, and may be an hour of a lecture. See
        // startTranscription for the same argument about the language.
        val isImport = ImportedRecording.isImported(displayName)
        if (!isImport && !prefs.getTranscriptionConfirmBeforeRun()) {
            enqueueTranscription(displayName, language)
        } else {
            // Estimating is arithmetic on a duration read from the container — microseconds — so the
            // dialog can open with the answer already in it rather than spinning first.
            transcriptScope.launch {
                val audioMs = withContext(Dispatchers.IO) {
                    val uri = libraryRecordings.firstOrNull { it.displayName == displayName }?.uri
                    uri?.let { AudioDecoder.durationMs(context, it) } ?: 0L
                }
                // The container's own length, which is the honest one and is read here anyway. The
                // check in startTranscription can only use the length the list happens to know, and
                // for a file with no duration in the call log and none cached that is nothing at all
                // — so a recording over the limit slipped through to the runner, which refuses it
                // with nobody to tell. Asking the file itself, on the path that was opening it
                // regardless, costs nothing and turns a silent nothing-happens into a sentence.
                if (TranscriptionLengthLimit.isTooLong(audioMs)) {
                    tooLongMinutes = (audioMs / 60_000L).toInt()
                } else {
                    val estimate = if (audioMs <= 0L) null else TranscriptionEstimate.estimateMs(
                        audioMs = audioMs,
                        cost = prefs.getRunCost(model),
                    )
                    confirmTranscribe = Triple(displayName, estimate, language)
                }
            }
        }
    }

    /**
     * Starts transcription, or explains why it cannot.
     *
     * The worker retries indefinitely while its model is absent, which is right for a download still
     * in flight but wrong for a tap: nothing would appear to happen, for ever. So the same condition
     * the worker checks is checked here, where it can be said out loud — and it stays ahead of both
     * dialogs, since asking someone to choose a language for work that cannot start would be a worse
     * kind of nothing-happens than the one it already guards against.
     */
    val startTranscription: (String) -> Unit = { displayName ->
        val prefs = AppPreferences(context)
        val model = TranscriptionModel.fromId(prefs.getTranscriptionModelId()) ?: TranscriptionModel.DEFAULT
        // Refuse a long recording before anything else. Transcription decodes the whole file into
        // memory first, so a long call ends in a crash or a job that dies silently after a long wait —
        // saying no immediately, with a reason, is strictly better than trying and failing. A stopgap
        // until decoding is chunked; see TranscriptionLengthLimit.
        val recordingSeconds = libraryRecordings
            .firstOrNull { it.displayName == displayName }?.durationSeconds
        if (TranscriptionLengthLimit.isTooLong(recordingSeconds)) {
            tooLongMinutes = ((recordingSeconds ?: 0L) / 60L).toInt()
        } else if (!ModelRepository.isInstalled(context, model)) {
            showModelMissing = true
        } else if (ImportedRecording.isImported(displayName) || prefs.getTranscriptionAskLanguage()) {
            // An import ALWAYS asks, whatever the setting says. The setting means "my calls are in
            // the language I set", and it is true — a phone's calls are mostly in one language. An
            // imported file is the one thing in the library that is nobody's call: a voice note from
            // abroad, a lecture, an interview. Transcribing an hour of Hebrew as English produces
            // fluent nonsense and no error, and the whole run has to be done again.
            askLanguageFor = displayName
        } else {
            continueTranscription(displayName, null)
        }
    }

    val selectionMode = selection.isNotEmpty()
    val selectedItems = remember(selection, uiState.recordings) {
        uiState.recordings.filter { it.uri in selection }
    }
    val clearSelection = { selection = emptySet() }

    // Back leaves selection mode rather than the screen — the standard behaviour, and without it the
    // only way out would be the close button.
    BackHandler(enabled = selectionMode) { clearSelection() }

    // The ON_RESUME refresh lives in AppNavigationScreen, not here. refresh() is not only a list
    // reload: it recomputes the status card, sweeps setup health and silently re-grants
    // WRITE_SECURE_SETTINGS. Hooking that to this screen made the heal conditional on the recordings
    // list being what the user happens to be looking at, which stops being true the moment there is
    // more than one section. Exactly one observer exists, and it is in the shell.

    // While a track is playing, tick the player position so the slider tracks playback.
    LaunchedEffect(playback.phase) {
        while (playback.phase == RecordingPlaybackController.Phase.PLAYING) {
            viewModel.syncPlaybackPosition()
            delay(500)
        }
    }

    playbackFor?.let { displayName ->
        // The combined list, so a transcribe-only import can still be played, shared and deleted
        // from its own screen while it exists. It is kept out of the LIST, not out of the app.
        val openItem = libraryRecordings.firstOrNull { it.displayName == displayName }
        if (openItem == null) {
            // The recording went away underneath us — a retention sweep, or a delete from elsewhere.
            playbackFor = null
        } else {
            // Leaving stops the audio. A recording that went on playing from a screen the user had
            // already left is how this was noticed — and on a private call, sound continuing after
            // you have visibly closed it is the wrong kind of surprise.
            val closePlayback = {
                viewModel.stopPlayback()
                playbackFor = null
            }
            BackHandler { closePlayback() }

            // Computed off the main thread and cached, so a ninety-minute call is decoded once. Keyed
            // by the recording, so opening a different one starts again rather than showing the last.
            val peaks by produceState(FloatArray(0), displayName) {
                value = FloatArray(0)
                value = RecordingExtrasRepository.waveform(context, displayName, openItem.uri)
            }
            val note by remember(displayName) {
                RecordingExtrasRepository.note(context, displayName)
            }.collectAsState(initial = "")

            val summaryState by rememberSummaryState(displayName)

            val tags by remember(displayName) {
                TagRepository.tagsFor(context, displayName)
            }.collectAsState(initial = emptyList())
            val knownTags by remember {
                TagRepository.allTags(context)
            }.collectAsState(initial = emptyList())
            val isFavourite by remember(displayName) {
                FavouriteRepository.isFavourite(context, displayName)
            }.collectAsState(initial = false)
            val flags by remember(displayName) {
                FlagRepository.flagsFor(context, displayName)
            }.collectAsState(initial = emptyList())

            PlaybackScreen(
                item = openItem,
                playback = playback,
                transcriptStatus = transcriptStatuses[displayName] ?: TranscriptStatus.NONE,
                // The same smoothed figure the list row shows, so opening a recording mid-run does
                // not swap a percentage for a bare spinner.
                transcriptPercent = transcribingShown.percentFor(displayName),
                summaryState = summaryState,
                peaks = peaks,
                note = note,
                onNoteChange = { text ->
                    transcriptScope.launch {
                        RecordingExtrasRepository.saveNote(context, displayName, text)
                    }
                },
                flags = flags,
                onPlayFromFlag = { atMs -> viewModel.playFrom(openItem.uri, atMs.toInt()) },
                onRemoveFlag = { atMs ->
                    transcriptScope.launch { FlagRepository.remove(context, displayName, atMs) }
                },
                isFavourite = isFavourite,
                onToggleFavourite = {
                    transcriptScope.launch {
                        FavouriteRepository.toggle(context, displayName, isFavourite)
                    }
                },
                tags = tags,
                knownTags = knownTags.map { it.tag },
                onAddTag = { text ->
                    transcriptScope.launch { TagRepository.add(context, displayName, text) }
                },
                onRemoveTag = { tag ->
                    transcriptScope.launch { TagRepository.remove(context, displayName, tag) }
                },
                onRenameTag = { tag, newName ->
                    transcriptScope.launch { TagRepository.rename(context, tag, newName) }
                },
                onDeleteTagEverywhere = { tag ->
                    transcriptScope.launch { TagRepository.deleteEverywhere(context, tag) }
                },
                onSummarise = { SummaryScheduler.runNow(context, displayName) },
                onStopSummary = { SummaryScheduler.stopNow(context) },
                onBack = closePlayback,
                onPlay = { viewModel.play(openItem.uri) },
                onPause = { viewModel.pausePlayback() },
                onResume = { viewModel.resumePlayback() },
                onSeek = { viewModel.seekTo(it) },
                onSkip = { viewModel.skipPlayback(it) },
                onCycleSpeed = { viewModel.cyclePlaybackSpeed() },
                onOpenTranscript = { transcriptFor = displayName },
                onTranscribe = { startTranscription(displayName) },
                // Confirm first, and ask which copies when there are two of them — the same
                // question the row's menu asks. This used to delete on the spot with nothing to
                // stop it, on the one screen where a mis-tap costs the whole recording.
                onDelete = { confirmDeleteFor = displayName }
            )
        }
    }

    // The pill beside the title, on whichever section is showing. One slot, two claimants:
    // transcription wins while it is working, because it is transient and explains something
    // happening right now, whereas the support pill is always there and loses nothing by waiting.
    val titleTrailing: @Composable () -> Unit = {
        if (transcribingShown.occupiesTitleSlot) {
            TranscribingPill(state = transcribingShown, onClick = { showTranscribingSheet = true })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SupportPill(onClick = { showSupport = true })
                // Where the Telegram card goes when the user long-presses it away — moved, not dismissed,
                // because someone who tucks it away has usually joined and may still want the way back.
                // Long-pressing here undoes it, which is the only way back: nothing else mentions it.
                if (communityTucked) {
                    Spacer(Modifier.width(8.dp))
                    CommunityPill(
                        onClick = { context.openTelegramGroup() },
                        onLongClick = {
                            preferences.setCommunityTucked(false)
                            communityTucked = false
                            Toast.makeText(context, R.string.home_community_restored_hint, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }

    // One section at a time, and none of them while a recording is open over the lot — or while a
    // transcript is being read on a page of its own, which likewise replaces the section rather than
    // sitting over it. Both are drawn below, as the only other full-screen things this shell emits.
    //
    // The switch is here, below every piece of state above it, and that placement is the point: see
    // the function's KDoc. Nothing in a section branch may remember anything it would mind losing.
    if (playbackFor == null && readingFor == null) when (section) {

    HomeSection.Hub -> {
        // Re-read on arrival rather than once for the life of the shell. The guard inside
        // LibraryCounts answers "is there a transcripts database?" at the moment it is called, and a
        // flow remembered before the user's first transcription would keep answering zero until the
        // app was restarted. Keyed on the section, so every visit to the hub asks again — and the
        // hub is the only thing that asks at all.
        val transcriptsCount by remember(section) { LibraryCounts.transcribed(context) }
            .collectAsState(initial = 0)
        val summariesCount by remember(section) { LibraryCounts.summarised(context) }
            .collectAsState(initial = 0)

        HubScreen(
            modifier = modifier,
            recordingsCount = uiState.recordings.size,
            transcriptsCount = transcriptsCount,
            summariesCount = summariesCount,
            listState = hubGridState,
            onOpenSection = onSelectSection,
            onOpenSettings = onOpenSettings,
            onOpenCommunity = { context.openTelegramGroup() },
            onTuckCommunity = {
                preferences.setCommunityTucked(true)
                communityTucked = true
                Toast.makeText(context, R.string.home_community_tucked_hint, Toast.LENGTH_LONG).show()
            },
            communityTucked = communityTucked,
            titleTrailing = titleTrailing,
            // The state of the app, all on the page a notification about it now lands on. Kept off
            // the recordings list rather than drawn in both places: a banner in two places is two
            // places to dismiss it, and the point of the hub is that the list is only the list.
            statusCards = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroStatusCard(
                        status = uiState.status,
                        health = uiState.setupHealth,
                        mode = uiState.privilegedMode,
                        onAction = if (uiState.status == HomeViewModel.HomeStatus.UPDATE_REGRANT_NEEDED) {
                            { context.openWirelessDebugging() }
                        } else {
                            null
                        },
                    )
                    if (uiState.usbScreenLockRisk) {
                        UsbReliabilityAdvisoryCard(
                            fixing = uiState.usbFixInProgress,
                            blockedByRecording = uiState.usbFixBlockedByRecording,
                            onFix = { viewModel.setUsbChargingOnly() },
                        )
                    }
                    uiState.updatedToVersion?.let { version ->
                        UpdatedBannerCard(
                            version = version,
                            onDismiss = { viewModel.dismissUpdatedBanner() }
                        )
                    }
                    uiState.availableUpdateTag?.let { tag ->
                        UpdateBannerCard(
                            tag = tag,
                            isInstalling = uiState.isUpdateInstalling,
                            progressPercent = uiState.updateProgressPercent,
                            onUpdate = { viewModel.installAvailableUpdate() }
                        )
                    }
                }
            },
        )
    }

    HomeSection.Transcripts -> {
        // One query for the page, re-read on arrival for the same reason the hub's counts are: the
        // guard inside LibraryCounts answers "is there a transcripts database?" when it is called,
        // and a flow remembered before the user's first transcription would answer empty for ever.
        val entries by remember(section) { LibraryCounts.transcripts(context) }
            .collectAsState(initial = emptyList())
        // The transcribe-only imports go in as well, so one that nothing else is accounting for —
        // a stopped run, a refusal, a process death before the queue heard about it — is listed
        // here rather than existing on disk and in no list at all. See TranscriptsPage.Groups.waiting.
        val groups = remember(entries, uiState.transcribeOnly) {
            TranscriptsPage.group(entries, uiState.transcribeOnly.map { it.displayName })
        }

        TranscriptsScreen(
            modifier = modifier,
            groups = groups,
            recordings = libraryRecordings,
            transcribing = transcribingShown,
            listState = transcriptsListState,
            onBack = { onSelectSection(HomeSection.Hub) },
            onOpenSettings = onOpenSettings,
            titleTrailing = titleTrailing,
            onSearch = { showTranscriptSearch = true },
            onOpenQueue = { showTranscribingSheet = true },
            // A page of its own, not the sheet. Here the transcript is the destination rather than a
            // look at something you are already standing on, and a sheet over a list of transcripts
            // would be a transcript over a list of transcripts.
            onOpen = { displayName ->
                readingFor = displayName
                readingFromSummary = false
            },
            // The same gate every other entry point uses: without it a retry on a phone whose model
            // has been deleted would fail exactly the silent way the first attempt did.
            onRetry = { displayName -> startTranscription(displayName) },
            // The waiting group's card: its audio is still on the phone, and its own screen is where
            // playing, sharing and deleting it live. Setting playbackFor directly rather than
            // changing section — the recording is deliberately not in Recordings to go back to.
            onOpenAudio = { displayName -> playbackFor = displayName },
            onImport = { importPicker.launch(arrayOf(IMPORT_MIME_FILTER)) },
            importing = uiState.isImporting,
        )
    }

    HomeSection.Summaries -> {
        // Two sources, one observer each, both above the list. Re-read on arrival for the reason
        // the hub's counts are: the guard inside LibraryCounts answers "is there a transcripts
        // database?" when it is called, and a flow remembered before the user's first summary would
        // answer empty for ever.
        val names by remember(section) { LibraryCounts.summarisedNames(context) }
            .collectAsState(initial = emptyList())
        // The queue, for the whole page at once. rememberSummaryState is per-recording: asked on a
        // row it would put one WorkManager observer and two database observers behind every visible
        // line of the list.
        val workManager = remember(context) { WorkManager.getInstance(context) }
        val workInfos by remember(workManager) {
            workManager.getWorkInfosForUniqueWorkFlow(SummaryScheduler.WORK_NAME)
        }.collectAsState(initial = emptyList())
        val groups = remember(names, workInfos) {
            SummariesPage.group(names, SummariesPage.jobsOf(workInfos))
        }

        SummariesScreen(
            modifier = modifier,
            groups = groups,
            recordings = libraryRecordings,
            listState = summariesListState,
            onBack = { onSelectSection(HomeSection.Hub) },
            onOpenSettings = onOpenSettings,
            titleTrailing = titleTrailing,
            // The reading view, not the recording's own screen, for three reasons that all point
            // the same way. It already carries the summary at the top, above the words it was
            // written from — which is what anyone checks when a summary looks wrong. It is the same
            // destination a Transcripts row opens, so one rule covers every library row. And it is
            // the only one of the two that can open a row whose recording is gone: the playback
            // screen finds no catalog row and closes itself again, so a tap on an orphan would be
            // swallowed in silence — on a page that exists to keep orphans listed.
            //
            // Leaving it stops the audio, which the page frame already does for Transcripts (#27).
            onOpen = { displayName ->
                readingFor = displayName
                readingFromSummary = true
            },
            // No confirmation, like the card's own Stop: stopping is the safe direction, and the
            // abort has to come first because cancelling the worker does not interrupt a generate.
            onStop = { SummaryScheduler.stopNow(context) },
        )
    }

    HomeSection.Recordings -> CvScaffold(
        modifier = modifier.fillMaxSize(),
        // Named for the section, not the app. The app's name belongs on the hub, which is what a
        // back arrow here now leads to — a section titled "CallVault" with an arrow back to
        // CallVault says nothing about where either of them goes.
        title =
            if (selectionMode) pluralStringResource(R.plurals.home_selected_count, selection.size, selection.size)
            else stringResource(R.string.home_recordings_title),
        // Leaving selection mode comes first: while rows are selected the arrow has to undo that,
        // not the navigation, or the only way out of selection would be the close button.
        onBack = if (selectionMode) clearSelection else ({ onSelectSection(HomeSection.Hub) }),
        // Nothing beside the title while selecting: the title is a count of what is selected, and a
        // pill next to it would read as part of it.
        titleTrailing = if (selectionMode) null else titleTrailing,
        actions = {
            if (selectionMode) {
                IconButton(onClick = {
                    context.shareRecordings(RecordingSelection.urisToShare(selectedItems))
                    clearSelection()
                }) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.home_share),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showBulkDelete = true }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.home_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                IconButton(onClick = { showTranscriptSearch = true }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.home_search_transcripts),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.home_open_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // The status card and the update banners are on the hub, not here. They are about the
            // app rather than about this list, the notifications that raise them now route to the
            // hub, and drawing them in both places would make each of them two things to dismiss.

            val recordings = uiState.filteredRecordings

            // The count alone. The section header that used to sit beside it said "RECORDINGS"
            // under a bar that now says the same thing, because this is a section with its own
            // title rather than one block on a single Home screen.
            if (recordings.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.home_recordings_count, recordings.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
                    )
                }
            }

            // Faceted, non-wrapping filter chips shown once there is anything to filter.
            if (uiState.recordings.isNotEmpty()) {
                item {
                    RecordingFilterBar(
                        sourceFilter = uiState.sourceFilter,
                        directionFilter = uiState.directionFilter,
                        contactFilter = uiState.contactFilter,
                        dateFilter = uiState.dateFilter,
                        availableContacts = uiState.availableContacts,
                        availableDates = uiState.availableDates,
                        tagFilter = uiState.tagFilter,
                        availableTags = uiState.availableTags,
                        onTagFilterChange = { viewModel.setTagFilter(it) },
                        favouritesOnly = uiState.favouritesOnly,
                        hasFavourites = uiState.favourites.isNotEmpty(),
                        onFavouritesOnlyChange = { viewModel.setFavouritesOnly(it) },
                        onSourceFilterChange = { viewModel.setSourceFilter(it) },
                        onDirectionFilterChange = { viewModel.setDirectionFilter(it) },
                        onContactFilterChange = { viewModel.setContactFilter(it) },
                        onDateFilterChange = { viewModel.setDateFilter(it) }
                    )
                }
            }

            if (recordings.isEmpty()) {
                // Only once the list has actually been read. Before that the list is *unknown*, and
                // announcing "no recordings yet" to someone with sixty of them — for the half second
                // it takes to read the folder — is simply wrong.
                if (uiState.hasLoaded) item { EmptyRecordings() }
            } else {
                items(recordings, key = { it.uri.toString() }) { item ->
                    RecordingRow(
                        item = item,
                        playback = playback,
                        deleting = item.uri in uiState.deletingUris,
                        selectionMode = selectionMode,
                        selected = item.uri in selection,
                        onToggleSelected = {
                            selection = if (item.uri in selection) selection - item.uri
                                        else selection + item.uri
                        },
                        // Playing opens the recording's own screen and starts it there. The inline
                        // strip could hold a play button and a slider and nothing else; skipping back
                        // over a missed sentence, and speed, are what a long call actually needs.
                        onPlayUri = { uri ->
                            playbackFor = item.displayName
                            viewModel.play(uri)
                        },
                        // Opens the recording. Deliberately does NOT start it: a tap meant to look
                        // at a call would begin playing it out loud, which on a recording of a
                        // private conversation is the wrong default in a room with other people.
                        // The play button is right there and says what it does.
                        onOpenPlayback = { playbackFor = item.displayName },
                        onPause = { viewModel.pausePlayback() },
                        onResume = { viewModel.resumePlayback() },
                        onSeek = { viewModel.seekTo(it) },
                        onDeleteAll = { viewModel.deleteRecording(item) },
                        onDeleteUri = { uri -> viewModel.deleteUri(uri) },
                        transcriptStatus = transcriptStatuses[item.displayName] ?: TranscriptStatus.NONE,
                        // Only the recording actually in hand gets the number; every other row that
                        // happens to be queued keeps spinning, because none of them has started.
                        transcriptPercent = transcribingShown.percentFor(item.displayName),
                        onTranscribe = { startTranscription(item.displayName) },
                        onOpenTranscript = { transcriptFor = item.displayName },
                        // Retry runs through the same gate: without a model it would fail exactly the
                        // same silent way a first attempt does.
                        onRetryTranscript = { startTranscription(item.displayName) },
                        onMerge = { mergeFor = item },
                        // Only offered on a recording that actually came from a merge; there is
                        // nothing to take apart otherwise.
                        mergedPartCount = mergedCounts[item.displayName] ?: 0,
                        onUnMerge = if (item.displayName in mergedCounts) {
                            {
                                mergeScope.launch {
                                    unMergeLabels = viewModel.mergedPartLabels(item.displayName)
                                    unMergeFor = item
                                }
                            }
                        } else null
                    )
                }
            }
        }

        // Back to the top from anywhere, without flinging. Appears only once scrolling has actually
        // buried the top of the list, so a short library never carries a control it does not need.
        androidx.compose.animation.AnimatedVisibility(
            visible = listState.firstVisibleItemIndex > 3,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = innerPadding.calculateTopPadding() + 12.dp)
        ) {
            FilledTonalButton(
                onClick = { listScope.launch { listState.animateScrollToItem(0) } },
                // Stated, not defaulted. FilledTonalButton takes secondaryContainer, which in this
                // scheme is CoralDeep — so the default renders a red button in a teal app. The same
                // trap has been hit here before; see the M3-defaults note.
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.home_scroll_to_top))
            }
        }
        }
    }

    } // end of the section switch

    // Sheets and dialogs any section can raise, and the playback screen with them.
    //
    // Kept OUT of every scaffold. A scaffold is not composed while a recording is open, so a
    // confirmation raised from the playback screen was queued and only appeared once the user went
    // back to the list — which is exactly how it was found. Sections make the same mistake available
    // three more ways: a sheet left inside the recordings scaffold would vanish the moment the user
    // reached the hub, and reappear on the way back.
    if (showTranscribingSheet) {
        TranscribingSheet(
            state = transcribing,
            recordings = libraryRecordings,
            onDismiss = { showTranscribingSheet = false },
            onStopped = { showTranscribingSheet = false }
        )
    }

    if (showTranscriptSearch) {
        TranscriptSearchSheet(
            // The whole library, not uiState.filteredRecordings: someone searching is looking for
            // a call they could not find by scrolling, and an active filter would hide it.
            recordings = libraryRecordings,
            onDismiss = { showTranscriptSearch = false },
            onOpen = { row ->
                showTranscriptSearch = false
                // Raised from the Transcripts page, a hit opens the transcript it was found in and
                // plays from there. On the recordings list it only plays, unchanged: there the row
                // is tinted behind the dismissed sheet, so something on screen owns the sound —
                // whereas a list of transcripts shows nothing about playback, and starting a private
                // call out loud over it would leave nothing anywhere to stop it (#27).
                if (section == HomeSection.Transcripts) {
                    readingFor = row.displayName
                    readingFromSummary = false
                }
                viewModel.playFrom(row.uri, row.startMs.toInt())
            }
        )
    }

    if (showBulkDelete) {
        BulkDeleteDialog(
            items = selectedItems,
            needsScopeChoice = RecordingSelection.needsScopeChoice(selectedItems),
            onConfirm = { scope ->
                showBulkDelete = false
                viewModel.deleteUris(RecordingSelection.urisToDelete(selectedItems, scope))
                clearSelection()
            },
            onDismiss = { showBulkDelete = false },
        )
    }
    if (showSupport) {
        SupportDialog(onDismiss = { showSupport = false })
    }
    // The appeal follows the release note rather than replacing it: the note is the reason the
    // user has the app open, and asking mid-note would bury what changed.
    if (showSupportAppeal) {
        SupportDialog(onDismiss = { showSupportAppeal = false })
    }
    if (uiState.showWhatsNew) {
        // Persist the version so the note never reappears for this build, and clear the small
        // "updated" banner too.
        val dismiss = {
            viewModel.markWhatsNewSeen()
            viewModel.dismissUpdatedBanner()
            showSupportAppeal = true
        }
        WhatsNewDialog(onDismiss = dismiss, onOpenSettings = { dismiss(); onOpenSettings() })
    }

    mergeFor?.let { primary ->
        MergeCallsDialog(
            primary = primary,
            candidates = viewModel.mergeCandidates(primary),
            keepOriginals = AppPreferences(context).isKeepOriginalsAfterMerge(),
            progress = mergeProgress,
            onConfirm = { picked ->
                val total = picked.size + 1
                val candidates = viewModel.mergeCandidates(primary)
                val seconds = (primary.durationSeconds ?: 0L) + picked.sumOf { name ->
                    candidates.firstOrNull { it.displayName == name }?.durationSeconds ?: 0L
                }
                // The card stays open and its contents change. Closing it and opening a second
                // dialog meant one surface disappearing and another arriving over it, which flashed.
                mergeProgress = MergeProgressState(
                    isUnMerge = false,
                    current = 1,
                    total = total,
                    detail = mergeDetail(context, total, seconds),
                )
                viewModel.merge(
                    primary.displayName,
                    picked,
                    onPartProgress = { index ->
                        mergeProgress = mergeProgress?.copy(current = (index + 1).coerceAtMost(total))
                    }
                ) { problem ->
                    // A refusal is the user's to act on — different codecs, or a call that is only
                    // in Drive — so it is said out loud rather than logged and swallowed.
                    if (problem != null) {
                        mergeProgress = null
                        mergeFor = null
                        Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                    } else {
                        mergeProgress = mergeProgress?.copy(current = total, finished = true)
                    }
                }
            },
            onDismiss = { mergeFor = null },
            onCloseProgress = {
                mergeProgress = null
                mergeFor = null
            }
        )
    }

    unMergeFor?.let { merged ->
        UnMergeDialog(
            partLabels = unMergeLabels,
            progress = mergeProgress,
            onConfirm = {
                val total = unMergeLabels.size
                mergeProgress = MergeProgressState(
                    isUnMerge = true,
                    current = 1,
                    total = total,
                    detail = context.resources.getQuantityString(R.plurals.merge_row_badge, total, total),
                )
                viewModel.unMerge(
                    merged.displayName,
                    onPartProgress = { index, _ ->
                        mergeProgress = mergeProgress?.copy(current = (index + 1).coerceAtMost(total))
                    }
                ) { problem ->
                    if (problem != null) {
                        mergeProgress = null
                        unMergeFor = null
                        Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                    } else {
                        mergeProgress = mergeProgress?.copy(current = total, finished = true)
                    }
                }
            },
            onDismiss = { unMergeFor = null },
            onCloseProgress = {
                mergeProgress = null
                unMergeFor = null
            }
        )
    }

    // The transcript, in whichever frame it was opened in. ONE block of plumbing for both: the data
    // a transcript needs — the text, the summary, the note, the tags, the speaker mapping, the
    // export labels — is the same wherever it is drawn, and a second copy of it for the reading view
    // would be the place the two silently stopped agreeing.
    //
    // A sheet when the recordings list or a recording's own screen raised it; a page when the
    // Transcripts section did. Never both, so the frame is derived rather than stored twice.
    //
    // Outside every scaffold, for the reason given above.
    val readingPresentation = readingFor != null && playbackFor == null
    val openTranscriptFor = if (readingPresentation) readingFor else transcriptFor
    openTranscriptFor?.let { displayName ->
        // Unread until the query answers, so the page can tell "still loading" from "there is none"
        // — the second is reachable now that a summary's row opens this, and deleting the text
        // leaves the summary behind.
        val read by remember(displayName) {
            TranscriptRepository.transcript(context, displayName)
        }.collectAsState(initial = TranscriptRepository.TranscriptRead.Unread)
        val transcript = (read as? TranscriptRepository.TranscriptRead.Read)?.transcript

        val row = libraryRecordings.firstOrNull { it.displayName == displayName }

        // Explicitly row != null: activeUri is null when nothing is loaded, so comparing it against
        // a null row?.uri would call an idle player "this recording".
        val isThisTrack = row != null && playback.activeUri == row.uri

        val sheetSummary by rememberSummaryState(displayName)
        val title = RecordingLabel.of(row) ?: BidiText.isolate(displayName)

        // Re-read whenever a transcript is opened rather than held for the life of the screen: the
        // mapping is learned in the background from calls that happen while the app is running, and
        // it can be un-learned by deleting the calls that taught it.
        // Bumped by a swap so the names redraw without waiting for the sheet to be reopened.
        var speakerMapNonce by remember(displayName) { mutableIntStateOf(0) }
        val channelMap by produceState(ChannelMap.UNKNOWN, displayName, speakerMapNonce) {
            value = SpeakerTurnsRepository.trustedMap(context)
        }
        val speakersConfirmed = remember(speakerMapNonce) {
            AppPreferences(context).getSpeakerMapConfirmed()
        }

        // The note and the tags travel with an export. Read here rather than inside the sheet so
        // the sheet stays a pure rendering of what it is handed, like everything else it shows.
        val sheetNote by remember(displayName) {
            RecordingExtrasRepository.note(context, displayName)
        }.collectAsState(initial = "")
        val sheetTags by remember(displayName) {
            TagRepository.tagsFor(context, displayName)
        }.collectAsState(initial = emptyList())

        // Resolved here because onExport runs from a click, outside composition, where
        // stringResource cannot be called. Without this the Markdown export writes its headings in
        // English while the screen behind it shows them translated.
        val exportLabels = rememberExportLabels()

        /**
         * Leaving the transcript.
         *
         * The page stops the audio; the sheet does not, and that difference is the whole reason the
         * two frames are told apart. Dismissing the sheet puts the user back on the recordings list,
         * where the playing row is tinted and its own screen is one tap away — something on screen
         * still owns the sound. Leaving the page puts them on a list of transcripts that says nothing
         * about playback at all, so a recording left running there would be playing a private
         * conversation out loud with nothing anywhere to stop it. That is issue #27's complaint, and
         * it is the same rule the playback screen's own back arrow follows.
         */
        val closeTranscript = {
            if (readingPresentation) {
                viewModel.stopPlayback()
                readingFor = null
            } else {
                transcriptFor = null
            }
        }

        // Back leaves the page, exactly as the arrow does. Registered here rather than in the router,
        // so it outranks the router's "back returns to the hub": Compose hands back to the most
        // recently composed enabled handler, and this is composed below that one.
        BackHandler(enabled = readingPresentation) { closeTranscript() }

        TranscriptView(
            transcript = transcript,
            isTranscriptSettled = read is TranscriptRepository.TranscriptRead.Read,
            title = title,
            presentation = if (readingPresentation) TranscriptPresentation.Screen
                           else TranscriptPresentation.Sheet,
            summaryFirst = readingPresentation && readingFromSummary,
            modifier = modifier,
            note = sheetNote,
            tags = sheetTags,
            positionMs = if (isThisTrack) playback.positionMs.toLong() else -1L,
            durationMs = if (isThisTrack) playback.durationMs.toLong() else 0L,
            isPlaying = isThisTrack && playback.phase == RecordingPlaybackController.Phase.PLAYING,
            isLoading = isThisTrack && playback.phase == RecordingPlaybackController.Phase.LOADING,
            onDismiss = closeTranscript,
            // playFrom, not seekTo: seekTo only works on a track already prepared, so tapping a
            // line in a recording that is not playing used to do nothing at all.
            onSeekTo = { startMs ->
                row?.uri?.let { viewModel.playFrom(it, startMs.toInt()) }
            },
            onPlay = { row?.uri?.let { viewModel.play(it) } },
            onPause = { viewModel.pausePlayback() },
            onResume = { viewModel.resumePlayback() },
            onSeek = { viewModel.seekTo(it) },
            onSkip = { viewModel.skipPlayback(it) },
            onCopy = { text -> context.copyToClipboard(displayName, text) },
            onShare = { text -> context.sharePlainText(displayName, text) },
            onExport = { format, document ->
                val file = TranscriptExportFile.write(
                    context = context,
                    fileName = TranscriptExport.fileName(format, displayName),
                    content = TranscriptExport.render(format, document, exportLabels)
                )
                // A failed write is reported rather than passed over: the user tapped a format and
                // is waiting for a chooser, so silence would read as the tap not registering and
                // invite them to try again into the same full cache.
                if (file == null) {
                    Toast.makeText(
                        context,
                        R.string.transcript_export_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    context.shareTranscriptFile(file, format.mimeType)
                }
            },
            // Leaving without stopping the audio even on the page: a re-transcription throws the
            // stored text away, so there is nothing left to read, but the recording is still the
            // recording and is now back in a list that shows it playing.
            onRetranscribe = {
                startTranscription(displayName)
                transcriptFor = null
                readingFor = null
            },
            onDelete = {
                // The SHEET is closed first: an AlertDialog raised over a ModalBottomSheet leaves
                // the user looking at the very text they asked to destroy. Cancelling puts it back —
                // losing the transcript you were reading because you thought better of deleting it
                // is a punishment for changing your mind (mirror176, #27).
                //
                // The PAGE stays put, because a dialog over a screen is ordinary and does not cover
                // what it is asking about. Nothing to restore on cancel, and nothing flashes away
                // and back for someone who only wanted to think about it.
                if (!readingPresentation) {
                    transcriptFor = null
                    reopenTranscriptAfterDelete = displayName
                }
                deleteTranscriptFor = displayName
            },
            // The contact's name is the one already in the header, so a labelled line reads as
            // part of the same conversation rather than introducing a second way to say who called.
            speakerNames = SpeakerNames(
                map = channelMap,
                you = stringResource(R.string.transcript_speaker_you),
                contact = title,
                sideA = stringResource(R.string.transcript_speaker_a),
                sideB = stringResource(R.string.transcript_speaker_b),
                // Unsettled until the user says otherwise — including when nothing has been
                // worked out, which is when being asked is worth the most: the convention needs two
                // agreeing calls, and until then this is the only way to get names at all.
                isGuess = !speakersConfirmed
            ),
            onChooseSpeakerMap = { chosen ->
                val prefs = AppPreferences(context)
                prefs.setSpeakerMapOverride(chosen.key)
                prefs.setSpeakerMapConfirmed(true)
                speakerMapNonce++
            },
            onConfirmSpeakers = {
                // Confirming stores no mapping: agreeing with what was worked out leaves it free to
                // improve as more calls are recorded, where pinning it would freeze today's guess.
                AppPreferences(context).setSpeakerMapConfirmed(true)
                speakerMapNonce++
            },
            summaryState = sheetSummary,
            onSummarise = { SummaryScheduler.runNow(context, displayName) },
            onStopSummary = { SummaryScheduler.stopNow(context) }
        )
    }

    confirmDeleteFor?.let { displayName ->
        val row = uiState.recordings.firstOrNull { it.displayName == displayName }
        if (row == null) {
            confirmDeleteFor = null
        } else {
            DeleteCopiesDialog(
                item = row,
                name = RecordingLabel.of(row) ?: BidiText.isolate(displayName),
                onConfirm = { scope ->
                    confirmDeleteFor = null
                    // Leave the screen only once it is settled: the recording is about to stop
                    // existing, and the list is where the result belongs. Cancelling stays put.
                    playbackFor = null
                    deleteByScope(
                        item = row,
                        scope = scope,
                        onDeleteAll = { viewModel.deleteRecording(row) },
                        onDeleteUri = { viewModel.deleteUri(it) }
                    )
                },
                onDismiss = { confirmDeleteFor = null }
            )
        }
    }

    confirmTranscribe?.let { (displayName, estimateMs, language) ->
        // Before this phone has timed a single run, the number comes from figures published for
        // other hardware — right to within a factor of two or three, which is not a promise worth
        // making to someone watching a progress bar. Say so plainly instead; every run after the
        // first quotes a measured figure.
        val isFirstRun = remember(displayName) {
            val prefs = AppPreferences(context)
            val model = TranscriptionModel.fromId(prefs.getTranscriptionModelId())
                ?: TranscriptionModel.DEFAULT
            !prefs.hasMeasuredRun(model.id)
        }
        TranscribeConfirmDialog(
            title = RecordingLabel.forDisplayName(libraryRecordings, displayName),
            estimate = estimateMs?.let { formatEstimate(it) },
            isFirstRun = isFirstRun,
            onDismiss = { confirmTranscribe = null },
            onConfirm = { dontAskAgain ->
                if (dontAskAgain) AppPreferences(context).setTranscriptionConfirmBeforeRun(false)
                confirmTranscribe = null
                enqueueTranscription(displayName, language)
            }
        )
    }

    askLanguageFor?.let { displayName ->
        TranscribeLanguageDialog(
            title = RecordingLabel.forDisplayName(libraryRecordings, displayName),
            setting = AppPreferences(context).getTranscriptionLanguage(),
            onDismiss = { askLanguageFor = null },
            onConfirm = { language ->
                askLanguageFor = null
                continueTranscription(displayName, language)
            }
        )
    }

    tooLongMinutes?.let { minutes ->
        AlertDialog(
            onDismissRequest = { tooLongMinutes = null },
            title = { Text(stringResource(R.string.transcript_too_long_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.transcript_too_long_message,
                        minutes,
                        TranscriptionLengthLimit.MAX_MINUTES,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { tooLongMinutes = null }) {
                    Text(stringResource(R.string.general_ok))
                }
            }
        )
    }

    // A dialog rather than a toast, because every one of these is something the user has to do
    // differently next time — pick another file, choose a folder — and a toast that appears while
    // they are still looking at the picker's closing animation is a message nobody reads.
    uiState.importRefusal?.let { reason ->
        AlertDialog(
            onDismissRequest = { viewModel.importRefusalSeen() },
            title = { Text(stringResource(R.string.import_failed_title)) },
            text = { Text(stringResource(importRefusalMessage(reason))) },
            confirmButton = {
                TextButton(onClick = { viewModel.importRefusalSeen() }) {
                    Text(stringResource(R.string.general_ok))
                }
            }
        )
    }

    if (showModelMissing) {
        AlertDialog(
            onDismissRequest = { showModelMissing = false },
            title = { Text(stringResource(R.string.transcript_no_model_title)) },
            text = { Text(stringResource(R.string.transcript_no_model_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showModelMissing = false
                    onOpenSettings()
                }) {
                    Text(stringResource(R.string.transcript_no_model_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showModelMissing = false }) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        )
    }

    deleteTranscriptFor?.let { displayName ->
        val row = libraryRecordings.firstOrNull { it.displayName == displayName }
        val label = RecordingLabel.of(row) ?: BidiText.isolate(displayName)
        DeleteRecordingDialog(
            name = label,
            title = stringResource(R.string.transcript_delete_confirm_title),
            message = stringResource(R.string.transcript_delete_confirm_message, label),
            onConfirm = {
                transcriptScope.launch { TranscriptRepository.delete(context, displayName) }
                // Deleted, so there is nothing to go back to — and nothing left on the page that was
                // showing it, which is still composed underneath this dialog. Leaving it there would
                // hold a reader on a page whose text is being removed from under them, and leave the
                // audio playing on a screen that no longer has a reason to exist.
                reopenTranscriptAfterDelete = null
                if (readingFor == displayName) {
                    viewModel.stopPlayback()
                    readingFor = null
                }
                deleteTranscriptFor = null
            },
            onDismiss = {
                deleteTranscriptFor = null
                transcriptFor = reopenTranscriptAfterDelete
                reopenTranscriptAfterDelete = null
            }
        )
    }
}

/**
 * What the SAF picker is asked to offer.
 *
 * The audio wildcard rather than the exact list [ImportableAudio] accepts, because a provider is
 * free to report a type of its own for a file it knows perfectly well — a WhatsApp voice note comes
 * back as `application/octet-stream` from more than one file manager, and naming exact types would
 * hide it from the picker entirely. The narrower check happens after the file is chosen, where a
 * refusal can say why.
 */
private const val IMPORT_MIME_FILTER = "audio/*"

// The sentence for a refused import — one per reason; "that didn't work" is what makes people retry
// — now lives in ShareImportScreen.kt, because the share sheet refuses the same files for the same
// reasons and a second set of wordings would be a second set to keep translated.

/**
 * Post-update "What's new" note: the last few releases, newest first, each labelled with its version.
 *
 * Per-release rather than per-feature. Updates are not always taken one at a time, and an earlier
 * design showed a single feature and let the rest go unmentioned.
 *
 * **The newest release is the only one written out, and it is written as one line per change.** It
 * used to be three releases of prose, and a reporter said plainly what that costs: a wall of text to
 * parse through, so people skip the notes entirely. Anyone who skipped a version still sees that they
 * did — the releases before this one keep their headline, which is one line each — and the whole
 * story stays a tap away in the release notes, where being thorough costs nobody anything.
 */
@Composable
private fun WhatsNewDialog(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_whatsnew_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                val releases = ReleaseHighlights.recent()
                releases.firstOrNull()?.let { ReleaseNote(it) }
                releases.drop(1).forEach { EarlierReleaseLine(it) }
                FullReleaseNotesLink()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.home_whatsnew_settings_cta))
            }
        },
    )
}

/** The newest release: version chip, headline, one line per change, and where to switch it on. */
@Composable
private fun ReleaseNote(release: ReleaseHighlight) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ReleaseHeadline(release)
        release.items?.let { items ->
            stringArrayResource(items).forEach { change -> ChangeLine(change) }
        }
        release.whereToFind?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A release the reader skipped: its headline only, so it is visible without being read. */
@Composable
private fun EarlierReleaseLine(release: ReleaseHighlight) {
    ReleaseHeadline(release)
}

/** The version chip and the headline, shared by both. */
@Composable
private fun ReleaseHeadline(release: ReleaseHighlight) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = release.version,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(release.title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One change, as a bullet with a hanging indent so a line that wraps stays aligned. */
@Composable
private fun ChangeLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "\u2022",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Where the thorough version lives, for anyone who wants it. */
@Composable
private fun FullReleaseNotesLink() {
    val uriHandler = LocalUriHandler.current
    Text(
        text = stringResource(R.string.home_whatsnew_full_notes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { uriHandler.openUri(RELEASE_NOTES_URL) },
    )
}

/** The releases page rather than one tag, so it keeps working for whatever version is installed. */
private const val RELEASE_NOTES_URL = "https://github.com/madkongo/CallVault/releases"

/**
 * Dismissable confirmation shown once after an update lands ("CallVault updated to X.Y.Z"). Uses a
 * success (primary) tint with a check, and a close button that clears it for good.
 */
@Composable
private fun UpdatedBannerCard(version: String, onDismiss: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val tinted = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
    CvCard(color = tinted, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.home_updated_banner_text, version),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.general_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Gentle advisory shown under the hero card when the Default USB Configuration is a DATA mode — locking
 * the screen mid-call can then stop recording. NOT a red "broken" state (recording works otherwise), so
 * it uses a soft warning tint. Tapping it applies the one-tap fix (set USB to "Charging only"); a spinner
 * replaces the action while that runs.
 */
@Composable
private fun UsbReliabilityAdvisoryCard(fixing: Boolean, blockedByRecording: Boolean, onFix: () -> Unit) {
    val accent = LocalCvBrand.current.warning
    val tinted = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
    CvCard(
        color = tinted,
        onClick = if (fixing) null else onFix,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_usb_advisory_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        if (blockedByRecording) R.string.settings_usb_default_busy
                        else R.string.home_usb_advisory_text
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (fixing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = stringResource(R.string.home_usb_advisory_fix),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
        }
    }
}

/**
 * Slim banner shown under the hero card when a newer release is known. Tapping Update downloads,
 * verifies, and installs it (system confirm dialog may appear); while working the action shows a
 * small progress spinner. Auto-update users normally never see this — it appears only when the
 * silent path couldn't run (e.g. metered network or the shell being unavailable).
 */
@Composable
private fun UpdateBannerCard(
    tag: String,
    isInstalling: Boolean,
    progressPercent: Int,
    onUpdate: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val tinted = accent.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface)
    // While installing: a subtitle tracks the phase. Downloading (percent -1..99, -1 = not yet
    // reported) shows the download label; only once the download completes (percent >= 100) does it
    // switch to "Installing…". A determinate bar during download, indeterminate before/after.
    val subtitle = when {
        !isInstalling -> stringResource(R.string.home_update_banner_text)
        progressPercent >= 100 -> stringResource(R.string.home_update_banner_installing)
        else -> stringResource(R.string.home_update_banner_downloading, progressPercent.coerceAtLeast(0))
    }
    CvCard(color = tinted, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_update_banner_title, tag.removePrefix("v")),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isInstalling) {
                    Spacer(Modifier.height(6.dp))
                    if (progressPercent in 0..99) {
                        LinearProgressIndicator(
                            progress = { progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (isInstalling) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onUpdate) {
                    Text(stringResource(R.string.home_update_banner_button))
                }
            }
        }
    }
}

/**
 * A compact, tappable "♥ Support" pill shown next to the app title. Opens the maintainer's Ko-fi
 * page in the browser — an optional, low-key donation entry point that keeps the status card and the
 * recordings list uncluttered. The matching, more explicit ask lives in Settings → About.
 */
/**
 * The tucked-away Telegram invite. Deliberately quieter than [SupportPill]: it is a way back to something
 * the user has already seen, not a second thing asking for their attention.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommunityPill(onClick: () -> Unit, onLongClick: () -> Unit) {
    val accent = LocalCvBrand.current.accent
    Surface(
        shape = CircleShape,
        color = accent.copy(alpha = 0.12f),
        modifier = Modifier.clip(CircleShape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.home_community_pill),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = accent,
            )
        }
    }
}

@Composable
private fun SupportPill(onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = accent.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.home_support_pill),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = accent,
            )
        }
    }
}

/**
 * The flagship hero status banner. READY uses a confident teal-tinted surface with a check;
 * problem states use a warm warning/coral tint with a warning glyph, making the call to action
 * unmistakable.
 */
@Composable
private fun HeroStatusCard(
    status: HomeViewModel.HomeStatus,
    health: SetupHealth,
    mode: PrivilegedMode,
    onAction: (() -> Unit)? = null,
) {
    val brand = LocalCvBrand.current
    // A healthy setup that has never been proved still reads as ready; only a real problem flips the card.
    val showsProblem = !status.isReady || health.isProblem
    val accent: Color = if (showsProblem) brand.warning else MaterialTheme.colorScheme.primary
    val icon: ImageVector = if (showsProblem) Icons.Filled.WarningAmber else Icons.Filled.CheckCircle
    val tone = when (status) {
        HomeViewModel.HomeStatus.READY -> CvTone.Success
        HomeViewModel.HomeStatus.NOT_PAIRED -> CvTone.Warning
        HomeViewModel.HomeStatus.NO_FOLDER -> CvTone.Error
        HomeViewModel.HomeStatus.DEV_OPTIONS_OFF -> CvTone.Error
        HomeViewModel.HomeStatus.UPDATE_REGRANT_NEEDED -> CvTone.Warning
        // Error, not Warning: the recorder is down and the next call will be missed.
        HomeViewModel.HomeStatus.RECOVERY_STUCK -> CvTone.Error
        // Error for the same reason: with Shizuku stopped nothing can record, and it stops on every
        // reboot — this is the state a Shizuku user will meet most often and must not read as cosmetic.
        HomeViewModel.HomeStatus.SHIZUKU_NOT_READY -> CvTone.Error
    }
    val pillText = when (status) {
        HomeViewModel.HomeStatus.READY -> stringResource(R.string.home_hero_pill_ready)
        HomeViewModel.HomeStatus.NOT_PAIRED -> stringResource(R.string.home_hero_pill_not_paired)
        HomeViewModel.HomeStatus.NO_FOLDER -> stringResource(R.string.home_hero_pill_no_folder)
        HomeViewModel.HomeStatus.DEV_OPTIONS_OFF -> stringResource(R.string.home_hero_pill_dev_options_off)
        HomeViewModel.HomeStatus.UPDATE_REGRANT_NEEDED -> stringResource(R.string.home_hero_pill_update_regrant)
        HomeViewModel.HomeStatus.RECOVERY_STUCK -> stringResource(R.string.home_hero_pill_recovery_stuck)
        HomeViewModel.HomeStatus.SHIZUKU_NOT_READY -> stringResource(R.string.home_hero_pill_shizuku)
    }

    // Subtle accent-tinted surface so the banner reads as a confident state, not a stock card.
    val tinted = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)

    CvCard(color = tinted, onClick = onAction, contentPadding = PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_hero_status_label).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(status.titleResId),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (status.isReady) healthMessage(health) else stringResource(status.suggestionResId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        CvStatusPill(
            text = stringResource(
                R.string.home_hero_pill_with_mode,
                pillText,
                stringResource(
                    if (mode.needsShizuku) R.string.home_mode_badge_shizuku
                    else R.string.home_mode_badge_standalone
                ),
            ),
            tone = tone,
        )
    }
}

/**
 * The card's second line when nothing is blocking: what real calls proved. The happy path stays plain —
 * a byte count appears only in the empty-recording failure, never as reassurance.
 */
@Composable
private fun healthMessage(health: SetupHealth): String {
    // Read the ticking clock so the phrasing below re-renders as time passes. Without it the line is
    // computed once and frozen: a card left on screen still read "0 minutes ago" three minutes after
    // the call, because an unchanged SetupHealth means no state change and so no recomposition.
    val now = tickingNow()
    return when (health) {
        is SetupHealth.Verified ->
            if (now - health.atMillis < DateUtils.MINUTE_IN_MILLIS) {
                stringResource(R.string.home_health_verified_just_now)
            } else {
                stringResource(R.string.home_health_verified, relativeTime(health.atMillis))
            }
        is SetupHealth.Unverified -> stringResource(R.string.home_health_unverified)
        is SetupHealth.StaleAfterChange -> stringResource(R.string.home_health_setup_changed)
        is SetupHealth.CallNotRecorded -> health.label?.let {
            stringResource(R.string.home_health_call_not_recorded, relativeTime(health.atMillis), it)
        } ?: stringResource(R.string.home_health_call_not_recorded_unnamed, relativeTime(health.atMillis))
        is SetupHealth.CallMissedNotReady -> {
            // Named per prerequisite so the card states the actual, user-owned cause instead of
            // reading as the unexplained CallNotRecorded case above.
            val (named, unnamed) = when (health.prerequisite) {
                Prerequisite.RECORDING_FOLDER ->
                    R.string.home_health_missed_recording_folder to R.string.home_health_missed_recording_folder_unnamed
                Prerequisite.ADB_PAIRING ->
                    R.string.home_health_missed_adb_pairing to R.string.home_health_missed_adb_pairing_unnamed
                Prerequisite.DEVELOPER_OPTIONS ->
                    R.string.home_health_missed_developer_options to R.string.home_health_missed_developer_options_unnamed
                Prerequisite.SECURE_SETTINGS_GRANT ->
                    R.string.home_health_missed_secure_settings to R.string.home_health_missed_secure_settings_unnamed
                Prerequisite.SHIZUKU ->
                    R.string.home_health_missed_shizuku to R.string.home_health_missed_shizuku_unnamed
            }
            health.label?.let { stringResource(named, it) } ?: stringResource(unnamed)
        }
        is SetupHealth.LastCallFailed -> stringResource(
            when (health.reason) {
                FailureReason.EMPTY_FILE -> R.string.home_health_failed_empty
                FailureReason.NO_AUDIO -> R.string.home_health_failed_no_audio
                FailureReason.DAEMON_DIED -> R.string.home_health_failed_daemon
                FailureReason.ONE_SIDED -> R.string.home_health_failed_one_sided
            }
        )
    }
}

/**
 * Wall-clock time that advances while the card is on screen, so a relative phrase ages instead of
 * freezing at whatever it said when it was first drawn. Ticks once a minute — the finest resolution
 * any of these lines actually shows.
 */
@Composable
private fun tickingNow(): Long {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(DateUtils.MINUTE_IN_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** "2 hours ago", "Yesterday 18:44" — the platform's own phrasing, so it is localised for free. */
@Composable
private fun relativeTime(atMillis: Long): String = DateUtils.getRelativeDateTimeString(
    LocalContext.current, atMillis, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0
).toString()

/** One selectable entry inside a filter chip's dropdown menu. */
private data class FilterOption<T>(val value: T, val label: String)

/**
 * A wrapping [FlowRow] of compact, content-sized filter chips — one per facet
 * (Source / Direction / Contact / Date). Chips wrap to additional lines so all four are visible
 * without horizontal swiping. Each chip never wraps internally (single line, sized to content) and
 * opens a [DropdownMenu] of that facet's options. The Contact and Date facets are populated
 * dynamically from the currently loaded recordings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingFilterBar(
    sourceFilter: SourceFilter,
    directionFilter: DirectionFilter,
    contactFilter: String?,
    dateFilter: String?,
    availableContacts: List<String>,
    availableDates: List<String>,
    tagFilter: String?,
    availableTags: List<String>,
    onTagFilterChange: (String?) -> Unit,
    favouritesOnly: Boolean,
    hasFavourites: Boolean,
    onFavouritesOnlyChange: (Boolean) -> Unit,
    onSourceFilterChange: (SourceFilter) -> Unit,
    onDirectionFilterChange: (DirectionFilter) -> Unit,
    onContactFilterChange: (String?) -> Unit,
    onDateFilterChange: (String?) -> Unit
) {
    // Source facet options + current value label.
    val sourceOptions = listOf(
        FilterOption(SourceFilter.ALL, stringResource(R.string.home_filter_source_all)),
        FilterOption(SourceFilter.LOCAL, stringResource(R.string.home_filter_source_local)),
        FilterOption(SourceFilter.DRIVE, stringResource(R.string.home_filter_source_drive))
    )
    val sourceValueLabel = sourceOptions.first { it.value == sourceFilter }.label

    // Direction facet options + current value label.
    val directionOptions = listOf(
        FilterOption(DirectionFilter.ALL, stringResource(R.string.home_filter_direction_all)),
        FilterOption(DirectionFilter.INCOMING, stringResource(R.string.home_filter_direction_incoming)),
        FilterOption(DirectionFilter.OUTGOING, stringResource(R.string.home_filter_direction_outgoing))
    )
    val directionValueLabel = directionOptions.first { it.value == directionFilter }.label

    // Contact facet: dropdown keeps the full "All contacts" wording; the chip itself shows the
    // compact "All" so Contact + Date fit together on one line.
    val allContactsLabel = stringResource(R.string.home_filter_contact_all)
    val allContactsShort = stringResource(R.string.home_filter_contact_all_short)
    val contactOptions = buildList<FilterOption<String?>> {
        add(FilterOption(null, allContactsLabel))
        availableContacts.forEach { add(FilterOption(it, it)) }
    }
    val contactValueLabel = contactFilter ?: allContactsShort

    // Date facet: dropdown keeps "All dates"; the chip itself shows the compact "All".
    val allDatesLabel = stringResource(R.string.home_filter_date_all)
    val allDatesShort = stringResource(R.string.home_filter_date_all_short)
    val dateOptions = buildList<FilterOption<String?>> {
        add(FilterOption(null, allDatesLabel))
        availableDates.forEach { add(FilterOption(it, it)) }
    }
    val dateValueLabel = dateFilter ?: allDatesShort

    // Tag facet. Absent entirely until at least one tag exists: a chip reading "Tag: All" on a
    // library with no tags is a control that can only ever do nothing.
    val allTagsLabel = stringResource(R.string.home_filter_tag_all)
    val allTagsShort = stringResource(R.string.home_filter_tag_all_short)
    val tagOptions = buildList<FilterOption<String?>> {
        add(FilterOption(null, allTagsLabel))
        availableTags.forEach { add(FilterOption(it, it)) }
    }
    val tagValueLabel = tagFilter ?: allTagsShort

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Only once something is starred. Before that the chip would be a control whose every use
        // produces an empty list, sitting at the head of a row people read on every visit.
        if (hasFavourites) {
            ToggleFilterChip(
                text = stringResource(R.string.home_filter_favourites_chip),
                active = favouritesOnly,
                onToggle = { onFavouritesOnlyChange(!favouritesOnly) }
            )
        }
        FilterChip(
            text = stringResource(R.string.home_filter_source_chip, sourceValueLabel),
            active = sourceFilter != SourceFilter.ALL,
            options = sourceOptions,
            selected = sourceFilter,
            onSelected = onSourceFilterChange
        )
        FilterChip(
            text = stringResource(R.string.home_filter_direction_chip, directionValueLabel),
            active = directionFilter != DirectionFilter.ALL,
            options = directionOptions,
            selected = directionFilter,
            onSelected = onDirectionFilterChange
        )
        FilterChip(
            text = stringResource(R.string.home_filter_contact_chip, contactValueLabel),
            active = contactFilter != null,
            options = contactOptions,
            selected = contactFilter,
            onSelected = onContactFilterChange
        )
        if (availableTags.isNotEmpty()) {
            FilterChip(
                text = stringResource(R.string.home_filter_tag_chip, tagValueLabel),
                active = tagFilter != null,
                options = tagOptions,
                selected = tagFilter,
                onSelected = onTagFilterChange
            )
        }
        FilterChip(
            text = stringResource(R.string.home_filter_date_chip, dateValueLabel),
            active = dateFilter != null,
            options = dateOptions,
            selected = dateFilter,
            onSelected = onDateFilterChange
        )
    }
}

/**
 * A single compact filter chip + its dropdown. Sized to its content with a single, ellipsized line
 * (never wraps). When [active] (a non-default value is selected) it fills with a teal tint so the
 * user can see at a glance which facets are narrowing the list.
 */
@Composable
private fun <T> FilterChip(
    text: String,
    active: Boolean,
    options: List<FilterOption<T>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val containerColor =
        if (active) primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
    val contentColor =
        if (active) primary else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor =
        if (active) primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant

    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(containerColor)
                .border(1.dp, borderColor, CircleShape)
                .clickable { expanded = true }
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (option.value == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(option.value)
                    }
                )
            }
        }
    }
}

/**
 * A filter chip for a facet with exactly two states, toggled by a single tap.
 *
 * Deliberately not a [FilterChip] with an "All / Starred only" dropdown: that is two taps and a menu
 * to express a boolean. It carries the same fill, border and tint as an active [FilterChip] so the
 * row still reads as one set of controls, and swaps the dropdown caret for the star itself.
 */
@Composable
private fun ToggleFilterChip(
    text: String,
    active: Boolean,
    onToggle: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val containerColor =
        if (active) primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
    val contentColor =
        if (active) primary else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor =
        if (active) primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .border(1.dp, borderColor, CircleShape)
            .clickable(onClick = onToggle)
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A small, subtle pill indicating where a recording is stored: a smartphone glyph for LOCAL, a
 * cloud glyph for DRIVE, and both glyphs for BOTH. Uses a muted/teal tone to stay clean within the row.
 */
@Composable
private fun SourceBadge(source: RecordingSource) {
    val label = when (source) {
        RecordingSource.LOCAL -> stringResource(R.string.home_source_badge_local)
        RecordingSource.DRIVE -> stringResource(R.string.home_source_badge_drive)
        RecordingSource.BOTH -> stringResource(R.string.home_source_badge_both)
    }
    val color = when (source) {
        RecordingSource.LOCAL -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    // Glyphs only, for every source. BOTH already worked this way because its text truncated to
    // "Dev…"; now that the badge lives in the gutter under the play disc it has 62dp, which a text
    // label does not fit — and three states drawn the same way are easier to learn than two
    // conventions. The label survives as the accessible name, so nothing is lost to a screen reader.
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            // Sized to sit *inside* the play disc's 46dp. At its old padding the pill came to ~59dp,
            // so centring it under the disc overflowed both edges and read as misaligned rather than
            // as centred. Two glyphs plus this padding come to ~37dp, which leaves a clear, even
            // margin either side.
            .padding(horizontal = 5.dp, vertical = 4.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (source == RecordingSource.LOCAL || source == RecordingSource.BOTH) {
            Icon(
                imageVector = Icons.Filled.Smartphone,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(BADGE_GLYPH_SIZE)
            )
        }
        if (source == RecordingSource.BOTH) Spacer(Modifier.width(3.dp))
        if (source == RecordingSource.DRIVE || source == RecordingSource.BOTH) {
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(BADGE_GLYPH_SIZE)
            )
        }
    }
}

/** Friendly centered empty state shown when there are no recordings yet. */
@Composable
private fun EmptyRecordings() {
    CvCard(contentPadding = PaddingValues(vertical = 36.dp, horizontal = 24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_recordings_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_recordings_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * What a confirmed delete should remove. Drives both the confirm dialog message and the action:
 *  - [All]        — every same-named copy (delete-both, BOTH rows' main delete).
 *  - [Single]     — a single-source row's only copy (generic message).
 *  - [DeviceCopy] — only the Device copy of a BOTH recording.
 *  - [DriveCopy]  — only the Drive copy of a BOTH recording.
 */
/**
 * The per-row overflow menu: Share, then Delete.
 *
 * Delete costs one more tap than it did as a bare icon. That is the right trade for the only
 * destructive action in the list — and it leaves somewhere to put the next row action without
 * squeezing the name further.
 */
@Composable
private fun RecordingRowMenu(
    shareUri: Uri,
    shareName: String,
    onDelete: () -> Unit,
    /** Null on a row nothing can be merged into it — an import, which was never a call. */
    onMerge: (() -> Unit)? = null,
    onUnMerge: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(ROW_ACTION_SIZE)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.home_row_menu),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_share)) },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = {
                    open = false
                    context.shareRecording(shareUri, shareName)
                }
            )
            if (onMerge != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.merge_menu_action)) },
                    leadingIcon = { Icon(Icons.Filled.CallMerge, contentDescription = null) },
                    onClick = {
                        open = false
                        onMerge()
                    }
                )
            }
            if (onUnMerge != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.merge_menu_take_apart)) },
                    leadingIcon = { Icon(Icons.Filled.CallSplit, contentDescription = null) },
                    onClick = {
                        open = false
                        onUnMerge()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    open = false
                    onDelete()
                }
            )
        }
    }
}

/**
 * Confirms a bulk delete, and asks which copies when the answer is not obvious.
 *
 * The scope question only appears when the selection actually holds a recording kept both on the
 * device and in Drive. Asking every time would put a three-way choice in front of an unambiguous
 * delete; never asking would guess, and guessing here destroys files.
 */
@Composable
private fun BulkDeleteDialog(
    items: List<RecordingItem>,
    needsScopeChoice: Boolean,
    onConfirm: (DeleteScope) -> Unit,
    onDismiss: () -> Unit,
) {
    // Which copies to remove is ONE choice, so it is one field. Laying the three out as buttons —
    // whether beside Cancel or stacked down the dialog — turned a single decision into a wall of
    // actions, and put a destructive option next to the safe one.
    //
    // Defaults to both copies, matching what deleting a single row has always done for a recording
    // held in two places. Nothing is destroyed until Delete is pressed either way.
    var scope by rememberSaveable(stateSaver = DeleteScopeStateSaver) {
        mutableStateOf(DeleteScope.BOTH)
    }

    // Each option carries what it would actually do — "Device only (2 of 3)" — so the consequence
    // is visible while choosing rather than discovered afterwards from a recording that survived.
    val options = listOf(
        DeleteScope.BOTH to R.string.home_bulk_delete_both,
        DeleteScope.DEVICE to R.string.home_bulk_delete_device_only,
        DeleteScope.DRIVE to R.string.home_bulk_delete_drive_only,
    ).map { (s, labelRes) ->
        OptionItem(
            key = s.name,
            label = stringResource(
                R.string.home_bulk_delete_option_count,
                stringResource(labelRes),
                RecordingSelection.affectedCount(items, s),
                items.size,
            ),
        )
    }

    // Which recordings the chosen scope leaves alone, named where there are few enough to name.
    val skipped = RecordingSelection.skipped(items, scope)
    val keptSubject = when {
        skipped.isEmpty() -> null
        skipped.size <= MAX_NAMED_SKIPPED ->
            skipped.joinToString(", ") { RecordingLabel.of(it) ?: it.displayName }
        else -> pluralStringResource(R.plurals.home_bulk_delete_kept_count, skipped.size, skipped.size)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.home_bulk_delete_title, items.size, items.size)) },
        text = {
            Column {
                Text(
                    stringResource(
                        if (needsScopeChoice) R.string.home_bulk_delete_scope_message
                        else R.string.home_bulk_delete_message
                    )
                )
                if (needsScopeChoice) {
                    // No spacer: M3DropdownField carries its own vertical padding, and the two
                    // together left an empty band that made the card taller than its content.
                    M3DropdownField(
                        label = stringResource(R.string.home_bulk_delete_scope_label),
                        selected = options.first { it.key == scope.name },
                        options = options,
                        onOptionSelected = { scope = DeleteScope.valueOf(it.key) },
                    )
                    // Names what survives. A recording the user selected for deletion and did not
                    // get is the one outcome they must never have to discover for themselves.
                    if (keptSubject != null) {
                        Text(
                            text = stringResource(
                                if (scope == DeleteScope.DEVICE) R.string.home_bulk_delete_kept_drive
                                else R.string.home_bulk_delete_kept_device,
                                keptSubject,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        },
        // Both buttons live in the confirm slot as one centred row. AlertDialog otherwise packs its
        // two slots into the bottom-right corner, which left the actions hanging off one edge of a
        // wide card.
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onConfirm(scope) }) {
                    Text(stringResource(R.string.home_delete))
                }
            }
        },
    )
}

/** Above this many skipped recordings the dialog says "3 recordings" instead of listing them. */
private const val MAX_NAMED_SKIPPED = 2

private sealed interface DeleteTarget {
    /**
     * Ask which copies, then delete them. The row's own menu always uses this: whether the question
     * is worth asking depends on where the recording lives, and that is the dialog's judgement.
     */
    data object Ask : DeleteTarget
    data object All : DeleteTarget
    data class Single(val uri: Uri) : DeleteTarget
    data class DeviceCopy(val uri: Uri) : DeleteTarget
    data class DriveCopy(val uri: Uri) : DeleteTarget
}

/**
 * Applies a chosen [DeleteScope] to one recording.
 *
 * Shared by the row's overflow menu and the playback screen so the two cannot drift into deleting
 * different files from the same answer to the same question.
 */
private fun deleteByScope(
    item: RecordingItem,
    scope: DeleteScope,
    onDeleteAll: () -> Unit,
    onDeleteUri: (Uri) -> Unit
) {
    when {
        // One copy: delete exactly the file this row stands for, and nothing else.
        //
        // NOT the delete-every-same-named-copy path, even though it would usually agree. The card
        // says this recording is in one place; if the catalogue is stale and a second copy exists
        // that the row never showed, deleting it would destroy a file the user was not told about.
        item.source != RecordingSource.BOTH -> onDeleteUri(item.uri)

        // Both copies: the long-standing delete-everything-by-this-name path, unchanged. It is a
        // superset of the two URIs below, which is what "delete this recording" has always meant.
        scope == DeleteScope.BOTH -> onDeleteAll()

        // One side of a two-copy recording. Delegated rather than hand-rolled: RecordingSelection
        // is where the bulk delete works out which files a scope means, it is free of Compose, and
        // it is under test — the last property being the one that matters when the answer is a
        // list of files about to be destroyed.
        else -> RecordingSelection.urisToDelete(listOf(item), scope).forEach(onDeleteUri)
    }
}

/**
 * A single recording row: a circular teal disc (play affordance), name/date/number, and size.
 *
 * Behaviour depends on where the recording lives:
 *  - **Single-source** (LOCAL or DRIVE): tapping the row or disc plays its primary [uri] and the
 *    inline teal player (progress slider + elapsed/total + play/pause) is revealed beneath.
 *  - **BOTH**: tapping the row toggles an expanded dropdown listing the Device and Drive copies, each
 *    individually playable via its own teal disc and individually deletable via its own delete icon;
 *    the inline player attaches to whichever copy is playing. A chevron signals expandability.
 *
 * The main-row delete button deletes the whole recording: for single-source rows that is its only
 * copy ([onDeleteUri] on [RecordingItem.uri]); for BOTH rows it deletes every same-named copy
 * ([onDeleteAll]). Per-copy deletion lives inside the BOTH row's expanded sub-entries.
 *
 * @param onDeleteAll Deletes every same-named copy of this recording (delete-both).
 * @param onDeleteUri Deletes a single physical copy at the given Uri.
 */
@Composable
private fun RecordingRow(
    item: RecordingItem,
    playback: RecordingPlaybackController.PlaybackState,
    deleting: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onPlayUri: (Uri) -> Unit,
    onOpenPlayback: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteUri: (Uri) -> Unit,
    transcriptStatus: TranscriptStatus,
    onTranscribe: () -> Unit,
    onOpenTranscript: () -> Unit,
    onRetryTranscript: () -> Unit,
    onMerge: () -> Unit = {},
    /** How many calls this recording was merged from; 0 when it is an ordinary recording. */
    mergedPartCount: Int = 0,
    /** Null unless this recording was made by merging. */
    onUnMerge: (() -> Unit)? = null,
    transcriptPercent: Int = 0
) {
    // The pending delete target drives the confirm dialog: null = closed.
    //
    // Left as a plain remember on purpose, where the screen-level state above is saveable. This is a
    // confirmation for a destructive act, and it holds a Uri for THIS row; a rotation that dismissed
    // it has destroyed nothing, whereas restoring one bound to a row the refreshed list no longer
    // contains would leave a Delete button pointed at something the user can no longer see.
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val isBoth = item.source == RecordingSource.BOTH

    // Which copies' Uris belong to this row — used to decide whether the row is "active" overall.
    val rowUris = remember(item) { listOfNotNull(item.uri, item.localUri, item.driveUri) }
    val activeUri = playback.activeUri
    val isRowActive = activeUri != null && activeUri in rowUris

    // Prefer the contact name, then the parsed number, then the raw file name.
    val primaryLabel = RecordingLabel.of(item) ?: item.displayName

    deleteTarget?.let { target ->
        if (target is DeleteTarget.Ask) {
            DeleteCopiesDialog(
                item = item,
                name = primaryLabel,
                onConfirm = { scope ->
                    deleteTarget = null
                    deleteByScope(item, scope, onDeleteAll, onDeleteUri)
                },
                onDismiss = { deleteTarget = null }
            )
        } else {
            val message = when (target) {
                is DeleteTarget.DeviceCopy ->
                    stringResource(R.string.home_delete_confirm_device_message, primaryLabel)
                is DeleteTarget.DriveCopy ->
                    stringResource(R.string.home_delete_confirm_drive_message, primaryLabel)
                else -> stringResource(R.string.home_delete_confirm_message, primaryLabel)
            }
            DeleteRecordingDialog(
                name = primaryLabel,
                message = message,
                onConfirm = {
                    deleteTarget = null
                    when (target) {
                        is DeleteTarget.All -> onDeleteAll()
                        is DeleteTarget.Single -> onDeleteUri(target.uri)
                        is DeleteTarget.DeviceCopy -> onDeleteUri(target.uri)
                        is DeleteTarget.DriveCopy -> onDeleteUri(target.uri)
                        DeleteTarget.Ask -> Unit // handled above
                    }
                },
                onDismiss = { deleteTarget = null }
            )
        }
    }

    val cardColor = when {
        // primaryContainer, NOT secondaryContainer: the secondary role in this theme is CoralDeep,
        // so selected rows came out maroon and read as an error or a pending deletion. Selection is
        // neutral, and the brand colour is teal — the same teal as the tick.
        selected -> MaterialTheme.colorScheme.primaryContainer
        isRowActive -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface
    }

    // While selecting, a tap picks the row instead of playing it or expanding its copies — otherwise
    // building a selection would start playback of everything on the way past.
    val onCardClick: () -> Unit = {
        if (selectionMode) onToggleSelected() else onOpenPlayback()
    }

    CvCard(
        onClick = onCardClick,
        onLongClick = onToggleSelected,
        color = cardColor,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // BOTH rows aren't directly playable from the disc; show a non-active disc as an affordance
            // hint but keep playback state attached to the active sub-copy if one is playing.
            // The disc opens the recording on every row. On a two-copy row the card tap expands, so
            // without this there would be no way to reach playback from the row at all.
            // The badge is drawn OUTSIDE the circular clip, not inside it.
            //
            // That clip is here to shape the ripple, and it clips *content* too — so the origin badge,
            // which is pinned to the disc's corner and deliberately overhangs it, was being sliced along
            // the circle's edge. It looked like the badge was cut off; the cause was an ancestor two
            // levels up. Giving the badge an outline made the cut MORE obvious rather than fixing it.
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(enabled = !selectionMode) { onOpenPlayback() }
                ) {
                    PlayDisc(
                        isActive = !isBoth && isRowActive,
                        playback = playback
                    )
                }
                CallOriginBadge(item.direction, item.voipApp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // The whole line, to itself. The badge now sits under the play disc, in the gutter the
                // details line already indents past — space that was simply empty.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = primaryLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // fill = false so the title is what ellipsises and the badge keeps its
                        // width. An import arrives in a list of calls with no number, no direction
                        // and no contact, so without this word it reads as a call whose details all
                        // failed to parse — and it also behaves differently: no Drive copy, no
                        // retention, no eviction, so this phone holds the only copy there is.
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.isImported) {
                        Spacer(Modifier.width(8.dp))
                        ImportedBadge()
                    }
                }

            }
            Spacer(Modifier.width(4.dp))
            // No expand chevron: tapping the card already expands a BOTH row, so it was a second
            // control for an action the whole card performs — and it was spending 48dp of the width
            // the contact name needed. The copies still appear on tap; the source badge below says
            // there are two.
            // Main-row overflow: Share, then Delete. A single icon rather than one per action —
            // the row is width-bound (the meta line had to move below it for the same reason), and a
            // BOTH row already spends a slot on its chevron.
            //
            // While a delete (and any cloud-copy removal) is in flight, a live spinner takes the
            // menu button's place — the row then vanishes on the list refresh. No modal.
            if (selectionMode) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else if (deleting) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                // One slot, four meanings: transcribe, in progress, read, retry. Placed before the
                // overflow menu so the common action is the easier reach.
                TranscriptActionButton(
                    status = transcriptStatus,
                    onTranscribe = onTranscribe,
                    onOpen = onOpenTranscript,
                    onRetry = onRetryTranscript,
                    modifier = Modifier.size(ROW_ACTION_SIZE),
                    percent = transcriptPercent
                )
                RecordingRowMenu(
                    // Share the device copy when there is one: it needs no network to read, and for a
                    // BOTH row the two copies are the same audio.
                    shareUri = item.localUri ?: item.driveUri ?: item.uri,
                    shareName = item.displayName,
                    onDelete = {
                        deleteTarget = DeleteTarget.Ask
                    },
                    // Merging joins a dropped call to the redial and deletes the parts. An import is
                    // neither half of that, so it is not offered the action at all.
                    onMerge = onMerge.takeIf { MergeCandidates.canMerge(item) },
                    onUnMerge = onUnMerge
                )
            }
        }

        // The meta line sits BELOW the row, not inside its middle column. In the column it had only the
        // sliver left between the play disc and two icon buttons — about twenty characters — so it
        // truncated mid-duration ("Yesterday 17:33 · 23…") no matter which fields were dropped. Here it
        // has the card's full width, indented to line up under the name.
        // The details line already began at META_INDENT, leaving the space under the play disc empty.
        // The badge goes there: it costs no height, takes nothing from the name, and sits beside the
        // disc it describes.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(PLAY_DISC_SIZE), contentAlignment = Alignment.Center) {
                SourceBadge(source = item.source)
            }
            Spacer(Modifier.width(META_INDENT - PLAY_DISC_SIZE))
            Text(
                text = buildSubtitle(item, mergedPartCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Kept, not deleted, and deliberately unreachable: playback belongs on the recording's own
        // screen now, and two players sharing one MediaPlayer was two things to keep in step. Flip
        // INLINE_PLAYER_ENABLED to bring it back.
        if (INLINE_PLAYER_ENABLED && !isBoth && isRowActive) {
            Spacer(Modifier.height(12.dp))
            InlinePlayer(
                playback = playback,
                onPlay = { onPlayUri(item.uri) },
                onPause = onPause,
                onResume = onResume,
                onSeek = onSeek
            )
        }

        // Same: the card no longer expands, so this is unreachable while INLINE_PLAYER_ENABLED is off.
        if (INLINE_PLAYER_ENABLED && isBoth && expanded) {
            Spacer(Modifier.height(12.dp))
            item.localUri?.let { uri ->
                CopySubEntry(
                    source = RecordingSource.LOCAL,
                    label = stringResource(R.string.home_copy_device),
                    sizeBytes = item.localSizeBytes,
                    uri = uri,
                    isActive = activeUri == uri,
                    playback = playback,
                    onPlayUri = onPlayUri,
                    onPause = onPause,
                    onResume = onResume,
                    onSeek = onSeek,
                    deleteContentDescription = stringResource(R.string.home_delete_copy_device_content_desc),
                    onDelete = { deleteTarget = DeleteTarget.DeviceCopy(uri) }
                )
            }
            if (item.localUri != null && item.driveUri != null) Spacer(Modifier.height(8.dp))
            item.driveUri?.let { uri ->
                CopySubEntry(
                    source = RecordingSource.DRIVE,
                    label = stringResource(R.string.home_copy_drive),
                    sizeBytes = item.driveSizeBytes,
                    uri = uri,
                    isActive = activeUri == uri,
                    playback = playback,
                    onPlayUri = onPlayUri,
                    onPause = onPause,
                    onResume = onResume,
                    onSeek = onSeek,
                    deleteContentDescription = stringResource(R.string.home_delete_copy_drive_content_desc),
                    onDelete = { deleteTarget = DeleteTarget.DriveCopy(uri) }
                )
            }
        }
    }
}

/**
 * One sub-entry inside a BOTH row's expanded section, representing a single physical copy (Device or
 * Drive). Tapping its teal disc plays THAT copy ([uri]); when it is the active copy the shared inline
 * player (seek bar etc.) is shown beneath it so progress attaches to the copy that is actually playing.
 */
@Composable
private fun CopySubEntry(
    source: RecordingSource,
    label: String,
    sizeBytes: Long?,
    uri: Uri,
    isActive: Boolean,
    playback: RecordingPlaybackController.PlaybackState,
    onPlayUri: (Uri) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit,
    deleteContentDescription: String,
    onDelete: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val containerColor =
        if (isActive) primary.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface)
        else MaterialTheme.colorScheme.surfaceVariant
    val isLoading = isActive && playback.phase == RecordingPlaybackController.Phase.LOADING
    val isPlaying = isActive && playback.phase == RecordingPlaybackController.Phase.PLAYING

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(containerColor)
            .clickable { if (!isActive) onPlayUri(uri) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = if (isActive) 0.22f else 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = primary
                    )
                    else -> Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.home_play_recording),
                        tint = primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            // Source glyph to the left of the Device/Drive tag.
            Icon(
                imageVector = if (source == RecordingSource.DRIVE) {
                    Icons.Filled.Cloud
                } else {
                    Icons.Filled.Smartphone
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatSize(sizeBytes ?: 0L),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            // Per-copy delete: removes ONLY this physical copy, leaving the other untouched.
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = deleteContentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (isActive) {
            Spacer(Modifier.height(10.dp))
            InlinePlayer(
                playback = playback,
                onPlay = { onPlayUri(uri) },
                onPause = onPause,
                onResume = onResume,
                onSeek = onSeek
            )
        }
    }
}

/**
 * Circular teal disc that anchors each row. Shows a small loading spinner while preparing, a pause
 * glyph while the row is the active playing track, and otherwise a play arrow. A tiny direction
 * badge (incoming / outgoing) overlays the bottom-right corner.
 */
@Composable
private fun PlayDisc(
    isActive: Boolean,
    playback: RecordingPlaybackController.PlaybackState
) {
    val primary = MaterialTheme.colorScheme.primary
    val isLoading = isActive && playback.phase == RecordingPlaybackController.Phase.LOADING
    val isPlaying = isActive && playback.phase == RecordingPlaybackController.Phase.PLAYING

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(PLAY_DISC_SIZE)
                .clip(CircleShape)
                .background(primary.copy(alpha = if (isActive) 0.22f else 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = primary
                )
                else -> Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.home_play_recording),
                    tint = primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        // A VoIP recording has no direction (there is no call-log entry behind it), so the same corner
        // slot carries the app it came from instead — the two can never collide.
    }
}

/** The inline player controls (loading / error / play-pause + teal seek slider + elapsed/total). */
@Composable
private fun InlinePlayer(
    playback: RecordingPlaybackController.PlaybackState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Int) -> Unit
) {
    when (playback.phase) {
        RecordingPlaybackController.Phase.LOADING -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_player_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        RecordingPlaybackController.Phase.ERROR -> {
            Text(
                text = stringResource(R.string.home_player_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        else -> {
            val isPlaying = playback.phase == RecordingPlaybackController.Phase.PLAYING
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (isPlaying) onPause() else onResume() }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.home_player_pause else R.string.home_player_play
                        ),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = formatMillis(playback.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SeekBar(
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    onSeek = onSeek,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = formatMillis(playback.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// -------- Formatting helpers

/** Play disc (48dp) plus its gap (14dp), so the meta line starts under the name rather than the disc. */
/**
 * The two row actions sit at 40dp rather than the default 48dp.
 *
 * A default IconButton centres a 24dp icon in a 48dp box, so two of them side by side put 24dp of
 * dead space between the glyphs — width the contact name did not have. At 40dp they read as a pair
 * and the name gains about 20dp with them, on a row where five characters was the whole budget.
 *
 * Not smaller: 40dp is already under Android's 48dp guidance for a touch target, and these are the
 * two controls on the row that are actually tapped.
 */
/**
 * Whether a recording plays inside its row.
 *
 * Off: tapping a recording opens its own screen, which has the waveform, the skip controls and the
 * speed the strip had no room for. The strip's code stays because it is a working player and the
 * decision is a layout one — but two players over a single MediaPlayer is two things to keep in step,
 * so only one of them is live.
 */
private const val INLINE_PLAYER_ENABLED = false

private val ROW_ACTION_SIZE = 40.dp

/**
 * The play disc's diameter, shared with the source badge that sits under it.
 *
 * Centring the badge in the details indent instead put it ~8dp right of the disc's centre — close
 * enough to look like a mistake rather than a choice. Both now measure from the same number.
 */
private val PLAY_DISC_SIZE = 46.dp

/** Badge glyphs, sized so two of them plus padding fit within [PLAY_DISC_SIZE]. */
private val BADGE_GLYPH_SIZE = 13.dp

private val META_INDENT = 62.dp

/**
 * The muted line under the name: when the call happened, how long it ran, how big the file is.
 *
 * It used to carry a raw "yyyy-MM-dd HH:mm" beside the size column, which left it so little width
 * that the date truncated to "2026…" — present, and useless. Size moved onto this line so it spans
 * the row, and the date became short enough to read at a glance.
 *
 * Duration is omitted rather than faked when it cannot be known (see [CallDurationLookup]). The phone
 * number no longer trails this line: it pushed the date and duration — the two things the line exists
 * for — off the end on any row with a contact name, which is most of them.
 */
/**
 * "4 calls · 23:27" for the progress card's detail line.
 *
 * Resolved from resources rather than through `pluralStringResource`, because it is built in a
 * click handler rather than during composition.
 */
private fun mergeDetail(context: android.content.Context, calls: Int, seconds: Long): String =
    context.resources.getQuantityString(
        R.plurals.merge_summary, calls, calls, formatDuration(seconds)
    )

@Composable
private fun buildSubtitle(item: RecordingItem, mergedPartCount: Int = 0): String {
    val parts = buildList {
        formatWhen(item)?.let { add(it) }
        item.durationSeconds?.let { add(formatDuration(it)) }
        if (item.sizeBytes > 0) add(formatSize(item.sizeBytes))
        // Last, so it reads as a note about the row rather than competing with when and how long.
        // Without it a merged call is indistinguishable from an ordinary one that happens to be
        // long, and the only clue that three other calls are inside it is that they are missing.
        if (mergedPartCount > 1) {
            add(pluralStringResource(R.plurals.merge_row_badge, mergedPartCount, mergedPartCount))
        }
    }
    return if (parts.isEmpty()) item.displayName else parts.joinToString(" · ")
}

/**
 * "Today 14:30", "Yesterday 09:15", or "29/07/26 14:30" for anything older.
 *
 * Relative days are compared on the calendar day, not on elapsed hours: a call at 23:50 is still
 * "Yesterday" at 00:10, which "less than 24 hours ago" would get wrong.
 *
 * **A row whose name carries no date still gets one.** Where nothing could be parsed this falls back
 * to the file's own last-modified time, which is what [RecordingsRepository.dayKey] has always done
 * for the Date *filter* — so until now a recording could be filed under "Jun 11, 2026" by the filter
 * while its own row showed no date at all. One of the two had to be wrong and it was this one.
 */
@Composable
private fun formatWhen(item: RecordingItem): String? {
    val millis = item.startedAtMillis
        // Only where the name said nothing. A name that parsed far enough to show something keeps
        // showing it: displayDate is the recording's own claim about itself, and the file's
        // timestamp is only evidence about the file.
        ?: item.lastModified.takeIf { item.displayDate == null && it > 0L }
        ?: return item.displayDate
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    val day = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay(day, today) -> "${stringResource(R.string.home_date_today)} $time"
        sameDay(day, yesterday) -> "${stringResource(R.string.home_date_yesterday)} $time"
        else -> SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(millis))
    }
}

/** "12:41" for a call under an hour, "1:05:30" beyond it. */
private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val sec = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

/** Formats a byte count as a compact human-readable size (e.g. "1.2 MB"). */
private fun formatSize(bytes: Long): String = formatByteSize(bytes)

/** Formats a millisecond duration as m:ss. */
private fun formatMillis(millis: Int): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
