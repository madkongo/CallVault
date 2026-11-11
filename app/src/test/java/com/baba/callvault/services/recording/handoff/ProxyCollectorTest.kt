/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import java.lang.ref.WeakReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The handoff release must log what really happened to the binder proxy, not that it tried. */
class ProxyCollectorTest {

    @Test
    fun `a proxy that is still held is reported as still reachable`() {
        val strong = Any()
        val ref = WeakReference(strong)

        var rounds = 0
        val freed = ProxyCollector.collect(ref, gc = { rounds++ })

        assertFalse(freed)
        assertEquals(ProxyCollector.MAX_ROUNDS, rounds)
        assertTrue(ref.get() === strong) // keeps `strong` alive until here
    }

    @Test
    fun `a proxy that was dropped is reported as freed`() {
        var strong: Any? = Any()
        val ref = WeakReference(strong)
        strong = null

        // The seam stands in for System.gc(): clearing the referent is what a real collection does.
        val freed = ProxyCollector.collect(ref, gc = { ref.clear() })

        assertTrue(freed)
    }

    @Test
    fun `an already collected proxy needs no collection`() {
        val ref = WeakReference(Any())
        ref.clear()
        var rounds = 0

        assertTrue(ProxyCollector.collect(ref, gc = { rounds++ }))
        assertEquals(0, rounds)
    }
}
