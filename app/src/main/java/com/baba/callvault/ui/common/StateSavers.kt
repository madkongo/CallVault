/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import android.net.Uri
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.baba.callvault.data.recordings.DeleteScope
import com.baba.callvault.ui.navigation.HomeSection
import com.baba.callvault.ui.navigation.LibrarySelection

/**
 * Encoding Home's screen state so a rotation does not throw it away.
 *
 * Rotation destroys and recreates the Activity — `MainActivity` declares no `configChanges`, and it
 * should not, because letting Android recreate is how layouts, resources and locales stay correct.
 * The cost is that plain `remember` starts again from its default, which is what closed the open
 * recording and cleared the multi-selection in issue #27.
 *
 * These live outside `HomeScreen` for one reason: they are the only part of the fix that can be
 * *wrong*. `rememberSaveable` restoring a value is the framework's guarantee; what we hand it is
 * ours, and a selection that decodes to the wrong set is a multi-select whose next action is a
 * delete. So the encodings are pure functions with tests, and the Savers below are thin wrappers.
 *
 * Everything is encoded as `String`s in a `List`, never as one delimited string. Recording names
 * carry spaces, plus signs, percent-encoding and — on at least one real device — a `|`. A delimiter
 * is a bug waiting for the right filename.
 */

/** A recording awaiting its "this will take about N" confirmation: name, estimate, chosen language. */
typealias TranscribeRequest = Triple<String, Long?, String?>

/** Marks an absent [Long] or [String] field. No recording name or language tag is ever empty. */
private const val ABSENT = ""

// ---- selection ----

internal fun encodeUriSet(selection: Set<Uri>): List<String> = selection.map(Uri::toString)

internal fun decodeUriSet(stored: List<String>): Set<Uri> = stored.map(Uri::parse).toSet()

/** The multi-selection. Non-null by construction — empty means "not in selection mode". */
val UriSetStateSaver: Saver<Set<Uri>, Any> = listSaver(
    save = { encodeUriSet(it) },
    restore = { decodeUriSet(it) },
)

// ---- library page selection ----

/**
 * The section key first, then the names.
 *
 * The section has to survive with them: restoring a set of names with no page attached would be a
 * selection that could be acted on from either library page — see [LibrarySelection] for why the two
 * overlap and what that would cost.
 */
internal fun encodeLibrarySelection(selection: LibrarySelection): List<String> =
    listOf(selection.section?.key ?: ABSENT) + selection.names

/**
 * Falls back to [LibrarySelection.EMPTY] rather than throwing, on the same grounds as the delete
 * scope's decoder: a stored section key can outlive the constant that wrote it. An unreadable
 * selection means no selection, which is the safe direction — the alternative is restoring rows
 * picked under a name we can no longer place, with a delete button above them.
 */
internal fun decodeLibrarySelection(stored: List<String>): LibrarySelection {
    val section = stored.firstOrNull()
        ?.takeIf { it != ABSENT }
        ?.let { key -> HomeSection.entries.firstOrNull { it.key == key } }
        ?: return LibrarySelection.EMPTY
    val names = stored.drop(1).toSet()
    return if (names.isEmpty()) LibrarySelection.EMPTY else LibrarySelection(section, names)
}

val LibrarySelectionStateSaver: Saver<LibrarySelection, Any> = listSaver(
    save = { encodeLibrarySelection(it) },
    restore = { decodeLibrarySelection(it) },
)

// ---- pending transcribe confirmation ----

internal fun encodeTranscribeRequest(request: TranscribeRequest): List<String> = listOf(
    request.first,
    request.second?.toString() ?: ABSENT,
    request.third ?: ABSENT,
)

internal fun decodeTranscribeRequest(stored: List<String>): TranscribeRequest = Triple(
    stored[0],
    // Deliberately not `?: 0`. A null estimate means "we do not know"; zero would be quoted back
    // to the user as "this will take about 0 seconds".
    stored.getOrNull(1)?.takeIf { it != ABSENT }?.toLongOrNull(),
    // Null means "use the configured language", which is not the same as any particular language.
    stored.getOrNull(2)?.takeIf { it != ABSENT },
)

/**
 * Nullable state, so this is a raw [Saver] rather than a `listSaver`: Compose only calls `restore`
 * for a value that was saved as non-null, and asserts the result is non-null. Returning a marker
 * list for "nothing pending" would therefore crash on restore; returning null does not.
 */
val TranscribeRequestStateSaver: Saver<TranscribeRequest?, Any> = Saver(
    save = { request -> request?.let { ArrayList(encodeTranscribeRequest(it)) } },
    restore = { raw ->
        @Suppress("UNCHECKED_CAST")
        (raw as? List<String>)?.takeIf { it.isNotEmpty() }?.let(::decodeTranscribeRequest)
    },
)

// ---- bulk delete scope ----

internal fun encodeDeleteScope(scope: DeleteScope): String = scope.name

/**
 * Falls back to [DeleteScope.BOTH] rather than throwing. A stored name can outlive the constant that
 * wrote it — a downgrade, or a renamed enum — and a dialog that crashes on open is worse than one
 * that reopens on its documented default. BOTH is also what the dialog defaults to when fresh, so
 * an unreadable value lands exactly where a first open would.
 */
internal fun decodeDeleteScope(stored: String): DeleteScope =
    DeleteScope.entries.firstOrNull { it.name == stored } ?: DeleteScope.BOTH

val DeleteScopeStateSaver: Saver<DeleteScope, Any> = Saver(
    save = { encodeDeleteScope(it) },
    restore = { raw -> (raw as? String)?.let(::decodeDeleteScope) },
)
