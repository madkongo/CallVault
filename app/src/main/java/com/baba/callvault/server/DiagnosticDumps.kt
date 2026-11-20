/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.system.diagnostics.SystemLogFilter
import com.baba.callvault.utils.AppLogger

/**
 * The diagnostic dumps the privileged daemon is willing to run on the app's behalf.
 *
 * **Why this exists.** The system half of a debug report is `logcat`, `dumpsys audio`, `dumpsys
 * appops` and `ps` — commands that need the shell user. The app used to reach them by opening its own
 * ADB shell, which meant seven round-trips, each able to reconnect, behind a 45-second budget. On a
 * phone whose transport is not healthy that budget ran out and the user silently got half a report,
 * which cost a tester six rounds of pointless back-and-forth. Reproduced on our own OP12 by revoking
 * WRITE_SECURE_SETTINGS: one file, after a long wait.
 *
 * The daemon already runs as the shell user and the app already talks to it over binder. So it can
 * simply run these itself — no Wireless Debugging, no WRITE_SECURE_SETTINGS, no transport, no
 * retries, no timeout to run out.
 *
 * **Why a whitelist and not a command runner.** A method that executed a string handed over the
 * binder would give shell-uid execution to anything that could reach this service. The app names a
 * dump; the daemon decides what that name means. The only caller-supplied value that ever reaches a
 * command line is the logcat buffer size on restore, and it is checked against [SIZE] first.
 */
object DiagnosticDumps {

    private const val TAG = "CV:DiagDump"

    /** Absolute paths: a bare name resolves against PATH, which a privileged process must not trust. */
    private const val SH = "/system/bin/sh"
    private const val LOGCAT = "/system/bin/logcat"
    private const val DUMPSYS = "/system/bin/dumpsys"
    private const val PS = "/system/bin/ps"
    private const val SETTINGS = "/system/bin/settings"
    private const val GETPROP = "/system/bin/getprop"
    private const val SETPROP = "/system/bin/setprop"

    /**
     * vivo's audioserver decides whether to zero-fill a remote-submix record during a call, and logs the
     * decision (`updateRecordCaptureState … allowCapture`, `setRecordSilenced`, `isLiveApp`) only at verbose
     * level — gated on this property (found via `property_get_bool` in `bin/audioserver`, iQOO A16 dump). Off
     * by default, so the reporter's earlier logs never carried the decision. The shell may set `log.tag.*`,
     * and the value is volatile — a reboot clears it, which is the signal we use to tell "survived" from
     * "was cleared". Turning it on only makes vivo log more; it changes no audio behaviour.
     */
    private const val VIVO_VERBOSE_TAG = "log.tag.audio.vivo.verbose"

    /** A logcat buffer size and nothing else — digits with an optional unit. */
    private val SIZE = Regex("^[0-9]{1,7}[KMG]?$")

    /** A logcat `-T` start time and nothing else: `MM-DD hh:mm:ss.mmm`. */
    private val STAMP = Regex("^[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}$")

    /** How long any one dump may take. Generous for `logcat -d` on a full ring, finite for everything. */
    private const val TIMEOUT_SECONDS = 20L
    private const val TIMEOUT_MS = TIMEOUT_SECONDS * 1_000

