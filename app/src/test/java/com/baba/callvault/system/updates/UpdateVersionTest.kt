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

    /**
     * A test build of X.Y.Z comes BEFORE the final X.Y.Z. Until 2026-09-27 the suffix was dropped, so a
     * phone on "2.4.2-rc2" read as 2.4.2 and was never offered the final v2.4.2 — every tester of a
     * pre-release was skipped by the very release their testing led to.
     */
    @Test
    fun the_final_release_is_newer_than_a_test_build_of_the_same_version() {
        assertTrue(UpdateVersion.isNewer("v2.4.2", "2.4.2-rc2"))
        assertTrue(UpdateVersion.isNewer("v1.2.3", "1.2.3-test2"))
    }

    @Test
    fun a_newer_version_is_newer_than_a_test_build() {
        assertTrue(UpdateVersion.isNewer("v1.2.4", "1.2.3-test2"))
    }

    @Test
    fun an_older_version_is_not_newer_than_a_test_build_of_a_later_one() {
        assertFalse(UpdateVersion.isNewer("v2.4.1", "2.4.2-rc2"))
    }

    /** The in-app updater never serves pre-releases, but a test tag must not look newer than the final. */
    @Test
    fun a_test_build_is_not_newer_than_the_final_of_the_same_version() {
        assertFalse(UpdateVersion.isNewer("v2.4.2-rc3", "2.4.2"))
    }

    @Test
    fun two_test_builds_of_the_same_version_are_never_an_update() {
        assertFalse(UpdateVersion.isNewer("v2.4.2-rc3", "2.4.2-rc2"))
        assertFalse(UpdateVersion.isNewer("v2.4.2-rc2", "2.4.2-rc2"))
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
