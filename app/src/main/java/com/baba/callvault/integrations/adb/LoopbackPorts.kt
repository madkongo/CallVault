/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Which loopback port to connect to, given the port adbd is listening on (`service.adb.tcp.port`).
 *
 * adbd listens on one tcp port at a time, and other apps can set it too. Shizuku forks have a "TCP mode" that
 * runs the same `tcpip:` as our off-Wi-Fi recording, on their own port (5555 by default). Before this,
 * CallVault accepted only its own port, so it re-armed. That restarted adbd and stopped Shizuku. Shizuku's
 * watchdog then started it again and moved adbd back to its port. The two never settled (voss, POCO X7 Pro,
 * HyperOS 3, 2026-10-22: recording down until Shizuku was uninstalled).
 *
 * Any port adbd listens on works for us: it is the same adbd and the same key. So we use it as it is, and only
 * restart adbd when nothing is listening.
 *
 * Free of Android types so every branch is tested.
 */
object LoopbackPorts {

    private const val MAX_PORT = 65_535

    /** The port in `service.adb.tcp.port`, or null when it is unset, off (0 / -1) or not a number. */
    fun parse(property: String?): Int? = property?.trim()?.toIntOrNull()?.takeIf { it in 1..MAX_PORT }

    /** Ports to try, in order. A port another app set comes first; ours stays as the fallback. */
    fun candidates(ourPort: Int, adbdPort: Int?): List<Int> =
        if (adbdPort == null || adbdPort == ourPort) listOf(ourPort) else listOf(adbdPort, ourPort)

    /** True when adbd is listening on some tcp port, ours or another app's. */
    fun isListening(adbdPort: Int?): Boolean = adbdPort != null

    /**
     * True when CallVault can reach adbd over this listener. Another app's port only counts in offline mode:
     * outside it CallVault dials loopback only as a last resort after Wireless debugging failed
     * ([alreadyOpenCandidates]) and never relies on it, so recovery and notices must not count it as a way in.
     */
    fun isUsable(ourPort: Int, adbdPort: Int?, offlineMode: Boolean): Boolean =
        isOurs(ourPort, adbdPort) || (offlineMode && isListening(adbdPort))

    /** True when the listener is the one CallVault armed. Only that one is ours to close. */
    fun isOurs(ourPort: Int, adbdPort: Int?): Boolean = adbdPort == ourPort

    // ---- adbd tcp listeners the ROM itself opened (no Wi-Fi, no Wireless debugging) ----
    //
    // Everything above is about a listener that was ARMED at runtime — by CallVault, or by another app's
    // `tcpip:` (Shizuku's TCP mode) — and read from `service.adb.tcp.port`, in offline mode only. Such a listener
    // is gone after a reboot. What follows is the other kind: a few OEM / TV-derived / eng or userdebug builds
    // have adbd listening on tcp from boot, because the ROM sets `persist.adb.tcp.port` (adbd reads it when
    // `service.adb.tcp.port` is unset) or `service.adb.listen_addrs`. On those phones CallVault can reach adbd
    // with no Wi-Fi and no Wireless debugging at all. Most phones set neither; then there is no candidate and
    // nothing is dialled. Rationale and AOSP source:
    // `docs/dev-notes/2026-10-23-wifi-free-adb-bootstrap-research.md` (adbd `daemon/main.cpp`).
    //
    // 🚨 SECURITY: only ports a system property names are candidates — never a well-known port such as 5555 on
    // a guess. Any app can bind 127.0.0.1:5555, answer CNXN/AUTH like adbd, and so get CallVault to sign its AUTH
    // token with CallVault's ADB key (a relay to the real adbd). A port named by adbd's own properties is the
    // one adbd is listening on, and an app cannot set those properties.

    /**
     * The tcp ports in `service.adb.listen_addrs` (comma-separated socket specs such as `tcp:5555`,
     * `vsock:5555`). Only `tcp:` entries count: vsock is not reachable from an app. The port is the last
     * `:`-separated field, so `tcp:5555` and `tcp:localhost:5555` both read as 5555.
     */
    fun parseListenAddrs(property: String?): List<Int> =
        property.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.startsWith("tcp:") }
            .mapNotNull { parse(it.substringAfterLast(':')) }

    /**
     * Ports where adbd may already be listening on tcp, in the order to ask them: the live listener
     * (`service.adb.tcp.port`), the ROM's persistent one (`persist.adb.tcp.port`), then the `listen_addrs` ones.
     * Only property-named ports, never a guessed default (see the SECURITY note above). Left out: ports in
     * [alreadyTried] (in offline mode, the ones [candidates] has just been refused on) and the live listener
     * when it is the one CallVault armed itself ([isOurs]) — that one belongs to the offline-mode path.
     */
    fun alreadyOpenCandidates(
        serviceTcpPort: Int?,
        persistTcpPort: Int?,
        listenAddrPorts: List<Int>,
        alreadyTried: Set<Int>,
        ourPort: Int,
    ): List<Int> {
        val live = serviceTcpPort?.takeUnless { isOurs(ourPort, it) }
        return (listOfNotNull(live, persistTcpPort) + listenAddrPorts)
            .distinct()
            .filterNot { it in alreadyTried }
    }

    /**
     * Whether a probed port is worth a real connect. Only a port that answered our `CNXN` as classic adbd
     * does (`AUTH` or `CNXN`): never a closed port, something that is not adb, or Wireless debugging's TLS
     * port (that one is ConnectPortFinder's, and needs the TLS identity).
     */
    fun isAlreadyOpenAdb(probe: PortProbe): Boolean = probe == PortProbe.ADB_PLAIN
}
