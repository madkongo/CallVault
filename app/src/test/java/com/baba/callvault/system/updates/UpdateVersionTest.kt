/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {

    @Test
    fun newer_patch_version_is_newer() {
        assertTrue(UpdateVersion.isNewer(remoteTag = "v1.2.4", installed = "1.2.3"))
    }

    @Test
    fun same_version_is_not_newer() {
        assertFalse(UpdateVersion.isNewer(remoteTag = "v1.2.3", installed = "1.2.3"))
    }

    @Test
    fun older_version_is_not_newer() {
        assertFalse(UpdateVersion.isNewer(remoteTag = "v1.2.2", installed = "1.2.3"))
    }

    @Test
    fun minor_and_major_bumps_are_newer() {
        assertTrue(UpdateVersion.isNewer("v1.3.0", "1.2.9"))
        assertTrue(UpdateVersion.isNewer("v2.0.0", "1.9.9"))
    }

    @Test
    fun test_suffix_on_installed_version_is_ignored() {
        // A device on "1.2.3-test2" must still see the final "v1.2.3"… as NOT newer,
        // and "v1.2.4" as newer.
        assertFalse(UpdateVersion.isNewer("v1.2.3", "1.2.3-test2"))
        assertTrue(UpdateVersion.isNewer("v1.2.4", "1.2.3-test2"))
    }

    @Test
    fun missing_parts_count_as_zero() {
        assertTrue(UpdateVersion.isNewer("v1.3", "1.2.9"))
        assertFalse(UpdateVersion.isNewer("v1.2", "1.2.0"))
    }

    @Test
    fun unparseable_versions_never_trigger_an_update() {
        assertFalse(UpdateVersion.isNewer("latest", "1.2.3"))
        assertFalse(UpdateVersion.isNewer("", "1.2.3"))
        assertFalse(UpdateVersion.isNewer("v1.2.4", "garbage"))
    }
}
