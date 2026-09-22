/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server;

/**
 * CallVault Plan 5 — PRODUCTION recorder command channel.
 *
 * Implemented by the detached, privileged shell-uid recorder daemon
 * ([com.baba.callvault.server.RecorderServer]) and called BY THE APP over a raw binder
 * (no ADB), even while Wireless debugging is OFF. The daemon runs scrcpy-server + the muxer; the APP
 * owns metadata (SAF filename, call-log lookups) and merely hands the daemon a writable output fd.
 *
 * KISS simplification vs the Plan 5 draft signature: recording metadata stays APP-side, so the
 * interface only carries what the daemon needs (source/codec/bitRate + the output fd). The app names
 * the SAF file and performs call-log lookups itself, exactly as
 * [com.baba.callvault.services.recording.AudioRecordingEngine] does today.
 *
 * Mirrors Shizuku's IShizukuService command-channel pattern.
 */
interface IRecorderService {

    /**
     * Starts a recording session. The daemon launches scrcpy-server with the given [source]/[codec]/
     * [bitRate] and muxes the captured audio into [outFd] (a writable fd the APP opened from its SAF
     * file — the privileged daemon writes through it). No-op + rejected if already recording.
     *
     * @param source  scrcpy `audio_source` cliKey (ScrcpyAudioSource.cliKey, e.g. "voice-call").
     * @param codec   scrcpy `audio_codec` cliKey (ScrcpyAudioCodec.cliKey, e.g. "opus").
     * @param bitRate Encoder bit rate in bps.
     * @param outFd   Writable output file descriptor opened by the APP from its SAF recording file.
     */
    void startRecording(String source, String codec, int bitRate, in ParcelFileDescriptor outFd);

    /** Stops the active recording, finalising the container trailer. Idempotent. */
    void stopRecording();

    /** Returns true while a recording session is active. */
    boolean isRecording();

    /** Stops any active recording and terminates the daemon process. */
    void destroy();

    /**
     * "Resilient recording" (audio-capture handoff, Option B). The daemon creates a privileged
     * AudioRecord for [source] at [sampleRate], extracts the IAudioRecord binder + cblk ashmem fd, and
     * DELIVERS them to the app's provider ("sendHandoff"). The app then holds its own ref (keep-alive)
     * and reads the ring + encodes into ITS OWN output fd — so the recording SURVIVES the daemon being
     * killed mid-call. The push delivery runs synchronously inside this call, so on a true return the
     * app has USUALLY started capturing — but the caller must confirm via the app-side capture state
     * (this return only reflects that the daemon delivered, not that the app-side encode actually began).
     *
     * @param source     scrcpy `audio_source` cliKey (must map to a real MediaRecorder.AudioSource;
     *                   output/playback are not supported and must use startRecording instead).
     * @param sampleRate Capture sample rate in Hz (e.g. 48000).
     * @param channels   Preferred channel count (2 for voice-call to capture both directions, 1 for
     *                   mono sources). The daemon may fall back to mono and reports the ACTUAL count in
     *                   the delivery.
     * @return true if the daemon created the track and delivered the handoff to the app (delivery only;
     *         the app confirms live capture separately).
     */
    boolean startHandoff(String source, int sampleRate, int channels);

    /**
     * Arms the VoIP capture policy (a loopback-render AudioMix on USAGE_VOICE_COMMUNICATION playback).
     *
     * MUST be called BEFORE a VoIP call's audio track is created — Android fixes a track's routing at
     * creation, so arming mid-call leaves the call permanently unattached and the recording silent.
     * Arm when the feature is switched on, not when a call starts. Idempotent, and free while idle:
     * an armed policy holds no wakelock and no active record track.
     *
     * @return true if the policy is registered (false when the device does not permit it, most often
     *         a missing CAPTURE_VOICE_COMMUNICATION_OUTPUT on the shell package).
     */
    boolean armVoipCapture();

    /** Unregisters the VoIP capture policy. Idempotent. */
    void disarmVoipCapture();

    /**
     * Records a VoIP call, both directions, into [outFd]: the far party from the armed policy's
     * loopback sink and the near party from the microphone, mixed to mono with the chosen codec.
     * Requires [armVoipCapture] to have been called before the call began. Stop with stopRecording().
     *
     * @return true if capture started.
     */
    boolean startVoipRecording(String codec, int bitRate, in ParcelFileDescriptor outFd);

    /**
     * Whether the far party was ever audible in the last VoIP recording.
     *
     * False means the app opted out of capture, or this OEM build did not attach the call to our mix —
     * indistinguishable from here, and in both cases the user gets a one-sided recording. Queried after
     * stopping so the app can say so rather than leaving them to discover it later.
     */
    boolean voipFarPartyHeard();

