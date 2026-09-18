/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Where a Shizuku manager keeps the binary that starts its server.
 *
 * Shizuku v13 ships its starter as a *native library* — `lib/<abi>/libshizuku.so` inside the APK's
 * install directory — and its own manager starts the server by running exactly that file from a shell.
 * There is no `start.sh` on the sdcard any more, so this is the only path there is. Verified on the OP9
 * on 2026-09-18: running it printed `info: shizuku_server pid is 23611` and the server was up in under a
 * second.
 *
 * **Nothing here knows a package name.** The package comes from
 * [com.baba.callvault.server.ShizukuBackend.managerPackage], which resolves it from the permission the
 * manager declares — a fork's stealth mode renames the package and Sui installs no app at all, so a
 * fixed name is the one thing about Shizuku that is never dependable.
 *
 * Kept free of `Context` so every branch is tested; the caller checks which candidate actually exists.
 */
object ShizukuStarterPaths {

    /** The file Shizuku's own manager runs. A fork that renames it is handled by finding nothing. */
    const val STARTER = "libshizuku.so"

    /**
     * ABI → the directory name the installer uses for it inside `lib/`. Only a fallback: when the
     * package manager answers at all it hands us `nativeLibraryDir`, which already names the one ABI
     * Android picked for that install. This list exists for the phone that ships several and where we
     * are left deriving the path from the APK location alone.
     */
    private val ABI_DIRS = mapOf(
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "arm",
        "armeabi" to "arm",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )

    /**
     * Every path worth trying, best first, for a manager whose install the package manager describes as
     * [nativeLibraryDir] / [apkPath].
     *
     * Returns an empty list rather than a guess when neither input is usable. That is the point: a fork
     * whose layout differs has to fail into "could not start it" and a notification the user can act on,
     * never into running some path we made up.
     *
     * @param apkPath either an `ApplicationInfo.sourceDir` or a line of `pm path` output, which carries a
     *   `package:` prefix. Split APKs give several lines; the caller passes one.
     */
    fun candidates(nativeLibraryDir: String?, apkPath: String?, abis: List<String>): List<String> {
        val fromNativeDir = nativeLibraryDir?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "${it.trimEnd('/')}/$STARTER" }

        val installDir = apkPath?.trim()
            ?.removePrefix("package:")
            ?.substringBeforeLast('/', "")
            ?.takeIf { it.isNotEmpty() }
        val fromApkDir = installDir
            ?.let { dir -> abis.mapNotNull { ABI_DIRS[it] }.distinct().map { "$dir/lib/$it/$STARTER" } }
            .orEmpty()

        return (listOfNotNull(fromNativeDir) + fromApkDir).distinct()
    }

    /**
     * Whether [path] is safe to hand to a shell inside single quotes.
     *
     * Install paths are base64-ish (`~~Q5MOm…==`) and never contain a quote, so this only ever rejects
     * something that has already gone wrong — but the hazard it prevents is the serious one: a quote in
     * the path would close our quoting and everything after it would be run as a command of its own, on
     * a *privileged* shell, from a string another app controls.
     */
    fun isShellSafe(path: String): Boolean =
        path.isNotBlank() && path.startsWith('/') && path.none { it == '\'' || it == '\n' || it == '\r' }
}
