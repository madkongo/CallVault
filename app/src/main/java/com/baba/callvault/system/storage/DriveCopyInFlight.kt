/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.storage

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many messages the `:drive` copier has received and not yet answered (review C146). The process is ended when the
 * app unbinds only if this is above zero, i.e. work is being abandoned; an idle copier just stops its service and lets
 * Android cache the process, instead of adding an exit record to the 16-entry history on every copy.
 */
class DriveCopyInFlight {
    private val inFlight = AtomicInteger(0)

    /** A message arrived (counted before it is queued, so a queued one is never missed). */
    fun received() { inFlight.incrementAndGet() }

    /** The reply for one message was sent (or the handler finished); never goes below zero. */
    fun replied() { inFlight.updateAndGet { if (it > 0) it - 1 else 0 } }

    fun count(): Int = inFlight.get()

    fun shouldKillOnDestroy(): Boolean = inFlight.get() > 0
}
