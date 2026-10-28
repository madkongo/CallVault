/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import com.baba.callvault.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The state word TalkBack says after a fold-open header (#61). */
class ExpandableHeaderTest {

    @Test
    fun an_open_section_reads_as_expanded() {
        assertEquals(R.string.general_expanded, expandStateRes(expanded = true))
    }

    @Test
    fun a_closed_section_reads_as_collapsed() {
        assertEquals(R.string.general_collapsed, expandStateRes(expanded = false))
    }
}
