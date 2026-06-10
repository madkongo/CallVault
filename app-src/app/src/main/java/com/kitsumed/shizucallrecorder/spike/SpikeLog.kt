/*
 * SpikeLog — a tiny process-wide log shared by the spike Activity and the pairing
 * service, so results appended from either component show up on the screen.
 *
 * DEBUG SPIKE: remove before production.
 */

package com.kitsumed.shizucallrecorder.spike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SpikeLog {
    private val _text = MutableStateFlow("Ready.\n")
    val text: StateFlow<String> = _text

    @Synchronized
    fun append(line: String) {
        _text.value = _text.value + line + "\n"
    }

    fun clear() {
        _text.value = "Log cleared.\n"
    }
}
