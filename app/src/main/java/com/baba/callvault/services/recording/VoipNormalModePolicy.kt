/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.media.AudioManager

/**
 * A second start signal for app calls, for the few apps that never leave `MODE_NORMAL` (BabApps item 108).
 *
 * [VoipCallDetector] starts on `MODE_IN_COMMUNICATION`. LINE on a POCO F8 Ultra (HyperOS 3) runs its call
 * with the mode left NORMAL: the 15:29-15:30 log shows a call-voice player and a call-voice recording both
 * live for 15 s and nothing started. This is the decision for that case, kept apart from the mode logic
 * (which does not change for any other app) and free of Android so it can be tested.
 *
 * Narrow on purpose. A call-voice player together with a call-voice recording, with the mode NORMAL, also
 * happens in a browser meeting or a game's voice chat. Only a package on [NORMAL_MODE_CALL_APPS] may start
 * a recording this way, and the player and the recording must both belong to it.
 */
object VoipNormalModePolicy {

    /** Apps known to run calls without leaving MODE_NORMAL. Everything else keeps the mode-driven start. */
    val NORMAL_MODE_CALL_APPS: Set<String> = setOf("jp.naver.line.android")

    /** How long both must be live, without a gap, before this starts a recording. */
    const val HOLD_MS = 2_000L

    /** How long both must be gone before the call is ended; the same as the mode path's debounce. */
    const val END_DEBOUNCE_MS = VoipRingDipPolicy.NORMAL_END_DEBOUNCE_MS

    enum class Verdict {
        START,
        /** Everything fits but the pair has not been live long enough yet. */
        NOT_YET,
        MODE_IS_NOT_NORMAL,
        CARRIER_CALL,
        CALL_ALREADY_ACTIVE,
        NO_VOICE_PLAYER,
        NO_VOICE_RECORDING,
        NOT_ON_ALLOW_LIST,
    }

    /**
     * @param vcPlayerPackages    packages that own an active USAGE_VOICE_COMMUNICATION player.
     * @param vcRecorderPackages  packages that own an active VOICE_COMMUNICATION recording.
     * @param carrierCallUp       a carrier (managed) call is up.
     * @param heldMs              how long the player and the recording have both been live.
     */
    fun decide(
        mode: Int,
        carrierCallUp: Boolean,
        callActive: Boolean,
        vcPlayerPackages: Set<String>,
        vcRecorderPackages: Set<String>,
        heldMs: Long,
    ): Verdict = when {
        mode != AudioManager.MODE_NORMAL -> Verdict.MODE_IS_NOT_NORMAL
        carrierCallUp -> Verdict.CARRIER_CALL
        callActive -> Verdict.CALL_ALREADY_ACTIVE
        vcPlayerPackages.isEmpty() -> Verdict.NO_VOICE_PLAYER
        vcRecorderPackages.isEmpty() -> Verdict.NO_VOICE_RECORDING
        (vcPlayerPackages intersect vcRecorderPackages intersect NORMAL_MODE_CALL_APPS).isEmpty() -> Verdict.NOT_ON_ALLOW_LIST
        heldMs < HOLD_MS -> Verdict.NOT_YET
        else -> Verdict.START
    }

    // ---- review C9: the judge is retried, never dropped ----

    /** Why a judge produced no verdict. */
    enum class JudgeFailure {
        /** Another owner lookup was still running (one at a time). */
        LOOKUP_BUSY,

        /** The recorder host was not connected. */
        HOST_NOT_CONNECTED,

        /** The host gave no answer (it died, or the dump failed or timed out). */
        NO_ANSWER,
    }

    enum class JudgeNext {
        /** The call starts. */
        STARTED,

        /** A successful look said no: not judged again for this candidate. */
        FINAL,

        /** Judge again as soon as the running lookup finishes. */
        RETRY_AFTER_LOOKUP,

        /** Judge again after [JUDGE_RETRY_MS]. */
        RETRY_LATER,

