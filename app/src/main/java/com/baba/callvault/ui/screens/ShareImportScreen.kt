/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.recordings.AudioImport
import com.baba.callvault.data.recordings.SharedAudio
import com.baba.callvault.ui.common.CvCard
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
 * @param onOpenRecording open what was just imported, by the name it was stored under.
 * @param onOpenApp       open CallVault itself; the way out of "setup is not finished".
 * @param onClose         dismiss, having done nothing further.
 */
@Composable
fun ShareImportScreen(
    state: ShareImportViewModel.State,
    onOpenRecording: (String) -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val isWorking = state is ShareImportViewModel.State.Working

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
                ShareImportViewModel.State.Working -> WorkingBody()

                is ShareImportViewModel.State.Imported -> OutcomeBody(
                    icon = Icons.Filled.CheckCircle,
                    // The app's own accent, never M3's default: several roles in this scheme resolve
                    // to CoralDeep, and a red tick beside "added" reads as a failure.
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.share_import_done_title),
                    body = stringResource(R.string.share_import_done_body),
                    primaryLabel = stringResource(R.string.share_import_open),
                    onPrimary = { onOpenRecording(state.displayName) },
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
