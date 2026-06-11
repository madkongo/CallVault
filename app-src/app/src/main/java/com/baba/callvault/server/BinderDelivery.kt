/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.Bundle
import android.os.IBinder
import android.os.Process
import com.baba.callvault.utils.AppLogger

/**
 * CallVault Plan 5 — PRODUCTION binder delivery from the privileged shell-uid daemon to the app's
 * exported [RecorderBinderProvider].
 *
 * Ported VERBATIM (logic-identical) from the proven spike, which itself mirrors Shizuku:
 *   persistserver/BinderDebugDaemon.deliverBinderToApp / deliverViaActivityManager / callProvider /
 *   buildAttributionSource / deliverViaSystemContext.
 *
 * Mechanism (mirrored from Shizuku):
 *   PRIMARY  — RikkaApps/Shizuku — server/.../ShizukuService.java `sendBinderToUserApp(...)`:
 *                holder = ActivityManagerApis.getContentProviderExternal(name, userId, token=null, tag);
 *                IContentProviderUtils.callCompat(holder.provider, null, name, "sendBinder", null, extra);
 *                finally ActivityManagerApis.removeContentProviderExternal(name, token);
 *              The IContentProvider.call version matrix is mirrored from
 *              RikkaApps/Shizuku — starter/.../IContentProviderCompat.java (SDK 31+ AttributionSource).
 *   FALLBACK — ActivityThread.systemMain().getSystemContext().getContentResolver()
 *                .call(Uri "content://<authority>", "sendBinder", null, bundle).
 * Everything hidden-API is reached by REFLECTION so this compiles against the public SDK.
 */
internal object BinderDelivery {

    private const val TAG = "CV:RecorderServer"

    /** Mirrors ShizukuProvider.METHOD_SEND_BINDER. */
    private const val METHOD_SEND_BINDER = "sendBinder"

    /** Single user (user 0) on this device; mirrors Shizuku passing the resolved userId. */
    private const val USER_0 = 0

    /**
     * Delivers [binder] to the app's provider at [authority]. Tries the Shizuku
     * [getContentProviderExternal] path first (the proven non-app->app provider mechanism), then the
     * system-context ContentResolver fallback. Safe to call repeatedly (re-delivery is harmless).
     *
     * @return true if either path got a non-null reply Bundle from the provider's `call`.
     */
    fun deliverBinderToApp(binder: IBinder, authority: String): Boolean {
        val bundle = Bundle().apply {
            // Wrap the binder so it survives the provider hop; mirrors Shizuku's BinderContainer use.
            putParcelable(RecorderBinderProvider.EXTRA_BINDER, BinderContainer(binder))
        }

        AppLogger.i(TAG, "Delivering binder: trying getContentProviderExternal path (authority=$authority)")
        if (runCatching { deliverViaActivityManager(authority, bundle) }
                .onFailure { AppLogger.w(TAG, "getContentProviderExternal path failed: ${it.message}", it) }
                .getOrDefault(false)
        ) {
            AppLogger.i(TAG, "Binder delivered via getContentProviderExternal")
            return true
        }

        AppLogger.i(TAG, "Falling back to system-context ContentResolver path")
        return runCatching { deliverViaSystemContext(authority, bundle) }
            .onFailure { AppLogger.e(TAG, "system-context path failed: ${it.message}", it) }
            .getOrDefault(false)
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
            AppLogger.i(TAG, "getContentProviderExternal call reply=$reply (null=${reply == null})")
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
}
