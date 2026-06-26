package com.baba.callvault.dialer

import android.app.role.RoleManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric 4.14 ShadowRoleManager note:
 * - isRoleAvailable() returns false for all roles by default (none pre-registered).
 * - Tests that exercise requestRoleIntent() must first add the role as available via
 *   shadowOf(rm).addAvailableRole(...) so that the implementation's isRoleAvailable guard passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class DialerRoleControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controller = DialerRoleController(context)

    @Test fun reports_not_default_when_role_not_held() {
        // Shadow default: role not available/held → isDefaultDialer() must return false
        assertFalse(controller.isDefaultDialer())
    }

    @Test fun request_intent_is_available_when_not_held() {
        // Shadow needs explicit role registration; make ROLE_DIALER available but not held
        val rm = context.getSystemService(RoleManager::class.java)
        shadowOf(rm).addAvailableRole(RoleManager.ROLE_DIALER)

        assertNotNull(controller.requestRoleIntent())
    }
}
