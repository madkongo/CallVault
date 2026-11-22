/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.baba.callvault.utils.AppLogger
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

/**
 * Runs inside the recorder daemon: sends the daemon's binder to the app again whenever the app's process starts
 * (BabApps item 115).
 *
 * Without it the binder is delivered once, when the daemon is launched. If the app is stopped (Active apps -> Stop)
 * the daemon survives, but the next call starts a new app process that has no binder, so it killed the live daemon
 * and launched another over ADB, which cannot happen away from Wi-Fi: the call was lost with a recorder running.
 *
 * That event fires only when the app's uid goes from idle to active. It does NOT fire when the main process is killed
 * and restarted while another process of the app (`:drive`) keeps the uid active: on the OnePlus 9 (2026-11-22) the app
 * was killed mid-call and restarted for a service, no event came, and the restarted app killed the recording daemon.
 * So the daemon also watches the app's provider binder (the provider lives in the main process) and, when it dies
 * while recording, delivers again after a short wait, with retries ([AppDeathRedelivery]). A delivery starts the app's
 * process itself. Idle daemons do not do this: they must not restart an app the user stopped.
 *
 * Mirrors Shizuku's `BinderSender` (RikkaApps/Shizuku, server/.../BinderSender.java): the server registers an
 * activity-manager uid observer and, when the client app's uid becomes active, calls its provider's `sendBinder`.
 * Shizuku subclasses the hidden `IUidObserver.Stub`; this runs from a bare `app_process` with no hidden-API stubs, so
 * the observer is a [Binder] answering the interface's one transaction we asked for (see [register]) and a [Proxy]
 * of the interface that hands the activity manager that binder. Everything is reflection, and any failure only means
 * the app falls back to launching over ADB, as it did before.
 */
internal object AppStartBinderSender {

    private const val TAG = "CV:RecorderServer"
    private const val OBSERVER_DESCRIPTOR = "android.app.IUidObserver"

    /** ActivityManager.UID_OBSERVER_ACTIVE: ask only for "a uid became active", so every call we get carries a uid first. */
    private const val UID_OBSERVER_ACTIVE_FALLBACK = 1 shl 3

    /** ActivityManager.PROCESS_STATE_UNKNOWN: no process-state cut point. */
    private const val NO_CUTPOINT = -1

    /** A hand-over starts the app's process itself, which fires the observer again; ignore that echo. */
    private const val ECHO_WINDOW_MS = 4_000L

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cv-binder-sender").apply { isDaemon = true } }

    @Volatile
    private var lastSentAt = 0L

    @Volatile
    private var watchedProvider: IBinder? = null

    private val appDeathRecipient = IBinder.DeathRecipient { onAppDied() }

    @Volatile
    private var deathContext: DeathContext? = null

    private class DeathContext(val binder: IBinder, val authority: String, val apkPath: String, val isRecording: () -> Boolean)

    private fun startDeathWatch(binder: IBinder, authority: String, apkPath: String, isRecording: () -> Boolean) {
        deathContext = DeathContext(binder, authority, apkPath, isRecording)
        BinderDelivery.onProviderBinder = ::watchProvider
        BinderDelivery.lastProviderBinder?.let(::watchProvider)
    }

    /** Links to the death of the app's provider binder (once per binder); a delivery after a restart brings a new one. */
    private fun watchProvider(provider: IBinder) {
        synchronized(this) {
            if (watchedProvider === provider) return
            runCatching { watchedProvider?.unlinkToDeath(appDeathRecipient, 0) }
            try {
                provider.linkToDeath(appDeathRecipient, 0)
                watchedProvider = provider
                AppLogger.i(TAG, "Watching the app's provider binder: if the app dies while this daemon records, it is handed the binder again")
            } catch (t: Throwable) {
                watchedProvider = null
                AppLogger.w(TAG, "Could not watch the app's provider binder (${t.message}); the app is not handed the binder again after its death")
            }
        }
    }

    @Volatile
    private var lastClientCallAt = 0L

