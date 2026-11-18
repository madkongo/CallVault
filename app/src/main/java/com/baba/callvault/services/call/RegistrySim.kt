/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.call

/**
 * Which SIM is in a call, read from `dumpsys telephony.registry`.
 *
 * The registry keeps one foreground-call state per phone. The phone that is not in the call stays
 * IDLE. That is a different field from [android.telephony.TelephonyManager.getCallState], which on
 * current Android is the state of every call on the device.
 */
internal object RegistrySim {

    private val PHONE = Regex("""Phone Id=(\d+)""")
    private val FOREGROUND = Regex("""mForegroundCallState=(-?\d+)""")
    private val CALL = Regex("""(?<!Foreground)(?<!Ringing)(?<!Background)mCallState=(-?\d+)""")

    /** 1-based slot of the single phone that is not idle, or null when the dump cannot say. */
    fun slotOf(dump: String?): Int? {
        if (dump.isNullOrBlank()) return null
        val phones = phonesIn(dump)
        val busy = phones.filter { it.busy }
        return if (busy.size == 1) busy[0].phoneId + 1 else null
    }

    private fun phonesIn(dump: String): List<Phone> {
        val headed = mutableListOf<Phone>()
        var phoneId: Int? = null
        var foreground: Int? = null
        var call: Int? = null
        fun flush() {
            val id = phoneId ?: return
            headed += Phone(id, foreground, call)
            foreground = null
            call = null
        }
        for (line in dump.lineSequence()) {
            PHONE.find(line)?.let {
                flush()
                phoneId = it.groupValues[1].toInt()
                return@let
            }
            if (phoneId == null) continue
            FOREGROUND.find(line)?.let { foreground = it.groupValues[1].toInt() }
            CALL.find(line)?.let { call = it.groupValues[1].toInt() }
        }
        flush()
        if (headed.isNotEmpty()) return headed
        // No "Phone Id=" headers: the lines are still one per phone, in phone order.
        val foregrounds = FOREGROUND.findAll(dump).map { it.groupValues[1].toInt() }.toList()
        if (foregrounds.isNotEmpty()) {
            return foregrounds.mapIndexed { index, state -> Phone(index, state, null) }
        }
        return CALL.findAll(dump).map { it.groupValues[1].toInt() }
            .mapIndexed { index, state -> Phone(index, null, state) }
            .toList()
    }

    private class Phone(val phoneId: Int, foreground: Int?, call: Int?) {
        /** Foreground state wins when it is present: IDLE there means this phone is not in the call. */
        val busy: Boolean = when {
            foreground != null -> foreground != IDLE
            else -> (call ?: IDLE) != IDLE
        }
    }

    private const val IDLE = 0
}
