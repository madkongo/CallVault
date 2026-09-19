/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.ksp)
}

val scrcpyVersion = "4.0"
val scrcpyServerUrl = "https://github.com/Genymobile/scrcpy/releases/download/v$scrcpyVersion/scrcpy-server-v$scrcpyVersion"
val scrcpyServerSha256 = "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a"
val scrcpyServerAssetName = "scrcpy-server"
val scrcpyDownloadDir = layout.buildDirectory.dir("generated/scrcpy/assets")
val scrcpyServerAssetFile = scrcpyDownloadDir.map { it.file(scrcpyServerAssetName) }
val libphonenumberMetadataDir = layout.buildDirectory.dir("generated/libphonenumber/assets")

// Detect if we're running in a CI environment (e.g., GitHub Actions).
val isEnvironmentGithubCI = providers.environmentVariable("GITHUB_ACTIONS").isPresent

// True only when a release-producing task is in the requested task graph (assembleRelease,
// bundleRelease, packageRelease, lintVitalRelease, …). The release keystore is required ONLY for
// real release builds — so debug-only CI jobs (notably CodeQL's `compileDebugSources`) don't trip
// the keystore check at configuration time, while `assembleRelease` still fails loudly on a missing
// secret. `taskNames` reflects what was requested on the command line (":app:assembleRelease", etc.).
val isReleaseBuildRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

