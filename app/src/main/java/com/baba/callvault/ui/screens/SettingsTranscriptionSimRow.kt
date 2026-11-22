/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.calls.SimCard
import com.baba.callvault.data.calls.SimCards
import com.baba.callvault.data.calls.SimChoice
import com.baba.callvault.data.calls.SimId
import com.baba.callvault.data.calls.SimMatch
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem

/**
 * "Auto-transcribe" after each call: all SIMs or one SIM (issue #81). Shown when two SIMs are in the phone, or
 * when a single SIM was already chosen (so a removed SIM's choice can still be changed back). The SIM's own name
 * is the option when it has one, else "SIM 1" / "SIM 2".
 */
@Composable
internal fun TranscriptionSimChoices(preferences: AppPreferences, updateTrigger: Int) {
    val context = LocalContext.current
    val simWord = stringResource(R.string.sim_label_word)
    val cards = remember(updateTrigger) { SimCards.list(context, simWord) }
    val stored = remember(updateTrigger) {
        val raw = preferences.getTranscriptionAutoSim()
        SimMatch.stabilize(raw, cards).also { if (it != raw) preferences.setTranscriptionAutoSim(it) }
    }
    if (cards.size >= 2 || stored !is SimChoice.All) {
        TranscriptionSimChoiceField(preferences, updateTrigger, cards, stored)
    }
}

@Composable
private fun TranscriptionSimChoiceField(
    preferences: AppPreferences,
    updateTrigger: Int,
    cards: List<SimCard>,
    stored: SimChoice,
) {
    var choice by remember(updateTrigger) { mutableStateOf(stored) }
    val options = simOptions(
        cards, choice,
        stringResource(R.string.transcription_sim_all),
        stringResource(R.string.sim_choice_unavailable),
    )
    val selectedKey = selectedSimKey(cards, choice)
    val label = stringResource(R.string.transcription_sim_label)
    DropdownRow {
        WithInfo(label, stringResource(R.string.transcription_sim_info)) {
            M3DropdownField(
                label = label,
                selected = options.find { it.key == selectedKey } ?: options.first(),
                options = options,
                onOptionSelected = { picked ->
                    val chosen = SimChoice.fromKey(picked.key)
                    preferences.setTranscriptionAutoSim(chosen)
                    choice = chosen
                },
            )
        }
    }
}

internal fun simOptions(
    cards: List<SimCard>,
    choice: SimChoice,
    allLabel: String,
    unavailable: String,
): List<OptionItem> = buildList {
    add(OptionItem(SimChoice.All.key, allLabel))
    cards.forEach { card -> add(OptionItem(SimMatch.stableId(card).key, card.label)) }
    if (choice is SimChoice.Only && cards.none { choice.id.matches(it) }) {
        val slotStillThere = (choice.id as? SimId.Slot)?.let { id -> cards.any { it.slot == id.value } } == true
        if (!slotStillThere) add(OptionItem(choice.key, unavailable))
    }
}

/** The dropdown key for [choice]: the card's stable id when a slot-only value still matches one. */
internal fun selectedSimKey(cards: List<SimCard>, choice: SimChoice): String {
    val only = choice as? SimChoice.Only ?: return choice.key
    val card = cards.find { only.id.matches(it) }
        ?: (only.id as? SimId.Slot)?.let { id -> cards.find { it.slot == id.value } }
    return card?.let { SimMatch.stableId(it).key } ?: choice.key
}
