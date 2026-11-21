/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package com.baba.callvault.system.health

import android.content.Context
import com.baba.callvault.R
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.data.RecordingState
import com.baba.callvault.system.LocalizedContext
import com.baba.callvault.utils.AppLogger

/**
 * Posts, updates and cancels the one "Calls may not be recorded" notice (R5), following
 * [RecordingRiskNoticePolicy]. Never changes a setting. Every outcome and every failure goes to the always-on log;
 * a failure never reaches the caller, which is the keep-alive tick and the boot receiver.
 */
object RecordingRiskNotice {

    private const val TAG = "CV:RiskCheck"
    private const val PREFS = "cv_risk_notice"
    private const val KEY_CHECK_AT = "check_at"
    private const val KEY_POSTED_AT = "posted_at"
    private const val KEY_SET = "posted_set"

    enum class Trigger {
        /** The keep-alive tick: acts at most once a day. */
        TICK,

        /** The phone has just started: always checks. */
        BOOT,

        /** Home was opened: only takes a notice down that no longer applies, never posts. */
        HOME,
    }

    /** Runs one evaluation and returns what it did (also logged). Never throws. */
    fun evaluate(
        context: Context,
        trigger: Trigger,
        now: Long = System.currentTimeMillis(),
        reasons: List<RiskReason>? = null,
    ): String {
        val outcome = try {
            run(context, trigger, now, reasons)
        } catch (e: Exception) {
            "failed: ${e.javaClass.simpleName}: ${e.message}"
        }
        // The tick runs every minute and is silent unless it did something.
        if (!outcome.startsWith("not due") && !outcome.startsWith("idle")) {
            val line = "Risk notice (${trigger.name.lowercase()}): $outcome"
            if (outcome.startsWith("failed")) AppLogger.w(TAG, line) else AppLogger.i(TAG, line)
        }
        return outcome
    }

    private fun run(context: Context, trigger: Trigger, now: Long, given: List<RiskReason>?): String {
        val state = load(context)
        when (trigger) {
            Trigger.TICK -> if (!RecordingRiskNoticePolicy.isCheckDue(state, now, atBoot = false)) return "not due: checked less than a day ago"
            Trigger.HOME -> if (state.lastPostedSet.isEmpty()) return "idle: no notice is up"
            Trigger.BOOT -> Unit
        }
        val all = given ?: RecordingRiskReasons.read(context)
        val eligible = RecordingRiskReasons.forNotice(all)
        val recordingOff = runCatching { AppPreferences(context).getRecordingState() == RecordingState.STOPPED }.getOrDefault(false)
        val decision = RecordingRiskNoticePolicy.decide(state, now, eligible, recordingOff)
        // Home only takes a stale notice down; anything else it leaves to the daily check.
        if (trigger == Trigger.HOME && decision !is RecordingRiskNoticePolicy.Decision.Cancel) return "idle: notice still applies"
        val names = eligible.joinToString(",") { it.name }.ifEmpty { "none" }
        return when (decision) {
            is RecordingRiskNoticePolicy.Decision.Post -> {
                val shown = post(context, decision.reasons)
                if (!shown) {
                    save(context, RecordingRiskNoticePolicy.nextState(state, RecordingRiskNoticePolicy.Decision.Skip("not shown"), now))
                    "skipped: could not be shown, will try at the next check (reasons=$names)"
                } else {
                    save(context, RecordingRiskNoticePolicy.nextState(state, decision, now))
                    (if (decision.updating) "updated" else "posted") + " (reasons=$names)"
                }
            }
            is RecordingRiskNoticePolicy.Decision.Cancel -> {
                SilentFailureNotifier.clearRecordingAtRisk(context)
                save(context, RecordingRiskNoticePolicy.nextState(state, decision, now))
                "cancelled: ${decision.why} (reasons=$names)"
            }
            is RecordingRiskNoticePolicy.Decision.Skip -> {
                save(context, RecordingRiskNoticePolicy.nextState(state, decision, now))
                "skipped: ${decision.why} (reasons=$names)"
            }
        }
    }

    private fun post(context: Context, reasons: List<RiskReason>): Boolean {
        val localized = LocalizedContext.of(context)
        return SilentFailureNotifier.warnRecordingAtRisk(
            context,
            title = localized.getString(R.string.risk_notice_title),
            text = RecordingRiskNoticePolicy.noticeText(reasons.map { localized.getString(it.shortTextRes) }),
            actionLabel = localized.getString(R.string.risk_notice_action),
        )
    }

    private fun load(context: Context): RecordingRiskNoticePolicy.State {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RecordingRiskNoticePolicy.State(
            lastCheckAt = p.getLong(KEY_CHECK_AT, 0L),
            lastPostedAt = p.getLong(KEY_POSTED_AT, 0L),
            lastPostedSet = RecordingRiskNoticePolicy.parseSet(p.getString(KEY_SET, null)),
        )
    }

    private fun save(context: Context, state: RecordingRiskNoticePolicy.State) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CHECK_AT, state.lastCheckAt)
            .putLong(KEY_POSTED_AT, state.lastPostedAt)
            .putString(KEY_SET, RecordingRiskNoticePolicy.formatSet(state.lastPostedSet))
            .apply()
    }
}
