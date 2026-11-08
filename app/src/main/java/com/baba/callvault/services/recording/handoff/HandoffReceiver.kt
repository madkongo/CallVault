/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.baba.callvault.utils.AppLogger
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * App-side receiver + controller for the audio-capture handoff ("Resilient recording", Option B).
 *
 * The recording engine calls [begin] with the output target BEFORE asking the daemon to start the
 * handoff. The daemon then pushes the `IAudioRecord` binder + cblk ashmem fd, which lands in [onReceived]
 * (routed by `RecorderBinderProvider`) on a binder thread. This object holds the binder — an independent
 * ref in the APP's binder_proc = keep-alive — and runs the full app-side capture: a native thread drains
 * the cblk ring to a pipe while [HandoffEncoder] reads the pipe and encodes into the engine's SAF output
 * fd. Killing the daemon mid-capture does NOT stop it: the track survives on the app's ref and the encode
 * is local. [stop] flips the stop flag and waits for the container to finalise.
 */
object HandoffReceiver {
    private const val T = "CV:HandoffRecv"

    private const val MAX_SECONDS = 4 * 60 * 60 // safety cap if a stop signal is ever lost
    private const val ENCODE_JOIN_MS = 8000L    // bound the wait for the encoder to finalise the container

    /** Output target the engine hands over (via [begin]) before starting the handoff. */
    data class Target(
        val outputFd: FileDescriptor,
        val mime: String,
        val muxerFormat: Int,
        val bitRate: Int,
        val downmixToMono: Boolean,
    )

    @Volatile private var pending: Target? = null
    // Strong ref so the app's binder_proc keeps the RecordHandle ref (keep-alive after the daemon dies).
    @Volatile private var held: IBinder? = null
    @Volatile private var cblk: ParcelFileDescriptor? = null
    @Volatile private var stopFlag: ByteBuffer? = null
    @Volatile private var encodeThread: Thread? = null

    /**
     * Asks the daemon for a fresh capture, mid-call. Supplied by the engine at [begin], because this
     * object is Context-less and sits on the push path.
     */
    @Volatile private var requestRearm: (() -> Boolean)? = null

    /** When the current capture began, for the completeness check in [stop]. */
    @Volatile private var captureStartedAtMs: Long = 0L

    /** The live encoder, so [stop] can read how much audio actually reached the file. */
    @Volatile private var encoderRef: HandoffEncoder? = null

    /** Set while the supervisor is waiting for a re-armed delivery, so [onReceived] routes it. */
    @Volatile private var awaitingRearm = false

    /**
     * Where [onReceived] parks a re-armed capture for the supervisor to pick up.
     *
     * A parking slot rather than a rendezvous: the daemon delivers the replacement capture *during*
     * the call that asks for it, so the supervisor cannot yet be waiting. See [HandoffSlot].
     */
    private val rearmed = HandoffSlot<Rearm> { runCatching { it.cblk.close() } }

    private class Rearm(val binder: IBinder?, val cblk: ParcelFileDescriptor, val geometry: HandoffGeometry)

    /**
     * How many times one recording may rebuild its capture.
     *
     * Bounded so a track that is torn down the instant it is created cannot spin: each attempt costs a
     * daemon round-trip, and past a few the call is not being recorded in any useful sense anyway.
     * AudioRecord's own restoreRecord_l uses 3 for the same reason.
     */
    private const val MAX_REARMS = 3

    /** How long to wait for the daemon's re-armed delivery before giving up on the recording. */
    private const val REARM_WAIT_MS = 4000L

    /** Below this, the ratio is dominated by start-up and rounding and says nothing useful. */
    private const val MIN_CHECKABLE_SECONDS = 5f

    /** Encoded/captured ratio treated as a whole recording; the shortfall below it is real loss. */
    private const val COMPLETE_ENOUGH = 0.90f

    /** True while a handoff capture is live in the app (survives daemon death). */
    @Volatile
    var isLive: Boolean = false
        private set

