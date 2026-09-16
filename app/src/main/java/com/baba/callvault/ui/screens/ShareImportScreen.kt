/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.recordings.AudioImport
import com.baba.callvault.data.recordings.ImportedRecording
import com.baba.callvault.data.recordings.SharedAudio
import com.baba.callvault.transcription.TranscriptionLengthLimit
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.TranscribeConfirmDialog
import com.baba.callvault.ui.common.TranscribeLanguageDialog
import com.baba.callvault.ui.common.formatEstimate
import com.baba.callvault.ui.viewmodels.ShareImportViewModel

/**
 * The whole of what a share looks like: a card over the app that sent the file.
 *
 * A card over a scrim rather than a full screen, because a share is a detour. The user is in
 * WhatsApp; they will be back in WhatsApp in a moment. Filling the screen would make CallVault look
 * like it had been opened, and the thing behind the scrim is the conversation the voice note came
 * from, which is the most useful context there is for "is this the right file".
 *
 * Every state ends in something the user can do: an import that worked offers the recording, and one
 * that did not offers a sentence saying why. Nothing here vanishes silently — a share that produced
 * a toast behind a closing share sheet is a share that, as far as anyone can tell, did nothing.
 *
 * @param onChoose        the user has said what the file is for; nothing has been copied before this.
 * @param onLanguage      the transcription language has been picked for the imported file.
 * @param onConfirm       the estimate has been accepted; queue the run.
 * @param onDecline       one of the two questions was backed out of. The file stays imported.
 * @param onOpenRecording open what was just imported, by the name it was stored under.
 * @param onOpenTranscripts open the Transcripts page — the only place a transcribe-only file appears.
 * @param onOpenApp       open CallVault itself; the way out of "setup is not finished".
 * @param onClose         dismiss, having done nothing further.
 */