        /**
         * The fast tries are spent: judge again after [JUDGE_SLOW_RETRY_MS], for as long as the candidate lives
         * (review C9 rework). Never a permanent give-up: a recorder host can reconnect many seconds into the call
         * (the app restarted mid-call, Q25), and the call is still worth recording from then on.
         */
        RETRY_SLOW,
    }

    /** The wait before a judge is retried after no answer or a racy answer, for the first tries. */
    const val JUDGE_RETRY_MS = 1_500L

    /** Fast judges per candidate, the first included; slower after that, each being a `dumpsys audio` run. */
    const val MAX_FAST_JUDGE_TRIES = 5

    /** The wait between judges once the fast tries are spent. */
    const val JUDGE_SLOW_RETRY_MS = 10_000L

    /** Slow checks between two always-on lines (about one a minute), so a long wait does not flood the file. */
    const val SLOW_CHECKS_PER_LOG_LINE = 6

    /** Whether the slow check after [triesUsed] judges gets an always-on line: the first one, then every few. */
    fun logSlowCheck(triesUsed: Int): Boolean =
        triesUsed >= MAX_FAST_JUDGE_TRIES && (triesUsed - MAX_FAST_JUDGE_TRIES) % SLOW_CHECKS_PER_LOG_LINE == 0

    /**
     * What follows a judge of a normal-mode candidate (review C9). The judge used to be one-shot: a lookup already
     * running, a missing recorder host or a failed dump left the candidate judged for the rest of the call, so a
     * LINE call that stayed live was neither recorded nor reported. Only a verdict from a successful dump that
     * rules the call out is final; a dump that misses audio the app itself still sees ([appSeesPair]) is racy and
     * retried. [triesUsed] counts the judges of this candidate so far, this one included.
     */
    fun afterJudge(failure: JudgeFailure?, verdict: Verdict?, appSeesPair: Boolean, triesUsed: Int): JudgeNext {
        if (failure == null) {
            when (verdict) {
                Verdict.START -> return JudgeNext.STARTED
                Verdict.NOT_ON_ALLOW_LIST, Verdict.CARRIER_CALL, Verdict.CALL_ALREADY_ACTIVE, Verdict.MODE_IS_NOT_NORMAL ->
                    return JudgeNext.FINAL
                Verdict.NO_VOICE_PLAYER, Verdict.NO_VOICE_RECORDING, Verdict.NOT_YET, null ->
                    if (!appSeesPair) return JudgeNext.FINAL
            }
        }
        if (triesUsed >= MAX_FAST_JUDGE_TRIES) return JudgeNext.RETRY_SLOW
        return if (failure == JudgeFailure.LOOKUP_BUSY) JudgeNext.RETRY_AFTER_LOOKUP else JudgeNext.RETRY_LATER
    }

    /**
     * Whether a call started by this path is over: the mode is still NORMAL and neither a call-voice
     * player nor a call-voice recording is live. (A mode other than NORMAL hands the call to the
     * mode-driven logic, which ends it by its own rules.)
     */
    fun shouldEnd(mode: Int, vcPlayers: Int, vcRecorders: Int): Boolean =
        mode == AudioManager.MODE_NORMAL && vcPlayers == 0 && vcRecorders == 0

    /**
     * A call this path started that moved into call mode and is now back in NORMAL with its call-voice player and
     * recording still live goes back to the pair watch ([shouldEnd]) instead of being ended by the mode (review
     * C14). Ending it by the mode published the recording 1.5 s later while LINE's call went on, and the pair watch
     * then opened a second file a few seconds after. A call the mode started is never handed back: its own rules
     * (the dip debounce, Q32) stay as they are.
     */
    fun handBackToPairWatch(startedNormal: Boolean, mode: Int, vcPlayers: Int, vcRecorders: Int): Boolean =
        startedNormal && mode == AudioManager.MODE_NORMAL && vcPlayers > 0 && vcRecorders > 0

    // ---- reading `dumpsys audio` (run by the recorder host: the app is shown anonymised configurations) ----

    data class Player(val uid: Int, val usage: String, val contentType: String)
    data class Recorder(val uid: Int, val packageName: String?, val source: String)
    data class Owners(val players: List<Player>, val recorders: List<Recorder>)

