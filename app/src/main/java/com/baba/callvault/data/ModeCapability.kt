/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

/**
 * What each privileged mode can actually do — the single list the UI greys out from and the mode switch
 * turns off from.
 *
 * **Everything here follows from two measured facts, not from taste:**
 *
 *  1. ~~A Shizuku-hosted process cannot get an `AudioRecord` into RECORDING state~~ — true until
 *     2026-10-19, when the cause turned out to be the host presenting our package under uid 2000.
 *     `ShellAttribution` fixed it (OP9: carrier and VoIP calls recorded directly in the Shizuku host), so
 *     every capture-side capability now works in both modes.
 *  2. In Shizuku mode there is **no embedded ADB connection at all**, so anything that drives adb has
 *     nothing to talk to.
 *
 * Listing the capabilities that *do* survive is as deliberate as listing the ones that do not: it makes
 * "does a mode switch disturb transcription?" a question with a written answer.
 */
enum class ModeCapability {

    /** Recording a carrier call at all. Direct AudioRecord in both modes; scrcpy is the fallback. */
    CARRIER_RECORDING,

    /**
     * Resilient recording: the daemon hands its live capture to the app so a recording survives the
     * daemon dying mid-call. Needs a privileged `AudioRecord` handed across a binder.
     */
    RESILIENT_RECORDING,

    /**
     * Recording app (VoIP) calls. `VoipCaptureSession` runs two `AudioRecord`s against a dynamic audio
     * policy. Android 14+ in both modes (the shell gains CAPTURE_VOICE_COMMUNICATION_OUTPUT in 14).
     */
    VOIP_RECORDING,

    /**
     * Attributing transcript lines to a speaker. The turns are measured from the two capture channels
     * *during* the call by `SpeakerTurnDetector`, which lives in the direct, handoff and VoIP captures.
     * Since the Shizuku host records directly (ShellAttribution, 2026-10-19), both modes have them; a call
     * that still falls back to scrcpy gets them afterwards from the offline labeller instead.
     */
    SPEAKER_ATTRIBUTION,

    /** Recording while off Wi-Fi over a loopback ADB port. Pure embedded-ADB machinery. */
    OFFLINE_RECORDING,

    /** Turning Wireless debugging on and off around the daemon, and the USB default-mode nudge. */
    WIRELESS_DEBUGGING_CONTROL,

    /** The keep-alive foreground service that relaunches our daemon. Shizuku restarts its own. */
    DAEMON_KEEP_ALIVE,

    /** Installing an update with no taps, by streaming into `pm install` over the ADB shell. */
    SILENT_UPDATE_INSTALL,

    /** On-device transcription and summaries. Reads finished files; never touches the recorder. */
    TRANSCRIPTION,

    /** Storage targets, Drive sync, retention. Also purely about files. */
    CLOUD_SYNC,

    ;

    /** Whether this capability works in [mode]. */
    fun isAvailableIn(mode: PrivilegedMode): Boolean = when (this) {
        // Works in the Shizuku host since it presents the shell identity (ShellAttribution; measured
        // on the OP9 2026-10-19 with a call-free self-test).
        VOIP_RECORDING -> true
        // Same privileged AudioRecord, same host code. Speaker turns come from the live detector in the
        // capture, which no longer differs by host; the handoff refuses a record that did not start.
        RESILIENT_RECORDING, SPEAKER_ATTRIBUTION -> true
        // Needs our own embedded ADB.
        OFFLINE_RECORDING, WIRELESS_DEBUGGING_CONTROL, DAEMON_KEEP_ALIVE, SILENT_UPDATE_INSTALL ->
            !mode.needsShizuku
        // Mode-independent.
        CARRIER_RECORDING, TRANSCRIPTION, CLOUD_SYNC -> true
    }

    /**
     * The oldest Android API level this works on (backlog #1). Evidence and gaps:
     * docs/dev-notes/2026-09-29-feature-android-version-map.md.
     *  - App calls need 34: before Android 14 the shell has no `CAPTURE_VOICE_COMMUNICATION_OUTPUT`
     *    (a tester's Android 12 failed at `setTargetMixRole`, #42's Android 13 at policy registration).
     *  - Resilient recording needs 33: it captured nothing on Android 12 (a tester) and was never tested on 11.
     * Everything else works from the app's minimum (Android 11).
     */
    val minSdk: Int get() = when (this) {
        VOIP_RECORDING -> ANDROID_14
        RESILIENT_RECORDING -> ANDROID_13
        else -> 0
    }

    /** The Android release a user would recognise, for "Needs Android N or newer". */
    val minAndroidRelease: String get() = when (minSdk) {
        ANDROID_14 -> "14"
        ANDROID_13 -> "13"
        else -> ""
    }

    fun isAvailableOn(sdk: Int): Boolean = sdk >= minSdk

    /** Both halves: the mode can do it and this Android version can. */
    fun isAvailable(mode: PrivilegedMode, sdk: Int): Boolean = isAvailableIn(mode) && isAvailableOn(sdk)

    companion object {
        private const val ANDROID_13 = 33
        private const val ANDROID_14 = 34

        /** Everything [mode] cannot do — what Settings greys out and the mode switch turns off. */
        fun unavailableIn(mode: PrivilegedMode): Set<ModeCapability> =
            entries.filterNot { it.isAvailableIn(mode) }.toSet()
    }
}
