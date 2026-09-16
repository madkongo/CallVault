/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.navigation

import com.baba.callvault.ui.navigation.HomeSection.Companion.forNotification
import com.baba.callvault.ui.navigation.HomeSection.Companion.opening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which section the app opens on.
 *
 * "Reopen where you were" reads as a convenience and behaves like a router: the stored key comes
 * from a previous build, the previous visit may have ended mid-onboarding, and the failure mode is
 * a blank screen with no way to say what went wrong. So the decision is pinned here, before anything
 * navigates on it.
 */
class HomeSectionTest {

    @Test
    fun `a first run opens the hub`() {
        // Nothing stored, because nobody has been anywhere yet. The cards are what explains the app;
        // dropping a new user straight into a list would not.
        assertEquals(HomeSection.Hub, opening(AppScreen.Home, storedKey = null))
    }

    @Test
    fun `a stored section is reopened`() {
        assertEquals(HomeSection.Recordings, opening(AppScreen.Home, storedKey = "recordings"))
        assertEquals(HomeSection.Transcripts, opening(AppScreen.Home, storedKey = "transcripts"))
        assertEquals(HomeSection.Summaries, opening(AppScreen.Home, storedKey = "summaries"))
        assertEquals(HomeSection.Hub, opening(AppScreen.Home, storedKey = "hub"))
    }

    @Test
    fun `a section that no longer exists opens the hub`() {
        // Written by a build that had a section this one does not, or renamed since. Landing on the
        // hub is the one answer that is always a real screen.
        assertEquals(HomeSection.Hub, opening(AppScreen.Home, storedKey = "speakers"))
        assertEquals(HomeSection.Hub, opening(AppScreen.Home, storedKey = ""))
    }

    @Test
    fun `onboarding beats whatever was stored`() {
        // Someone who has not accepted the disclaimer or finished the wizard has nothing to be
        // returned to, and resuming ahead of setup would open an app that cannot record.
        assertNull(opening(AppScreen.Disclaimer, storedKey = "recordings"))
        assertNull(opening(AppScreen.Permissions, storedKey = "recordings"))
        assertNull(opening(AppScreen.Wizard, storedKey = "recordings"))
    }

    @Test
    fun `every notification lands where it can be acted on`() {
        // The status card, the update offer and the banners are all on the hub, so every one of
        // these taps has to arrive there. Landing on a transcript list would show no sign of the
        // problem the notification was about, and nothing would say so.
        assertEquals(HomeSection.Hub, forNotification(AppScreen.Home, NotificationDestination.Status))
        assertEquals(HomeSection.Hub, forNotification(AppScreen.Home, NotificationDestination.Update))
        assertEquals(HomeSection.Hub, forNotification(AppScreen.Home, NotificationDestination.Pairing))
        assertEquals(HomeSection.Hub, forNotification(AppScreen.Home, NotificationDestination.Debug))
        // And the two transcript notices land on Transcripts, which is the only page carrying
        // either of the things they are about: the finished transcripts, and the "didn't finish"
        // group whose whole card retries.
        assertEquals(
            HomeSection.Transcripts,
            forNotification(AppScreen.Home, NotificationDestination.Transcript)
        )
        assertEquals(
            HomeSection.Transcripts,
            forNotification(AppScreen.Home, NotificationDestination.TranscriptFailed)
        )
    }

    @Test
    fun `nothing asked for moves nobody`() {
        // A launcher open, a build older than the extra, or a destination already acted on. By then
        // the user may have navigated somewhere themselves, and taking them off it would be the same
        // out-of-nowhere jump the history-flag check exists to prevent.
        assertNull(forNotification(AppScreen.Home, NotificationDestination.None))
    }

    @Test
    fun `a notification cannot jump ahead of onboarding`() {
        assertNull(forNotification(AppScreen.Wizard, NotificationDestination.Status))
        assertNull(forNotification(AppScreen.Disclaimer, NotificationDestination.Pairing))
        assertNull(forNotification(AppScreen.Permissions, NotificationDestination.Update))
    }

    @Test
    fun `no two sections share a key`() {
        // The keys outlive the build that wrote them; a duplicate would make one unreachable.
        val keys = HomeSection.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