    /** Engine sets the output target BEFORE calling `IRecorderService.startHandoff`, so the push finds it. */
    fun begin(target: Target, requestRearm: (() -> Boolean)? = null) {
        pending = target
        this.requestRearm = requestRearm
        isLive = false
    }

    /** Clears a pending/failed handoff without a live capture (used when establishment fails → fall back). */
    fun abort() {
        pending = null
        releaseRefs()
        isLive = false
    }

    /**
     * Push entry point (called from `RecorderBinderProvider` on a binder thread when the daemon delivers).
     * Holds the binder + cblk and starts the drain + encode into the pending target's output fd.
     */
    fun onReceived(binder: IBinder?, cblkFd: ParcelFileDescriptor?, frameCount: Int, sampleRate: Int, channels: Int) {
        // A probe run borrows the delivery: it is measuring whether the app may START a handed-over
        // track, not recording anything, so the normal drain must not begin.
        if (TrackAProbe.isArmed && binder != null) {
            TrackAProbe.onHandoff(binder, cblkFd, frameCount, sampleRate, channels)
            return
        }

        val target = pending
        if (target == null) {
            AppLogger.w(T, "onReceived with no pending target — ignoring handoff")
            runCatching { cblkFd?.close() }
            return
        }
        // A re-arm in flight: this delivery is a REPLACEMENT capture for the recording already running,
        // not a new one. Hand it to the supervisor, which resumes the existing pipe and encoder so the
        // output file continues rather than being finalised and restarted.
        if (awaitingRearm) {
            val fdNum = cblkFd?.fd ?: -1
            val geo = if (fdNum >= 0) HandoffGeometry.of(
                frameCount = frameCount, sampleRate = sampleRate, channels = channels,
                cblkSize = AudioHandoffNative.nativeAshmemSize(fdNum),
            ) else null
            val bad = if (geo == null) "missing cblk fd" else geo.validationError()
            if (cblkFd == null || geo == null || bad != null) {
                AppLogger.e(T, "re-armed handoff rejected ($bad) — the recording ends here")
                runCatching { cblkFd?.close() }
                return
            }
            // Parked, not handed over. This runs on a binder thread serving the daemon, during the
            // very call the supervisor is still blocked in, so there is nobody to hand it to yet —
            // and waiting for one held this thread for four seconds and then closed a healthy
            // capture. Parking returns immediately and the supervisor collects it when it unblocks.
            if (rearmed.put(Rearm(binder, cblkFd, geo))) {
                AppLogger.i(T, "re-armed capture parked for the supervisor to collect")
            } else {
                // Only reachable if a previous attempt's capture is still parked, which `rebuild`
                // clears before asking. Closing is ours to do: `put` leaves ownership with us.
                AppLogger.e(T, "a re-armed capture is already waiting — closing this duplicate")
                runCatching { cblkFd.close() }
            }
            return
        }
        // Idempotency: a spurious/duplicate delivery must NOT spin up a second drain+encode against the
        // same output fd (dual-writer corruption). Only the first delivery for this session captures.
        if (isLive) {
            AppLogger.w(T, "onReceived while already capturing — ignoring duplicate handoff")
            runCatching { cblkFd?.close() }
            return
        }
        // Lazily load libaudiohandoff.so on the app side the first time a handoff actually arrives (the
        // native drain + ashmem-size calls need it). Idempotent.
        if (!AudioHandoffNative.ensureLoaded()) {
            AppLogger.e(T, "libaudiohandoff.so not available — cannot capture handoff")
            runCatching { cblkFd?.close() }
            return
        }
        val cblkFdNum = cblkFd?.fd ?: -1
        val ping = runCatching { binder?.pingBinder() }.getOrNull()
        AppLogger.i(T, "handoff received ping(alive)=$ping cblkFd=$cblkFdNum frameCount=$frameCount rate=$sampleRate ch=$channels")

        // Validate the geometry BEFORE any native mmap/drain. The trusted daemon always sends valid
        // values; this rejects a malicious or corrupt payload that could drive an out-of-bounds native
        // read (frameCount/channels/cblk size are otherwise fed straight into pointer arithmetic).
        if (cblkFdNum < 0) { AppLogger.w(T, "handoff missing cblk fd — cannot capture"); runCatching { cblkFd?.close() }; return }
        val geometry = HandoffGeometry.of(
            frameCount = frameCount,
            sampleRate = sampleRate,
            channels = channels,
            cblkSize = AudioHandoffNative.nativeAshmemSize(cblkFdNum),
        )
        geometry.validationError()?.let { reason ->
            AppLogger.w(T, "handoff rejected: $reason")
            runCatching { cblkFd?.close() }
            return
        }

        // Validated — hold the refs (app keep-alive) and start the drain + encode into the target fd.
        held = binder
        cblk = cblkFd
        startCapture(target, cblkFdNum, geometry)
    }

