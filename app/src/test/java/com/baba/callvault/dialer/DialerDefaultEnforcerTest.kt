package com.baba.callvault.dialer

import org.junit.Assert.assertEquals
import org.junit.Test

class DialerDefaultEnforcerTest {
    @Test fun set_default_command() {
        assertEquals("cmd telecom set-default-dialer com.baba.callvault",
            DialerCommands.setDefault("com.baba.callvault"))
    }
    @Test fun grant_call_phone_command() {
        assertEquals("pm grant com.baba.callvault android.permission.CALL_PHONE",
            DialerCommands.grantCallPhone("com.baba.callvault"))
    }
    @Test fun restore_command() {
        assertEquals("cmd telecom set-default-dialer com.google.android.dialer",
            DialerCommands.restore("com.google.android.dialer"))
    }
    @Test fun set_default_and_grant_is_one_command() {
        assertEquals(
            "cmd telecom set-default-dialer com.baba.callvault ; pm grant com.baba.callvault android.permission.CALL_PHONE",
            DialerCommands.setDefaultAndGrant("com.baba.callvault"))
    }
}
