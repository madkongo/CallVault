# CallVault Unified — Plan 1: Embedded-ADB De-risk Spike

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove on the real OnePlus 12 (CPH2581 / OxygenOS 16 / Android 16) that CallVault can, *in-app and without Shizuku or root*, (a) pair with Android Wireless debugging, (b) connect and run a shell as uid `2000(shell)`, (c) reconnect after a reboot using a stored key, and (d) open a forwarded socket suitable for streaming scrcpy-server audio.

**Architecture:** Add the `libadb-android` library (pure-Java ADB protocol incl. Android-11 pairing) to the existing `app-src` project behind a throwaway debug `Activity`. Drive pairing/connect/shell/forward from that screen and record empirical results. No production wiring, no removal of Shizuku yet — this spike only answers "is the transport viable?" so Plan 2 can be written against facts.

**Tech Stack:** Kotlin + Jetpack Compose · `com.github.MuntashirAkon:libadb-android:3.1.1` (JitPack) · Android SDK (compileSdk 36, minSdk 30) · JDK 17 · scrcpy-server v4.0 (already vendored at build time).

---

## Decomposition (this is Plan 1 of 4)

The unified-app spec (`docs/superpowers/specs/2026-06-09-callvault-unified-design.md`) is large and strictly sequential, and its risk section mandates a de-risk spike before the rest. Therefore:

- **Plan 1 (this doc) — Embedded-ADB spike.** A hard GATE. Validation-procedure-driven (a spike, not production), so it is concrete on-device steps with expected outputs rather than strict red-green TDD.
- **Plan 2 — AdbShell layer + ScrcpyLauncher + recording (Shizuku removed).** Written *after* this spike, using its findings (which library/API actually paired, how the port is discovered, whether scrcpy audio streams over the forwarded socket). Strict TDD.
- **Plan 3 — StorageRouter + Storage Target setting + retry queue.** Strict TDD.
- **Plan 4 — BootBridge hands-free boot + watchdog + whitelisting onboarding + QS-tile fallback.** Written after Plans 2–3.

**Do not start Plan 2 until this spike's exit criterion (Task 8) is met and documented.**

## Environment Facts (verified 2026-06-09)

- Repo root: `/Users/kfirbaba/Desktop/Projects/callrecorder` (git, branch `main`). Gradle project lives under `app-src/`.
- Build env: `source ~/.callvault-env.sh` sets `JAVA_HOME` (brew openjdk@17), `ANDROID_HOME`, PATH; build with `cd app-src && ./gradlew assembleDebug`.
- Device: `adb-6011b07e-cDHaSu._adb-tls-connect._tcp` (CPH2581) over wireless adb. `adb` at `/opt/homebrew/bin/adb`.
- `app-src/settings.gradle.kts`: `dependencyResolutionManagement` uses `RepositoriesMode.FAIL_ON_PROJECT_REPOS` → new repos (JitPack) MUST be added there, not in the module.
- `app-src/app/build.gradle.kts`: `namespace = "com.kitsumed.shizucallrecorder"`, `applicationId = "com.kfir.callvault"`, `minSdk = 30`, `compileSdk = 36`, `dependencies { ... }` starts at line 224 (uses a `libs.*` version catalog).
- Source package dir for new Kotlin files: `app-src/app/src/main/java/com/kitsumed/shizucallrecorder/`.
- minSdk 30 == Android 11 == the first version with Wireless debugging pairing. The target device is Android 16. Good.

## Reference implementations (port, don't invent)

`libadb-android`'s connection manager is abstract; the canonical concrete subclass is **App Manager's** `AdbConnectionManager.java`
(`io.github.muntashirakon.AppManager`, repo `MuntashirAkon/AppManager`, file
`app/src/main/java/io/github/muntashirakon/AppManager/adb/AdbConnectionManager.java`, GPL-3.0 — compatible with CallVault).
When a step says "port the reference manager," copy that class's structure (key/cert generation + persistence, `getDeviceName()`), adapting package/storage. Confirm exact helper names against the `libadb-android` 3.1.1 sources in your dependency cache rather than guessing.

---

## Task 1: Add JitPack + libadb-android and confirm it builds

**Files:**
- Modify: `app-src/settings.gradle.kts` (the `dependencyResolutionManagement { repositories { … } }` block)
- Modify: `app-src/app/build.gradle.kts:224` (the `dependencies {` block)

- [ ] **Step 1: Add JitPack to settings repositories**

In `app-src/settings.gradle.kts`, change the `dependencyResolutionManagement.repositories` block from:

```kotlin
    repositories {
        google()
        mavenCentral()
    }
```

to:

```kotlin
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
```

