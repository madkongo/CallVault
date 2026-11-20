/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

/** One known package that can conflict with CallVault, and the A-ids (docs/dev-notes/device-quirks.md, section F) it belongs to. */
data class ConflictingApp(val packageName: String, val displayName: String, val ids: List<String>)

/**
 * The FIXED list of packages whose mere presence is worth a line in the report. The phone is only ever asked about
 * these names (a read-only package lookup, visible through the manifest's LAUNCHER <queries>); the installed-app list
 * is never read, logged or sent. AVault ships ShizuCallRecorder's package name, so one entry names both.
 */
object KnownConflictingApps {

    val entries: List<ConflictingApp> = listOf(
        // A1 + A2: other call recorders. The Shizuku/shell-hosted ones are also A2.
        ConflictingApp("com.catalinagroup.callrecorder", "Cube ACR", listOf("A1", "A2")),
        ConflictingApp("com.nll.cb", "ACR Phone", listOf("A1", "A2")),
        ConflictingApp("org.skvalex.cr", "SKVALEX call recorder", listOf("A1", "A2")),
        ConflictingApp("com.talker.acr", "Talker ACR", listOf("A1")),
        ConflictingApp("com.talker.acr.main", "Talker ACR", listOf("A1")),
        ConflictingApp("com.smsrobot.callrecorder", "callX", listOf("A1")),
        ConflictingApp("com.smsrobot.callu", "callX", listOf("A1")),
        ConflictingApp("com.q4u.autocallrecorder", "Automatic Call Recorder (q4u)", listOf("A1")),
        ConflictingApp("com.chiller3.bcr", "BCR", listOf("A1")),
        ConflictingApp("com.kitsumed.shizucallrecorder", "ShizuCallRecorder or AVault", listOf("A1", "A2")),
        ConflictingApp("com.jemcik.jemrec", "JemRec", listOf("A1", "A2", "A4", "A5")),
        // A1 + A10: dialers that record or screen calls.
        ConflictingApp("com.samsung.android.dialer", "Samsung Phone", listOf("A1", "A10")),
        ConflictingApp("com.oplus.dialer", "OPPO/OnePlus Phone", listOf("A1", "A10")),
        ConflictingApp("com.google.android.dialer", "Google Phone", listOf("A1", "A10")),
        // A3: task killers and freezers.
        ConflictingApp("com.oasisfeng.greenify", "Greenify", listOf("A3")),
        ConflictingApp("com.aistra.hail", "Hail", listOf("A3")),
        ConflictingApp("com.catchingnow.icebox", "Ice Box", listOf("A3")),
        ConflictingApp("github.tornaco.android.thanos", "Thanox", listOf("A3", "A15")),
        ConflictingApp("me.piebridge.brevent", "Brevent", listOf("A3")),
        ConflictingApp("com.transsion.phonemaster", "Phone Master", listOf("A3", "A17")),
        ConflictingApp("io.github.muntashirakon.AppManager", "App Manager", listOf("A3", "A5", "A15")),
        // A4: apps that switch debugging off.
        ConflictingApp("app.devswitch", "DevSwitch", listOf("A4")),
        ConflictingApp("net.dinglisch.android.taskerm", "Tasker", listOf("A4", "A14")),
        ConflictingApp("com.arlosoft.macrodroid", "MacroDroid", listOf("A4", "A14")),
        ConflictingApp("com.llamalab.automate", "Automate", listOf("A4", "A5", "A14")),
        // A5: other ADB / Shizuku clients.
        ConflictingApp("moe.shizuku.privileged.api", "Shizuku", listOf("A5")),
        ConflictingApp("com.draco.ladb", "LADB", listOf("A5")),
        ConflictingApp("in.hridayan.ashell", "aShellYou", listOf("A5")),
        ConflictingApp("com.termux", "Termux", listOf("A5")),
        // A8: Wi-Fi Calling / IMS enablers.
        ConflictingApp("dev.bluehouse.enablevolte", "Pixel IMS", listOf("A8")),
        // A10: caller-ID and screening.
        ConflictingApp("com.truecaller", "Truecaller", listOf("A10", "A14")),
        ConflictingApp("com.webascender.callerid", "Hiya", listOf("A10")),
        ConflictingApp("org.mistergroup.shouldianswer", "Should I Answer", listOf("A10")),
        ConflictingApp("com.google.android.apps.googlevoice", "Google Voice", listOf("A10")),
        // A11: VPN, firewall, DNS.
        ConflictingApp("eu.faircode.netguard", "NetGuard", listOf("A11")),
        ConflictingApp("com.celzero.bravedns", "RethinkDNS", listOf("A11")),
        ConflictingApp("com.adguard.android", "AdGuard", listOf("A11")),
        ConflictingApp("com.wireguard.android", "WireGuard", listOf("A11")),
        ConflictingApp("com.tailscale.ipn", "Tailscale", listOf("A11")),
        // A15: permission and app-op managers.
        ConflictingApp("rikka.appops", "App Ops", listOf("A15")),
        // A17: cleaners.
        ConflictingApp("eu.thedarken.sdm", "SD Maid", listOf("A17")),
        ConflictingApp("com.piriform.ccleaner", "CCleaner", listOf("A17")),
    )

    /** Every A-id that has at least one package in the list. */
    val coveredIds: Set<String> = entries.flatMap { it.ids }.toSet()

    /**
     * The entries whose package [isInstalled] confirms, sorted by package name. [isInstalled] is asked only about the
     * fixed list; one that throws counts as not installed (an invisible package reads the same as an absent one).
     */
    fun detect(isInstalled: (String) -> Boolean): List<ConflictingApp> =
        entries.filter { runCatching { isInstalled(it.packageName) }.getOrDefault(false) }
            .sortedBy { it.packageName }

    /** Package names only, as the report and the always-on log show them. */
    fun packageNames(found: List<ConflictingApp>): List<String> = found.map { it.packageName }.distinct().sorted()
}
