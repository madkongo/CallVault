/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package com.baba.callvault.system

import com.baba.callvault.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * Guards the shipped ABI set. The default build (no -PagentAbi) must carry arm64-v8a and nothing
 * else; -PagentAbi (agent test builds only) is the one way to change it, and it flags itself.
 */
class AgentAbiDefaultTest {
    @Test
    fun defaultBuildCarriesArm64Only() {
        assumeFalse("built with -PagentAbi", BuildConfig.AGENT_ABI_ACTIVE)
        assertEquals("arm64-v8a", BuildConfig.NATIVE_ABIS)
    }

    @Test
    fun defaultBuildVersionNameHasNoAgentSuffix() {
        assumeFalse("built with -PagentAbi", BuildConfig.AGENT_ABI_ACTIVE)
        assertFalse(BuildConfig.VERSION_NAME.endsWith("-abi"))
    }

    @Test
    fun agentBuildIsMarkedInVersionName() {
        if (BuildConfig.AGENT_ABI_ACTIVE) {
            assertEquals(true, BuildConfig.VERSION_NAME.endsWith("-abi"))
        }
    }
}