@Composable
fun ShareImportScreen(
    state: ShareImportViewModel.State,
    onChoose: (ImportedRecording.Kind) -> Unit,
    onLanguage: (displayName: String, label: String, language: String?) -> Unit,
    onConfirm: (displayName: String, language: String?, dontAskAgain: Boolean) -> Unit,
    onDecline: (String) -> Unit,
    onOpenRecording: (String) -> Unit,
    onOpenTranscripts: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val isWorking = state is ShareImportViewModel.State.Importing

    // Back is swallowed while the copy is running, and only then. Finishing this Activity takes its
    // ViewModel with it, which cancels the job between the copy and the catalogue — leaving a file
    // in the user's recordings folder that the app has no record of and no sweep will reconcile.
    // Deliberately not a permanent trap: every other state lets back through, and Home is always
    // available even here.
    BackHandler(enabled = isWorking) { /* the copy has to finish or be nothing */ }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Stated, not defaulted. The window is translucent, so this scrim is the only thing
            // separating the card from the sender's own screen.
            .background(SCRIM)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CvCard(
            modifier = Modifier.widthIn(max = CARD_MAX_WIDTH),
            color = MaterialTheme.colorScheme.surface,
        ) {
            when (state) {
                ShareImportViewModel.State.Reading,
                ShareImportViewModel.State.Importing -> WorkingBody()

                // The question the card exists to ask. Nothing has been copied at this point, so
                // closing here really does leave the phone exactly as it was.
                is ShareImportViewModel.State.Deciding -> ChoiceBody(
                    displayName = state.displayName,
                    sizeBytes = state.sizeBytes,
                    onChoose = onChoose,
                    onClose = onClose,
                )

                // The card keeps saying "importing" underneath, because the two dialogs below are
                // windows of their own and would otherwise stand over an empty card.
                is ShareImportViewModel.State.AskLanguage,
                is ShareImportViewModel.State.Confirm -> WorkingBody()

                is ShareImportViewModel.State.Queued -> OutcomeBody(
                    icon = Icons.Filled.CheckCircle,
                    // The app's own accent, never M3's default: several roles in this scheme resolve
                    // to CoralDeep, and a red tick beside "added" reads as a failure.
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(
                        if (state.kind == ImportedRecording.Kind.TRANSCRIBE_ONLY) {
                            R.string.share_import_queued_only_title
                        } else {
                            R.string.share_import_queued_keep_title
                        }
                    ),
                    body = stringResource(
                        if (state.kind == ImportedRecording.Kind.TRANSCRIBE_ONLY) {
                            R.string.share_import_queued_only_body
                        } else {
                            R.string.share_import_queued_keep_body
                        }
                    ),
                    primaryLabel = stringResource(R.string.share_import_open),
                    // A transcribe-only file has no row in Recordings to open — that is the point of
                    // it — so Open goes to the page where its words will appear instead.
                    onPrimary = {
                        if (state.kind == ImportedRecording.Kind.TRANSCRIBE_ONLY) onOpenTranscripts()
                        else onOpenRecording(state.displayName)
                    },
                    onClose = onClose,
                )

                is ShareImportViewModel.State.NotTranscribed -> OutcomeBody(
                    icon = Icons.Filled.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.share_import_kept_title),
                    body = stringResource(
                        if (state.kind == ImportedRecording.Kind.TRANSCRIBE_ONLY) {
                            R.string.share_import_kept_only_body
                        } else {
                            R.string.share_import_kept_body
                        }
                    ),
                    primaryLabel = stringResource(R.string.share_import_open),
                    onPrimary = {
                        if (state.kind == ImportedRecording.Kind.TRANSCRIBE_ONLY) onOpenTranscripts()
                        else onOpenRecording(state.displayName)
                    },
                    onClose = onClose,
                )

                // Both of these keep the audio, and both say where it is: neither is a refusal of
                // the file, only of reading it right now.
                is ShareImportViewModel.State.TooLong -> RefusedBody(
                    body = stringResource(
                        R.string.share_import_too_long,
                        state.minutes,
                        TranscriptionLengthLimit.MAX_MINUTES,
                    ),
                    onClose = onClose,
                )

                ShareImportViewModel.State.NoModel -> RefusedBody(
                    body = stringResource(R.string.share_import_no_model),
                    onClose = onClose,
                )

                is ShareImportViewModel.State.ShareRefused -> when (state.reason) {
                    // The one refusal that is about the app rather than about the file, so it gets
                    // its own wording and its own way forward instead of "couldn't import that".
                    SharedAudio.Reason.NOT_SET_UP -> OutcomeBody(
                        icon = Icons.Filled.Settings,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.share_import_not_set_up_title),
                        body = stringResource(R.string.share_import_not_set_up_body),
                        primaryLabel = stringResource(R.string.share_import_open_app),
                        onPrimary = onOpenApp,
                        onClose = onClose,
                    )
                    // Both remaining cases are, from where the user stands, the same event:
                    // CallVault was opened with nothing to import. One sentence, and the
                    // distinction — a text-only share versus an action we do not handle — is kept
                    // in the log, where it is the only place it means anything.
                    SharedAudio.Reason.NOT_A_SHARE,
                    SharedAudio.Reason.NOTHING_SHARED -> RefusedBody(
                        body = stringResource(R.string.share_import_nothing),
                        onClose = onClose,
                    )
                }

                is ShareImportViewModel.State.ImportRefused -> RefusedBody(
                    // The same sentences the picker's refusals use. One file, one reason, whichever
                    // door it came in through — and nothing was left behind in the folder either
                    // way; AudioImport deletes a copy it could not decode.
                    body = stringResource(importRefusalMessage(state.reason)),
                    onClose = onClose,
                )
            }
        }
    }

    // Raised beside the card, not inside it: an AlertDialog is its own window, so drawn from within
    // the card's own `when` it would stand over a card with nothing left in it.
    //
    // The two asks an import always makes, whatever the "don't ask" settings say — a shared file is
    // nobody's call, so neither the pinned language nor a familiar estimate applies to it. Backing
    // out of either keeps the file rather than deleting it: the copy has already happened, and a
    // share that asked two questions and then silently threw the answer away would be worse than one
    // that asked none.
    when (state) {
        is ShareImportViewModel.State.AskLanguage -> TranscribeLanguageDialog(
            title = state.label,
            setting = AppPreferences(LocalContext.current).getTranscriptionLanguage(),
            onDismiss = { onDecline(state.displayName) },
            onConfirm = { language -> onLanguage(state.displayName, state.label, language) },
        )

        is ShareImportViewModel.State.Confirm -> TranscribeConfirmDialog(
            title = state.label,
            estimate = state.estimateMs?.let { formatEstimate(it) },
            isFirstRun = state.isFirstRun,
            onDismiss = { onDecline(state.displayName) },
            onConfirm = { dontAskAgain -> onConfirm(state.displayName, state.language, dontAskAgain) },
        )

        else -> Unit
    }
}

