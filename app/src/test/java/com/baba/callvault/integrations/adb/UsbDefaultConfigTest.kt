/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import com.baba.callvault.data.PrivilegedMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two decisions that left issue #22's reporter without the one piece of advice that would
 * have helped: how a `dumpsys usb` line is classified, and when the user is told about it.
 */
class UsbDefaultConfigTest {

    // ---- parse: what the device reports -> what we think it means

    @Test
    fun `treats adb alone as debugging-only, the One UI 8 default`() {
        assertEquals(
            UsbDefaultMode.DEBUGGING_ONLY,
            UsbDefaultConfig.parse("    screen_unlocked_functions=adb"),
        )
    }

    @Test
    fun `treats a data function combined with adb as a data mode`() {
        assertEquals(
            UsbDefaultMode.FILE_TRANSFER,
            UsbDefaultConfig.parse("screen_unlocked_functions=mtp,adb"),
        )
    }

    @Test
    fun `treats an empty function list as charging only`() {
        assertEquals(UsbDefaultMode.CHARGING, UsbDefaultConfig.parse("screen_unlocked_functions="))
        assertEquals(UsbDefaultMode.CHARGING, UsbDefaultConfig.parse("screen_unlocked_functions=none"))
    }

    @Test
    fun `reports an unrecognised function as unknown rather than guessing`() {
        assertEquals(
            UsbDefaultMode.UNKNOWN,
            UsbDefaultConfig.parse("screen_unlocked_functions=some_oem_mode"),
        )
    }

    // ---- noticeFor: what the user is told

    @Test
    fun `warns about a data mode even when the recorder is not ready`() {
        // The regression this exists to prevent: the data mode kills the daemon, the dead daemon reads
        // as not-ready, and readiness used to gate the warning — so it vanished exactly when it applied.
        assertEquals(
            UsbNotice.DATA_MODE_RISK,
            UsbDefaultConfig.noticeFor(UsbDefaultMode.FILE_TRANSFER, recorderReady = false),
        )
    }

    @Test
    fun `warns about a data mode while the recorder is ready`() {
        assertEquals(
            UsbNotice.DATA_MODE_RISK,
            UsbDefaultConfig.noticeFor(UsbDefaultMode.TETHERING, recorderReady = true),
        )
    }

    @Test
    fun `says nothing about the safe modes`() {
        assertEquals(UsbNotice.NONE, UsbDefaultConfig.noticeFor(UsbDefaultMode.CHARGING, recorderReady = true))
        assertEquals(UsbNotice.NONE, UsbDefaultConfig.noticeFor(UsbDefaultMode.CHARGING, recorderReady = false))
        assertEquals(UsbNotice.NONE, UsbDefaultConfig.noticeFor(UsbDefaultMode.DEBUGGING_ONLY, recorderReady = false))
    }

    @Test
    fun `admits it could not check only while the recorder is failing to come up`() {
        assertEquals(
            UsbNotice.COULD_NOT_CHECK,
            UsbDefaultConfig.noticeFor(UsbDefaultMode.UNKNOWN, recorderReady = false),
        )
        // A working phone is not nagged about a check that turned out not to matter.
        assertEquals(
            UsbNotice.NONE,
            UsbDefaultConfig.noticeFor(UsbDefaultMode.UNKNOWN, recorderReady = true),
        )
    }

    // ---- parseProperty: the sys.usb.config fallback, for ROMs whose dumpsys omits the setting

    @Test
    fun `reads a data function out of the property, whatever else is listed alongside it`() {
        // The real value sampled on a OnePlus 12 while the mode was USB tethering.
        assertEquals(UsbDefaultMode.TETHERING, UsbDefaultConfig.parseProperty("rndis,none,adb"))
        assertEquals(UsbDefaultMode.FILE_TRANSFER, UsbDefaultConfig.parseProperty("mtp,adb"))
        assertEquals(UsbDefaultMode.PTP, UsbDefaultConfig.parseProperty("ptp,adb"))
        assertEquals(UsbDefaultMode.MIDI, UsbDefaultConfig.parseProperty("midi,adb"))
    }

    @Test
    fun `treats adb-only and none as the safe case`() {
        assertEquals(UsbDefaultMode.CHARGING, UsbDefaultConfig.parseProperty("adb"))
        assertEquals(UsbDefaultMode.CHARGING, UsbDefaultConfig.parseProperty("none"))
    }

    @Test
    fun `treats an absent property as unknown, not as safe`() {
        // A blank is no evidence. Mapping it to "no data functions" would report SAFE for a phone whose
        // mode we never learned — the exact false reassurance this whole area is about.
        assertEquals(UsbDefaultMode.UNKNOWN, UsbDefaultConfig.parseProperty(""))
        assertEquals(UsbDefaultMode.UNKNOWN, UsbDefaultConfig.parseProperty("   "))
    }

    // ---- isShellUsable: which modes may drive the embedded ADB shell at all

    @Test
    fun `never drives the embedded shell in Shizuku mode`() {
        // Not a nicety: ensureConnected is the app's only writer of adb_wifi_enabled = 1, so a probe
        // that cannot succeed here still opens a network port. The wizard's reliability step did exactly
        // that on entry, for a user who had never paired anything.
        assertFalse(UsbDefaultConfig.isShellUsable(PrivilegedMode.SHIZUKU))
    }