- [ ] **Step 2: Add the dependency**

In `app-src/app/build.gradle.kts`, immediately after the Shizuku lines (`implementation(libs.shizukuProvider)` at line 255), add:

```kotlin
    // Spike (Plan 1): in-app ADB over wireless debugging. Candidate transport to replace Shizuku.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
```

- [ ] **Step 3: Build to confirm resolution**

Run:
```bash
source ~/.callvault-env.sh && cd app-src && ./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. If JitPack returns 401/404 for the artifact, re-resolve with `--refresh-dependencies`; if it still fails, check the latest tag at https://github.com/MuntashirAkon/libadb-android/releases and update the version, recording the working version in Task 8's notes.

- [ ] **Step 4: Commit**

```bash
cd /Users/kfirbaba/Desktop/Projects/callrecorder
git add app-src/settings.gradle.kts app-src/app/build.gradle.kts
git commit -m "chore: add libadb-android dependency for ADB transport spike"
```

---

## Task 2: Port the ADB connection manager

**Files:**
- Create: `app-src/app/src/main/java/com/kitsumed/shizucallrecorder/spike/SpikeAdbManager.kt`

- [ ] **Step 1: Create the manager subclass**

Create `SpikeAdbManager.kt`. This wraps `libadb-android`'s `AbsAdbConnectionManager` with a persisted RSA keypair + self-signed certificate (required for the TLS pairing/connect handshake) stored in the app's private files dir. Port the key/cert logic from App Manager's `AdbConnectionManager.java` (see "Reference implementations"). Skeleton (adapt helper names to the 3.1.1 API in your dependency cache):

```kotlin
package com.kitsumed.shizucallrecorder.spike

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AndroidPubkey
import java.security.KeyPair
import java.security.cert.X509Certificate

/**
 * Spike-only ADB connection manager. Persists one RSA keypair + self-signed cert
 * under the app's filesDir so pairing survives process death and reboots.
 * Ported from App Manager's AdbConnectionManager (GPL-3.0).
 */
class SpikeAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val keyPair: KeyPair
    private val certificate: X509Certificate

    init {
        setApi(android.os.Build.VERSION.SDK_INT)
        // Load existing key+cert from filesDir, or generate and persist new ones.
        // Use the library's key generation helpers (see AdbConnectionManager reference).
        val loaded = loadOrGenerate(context)
        keyPair = loaded.first
        certificate = loaded.second
    }

    override fun getPrivateKey(): java.security.PrivateKey = keyPair.private
    override fun getCertificate(): X509Certificate = certificate
    override fun getDeviceName(): String = "CallVault-Spike"

    companion object {
        @Volatile private var instance: SpikeAdbManager? = null
        fun getInstance(context: Context): SpikeAdbManager =
            instance ?: synchronized(this) {
                instance ?: SpikeAdbManager(context.applicationContext).also { instance = it }
            }

        // loadOrGenerate(): read keypair.der + cert.pem from context.filesDir if present;
        // otherwise generate (RSA 2048 + self-signed X509 valid ~30y) and write them.
        // Copy this verbatim from the reference AdbConnectionManager, changing only paths.
    }
}
```

- [ ] **Step 2: Compile**

Run:
```bash
source ~/.callvault-env.sh && cd app-src && ./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. Resolve any abstract-method mismatches by reading the `AbsAdbConnectionManager` source in the dependency cache (`~/.gradle/caches/modules-2/.../libadb-android-3.1.1-sources.jar` or the JitPack AAR) and matching the exact required overrides.

- [ ] **Step 3: Commit**

```bash
cd /Users/kfirbaba/Desktop/Projects/callrecorder
git add app-src/app/src/main/java/com/kitsumed/shizucallrecorder/spike/SpikeAdbManager.kt
git commit -m "feat(spike): persisted ADB connection manager (key+cert)"
```

---

## Task 3: Throwaway debug Activity to drive the spike

**Files:**
- Create: `app-src/app/src/main/java/com/kitsumed/shizucallrecorder/spike/AdbSpikeActivity.kt`
- Modify: `app-src/app/src/main/AndroidManifest.xml` (register the activity, debug-only, not exported to launcher)

- [ ] **Step 1: Create the Activity**

Create `AdbSpikeActivity.kt`: a minimal Compose screen with text fields for **pairing port** and **pairing code**, buttons **Pair**, **Connect**, **Run `id`**, **Forward test**, and a scrolling **log** `Text`. All ADB calls run on a background thread (`Dispatchers.IO`); append results/exceptions to the log state. Use `SpikeAdbManager.getInstance(this)`.

