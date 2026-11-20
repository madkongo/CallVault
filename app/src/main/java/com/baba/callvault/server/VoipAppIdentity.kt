/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger

/**
 * Which app is on the VoIP call, taken from the audio system rather than from notifications.
 *
 * This is ground truth, not a guess: a VoIP app renders the remote voice as a playback track tagged
 * `USAGE_VOICE_COMMUNICATION`, that track is *the very stream this feature records*, and the audio
 * service knows which uid owns it. Asking anything else is asking a proxy.
 *
 * The previous approach — scanning notifications for one tagged `category=call` — was wrong on both
 * ends. Telegram's ongoing-call notification carries **no category at all**, so it never matched; and
 * an unrelated app's notification could match first, which is how a Telegram call was once filed under
 * WhatsApp and later under Google. Nothing here can misidentify the app, because there is exactly one
 * stream being captured and we read its owner.
 *
 * Only the daemon can do this. `getActivePlaybackConfigurations` is **anonymised** for callers without
 * `MODIFY_AUDIO_ROUTING` — the app would see the configurations with their usage and uid stripped, so
 * this same code in the app returns nothing on every device.
 *
 * Returns a uid, deliberately, and lets the app map it to a package: the daemon holds no `Context`, and
 * `PackageManager.getPackagesForUid` handles shared uids and work profiles properly where string
 * matching would not.
 */
internal object VoipAppIdentity {
    /** The shell, by absolute path: a bare "sh" resolves through PATH, which we do not control. */
    private const val SHELL = "/system/bin/sh"

    private const val TAG = "CV:VoipIdentity"

    const val UID_UNKNOWN = -1

    /** `AudioAttributes.USAGE_VOICE_COMMUNICATION`. */
    private const val USAGE_VOICE_COMMUNICATION = 2

    /**
     * `AID_APP_START` — the first uid Android assigns to an installed app. Everything below it is the
     * platform (uid 1000 is `system`).
     */
    private const val APP_UID_START = 10_000

    /**
     * Whether [uid] could be a calling app at all.
     *
     * A VoIP call is always placed by a user-installed app, so a platform uid is never the answer — it
     * means this source is reporting the framework rather than the caller. On One UI the audio mode
     * owner during a WhatsApp call is uid 1000, which resolved to Samsung's "Device maintenance" and
     * put its battery icon on the recording. Rejecting these lets the next source answer instead, and
     * a recording named by time alone beats one named after the wrong app.
     */
    internal fun isAppUid(uid: Int): Boolean = uid >= APP_UID_START

    private const val DUMP_TIMEOUT_MS = 1_500L

    /** Total time to keep looking before giving up, and the gap between attempts. */
    private const val RESOLVE_BUDGET_MS = 1_200L
    private const val RETRY_GAP_MS = 150L

    /**
     * The uid of the app on the VoIP call, or [UID_UNKNOWN].
     *
     * Retries briefly because this is called the moment the audio mode changes, and an app does not
     * necessarily have its playback track running yet at that instant — the first attempt legitimately
     * finds nothing. The budget is small: it delays the start of capture, and the audio being recorded
     * is already flowing.
     *
     * Three sources, strongest first. The **mode owner** is the app that put the device into
     * communication mode, set at the same instant as the mode change we react to, so it is the one
     * source that cannot lose the race.
     */
    fun currentVoiceCommUid(skip: Set<Int> = emptySet()): Int {
        val deadline = System.currentTimeMillis() + RESOLVE_BUDGET_MS
        var attempt = 0
        while (true) {
            attempt++
            val uid = resolveOnce(skip)
            if (uid != UID_UNKNOWN) {
                AppLogger.i(TAG, "VoIP app resolved to uid $uid on attempt $attempt" + if (skip.isEmpty()) "" else " (skipping $skip)")
                return uid
            }
            if (System.currentTimeMillis() >= deadline) {
                AppLogger.w(TAG, "No VoIP app uid after $attempt attempts")
                return UID_UNKNOWN
            }
            runCatching { Thread.sleep(RETRY_GAP_MS) }.onFailure { return UID_UNKNOWN }
        }
    }

    /**
     * One pass over every source.
     *
     * Order matters and was settled on-device: during a Telegram call the playback track did not exist
     * yet when the mode changed (measured: mode owner present, no track), while during a Signal call it
     * did. The mode owner is checked whenever the structured lookup comes up empty precisely because it
     * is the one source that is always already there.
     */
    private fun resolveOnce(skip: Set<Int>): Int {
        val viaBinder = runCatching { uidFromPlaybackConfigurations(skip) }
            .onFailure { AppLogger.d(TAG, "Playback-configuration lookup unavailable: ${it.message}") }
            .getOrDefault(UID_UNKNOWN)
        if (viaBinder != UID_UNKNOWN) return viaBinder

        val dump = runCatching { readAudioDump() }.getOrNull().orEmpty()
        if (dump.isEmpty()) return UID_UNKNOWN

        // A skipped mode owner must not hide the playback track behind it.
        val owner = parseModeOwnerUid(dump, skip)
        if (owner != UID_UNKNOWN) {
            AppLogger.i(TAG, "VoIP app uid $owner (mode owner in dump)")
            return owner
        }
        return parseVoiceCommUid(dump, skip)
    }

