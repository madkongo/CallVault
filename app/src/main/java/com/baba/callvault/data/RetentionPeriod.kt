/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data

import androidx.annotation.StringRes
import com.baba.callvault.R

/**
 * Preset retention periods offered in Settings. [days] is the cutoff in days; [FOREVER] (0) means
 * "keep forever" (retention OFF). The stored preference is the raw [days] int, so the set of presets
 * can change without a data migration.
 *
 * @property days     Delete recordings older than this many days; 0 = keep forever.
 * @property labelRes The user-facing cadence label (e.g. "Monthly (30 days)").
 */
enum class RetentionPeriod(val days: Int, @StringRes val labelRes: Int) {
    FOREVER(0, R.string.retention_keep_forever),
    DAILY(1, R.string.retention_daily),
    WEEKLY(7, R.string.retention_weekly),
    BIWEEKLY(14, R.string.retention_biweekly),
    MONTHLY(30, R.string.retention_monthly);

    companion object {
        /** The preset matching [days], or [FOREVER] when [days] is 0 or not a known preset. */
        fun fromDays(days: Int): RetentionPeriod = entries.firstOrNull { it.days == days } ?: FOREVER
    }
}