    /** Ends the capture: flips the stop flag, waits for the encoder to finalise the container, releases refs. */
    fun stop() {
        AppLogger.i(T, "stop requested")
        stopFlag?.putInt(0, 1) // drain exits within one poll cycle → closes writeFd → encoder hits EOF
        val enc = encodeThread
        runCatching { enc?.join(ENCODE_JOIN_MS) }
        if (enc?.isAlive == true) {
            // The encoder didn't finish flushing/finalising the container in time. The caller is about to
            // close the output fd, so log it — a truncated recording is then diagnosable rather than
            // silently mis-attributed. (Expected only under severe load; normal finalise is sub-second.)
            AppLogger.e(T, "handoff encoder still running after ${ENCODE_JOIN_MS}ms — recording may be truncated")
        }
        reportCompleteness()
        releaseRefs()
        isLive = false
        pending = null
    }

    private fun releaseRefs() {
        val binder = held
        held = null

        // Stop the track BEFORE dropping the last reference to it, and this is load-bearing.
        //
        // In the normal flow the daemon's stopHandoff already called stop() on its own AudioRecord.
        // But when the daemon has DIED mid-call — the case the handoff exists for — nothing ever
        // does, and the track is then destroyed purely by our reference going away. That route runs
        // AudioPolicyService::releaseInput(), which clears the client WITHOUT calling
        // finishRecording(); only stopInput() finishes it. The microphone app-op therefore stays
        // started forever, under uid 2000, owned by no living process — a green "Shell is using the
        // microphone" dot that nothing can clear, not even restarting the daemon.
        //
        // It is invisible in every log we collect: dumpsys audio's record-activity section tracks
        // recording CONFIGURATIONS, not ops, so the phone honestly reports "no capture left open"
        // while the indicator stays lit. See docs/dev-notes/2026-09-03-shell-mic-open-first-evidence.md.
        //
        // Stopping here is safe in BOTH flows: stopping an already-stopped track is a no-op, and by
        // this point the drain has ended and the encoder has been joined, so no audio is lost.
        if (binder != null) {
            val accepted = HeldRecordControl.stop(binder)
            AppLogger.i(T, "handoff track stop() before release: accepted=$accepted (finishes the mic app-op)")
        }

        runCatching { cblk?.close() }
        cblk = null
        stopFlag = null
        encodeThread = null
        if (binder != null) forceReleaseCaptureInput()
    }

    /**
     * Drops the handed-off capture for real.
     *
     * The `IAudioRecord` is kept alive purely by THIS process's binder REFCOUNT — that is precisely what
     * makes the capture survive the daemon's death. Nulling [held] is therefore NOT enough: the native
     * ref is only released when the `BinderProxy` is finalized, so without a collection the RecordTrack
     * stays alive and keeps the (exclusive) VOICE_CALL input open — silently starving the NEXT recording,
     * which then produces an empty file.
     *
     * In the normal flow the daemon's `stopHandoff` stops its own track and frees the input; but when the
     * daemon has DIED — the case this whole feature exists for — nothing else can release it. So force a
     * collection here, off the caller's thread (this runs once per recording, at call end).
     */
    private fun forceReleaseCaptureInput() {
        Thread {
            runCatching {
                System.gc()
                System.runFinalization()
                System.gc() // second pass: finalizer-enqueued proxies are reclaimed on the following cycle
            }
            AppLogger.i(T, "handoff capture input released (forced collection of the IAudioRecord ref)")
        }.apply { isDaemon = true; name = "cv-handoff-release" }.start()
    }

