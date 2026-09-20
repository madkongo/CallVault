/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.transcripts

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.ChannelMap
import com.baba.callvault.data.FirstSpeakerHeuristic
import com.baba.callvault.data.ChannelMapCorroboration
import com.baba.callvault.data.transcripts.db.SpeakerTurnsEntry
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.server.speakers.SpeakerTurn
import com.baba.callvault.server.speakers.SpeakerTurnCodec
import com.baba.callvault.utils.AppLogger

/**
 * Collects the speaker turns the daemon gathered during a call, and keeps them.
 *
 * Called once, right after a recording is catalogued and before transcription is queued — the turns
 * have to exist by the time a transcript is written, because that is when segments are labelled.
 *
 * **Everything here is best-effort and says so.** These labels are a nicety; the recording is the
 * product. A daemon that has gone away, an older daemon that predates the binder method, a mono
 * capture with no directions to compare — each ends in no turns and a log line, never in a failed
 * recording or a lost file.
 */
object SpeakerTurnsRepository {

    private const val TAG = "CV:SpeakerTurns"

    /** How many recent calls the trusted mapping is decided from. */
    private const val OBSERVATION_WINDOW = 5

    /**
     * Reads the turns for the call that just ended and stores them against [displayName].
     *
     * @param outgoing whether the user placed the call. Only an outgoing call has a ringback phase,
     *   so only an outgoing call's observation is allowed to teach the channel mapping. The daemon
     *   does not know this; the app does.
     */
    suspend fun collectAfterCall(context: Context, displayName: String, outgoing: Boolean) {
        // The app's own capture is asked FIRST, and taken whether or not a daemon is reachable.
        // With resilient recording on, the stereo frames pass through the app's HandoffEncoder and
        // the daemon holds no session to ask — so a daemon-only lookup found nothing on every call
        // this phone ever made. Consumed here, so it can never be attached to a later recording.
        val fromHandoff = CapturedSpeakerTurns.takeIfPresent()

        val service = RecorderConnection.service
        if (service == null && fromHandoff.isBlank()) {
            AppLogger.i(TAG, "No daemon to ask for speaker turns; the recording is unaffected")
            return
        }

        // Separately guarded: a warm daemon from an older build has neither method, and the two were
        // added together but may not stay that way.
        val fromDaemon = service?.let { runCatching { it.speakerTurns().orEmpty() }.getOrElse { "" } }.orEmpty()
        val turns = fromHandoff.ifBlank { fromDaemon }
        if (turns.isBlank()) {
            AppLogger.i(TAG, "No speaker turns for this recording (mono capture, or an older daemon)")
            return
        }
        AppLogger.i(TAG, "Speaker turns came from the ${if (fromHandoff.isNotBlank()) "handoff" else "daemon"} capture")

        // A convention, because both measurements are closed on this hardware: no ringback reaches
        // the capture, and a second VOICE_DOWNLINK capture beside the call costs the recording its
        // near side. What is left is that on an outgoing call, whoever speaks first is the person
        // who answered.
        //
        // Only outgoing calls are read. On an incoming one the same convention points the other way
        // and holds less firmly, and two observations are enough to settle this without it.
        val usable = if (outgoing) FirstSpeakerHeuristic.observe(turns) else ChannelMap.UNKNOWN

        runCatching {
            val db = TranscriptDatabase.get(context)
            db.speakerTurnsDao().upsert(
                SpeakerTurnsEntry(
                    displayName = displayName,
                    turns = turns,
                    outgoing = outgoing,
                    observedMap = usable.key,
                    updatedAt = System.currentTimeMillis()
                )
            )
            AppLogger.i(TAG, "Stored speaker turns; this call suggested ${usable.key}")
            logTrustedMap(context)
        }.onFailure {
            AppLogger.w(TAG, "Could not store speaker turns: ${it.message}")
        }
    }

    /**
     * Says where the mapping stands after this call, so it can be seen without instrumentation.
     *
     * Only a log line: there is nothing to update. The trusted mapping is derived from the stored
     * observations on every read rather than cached, which is what lets it be **lost** as well as
     * gained — and that direction matters. The mapping is a property of the device and should never
     * change, so evidence that stops agreeing means something assumed here is wrong, and neutral
     * labels are the honest answer until it settles again.
     */
    private suspend fun logTrustedMap(context: Context) {
        AppLogger.i(TAG, "Channel mapping now stands at ${trustedMap(context).key}")
    }

    /**
     * The mapping to label transcripts with, or [ChannelMap.UNKNOWN] for neutral labels.
     *
     * Derived on demand from the stored observations rather than cached in a preference. There is
     * exactly one source of truth that way, and the answer can never be a stale belief left behind
     * by calls that have since been deleted.
     */
    /**
     * Stores speaker turns worked out from a recording after the fact (issue #38).
     *
     * For Shizuku recordings, where the live capture never sees the raw channels because scrcpy does
     * the capture and the encode inside its own process. [collectAfterCall] finds nothing for those,
     * so this is the only route by which they ever get labels.
     *
     * **Never overwrites what the live capture found.** That answer came from the samples themselves
     * before any encoding; this one is read back out of a lossy file, so where both exist the live one
     * wins. Empty turns store nothing at all — that is the normal answer for a mono recording.
     *
     * @return true when something was stored.
     */
    suspend fun storeFromRecording(context: Context, displayName: String, turns: List<SpeakerTurn>): Boolean {
        if (turns.isEmpty()) return false

        return runCatching {
            val dao = TranscriptDatabase.get(context).speakerTurnsDao()
            if (dao.turnsFor(displayName) != null) {
                AppLogger.i(TAG, "Speaker turns already stored for this recording; leaving the capture's own answer")
                return false
            }
            dao.upsert(
                SpeakerTurnsEntry(
                    displayName = displayName,
                    turns = SpeakerTurnCodec.encode(turns),
                    // Read back from a file, so there is no call direction to learn a channel map
                    // from — that inference belongs to the live path, which knows the call.
                    outgoing = false,
                    observedMap = ChannelMap.UNKNOWN.key,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            AppLogger.i(TAG, "Stored ${turns.size} speaker turns worked out from the recording")
            true
        }.onFailure { AppLogger.w(TAG, "Could not store speaker turns from the recording: ${it.message}") }
            .getOrDefault(false)
    }

    suspend fun trustedMap(context: Context): ChannelMap {
        // Being told beats working it out. The user can see the transcript and knows which words are
        // theirs; nothing the app derives should be able to argue with that.
        val override = ChannelMap.fromKey(AppPreferences(context).getSpeakerMapOverride())
        if (override != ChannelMap.UNKNOWN) return override

        if (!TranscriptDatabase.exists(context)) return ChannelMap.UNKNOWN
        return runCatching {
            val dao = TranscriptDatabase.get(context).speakerTurnsDao()
            val observations = dao.recentOutgoing(OBSERVATION_WINDOW).map {
                FirstSpeakerHeuristic.observe(it.turns)
            }
            ChannelMapCorroboration.trusted(observations)
        }.getOrDefault(ChannelMap.UNKNOWN)
    }

    /** The stored turns for [displayName], or an empty string. */
    suspend fun turnsFor(context: Context, displayName: String): String {
        if (!TranscriptDatabase.exists(context)) return ""
        return runCatching {
            TranscriptDatabase.get(context).speakerTurnsDao().turnsFor(displayName)?.turns.orEmpty()
        }.getOrDefault("")
    }
}
