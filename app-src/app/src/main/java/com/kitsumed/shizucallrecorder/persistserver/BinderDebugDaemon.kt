/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.kitsumed.shizucallrecorder.persistserver

import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import com.kitsumed.shizucallrecorder.utils.AppLogger
import java.io.File
import java.io.FileWriter

/**
 * THROWAWAY de-risk spike (CallVault Plan 5, Task 0c) — proves the BINDER COMMAND CHANNEL.
 *
 * A bare `app_process` entrypoint (shell uid 2000, NO Android Activity) launched DETACHED exactly
 * like the 0a/0b daemons (`setsid ... CLASSPATH=<apk> app_process / <fqcn>`), so it survives Wireless
 * debugging being turned OFF. It exposes an [IPersistDebugService] over binder and PUSHES that binder
 * to the app's exported provider (authority [RecorderBinderDebugProvider.AUTHORITY]) the same way the
 * Shizuku server pushes its binder to a client app.
 *
 * What it proves with the app side:
 *  • risk #3 — a non-app process (shell uid) can deliver a usable IBinder to an app provider via
 *    `call("sendBinder", Bundle{BinderContainer})` (SELinux shell->app provider allowed).
 *  • risk #4 — the app can then call BACK over that binder with NO ADB (even while WD is OFF) and pass
 *    a [ParcelFileDescriptor] the shell-uid daemon writes into (the app holds the file grant we lack).
 *
 * Binder-delivery mechanism (mirrored from Shizuku):
 *   PRIMARY  — RikkaApps/Shizuku — server/.../ShizukuService.java `sendBinderToUserApp(...)`:
 *                provider = ActivityManagerApis.getContentProviderExternal(name, userId, token=null, tag);
 *                extra.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
 *                IContentProviderUtils.callCompat(provider, null, name, "sendBinder", null, extra);
 *                ... finally ActivityManagerApis.removeContentProviderExternal(name, token);
 *              The IContentProvider.call version matrix is mirrored from
 *              RikkaApps/Shizuku — starter/.../IContentProviderCompat.java (SDK 31+ AttributionSource).
 *   FALLBACK — ActivityThread.systemMain().getSystemContext().getContentResolver()
 *                .call(Uri "content://<authority>", "sendBinder", null, bundle).
 * Everything hidden-API is reached by REFLECTION so this compiles against the public SDK.
 *
 * Status evidence (durable; the driver reads it AFTER WD is toggled back on, since ADB/logcat is dead
 * while WD is OFF) is appended to [STATUS_PATH] — START / DELIVERED / FATAL lines, like 0b.
 *
 * Args: none required. Optional `[authority]` overrides [RecorderBinderDebugProvider.AUTHORITY].
 */
@Keep
object BinderDebugDaemon {

    private const val TAG = "SCR:Binder"

    /** Durable evidence file the device-driver polls after WD-on (ADB/logcat dead while WD off). */
    private const val STATUS_PATH = "/data/local/tmp/cv_binder_status.txt"

    /** Mirrors ShizukuProvider.METHOD_SEND_BINDER. */
    private const val METHOD_SEND_BINDER = "sendBinder"

    /** Single user (user 0) on this device; mirrors Shizuku passing the resolved userId. */
    private const val USER_0 = 0

    /**
     * app_process entrypoint. Implements the stub, prepares a looper (binder threads + a usable
     * context need one), delivers the binder to the app provider, then loops until SIGTERM.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val pid = Process.myPid()
        val uid = Process.myUid()
        val authority = args.getOrNull(0) ?: RecorderBinderDebugProvider.AUTHORITY

        // Pre-detach logs: visible in the launching adb shell BEFORE the pipe closes; after detach
        // stdio is /dev/null. AppLogger.* still reaches logcat while WD is ON.
        println("BinderDebugDaemon starting pid=$pid uid=$uid authority=$authority")
        AppLogger.i(TAG, "BinderDebugDaemon starting pid=$pid uid=$uid authority=$authority")
        appendStatus("START ${System.currentTimeMillis()} pid=$pid uid=$uid authority=$authority\n")

        try {
            // A main looper is required: binder transactions are dispatched on it and the system-context
            // fallback's ContentResolver expects a thread with a Looper. Mirrors Shizuku's server thread.
            Looper.prepareMainLooper()

            val stub = createStub(pid, uid)
            appendStatus("STUB_READY ${System.currentTimeMillis()} pid=$pid\n")

            val delivered = deliverBinderToApp(stub, authority, pid)
            appendStatus("DELIVERED ok=$delivered ts=${System.currentTimeMillis()} pid=$pid\n")
            AppLogger.i(TAG, "Binder delivery finished ok=$delivered; entering Looper.loop() (waiting for app IPC)")

            // Keep the process + binder alive so the app can call us back over IPC (the real risk #4
            // test happens HERE, driven by the app, possibly while WD is OFF). Ends on SIGTERM.
            Looper.loop()
        } catch (t: Throwable) {
            runCatching {
                appendStatus("FATAL ${System.currentTimeMillis()} pid=$pid ${t.javaClass.simpleName}: ${t.message}\n")
            }
            AppLogger.e(TAG, "BinderDebugDaemon fatal: ${t.message}", t)
        }
    }

    /**
     * The daemon-side [IPersistDebugService] implementation. `ping` echoes our uid/pid; `writeToPfd`
     * writes [text] into the app-provided fd (the risk #4 proof); `myUid` returns our shell uid.
     */
    private fun createStub(pid: Int, uid: Int): IPersistDebugService.Stub =
        object : IPersistDebugService.Stub() {
            override fun ping(msg: String?): String {
                val reply = "pong from uid=${Process.myUid()} pid=${Process.myPid()} msg=$msg"
                AppLogger.i(TAG, "ping() called by app -> $reply")
                appendStatus("PING ${System.currentTimeMillis()} pid=$pid reply=$reply\n")
                return reply
            }