    /** Called for every transaction an app process makes on this daemon's binder. */
    fun noteClientCall() {
        lastClientCallAt = android.os.SystemClock.elapsedRealtime()
    }

    private var deathCycles = 0
    private var lastCycleAt: Long? = null

    @Volatile
    private var loopGeneration = 0

    private fun onAppDied() {
        val ctx = deathContext ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val recording = runCatching { ctx.isRecording() }.getOrDefault(false)
        val generation: Int
        synchronized(this) {
            watchedProvider = null
            if (!recording) { deathCycles = 0; lastCycleAt = null }
            generation = ++loopGeneration
        }
        if (!recording) {
            AppLogger.i(TAG, "The app's process died; idle, leaving the app alone")
            return
        }
        val sinceLast = lastCycleAt?.let { now - it }
        if (!AppDeathRedelivery.mayStartCycle(deathCycles, sinceLast)) {
            AppLogger.w(TAG, "The app's process died while recording; NOT restarting it again (cycles=$deathCycles, ${sinceLast ?: "-"}ms since the last): " +
                "an app killer or a crash loop would be fed. The recording continues unowned until the app comes back on its own")
            return
        }
        deathCycles++
        lastCycleAt = now
        AppLogger.i(TAG, "The app's process died while recording; handing the binder over again (cycle $deathCycles of ${AppDeathRedelivery.MAX_CYCLES})")
        Thread({ redeliverLoop(ctx, generation, now) }, "cv-redeliver").apply { isDaemon = true }.start()
    }

    /** Offers the binder again until an app call proves it holds it, the recording ends, or the attempts run out. */
    private fun redeliverLoop(ctx: DeathContext, generation: Int, diedAt: Long) {
        var attempt = 0
        while (generation == loopGeneration) {
            val clientSeen = lastClientCallAt >= diedAt
            val stillRecording = runCatching { ctx.isRecording() }.getOrDefault(false)
            val wait = AppDeathRedelivery.nextDelayMs(attempt, stillRecording, clientSeen) ?: break
            runCatching { Thread.sleep(wait) }
            if (generation != loopGeneration || lastClientCallAt >= diedAt) continue
            val delivered = runCatching { BinderDelivery.redeliverBinderToApp(ctx.binder, ctx.authority, ctx.apkPath) }
                .onFailure { AppLogger.w(TAG, "Re-delivery after the app's death threw: ${it.message}") }
                .getOrDefault(false)
            lastSentAt = android.os.SystemClock.elapsedRealtime()
            AppLogger.i(TAG, "Re-delivery after the app's death, attempt ${attempt + 1} of ${AppDeathRedelivery.MAX_ATTEMPTS} " +
                "(after ${wait}ms): provider replied=$delivered (not proof the app took it)")
            attempt++
        }
        val held = lastClientCallAt >= diedAt
        val recording = runCatching { ctx.isRecording() }.getOrDefault(false)
        if (generation == loopGeneration) {
            if (held) AppLogger.i(TAG, "Re-delivery finished: the app is calling this daemon again (after $attempt attempt(s))")
            else if (recording) AppLogger.w(TAG, "Re-delivery gave up after $attempt attempt(s): the app has not called this daemon and it is STILL RECORDING with no owner")
            else AppLogger.i(TAG, "Re-delivery stopped: not recording any more (after $attempt attempt(s))")
        }
    }

    /** Keeps the observer from being collected: the activity manager only holds a weak reference to our side. */
    @Volatile
    private var observer: Binder? = null

    /**
     * Starts watching. Returns whether the observer was registered; the outcome is logged either way, so the log
     * says whether this daemon can hand its binder over again.
     */
    fun register(binder: IBinder, authority: String, apkPath: String, isRecording: () -> Boolean): Boolean = try {
        startDeathWatch(binder, authority, apkPath, isRecording)
        registerUidObserver(binder, authority, apkPath)
    } catch (t: Throwable) {
        AppLogger.w(TAG, "Cannot watch the app: ${t.message}", t)
        false
    }

