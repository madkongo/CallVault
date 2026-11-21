/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/** Review R26: the update check sends If-None-Match, and a 304 still yields the release. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitHubReleasesConditionalTest {
    private val body = """{"tag_name":"v9.9.9","assets":[{"name":"CallVault.apk","browser_download_url":"https://x/CallVault.apk","size":10}]}"""
    private lateinit var server: HttpServer
    private val seenIfNoneMatch = mutableListOf<String?>()
    private var hits = 0
    private var etag = "\"abc\""
    private var failNext = false

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/r") { ex ->
            hits++
            val inm = ex.requestHeaders.getFirst("If-None-Match")
            seenIfNoneMatch += inm
            when {
                failNext -> ex.sendResponseHeaders(500, -1)
                inm == etag -> ex.sendResponseHeaders(304, -1)
                else -> {
                    val bytes = body.toByteArray()
                    ex.responseHeaders.add("ETag", etag)
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
            }
            ex.close()
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}/r"
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun the_second_check_is_conditional_and_still_returns_the_release() {
        val cache = ReleaseEtagCache(context)
        cache.clear()

        val first = GitHubReleases.fetchLatestRelease(url, cache)
        val second = GitHubReleases.fetchLatestRelease(url, cache)

        assertEquals("v9.9.9", first?.tag)
        assertEquals("v9.9.9", second?.tag)
        assertEquals(listOf(null, "\"abc\""), seenIfNoneMatch)
    }

    @Test
    fun a_changed_release_replaces_the_cached_one() {
        val cache = ReleaseEtagCache(context)
        cache.clear()
        GitHubReleases.fetchLatestRelease(url, cache)
        etag = "\"def\""

        GitHubReleases.fetchLatestRelease(url, cache)
        GitHubReleases.fetchLatestRelease(url, cache)

        assertEquals(listOf(null, "\"abc\"", "\"def\""), seenIfNoneMatch)
    }

    @Test
    fun a_server_error_is_a_failed_check_and_keeps_the_cache() {
        val cache = ReleaseEtagCache(context)
        cache.clear()
        GitHubReleases.fetchLatestRelease(url, cache)
        failNext = true
        assertNull(GitHubReleases.fetchLatestRelease(url, cache))
        failNext = false
        assertEquals("v9.9.9", GitHubReleases.fetchLatestRelease(url, cache)?.tag)
    }

    @Test
    fun a_different_url_never_reuses_another_urls_validator() {
        val cache = ReleaseEtagCache(context)
        cache.clear()
        cache.put("http://other/", "\"abc\"", body)
        GitHubReleases.fetchLatestRelease(url, cache)
        assertEquals(listOf<String?>(null), seenIfNoneMatch)
    }
}