    /**
     * Reads the live `AudioPlaybackConfiguration` list straight from the audio service.
     *
     * All reflection: the configurations are a hidden API, as is the uid on them. Any missing piece
     * throws and the caller falls back to the dump.
     */
    private fun uidFromPlaybackConfigurations(skip: Set<Int>): Int {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java).invoke(null, "audio")
            ?: return UID_UNKNOWN

        val stub = Class.forName("android.media.IAudioService\$Stub")
        val audioService = stub.getMethod("asInterface", Class.forName("android.os.IBinder"))
            .invoke(null, binder) ?: return UID_UNKNOWN

        val configurations = audioService.javaClass
            .getMethod("getActivePlaybackConfigurations")
            .invoke(audioService) as? List<*> ?: return UID_UNKNOWN

        for (config in configurations.filterNotNull()) {
            if (usageOf(config) != USAGE_VOICE_COMMUNICATION) continue
            if (!isActive(config)) continue
            val uid = config.javaClass.getMethod("getClientUid").invoke(config) as? Int ?: continue
            if (!isAppUid(uid)) {
                AppLogger.d(TAG, "Ignoring platform uid $uid from playback configuration")
                continue
            }
            if (uid in skip) {
                AppLogger.d(TAG, "Skipping uid $uid from playback configuration: not a calling app")
                continue
            }
            AppLogger.i(TAG, "VoIP app uid $uid (playback configuration)")
            return uid
        }
        return UID_UNKNOWN
    }

    private fun usageOf(config: Any): Int? = runCatching {
        val attributes = config.javaClass.getMethod("getAudioAttributes").invoke(config)
        attributes?.javaClass?.getMethod("getUsage")?.invoke(attributes) as? Int
    }.getOrNull()

    /**
     * Whether the track is playing rather than merely registered. Treated as active when the hidden
     * accessor is absent, since a configuration matching the call usage is the one we are recording.
     */
    private fun isActive(config: Any): Boolean = runCatching {
        config.javaClass.getMethod("isActive").invoke(config) as? Boolean ?: true
    }.getOrDefault(true)

    /**
     * Pulls the uid out of a `dumpsys audio` players section.
     *
     * The line carries the fields as `u/pid:<uid>/<pid> state:started ... usage=USAGE_VOICE_COMMUNICATION`.
     * Only lines with all three are considered, so an idle or media player can never be mistaken for a
     * call. Split out from the I/O so it can be tested without a device.
     */
    internal fun parseVoiceCommUid(dump: String, skip: Set<Int> = emptySet()): Int {
        for (line in dump.lineSequence()) {
            if (!line.contains("AudioPlaybackConfiguration")) continue
            if (!line.contains("usage=USAGE_VOICE_COMMUNICATION")) continue
            if (!line.contains("state:started")) continue
            val uid = UID_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (!isAppUid(uid) || uid in skip) continue
            AppLogger.i(TAG, "VoIP app uid $uid (playback track in dump)")
            return uid
        }
        return UID_UNKNOWN
    }

    private val UID_REGEX = Regex("""u/pid:(\d+)/\d+""")

    /**
     * Pulls the uid out of the audio mode owner line:
     * `mAudioModeOwner: AudioModeInfo: mMode=MODE_IN_COMMUNICATION, mPid=1234, mUid=10304`.
     *
     * This is the app that requested communication mode — the VoIP app — and it is recorded at the same
     * moment as the mode change this feature detects, so unlike a playback track it is always already
     * there. Only `MODE_IN_COMMUNICATION` is accepted: `MODE_IN_CALL` is a carrier call, which this
     * feature does not handle, and a stale owner in `MODE_NORMAL` means no call at all.
     */
    internal fun parseModeOwnerUid(dump: String, skip: Set<Int> = emptySet()): Int {
        for (line in dump.lineSequence()) {
            if (!line.contains("mAudioModeOwner")) continue
            if (!line.contains("mMode=MODE_IN_COMMUNICATION")) continue
            val uid = MODE_OWNER_UID_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            // mUid=0 is the "nobody owns the mode" placeholder; a platform uid means the framework owns
            // the mode rather than the calling app, which is the normal case on One UI.
            if (!isAppUid(uid) || uid in skip) continue
            return uid
        }
        return UID_UNKNOWN
    }

    private val MODE_OWNER_UID_REGEX = Regex("""mUid=(\d+)""")

    internal fun readAudioDump(): String? {
        // Absolute path, not "sh": a bare name resolves through PATH, and the daemon should not
        // depend on an environment it does not own. Safe to hard-code — the caller already treats a
        // failure to start as "owner unknown", so a device without it behaves as one without a
        // usable `sh` does today.
        val process = ProcessBuilder(SHELL, "-c", "dumpsys audio").redirectErrorStream(true).start()
        return try {
            val text = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(DUMP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroy()
            }
            text.takeIf { it.isNotBlank() }
        } finally {
            runCatching { process.destroy() }
        }
    }
}
