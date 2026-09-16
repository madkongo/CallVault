/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.navigation

/**
 * Which rows are picked on a library page, and which page they were picked on.
 *
 * **The section travels with the names, and that is the whole design.** Transcripts and Summaries
 * are two lists of display names with a large overlap: most summarised calls are transcribed ones,
 * so a bare `Set<String>` carried from one page to the other would arrive looking like a perfectly
 * valid selection of *different* things — and the next tap after a selection is a delete. Asking
 * [on] rather than reading the set directly makes that impossible rather than merely unlikely.
 *
 * The alternative considered and rejected was clearing the set in an effect keyed on the section.
 * That effect also runs on first composition, which would throw the selection away every time the
 * process was recreated — exactly the restoration issue #27 was about — and it fixes the leak only
 * for as long as nobody adds a fourth section that forgets to fire it.
 *
 * Empty means "not in selection mode"; there is no separate flag to fall out of step with the set.
 */
data class LibrarySelection(
    /** Where the names were picked, or null when nothing is picked. */
    val section: HomeSection?,
    val names: Set<String>,
) {

    /** What is selected on [section] — empty for any other page, whatever this holds. */
    fun on(section: HomeSection): Set<String> =
        if (this.section == section) names else emptySet()

    /**
     * [name] added on [section], or removed if it was already there.
     *
     * A toggle on a different section **replaces** the selection rather than adding to it: arriving
     * on a page and long-pressing a row starts a selection of that page's rows, and carrying the old
     * page's names silently into it is the leak this type exists to stop.
     *
     * The last row deselected leaves [EMPTY] — section and all — so "nothing selected" has one
     * representation and selection mode ends the same way wherever it ends.
     */
    fun toggled(section: HomeSection, name: String): LibrarySelection {
        val base = if (this.section == section) names else emptySet()
        val next = if (name in base) base - name else base + name
        return if (next.isEmpty()) EMPTY else LibrarySelection(section, next)
    }

    companion object {

        /** Nothing selected anywhere. */
        val EMPTY = LibrarySelection(section = null, names = emptySet())
    }
}
