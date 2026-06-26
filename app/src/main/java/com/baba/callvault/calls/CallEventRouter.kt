package com.baba.callvault.calls

import java.util.concurrent.atomic.AtomicReference

enum class DetectionMode { BROADCAST, TELECOM }

/**
 * Single sink for normalized call events. Only events from the currently active detection mode are
 * forwarded; the inactive source is ignored. This enforces broadcast↔Telecom mutual exclusion.
 */
class CallEventRouter(private val sink: (CallEvent) -> Unit) {
    private val active = AtomicReference(DetectionMode.BROADCAST)

    fun setActiveMode(mode: DetectionMode) { active.set(mode) }

    fun submit(source: DetectionMode, event: CallEvent) {
        if (source == active.get()) sink(event)
    }
}
