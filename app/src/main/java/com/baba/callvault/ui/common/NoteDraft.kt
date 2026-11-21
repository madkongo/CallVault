/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The text of a note while it is being typed.
 *
 * The field shows [text] — the draft — never the database's echo of it. Binding the field to the
 * stored value sent every keystroke through Room and back before it appeared; a fast typist lost
 * the race, the IME's composing text was reset under it, and letters came out swapped or missing
 * ("hello note" typed over adb read "o otehe"). Reported on 2.4.1 as "you cannot save a note".
 *
 * Saves [debounceMs] after the last keystroke, and at once on [flush] (leaving the screen). A
 * value that changes in the store while nothing is being typed — a merge — replaces the draft.
 */
class NoteDraft(
    initial: String,
    private val scope: CoroutineScope,
    private val debounceMs: Long,
    private val save: (String) -> Unit,
) {
    var text by mutableStateOf(initial)
        private set

    /** True when the store holds what the field shows. */
    var isSaved by mutableStateOf(true)
        private set

    private var pending: Job? = null

    fun edit(new: String) {
        if (new == text && isSaved) return
        text = new
        isSaved = false
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            commit()
        }
    }

    /** Saves now if anything is unsaved. Called when the field goes away. */
    fun flush() {
        pending?.cancel()
        pending = null
        if (!isSaved) commit()
    }

    /** The store's current value: adopted unless letters are being typed over it. */
    fun stored(value: String) {
        if (isSaved && value != text) text = value
    }

    private fun commit() {
        save(text)
        isSaved = true
    }

    companion object {
        /** Long enough to cover a pause between words, short enough that leaving never waits. */
        const val DEBOUNCE_MS = 600L
    }
}

/**
 * A [NoteDraft] for [stored], one per [key] (the recording), following the store while idle and
 * saved through the latest [onSave]. Switching recordings flushes the old draft before the new one
 * starts, so a note half-typed on one call is never written under the next.
 */
@Composable
fun rememberNoteDraft(stored: String, key: Any?, onSave: (String) -> Unit): NoteDraft {
    val scope = rememberCoroutineScope()
    val latestSave = rememberUpdatedState(onSave)
    val latestKey = rememberUpdatedState(key)
    val draft = remember(key) {
        NoteDraft(
            stored, scope, NoteDraft.DEBOUNCE_MS,
            ownerBoundSave(key, { latestKey.value }, onSave) { latestSave.value },
        )
    }
    LaunchedEffect(draft, stored) { draft.stored(stored) }
    DisposableEffect(draft) { onDispose { draft.flush() } }
    return draft
}

/**
 * The save for a draft that belongs to [ownerKey] (C121).
 *
 * While the screen still shows that recording the latest callback is used, so a callback re-made by
 * recomposition keeps working. Once the screen has moved to another recording ([currentKey] differs) the
 * draft's last flush must still land on its own recording, so it goes through [ownerSave], the callback
 * the draft was created with. The latest callback by then points at the NEW recording and would write
 * the old note under it.
 */
internal fun ownerBoundSave(
    ownerKey: Any?,
    currentKey: () -> Any?,
    ownerSave: (String) -> Unit,
    latestSave: () -> (String) -> Unit,
): (String) -> Unit = { text ->
    if (currentKey() == ownerKey) latestSave()(text) else ownerSave(text)
}
