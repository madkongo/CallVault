/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.dialer

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baba.callvault.R
import com.baba.callvault.calls.CallEvent
import com.baba.callvault.calls.UiCall

private val NavyCallBg = Color(0xFF0A1628)
private val GreenAnswer = Color(0xFF1B7F4B)
private val RedEnd = Color(0xFFB00020)

/**
 * Stateless in-call screen. The Activity owns the ViewModel; this composable is purely
 * presentation and delegates every action through lambdas.
 *
 * Renders three layouts driven by [call.phase]:
 *  - [CallEvent.Phase.RINGING]: large answer + reject buttons for an incoming call.
 *  - [CallEvent.Phase.DIALING] / [CallEvent.Phase.ACTIVE]: full control row (mute, speaker, hold,
 *    keypad, end, record toggle).
 *  - [CallEvent.Phase.ENDED]: blank (the Activity finishes itself before reaching here).
 */
@Composable
fun InCallScreen(
    call: UiCall?,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onHold: () -> Unit,
    onMute: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
    onToggleRecord: () -> Unit,
    onDtmf: (Char) -> Unit = {},
) {
    if (call == null) return

    val unknownLabel = stringResource(R.string.dialer_unknown_caller)
    val displayNumber = InCallLabels.displayNumber(call, unknownLabel)

    var isMuted by rememberSaveable { mutableStateOf(false) }
    var isSpeaker by rememberSaveable { mutableStateOf(false) }
    var showKeypad by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NavyCallBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            Text(
                text = when (call.phase) {
                    CallEvent.Phase.RINGING -> stringResource(R.string.dialer_incoming_title)
                    CallEvent.Phase.DIALING -> "Calling…"
                    CallEvent.Phase.ACTIVE  -> "Active"
                    CallEvent.Phase.ENDED   -> "Call ended"
                },
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = displayNumber,
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.weight(1f))

            when (call.phase) {
                CallEvent.Phase.RINGING -> IncomingControls(
                    onAnswer = onAnswer,
                    onReject = onReject,
                )
                CallEvent.Phase.DIALING,
                CallEvent.Phase.ACTIVE -> ActiveControls(
                    isRecording = call.isRecording,
                    isMuted = isMuted,
                    isSpeaker = isSpeaker,
                    showKeypad = showKeypad,
                    onEnd = onEnd,
                    onHold = onHold,
                    onMuteToggle = { muted ->
                        isMuted = muted
                        onMute(muted)
                    },
                    onSpeakerToggle = { speaker ->
                        isSpeaker = speaker
                        onSpeaker(speaker)
                    },
                    onKeypadToggle = { showKeypad = !showKeypad },
                    onToggleRecord = onToggleRecord,
                    onDtmf = onDtmf,
                )
                CallEvent.Phase.ENDED -> Unit
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun IncomingControls(
    onAnswer: () -> Unit,
    onReject: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(
            icon = Icons.Filled.Call,
            label = stringResource(R.string.dialer_answer),
            containerColor = GreenAnswer,
            onClick = onAnswer,
        )
        ActionButton(
            icon = Icons.Filled.CallEnd,
            label = stringResource(R.string.dialer_reject),
            containerColor = RedEnd,
            onClick = onReject,
        )
    }
}

@Composable
private fun ActiveControls(
    isRecording: Boolean,
    isMuted: Boolean,
    isSpeaker: Boolean,
    showKeypad: Boolean,
    onEnd: () -> Unit,
    onHold: () -> Unit,
    onMuteToggle: (Boolean) -> Unit,
    onSpeakerToggle: (Boolean) -> Unit,
    onKeypadToggle: () -> Unit,
    onToggleRecord: () -> Unit,
    onDtmf: (Char) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (showKeypad) {
            DtmfKeypad(onDigit = onDtmf)
            Spacer(Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToggleButton(
                icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = stringResource(R.string.dialer_mute),
                checked = isMuted,
                onCheckedChange = onMuteToggle,
            )
            ToggleButton(
                icon = Icons.Filled.VolumeUp,
                label = stringResource(R.string.dialer_speaker),
                checked = isSpeaker,
                onCheckedChange = onSpeakerToggle,
            )
            ToggleButton(
                icon = Icons.Filled.Pause,
                label = stringResource(R.string.dialer_hold),
                checked = false,
                onCheckedChange = { onHold() },
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToggleButton(
                icon = Icons.Filled.Dialpad,
                label = stringResource(R.string.dialer_keypad),
                checked = showKeypad,
                onCheckedChange = { onKeypadToggle() },
            )
            ActionButton(
                icon = Icons.Filled.CallEnd,
                label = stringResource(R.string.dialer_end_call),
                containerColor = RedEnd,
                onClick = onEnd,
            )
            ToggleButton(
                icon = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                label = if (isRecording) stringResource(R.string.dialer_stop_recording)
                        else stringResource(R.string.dialer_record),
                checked = isRecording,
                onCheckedChange = { onToggleRecord() },
            )
        }
    }
}

@Composable
private fun DtmfKeypad(onDigit: (Char) -> Unit) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf('*', '0', '#'),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { digit ->
                    FilledIconButton(
                        onClick = { onDigit(digit) },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White,
                        ),
                        shape = CircleShape,
                    ) {
                        Text(text = digit.toString(), color = Color.White, fontSize = 20.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor),
            shape = CircleShape,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(text = label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

@Composable
private fun ToggleButton(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = Color.White.copy(alpha = 0.12f),
                contentColor = Color.White,
                checkedContainerColor = Color.White.copy(alpha = 0.35f),
                checkedContentColor = Color.White,
            ),
            shape = CircleShape,
        ) {
            Icon(imageVector = icon, contentDescription = label)
        }
        Spacer(Modifier.height(4.dp))
        Text(text = label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}