            override fun writeToPfd(pfd: ParcelFileDescriptor?, text: String?): Boolean {
                if (pfd == null) {
                    AppLogger.e(TAG, "writeToPfd: null pfd")
                    return false
                }
                val payload = (text ?: "")
                return runCatching {
                    // AutoCloseOutputStream takes ownership of the fd and closes it when done.
                    ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                        out.write(payload.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    AppLogger.i(TAG, "writeToPfd: wrote ${payload.length} bytes as uid=${Process.myUid()} (risk #4 PASS)")
                    appendStatus("WRITE_PFD ${System.currentTimeMillis()} pid=$pid bytes=${payload.length} uid=${Process.myUid()}\n")
                    true
                }.getOrElse {
                    AppLogger.e(TAG, "writeToPfd failed: ${it.message}", it)
                    appendStatus("WRITE_PFD_FAIL ${System.currentTimeMillis()} pid=$pid err=${it.message}\n")
                    false
                }
            }

            override fun myUid(): Int = Process.myUid()
        }

    /**
     * Delivers [stub] to the app's provider. Tries the Shizuku [getContentProviderExternal] path first
     * (the proven non-app->app provider mechanism), then the system-context ContentResolver fallback.
     *
     * @return true if either path got a non-null reply Bundle from the provider's `call`.
     */
    private fun deliverBinderToApp(stub: IPersistDebugService.Stub, authority: String, pid: Int): Boolean {
        val bundle = Bundle().apply {
            // Wrap the binder so it survives the provider hop; mirrors Shizuku's BinderContainer use.
            putParcelable(RecorderBinderDebugProvider.EXTRA_BINDER, BinderContainer(stub))
        }

        AppLogger.i(TAG, "Delivering binder: trying getContentProviderExternal path (authority=$authority)")
        appendStatus("DELIVER_TRY_AMS ${System.currentTimeMillis()} pid=$pid\n")
        if (runCatching { deliverViaActivityManager(authority, bundle) }
                .onFailure {
                    AppLogger.w(TAG, "getContentProviderExternal path failed: ${it.message}", it)
                    appendStatus("AMS_FAIL ${System.currentTimeMillis()} pid=$pid err=${it.message}\n")
                }.getOrDefault(false)
        ) {
            AppLogger.i(TAG, "Binder delivered via getContentProviderExternal (risk #3 PASS)")
            return true
        }

        AppLogger.i(TAG, "Falling back to system-context ContentResolver path")
        appendStatus("DELIVER_TRY_SYSCTX ${System.currentTimeMillis()} pid=$pid\n")
        return runCatching { deliverViaSystemContext(authority, bundle) }
            .onFailure {
                AppLogger.e(TAG, "system-context path failed: ${it.message}", it)
                appendStatus("SYSCTX_FAIL ${System.currentTimeMillis()} pid=$pid err=${it.message}\n")
            }.getOrDefault(false)
    }

