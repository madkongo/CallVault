/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36 (org.json needs the Android runtime)
class GitHubReleasesTest {

    private val goodJson = """
        {
          "tag_name": "v1.2.4",
          "draft": false,
          "prerelease": false,
          "assets": [
            {"name": "mapping.txt", "browser_download_url": "https://example.com/mapping.txt", "size": 10},
            {"name": "CallVault.apk", "browser_download_url": "https://github.com/madkongo/CallVault/releases/download/v1.2.4/CallVault.apk", "size": 83061613}
          ]
        }
    """.trimIndent()

    @Test
    fun parses_tag_and_apk_asset() {
        val release = GitHubReleases.parseLatestRelease(goodJson)

        assertEquals("v1.2.4", release?.tag)
        assertEquals(
            "https://github.com/madkongo/CallVault/releases/download/v1.2.4/CallVault.apk",
            release?.apkUrl
        )
        assertEquals(83061613L, release?.apkSizeBytes)
    }

    @Test
    fun prerelease_and_draft_are_ignored() {
        val prerelease = goodJson.replace("\"prerelease\": false", "\"prerelease\": true")
        val draft = goodJson.replace("\"draft\": false", "\"draft\": true")

        assertNull(GitHubReleases.parseLatestRelease(prerelease))
        assertNull(GitHubReleases.parseLatestRelease(draft))
    }

    @Test
    fun missing_apk_asset_returns_null() {
        val noApk = goodJson.replace("CallVault.apk", "CallVault-other.zip")

        assertNull(GitHubReleases.parseLatestRelease(noApk))
    }

    @Test
    fun malformed_json_returns_null() {
        assertNull(GitHubReleases.parseLatestRelease("not json at all"))
        assertNull(GitHubReleases.parseLatestRelease("{}"))
    }

    @Test
    fun cleartext_http_download_url_is_rejected() {
        val http = goodJson.replace("https://github.com", "http://github.com")
        assertNull(GitHubReleases.parseLatestRelease(http))
    }

    @Test
    fun missing_or_zero_asset_size_is_rejected() {
        val zero = goodJson.replace("\"size\": 83061613", "\"size\": 0")
        assertNull(GitHubReleases.parseLatestRelease(zero))
    }
}
