package com.baba.callvault.system.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review C36: "update available" shared id 4716 with the health warning, and every "recorder ready" cleared it,
 * while the once-per-tag memory kept it from ever coming back. These pin the two halves of the repair.
 */
class UpdateNoticePolicyTest {

    @Test
    fun `posts a tag that was never announced`() {
        assertTrue(UpdateNoticePolicy.shouldPost(lastNotifiedTag = null, tag = "v2.5.3", ownIdAdopted = true))
    }

    @Test
    fun `does not repeat a tag already announced once the notice has its own id`() {
        assertFalse(UpdateNoticePolicy.shouldPost(lastNotifiedTag = "v2.5.3", tag = "v2.5.3", ownIdAdopted = true))
    }

    @Test
    fun `posts a newer tag after an older one`() {
        assertTrue(UpdateNoticePolicy.shouldPost(lastNotifiedTag = "v2.5.3", tag = "v2.5.4", ownIdAdopted = true))
    }

    @Test
    fun `brings back once a notice that the old shared id may have wiped`() {
        assertTrue(UpdateNoticePolicy.shouldPost(lastNotifiedTag = "v2.5.3", tag = "v2.5.3", ownIdAdopted = false))
    }
}