/**
 * The question: what is this audio for?
 *
 * Asked BEFORE anything is copied, so "Close" here really does leave the phone as it was. The two
 * answers are not two shades of the same thing — one files a stranger's voice note among the user's
 * own calls for ever, the other borrows it until its words exist and then deletes it — so each
 * carries a sentence saying what becomes of the audio, rather than relying on the button's two words.
 *
 * The file is named above them, because a share sheet gives no confirmation of what was picked and
 * "this audio" is not enough to answer the question about. The size goes beside it where the
 * provider reported one; the duration cannot be here, since reading it needs the copy this card has
 * not made (see [ShareImportViewModel.State.Deciding]).
 *
 * Transcribe-only is drawn as the quieter of the two: it is the answer that deletes something, and a
 * filled button is a poor place for that. Neither is the default in the sense of being pre-selected
 * — there is nothing to confirm, only two things to tap.
 */
@Composable
private fun ChoiceBody(
    displayName: String?,
    sizeBytes: Long,
    onChoose: (ImportedRecording.Kind) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Text(
        text = stringResource(R.string.share_import_choice_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        // The name the sender gave it, with its size where one was reported. A negative size means
        // the provider did not say, which is different from zero and must not be printed as "0 B".
        text = listOfNotNull(
            displayName?.takeIf { it.isNotBlank() },
            sizeBytes.takeIf { it > 0L }?.let { Formatter.formatShortFileSize(context, it) },
        ).joinToString(" · ").ifBlank { stringResource(R.string.share_import_choice_unnamed) },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(16.dp))

    ChoiceButton(
        icon = Icons.Filled.LibraryMusic,
        label = stringResource(R.string.import_choice_keep),
        hint = stringResource(R.string.import_choice_keep_hint),
        filled = true,
        onClick = { onChoose(ImportedRecording.Kind.KEEP) },
    )
    Spacer(Modifier.height(10.dp))
    ChoiceButton(
        icon = Icons.AutoMirrored.Filled.Article,
        label = stringResource(R.string.import_choice_transcribe_only),
        hint = stringResource(R.string.import_choice_transcribe_only_hint),
        filled = false,
        onClick = { onChoose(ImportedRecording.Kind.TRANSCRIBE_ONLY) },
    )
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onClose) { Text(stringResource(R.string.share_import_close)) }
    }
}

/**
 * One of the two answers: a full-width button with the sentence that explains it underneath.
 *
 * The sentence is outside the button rather than inside it, because a two-line button is a target
 * whose label wraps differently in every one of eleven locales — and the German for "the audio is
 * deleted once the transcript is ready" is not a button label anywhere.
 */
@Composable
private fun ChoiceButton(
    icon: ImageVector,
    label: String,
    hint: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (filled) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            // Stated, not defaulted: an OutlinedButton takes its content colour from the primary
            // role, and several of M3's own roles resolve to CoralDeep in this scheme — which would
            // put the quieter of the two answers in the app's error colour.
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) { content() }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** The spinner and what it is waiting for. No buttons: there is nothing to decide yet. */
@Composable
private fun WorkingBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(16.dp))
        Column {
            Text(
                text = stringResource(R.string.transcripts_import_working),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.share_import_working_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A finished share that has somewhere to go next: an icon, a sentence, and two ways out. */
@Composable
private fun OutcomeBody(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClose) { Text(stringResource(R.string.share_import_close)) }
        Spacer(Modifier.size(8.dp))
        Button(onClick = onPrimary) { Text(primaryLabel) }
    }
}

/** A refusal: why it did not happen, and the only thing left to do about it. */
@Composable
private fun RefusedBody(body: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(14.dp))
        Text(
            text = stringResource(R.string.import_failed_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onClose) { Text(stringResource(R.string.share_import_close)) }
    }
}

/**
 * The sentence for a refused import.
 *
 * Shared with the picker path rather than written twice: the reasons are properties of the file, not
 * of how it arrived, and a second set of wordings would be a second set to keep translated.
 */
internal fun importRefusalMessage(reason: AudioImport.Reason): Int = when (reason) {
    AudioImport.Reason.NOT_AUDIO -> R.string.import_failed_not_audio
    AudioImport.Reason.EMPTY -> R.string.import_failed_empty
    AudioImport.Reason.NO_FOLDER -> R.string.import_failed_no_folder
    AudioImport.Reason.COPY_FAILED -> R.string.import_failed_copy
    AudioImport.Reason.UNDECODABLE -> R.string.import_failed_undecodable
}

/** Dark enough that the card reads as being in front of the sender's screen rather than on it. */
private val SCRIM = Color(0x99000000)

/** Keeps the card a card on a tablet, instead of a band of text the width of the screen. */
private val CARD_MAX_WIDTH = 420.dp
