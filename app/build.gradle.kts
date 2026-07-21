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

// In-repo source of truth for the app version. CI overrides these via -PversionName / -PversionCode
// (versionCode = the CI run number), but local builds and the in-app "About" version use these
// defaults — so BUMP versionName here every release to match the CHANGELOG and the version dispatched
// to the build workflow.
val ciVersionCode = providers.gradleProperty("versionCode").map { it.toIntOrNull() }.orElse(10206)
val ciVersionName = providers.gradleProperty("versionName").orElse("1.2.3")
val ciBuildNumber = providers.gradleProperty("ciBuildNumber").orElse("Local")

android {
    namespace = "com.baba.callvault"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.baba.callvault"
        minSdk = 30
        targetSdk = 36
        versionCode = ciVersionCode.get()
        versionName = ciVersionName.get()

        buildConfigField("String", "CI_BUILD_NUMBER", "\"${ciBuildNumber.get()}\"")

        buildConfigField("String", "SCRCPY_VERSION", "\"$scrcpyVersion\"")
        buildConfigField("String", "SCRCPY_SERVER_SHA256", "\"$scrcpyServerSha256\"")
        buildConfigField("String", "SCRCPY_SERVER_ASSET_NAME", "\"$scrcpyServerAssetName\"")
    }
    signingConfigs {
        // Signing config for CI environments.
        create("ci-release") {
            if (isEnvironmentGithubCI) {
                storeFile = file(System.getenv("KEYSTORE_FILE") ?: throw GradleException("Keystore file not provided for release signing. env variable: KEYSTORE_FILE"))
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: throw GradleException("Keystore password not provided for release signing. env variable: KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: throw GradleException("Key alias not provided for release signing. env variable: KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?:throw GradleException("Key password not provided for release signing. env variable: KEY_PASSWORD")

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
            if (isEnvironmentGithubCI) {
                println("Configuring release build for CI environment. Official release signing keys will be used.")
                signingConfig = signingConfigs.getByName("ci-release")
            }
        }
    }
    compileOptions {
        sourceCompatibility =  JavaVersion.VERSION_17
        targetCompatibility =  JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // Re-enabled for CallVault Plan 5 Task 0c: the binder command-channel spike needs the
        // generated AIDL stubs (IPersistDebugService, BinderContainer) shared between the app and the
        // app_process daemon. THROWAWAY — flip back to false when persistserver/ + aidl/ are removed.
        aidl = true
        buildConfig = true
    }
    packaging {
        // Exclude the original metadata from libphonenumber to avoid conflicts with our extracted version. This ensures only our processed assets are included in the final APK.
        resources.excludes.add("com/google/i18n/phonenumbers/data/**")
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

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
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
}
