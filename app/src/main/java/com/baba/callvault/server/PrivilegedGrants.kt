/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Grants the app needs that only a shell-uid process can make: app ops, and roles.
 *
 * Runs inside [RecorderServiceImpl], so it works identically whether that was started by our own ADB
 * daemon or by Shizuku — both are uid 2000. Modelled on ShizuCallRecorder's `ShellCommandExecutor`,
 * which does the same three things, and like theirs it needs **no root**: everything here is in the
 * shell user's own permission set. Verified as shell on an emulator, 2026-08-24.
 *
 * **Every grant is read back rather than believed.** Measured that day:
 *
 *  - `appops set --user 0 <pkg> <op> allow` — works.
 *  - `appops set --user 0 --uid <uid> <op> allow` — **exits 0 and does nothing** when the op backs a
 *    runtime permission (`RECORD_AUDIO`, `READ_PHONE_STATE`, …); the system logs "Ignored setUidMode
 *    call for runtime permission app op". Not offered here at all: an API whose success means nothing
 *    is worse than no API. Upstream offers it and documents it as taking priority, which was true on
 *    older releases and is now a trap.
 *  - `cmd role add-role-holder --user 0 <role> <pkg>` — works, but refuses a package that does not
 *    *qualify* for the role. CallVault is refused `android.app.role.DIALER` with "not qualified …
 *    missing RequiredComponent … action='android.intent.action.DIAL'" because it declares no dialer
 *    component. **Root would not change that**, and neither would Shizuku: it is a statement about the
 *    manifest, not about privilege.
 *
 * Some OEMs block these outright — ColorOS refuses `appops set` from shell — so a false answer here
 * must stay false rather than become an optimistic true.
 */
object PrivilegedGrants {

    private const val TAG = "CV:Grants"

    /** Absolute for the same reason as the daemon's pgrep: privileged context, inherited PATH. */
    private const val APPOPS = "/system/bin/appops"
    private const val CMD = "/system/bin/cmd"

    /** Long enough for a slow `cmd` round trip, short enough not to hang a binder call. */
    private const val TIMEOUT_SEC = 10L

    /**
     * Allows [opName] for [packageName], and returns whether it is allowed **afterwards**.
     *
     * The package-level op only. See the class note for why the uid-level variant is not offered.
     */
    fun grantAppOp(packageName: String, opName: String, userId: Int): Boolean {
        run(APPOPS, "set", "--user", userId.toString(), packageName, opName, "allow")
        val after = run(APPOPS, "get", "--user", userId.toString(), packageName, opName)
        val allowed = GrantOutput.appOpAllowed(after.output, opName)
        AppLogger.i(TAG, "grantAppOp($packageName, $opName) -> $allowed")
        return allowed
    }

    /**
     * Makes [packageName] a holder of [roleName], and returns whether it holds it **afterwards**.
     *
     * A false here usually means the app does not qualify for the role rather than that the grant was
     * forbidden; the reason lands in logcat under the `Role` tag, and is worth reading before assuming
     * a privilege problem.
     */
    fun grantRole(roleName: String, packageName: String, userId: Int): Boolean {
        run(CMD, "role", "add-role-holder", "--user", userId.toString(), roleName, packageName)
        val after = run(CMD, "role", "get-role-holders", "--user", userId.toString(), roleName)
        val holds = GrantOutput.holdsRole(after.output, packageName)
        AppLogger.i(TAG, "grantRole($roleName, $packageName) -> $holds")
        return holds
    }

    /** A component name as `cmd notification` takes it; refuses anything with a space, quote or `;`. */
    private val COMPONENT = Regex("""[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+""")

    /**
     * Approves or removes [component] as a notification listener, as `cmd notification allow_listener`
     * does. The secure setting `enabled_notification_listeners` is NOT enough on Android 14: the system
     * only reads it when it migrates its own list, so writing it from the app changed the setting and
     * bound nothing (measured on the OP9, 2026-11-17). Returns what the command answered, for the log.
     */
    fun setNotificationListener(component: String, allow: Boolean, userId: Int): String {
        if (!COMPONENT.matches(component)) return "refused: not a component name"
        val r = run(CMD, "notification", if (allow) "allow_listener" else "disallow_listener", component, userId.toString())
        return "exit=${r.exitCode} ${r.output.trim().take(120)}"
    }

    private data class Result(val exitCode: Int, val output: String)

    /**
     * Runs a command and collects stdout+stderr.
     *
     * The exit code is captured for the log but never used as the answer — see the class note. A
     * command that hangs is killed rather than allowed to block the binder thread it was called on.
     */
    private fun run(vararg command: String): Result = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!finished) {
            AppLogger.w(TAG, "'${command.joinToString(" ")}' did not finish in ${TIMEOUT_SEC}s; killing it")
            process.destroyForcibly()
            return@runCatching Result(exitCode = -1, output = output)
        }
        val exit = process.exitValue()
        if (exit != 0) {
            AppLogger.w(TAG, "'${command.joinToString(" ")}' exited $exit: ${output.trim().take(200)}")
        }
        Result(exit, output)
    }.getOrElse {
        AppLogger.w(TAG, "'${command.joinToString(" ")}' could not run: ${it.message}")
        Result(exitCode = -1, output = "")
    }
}