```kotlin
package com.kitsumed.shizucallrecorder.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdbSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var port by remember { mutableStateOf("") }
            var code by remember { mutableStateOf("") }
            var log by remember { mutableStateOf("Spike ready.\n") }
            fun append(s: String) { log += s + "\n" }
            val mgr = remember { SpikeAdbManager.getInstance(this) }

            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    OutlinedTextField(port, { port = it }, label = { Text("Pairing port") })
                    OutlinedTextField(code, { code = it }, label = { Text("Pairing code") })
                    Row {
                        Button(onClick = {
                            lifecycleScope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        mgr.pair("127.0.0.1", port.trim().toInt(), code.trim())
                                    }
                                }.onSuccess { append("PAIR ok=$it") }
                                 .onFailure { append("PAIR FAIL: ${it.message}") }
                            }
                        }) { Text("Pair") }
                        Button(onClick = {
                            lifecycleScope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { mgr.autoConnect(applicationContext, 10_000) }
                                }.onSuccess { append("CONNECT ok=$it") }
                                 .onFailure { append("CONNECT FAIL: ${it.message}") }
                            }
                        }) { Text("Connect") }
                        Button(onClick = {
                            lifecycleScope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val s = mgr.openStream("shell:id")
                                        s.openInputStream().bufferedReader().readText()
                                    }
                                }.onSuccess { append("ID: $it") }
                                 .onFailure { append("ID FAIL: ${it.message}") }
                            }
                        }) { Text("Run id") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(log, Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
            }
        }
    }
}
```

Note: `autoConnect(context, timeout)` is libadb-android's mDNS-based discovery+connect; if its signature differs in 3.1.1, fall back to a manual `connect("127.0.0.1", <connectPort>)` field and record the discovery API actually used in Task 8.

- [ ] **Step 2: Register the Activity (debug-only, hidden)**

In `app-src/app/src/main/AndroidManifest.xml`, inside `<application>`, add:

```xml
<activity
    android:name=".spike.AdbSpikeActivity"
    android:exported="true"
    android:label="ADB Spike" />
```

