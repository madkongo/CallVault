/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.phone

import com.baba.callvault.data.PrivilegedMode

/** How a quirk in docs/dev-notes/device-quirks.md can be told apart on a phone. */
enum class DetectionKind {
    /** A setting or API answers before a call is lost. */
    SETTING,
    /** Behaviour seen on this phone (an exception shape, a timing, a failed start). */
    PROBE,
    /** Only brand, ROM or Android version tells; used for wording or a case that cannot be probed. */
    IDENTITY,
    /** Applies to every phone; nothing to detect. */
    ALWAYS_ON,
    /** Only a report can show it; [PhoneRule.reason] says why. */
    NOT_DETECTABLE,
}

enum class Severity { CALL_LOSS, RISK, INFO }

/**
 * One quirk. [applies] is null while the rule has no profile-based predicate (most today): such a rule is only
 * catalogued. No rule changes behaviour yet; the registry is groundwork and a report aid.
 */
data class PhoneRule(
    val id: String,
    val kind: DetectionKind,
    val severity: Severity? = null,
    val reason: String? = null,
    val applies: ((PhoneProfile) -> Boolean)? = null,
)

object PhoneRules {

    private const val ANDROID_12 = 31
    private fun probe(id: String) = PhoneRule(id, DetectionKind.PROBE)
    private fun setting(id: String) = PhoneRule(id, DetectionKind.SETTING)
    private fun identity(id: String) = PhoneRule(id, DetectionKind.IDENTITY)
    private fun always(id: String, why: String) = PhoneRule(id, DetectionKind.ALWAYS_ON, reason = why)
    private fun none(id: String, why: String) = PhoneRule(id, DetectionKind.NOT_DETECTABLE, reason = why)

    val entries: List<PhoneRule> = listOf(
        // Q01-Q10: ADB transport; all behaviour.
        probe("Q01"), probe("Q02"), probe("Q03"), probe("Q04"), probe("Q05"), probe("Q06"), probe("Q07"),
        probe("Q08"), probe("Q09"), probe("Q10"),
        setting("Q11"), probe("Q12"), setting("Q13"), setting("Q14"),
        probe("Q15"), always("Q16", "Applies to every Shizuku-hosted recorder; the host always drops the app identity."),
        probe("Q17"), probe("Q18"), probe("Q19"), probe("Q20"), probe("Q21"), probe("Q22"), probe("Q23"),
        probe("Q24"), probe("Q25"), probe("Q26"), setting("Q27"), probe("Q28"), probe("Q29"),
        identity("Q30"), probe("Q31"), probe("Q32"), probe("Q33"), probe("Q34"), probe("Q35"), probe("Q36"),
        probe("Q37"), always("Q38", "Two free-running captures always drift; the pairing code applies on every phone."),
        always("Q39", "A second voice capture is never opened during a call, on any phone."),
        identity("Q40"), probe("Q41"), probe("Q42"), probe("Q43"), identity("Q44"), probe("Q45"),
        none("Q46", "Other apps recording silence after a call is only visible in another app; a report is the only signal."),
        probe("Q47"), setting("Q48"), probe("Q49"), probe("Q50"), probe("Q51"), probe("Q52"),
        // Q53-Q76: from the real-use review, the tracker and the support KB.
        PhoneRule("Q53", DetectionKind.SETTING, Severity.CALL_LOSS, applies = { p ->
            p.mode == PrivilegedMode.SHIZUKU && p.appCallsOn == Tri.NO && p.batteryExempt == Tri.NO &&
                p.sdk >= ANDROID_12
        }),
        // CALL_LOSS: proven on the A14/A17 emulators to stop recording in Shizuku + app calls off. It bites only after
        // the unused period (about 90 days unopened), so a phone showing it today is still recording.
        PhoneRule("Q54", DetectionKind.SETTING, Severity.CALL_LOSS, applies = { it.hibernation == Tri.YES }),
        PhoneRule("Q55", DetectionKind.SETTING, Severity.CALL_LOSS, applies = { it.batteryExempt == Tri.NO }),
        PhoneRule("Q56", DetectionKind.SETTING, Severity.CALL_LOSS, applies = { it.backgroundRestricted == Tri.YES }),
        PhoneRule("Q57", DetectionKind.SETTING, Severity.RISK, applies = { it.standbyBucket == StandbyBucketClass.RESTRICTED }),
        identity("Q58"), identity("Q59"), identity("Q60"), identity("Q61"), identity("Q62"),
        probe("Q63"), setting("Q64"), probe("Q65"), probe("Q66"), identity("Q67"), probe("Q68"),
        none("Q69", "Cause unknown (wired headphones on app calls, vivo A17); the report logs the route, nothing to probe yet."),
        PhoneRule("Q70", DetectionKind.SETTING, Severity.CALL_LOSS, applies = {
            it.notificationsEnabled == Tri.NO || it.blockedChannels.isNotEmpty()
        }),
        none("Q71", "A RAM ceiling or phantom-process kill is visible only as the recorder dying, which is a symptom not a cause."),
        probe("Q72"), probe("Q73"), setting("Q74"), probe("Q75"),
        none("Q76", "Other apps' debugging checks cannot be seen from CallVault; answered in the FAQ only."),
        // Unclear items.
        none("U1", "Whether Honor kills the :drive copier on its own is not shown; waits for a report."),
        none("U2", "No evidence the system-context binder fallback was ever used."),
        identity("U3"),
        none("U4", "Unclear whether the call-evidence gate is a quirk handler with a gap; waits for a report."),
        none("U5", "Hidden-API constant fallback; no device evidence either way."),
        probe("U6"),
        setting("U7"),
        none("U8", "Superseded by Q68."),
    )

    val ids: Set<String> = entries.map { it.id }.toSet()

    /** The rules whose profile-based predicate holds for [profile], sorted by id. Pure; unknown facts never match. */
    fun evaluate(profile: PhoneProfile): List<PhoneRule> =
        entries.filter { rule -> rule.applies?.let { runCatching { it(profile) }.getOrDefault(false) } == true }
            .sortedBy { it.id }
}