    /**
     * PRIMARY delivery — mirrors `ShizukuService.sendBinderToUserApp`. All hidden APIs via reflection:
     *  1. IActivityManager am = ActivityManager.getService()
     *  2. holder = am.getContentProviderExternal(authority, userId, /*token*/ null, /*tag*/ authority)
     *  3. IContentProvider provider = holder.provider
     *  4. provider.call(<attribution per SDK>, authority, "sendBinder", null, bundle)
     *  5. finally am.removeContentProviderExternal(authority, /*token*/ null)
     */
    private fun deliverViaActivityManager(authority: String, bundle: Bundle): Boolean {
        // 1. IActivityManager via public-but-grey ActivityManager.getService().
        val am = Class.forName("android.app.ActivityManager")
            .getMethod("getService")
            .invoke(null) ?: throw IllegalStateException("ActivityManager.getService() returned null")
        val amClass = am.javaClass

        // 2. getContentProviderExternal(String name, int userId, IBinder token, String tag)
        // Signature stable since API 26; tag param added API 29 (Q) — we target Android 16, so the
        // 4-arg form applies. Resolve by walking declared methods to be resilient to obfuscation.
        val gcpe = amClass.methods.firstOrNull {
            it.name == "getContentProviderExternal" && it.parameterTypes.size == 4
        } ?: throw NoSuchMethodException("getContentProviderExternal(String,int,IBinder,String)")
        val holder = gcpe.invoke(am, authority, USER_0, null, authority)
            ?: throw IllegalStateException("getContentProviderExternal returned null holder for $authority")

        try {
            // 3. holder.provider : IContentProvider (field name "provider" on ContentProviderHolder).
            val provider = holder.javaClass.getField("provider").get(holder)
                ?: throw IllegalStateException("ContentProviderHolder.provider is null")

            // 4. provider.call(...) — version matrix from Shizuku's IContentProviderCompat.
            val reply = callProvider(provider, authority, METHOD_SEND_BINDER, bundle)
            AppLogger.i(TAG, "getContentProviderExternal call reply=${reply} (null=${reply == null})")
            return reply != null
        } finally {
            // 5. removeContentProviderExternal(String name, IBinder token) — best-effort, like Shizuku.
            runCatching {
                amClass.methods.firstOrNull {
                    it.name == "removeContentProviderExternal" && it.parameterTypes.size == 2
                }?.invoke(am, authority, null)
            }.onFailure { AppLogger.w(TAG, "removeContentProviderExternal failed: ${it.message}") }
        }
    }

    /**
     * Reflectively invokes `IContentProvider.call(...)` choosing the overload for the running SDK,
     * mirroring RikkaApps/Shizuku starter/.../IContentProviderCompat.java. On Android 16 (SDK 36) the
     * AttributionSource form is used. Returns the reply Bundle (or null).
     */
    private fun callProvider(provider: Any, authority: String, method: String, bundle: Bundle): Bundle? {
        val providerClass = provider.javaClass
        // SDK 31+ : call(AttributionSource, String authority, String method, String arg, Bundle extras)
        val attributionSource = buildAttributionSource()
        if (attributionSource != null) {
            val m = providerClass.methods.firstOrNull {
                it.name == "call" && it.parameterTypes.size == 5 &&
                    it.parameterTypes[0].name == "android.content.AttributionSource"
            }
            if (m != null) {
                return m.invoke(provider, attributionSource, authority, method, null, bundle) as? Bundle
            }
        }
        // SDK 30 : call(String callingPkg, String attributionTag, String authority, String method, String arg, Bundle)
        providerClass.methods.firstOrNull {
            it.name == "call" && it.parameterTypes.size == 6 && it.parameterTypes[0] == String::class.java
        }?.let { return it.invoke(provider, null, null, authority, method, null, bundle) as? Bundle }

        // SDK 29 : call(String callingPkg, String authority, String method, String arg, Bundle)
        providerClass.methods.firstOrNull {
            it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == String::class.java
        }?.let { return it.invoke(provider, null, authority, method, null, bundle) as? Bundle }

        throw NoSuchMethodException("No usable IContentProvider.call overload found")
    }

    /**
     * Builds an `AttributionSource` for SDK 31+ via reflection:
     *   new AttributionSource.Builder(Process.myUid()).setPackageName(null).build()
     * Returns null on older SDKs or if the class is unavailable (then the String overloads are tried).
     */
    private fun buildAttributionSource(): Any? = runCatching {
        val builderClass = Class.forName("android.content.AttributionSource\$Builder")
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType)
            .newInstance(Process.myUid())
        // setPackageName(String) -> Builder ; pass null (we are a shell uid with no package).
        builderClass.getMethod("setPackageName", String::class.java).invoke(builder, null)
        builderClass.getMethod("build").invoke(builder)
    }.getOrNull()

    /**
     * FALLBACK delivery — system-context ContentResolver:
     *   ActivityThread at = ActivityThread.systemMain();
     *   Context ctx = at.getSystemContext();
     *   ctx.getContentResolver().call(Uri "content://<authority>", "sendBinder", null, bundle)
     * Reflection used for the two hidden ActivityThread methods; the rest is public API.
     */
    private fun deliverViaSystemContext(authority: String, bundle: Bundle): Boolean {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val at = activityThreadClass.getMethod("systemMain").invoke(null)
            ?: throw IllegalStateException("ActivityThread.systemMain() returned null")
        val context = activityThreadClass.getMethod("getSystemContext").invoke(at) as? android.content.Context
            ?: throw IllegalStateException("getSystemContext() returned null")

        val uri = android.net.Uri.parse("content://$authority")
        val reply = context.contentResolver.call(uri, METHOD_SEND_BINDER, null, bundle)
        AppLogger.i(TAG, "system-context call reply=$reply (null=${reply == null})")
        return reply != null
    }

    /** Appends [line] to the status file and flushes immediately so it is durable across WD toggles. */
    private fun appendStatus(line: String) {
        runCatching {
            FileWriter(File(STATUS_PATH), /* append = */ true).use { w ->
                w.write(line)
                w.flush()
            }
        }
    }
}
