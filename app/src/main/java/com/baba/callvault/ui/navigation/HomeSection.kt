/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.navigation

/**
 * The sections that will live behind [AppScreen.Home] once it becomes a hub: a page of cards, and
 * the three lists reachable from it.
 *
 * Declared ahead of the screens themselves, and deliberately: the one decision worth getting wrong
 * only once is which section the app opens on. Nothing routes on this yet — [opening] is called from
 * nowhere, and Home still resolves to the recordings list exactly as it did.
 */
enum class HomeSection(val key: String) {

    /** The cards. Where a fresh install lands, and where back from a section returns to. */
    Hub("hub"),

    /** The recordings list — everything Home shows today. */
    Recordings("recordings"),

    /** Everything transcribed, plus the transcription tools and audio import. */
    Transcripts("transcripts"),

    /** Everything summarised. */
    Summaries("summaries");

    companion object {

        /**
         * Which section a visit should open on.
         *
         * Three rules, and the order between them is the whole point:
         *
         * 1. **Onboarding wins.** [resolvedScreen] is the router's own answer, passed in rather than
         *    recomputed, so there is exactly one place that decides whether setup is finished. Null
         *    means no section at all: someone who has not accepted the disclaimer or finished the
         *    wizard has no recordings to be returned to, and reopening "where they were" ahead of
         *    that would drop them into an app that cannot record.
         * 2. **Nothing stored means the hub**, not the recordings list. A first run has no history to
         *    resume, and the cards are the thing that explains what the app now has.
         * 3. **A stored section that no longer exists means the hub too.** The key is written by
         *    whatever build the user was last on, and a section can be renamed or dropped between
         *    releases; a missing one has to land somewhere real rather than on a blank screen.
         *
         * @param resolvedScreen The destination the router settled on for this visit.
         * @param storedKey      The raw [key] last written, or null if none ever was.
         * @return The section to open, or null when onboarding must run first.
         */
        fun opening(resolvedScreen: AppScreen, storedKey: String?): HomeSection? {
            if (resolvedScreen != AppScreen.Home) return null
            return entries.firstOrNull { it.key == storedKey } ?: Hub
        }
    }
}
