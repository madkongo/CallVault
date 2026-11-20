/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

/**
 * Review N30: the user being heard always wins over our recording.
 *
 * On some phones the most recent starter holds the microphone, so re-taking ours after it went quiet ([VoipCaptureSession])
 * can silence the CALLING app's own capture, and the other person then stops hearing the user. After a re-take the
 * daemon asks Android whether the call app's capture is now silenced; if it is, we give the mic back for the rest of
 * the call and the recording ends as failed (both sides or nothing: never a mic-only rescue).
 *
 * The signal is read from the `RecordingActivityMonitor` section of `dumpsys audio`, which is what the daemon can read
 * for any client: `rec <start|update|stop> riid:<n> uid:<n> session:<n> src:<SRC> [not ]silenced pack:<pkg>`. The
 * app process cannot see other clients' state, and `AudioRecordingConfiguration.isClientSilenced` (API 29) only tells
 * about a record we hold ourselves. Unknown (no dump, no live capture of that uid) keeps today's behaviour.
 */
internal object MicYield {

    enum class Action { KEEP, YIELD }

    /** What Android says about one live capture of the call app. */
    data class ClientState(val pack: String, val source: String, val silenced: Boolean)

    /** Marks the evidence line inside the capture-health text the recorder hands to the app. */
    const val MARKER = "MIC_CONTENTION"

    /**
     * Whether the yield itself is switched on. OFF in 2.5.2 (owner rule: nothing that cannot be tested on phones before
     * release unless certain). Detection and the always-on evidence line run regardless; only the behaviour change waits.
     *
     * Safe to switch on once real reports show, for the phones that matter, that (1) the call app's `silenced` flag in
     * `dumpsys audio` flips to true right after our re-take and back to false when we hold off (the evidence lines in
     * reports, with the "kept re-taking" action, show the flag really is read), and (2) the far party then did not hear
     * the user (a report or a test), and (3) no phone shows the flag silenced while the call is fine (a false yield would
     * end a good recording). The S24 FE case, where the re-take rescued near audio, must be re-measured with it.
     */
    const val YIELD_ENABLED = false

    /** Silenced after our re-take: give the mic back. Not silenced, unknown or [yieldEnabled] false: the re-take behaves as before. */
    fun decide(callAppSilenced: Boolean?, yieldEnabled: Boolean = YIELD_ENABLED): Action =
        if (yieldEnabled && callAppSilenced == true) Action.YIELD else Action.KEEP

    private val EVENT = Regex(
        """rec (start|update|stop) riid:(\d+) uid:(\d+) session:\d+ src:(\w+)\s+(not silenced|silenced)\s+pack:(\S+)"""
    )

    /**
     * The state of the call app's live capture, from the latest event of each of its captures, or null when the dump
     * shows none (no dump, a ring that evicted it, or the app is not capturing). Several live captures: silenced if any is.
     */
    fun callAppState(dump: String, uid: Int): ClientState? {
        if (!VoipAppIdentity.isAppUid(uid)) return null   // the shell (ours) or the platform is never the call app
        val latest = LinkedHashMap<String, ClientState?>()
        for (m in EVENT.findAll(dump)) {
            val (kind, riid, eventUid, source, silence, pack) = m.destructured
            if (eventUid.toIntOrNull() != uid) continue
            latest[riid] = if (kind == "stop") null else ClientState(pack, source, silence == "silenced")
        }
        val live = latest.values.filterNotNull()
        return live.firstOrNull { it.silenced } ?: live.lastOrNull()
    }

    /** The one line the recorder adds to its capture health; no phone numbers or names, only the client and timings. */
    fun evidenceLine(state: ClientState, atMs: Long, retakes: Int, checkMs: Long, action: Action = Action.KEEP): String =
        "$MARKER: ${state.pack} (src=${state.source}) was silenced after our mic re-take #$retakes, " +
            "seen ${atMs}ms into the capture, check took ${checkMs}ms; " +
            if (action == Action.YIELD) "action=yielded (our mic released, recording stopped)"
            else "action=kept re-taking (yield is off)"

    /** True when the evidence line says the recording was stopped for the user's sake. */
    fun wasYielded(evidence: String): Boolean = evidence.contains("action=yielded")

    /** The evidence line inside [health], or null. */
    fun evidenceIn(health: String?): String? {
        val text = health ?: return null
        val at = text.indexOf("$MARKER:")
        if (at < 0) return null
        return text.substring(at).lineSequence().first().trim()
    }

    private val PACK = Regex("""$MARKER: (\S+) """)

    fun packOf(evidence: String): String? = PACK.find(evidence)?.groupValues?.get(1)
}
