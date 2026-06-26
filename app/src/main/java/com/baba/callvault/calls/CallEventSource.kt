package com.baba.callvault.calls

/** Receives normalized call events from whichever source is active. */
fun interface CallEventListener {
    fun onCallEvent(event: CallEvent)
}

/** A source of normalized call events. Exactly one source is active at a time (see CallEventRouter). */
interface CallEventSource {
    fun start(listener: CallEventListener)
    fun stop()
}
