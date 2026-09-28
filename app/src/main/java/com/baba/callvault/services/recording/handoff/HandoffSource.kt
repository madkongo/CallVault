/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import android.media.AudioFormat
import android.media.AudioRecord
import com.baba.callvault.integrations.scrcpy.androidAudioSourceForKey
import com.baba.callvault.server.BinderDelivery
import com.baba.callvault.server.CaptureAudit
import com.baba.callvault.server.HostAudioRecord
import com.baba.callvault.utils.AppLogger

/**
 * DAEMON-side half of "Resilient recording": creates the privileged capture and hands it to the app.
 *
 * Only the shell-uid daemon can open `VOICE_CALL`, so the `AudioRecord` must be created here. What it
 * hands over is the capture ITSELF, not the audio: the native `IAudioRecord` binder (whose refcount is
 * what keeps the track alive) plus the control-block ashmem fd holding the ring buffer. Once the app
 * holds its own ref to that binder, killing this daemon does not stop the capture — the app keeps
 * draining the ring and encoding locally. That is the entire point of the feature.
 *
 * Counterpart: [HandoffReceiver] in the app process.
 */
object HandoffSource {
    private const val T = "CV:HandoffSource"

    private const val DEFAULT_SAMPLE_RATE = 48_000

    /**
     * Ring size multiplier over the minimum buffer, matching `DirectAudioRecorderSession`. We advance
     * `mFront` manually without the ClientProxy's futex wake, so a ring small enough to fill makes the
     * server stall → periodic micro-gaps (choppy audio). A large ring never fills under our drain.
     */
    private const val BUFFER_FACTOR = 4

    /**
     * The daemon's own reference to the delivered track. Held so the native track cannot be torn down
     * between creation and the app taking its own ref; released by [releaseHeld] at call end.
     */
    @Volatile private var heldRecord: AudioRecord? = null

    /** Ledger id for [heldRecord], so the open and the release name the same capture. */
    @Volatile private var heldAuditId: Int = 0