    /**
     * The command for [key], or null if it is not one we run.
     *
     * [arg] is used only by `logcat_restore`, and only after [SIZE] accepts it.
     */
    fun commandFor(key: String, arg: String?): Array<String>? = when (key) {
        "logcat_size" -> arrayOf(LOGCAT, "-g")
        "logcat_grow" -> arrayOf(LOGCAT, "-b", "main", "-G", "8M")
        "logcat_restore" -> arg?.takeIf { SIZE.matches(it) }?.let { arrayOf(LOGCAT, "-b", "main", "-G", it) }
        "logcat_dump" -> arrayOf(LOGCAT, "-b", "main", "-b", "system", "-d", "-v", "threadtime")
        // The same log, cut down in this process to what is about the app (see runLogcatOurs). Plain logcat, no
        // shell: the caller's argument says who the app is and is parsed here as digits, never put on a command line.
        LOGCAT_OURS -> arrayOf(LOGCAT, "-b", "main", "-b", "system", "-d", "-v", "threadtime")
        // The debugging/USB lines of the log from a given moment on, for USB debugging going off
        // (UsbDebuggingRestorer). Filtered and capped here: everything since the moment ran past the binder's
        // 1 MB limit on the OP9 ("Transaction failed on small parcel"). The argument only reaches the shell after
        // [STAMP] has accepted it — digits, dashes, colons, a space and a dot — and inside single quotes.
        "logcat_since" -> arg?.takeIf { STAMP.matches(it) }?.let {
            arrayOf(
                SH, "-c",
                "$LOGCAT -b main -b system -b events -d -v threadtime -T '$it' " +
                    "| grep -E 'Adb|ADB|Usb|USB|(^|[^A-Za-z])(adb|usb)|[Dd]ebugg|[Dd]evelop' | head -n 800",
            )
        }
        // Why Android stopped us, from the buffers the plain dump above does not read. See [lifecycleScript].
        // Fixed command; any argument is ignored.
        LOGCAT_LIFECYCLE -> arrayOf(SH, "-c", lifecycleScript(LOGCAT))
        "dumpsys_audio" -> arrayOf(DUMPSYS, "audio")
        "appops_all" -> arrayOf(DUMPSYS, "appops")
        // Filtered on the device: the raw dump is megabytes and most of it is irrelevant. A pipeline
        // is the one case that needs a shell.
        "appops_mic" -> arrayOf(
            SH, "-c",
            "$DUMPSYS appops | grep -E '^[[:space:]]*(Uid [0-9]+:|Package |[A-Z_]+ \\(|Running start at:)'",
        )
        "processes" -> arrayOf(PS, "-A", "-o", "USER,PID,ARGS")
        // The same list with the parent and the start time, for BabApps #95: who launched a process, and when, next to
        // the microphone app-op's own "running since". A separate key so the three-column parser of "processes" stays as is.
        "processes_times" -> arrayOf(PS, "-A", "-o", "USER,PID,PPID,STIME,ARGS")
        // Bigger main log buffer for the host to set once when it starts (BabApps #95): the default 256 KiB rotates
        // within minutes, long before a report is exported. Fixed command, no argument.
        "logcat_grow_host" -> arrayOf(LOGCAT, "-b", "main", "-G", "16M")
        // Every line of the audio service's dump that speaks of a recording client, a silenced flag, a
        // capture or the audio mode: the part of `dumpsys audio` that says whether some client is still
        // registered after a call (BabApps item 94: other apps record silence after a call). Filtered
        // on the device because the whole dump is large. Fixed command; any argument is ignored.
        "audio_record_state" -> arrayOf(
            SH, "-c",
            "$DUMPSYS audio | grep -iE 'record|silenc|capture|audio mode|mode:|mic' | head -n 200",
        )
        // The two settings Android 17 redacts, read from HERE rather than from the app.
        //
        // Both of the platform's redaction sites exempt uid < FIRST_APPLICATION_UID, and the daemon is
        // uid 2000 (confirmed on a Pixel 8 running Android 17: `recorder host identity: uid=2000 …
        // context=u:r:shell:s0`). So this process should see the true value where the app process is
        // told "0" whatever the truth. Nothing in the app can recover it — the second redaction site
        // runs INSIDE the calling app's own process — so a lower uid is the only way.
        //
        // Read-only, fixed key, no argument: the name selects the setting, the caller never supplies it.
        // See docs/dev-notes/2026-09-19-android-17-adb-detection-issue-40.md.
        "setting_adb_enabled" -> arrayOf(SETTINGS, "get", "global", "adb_enabled")
        "setting_dev_options" -> arrayOf(SETTINGS, "get", "global", "development_settings_enabled")
        // The precise state of the foreground call — DIALING, ALERTING, ACTIVE — which "start when
        // they answer" polls for. `READ_PRECISE_PHONE_STATE` is a signature permission the app can
        // never hold; the registry's dump prints the field for the shell. Filtered here because the
        // full dump is thousands of lines and this is read every half second while a call rings.
        // One line per phone: on a dual-SIM device the SIM not in the call says IDLE, so every line
        // is returned and the reader picks the one that is in a call (see AnswerWait).
        "call_state" -> arrayOf(SH, "-c", "$DUMPSYS telephony.registry | grep mForegroundCallState")
        // The mixer's output threads: which device each plays to and its latency. The far party of
        // an app call is tapped BEFORE this latency and the user hears it after, so a large one (a
        // Bluetooth route above all) puts the far party ahead of the user's replies in the file by
        // exactly that much — issue #41's symptom. Read a few seconds into an app call.
        "audio_latency" -> arrayOf(
            SH, "-c",
            "$DUMPSYS media.audio_flinger | grep -iE 'Output thread|Output devices|latency|Standby: |Sample rate'",
        )
        // Where an app call's audio goes — for a far party that stays digital silence with the sink open
        // (vivo V2507A, 2026-09-28). Read a few seconds into the call. Each is cut short on the device: the
        // raw dumps run to megabytes. Fixed commands; any argument is ignored.
        //
        // The audio policy's registered dynamic mixes and their rules: is our loop-back mix there, with the
        // voice-communication rule and its address?
        "voip_policy" -> arrayOf(
            SH, "-c",
            // From "Inputs" on: each open recording input with its device and address (where our sink is
            // really attached), then the mixes — so the two addresses can be compared.
            "$DUMPSYS media.audio_policy | sed -n '/^ Inputs (/,/Preferred mixer/p' | head -n 120",
        )
        // Who is playing right now, with usage and flags — is the calling app's track VOICE_COMMUNICATION,
        // and does it carry a no-capture flag? Idle players are left out.
        "voip_players" -> arrayOf(
            SH, "-c",
            "$DUMPSYS audio | sed -n '/^  players:/,/ducked players/p' | grep -v 'state:idle' | head -n 40",
        )
        // Who owns the live call-voice audio: every player line (uid, usage, state) and every recording line
        // (source, uid, package) of the audio service's dump. For the normal-mode app-call start (BabApps item 108),
        // which has to know the PACKAGE of a player and a recording the app is shown anonymised.
        "voip_owners" -> arrayOf(
            SH, "-c",
            "$DUMPSYS audio | grep -E 'AudioPlaybackConfiguration|riid [0-9]+; active|source client=' | grep -v 'state:idle' | head -n 120",
        )
        // Each mixer output, its device, and its track table (the Usg column is the usage) — is the call's
        // track on the remote-submix output our sink reads, or on a voice/VoIP output the mix never sees?
        "voip_tracks" -> arrayOf(
            SH, "-c",
            "$DUMPSYS media.audio_flinger | awk '/^Output thread/{print} /Output devices/{print} " +
                "/Tracks of which|^  [0-9]+ Tracks/{print; t=1; next} t && (/^ *\$/ || /Effect Chains|Local log/){t=0} t{print}' " +
                "| head -n 150",
        )
        // The audio stack's own log lines around an app call — submix HAL, vendor audio features, anything
        // "silenced" — which the report's system-log filter leaves out. Read at the end of an app call whose
        // far party was never heard. Fixed command; any argument is ignored.
        "voip_audio_log" -> arrayOf(
            SH, "-c",
            // vivo's own record-silencing decision (strings found in vivo's audioserver, 2026-09-28):
            // updateRecordCaptureState … allowCapture, setRecordSilenced, isRemoteSubMixApp/isLiveApp, WhitePkgList.
            "$LOGCAT -d -b main -b system | grep -iE 'submix|remote_support|remote_showstatus|AudioFeature|VivoAudio|" +
                "isLiveApp|gamecube|AudioPolicyMix|silenc|playback.?capture|allowCapture|updateRecordCaptureState|" +
                "WhitePkgList|LiveApp|isRemoteSubMixApp|isSpecialCapture' | grep -v 'adbd' | tail -n 150",
        )
        // Turns vivo's own audioserver verbose logging on so THIS call's silencing decision is written, and
        // reports whether it had been on beforehand — empty means a reboot (or first run) cleared it, which is
        // the auto-heal signal: re-armed at every app-call start, so a post-reboot call sets it again. Sets a
        // log tag only; on a non-vivo phone it is a harmless unused property. Fixed command; any argument ignored.
        "vivo_verbose_arm" -> arrayOf(
            SH, "-c",
            "was=$($GETPROP $VIVO_VERBOSE_TAG); $SETPROP $VIVO_VERBOSE_TAG true; " +
                "echo \"$VIVO_VERBOSE_TAG before=[\$was] after=[$($GETPROP $VIVO_VERBOSE_TAG)]\"",
        )
        // vivo's audio switches, read-only — e.g. persist.sys.audio.vapc.record.share_record.enable, which its
        // policy XML ships "off" (iQOO firmware dump, 2026-09-28). Harmless elsewhere: matches nothing.
        "vivo_audio_props" -> arrayOf(
            SH, "-c",
            "/system/bin/getprop | grep -iE 'vapc|vivo.*audio|audio.*vivo|liveapp|remote_?submix' | head -n 40",
        )
        else -> null
    }

