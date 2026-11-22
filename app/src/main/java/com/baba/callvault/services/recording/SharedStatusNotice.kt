/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.Notification
import android.os.SystemClock
import kotlin.math.abs
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one notification CallVault keeps in the shade: "Ready to record calls" while idle, and the call being
 * recorded — with Pause, Mark and Stop — while it is.
 *
 * A recorded carrier call used to show both side by side, and "Ready to record calls" says nothing useful while a
 * call is already being recorded (maintainer, issue #31 follow-up). Neither can simply be hidden: both belong to
 * foreground services, which Android requires to show a notification. What Android does support is two foreground
 * services in one app posting under the **same id** — `ActiveServices.cancelForegroundNotificationLocked` leaves the
 * notification up while another foreground service still holds that id. So the recording service posts under the
 * keep-alive's id, and this object keeps the two from overwriting each other:
 *
 * - while a call is recorded the keep-alive does not post, because "Ready" would replace the only in-call controls;
 * - if the keep-alive has to start meanwhile, it posts the recording's notification rather than its own;
 * - when the recording lets go, the keep-alive is told to put "Ready" back, because Android will not cancel an id
 *   the keep-alive still holds and the finished call's notification would otherwise stay up.
 *
 * Both services run on the main thread of the same process; the fields are volatile for the rare off-thread reader.
 */
object SharedStatusNotice {

    /** The keep-alive's existing id, so a notification already on a user's phone stays the same one. */
    const val ID = 4720

    /**
     * Who holds the id (review C23). A phone call and an app call can both be recorded around the same time, and
     * one slot without an owner let the carrier service's release (a stand-down for the app call, or its own end)
     * throw away the app call's notice: the keep-alive then replaced Pause, Mark and Stop with a notice without
     * them. Each owner now releases only its own claim.
     */
    enum class Owner { CARRIER, VOIP }

    /** Claims in the order they were last made; the newest is what the id shows. Guarded by itself. */
    private val claims = LinkedHashMap<Owner, Notification>()

    /** Called when a recorded call releases the notification. The keep-alive re-posts what is left: [heldNotification] or its own. */
    @Volatile
    var onReleased: (() -> Unit)? = null

    /**
     * Posts a notification as the keep-alive's foreground notice under [ID], returning whether it did; set while the
     * keep-alive runs. On Android 17 a notify() on a foreground service's id does not replace what is shown (Q49,
     * #73), so a recorded call's notice goes through this; notify() is only a logged fallback.
     */
    @Volatile
    var foregroundPoster: ((Notification) -> Boolean)? = null

    /** What Home's card should say about the recording that is really running (UI-1). */
    enum class Live { NONE, RECORDING, PAUSED }

    private val liveByOwner = LinkedHashMap<Owner, Live>()
    private val liveState = MutableStateFlow(Live.NONE)

    /**
     * The live recording state as a flow, so Home follows a call starting, pausing and ending while it is open.
     * Set by the engine's own state (carrier) or by the app-call recorder, NOT by the notification claim: an app
     * call recorded with notification permission denied has no claim but is still recording.
     */
    val liveFlow: StateFlow<Live> get() = liveState

    /**
     * What the carrier service's state means for Home's card: RECORDING/PAUSED only for an Active session whose recorder
     * has not been lost and whose capture did start; a lost recorder or a capture that never started is NONE.
     */
    fun carrierLive(isActive: Boolean, isPaused: Boolean, recorderLostOrNeverStarted: Boolean): Live = when {
        !isActive || recorderLostOrNeverStarted -> Live.NONE
        isPaused -> Live.PAUSED
        else -> Live.RECORDING
    }

    // How long the live recording has really run, pausing excluded (the Home card's timer). Guarded by liveByOwner.
    private var runningSinceMs = NOT_RUNNING
    private var bankedMs = 0L
    private var startedWallMs = 0L

    /** What the clock persists so a process kill mid-call does not restart the timer at 00:00. */
    data class SavedClock(val runningSinceMs: Long, val bankedMs: Long, val bootWallMs: Long, val savedWallMs: Long, val startedWallMs: Long)

    /** Where the clock is persisted; null (tests, the shell process) means no persistence. */
    interface ClockStore {
        fun load(): SavedClock?
        fun save(clock: SavedClock?)

        /** The last wall-clock moment the phone was seen with no call at all (0 if never); a call-free moment ends any call. */
        fun lastNoCallAt(): Long
    }

    @Volatile
    var clockStore: ClockStore? = null

    /**
     * Milliseconds the current recording has been actively recording at [nowMs] (the same clock [setLive] reads,
     * SystemClock.elapsedRealtime): frozen while paused, 0 when nothing is live.
     */
    fun liveElapsedMs(nowMs: Long): Long = synchronized(liveByOwner) {
        bankedMs + if (runningSinceMs == NOT_RUNNING) 0L else (nowMs - runningSinceMs).coerceAtLeast(0L)
    }

    /**
     * A saved clock is trusted only when it was written in this same boot (elapsedRealtime is comparable then),
     * recently enough to be one call, and is not from the future. Anything else is a different call: start at zero.
     */
    internal fun restorable(saved: SavedClock, nowMs: Long, wallMs: Long, lastNoCallAt: Long = 0L): Boolean =
        lastNoCallAt <= saved.startedWallMs &&
            abs((wallMs - nowMs) - saved.bootWallMs) <= BOOT_TOLERANCE_MS &&
            wallMs - saved.savedWallMs in 0..MAX_RESTORE_AGE_MS &&
            saved.runningSinceMs <= nowMs

    private fun trackClock(state: Live, nowMs: Long, wallMs: Long) {
        val store = clockStore
        when (state) {
            Live.NONE -> {
                runningSinceMs = NOT_RUNNING; bankedMs = 0L; startedWallMs = 0L
                store?.save(null)
                return
            }
            Live.RECORDING -> {
                if (runningSinceMs == NOT_RUNNING && bankedMs == 0L) {
                    // A fresh process joining a call that was already being timed?
                    store?.load()?.takeIf { restorable(it, nowMs, wallMs, store.lastNoCallAt()) }?.let {
                        runningSinceMs = it.runningSinceMs; bankedMs = it.bankedMs; startedWallMs = it.startedWallMs
                    }
                }
                if (runningSinceMs == NOT_RUNNING) runningSinceMs = nowMs
            }
            Live.PAUSED -> {
                if (runningSinceMs == NOT_RUNNING && bankedMs == 0L) {
                    store?.load()?.takeIf { restorable(it, nowMs, wallMs, store.lastNoCallAt()) }?.let {
                        runningSinceMs = it.runningSinceMs; bankedMs = it.bankedMs; startedWallMs = it.startedWallMs
                    }
                }
                if (runningSinceMs != NOT_RUNNING) {
                    bankedMs += (nowMs - runningSinceMs).coerceAtLeast(0L)
                    runningSinceMs = NOT_RUNNING
                }
            }
        }
        // The call's first start: kept from a restored clock, else now.
        val startedWall = startedWallMs.takeIf { it > 0L } ?: wallMs
        startedWallMs = startedWall
        store?.save(SavedClock(runningSinceMs, bankedMs, wallMs - nowMs, wallMs, startedWall))
    }

    private const val NOT_RUNNING = -1L
    private const val BOOT_TOLERANCE_MS = 5_000L
    private const val MAX_RESTORE_AGE_MS = 2 * 60 * 60 * 1000L

    /** The live state now. */
    val live: Live get() = liveState.value

    /** [owner] is recording ([paused] false) or holding a paused recording; [Live.NONE] clears it. */
    fun setLive(owner: Owner, state: Live, nowMs: Long = SystemClock.elapsedRealtime(), wallMs: Long = System.currentTimeMillis()) {
        synchronized(liveByOwner) {
            if (state == Live.NONE) liveByOwner.remove(owner) else liveByOwner[owner] = state
            // Recording wins over paused when two calls overlap.
            liveState.value = when {
                liveByOwner.containsValue(Live.RECORDING) -> Live.RECORDING
                liveByOwner.containsValue(Live.PAUSED) -> Live.PAUSED
                else -> Live.NONE
            }
            trackClock(liveState.value, nowMs, wallMs)
        }
    }

    /** True while any recorded call owns the notification. */
    val isHeldByRecording: Boolean get() = synchronized(claims) { claims.isNotEmpty() }

    /** True while [owner] has a claim. */
    fun isHeldBy(owner: Owner): Boolean = synchronized(claims) { claims.containsKey(owner) }

    /** The notification the id should show for the recorded calls, the newest claim's; null when none is held. */
    val heldNotification: Notification? get() = synchronized(claims) { claims.values.lastOrNull() }

    /** Records the notification [owner] has just posted under [ID]; each update replaces its last one. */
    fun claim(owner: Owner, notification: Notification) {
        synchronized(claims) {
            claims.remove(owner)
            claims[owner] = notification
        }
    }

    /**
     * Gives [owner]'s claim back. Only a real release notifies (the stop path can run more than once), and another
     * owner's claim is left alone: the keep-alive then re-posts that one, not "Ready".
     */
    fun release(owner: Owner) {
        val released = synchronized(claims) { claims.remove(owner) != null }
        if (released) onReleased?.invoke()
    }

    /**
     * Asks the keep-alive to put its own content back although nothing was claimed. For a recording-service
     * instance that ends without having posted — an orphaned notification action — while a leftover
     * recording notification may still sit on [ID]. Ignored while a call holds the id.
     */
    fun requestRefresh() {
        if (isHeldByRecording) return
        onReleased?.invoke()
    }

    /**
     * Whether the notification actually posted under [ID] is left over from a call: it belongs to another
     * channel than the keep-alive's own, and no recorded call holds the id.
     *
     * It happens because a service's notification is posted asynchronously by system_server, while the
     * keep-alive re-posts "Ready" directly — a recording post can land after "Ready" and stay (LAVA
     * LXX508, 2026-09-26). The keep-alive reads what is posted and heals it.
     */
    fun isStale(postedChannelId: String?, ownChannelId: String): Boolean =
        !isHeldByRecording && postedChannelId != null && postedChannelId != ownChannelId

    /** What the keep-alive should post under [ID]: the recorded call's notification if there is one, else [own]. */
    fun contentForKeepAlive(own: Notification): Notification = heldNotification ?: own

    @VisibleForTesting
    fun resetForTest() {
        synchronized(claims) { claims.clear() }
        synchronized(liveByOwner) { liveByOwner.clear() }
        liveState.value = Live.NONE
        // The saved clock is left alone on purpose: this is how a test simulates a process kill.
        synchronized(liveByOwner) { runningSinceMs = NOT_RUNNING; bankedMs = 0L; startedWallMs = 0L }
        clockStore = null
        onReleased = null
        foregroundPoster = null
    }
}
