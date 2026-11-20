/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import com.baba.callvault.utils.AppLogger

/**
 * Builds a part a long-lived service can do without, or returns null and logs why when it cannot be built.
 *
 * Errors count too: on Android 11 the app-call detector threw NoClassDefFoundError from its constructor (2026-11-20). One
 * use of it sat inside a runCatching, but the next plain call threw again and took the keep-alive down, and with it the
 * whole app, on every start. Built once through here, a part that fails stays absent instead of failing at each use.
 * [onFailure] is for the caller's always-on record ([UnavailablePartLog]); the AppLogger line needs debug logging on.
 */
internal inline fun <T : Any> buildOptionalPart(
    tag: String,
    what: String,
    onFailure: (Throwable) -> Unit = {},
    build: () -> T,
): T? =
    runCatching(build)
        .onFailure {
            AppLogger.e(tag, "$what is unavailable: it could not be created", it)
            onFailure(it)
        }
        .getOrNull()
