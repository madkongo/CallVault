/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings

import android.content.Intent

/**
 * What an incoming share means, and whether this app is in a position to act on it.
 *
 * **Why a share target exists at all.** The SAF picker can only offer what a document provider is
 * willing to enumerate, and the file this feature exists for — a WhatsApp voice note — lives in
 * WhatsApp's own private storage, where no picker can see it. Sharing is the only route to it. So
 * the picker and the share sheet are not two ways to do the same thing: one of them is the only way
 * to reach the files people actually want transcribed.
 *
 * **Everything here is a decision, and nothing here touches a file.** The verdict is taken from the
 * Intent's shape alone so it can be tested without an Android runtime, and so the hazards each guard
 * prevents are written down next to the guard rather than buried in an Activity.
 *
 * ## What is deliberately NOT checked here
 *
 * **The sender's MIME type.** The manifest's `<data android:mimeType>` decides whether CallVault
 * appears in the share sheet at all; once the user has picked us, the type on the Intent is a claim
 * by the sending app about a file it may have got from somewhere else. [ImportableAudio] and
 * [AudioImport]'s decode probe judge the file itself — its name, the type its *provider* reports,
 * and finally whether this phone can decode the bytes. Re-checking the sender's claim here would add
 * a fourth opinion that is the weakest of the four, and its only possible effect would be to refuse
 * a file the other three would have accepted.
 */
object SharedAudio {

    /** What to do about an Intent that arrived at the share target. */
    sealed interface Verdict {

        /** Read the stream and hand it to [AudioImport]. */
        data object Import : Verdict

        /** Do nothing, and say [reason] out loud. */
        data class Refuse(val reason: Reason) : Verdict
    }

    /** Why a share was not acted on. */
    enum class Reason {

        /**
         * Not a share at all: some other action, or `ACTION_SEND_MULTIPLE`.
         *
         * Multiple is refused rather than half-handled. Importing each file is a loop, but the
         * result is not: one copy can fail while another succeeds, a refusal is a sentence about
         * *one* file, and the screen has one outcome to show. That is batch import, which the plan
         * already has as its own piece of work — and a share target that took two files and
         * reported on one would be worse than one that says plainly it takes one at a time.
         */
        NOT_A_SHARE,

        /**
         * A share with no file on it.
         *
         * Reachable in practice: several apps offer "share" on a message and send the *text* of it,
         * with no `EXTRA_STREAM` at all, and a chooser can be re-entered after the sender has
         * revoked what it offered. Without this the Activity would call [AudioImport] with a null
         * URI and the user would watch a spinner refuse a file they never chose.
         */
        NOTHING_SHARED,

        /**
         * CallVault has not finished its one-time setup, so there is nowhere to put the file.
         *
         * This is the hazard the separate Activity exists for. `MainActivity` routes an unfinished
         * setup straight into the disclaimer or the wizard; a share landing there would abandon the
         * file silently and leave the user in a setup flow they did not ask for. Told instead.
         */
        NOT_SET_UP,
    }

    /**
     * What to do about a share, decided from its shape alone.
     *
     * The order is the same one [AudioImport.planFor] uses and for the same reason: what is wrong
     * with *this share* first, because it is the commonest and the most explicable, then what is
     * wrong with *the app*, which is a confusing thing to be told about a file we were never given.
     *
     * @param action       the Intent's action, or null when it carries none.
     * @param hasStream    whether the Intent carries an `EXTRA_STREAM`.
     * @param isAppSetUp   whether onboarding has finished — the router's own answer, passed in, so
     *                     there is one place that decides what "set up" means.
     */
    fun verdictFor(action: String?, hasStream: Boolean, isAppSetUp: Boolean): Verdict = when {
        action != Intent.ACTION_SEND -> Verdict.Refuse(Reason.NOT_A_SHARE)
        !hasStream -> Verdict.Refuse(Reason.NOTHING_SHARED)
        !isAppSetUp -> Verdict.Refuse(Reason.NOT_SET_UP)
        else -> Verdict.Import
    }
}