    @Test
    fun `drives the embedded shell in standalone mode, where it is ours to drive`() {
        assertTrue(UsbDefaultConfig.isShellUsable(PrivilegedMode.STANDALONE))
    }

    // ---- what the cached value means, per privileged mode ----

    @Test
    fun `a stored data mode is a risk in standalone mode`() {
        assertEquals(
            UsbDefaultMode.FILE_TRANSFER,
            UsbDefaultConfig.resolveCached("FILE_TRANSFER", PrivilegedMode.STANDALONE),
        )
    }

    @Test
    fun `in Shizuku mode the stored value means nothing and resolves to unknown`() {
        // The fix for issue #28's nag loop. In Shizuku mode setViaShell and readViaShell both refuse
        // to run — there is no embedded ADB — so whatever is stored was written under a different
        // mode and can never be refreshed or corrected. The app was still warning from it, about a
        // setting its own picker declines to change. UNKNOWN is what "we cannot see it" already
        // means everywhere else here, and noticeFor() already handles that case sensibly.
        assertEquals(
            UsbDefaultMode.UNKNOWN,
            UsbDefaultConfig.resolveCached("FILE_TRANSFER", PrivilegedMode.SHIZUKU),
        )
        assertEquals(
            UsbDefaultMode.UNKNOWN,
            UsbDefaultConfig.resolveCached("CHARGING", PrivilegedMode.SHIZUKU),
        )
    }

    @Test
    fun `nothing stored is unknown in either mode`() {
        assertEquals(UsbDefaultMode.UNKNOWN, UsbDefaultConfig.resolveCached(null, PrivilegedMode.STANDALONE))
        assertEquals(UsbDefaultMode.UNKNOWN, UsbDefaultConfig.resolveCached(null, PrivilegedMode.SHIZUKU))
    }

    @Test
    fun `an unrecognised stored value is unknown rather than a crash`() {
        // A downgrade, or a renamed constant, must not throw on a screen that only wants advice.
        assertEquals(UsbDefaultMode.UNKNOWN, UsbDefaultConfig.resolveCached("NO_SUCH_MODE", PrivilegedMode.STANDALONE))
        assertEquals(UsbDefaultMode.UNKNOWN, UsbDefaultConfig.resolveCached("", PrivilegedMode.STANDALONE))
    }

    @Test
    fun `resolving to unknown in Shizuku mode silences the screen-lock warning`() {
        // The user-visible consequence, stated as a test so it cannot regress: UNKNOWN is not a risk,
        // and noticeFor treats it as nothing to say while the recorder is up.
        val shizuku = UsbDefaultConfig.resolveCached("FILE_TRANSFER", PrivilegedMode.SHIZUKU)
        assertEquals(UsbNotice.NONE, UsbDefaultConfig.noticeFor(shizuku, recorderReady = true))

        // ...while standalone still warns, which is the whole point of the warning existing.
        val standalone = UsbDefaultConfig.resolveCached("FILE_TRANSFER", PrivilegedMode.STANDALONE)
        assertEquals(UsbNotice.DATA_MODE_RISK, UsbDefaultConfig.noticeFor(standalone, recorderReady = true))
    }

    // A Honor BVL-N49 (Android 16, 2026-11-03) set to Charging only: dumpsys usb printed the USB handler
    // state without screen_unlocked_functions, and sys.usb.config read 'hisuite,mtp,mass_storage'. The
    // property won, the app warned "file transfer", the fix button cleared it, and the next read put it back.

    private val handlerWithoutUnlockedFunctions = """
        USB MANAGER STATE (dumpsys usb):
        {
          device_manager={
            handler={
              current_functions_applied=true
              screen_locked=false
              connected=false
            }
          }
        }
    """.trimIndent()

    @Test
    fun `a USB handler dump without screen-unlocked functions means none are set`() {
        // dumpsys usb leaves out fields that are empty, so the handler with no such field is Charging only.
        assertEquals(UsbDefaultMode.CHARGING, UsbDefaultConfig.parseDump(handlerWithoutUnlockedFunctions))
    }

    @Test
    fun `a dump that carries the field is read from the field`() {
        val dump = handlerWithoutUnlockedFunctions.replace(
            "current_functions_applied=true",
            "current_functions_applied=true\n      screen_unlocked_functions=MTP",
        )
        assertEquals(UsbDefaultMode.FILE_TRANSFER, UsbDefaultConfig.parseDump(dump))
    }

    @Test
    fun `a dump with no USB handler state says nothing`() {
        // A shell that printed an error, or a ROM that prints something else entirely: no evidence.
        assertEquals(UsbDefaultMode.UNKNOWN, UsbDefaultConfig.parseDump("Can't find service: usb"))
    }

    @Test
    fun `the system property never overrides a mode already read`() {
        // On Honor the property holds the vendor default, not the setting, so it may only fill a blank.
        assertFalse(UsbDefaultConfig.mayUseProperty(cached = UsbDefaultMode.CHARGING))
        assertFalse(UsbDefaultConfig.mayUseProperty(cached = UsbDefaultMode.FILE_TRANSFER))
        assertTrue(UsbDefaultConfig.mayUseProperty(cached = UsbDefaultMode.UNKNOWN))
    }
}