    /**
     * The uid of the app whose VoIP call is in progress, or -1 if it cannot be determined.
     *
     * Read from the audio system — the owner of the `USAGE_VOICE_COMMUNICATION` playback track, which
     * is the very stream being recorded — so it cannot name the wrong app. Only the daemon can ask:
     * playback configurations are anonymised for callers without MODIFY_AUDIO_ROUTING.
     *
     * A uid rather than a package because the daemon holds no Context; the app maps it with
     * PackageManager, which handles shared uids and work profiles correctly.
     */
    int voipCallAppUid();

    /**
     * Best-effort name of the person on the call, from the given package's ongoing notification.
     *
     * Scoped to the package resolved from {@link #voipCallAppUid} so a name can never be paired with
     * the wrong app. Null whenever the app does not publish one.
     */
    String voipCallerName(String packageName);

    /**
     * Creates a capture track and hands it over **stopped**, for the app to start itself.
     *
     * Track A: capture permission is checked when the track is created, not when it is started, so a
     * track created once here may be run per call by the app with no daemon alive. Whether AudioFlinger
     * accepts a start from the app's uid is the open question this exists to answer.
     */
    boolean startHandoffHeld(String source, int sampleRate, int channels);

    /** Releases the daemon's held handoff AudioRecord (frees the capture input). Idempotent. */
    void stopHandoff();

    /**
     * Speaker turns observed during the recording that just stopped, or "" when there are none.
     *
     * Queried AFTER stopRecording(), mirroring {@link #voipFarPartyHeard} — the daemon gathers this
     * during capture and the app collects it once the file is finalised.
     *
     * The capture puts the two call directions on separate stereo channels; comparing their energy
     * before the mono downmix yields exact speaker turns at no cost to the audio, which is written
     * unchanged. Encoded by SpeakerTurnCodec as "startMs:channel" pairs joined by ";".
     *
     * Empty is a normal answer, not an error: a mono capture carries no direction information, and
     * the scrcpy fallback never sees the raw channels. Callers must also tolerate this method being
     * absent altogether — a warm daemon from an older build predates it, and that must degrade to "no
     * speaker data" rather than fail the recording that just completed.
     *
     * Channels are reported as A and B rather than near and far: which index is the near party is an
     * OEM detail Android never specifies, so the mapping is learned separately and applied when the
     * transcript is displayed.
     */
    String speakerTurns();

    /**
     * Allows an app op for a package, returning whether it is allowed AFTERWARDS.
     *
     * Appended at the END of this interface deliberately. The methods here have implicit transaction
     * ids taken from their position, so inserting anywhere else renumbers everything below it and a
     * daemon left running by the previous version would answer the wrong call. RecorderTransactionCodesTest
     * pins the numbering.
     *
     * Needs shell (uid 2000), which both hosts provide; NOT root. The result is read back from the
     * system rather than taken from the command's exit code, because the uid-level variant of this
     * command exits 0 while doing nothing — see PrivilegedGrants.
     */
    boolean grantAppOp(String packageName, String opName, int userId);

    /**
     * Makes a package a holder of a role, returning whether it holds it AFTERWARDS.
     *
     * False usually means the package does not QUALIFY for the role (e.g. CallVault is refused
     * android.app.role.DIALER while it declares no android.intent.action.DIAL component) rather than
     * that the caller lacked privilege. Root would not change that.
     */
    boolean grantRole(String roleName, String packageName, int userId);

    /**
     * The uid this recorder process is running as: 2000 for shell, 0 when Shizuku itself was started
     * as root (Sui, or a rooted start).
     *
     * Asked rather than assumed, because it decides what the grants above can reach, and because
     * "Shizuku is running" says nothing about which of the two it is.
     */
    int hostUid();

    /**
     * Kills any leftover CallVault ADB daemon (our detached app_process recorder), from inside this
     * shell-uid process.
     *
     * Appended at the END, like everything else here: the transaction ids are positional.
     *
     * Needed because the two backends are started by completely different machinery. Our own daemon is
     * setsid-detached and survives the app, so switching to Shizuku mode leaves it running — and then
     * TWO shell-uid recorders exist, either of which may hold the binder the app talks to. Measured on
     * the OP9: an app in Shizuku mode recorded a call through the leftover ADB daemon.
     *
     * Deliberately narrow: it matches only the daemon's own main class, which a Shizuku-hosted service
     * never runs under (its process is named <package>:recorder), so this can never kill itself.
     */
    void killStaleRecorders();