    /**
     * Wires the native cblk drain to [HandoffEncoder] through a pipe: native writes ordered interleaved
     * PCM to the pipe (its write end is detached to native ownership, close()=EOF), the encoder reads the
     * pipe, downmixes if asked, and muxes into [Target.outputFd]. The output fd is NOT closed here — the
     * engine owns its lifecycle and closes it after [stop] (once the container trailer is written).
     */
    private fun startCapture(target: Target, cblkFdNum: Int, geometry: HandoffGeometry) {
        val flag = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        stopFlag = flag
        val pipe = ParcelFileDescriptor.createPipe() // [0]=read, [1]=write
        val readPfd = pipe[0]
        val writeFd = pipe[1].detachFd()             // ownership → native (it close()s = EOF)
        AppLogger.i(T, "handoff capture → SAF fd (pipe writeFd=$writeFd frameSize=${geometry.frameSize} rate=${geometry.sampleRate} ch=${geometry.channels})")

        captureStartedAtMs = System.currentTimeMillis()
        val enc = HandoffEncoder(
            pcmIn = ParcelFileDescriptor.AutoCloseInputStream(readPfd),
            outFd = target.outputFd,
            sampleRate = geometry.sampleRate,
            captureChannels = geometry.channels,
            downmixToMono = target.downmixToMono,
            mime = target.mime,
            muxerFormat = target.muxerFormat,
            bitRate = target.bitRate,
        )
        encoderRef = enc
        val encoder = Thread {
            runCatching {
                enc.encodeBlocking()
                AppLogger.i(T, "handoff encode DONE")
            }.onFailure {
                // ERROR, not WARN: if the encoder dies the pipe's reader is gone, the drain's next
                // write fails, and the recording stops there — silently, mid-call. This used to be the
                // one line standing between a lost conversation and knowing why.
                AppLogger.e(T, "handoff encode FAILED — the recording stops here: ${it.message}", it)
            }
        }.apply { isDaemon = true; name = "cv-handoff-encode" }
        encodeThread = encoder
        encoder.start()

        Thread { superviseDrain(cblkFdNum, geometry, writeFd, flag) }
            .apply { isDaemon = true; name = "cv-handoff-drain" }.start()

        isLive = true
    }