    private fun registerUidObserver(binder: IBinder, authority: String, apkPath: String): Boolean = try {
        val appUid = resolveAppUid(authority)
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
            ?: error("ActivityManager.getService() returned null")
        val activeCode = runCatching {
            Class.forName("$OBSERVER_DESCRIPTOR\$Stub").getDeclaredField("TRANSACTION_onUidActive")
                .apply { isAccessible = true }.getInt(null)
        }.onFailure { AppLogger.w(TAG, "Cannot resolve the onUidActive transaction code; any observer call counts: ${it.message}") }
            .getOrNull()
        val observerBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                // Only onUidActive(int uid) is subscribed, and every IUidObserver call starts with the uid.
                return try {
                    data.enforceInterface(OBSERVER_DESCRIPTOR)
                    val uid = data.readInt()
                    val isActive = AppDeathRedelivery.isActiveTransaction(code, activeCode)
                    // Other apps' uids are not logged; ours, or a code that is not "active", is.
                    if (uid == appUid || !isActive) AppLogger.i(TAG, "uid observer call: code=$code uid=$uid (active code=$activeCode)")
                    if (isActive && uid == appUid) onAppActive(binder, authority, apkPath)
                    true
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "uid observer call ignored: ${t.message}")
                    super.onTransact(code, data, reply, flags)
                }
            }
        }
        val observerType = Class.forName(OBSERVER_DESCRIPTOR)
        // The activity manager's proxy only asks the argument for asBinder(), so a Proxy of the interface will do.
        val observerProxy = Proxy.newProxyInstance(observerType.classLoader, arrayOf(observerType)) { _, method, _ ->
            if (method.name == "asBinder") observerBinder else null
        }
        val flags = runCatching { Class.forName("android.app.ActivityManager").getField("UID_OBSERVER_ACTIVE").getInt(null) }
            .getOrDefault(UID_OBSERVER_ACTIVE_FALLBACK)
        val register = am.javaClass.methods.firstOrNull { it.name == "registerUidObserver" && it.parameterTypes.size == 4 }
            ?: error("registerUidObserver(IUidObserver,int,int,String) not found")
        register.invoke(am, observerProxy, flags, NO_CUTPOINT, "com.android.shell")
        observer = observerBinder
        AppLogger.i(TAG, "Will hand the binder to the app again whenever its process starts (uid=$appUid)")
        true
    } catch (t: Throwable) {
        AppLogger.w(TAG, "Cannot watch for the app's process start; after the app is stopped this recorder cannot be reused: ${t.message}", t)
        false
    }

    private fun onAppActive(binder: IBinder, authority: String, apkPath: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSentAt < ECHO_WINDOW_MS) return
        lastSentAt = now
        worker.execute {
            val ok = runCatching { BinderDelivery.redeliverBinderToApp(binder, authority, apkPath) }.getOrDefault(false)
            AppLogger.i(TAG, "App process started: handed the binder over again, delivered=$ok")
        }
    }

    /** The uid of the app that owns [authority], from the package manager: the one uid whose start we care about. */
    private fun resolveAppUid(authority: String): Int {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val pmBinder = serviceManager.getMethod("getService", String::class.java).invoke(null, "package") as? IBinder
            ?: error("no package service")
        val pm = Class.forName("android.content.pm.IPackageManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, pmBinder)
        val resolve = pm.javaClass.methods.firstOrNull { it.name == "resolveContentProvider" && it.parameterTypes.size == 3 }
            ?: error("resolveContentProvider not found")
        // The flags argument is an int up to Android 12 and a long from 13.
        val flags: Any = if (resolve.parameterTypes[1] == java.lang.Long.TYPE) 0L else 0
        val info = resolve.invoke(pm, authority, flags, 0) ?: error("no provider for $authority")
        val appInfo = info.javaClass.getField("applicationInfo").get(info) ?: error("provider has no applicationInfo")
        return appInfo.javaClass.getField("uid").getInt(appInfo)
    }
}