    /**
     * The crash buffer, plus every system and event-log line naming our package, plus the stack trace under
     * any refused foreground notice — each cut to its newest lines ON THE DEVICE.
     *
     * Why (vivo Android 17, 2026-11-20): three "Bad notification for startForeground" crashes and the report
     * held no system line about any of them. The plain dump reads main+system whole, which on a 16 MB ring
     * runs past the binder's 1 MB limit and is easily empty on a ROM that keeps its system log quiet, and it
     * never read `-b crash` or `-b events` at all — where `am_crash`, `am_kill` and `am_proc_died` live.
     * Bounded to ~1,800 lines so the answer always fits through the binder. The app filters it again by
     * exact package, uid and pid ([com.baba.callvault.system.diagnostics.SystemLogFilter.select]); the
     * package in the grep is a coarse first cut so that little else is ever read off the device.
     *
     * [logcat] is the binary to run: an absolute path in the daemon, plain `logcat` over the app's ADB shell.
     */
    fun lifecycleScript(logcat: String): String =
        "{ $logcat -b crash -d -v threadtime | tail -n 400; " +
            "$logcat -b system -b events -d -v threadtime | grep -F 'com.baba.callvault' | tail -n 1200; " +
            "$logcat -b system -d -v threadtime " +
            "| grep -E -A 20 'Error showing notification for service|invalid (channel for )?service notification|Bad notification' " +
            "| grep -v '^--\$' | tail -n 200; }"

