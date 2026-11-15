/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * Decides whether a uid the daemon named can be the app on a VoIP call, by what the uid IS.
 *
 * The daemon reads the uid from the audio system, and on some phones that answer is System UI (uid
 * 10314 in one report, from "playback track in dump"): an app uid, so the plain uid >= 10000 check let
 * it through, and its ongoing battery notification ("Charging (59 m until full)") then became the
 * caller's name (BabApps item 103). A calling app is user-installed, or a preinstalled app the user can
 * open (it has a launcher activity: Meet, Teams, a vendor dialer app). A platform component that is
 * flagged system and has no launcher is never the call.
 *
 * Kept free of Android types so it can be unit-tested; the coordinator fills in [PackageFacts].
 */
internal object VoipCallAppFilter {

    /** What the coordinator read from PackageManager about one package under a uid. */
    data class PackageFacts(val name: String, val isSystem: Boolean, val hasLauncher: Boolean)

    /** The uid that was accepted, with the packages under it (for the log line). */
    data class Resolved(val uid: Int, val packages: List<PackageFacts>)

    /** Platform packages that are never a call, whatever else is known about them. */
    private val PLATFORM_PACKAGES = setOf("com.android.systemui", "android")

    /** A new uid is tried at most this many times per lookup: each rejection costs a daemon call. */
    private const val MAX_TRIES = 3

    /** Whether any package under the uid could be the calling app. */
    fun isCallApp(packages: List<PackageFacts>): Boolean =
        packages.any { it.name !in PLATFORM_PACKAGES && (!it.isSystem || it.hasLauncher) }

    /**
     * Asks for the call app's uid until one is a calling app, giving the daemon the uids already
     * rejected so every source in it can answer with the next candidate.
     *
     * @param askUid the daemon's answer, given the rejected uids; [unknownUid] when none
     * @param packagesFor the packages under a uid; empty when none is visible
     * @param log one line per lookup, no notification text
     * @return the accepted uid and its packages, or null (nothing found, or only non-call apps)
     */
    fun resolve(
        stage: String,
        unknownUid: Int,
        askUid: (rejected: Set<Int>) -> Int,
        packagesFor: (Int) -> List<PackageFacts>,
        log: (String) -> Unit,
    ): Resolved? {
        val rejected = LinkedHashSet<Int>()
        repeat(MAX_TRIES) {
            val uid = askUid(rejected)
            if (uid == unknownUid) return null
            val packages = packagesFor(uid)
            val listed = packages.joinToString(",") { it.name }.ifEmpty { "none visible" }
            if (packages.isEmpty()) {
                log("VoIP call app ($stage): uid $uid, packages $listed: no visible package, skipped")
                return null
            }
            if (isCallApp(packages)) {
                log("VoIP call app ($stage): uid $uid, packages $listed: accepted")
                return Resolved(uid, packages)
            }
            log("VoIP call app ($stage): uid $uid, packages $listed: rejected, not a calling app")
            if (!rejected.add(uid)) return null   // the daemon repeated a skipped uid (older host)
        }
        return null
    }
}
