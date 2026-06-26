package com.baba.callvault.calls

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiCall(
    val number: String?,
    val phase: CallEvent.Phase,
    val direction: CallDirection,
    val isEmergency: Boolean,
    val isRecording: Boolean,
)

/** Process-wide current-call state, shared between the InCallService and the in-call UI. */
object CallStateRepository {
    private val _current = MutableStateFlow<UiCall?>(null)
    val current: StateFlow<UiCall?> = _current.asStateFlow()

    fun update(call: UiCall?) { _current.value = call }

    fun setRecording(active: Boolean) {
        _current.value = _current.value?.copy(isRecording = active)
    }
}
