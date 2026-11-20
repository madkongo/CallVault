/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review N15: Android's trusted Wi-Fi list as `dumpsys adb` prints it on a real phone. The keystore is Android Binary
 * XML on one line, so each address sits between binary bytes: a length byte (0x11) or a printable character from the
 * token before it, and the next token's type byte, '3' (0x33), right after it. An address missed here reads as
 * untrusted, and the recorder re-approves on every Wi-Fi join (OnePlus 9 Pro, Android 14, 2026-11-20).
 */
class TrustedBssidsAbxTest {

    private fun dump(keystore: String) = "ADB MANAGER STATE (dumpsys adb):\n  debugging_manager={\n    keystore=$keystore\n  }"

    @Test
    fun `the OnePlus 9 Pro's real binary-XML keystore yields both its access points`() {
        // Read as the recorder reads a process's output: bytes decoded as UTF-8.
        val bytes = checkNotNull(javaClass.classLoader?.getResourceAsStream("n15/dumpsys-adb-op9-abx.bin")) {
            "missing fixture n15/dumpsys-adb-op9-abx.bin"
        }.use { it.readBytes() }

        assertEquals(
            setOf("02:11:22:33:00:01", "02:11:22:33:01:02"),
            WirelessDebuggingApproval.parseTrustedBssids(String(bytes, Charsets.UTF_8)),
        )
    }

    @Test
    fun `text XML with a self-closing wifiAP still reads`() {
        assertEquals(
            setOf("aa:bb:cc:dd:ee:ff"),
            WirelessDebuggingApproval.parseTrustedBssids(dump("<keyStore version=\"1\"><wifiAP bssid=\"aa:bb:cc:dd:ee:ff\"/></keyStore>")),
        )
    }

    @Test
    fun `a hex character right before or after an address in binary XML does not hide it`() {
        val abx = "ABX\u0000d\u0000\u001102:11:22:33:00:013\u0000\u0005" +
            "\u0006\u0000aaa:bb:cc:dd:ee:ff3\u0000" +
            "\u0006\u0000\u001110:20:30:40:50:60f"

        assertEquals(
            setOf("02:11:22:33:00:01", "aa:bb:cc:dd:ee:ff", "10:20:30:40:50:60"),
            WirelessDebuggingApproval.parseTrustedBssids(dump(abx)),
        )
    }

    @Test
    fun `a colon run longer than six groups is not read as an access point`() {
        // Reading a network as trusted when it is not would skip its approval and leave the user with Android's prompt.
        val text = "x 11:22:33:44:55:66:77 y fe80:aa:bb:cc:dd:ee:ff:1 z 01:02:03:04:05:06:07:08 w aa:bb:cc:dd:ee:ff:"

        assertEquals(emptySet<String>(), WirelessDebuggingApproval.parseTrustedBssids(dump(text)))
    }

    @Test
    fun `a newline in the binary data followed by a brace or a name= does not end the keystore early`() {
        // 0x0A is an ordinary byte in ABX (a length or an index of 10); only the dump's own lines end the field.
        val abx = "ABX\u0000\u001111:11:11:11:11:113\n}\u0000\u001122:22:22:22:22:223" +
            "\nabc=\u0000\u001133:33:33:33:33:333\n  }x\u0000\u001144:44:44:44:44:443"

        assertEquals(
            setOf("11:11:11:11:11:11", "22:22:22:22:22:22", "33:33:33:33:33:33", "44:44:44:44:44:44"),
            WirelessDebuggingApproval.parseTrustedBssids(dump(abx)),
        )
    }

    @Test
    fun `the keystore ends at the next field at its own indentation or at its parent's closing brace`() {
        val nextField = "ADB MANAGER STATE (dumpsys adb):\n  debugging_manager={\n    keystore=<wifiAP bssid=\"aa:bb:cc:dd:ee:ff\"/>\n" +
            "    other_field=11:22:33:44:55:66\n  }\n}"
        val closingBrace = "ADB MANAGER STATE (dumpsys adb):\n  debugging_manager={\n    keystore=<wifiAP bssid=\"aa:bb:cc:dd:ee:ff\"/>\n" +
            "  }\n  other_manager={\n    note=11:22:33:44:55:66\n  }\n}"

        assertEquals(setOf("aa:bb:cc:dd:ee:ff"), WirelessDebuggingApproval.parseTrustedBssids(nextField))
        assertEquals(setOf("aa:bb:cc:dd:ee:ff"), WirelessDebuggingApproval.parseTrustedBssids(closingBrace))
    }
}