    /**
     * Creates the privileged capture for [sourceCliKey] and delivers the `IAudioRecord` binder + cblk fd
     * to the app's provider at [authority]. Returns true once the app has been handed the capture.
     *
     * For voice-call we try STEREO first — that reliably yields BOTH directions (uplink on one channel,
     * the remote party's downlink on the other) — and fall back to mono. The ACTUAL channel count and
     * frame count are part of the delivery, so the receiver adapts to whatever the device gave us.
     */
    /**
     * @param startTrack when false the track is handed over **stopped**, for the app to start itself.
     *   That is the Track A shape: capture permission is checked at creation, so a track created once
     *   by the privileged daemon may be run per call by the app with no daemon alive. A track left
     *   *started* across a call boundary is useless — the vendor HAL pins its use-case at
     *   `start_input_stream()` and it stays silent for the whole call (measured), so "held" has to mean
     *   "held stopped".
     */
    fun deliverToApp(
        authority: String,
        sourceCliKey: String,
        sampleRate: Int,
        preferredChannels: Int,
        startTrack: Boolean = true,
    ): Boolean = runCatching {
        // A second delivery for the same call is a mid-call re-arm after the first track was torn
        // down. `heldRecord = record` below would otherwise overwrite the old reference and drop it on
        // the floor — leaking the AudioRecord AND stranding its microphone app-op, which is the exact
        // defect this whole investigation is about. Let go of the old one first, and audit it.
        if (heldRecord != null) {
            AppLogger.i(T, "deliver: releasing the previous held record before re-arming")
            releaseHeld()
        }
        val rate = if (sampleRate > 0) sampleRate else DEFAULT_SAMPLE_RATE
        val source = androidAudioSourceForKey(sourceCliKey)
            ?: run { AppLogger.w(T, "deliver: source '$sourceCliKey' has no direct AudioSource"); return@runCatching false }

        var ar: AudioRecord? = null
        var channelCount = 1
        var minBuffer = 0
        val masks = if (preferredChannels >= 2)
            intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        else intArrayOf(AudioFormat.CHANNEL_IN_MONO)
        for (mask in masks) {
            val ch = if (mask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val mb = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (mb <= 0) continue
            // Through the host's one opener: on vivo the public constructor crashes here, which is why Resilient
            // recording could never start on vivo (2026-09-28).
            val rec = HostAudioRecord.open(
                "handoff (source=$sourceCliKey, ch=$ch)", source, rate, mask, AudioFormat.ENCODING_PCM_16BIT, mb * BUFFER_FACTOR,
            )
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                ar = rec; channelCount = ch; minBuffer = mb; break
            }
            runCatching { rec?.release() }
        }
        val record = ar ?: run {
            AppLogger.w(T, "deliver: $sourceCliKey AudioRecord not initialized (stereo or mono)")
            return@runCatching false
        }
        AppLogger.i(T, "deliver: handoff source=$sourceCliKey capture channels=$channelCount rate=$rate startTrack=$startTrack")
        if (startTrack) record.startRecording()
        else AppLogger.i(T, "deliver: handing the track over STOPPED — the app will start it (Track A)")
        heldRecord = record
        heldAuditId = CaptureAudit.opened("handoff held record (source=$sourceCliKey, ch=$channelCount, rate=$rate)")

        // The native android::AudioRecord* lives in an obfuscation-proof-but-unnamed long field; pick the
        // one that actually points at a native AudioRecord rather than trusting a field name.
        val arPtr = record.javaClass.declaredFields
            .filter { it.type == java.lang.Long.TYPE }
            .mapNotNull { f -> f.isAccessible = true; runCatching { f.getLong(record) }.getOrNull() }
            .firstOrNull { it != 0L && AudioHandoffNative.nativeValidateArPtr(it) }
        if (arPtr == null) { AppLogger.w(T, "deliver: no native AudioRecord ptr"); return@runCatching false }

        val binder = AudioHandoffNative.nativeExtractBinder(arPtr)
            ?: run { AppLogger.w(T, "deliver: extractBinder null"); return@runCatching false }

        // Authoritative ring frame count straight from the AudioRecord: the app-side drain wraps on it,
        // and it also IDENTIFIES the cblk ashmem region (whose byte size varies with the sample rate).
        val frameCount = runCatching { record.bufferSizeInFrames }.getOrDefault(0)
        AppLogger.i(T, "deliver: bufferSizeInFrames=$frameCount rate=$rate ch=$channelCount (minBuf=$minBuffer bytes)")

        val cblkPfd = runCatching {
            val fd = AudioHandoffNative.nativeFindCblkFd(frameCount)
            if (fd >= 0) android.os.ParcelFileDescriptor.adoptFd(fd) else null
        }.getOrNull()
        AppLogger.i(T, "deliver: extracted IAudioRecord ping=${runCatching { binder.pingBinder() }.getOrNull()} cblkFd=${cblkPfd?.fd} — delivering to app")

        val ok = try {
            BinderDelivery.deliverHandoffToApp(binder, authority, cblkPfd, frameCount, rate, channelCount)
        } finally {
            // The app received its own copy of the fd with the (synchronous) delivery. Ours is a dup made
            // by nativeFindCblkFd, and it used to be left for the garbage collector — so after one call the
            // host still held that call's dead control block, same frame count, and the next call's scan
            // could hand the app THAT one: "TRACK INVALIDATED" within milliseconds, 0 bytes, every second
            // call (Xiaomi Redmi Note 10 Pro, Android 13, 2026-09-27). Close it now.
            runCatching { cblkPfd?.close() }
                .onFailure { AppLogger.w(T, "deliver: could not close the host's copy of the cblk fd: ${it.message}") }
        }
        AppLogger.i(T, "deliver: BinderDelivery.deliverHandoffToApp ok=$ok")
        ok
    }.onFailure { AppLogger.e(T, "deliverToApp failed: ${it.message}", it) }.getOrDefault(false)

    /**
     * Releases the daemon's own reference to the handed-off track. The app owns the capture after a
     * successful handoff, so this only frees the daemon's now-redundant hold. Idempotent.
     */
    fun releaseHeld() {
        val rec = heldRecord ?: return
        heldRecord = null
        val id = heldAuditId
        heldAuditId = 0

        // Report what happened, not what was attempted. This used to announce "released" after two
        // runCatching blocks that swallowed everything — so a release that threw produced a log
        // indistinguishable from one that worked, on the single line that decides whether the
        // microphone was let go.
        runCatching { rec.stop() }
            .onFailure { AppLogger.w(T, "releaseHeld: stop() failed: ${it.message}") }
        val outcome = runCatching { rec.release() }
        CaptureAudit.released(id, outcome.exceptionOrNull())
        CaptureAudit.assertNoneLive("after releasing the handoff capture")
    }

    /** Whether [cliKey] can be captured via a direct handoff AudioRecord (else use the daemon path). */
    fun supportsHandoff(cliKey: String): Boolean = androidAudioSourceForKey(cliKey) != null
}