    private val PLAYER_UID = Regex("""u/pid:(\d+)/\d+""")
    private val USAGE = Regex("""usage=(\w+)""")
    private val CONTENT = Regex("""content=(\w+)""")
    private val REC_SOURCE = Regex("""source client=(\w+)""")
    private val REC_UID = Regex("""-- uid:(\d+)""")
    private val REC_PACK = Regex("""-- pack:(\S+)""")

    private const val USAGE_VC = "USAGE_VOICE_COMMUNICATION"
    private const val SOURCE_VC = "VOICE_COMMUNICATION"

    /**
     * Reads the `voip_owners` dump: the started players and the live recordings, one per line. Players
     * that are not started (idle, paused, stopped) are left out, so only live audio counts.
     */
    fun parseOwners(dump: String): Owners {
        val players = ArrayList<Player>()
        val recorders = ArrayList<Recorder>()
        // The audio service prints "riid N; active? true|false" and then the recording's own line, and it
        // lists recordings that were opened but never started too. null = no such line seen.
        var recordingActive: Boolean? = null
        for (line in dump.lineSequence()) {
            if (line.contains("; active? ")) {
                recordingActive = line.contains("active? true")
                continue
            }
            if (line.contains("AudioPlaybackConfiguration")) {
                if (!line.contains("state:started")) continue
                val uid = PLAYER_UID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val usage = USAGE.find(line)?.groupValues?.get(1) ?: "?"
                val content = CONTENT.find(line)?.groupValues?.get(1) ?: "?"
                players += Player(uid, usage, content)
            } else if (line.contains("source client=")) {
                val active = recordingActive
                recordingActive = null
                if (active == false) continue
                val uid = REC_UID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val source = REC_SOURCE.find(line)?.groupValues?.get(1) ?: "?"
                val pack = REC_PACK.find(line)?.groupValues?.get(1)?.takeIf { it != "null" }
                recorders += Recorder(uid, pack, source)
            }
        }
        return Owners(players, recorders)
    }

    /** Packages that own a started call-voice player, [packagesFor] mapping each uid to its packages. */
    fun voicePlayerPackages(owners: Owners, packagesFor: (Int) -> List<String>): Set<String> =
        owners.players.filter { it.usage == USAGE_VC }.flatMap { packagesFor(it.uid) }.toSet()

    /** Packages that own a live call-voice recording; the dump's own package name counts as well. */
    fun voiceRecorderPackages(owners: Owners, packagesFor: (Int) -> List<String>): Set<String> =
        owners.recorders.filter { it.source == SOURCE_VC }
            .flatMap { r -> listOfNotNull(r.packageName) + packagesFor(r.uid) }.toSet()

    /**
     * [voiceRecorderPackages], or — when the dump listed no recording line at all although the app itself
     * sees a call-voice recording (a ROM that words the line differently) — the player's own packages.
     * Never used when the dump did list recordings: those are read as they are.
     */
    fun recorderPackagesOrPlayers(
        owners: Owners,
        packagesFor: (Int) -> List<String>,
        appSeesVoiceRecording: Boolean,
        playerPackages: Set<String>,
    ): Set<String> {
        val read = voiceRecorderPackages(owners, packagesFor)
        return if (read.isEmpty() && owners.recorders.isEmpty() && appSeesVoiceRecording) playerPackages else read
    }

    /** One line for the log: who owns what, and whether the allow-list matched. */
    fun describe(
        owners: Owners,
        playerPackages: Set<String>,
        recorderPackages: Set<String>,
    ): String {
        val matched = (playerPackages intersect recorderPackages intersect NORMAL_MODE_CALL_APPS)
        val players = owners.players.joinToString(",", "[", "]") { "${it.usage.removePrefix("USAGE_")}/${it.contentType.removePrefix("CONTENT_TYPE_")}" }
        val recorders = owners.recorders.joinToString(",", "[", "]") { it.source }
        return "players=$players owners=${playerPackages.sorted()} recordings=$recorders owners=${recorderPackages.sorted()} " +
            "allowList=${if (matched.isEmpty()) "no match" else "MATCH ${matched.sorted()}"}"
    }
}
