package com.baba.callvault.calls

enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }

/** A call-lifecycle event normalized across detection sources (broadcast vs Telecom). */
data class CallEvent(
    val phase: Phase,
    val number: String?,
    val direction: CallDirection,
    val isEmergency: Boolean,
) {
    enum class Phase { RINGING, DIALING, ACTIVE, ENDED }
}
