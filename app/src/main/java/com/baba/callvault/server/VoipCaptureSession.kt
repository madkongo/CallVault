/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.server.speakers.SpeakerTurnCodec
import com.baba.callvault.server.speakers.SpeakerTurnDetector
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.PcmDownmix
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records BOTH sides of a VoIP call (WhatsApp / Signal / Telegram / …), which the telephony capture
 * paths cannot reach because there is no carrier call involved.
 *
 * Two independent streams are combined:
 *  - **far party** — [VoipAudioPolicy]'s loopback-render sink, a duplicate of the remote voice the app
 *    is rendering. The policy must already be armed (see that class); the sink may be created after
 *    the call has started.
 *  - **near party** — a plain `MIC` capture. The source matters: `VOICE_COMMUNICATION` is zero-filled
 *    for the whole call, because the in-call policy silences a record client that cannot bypass it and
 *    that source is the very one the VoIP app itself holds. `MIC` is not silenced, and the VoIP app
 *    keeps working alongside it.
 *
 * The two are interleaved to stereo — LEFT near, RIGHT far — and then downmixed to mono for the
 * encoder, matching what [DirectAudioRecorderSession] does for a stereo carrier capture, so recordings
 * from both paths sound alike.
 *
 * Because the streams are separate free-running `AudioRecord`s, the muxer pairs them by the real
 * time their audio was captured ([SlotPairer]): the file is a run of 20 ms slots, each side fills
 * the slot its chunk belongs to, silence fills a gap, and neither side can push the other along.
 * Until 2.4.2 it paired BY ARRIVAL — one chunk from each queue per loop — which consumed the far
 * queue at the near side's pace; on a Galaxy S21 Ultra where the mic was re-taken 155 times in two
 * minutes (issue #41) the far party ended 6.4 s late. [VoipSyncLedger] measures the offset and
 * counts every loss (docs/dev-notes/2026-09-22-voip-sync-instrumentation.md).
 */
internal class VoipCaptureSession(
    private val codec: ScrcpyAudioCodec,
    private val bitRate: Int,
    /** The daemon's received fd copy. The muxer writes through it; [stop] closes it after finalising. */
    private val outFd: ParcelFileDescriptor,
) : RecordingSession {

    private val stopRequested = AtomicBoolean(false)

    /**
     * Whether the FAR party was ever actually audible.
     *
     * An app can opt out of being captured (`ALLOW_CAPTURE_BY_NONE` sets a flag checked before any
     * permission and bypassable by nothing), and some OEM builds simply do not attach the call to our
     * mix. Both look identical from here: the mix delivers perfect digital silence while the near side
     * records normally — so the user would get a one-sided recording with no explanation.
     *
     * Reading the app's capture policy directly is not available to us: it needs an AudioManager, which
     * needs a Context, and obtaining one in this process poisons the attribution the capture depends on
     * (see VoipAudioPolicy). So judge the OUTCOME instead — which also catches the OEM cases a policy
     * read would miss.
     */
    @Volatile var farPartyHeard: Boolean = false
        private set

    /** Speaker turns for this capture, encoded; empty until the loop finishes. */
    @Volatile private var speakerTurnsEncoded: String = ""

    override fun speakerTurns(): String = speakerTurnsEncoded

    /** The sync summary of the finished capture, for the app's log; empty until the loop ends. */
    @Volatile private var syncSummary: String = ""

    override fun captureDiagnostics(): String = syncSummary

    private val ledger = VoipSyncLedger(SAMPLE_RATE, CHUNK_FRAMES)

    /** The monotonic moment the two records were started; the wall clock of every sync line. */
    @Volatile private var startedNanos = 0L

    /** A chunk of one side with the real time its audio was captured (see [VoipSyncLedger]). */
    private class Chunk(val bytes: ByteArray, val contentNanos: Long)

    @Volatile private var farRecord: AudioRecord? = null

    /** Whether [recordStatus] has been logged for this call. */
    private var recordStatusLogged = false
    @Volatile private var nearRecord: AudioRecord? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var muxer: MediaMuxer? = null
    @Volatile private var muxThread: Thread? = null

    override fun start() {
        try {
            startInternal()
        } catch (t: Throwable) {
            // Release our own resources but do NOT close outFd — the caller still owns it.
            cleanupPartial()
            throw t
        }
    }

    private fun startInternal() {
        val far = VoipAudioPolicy.createSink()
            ?: throw IllegalStateException("VoIP far-party sink unavailable (policy not armed?)")
        farRecord = far
        // Audited like the near capture. This was missed when the ledger was added, so half of what a
        // VoIP call opens was invisible to it: stop() released `farAuditId` without anything ever
        // having opened it, and assertNoneLive could report "no capture left open" while the far
        // record was still held.
        farAuditId = CaptureAudit.opened("VoIP far capture (policy submix)")

        val near = newNearRecord() ?: throw IllegalStateException("VoIP mic capture failed to initialise")
        nearRecord = near
        nearAuditId = CaptureAudit.opened("VoIP near capture (MIC)")

        val mime = when (codec) {
            ScrcpyAudioCodec.OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
            ScrcpyAudioCodec.AAC -> MediaFormat.MIMETYPE_AUDIO_AAC
        }
        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, ENCODE_CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val enc = MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        encoder = enc

        // Muxer last: everything riskier has succeeded, so the output fd is only consumed now.
        val mux = MediaMuxer(outFd.fileDescriptor, codec.outputFormat)
        muxer = mux

        enc.start()
        startedNanos = System.nanoTime()
        far.startRecording()
        near.startRecording()
        AppLogger.i(TAG, "VoIP capture started: codec=${codec.cliKey} rate=$SAMPLE_RATE bitRate=$bitRate")
        AppLogger.i(
            TAG,
            "VoIP sync start: near buf=${near.bufferSizeInFrames} frames far buf=${far.bufferSizeInFrames} frames " +
                "chunk=$CHUNK_FRAMES frames grace=${SlotPairer.GRACE_NANOS / 1_000_000L}ms queue=$QUEUE_CHUNKS; " +
                "sdk=${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        )

        muxThread = Thread {
            runCatching { captureLoop(enc, mux) }
                .onFailure { AppLogger.w(TAG, "VoIP capture loop ended: ${it.message}") }
        }.apply { isDaemon = true; name = "voip-capture" }.also { it.start() }
    }

    /**
     * Pairs one chunk from each direction, downmixes to mono and drives the encoder. Each side is read
     * on its own thread so a slow or silent one cannot stall the other.
     */
    /** A fresh MIC record, or null if it will not initialise. Shared by start and resume. */
    @Suppress("MissingPermission")
    private fun newNearRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            AppLogger.w(TAG, "VoIP mic minBufferSize=$minBuf")
            return null
        }
        // Through the host's one AudioRecord opener: on vivo the public constructor crashes in this process,
        // and swallowing that is how app calls kept failing on 2.4.3 with no reason in the log.
        val rec = HostAudioRecord.open(
            "VoIP mic", MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * BUFFER_FACTOR,
        )
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }
            return null
        }
        return rec
    }

    /** Set from the app while a recording is in flight; see [captureLoop] for what it does. */
    private val pauseRequested = AtomicBoolean(false)

    /**
     * Set while the recording is held open across a carrier call.
     *
     * Different from [pauseRequested] in the one way that matters: a pause keeps the microphone, a
     * suspend gives it up. It has to. A second voice AudioRecord open during a carrier recording
     * silently drops the user's own side of the phone call — proven by A/B test, invisible in the
     * logs and in the waveform — so holding our mic through a carrier call would trade a split app
     * recording for a half-broken phone recording.
     *
     * The encoder, muxer and output file stay open throughout, which is what lets the app call
     * continue into the SAME file when the phone call is over.
     */
    private val suspended = AtomicBoolean(false)

    /** How long a feeder waits between checks while the capture is suspended. */
    private val SUSPEND_POLL_MS = 100L

    /**
     * Releases or re-acquires the capture, keeping the output file open either way.
     *
     * Re-acquiring is not new machinery: [retakeMic] already replaces a live MIC record mid-recording
     * on One UI and has been measured recovering audio a platform silencing had lost. This uses the
     * same move for a different reason.
     */
    fun setSuspended(on: Boolean) {
        if (!suspended.compareAndSet(!on, on)) return
        if (on) {
            AppLogger.i(TAG, "VoIP capture suspended for a carrier call — releasing the mic, keeping the file")
            val near = nearRecord
            nearRecord = null
            runCatching { near?.stop() }
            runCatching { near?.release() }
            if (near != null) {
                CaptureAudit.released(nearAuditId)
                // Zeroed, or stop() releases this same id a second time and the audit logs a
                // misleading "already released, or never registered" line — in the exact record a
                // stuck-microphone report is read from.
                nearAuditId = 0
            }
            val far = farRecord
            farRecord = null
            runCatching { far?.stop() }
            runCatching { far?.release() }
        } else {
            // The submix first: it is the one that can fail for a reason worth logging distinctly —
            // the policy is unarmed — while the mic failing is almost always another app holding it.
            val far = VoipAudioPolicy.createSink()
            if (far == null) {
                AppLogger.w(TAG, "Could not re-open the far-party sink on resume; the rest of this call is near-side only")
            } else {
                farRecord = far
                runCatching { far.startRecording() }
            }
            val near = newNearRecord()
            if (near == null) {
                AppLogger.w(TAG, "Could not re-take the mic on resume; the rest of this call is far-side only")
            } else {
                runCatching { near.startRecording() }
                nearRecord = near
                nearAuditId = CaptureAudit.opened("VoIP near capture (MIC, resumed)")
            }
            AppLogger.i(TAG, "VoIP capture resumed into the same file (near=${near != null} far=${far != null})")
        }
    }

    /** Pauses or resumes the encode. Safe to call when nothing is recording; it simply arms the flag. */
    fun setPaused(paused: Boolean) {
        pauseRequested.set(paused)
        AppLogger.i(TAG, "VoIP capture ${if (paused) "paused" else "resumed"} by the user")
    }

    private fun captureLoop(enc: MediaCodec, mux: MediaMuxer) {
        val qNear: BlockingQueue<Chunk> = ArrayBlockingQueue(QUEUE_CHUNKS)
        val qFar: BlockingQueue<Chunk> = ArrayBlockingQueue(QUEUE_CHUNKS)
        val readers = listOf(feeder(qNear, ledger.near), feeder(qFar, ledger.far))
        readers.forEach { it.start() }
        var nearPeak = 0
        var nextSnapshotFrames = SNAPSHOT_FRAMES

        val silence = ByteArray(CHUNK_BYTES)
        val stereo = ByteArray(CHUNK_BYTES * 2)
        // Speaker turns, from the same interleaved buffer the encoder's downmix is about to flatten.
        // This path built L=near/R=far all along and then threw the separation away — so app calls
        // arrived with no speaker labels at all, while carrier calls had them, for no reason anyone
        // had decided. Here the two sides are known exactly rather than inferred: near is the user.
        val speakers = SpeakerTurnDetector(SAMPLE_RATE)
        val mono = ByteArray(CHUNK_BYTES)
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var totalFrames = 0L
        var substituted = 0L
        // The file is a run of slots of real time — see SlotPairer for why arrival order is not.
        val pairer = SlotPairer(CHUNK_NANOS)
        var slotNanos = -1L

        try {
            while (!stopRequested.get()) {
                if (slotNanos < 0) {
                    // Anchor on the earlier first capture, waiting briefly for the other side so a
                    // slow start is padded at the head rather than the file starting without it.
                    val n = qNear.peek()
                    val f = qFar.peek()
                    val waited = System.nanoTime() - startedNanos
                    if ((n == null || f == null) && waited < ANCHOR_WAIT_NANOS) { Thread.sleep(SLOT_POLL_MS); continue }
                    if (n == null && f == null) { Thread.sleep(SLOT_POLL_MS); continue }
                    slotNanos = pairer.anchor(n?.contentNanos, f?.contentNanos)
                    AppLogger.i(TAG, "VoIP sync: file anchored ${(slotNanos - startedNanos) / 1_000_000L}ms after start (near=${n != null} far=${f != null})")
                }
                // Not before the slot's audio has had time to be read; a burst of due slots after
                // an encoder stall is written back to back, so the file never falls behind for long.
                if (!pairer.due(slotNanos, System.nanoTime())) { Thread.sleep(SLOT_POLL_MS); continue }
                val nearChunk = takeForSlot(qNear, slotNanos, pairer, ledger.near)
                val farChunk = takeForSlot(qFar, slotNanos, pairer, ledger.far)
                slotNanos += CHUNK_NANOS
                val n = nearChunk?.bytes ?: silence.also { substituted++ }
                val f = farChunk?.bytes ?: silence
                ledger.paired(nearChunk?.contentNanos, farChunk?.contentNanos)
                // Paused: the chunks were read above and are now dropped. The AudioRecords and the
                // feeder threads are untouched — capture itself does not change shape when a user
                // pauses — but nothing reaches the encoder, the speaker detector or the frame count,
                // so the paused stretch is simply absent from the file. Same result as the carrier
                // path, reached without going anywhere near the microphone.
                if (pauseRequested.get() || suspended.get()) continue
                var o = 0
                var farPeak = 0
                for (i in 0 until CHUNK_BYTES step 2) {
                    stereo[o] = n[i]; stereo[o + 1] = n[i + 1]          // L = near
                    stereo[o + 2] = f[i]; stereo[o + 3] = f[i + 1]      // R = far
                    val fs = ((f[i].toInt() and 0xFF) or (f[i + 1].toInt() shl 8)).toShort().toInt()
                    val fa = if (fs < 0) -fs else fs
                    if (fa > farPeak) farPeak = fa
                    val ns = ((n[i].toInt() and 0xFF) or (n[i + 1].toInt() shl 8)).toShort().toInt()
                    val na = if (ns < 0) -ns else ns
                    if (na > nearPeak) nearPeak = na
                    o += 4
                }
                // A silenced mix is EXACTLY zero, so any real signal clears the threshold easily; the
                // margin only guards against dither.
                if (!farPartyHeard && farPeak > FAR_SILENCE_THRESHOLD) farPartyHeard = true
                // Mono for the encoder, so the whole bitrate goes to one channel — the same reasoning
                // as the carrier path, where encoding stereo starved the far party.
                // Before the downmix, which is the only moment the two directions still exist
                // separately. Never allowed to break the recording: a diagnostic that costs a call
                // would be a bad trade.
                runCatching { speakers.accept(stereo, stereo.size) }
                    .onFailure { AppLogger.w(TAG, "Speaker turn detection failed: ${it.message}") }

                val len = PcmDownmix.stereoToMono(stereo, stereo.size, mono)

                var inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                while (inIdx < 0 && !stopRequested.get()) {
                    muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
                    inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                }
                if (inIdx < 0) break
                enc.getInputBuffer(inIdx)!!.apply { clear(); put(mono, 0, len) }
                enc.queueInputBuffer(inIdx, 0, len, totalFrames * 1_000_000L / SAMPLE_RATE, 0)
                totalFrames += len / (2 * ENCODE_CHANNELS)
                muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
                if (totalFrames >= nextSnapshotFrames) {
                    nextSnapshotFrames += SNAPSHOT_FRAMES
                    // The peaks say whether a side was silent while the counters say it was late;
                    // an all-zero near side with no re-take is a silencing the re-take missed.
                    AppLogger.i(
                        TAG,
                        ledger.snapshot(totalFrames, System.nanoTime() - startedNanos, qNear.size, qFar.size) +
                            " peak near=$nearPeak far=$farPeak",
                    )
                    nearPeak = 0; farPeak = 0
                    // Once per call, at the first snapshot: what Android itself says about each record —
                    // silenced by the platform, and the device it is really routed to. A far side that reads
                    // pure zeros while "not silenced" and routed to the right submix is being zeroed below the
                    // framework (vivo V2507A, 2026-09-28).
                    if (!recordStatusLogged) {
                        recordStatusLogged = true
                        AppLogger.i(TAG, "VoIP record status: far{${recordStatus(farRecord)}} near{${recordStatus(nearRecord)}}")
                        AppLogger.i(TAG, "VoIP vendor audio lists: ${vendorAudioLists()}")
                    }
                }
            }

            val inIdx = enc.dequeueInputBuffer(END_OF_STREAM_TIMEOUT_US)
            if (inIdx >= 0) enc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(enc, mux, info, muxerStarted, drainToEos = true)
            AppLogger.i(TAG, "VoIP capture finished: ${totalFrames / SAMPLE_RATE}s, $substituted silence-filled chunks, farPartyHeard=$farPartyHeard")
            syncSummary = ledger.summary(totalFrames, System.nanoTime() - startedNanos)
            AppLogger.i(TAG, syncSummary)
            if (!farPartyHeard) {
                AppLogger.w(TAG, "Far party was never audible — this app blocks capture, or the OEM did not attach the call to our mix")
            }
        } finally {
            readers.forEach { it.interrupt() }
            // In the finally, so a capture that ends by exception still yields whatever turns it saw.
            runCatching { speakerTurnsEncoded = SpeakerTurnCodec.encode(speakers.finish()) }
                .onFailure { AppLogger.w(TAG, "Could not encode VoIP speaker turns: ${it.message}") }
        }
    }

    /**
     * The chunk of [q] that belongs to the slot at [slotNanos], or null for silence there. Chunks
     * older than the slot are discarded on the way: their moment has already been written.
     */
    private fun takeForSlot(q: BlockingQueue<Chunk>, slotNanos: Long, pairer: SlotPairer, side: VoipSyncLedger.Side): Chunk? {
        while (true) {
            when (pairer.classify(slotNanos, q.peek()?.contentNanos)) {
                SlotPairer.Take.TAKE -> return q.poll()
                SlotPairer.Take.SILENCE -> { side.substituted(); return null }
                SlotPairer.Take.DISCARD -> { q.poll(); side.discarded() }
            }
        }
    }

    /**
     * Reads whole chunks from one direction into its queue; drops rather than blocks if the muxer lags.
     *
     * Each chunk is stamped with the real time its audio was captured: the HAL's own fix
     * (`getTimestamp`, refreshed every [TIMESTAMP_EVERY_CHUNKS]) applied to the chunk's frame index,
     * or the read moment where the device gives no fix. That stamp is what [VoipSyncLedger] measures
     * the two sides' offset from.
     */
    private fun feeder(q: BlockingQueue<Chunk>, side: VoipSyncLedger.Side) = Thread {
        val tag = side.name
        val buf = ByteArray(CHUNK_BYTES)
        var silentChunks = 0
        var current: AudioRecord? = null
        var frameIndex = 0L
        var firstReadLogged = false
        val ts = android.media.AudioTimestamp()
        while (!stopRequested.get()) {
            // Read the record from the field on every pass rather than holding the one we started
            // with. A suspend releases it and a resume installs a different one, and a thread
            // clutching the original would be reading a released object.
            val record = if (tag == "near") nearRecord else farRecord
            if (record !== current) {
                // A fresh record numbers its frames from zero and carries its own HAL clock.
                current = record
                frameIndex = 0L
                side.newRecord()
            }
            if (record == null || suspended.get()) {
                // Waiting, not dying. The old feeder ended itself the moment a read failed, which is
                // right for a broken capture and fatal for a suspended one — the thread would be gone
                // by the time the phone call ended and there would be nothing left to resume into.
                try { Thread.sleep(SUSPEND_POLL_MS) } catch (e: InterruptedException) { return@Thread }
                continue
            }
            var off = 0
            var interrupted = false
            while (off < CHUNK_BYTES && !stopRequested.get()) {
                val r = runCatching { record.read(buf, off, CHUNK_BYTES - off) }.getOrDefault(-1)
                if (r <= 0) {
                    // A failed read while suspending is the record being released underneath us, not
                    // a fault. Go back to waiting instead of ending the thread for good.
                    if (suspended.get() || stopRequested.get()) { interrupted = true; break }
                    AppLogger.d(TAG, "$tag read=$r, feeder ending")
                    return@Thread
                }
                off += r
            }
            if (interrupted) continue
            val readAt = System.nanoTime()
            if (!firstReadLogged) {
                firstReadLogged = true
                AppLogger.i(TAG, "VoIP sync: first $tag chunk ${(readAt - startedNanos) / 1_000_000L}ms after start")
            }
            if (side.needsTimestamp || side.chunksRead % TIMESTAMP_EVERY_CHUNKS == 0L) {
                val ok = runCatching { record.getTimestamp(ts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) }
                    .getOrDefault(AudioRecord.ERROR)
                if (ok == AudioRecord.SUCCESS) side.timestamp(ts.framePosition, ts.nanoTime)
            }
            val contentNanos = side.contentNanos(frameIndex, readAt)
            frameIndex += CHUNK_FRAMES
            side.read()
            // Re-take the mic when the platform has silenced us.
            //
            // On One UI only one client gets the mic, and the most recent starter wins: when the VoIP
            // app restarts ITS capture mid-call, ours is silenced — it keeps delivering chunks, but
            // they are digital zeros, so nothing else notices. Measured on a Galaxy S24 FE: ~6 s of a
            // 21 s call lost, matching a flat -107 dB stretch in the output.
            //
            // Restarting our AudioRecord makes US the most recent starter, and the audio comes back.
            // Verified safe for the call itself: with the phones in separate rooms the far end still
            // heard everything while our capture held the mic, so the VoIP app keeps transmitting
            // regardless of what the arbitration reports. Only the NEAR source needs this — the far
            // party arrives through the policy submix, outside the mic arbitration entirely.
            if (tag == "near") {
                if (isAllZero(buf)) { silentChunks++; side.zeroChunk() } else silentChunks = 0
                if (silentChunks >= SILENT_CHUNKS_BEFORE_RETAKE) {
                    silentChunks = 0
                    // Asked BEFORE the re-take, of the record that went quiet: did Android silence it, or is
                    // this a pause? Recorded only (backlog #9, step 1) — the re-take happens either way, as
                    // before. Null when the platform will not say.
                    val platformSilenced = runCatching { record.activeRecordingConfiguration?.isClientSilenced }.getOrNull()
                    val retakeStart = System.nanoTime()
                    val fresh = retakeMic(record)
                    if (fresh != null) {
                        // The gap is what the near side loses: from the last chunk of the old record
                        // to the fresh one being started. Its first read adds one buffer on top.
                        val gap = System.nanoTime() - retakeStart
                        side.retake(gap, platformSilenced)
                        AppLogger.i(TAG, "VoIP sync: re-take #${side.retakes} at ${(retakeStart - startedNanos) / 1_000_000L}ms took ${gap / 1_000_000L}ms; ${side.zeroChunks} zero chunks so far; Android says silenced=$platformSilenced")
                        // The platform took the mic away and we opened another. Without closing the
                        // old id and opening a new one, every re-take would read as a leaked capture
                        // — and this happens several times in a normal call.
                        CaptureAudit.released(nearAuditId)
                        // Only the field now; the loop re-reads it next pass. Keeping a local copy
                        // in step was the old shape and would now be a second source of truth.
                        nearRecord = fresh
                        nearAuditId = CaptureAudit.opened("VoIP near capture (MIC, re-taken)")
                    }
                }
            }
            if (!q.offer(Chunk(buf.copyOf(), contentNanos))) side.dropped()
        }
    }.apply { isDaemon = true; name = "voip-${side.name}" }

    /**
     * The allow-lists vivo's audioserver consults before zero-filling a remote-submix record during a call (keys found
     * in its binary, 2026-09-28), read with getParameters — read-only; nothing here sets anything. Empty on other
     * ROMs, which do not answer these keys.
     */
    private fun vendorAudioLists(): String = runCatching {
        val get = Class.forName("android.media.AudioSystem").getMethod("getParameters", String::class.java)
        listOf("LiveAppList", "RemoteProtectList", "RemoteSubmixSupp", "APPShare").joinToString(" ") { key ->
            "$key=[${(get.invoke(null, key) as String?).orEmpty().take(VENDOR_LIST_MAX_CHARS)}]"
        }
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }

    /** Android's own account of a record: silenced or not, routed device, source, session. Diagnostics only. */
    private fun recordStatus(record: AudioRecord?): String = runCatching {
        if (record == null) return@runCatching "none"
        val cfg = record.activeRecordingConfiguration
        val dev = record.routedDevice
        "silenced=${cfg?.isClientSilenced} routed=${dev?.type}:${dev?.address} source=${cfg?.clientAudioSource} " +
            "session=${record.audioSessionId} state=${record.recordingState}"
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }

    /** True when every sample in the chunk is exactly zero — the fingerprint of a silenced capture. */
    private fun isAllZero(buf: ByteArray): Boolean {
        for (b in buf) if (b.toInt() != 0) return false
        return true
    }

    /**
     * Closes the silenced capture and opens a fresh one, which makes us the most recent starter.
     * Returns null (and leaves the old one running) if the new capture cannot be opened, so a failure
     * here degrades to today's behaviour rather than ending the recording.
     */
    private fun retakeMic(current: AudioRecord): AudioRecord? {
        // "Went quiet", not "silenced": most re-takes follow a pause, not the platform (2026-09-23 note);
        // the caller logs Android's own answer.
        AppLogger.i(TAG, "near capture went quiet for ${SILENT_CHUNKS_BEFORE_RETAKE} chunks — re-taking the mic")
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val fresh = HostAudioRecord.open(
            "VoIP mic re-take", MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * BUFFER_FACTOR,
        )
        if (fresh == null || fresh.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.w(TAG, "re-take failed to initialise; keeping the silenced capture")
            runCatching { fresh?.release() }
            return null
        }
        runCatching { fresh.startRecording() }
        runCatching { current.stop() }
        runCatching { current.release() }
        return fresh
    }

    /** Drains available encoder output into the muxer. Returns whether the muxer is (now) started. */
    private fun drainEncoder(
        enc: MediaCodec, mux: MediaMuxer, info: MediaCodec.BufferInfo,
        muxerStartedIn: Boolean, drainToEos: Boolean = false,
    ): Boolean {
        var muxerStarted = muxerStartedIn
        var track = if (muxerStarted) 0 else -1
        while (true) {
            val outIdx = enc.dequeueOutputBuffer(info, if (drainToEos) END_OF_STREAM_TIMEOUT_US else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = mux.addTrack(enc.outputFormat)   // carries the codec-specific data
                    mux.start()
                    muxerStarted = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (drainToEos) continue else return muxerStarted
                outIdx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(outIdx)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        mux.writeSampleData(track, outBuf, info)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return muxerStarted
                }
            }
        }
    }

    /** Ledger ids for the two microphones this session holds. */
    @Volatile private var nearAuditId: Int = 0
    @Volatile private var farAuditId: Int = 0

    override fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        runCatching { muxThread?.join(STOP_JOIN_MS) }
        runCatching { farRecord?.stop() }
        CaptureAudit.released(farAuditId, runCatching { farRecord?.release() }.exceptionOrNull())
        runCatching { nearRecord?.stop() }
        CaptureAudit.released(nearAuditId, runCatching { nearRecord?.release() }.exceptionOrNull())
        farAuditId = 0; nearAuditId = 0
        runCatching { encoder?.stop() }; runCatching { encoder?.release() }
        runCatching { muxer?.stop() }    // writes the trailer — without it the file won't play
        runCatching { muxer?.release() }
        runCatching { outFd.close() }
        farRecord = null; nearRecord = null; encoder = null; muxer = null
        AppLogger.i(TAG, "VoIP capture stopped")
        CaptureAudit.assertNoneLive("after stopping VoIP capture")
    }

    /**
     * Releases what start() managed to build, leaving [outFd] open for the caller.
     *
     * Stops before releasing, and closes the ledger entries, for the same reason [stop] does. Without
     * the ledger part a start that threw after opening a capture left that id open forever, so the
     * process reported a microphone it was no longer holding — a false alarm in the one report meant
     * to answer whether the microphone is stuck.
     */
    private fun cleanupPartial() {
        runCatching { farRecord?.stop() }
        runCatching { farRecord?.release() }
        if (farAuditId != 0) { CaptureAudit.released(farAuditId); farAuditId = 0 }
        runCatching { nearRecord?.stop() }
        runCatching { nearRecord?.release() }
        if (nearAuditId != 0) { CaptureAudit.released(nearAuditId); nearAuditId = 0 }
        runCatching { encoder?.release() }
        runCatching { muxer?.release() }
        farRecord = null; nearRecord = null; encoder = null; muxer = null
    }

    companion object {
        /** Cap on each vendor audio list in the log; the value is for reading, not for completeness. */
        private const val VENDOR_LIST_MAX_CHARS = 300

        /**
         * Consecutive all-zero chunks before we conclude the platform has silenced us rather than the
         * room simply being quiet. A real mic never returns exact zeros — even silence carries a noise
         * floor — so this only needs to outlast a codec-aligned run of them, not real quiet.
         */
        private const val SILENT_CHUNKS_BEFORE_RETAKE = 15

        private const val TAG = "CV:VoipCapture"
        private const val SAMPLE_RATE = VoipAudioPolicy.SAMPLE_RATE
        private const val ENCODE_CHANNELS = 1
        private const val BUFFER_FACTOR = 4
        private const val CHUNK_FRAMES = 960                 // 20 ms at 48 kHz
        private const val CHUNK_BYTES = CHUNK_FRAMES * 2     // mono PCM-16
        private const val QUEUE_CHUNKS = 400                 // ~8 s of slack per direction
        private const val CHUNK_NANOS = CHUNK_FRAMES * 1_000_000_000L / SAMPLE_RATE
        /** How long the mux thread sleeps between checks of the slot clock. */
        private const val SLOT_POLL_MS = 5L
        /** How long the file waits for the second side's first chunk before anchoring on the first alone. */
        private const val ANCHOR_WAIT_NANOS = 1_000_000_000L
        /** A sync snapshot every this many file frames — 10 s; 60 lines an hour in the ring. */
        private const val SNAPSHOT_FRAMES = SAMPLE_RATE * 10L
        /** How often a side refreshes its HAL time fix; the fix drifts by nothing in a second. */
        private const val TIMESTAMP_EVERY_CHUNKS = 50L
        private const val MAX_INPUT_SIZE = 16_384
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val END_OF_STREAM_TIMEOUT_US = 100_000L
        private const val STOP_JOIN_MS = 3_000L
        private const val FAR_SILENCE_THRESHOLD = 100

        /** True if this device can encode the chosen codec; the policy tap is checked when arming. */
        fun supports(codec: ScrcpyAudioCodec): Boolean = runCatching {
            val mime = when (codec) {
                ScrcpyAudioCodec.OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
                ScrcpyAudioCodec.AAC -> MediaFormat.MIMETYPE_AUDIO_AAC
            }
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }
}
