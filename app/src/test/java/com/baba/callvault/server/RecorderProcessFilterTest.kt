package com.baba.callvault.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BabApps #95: the clearing step must not kill the adb shell that launched the host. */
class RecorderProcessFilterTest {

    private val nul = "\u0000"

    @Test
    fun our_daemon_is_a_recorder_host() {
        assertTrue(RecorderProcessFilter.isRecorderHost("app_process$nul/${nul}com.baba.callvault.server.RecorderServer$nul/data/app/x/base.apk$nul"))
    }

    @Test
    fun the_shizuku_hosted_recorder_is_a_recorder_host() {
        assertTrue(RecorderProcessFilter.isRecorderHost("com.baba.callvault:recorder$nul"))
    }

    @Test
    fun the_launching_shell_is_not() {
        val launcher = "sh${nul}-c${nul}setsid sh -c 'CLASSPATH=/a/base.apk exec app_process / com.baba.callvault.server.RecorderServer /a/base.apk' >/dev/null & sleep 3$nul"
        assertFalse(RecorderProcessFilter.isRecorderHost(launcher))
        assertFalse(RecorderProcessFilter.isRecorderHost("setsid${nul}sh${nul}-c${nul}x RecorderServer$nul"))
    }

    @Test
    fun an_unreadable_command_line_is_treated_as_before() {
        assertTrue(RecorderProcessFilter.isRecorderHost(null))
        assertTrue(RecorderProcessFilter.isRecorderHost(""))
    }
}