    /**
     * Drains, and rebuilds the capture when the platform takes it away mid-call.
     *
     * **Why this loop exists.** AudioFlinger invalidates a record track on route changes, input
     * preemption and audioserver restarts. An ordinary `AudioRecord` never notices: `obtainBuffer()`
     * sees the dead track and calls `restoreRecord_l()`, which recreates it — preserving position,
     * retrying three times — and recording continues. The handoff deliberately bypasses `AudioRecord`
     * to drain the control block directly, and in doing so gave that recovery up. The result was
     * measured in the field on 2026-09-04: a 710-second call whose drain ended after 1.06 s produced a
     * 2511-byte file, with nothing in the app noticing. This restores parity.
     *
     * The pipe and the encoder are deliberately kept alive across a rebuild, so the output file
     * continues rather than being finalised and restarted. The audio buffered when the track died is
     * unrecoverable — AOSP's own restore loses it too — so a seam of a few hundred milliseconds
     * remains. That is the difference between a whole recording and losing the rest of the call.
     */
    private fun superviseDrain(
        firstCblkFd: Int,
        firstGeometry: HandoffGeometry,
        writeFd: Int,
        flag: ByteBuffer,
    ) {
        var cblkFdNum = firstCblkFd
        var geometry = firstGeometry
        var rearms = 0

        val stats = ByteBuffer.allocateDirect(AudioHandoffNative.STATS_BYTES).order(ByteOrder.nativeOrder())

        while (true) {
            val code = runCatching {
                AudioHandoffNative.nativeDrainToPipe(
                    cblkFdNum, geometry.cblkSize, geometry.wrapFrames, geometry.dataOff,
                    geometry.frameSize, HandoffGeometry.GUARD_FRAMES, writeFd, flag, MAX_SECONDS,
                    // Hold the pipe open only while a rebuild is still permitted; once the budget is
                    // spent an abnormal exit really is the end and the encoder must see EOF.
                    rearms < MAX_REARMS,
                    stats,
                )
            }.onFailure {
                // ERROR with the throwable: an UnsatisfiedLinkError here means no audio at all, and its
                // message alone says nothing useful.
                AppLogger.e(T, "handoff drain threw — the recording stops here: ${it.message}", it)
            }.getOrNull()

            val exit = AudioHandoffNative.DrainExit.of(code ?: -1)
            val s = AudioHandoffNative.DrainStats.read(stats)
            // Everything the native side knows, in the log that survives an export. Ring overrun is
            // called out separately because it loses audio WITHOUT ending the drain: the encoder
            // inserts no silence, so the timeline compresses and the file just comes out short.
            if (s.hasLoss) {
                AppLogger.e(T, "drain segment LOST AUDIO: $s — the file is short by roughly that much")
            } else {
                AppLogger.i(T, "drain segment: $s")
            }
            if (exit.isClean) {
                AppLogger.i(T, "handoff drain ended: ${exit.label}")
                return
            }

            // Abnormal. Say so in the app's own log — the native side's identical message goes only to
            // logcat, whose ring rotates long before a user exports a report.
            AppLogger.e(T, "handoff drain ended EARLY after ${geometry.sampleRate}Hz capture: ${exit.label}")

            if (code == null || rearms >= MAX_REARMS) {
                AppLogger.e(
                    T,
                    "not rebuilding the capture (attempts=$rearms/$MAX_REARMS): the recording ends " +
                        "here and the rest of the call is NOT in the file"
                )
                // Stop claiming to be recording. Until this line the drain could die and every other
                // part of the app carried on saying "recording" — the notification, the service, the
                // engine — for the rest of the call. That is how a 710-second call came back as 1.06
                // seconds with nothing amiss on screen.
                isLive = false
                closePipe(writeFd)
                return
            }
            // A stop that landed while we were between drains: not a failure, just the end.
            if (flag.getInt(0) != 0) {
                AppLogger.i(T, "stop requested during rebuild — ending normally")
                closePipe(writeFd)
                return
            }

            rearms++
            AppLogger.w(T, "rebuilding the capture, attempt $rearms/$MAX_REARMS (call is still up)")
            val next = rebuild()
            if (next == null) {
                AppLogger.e(T, "capture rebuild failed — the recording ends here")
                isLive = false
                closePipe(writeFd)
                return
            }
            // Let go of the dead track only once its replacement is in hand, so a failed rebuild does
            // not also throw away the reference that keeps the app's claim on the old one.
            val oldHeld = held
            val oldCblk = cblk
            held = next.binder
            cblk = next.cblk
            // STOP the invalidated track before dropping it, for the reason spelled out in
            // [releaseRefs]: letting the last reference go runs releaseInput() without
            // finishRecording(), and only stopInput() finishes the microphone app-op. That fix was made
            // for the end-of-call path and this rebuild reintroduced the same leak — with evidence
            // (2026-09-07, the tester's phone): capture#1 was invalidated 0.79 s into a call, rebuilt as
            // capture#2, and five minutes after the call ended the phone still reported
            // `uid=2000 op=RECORD_AUDIO running since +5m4s` — the age of capture#1, not of the
            // recording, which finished clean. The green dot never went out.
            //
            // Whether AudioFlinger accepts a stop on an already-invalidated track is the one thing we
            // cannot know from here, so the result is logged: `accepted=` answers it in the next report.
            if (oldHeld != null) {
                val accepted = HeldRecordControl.stop(oldHeld)
                AppLogger.i(T, "invalidated track stop() before rebuild: accepted=$accepted (finishes the mic app-op)")
            }
            runCatching { oldCblk?.close() }
            // The old BinderProxy still holds the native ref until it is collected — same reason
            // [forceReleaseCaptureInput] exists at end of call.
            if (oldHeld != null) forceReleaseCaptureInput()
            cblkFdNum = next.cblk.fd
            geometry = next.geometry
            AppLogger.i(T, "capture rebuilt (cblkFd=$cblkFdNum ch=${geometry.channels} rate=${geometry.sampleRate}) — recording continues")
        }
    }