    /**
     * Turns the daemon's in-memory diagnostic ring on or off, mirroring the user's logging preference.
     *
     * Appended at the END, like everything else here: the transaction ids are positional.
     *
     * The daemon cannot answer this for itself — it runs as shell, in another process, with no access
     * to the app's preferences — so the app has to tell it. Off by default, so a user who has never
     * enabled logging has nothing collected anywhere.
     */
    void setDiagnosticsEnabled(boolean enabled);

    /**
     * Returns the daemon's collected log lines and clears them.
     *
     * Without this, a debug export contains **nothing at all** from the process that owns the
     * microphone: the daemon cannot write to the app's private files directory, so its lines go to
     * logcat and nowhere else. A stuck-microphone report on 2026-08-25 could not be diagnosed for
     * exactly that reason — whether the daemon released its AudioRecord was the question, and the
     * export could not show it.
     *
     * Each line is already formatted and redacted the same way the app's own lines are, so the two can
     * simply be merged and sorted by their timestamps.
     */
    String[] drainDiagnostics();

    /**
     * Pauses or resumes an in-flight VoIP recording.
     *
     * Appended at the END of this interface on purpose: AIDL assigns transaction ids in declaration
     * order, so inserting anywhere else would renumber every method after it and a daemon from a
     * previous build would answer the wrong call. The app must still tolerate this being absent —
     * a daemon left over from an older APK simply does not implement it.
     *
     * Paused chunks are read from the capture queues and dropped, so the microphone is never touched
     * and the feeder threads never back up; nothing reaches the encoder, which means paused time is
     * absent from the file exactly as it is on the carrier path.
     */
    void setVoipPaused(boolean paused);

    /**
     * Holds an app-call recording open across a carrier call, or resumes it afterwards.
     *
     * Distinct from {@link #setVoipPaused} in the one way that matters: a pause keeps the
     * microphone, a suspend gives it up. It must — a second voice AudioRecord open during a carrier
     * recording silently drops the user's own side of the phone call, so holding our mic through one
     * would trade a split app recording for a half-broken phone recording.
     *
     * The encoder, muxer and output file stay open throughout, which is what lets the app call
     * continue into the SAME file once the phone call is over.
     */
    void setVoipSuspended(boolean suspended);

    /**
     * Runs one of a fixed set of diagnostic dumps as the shell user and returns its output.
     *
     * This is how the system half of a debug report is collected. It used to be gathered by the app
     * opening its own ADB shell — seven round-trips, each able to reconnect, behind a 45-second
     * budget — which silently produced half a report on any phone whose transport was unhealthy.
     * The daemon is already the shell user and already reachable over this binder, so it needs no
     * Wireless Debugging, no WRITE_SECURE_SETTINGS and no transport at all.
     *
     * {@code key} NAMES a dump; it is never a command. The daemon holds the whitelist, so nothing
     * that reaches this binder can choose what runs. {@code arg} is used only by the logcat-size
     * restore and is refused unless it is a plain buffer size.
     *
     * @return the dump's output, or null if the key is unknown or the dump failed.
     */
    String diagnosticDump(String key, String arg);

    /**
     * What the capture noticed about its own health during the recording that just ended, as a short
     * one-line summary, or {@code ""} when nothing went wrong — which is the normal answer.
     *
     * Read after {@code stopRecording}, like {@code speakerTurns}. It exists because the daemon's own
     * log reaches a bug report only through logcat, which only exists if the reporter happened to have
     * debug logging on BEFORE the call. Issue #28b needs the opposite: a fact about a call that has
     * already happened, in a report from someone who was not debugging at the time.
     */
    String captureDiagnostics();

    /**
     * The APK this recorder process was started from.
     *
     * A {@code daemon(true)} Shizuku service outlives an app update and goes on answering from an APK
     * that no longer exists — until a call needs the scrcpy jar out of it and records nothing. The app
     * compares this with the APK installed now and retires a host that does not match. A host too old
     * to have this method fails the call, which the app reads as the same answer.
     *
     * LAST in the file on purpose: transaction codes are positional, and an older host must keep
     * understanding every method above.
     */
    String hostApkPath();

    /**
     * Whether the given package's call has been answered, from the call timer on its ongoing
     * notification: 1 answered, 0 still ringing, -1 an ongoing notification without a timer (this
     * app never says), -2 no ongoing notification at all (not posted yet, or already gone).
     * Polled by "start when they answer".
     *
     * Appended last: transaction codes are positional, and an older host must keep answering the
     * codes it knows (see RecorderTransactionCodesTest).
     */
    int voipCallAnswered(String packageName);
}