abstract class DownloadAssetTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val assetName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val targetFile = outputDir.get().file(assetName.get()).asFile

        // Internal check to skip if already correct
        if (targetFile.exists() && calculateSha256(targetFile).equals(sha256.get(), ignoreCase = true)) {
            println("${assetName.get()} is already up-to-date.")
            return
        }

        targetFile.parentFile.mkdirs()
        println("Downloading ${assetName.get()}...")

        URI(url.get()).toURL().openStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val actualHash = calculateSha256(targetFile)
        if (!actualHash.equals(sha256.get(), ignoreCase = true)) {
            targetFile.delete()
            throw GradleException("SHA256 mismatch! Expected ${sha256.get()} but got $actualHash")
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
abstract class ExtractMetadataTask : Sync() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val downloadScrcpyServer = tasks.register<DownloadAssetTask>("downloadScrcpyServer") {
    url.set(scrcpyServerUrl)
    sha256.set(scrcpyServerSha256)
    assetName.set(scrcpyServerAssetName)
    outputDir.set(scrcpyDownloadDir)
}

val extractLibphonenumberMetadata = tasks.register<ExtractMetadataTask>("extractLibphonenumberMetadata") {
    val lib = libs.libphonenumber.get()
    val jarFile = project.configurations
        .detachedConfiguration(project.dependencies.create(lib))
        .singleFile

    from(zipTree(jarFile)) {
        include("com/google/i18n/phonenumbers/data/**")
        eachFile {
            relativePath = RelativePath(true, "phonenumber_data", name)
        }
        includeEmptyDirs = false
    }
    outputDir.set(libphonenumberMetadataDir)
    into(outputDir)
}

// In-repo source of truth for the app version. Releases are built LOCALLY from these defaults (the
// signing key that existing installs can update over lives on the maintainer's machine, not in CI),
// so `./gradlew :app:assembleRelease` with no -P flags must produce exactly the shipped artifact.
//
// BUMP BOTH every release: versionName to match the CHANGELOG, and versionCode strictly upwards.
// Android refuses to install a lower versionCode over a higher one, and uninstalling to get around
// that would destroy the device's ADB pairing — so a versionCode that goes backwards is not a
// cosmetic mistake, it strands that device on the old build.
val ciVersionCode = providers.gradleProperty("versionCode").map { it.toIntOrNull() }.orElse(20400)
val ciVersionName = providers.gradleProperty("versionName").orElse("2.4.0")
val ciBuildNumber = providers.gradleProperty("ciBuildNumber").orElse("Local")

// -PisolateTestApp builds the debug variant under its own applicationId so instrumented tests can be
// run on a device that is already carrying a working release build. Off by default: it must not
// change the ordinary `installDebug` loop, which deliberately reinstalls over the top.
val isolateTestApp = providers.gradleProperty("isolateTestApp").isPresent

android {
    namespace = "com.baba.callvault"
    compileSdk = 36

    // Required for libaudiohandoff.so, the native half of "Resilient recording".
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.baba.callvault"
        minSdk = 30
        targetSdk = 36
        versionCode = ciVersionCode.get()
        versionName = ciVersionName.get()

        // arm64 only. Every device this app can run on (minSdk 30, and the ADB-over-binder daemon it
        // depends on) is arm64, and shipping one ABI keeps the APK small.
        ndk { abiFilters += "arm64-v8a" }

        // Instrumented tests. Added with on-device transcription: the JNI bridge to whisper.cpp
        // cannot be verified by a JVM unit test, and a wrong language parameter or a mismatched
        // native symbol fails only at runtime — silently producing confident nonsense rather than
        // crashing. See app/src/androidTest.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default provider authority. Only an isolated instrumented build overrides it; every normal
        // build resolves to exactly the string RecorderBinderProvider.AUTHORITY hardcodes, so release
        // output is unchanged.
        manifestPlaceholders["recorderAuthority"] = "com.baba.callvault.recorder"

        buildConfigField("String", "CI_BUILD_NUMBER", "\"${ciBuildNumber.get()}\"")

        buildConfigField("String", "SCRCPY_VERSION", "\"$scrcpyVersion\"")
        buildConfigField("String", "SCRCPY_SERVER_SHA256", "\"$scrcpyServerSha256\"")
        buildConfigField("String", "SCRCPY_SERVER_ASSET_NAME", "\"$scrcpyServerAssetName\"")
    }
    // Local release keystore (gitignored). This is the SAME key the published debug builds used
    // (cert c875ffd0…), preserved here so `assembleRelease` produces a NON-debuggable APK that
    // existing installs can still update over in place. Guard/back this file up — losing it means
    // no more in-place updates for existing users, ever.
    val localReleaseKeystore = rootProject.file("signing/callvault-signing.keystore")

    signingConfigs {
        // Signing config for CI environments. Only demand the keystore env when a release build is
        // actually requested — otherwise a debug-only CI job (e.g. CodeQL's `compileDebugSources`)
        // would fail here at configuration time even though it never signs a release APK.
        create("ci-release") {
            if (isEnvironmentGithubCI && isReleaseBuildRequested) {
                storeFile = file(System.getenv("KEYSTORE_FILE") ?: throw GradleException("Keystore file not provided for release signing. env variable: KEYSTORE_FILE"))
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: throw GradleException("Keystore password not provided for release signing. env variable: KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: throw GradleException("Key alias not provided for release signing. env variable: KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?:throw GradleException("Key password not provided for release signing. env variable: KEY_PASSWORD")

            }
        }
        // Local release signing (maintainer machine). Same key/creds as the app's historical debug
        // keystore (default alias/password), so the cert matches every prior release.
        create("local-release") {
            if (localReleaseKeystore.exists()) {
                storeFile = localReleaseKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes {
        release {
            // R8/minification is DISABLED: the privileged recorder daemon is launched out-of-process
            // by `app_process` via a string classpath reference (com.baba.callvault.server.RecorderServer),
            // so R8 can't see it as reachable and strips its internals — breaking recording. This matches
            // the (un-minified) v1.1.x releases. Re-enabling minify would require comprehensive -keep rules
            // for the whole daemon class graph plus on-device verification.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // release build type defaults to debuggable=false — the whole point of shipping this
            // instead of assembleDebug. Sign with the CI key on CI, else the local release keystore.
            when {
                isEnvironmentGithubCI -> {
                    println("Configuring release build for CI environment. Official release signing keys will be used.")
                    signingConfig = signingConfigs.getByName("ci-release")
                }
                localReleaseKeystore.exists() -> {
                    signingConfig = signingConfigs.getByName("local-release")
                }
                else -> println("No release keystore found (signing/callvault-signing.keystore); release APK will be unsigned.")
            }
        }

        // Opt-in isolation for instrumented runs: -PisolateTestApp
        //
        // The only test device is the maintainer's daily driver, running a release build that is
        // actively recording calls. Installing a debug APK over it replaces a working recorder and
        // drops WRITE_SECURE_SETTINGS, forcing a re-grant before recording works again.
        //
        //   ./gradlew connectedDebugAndroidTest -PisolateTestApp
        //
        // installs under its own applicationId instead, alongside the real app, and leaves it alone.
        // This is a property rather than a separate build type on purpose: AGP creates unit-test
        // tasks only for `testBuildType`, so introducing one and pointing testBuildType at it
        // silently deletes `testDebugUnitTest` — the command this project and its docs use
        // everywhere. Plain `installDebug` is likewise unaffected, so the normal
        // reinstall-over-the-top dev loop still works.
        debug {
            if (isolateTestApp) {
                applicationIdSuffix = ".instrtest"
                // Two installs cannot share a ContentProvider authority. Only the isolated build
                // moves; every normal build keeps the authority RecorderBinderProvider.AUTHORITY
                // expects. The isolated build never uses that provider — the instrumented tests
                // exercise the whisper.cpp JNI bridge, which needs no provider and no daemon.
                manifestPlaceholders["recorderAuthority"] = "com.baba.callvault.instrtest.recorder"
            }
        }
    }
    compileOptions {
        sourceCompatibility =  JavaVersion.VERSION_17
        targetCompatibility =  JavaVersion.VERSION_17
    }
    // Builds libaudiohandoff.so (see src/main/cpp).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        // Required: the app and the shell-uid app_process daemon talk over the generated AIDL stubs
        // (IRecorderService, BinderContainer).
        aidl = true
        buildConfig = true
    }
    packaging {
        // Exclude the original metadata from libphonenumber to avoid conflicts with our extracted version. This ensures only our processed assets are included in the final APK.
        resources.excludes.add("com/google/i18n/phonenumbers/data/**")
        // REQUIRED, do not remove: extracts native libs to nativeLibraryDir on install. The shell-uid
        // app_process DAEMON has no app classloader library-search path, so it can only load
        // libaudiohandoff.so from an explicit on-disk path (<apkDir>/lib/arm64/). Left uncompressed
        // inside the APK it would not exist as a file for the daemon to load.
        jniLibs { useLegacyPackaging = true }
    }
    androidResources {
        generateLocaleConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    lint {
        // Fail the build if any shipped locale is missing a translatable string, or has a
        // stale key that no longer exists in the default resources. This prevents the
        // "first page stays English" class of regressions where new strings ship untranslated.
        warningsAsErrors = false
        error += setOf("MissingTranslation", "ExtraTranslation")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * Where Room writes its schema JSON.
 *
 * Only [com.baba.callvault.data.transcripts.db.TranscriptDatabase] exports a schema. It must, because
 * it carries real migrations: a transcript costs minutes of device CPU and cannot be regenerated, so
 * that database may never take the destructive fallback the recordings catalog deliberately uses. A
 * migration needs a committed baseline to be written and tested against, and without this argument
 * `exportSchema = true` silently produces nothing.
 *
 * The exported files are committed on purpose — they are the record of every shipped schema.
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * Puts the exported schemas where an instrumented test can read them.
 *
 * `MigrationTestHelper` creates a database at an older version by replaying the schema Room itself
 * exported, which means it has to find those files on the device — it reads them from the test
 * APK's assets, not from the project. Without this the helper throws at construction and the
 * migration test cannot run at all.
 */
android.sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            downloadScrcpyServer,
            DownloadAssetTask::outputDir
        )

        variant.sources.assets?.addGeneratedSourceDirectory(
            extractLibphonenumberMetadata,
            ExtractMetadataTask::outputDir
        )
    }
}

aboutLibraries {
    // Gradle sync runs in the Task :app:prepareLibraryDefinitionsDebug and :app:prepareLibraryDefinitionsRelease.
    collect {
        // Define the path configuration files are located in. E.g. additional libraries, licenses to add to the target .json
        // Warning: Please do not use the parent folder of a module as path, as this can result in issues. More details: https://github.com/mikepenz/AboutLibraries/issues/936
        // The path provided is relative to the modules path (not project root)
        configPath = file("../aboutLibrariesConfig")

        // Enable fetching of "remote" licenses.  Uses the API of supported source hosts
        // See https://github.com/mikepenz/AboutLibraries#special-repository-support
        // A `gitHubApiToken` is required for this to work as it fetches information from GitHub's API.
        fetchRemoteLicense = false

        // Enables fetching of "remote" funding information. Uses the API of supported source hosts
        // See https://github.com/mikepenz/AboutLibraries#special-repository-support
        // A `gitHubApiToken` is required for this to work as it fetches information from GitHub's API.
        fetchRemoteFunding = false

    }
    library {
        // Enable the duplication mode, allows to merge, or link dependencies which relate
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
        // Configure the duplication rule, to match "duplicates" with
        // We merge when groupId and license are equal
        duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.GROUP
    }
}

// Fails the build when a locale drifts from the base strings. See the script for why.
apply(from = rootProject.file("gradle/translation-coverage.gradle.kts"))

dependencies {

    // Shizuku: an optional second source of shell-uid privileges, for phones that already run it.
    // The app works fully without it — see docs/dev-notes/2026-08-24-shizuku-support-plan.md.
    implementation(libs.shizukuApi)
    implementation(libs.shizukuProvider)
    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)

    // Compose Core
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Compose Tooling
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // AboutLibraries
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)

    // Libphonenumber
    implementation(libs.libphonenumber)

    // WorkManager: reliable background task execution for post-call Drive copy.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Room: the on-device recordings catalog — CallVault's own source of truth for the Home list,
    // so it never depends on a cloud provider's (Google Drive's) eventually-consistent folder listing.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Spike (Plan 1): in-app ADB over wireless debugging. Candidate transport to replace an external privileged-helper dependency.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // bcprov is already a runtime transitive dep of libadb-android; we need it at compile time too
    // so SpikeAdbManager can use X509V3CertificateGenerator for self-signed cert generation.
    compileOnly("org.bouncycastle:bcprov-jdk15to18:1.81")
    // Required by libadb-android for ADB TLS pairing: its SslUtils prefers the bundled
    // Conscrypt (org.conscrypt.OpenSSLProvider) for TLSv1.3 + exportKeyingMaterial. Without it,
    // pairing falls back to the platform Conscrypt and fails on Android 14+/OEM builds with
    // NoSuchMethodException: com.android.org.conscrypt.Conscrypt.exportKeyingMaterial.
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    // Test harness
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.robolectric:robolectric:4.14")
    testImplementation("androidx.test:core:1.6.1")
    // Lets tests exercise code that enqueues work. Without it WorkManager throws "not initialized"
    // the moment a test touches a scheduler, which would otherwise push that code out of test reach.
    testImplementation("androidx.work:work-testing:2.10.0")

    // Instrumented tests — the only way to exercise the whisper.cpp JNI bridge, which a JVM unit
    // test cannot load. Deliberately minimal: runner + JUnit extensions, no UI-testing stack.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Test-only (never shipped): lets an instrumented test grant Shizuku permission the way a person
    // does — by tapping Allow in Shizuku's own dialog. Shizuku 13 keeps its grants in its own server,
    // so neither `pm grant` nor UiAutomation.grantRuntimePermission can reach them.
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    // Opens a database at an older schema and runs the real migrations against it, on a real
    // SQLite. The transcripts database has no destructive fallback by design, so a migration that
    // is subtly wrong is a crash on first launch for every user who has ever transcribed a call —
    // and the unit-level drift guard compares SQL, which cannot catch a statement that fails to
    // execute or silently drops rows. Needs the exported schemas, wired into assets below.
    androidTestImplementation(libs.androidx.room.testing)
}
