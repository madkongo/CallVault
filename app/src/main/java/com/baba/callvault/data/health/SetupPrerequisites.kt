/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.health

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.PrivilegedMode
import com.baba.callvault.server.RecorderBackend
import com.baba.callvault.server.ShizukuStatus
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.DeveloperOptions
import com.baba.callvault.server.RecorderConnection

/** A user-owned condition that must hold for a call to be recordable at all. */
enum class Prerequisite {
    RECORDING_FOLDER,
    ADB_PAIRING,
    DEVELOPER_OPTIONS,
    SECURE_SETTINGS_GRANT,

    /**
     * Shizuku is not installed, not running, or has not allowed CallVault — in Shizuku mode.
     *
     * User-owned like the rest: Shizuku does not survive a reboot and has to be started again, which
     * makes "Shizuku is not running" a real and fixable reason a call went unrecorded rather than a
     * mystery to be blamed on the app.
     */
    SHIZUKU,
}

/**
 * The single definition of "is this setup capable of recording right now", shared by
 * [com.baba.callvault.ui.viewmodels.HomeViewModel.computeStatus] (which maps the result onto its
 * `HomeStatus` enum for the card) and [com.baba.callvault.services.call.CallSessionManager] (which
 * uses it to report a call precisely instead of leaving it to the gap sweep to infer later).
 *
 * Every condition here is USER-OWNED: a missing recording folder, an ADB pairing never completed, the
 * Developer options master toggle off, or the WRITE_SECURE_SETTINGS grant gone while there is no live
 * daemon to fall back on. These are deliberate or environmental facts, not a symptom of the daemon
 * failing on its own — that distinction is why a call in a window where [missing] is non-null EXCUSES
 * the call, while a live daemon dying with every prerequisite intact must never be excused by this
 * check. Daemon liveness ([RecorderConnection.isConnected]) is intentionally examined only as a
 * fallback for the last condition, exactly as `computeStatus()` already did — never as a check of its
 * own, and never in a way that hides a daemon that died on its own.
 */
object SetupPrerequisites {

    /**
     * The first missing prerequisite, in the same precedence
     * [com.baba.callvault.ui.viewmodels.HomeViewModel.computeStatus] checks them, or null when all
     * are met. Synchronous, cheap reads only — no I/O, no daemon launch.
     */
    fun missing(context: Context): Prerequisite? {
        val preferences = AppPreferences(context)
        val mode = preferences.getPrivilegedMode()
        return firstMissing(
            mode = mode,
            hasFolder = preferences.getRecordingFolderUri() != null,
            isPaired = preferences.isAdbPaired(),
            // isExplicitlyDisabled (not !isEnabled): an absent/unreadable global must not read as
            // missing on a ROM that doesn't expose the setting — see DeveloperOptions' own doc comment.
            devOptionsDisabled = DeveloperOptions.isExplicitlyDisabled(context),
            hasSecureSettings = AdbShell.hasWriteSecureSettings(context),
            daemonConnected = RecorderConnection.isConnected,
            // Only consulted in Shizuku mode, and cheap: three local checks, no I/O.
            shizuku = if (mode.needsShizuku) RecorderBackend.shizukuStatus(context) else ShizukuStatus.READY,
        )
    }

    /**
     * The decision itself, over facts rather than a Context, so it can be tested.
     *
     * **The two modes ask different questions.** A Shizuku user never pairs anything, never turns
     * Wireless debugging on and never grants WRITE_SECURE_SETTINGS; asking those of them would report
     * "not ready" for ever on a phone that records perfectly, and — worse — would *excuse* every missed
     * call as their own doing, which is precisely the reporting this project worked to make honest.
     */
    fun firstMissing(
        mode: PrivilegedMode,
        hasFolder: Boolean,
        isPaired: Boolean,
        devOptionsDisabled: Boolean,
        hasSecureSettings: Boolean,
        daemonConnected: Boolean,
        shizuku: ShizukuStatus,
    ): Prerequisite? {
        // Asked first in both modes: it is about where recordings go, not about privileges.
        if (!hasFolder) return Prerequisite.RECORDING_FOLDER

        if (mode.needsShizuku) {
            // A recorder that is already connected outranks anything Shizuku says now: the user
            // service outlives the app, so Shizuku stopping does not stop a live recording — and
            // calling that "not ready" would excuse a genuine failure as the user's fault.
            if (daemonConnected) return null
            return if (shizuku == ShizukuStatus.READY) null else Prerequisite.SHIZUKU
        }

        if (!isPaired) return Prerequisite.ADB_PAIRING
        // A connected daemon outranks this flag, for the same reason it outranks Shizuku's status above
        // and the grant below: recording is flowing right now, so calling the setup incomplete would
        // excuse a genuine failure and write "this call was not recorded" against calls that were.
        if (devOptionsDisabled && !daemonConnected) return Prerequisite.DEVELOPER_OPTIONS
        // The grant is only needed to relaunch a DEAD daemon; while one is already connected, recording
        // works right now regardless of the grant, so this only counts as missing when BOTH are true.
        if (!hasSecureSettings && !daemonConnected) return Prerequisite.SECURE_SETTINGS_GRANT
        return null
    }
}

/**
 * Reports, for the status card, that the call starting at [atMillis] could not have been recorded
 * because [missing] was absent — precise reporting (naming the exact cause via
 * [SetupHealthStore.recordMissedWhileNotReady]) instead of leaving it to the gap sweep to later infer
 * an unexplained [SetupHealth.CallNotRecorded] for a call that was never recordable in the first place.
 *
 * Two refusals matter as much as the recording itself:
 *  - A no-op when [missing] is null: a daemon that dies later on an otherwise-complete setup is the
 *    normal path this whole feature exists to catch, and must stay reportable, so nothing is ever
 *    recorded here on that path.
 *  - GATED on the setup having verified at least once before ([HealthFacts.lastVerifiedAt] > 0). A user
 *    who has never had a single working call — mid-onboarding, no folder configured yet, never paired —
 *    must never be told a call was "missed"; there is nothing earlier that proved recording ever worked
 *    for them to have lost. Without this gate, reporting turns into nagging someone who never finished
 *    setting the app up.
 */
fun SetupHealthStore.recordMissedForMissingPrerequisite(missing: Prerequisite?, atMillis: Long, label: String?) {
    if (missing == null) return
    if (read().lastVerifiedAt <= 0L) return
    recordMissedWhileNotReady(atMillis, label, missing)
}