    /** Asks the daemon for a replacement capture and waits for it to be pushed back. */
    private fun rebuild(): Rearm? {
        val ask = requestRearm ?: run {
            AppLogger.e(T, "no rebuild path wired for this session")
            return null
        }
        // Anything parked now belongs to an attempt that has already been abandoned. Collecting it
        // would point the drain at shared memory whose track is gone, so it is closed before asking.
        rearmed.clear()
        awaitingRearm = true
        return try {
            if (!runCatching { ask() }.onFailure { AppLogger.e(T, "rebuild request threw: ${it.message}") }
                    .getOrDefault(false)
            ) {
                AppLogger.e(T, "the daemon refused to re-arm the capture")
                return null
            }
            // Usually already parked by the time we get here, so this returns at once. The timeout
            // only covers a daemon that answered the request but delivers late.
            rearmed.takeWithin(REARM_WAIT_MS)
                ?: run { AppLogger.e(T, "no re-armed capture arrived within ${REARM_WAIT_MS}ms"); null }
        } finally {
            // Cleared BEFORE the flag, deliberately. A delivery landing in this window while the flag
            // is still set merely parks and is closed by the next attempt's clear(); one landing after
            // the flag is cleared would fall through to the ordinary path and could start a second
            // capture against the same output. A stranded fd is the cheaper of the two failures.
            rearmed.clear()
            awaitingRearm = false
        }
    }

    /**
     * Closes the pipe's write end, which is what tells the encoder to finalise the container.
     *
     * Native owns the fd while it is draining and closes it on a clean stop; when it is told to keep it
     * open for a rebuild that never comes, closing falls to us or the encoder waits forever.
     */
    private fun closePipe(writeFd: Int) {
        runCatching { ParcelFileDescriptor.adoptFd(writeFd).close() }
            .onFailure { AppLogger.w(T, "closing the capture pipe failed: ${it.message}") }
    }

    /**
     * Did the file get the whole call?
     *
     * One check that catches a short recording **whatever caused it** — an invalidated track, a dead
     * encoder, a stalled ring, a cause nobody has thought of yet. Everything else in this class
     * reports a specific known failure; this reports the symptom, so a new failure mode cannot be
     * silent the way the 2026-09-04 one was (a 710-second call, 1.06 seconds recorded, nothing said).
     *
     * Compares audio actually encoded against how long the capture was up. A little slack is normal —
     * codec priming, the final partial frame, the moments either side of the drain — so only a real
     * shortfall is reported, and it is reported at ERROR with both numbers.
     */
    private fun reportCompleteness() {
        val startedAt = captureStartedAtMs
        if (startedAt <= 0L) return
        val wallSeconds = (System.currentTimeMillis() - startedAt) / 1000f
        val encodedSeconds = encoderRef?.encodedSeconds ?: 0f
        captureStartedAtMs = 0L
        encoderRef = null
        if (wallSeconds < MIN_CHECKABLE_SECONDS) return

        val ratio = encodedSeconds / wallSeconds
        if (ratio >= COMPLETE_ENOUGH) {
            AppLogger.i(T, "recording complete: ${"%.1f".format(encodedSeconds)}s encoded of ${"%.1f".format(wallSeconds)}s captured")
        } else {
            AppLogger.e(
                T,
                "RECORDING IS SHORT: only ${"%.1f".format(encodedSeconds)}s of audio for a " +
                    "${"%.1f".format(wallSeconds)}s capture (${(ratio * 100).toInt()}%). The rest of " +
                    "the call is NOT in the file — see the drain/encode lines above for why."
            )
        }
    }
}