    /** Runs [key] and returns its output, or null if it is not allowed, fails, or times out. */
    fun run(key: String, arg: String?): String? {
        if (key == LOGCAT_OURS) return runLogcatOurs(arg)
        if (key == LOGCAT_LIFECYCLE) return runLifecycle()
        val command = commandFor(key, arg) ?: run {
            AppLogger.w(TAG, "refused unknown diagnostic dump '$key'")
            return null
        }
        return runCatching {
            val outcome = BoundedProcess.run(command, TIMEOUT_MS) { it.bufferedReader().readText() }
            if (outcome.timedOut) {
                AppLogger.w(TAG, "diagnostic dump '$key' timed out after ${TIMEOUT_SECONDS}s; stopped it")
                return null
            }
            outcome.value
        }.onFailure { AppLogger.w(TAG, "diagnostic dump '$key' failed: ${it.message}") }.getOrNull()
    }

    private const val LOGCAT_OURS = "logcat_ours"
    private const val LOGCAT_LIFECYCLE = "logcat_lifecycle"

    /** Characters the crash and event slice may send: ~500 KB as UTF-16, under the binder's 1 MB with room to spare. */
    const val LIFECYCLE_MAX_CHARS = 250_000

    /**
     * [lifecycleScript]'s output, cut to its newest [LIFECYCLE_MAX_CHARS] in this process and sent with a summary
     * line saying how many lines were left out and whether the run was partial. Its line limits alone did not bound
     * it: long ActivityManager or NotificationService lines could still overrun the binder, and in Shizuku mode —
     * no ADB fallback — the report then had no crash or event log at all.
     */
    private fun runLifecycle(): String? {
        val command = commandFor(LOGCAT_LIFECYCLE, null) ?: return null
        return runCatching {
            val outcome = BoundedProcess.run(command, TIMEOUT_MS) {
                SystemLogFilter.capNewest(it.bufferedReader().lineSequence(), LIFECYCLE_MAX_CHARS)
            }
            val cut = outcome.value ?: run {
                AppLogger.w(TAG, "diagnostic dump '$LOGCAT_LIFECYCLE' gave nothing (${outcome.partial})")
                return null
            }
            SystemLogFilter.render(cut.copy(partial = outcome.partial?.let { "the slice $it" }))
        }.onFailure { AppLogger.w(TAG, "diagnostic dump '$LOGCAT_LIFECYCLE' failed: ${it.message}") }.getOrNull()
    }

    /**
     * The main+system log cut down to what is about the app, bounded under the binder's limit, with a summary
     * line saying how much was read and how much had to be left out ([SystemLogFilter.prefilter]).
     *
     * Why (vivo, Shizuku mode, 2026-11-20): `logcat_dump` sent the whole log, which with the 16 MB ring is far
     * past the binder's 1 MB; the call failed, the app fell back to its ADB shell, Shizuku mode refuses that, and
     * the report had no main or system log at all. Streamed, so this process holds only what it keeps.
     */
    private fun runLogcatOurs(arg: String?): String? {
        val ours = SystemLogFilter.decodeOurs(arg, com.baba.callvault.BuildConfig.APPLICATION_ID) ?: run {
            AppLogger.w(TAG, "refused '$LOGCAT_OURS': the identity argument is not digits in the expected shape")
            return null
        }
        val command = commandFor(LOGCAT_OURS, null) ?: return null
        return runCatching {
            // Bounded for real: a wedged logd used to hold this binder thread for good. A cut that ended early
            // carries why, and the report says the log is partial.
            val outcome = BoundedProcess.run(command, TIMEOUT_MS) {
                SystemLogFilter.prefilterCut(it.bufferedReader().lineSequence(), ours, SystemLogFilter.PREFILTER_MAX_CHARS)
            }
            val cut = outcome.value ?: run {
                AppLogger.w(TAG, "diagnostic dump '$LOGCAT_OURS' gave nothing (${outcome.partial})")
                return null
            }
            outcome.partial?.let { AppLogger.w(TAG, "diagnostic dump '$LOGCAT_OURS' is partial: logcat $it") }
            SystemLogFilter.render(cut.copy(partial = outcome.partial?.let { "logcat $it" }))
        }.onFailure { AppLogger.w(TAG, "diagnostic dump '$LOGCAT_OURS' failed: ${it.message}") }.getOrNull()
    }
}