`exported="true"` is only so we can launch it via `adb` (below). It will be deleted at the end of the spike. (It is fine to leave it in the debug build for the spike's lifetime; it is removed before any release.)

- [ ] **Step 3: Build, install, launch**

```bash
source ~/.callvault-env.sh && cd app-src && ./gradlew :app:installDebug
adb shell am start -n com.kfir.callvault/com.kitsumed.shizucallrecorder.spike.AdbSpikeActivity
```
Expected: app builds, installs, and the "ADB Spike" screen appears on the phone with the two fields, buttons, and "Spike ready." log.

- [ ] **Step 4: Commit**

```bash
cd /Users/kfirbaba/Desktop/Projects/callrecorder
git add app-src/app/src/main/java/com/kitsumed/shizucallrecorder/spike/AdbSpikeActivity.kt app-src/app/src/main/AndroidManifest.xml
git commit -m "feat(spike): debug activity to drive ADB pair/connect/shell"
```

---

## Task 4: Validate in-app PAIRING on the device

This task is performed on the phone with the spike screen open. **You (the user) read the pairing code from the phone; the agent cannot.**

- [ ] **Step 1: Open Wireless debugging pairing**

On the phone: Settings → System → Developer options → **Wireless debugging** → **Pair device with pairing code**. A dialog shows a **6-digit code** and an **IP:port** (the *pairing* port — different from the connect port).

- [ ] **Step 2: Pair from the spike screen**

In the "ADB Spike" screen: enter the **pairing port** (the port after the `:` in the dialog) and the **6-digit code**, then tap **Pair**.
Expected log: `PAIR ok=true`. The phone's pairing dialog dismisses and the device shows under "Paired devices" as `CallVault-Spike`.
If `PAIR FAIL`: keep both the Settings dialog and the app visible (Android voids the code if the dialog closes); retry with a fresh code. Record the exact failure text for Task 8.

---

## Task 5: Validate CONNECT + shell identity (the core privilege check)

- [ ] **Step 1: Connect**

Tap **Connect**.
Expected log: `CONNECT ok=true`. (This uses mDNS discovery of the randomized `_adb-tls-connect._tcp` port; if it can't find it, note that and use a manual connect-port field instead — the connect port is visible on the main Wireless debugging screen.)

- [ ] **Step 2: Run `id`**

Tap **Run id**.
Expected log: `ID: uid=2000(shell) gid=2000(shell) ...`.
**This is the make-or-break result:** uid 2000 means CallVault has the exact shell privilege scrcpy-server needs to capture call audio — *without Shizuku*. Record the full line for Task 8.

- [ ] **Step 3: Sanity-check a privileged op**

Temporarily change the **Run id** button's command to `shell:dumpsys audio | head -n 5` (or add a fourth button) and confirm it returns output (proves general shell command execution, not just `id`). Revert afterward. Record result.

---

## Task 6: Validate reconnect after reboot (no re-pairing)

- [ ] **Step 1: Reboot the phone**

```bash
adb reboot
```
Wait for the device to come back (`adb wait-for-device` over wireless may need the phone back on Wi-Fi; if the wireless adb host connection doesn't auto-return, that's fine — this test is about the *in-app* connection, not the laptop's).

- [ ] **Step 2: Relaunch the spike and reconnect**

Ensure **Wireless debugging is still ON** on the phone (it persists across reboot if left enabled). Open the "ADB Spike" screen, tap **Connect** (do NOT pair again), then **Run id**.
Expected: `CONNECT ok=true` and `ID: uid=2000(shell) ...` **without** re-entering a pairing code — proving the persisted key in `SpikeAdbManager` reconnects after reboot via the new randomized connect port.
Record: did it reconnect with zero pairing? How long did mDNS discovery take? Any OnePlus prompt?

---

## Task 7: Validate a forwarded socket for scrcpy audio

This proves the transport that Plan 2's `ScrcpyLauncher` depends on. Two levels — do the basic one; attempt the stretch one if time allows, since it's the real risk.

- [ ] **Step 1 (basic): open a shell stream and stream bytes**

Add a **Forward test** button that runs `shell:cat /proc/uptime` repeatedly via a held-open `AdbStream`, appending bytes as they arrive. Confirm streamed data arrives over the `AdbStream.openInputStream()` continuously (not just one shot). Expected: uptime values stream into the log. This proves long-lived stream reads work.

- [ ] **Step 2 (stretch): run scrcpy-server audio over the stream**

Push the vendored scrcpy-server and start it audio-only, reading its socket:
```
# (driven from the Forward test button, adapted)
shell: push app's scrcpy-server.jar to /data/local/tmp/scrcpy-server-spike.jar
shell: CLASSPATH=/data/local/tmp/scrcpy-server-spike.jar app_process / com.genymobile.scrcpy.Server <ver> tunnel_forward=true audio=true video=false control=false cleanup=false
```
Then read the audio socket and confirm the scrcpy audio **codec header** (the 4-byte FourCC the existing `ScrcpyClient.onMetadataReceived` expects) arrives. Reuse the existing `app-src/.../integrations/scrcpy/` parsing as reference for what bytes to expect.
Expected: a non-error byte stream with a recognizable codec FourCC. If audio capture fails with "stream explicitly disabled" or silence, record it — it's an OEM capture risk for Plan 2, not a transport blocker.
Note the scrcpy-server location/version produced by the upstream build (downloaded at build time; check `app-src/app/build/**/scrcpy-server*` or the build task that fetches v4.0).

---

## Task 8: Record findings (the gate decision)

**Files:**
- Create: `docs/superpowers/specs/2026-06-09-adb-spike-findings.md`

- [ ] **Step 1: Write the findings doc**

Capture, with exact outputs copied from the spike log:
- libadb-android version that built; any version bump needed.
- Pairing: worked? exact UX; failure modes seen.
- Connect: mDNS `autoConnect` worked, or manual connect port needed? discovery latency.
- Shell identity: the full `id` line (uid 2000 confirmed?).
- Reboot reconnect: zero-pairing reconnect worked? latency? OEM prompts?
- Forwarded socket: long-lived stream worked? scrcpy audio header seen (stretch)?
- **Decision:** GREEN (proceed to Plan 2 with libadb-android), or FALLBACK (bundle the `adb` binary, LADB-style) with the specific reason.

- [ ] **Step 2: Commit**

```bash
cd /Users/kfirbaba/Desktop/Projects/callrecorder
git add docs/superpowers/specs/2026-06-09-adb-spike-findings.md
git commit -m "docs: ADB transport spike findings + go/no-go decision"
```

- [ ] **Step 3: Leave the spike code in place (do not delete yet)**

Keep `spike/` and the debug activity until Plan 2 has reused/replaced them, so Plan 2 can build on a known-working manager. Plan 2's final task removes the throwaway `AdbSpikeActivity` and its manifest entry.

---

## Spike Exit Criterion (GATE)

On the OnePlus 12 / OOS16, from inside CallVault with no Shizuku and no root:
**pair once → connect → `shell:id` returns `uid=2000(shell)` → reboot → reconnect with zero re-pairing → a long-lived forwarded stream delivers bytes.** When all five hold (stretch scrcpy-audio header is a strong bonus), record GREEN in the findings doc and proceed to author Plan 2.
