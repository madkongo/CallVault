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

    private class DeathContext(
        val binder: IBinder, val authority: String, val apkPath: String,
        val isRecording: () -> Boolean, val stopRecording: () -> Unit,
    )

    private fun startDeathWatch(binder: IBinder, authority: String, apkPath: String, isRecording: () -> Boolean, stopRecording: () -> Unit) {
        deathContext = DeathContext(binder, authority, apkPath, isRecording, stopRecording)
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

    private val clientSignals = ClientSignals()

    /** Wall-clock time this daemon last finished a recording itself (self-stop), or 0. Read through [selfStopFact]. */
    @Volatile private var selfStopAtMillis = 0L

    /** The dump the app queries ("self_stop"): "self_stop_at=<ms>" or "none". */
    fun selfStopFact(): String = selfStopAtMillis.takeIf { it > 0L }?.let { "self_stop_at=$it" } ?: "none"

    /** Called for every transaction an app process makes on this daemon's binder: the app holds the binder. */
    fun noteClientCall() = clientSignals.noteHold(android.os.SystemClock.elapsedRealtime())

    /** Called by a start that won the recording latch or a stop that tore a session down (never by a refused call, nor by the handoff calls: they never touch the daemon's session): the app owns it. */
    fun noteOwnerCall() = clientSignals.noteOwner(android.os.SystemClock.elapsedRealtime())

    private val restartCycles = RestartCycles()

    /** A new recording session starts the restart budget over (the cap is per session, not per daemon lifetime). */
    fun noteSessionStarted() = restartCycles.onSessionStarted()

    @Volatile
    private var loopGeneration = 0

    private fun onAppDied() {
        val ctx = deathContext ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val recording = runCatching { ctx.isRecording() }.getOrDefault(false)
        val generation: Int
        val mayRestart: Boolean
        synchronized(this) {
            watchedProvider = null
            generation = ++loopGeneration
            mayRestart = recording && restartCycles.tryStart(now)
        }
        if (!recording) {
            AppLogger.i(TAG, "The app's process died; idle, leaving the app alone")
            return
        }
        if (mayRestart) {
            AppLogger.i(TAG, "The app's process died while recording; handing the binder over again (cycle ${restartCycles.count()} of ${AppDeathRedelivery.MAX_CYCLES})")
        } else {
            AppLogger.w(TAG, "The app's process died while recording; NOT restarting it again (cycles=${restartCycles.count()}): " +
                "an app killer or a crash loop would be fed. The recording continues unowned; this daemon will finish it when the call ends")
        }
        Thread({ watchLoop(ctx, generation, now, mayRestart) }, "cv-orphan-watch").apply { isDaemon = true }.start()
    }

    /**
     * One thread per app death while recording; each tick is [OrphanWatch.step]. It finishes the recording itself once
     * the call is over and no app has attached (the trailer is written, the app's cut-off rescue publishes it later) and
     * offers the binder again on the [AppDeathRedelivery] schedule, never to a force-stopped app.
     */
    private fun watchLoop(ctx: DeathContext, generation: Int, diedAt: Long, mayRestart: Boolean) {
        val watch = OrphanWatch(mayRestart = mayRestart, diedAt = diedAt)
        val audio = AudioProbe()
        val telecom = TelecomProbe()
        val extras = listOf(
            CallSignal("foreign voice-communication recorder", { audio.recordingProbeAbsent }, audio::foreignVoiceCommRecorderActive),
            CallSignal("Telecom in-call flag", { telecom.absent }, telecom::isInCall),
        )
        extras.filter { it.absent() }.forEach { AppLogger.i(TAG, "Call signal '${it.name}' is not available on this ROM; the self-stop does not wait for it") }
        val unreadableLogged = mutableSetOf<String>()
        while (true) {
            val now = android.os.SystemClock.elapsedRealtime()
            val mode = audio.mode()
            val playback = audio.voiceCommPlaybackActive()
            val extra = CallSignals.read(extras)
            val step = watch.step(
                now = now,
                current = generation == loopGeneration,
                recording = runCatching { ctx.isRecording() }.getOrDefault(false),
                clientHolds = clientSignals.holdsSince(diedAt),
                clientOwns = clientSignals.ownsSince(diedAt),
                callUp = mode?.let(OrphanSelfStop::isCallAudioMode),
                voiceCommActive = playback,
                // A LINE call on hold or muted shows no call mode and no playback: a foreign VOICE_COMMUNICATION recorder or Telecom's
                // own in-call flag still says it is up.
                otherCallSignals = extra.values,
                packageStopped = { isAppStopped(ctx.authority) },
            )
            if (watch.consumeRedeliveryCancelled()) AppLogger.i(TAG, "The app holds the binder; no more re-deliveries (the self-stop still runs until it claims the session)")
            if (step.audioUnreadable) {
                val names = listOfNotNull("audio mode".takeIf { mode == null }, "voice-communication playback".takeIf { playback == null }) + extra.unreadable
                names.filter { unreadableLogged.add(it) }.forEach { AppLogger.w(TAG, "Cannot read the call signal '$it'; not stopping the unowned recording") }
            }
            if (step.appForceStopped) AppLogger.i(TAG, "app was force-stopped; not reopening it")
            if (step.stoppedUnknown) AppLogger.w(TAG, "Cannot tell whether the app was force-stopped; delivering at most once")
            when (step.action) {
                OrphanWatch.Action.SUPERSEDED -> return
                OrphanWatch.Action.APP_RETURNED -> {
                    AppLogger.i(TAG, "The app claimed the session again; watch over (after ${watch.attempts} delivery attempt(s))")
                    return
                }
                OrphanWatch.Action.RECORDING_ENDED -> {
                    AppLogger.i(TAG, "Not recording any more; watch over (after ${watch.attempts} delivery attempt(s))")
                    return
                }
                OrphanWatch.Action.SELF_STOP -> {
                    // The app may have come back during this very tick: never stop a recording it now owns.
                    if (clientSignals.ownsSince(diedAt)) {
                        AppLogger.i(TAG, "The app claimed the session just before the self-stop; leaving the recording to it")
                        return
                    }
                    AppLogger.i(TAG, "the call ended with no app attached; stopping and finalising the recording myself (audio mode $mode)")
                    runCatching { ctx.stopRecording() }.onFailure { AppLogger.e(TAG, "Self-stop failed: ${it.message}", it) }
                    val stillRecording = runCatching { ctx.isRecording() }.getOrDefault(true)
                    // The fact the app asks for over the dump binder call: only a recording this daemon finalised itself is
                    // saved in full later (a recording cut by SIGTERM never gets one).
                    if (!stillRecording) selfStopAtMillis = System.currentTimeMillis()
                    AppLogger.i(TAG, "Self-stop done; recording=$stillRecording")
                    return
                }
                OrphanWatch.Action.DELIVER -> {
                    val delivered = runCatching { BinderDelivery.redeliverBinderToApp(ctx.binder, ctx.authority, ctx.apkPath) }
                        .onFailure { AppLogger.w(TAG, "Re-delivery after the app's death threw: ${it.message}") }
                        .getOrDefault(false)
                    lastSentAt = android.os.SystemClock.elapsedRealtime()
                    watch.onDelivered(lastSentAt)
                    AppLogger.i(TAG, "Re-delivery after the app's death, attempt ${watch.attempts} of ${AppDeathRedelivery.MAX_ATTEMPTS}: " +
                        "provider replied=$delivered (not proof the app took it)")
                    if (watch.deliveryExhausted) AppLogger.w(TAG, "No more re-deliveries; this daemon will finish the recording when the call ends")
                }
                OrphanWatch.Action.NONE -> Unit
            }
            runCatching { Thread.sleep(WATCH_TICK_MS) }
        }
    }

    /** The audio service, resolved once per watch (no reflection lookups per tick). */
    private class AudioProbe {
        private val service: Any? = runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "audio") as? IBinder
            binder?.let { Class.forName("android.media.IAudioService\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, it) }
        }.getOrNull()
        private val getMode = runCatching { service?.javaClass?.getMethod("getMode") }.getOrNull()
        private val getPlayback = runCatching { service?.javaClass?.getMethod("getActivePlaybackConfigurations") }.getOrNull()

        /** The audio mode (the app-call truth; the telephony call state lies during VoIP), or null. */
        fun mode(): Int? = runCatching { getMode?.invoke(service) as? Int }.getOrNull()

        private val getRecording = runCatching { service?.javaClass?.getMethod("getActiveRecordingConfigurations") }.getOrNull()

        /** True when the shell cannot see which uid owns each recording configuration. */
        @Volatile var uidsHidden = false
        val recordingProbeAbsent: Boolean get() = service == null || getRecording == null || uidsHidden

        /**
         * Whether a VOICE_COMMUNICATION recorder is active under a uid other than the daemon's own (a LINE/WhatsApp call on
         * hold or muted keeps its mic open). Null = unreadable.
         */
        fun foreignVoiceCommRecorderActive(): Boolean? = runCatching {
            val list = getRecording?.invoke(service) as? List<*> ?: return@runCatching null
            val own = android.os.Process.myUid()
            list.filterNotNull().any { config ->
                val source = config.javaClass.getMethod("getClientAudioSource").invoke(config) as? Int
                val uid = try { config.javaClass.getMethod("getClientUid").invoke(config) as? Int } catch (e: NoSuchMethodException) { null }
                // No visible uid (missing method, or -1): every recorder would look foreign. The probe is dropped instead.
                if (uid == null || uid < 0) { uidsHidden = true; return@any false }
                source == SOURCE_VOICE_COMMUNICATION && uid != own
            }
        }.getOrNull()

        /** Whether any VOICE_COMMUNICATION track is playing: a VoIP call whose audio mode dipped to NORMAL. Null = unreadable. */
        fun voiceCommPlaybackActive(): Boolean? = runCatching {
            val list = getPlayback?.invoke(service) as? List<*> ?: return@runCatching null
            list.filterNotNull().any { config ->
                val attrs = config.javaClass.getMethod("getAudioAttributes").invoke(config)
                val usage = attrs?.javaClass?.getMethod("getUsage")?.invoke(attrs) as? Int
                val active = runCatching { config.javaClass.getMethod("isActive").invoke(config) as? Boolean }.getOrNull() ?: true
                usage == USAGE_VOICE_COMMUNICATION && active
            }
        }.getOrNull()
    }

    /** Telecom's own "a call is up" flag, read through ITelecomService (the shell may read it). */
    private class TelecomProbe {
        private val service: Any? = runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "telecom") as? IBinder
            binder?.let { Class.forName("com.android.internal.telecom.ITelecomService\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, it) }
        }.getOrNull()
        private val isInCall = runCatching {
            service?.javaClass?.methods?.firstOrNull { m -> m.name == "isInCall" && m.parameterTypes.all { it == String::class.java } }
        }.getOrNull()

        val absent: Boolean get() = service == null || isInCall == null

        /** Null = unreadable. The calling package is the shell's own; the feature id (API 30+) is null. */
        fun isInCall(): Boolean? = runCatching {
            val m = isInCall ?: return@runCatching null
            val args = Array<Any?>(m.parameterTypes.size) { if (it == 0) "com.android.shell" else null }
            m.invoke(service, *args) as? Boolean
        }.getOrNull()
    }

    /** Whether the app's package is in the stopped state (force-stopped), or null when that cannot be read. */
    private fun isAppStopped(authority: String): Boolean? = runCatching {
        val pm = packageManager()
        val info = resolveProvider(pm, authority)
        val pkg = info.javaClass.getField("packageName").get(info) as String
        pm.javaClass.methods.firstOrNull { it.name == "isPackageStoppedForUser" && it.parameterTypes.size == 2 }
            ?.let { return@runCatching it.invoke(pm, pkg, 0) as? Boolean }
        val appInfo = info.javaClass.getField("applicationInfo").get(info)
        val flags = appInfo?.javaClass?.getField("flags")?.getInt(appInfo) ?: return@runCatching null
        // The provider's applicationInfo is read now, so its stopped bit is current.
        (flags and AppDeathRedelivery.FLAG_STOPPED) != 0
    }.onFailure { AppLogger.w(TAG, "Could not read the app's stopped state: ${it.message}") }.getOrNull()

    /** Keeps the observer from being collected: the activity manager only holds a weak reference to our side. */
    @Volatile
    private var observer: Binder? = null

    /**
     * Starts watching. Returns whether the observer was registered; the outcome is logged either way, so the log
     * says whether this daemon can hand its binder over again.
     */
    fun register(binder: IBinder, authority: String, apkPath: String, isRecording: () -> Boolean, stopRecording: () -> Unit): Boolean = try {
        startDeathWatch(binder, authority, apkPath, isRecording, stopRecording)
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
        val info = resolveProvider(packageManager(), authority)
        val appInfo = info.javaClass.getField("applicationInfo").get(info) ?: error("provider has no applicationInfo")
        return appInfo.javaClass.getField("uid").getInt(appInfo)
    }

    private fun packageManager(): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val pmBinder = serviceManager.getMethod("getService", String::class.java).invoke(null, "package") as? IBinder
            ?: error("no package service")
        return Class.forName("android.content.pm.IPackageManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, pmBinder) ?: error("no package manager")
    }

    private fun resolveProvider(pm: Any, authority: String): Any {
        val resolve = pm.javaClass.methods.firstOrNull { it.name == "resolveContentProvider" && it.parameterTypes.size == 3 }
            ?: error("resolveContentProvider not found")
        // The flags argument is an int up to Android 12 and a long from 13.
        val flags: Any = if (resolve.parameterTypes[1] == java.lang.Long.TYPE) 0L else 0
        return resolve.invoke(pm, authority, flags, 0) ?: error("no provider for $authority")
    }

    private const val WATCH_TICK_MS = 1_000L
    private const val USAGE_VOICE_COMMUNICATION = 2
    private const val SOURCE_VOICE_COMMUNICATION = 7
}
