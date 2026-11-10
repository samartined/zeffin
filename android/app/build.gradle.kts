// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

val fbVersion = providers.gradleProperty("fb.version").get()
// A release candidate's version name ends in -rc.<n> (-Pfb.releaseSuffix=-rc.1, set by the Release APK workflow on a
// release/<version> branch); its version code stays the release's, so the final release installs on top of it.
val releaseSuffix = providers.gradleProperty("fb.releaseSuffix").orNull.orEmpty().also {
    require(it.isEmpty() || Regex("-rc\\.[0-9]{1,3}").matches(it)) { "fb.releaseSuffix: empty or -rc.<n>" }
}

// The commit a preview build comes from, "+dirty" with uncommitted changes: shown in its version name.
fun git(vararg args: String) = providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
val previewCommit = git("rev-parse", "--short=7", "HEAD").ifEmpty { "unknown" } + if (git("status", "--porcelain").isEmpty()) "" else "+dirty"
// Test builds from several branches carry different fb.version values; Android refuses a lower versionCode on top of a
// higher one, so a preview's code is the minutes from 2026-01-01 to its commit: the newest commit always installs.
val previewCode = (git("log", "-1", "--format=%ct").toLongOrNull() ?: 0L).let { ((it - 1_767_225_600L) / 60).coerceAtLeast(1L).toInt() }
// The task a test build comes from, -Pfb.testLabel=ux: "0.3.4-test.ux+701fa57".
val previewLabel = providers.gradleProperty("fb.testLabel").orNull?.also {
    require(Regex("[a-z0-9]{1,12}").matches(it)) { "fb.testLabel: 1 to 12 lowercase letters or digits" }
}?.let { ".$it" }.orEmpty()

// The test app's key: the keystore in base64 and its password (one for the store and the key), from the environment.
// Only a preview build reads them, so tests, lint and the release build need neither.
val previewKeystoreB64 = providers.environmentVariable("FB_PREVIEW_KEYSTORE_B64")
val previewPassword = providers.environmentVariable("FB_PREVIEW_KEYSTORE_PASSWORD")
val previewKeystore = layout.buildDirectory.file("preview-signing/preview.jks")
val writePreviewKeystore = tasks.register("writePreviewKeystore") {
    description = "Writes the test app's keystore from FB_PREVIEW_KEYSTORE_B64 into build/ for the preview signing."
    val encoded = previewKeystoreB64
    val password = previewPassword
    val out = previewKeystore
    outputs.file(out)
    outputs.upToDateWhen { false }  // the key can change between builds; it is never part of the task's history
    doLast {
        if (encoded.orNull.isNullOrBlank() || password.orNull.isNullOrBlank()) {
            throw GradleException(
                "The test app's key is not set: add FB_PREVIEW_KEYSTORE_B64 and FB_PREVIEW_KEYSTORE_PASSWORD to the " +
                    "environment (docs/android-app.md, \"Test builds\")."
            )
        }
        val bytes = Base64.getMimeDecoder().decode(encoded.get().trim())
        out.get().asFile.apply { parentFile.mkdirs(); writeBytes(bytes) }
    }
}
tasks.matching { it.name == "validateSigningPreview" || it.name == "packagePreview" }.configureEach {
    dependsOn(writePreviewKeystore)
}

androidComponents.onVariants(androidComponents.selector().withBuildType("preview")) { variant ->
    variant.outputs.forEach { it.versionCode.set(previewCode) }
}

android {
    namespace = "dev.zeffin"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.zeffin"
        minSdk = 34
        targetSdk = 36
        versionName = fbVersion + releaseSuffix
        // 0.1.0 -> 100, 1.12.4 -> 11204
        versionCode = fbVersion.split(".").map(String::toInt).let { (a, b, c) -> a * 10000 + b * 100 + c }
        manifestPlaceholders["appLabel"] = "Zeffin"
    }
    // Preview builds for quick tests on a phone: another app, dev.zeffin.test, beside the released one, so a test
    // never touches the real app's settings and pairing, signed with a key that is only for it. The key is not in
    // the repo, so nobody with a clone can sign a test app: the environment holds it, and
    // writePreviewKeystore puts it in build/ for the signing.
    signingConfigs {
        create("preview") {
            storeFile = previewKeystore.get().asFile
            storePassword = previewPassword.orNull
            keyAlias = "preview"
            keyPassword = previewPassword.orNull
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
        create("preview") {
            initWith(getByName("debug"))
            isDebuggable = false  // it holds real tokens: no debugger or run-as on the phone
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test$previewLabel+$previewCommit"
            manifestPlaceholders["appLabel"] = "Zeffin Test"
            signingConfig = signingConfigs.getByName("preview")
            matchingFallbacks += "debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    // THIRD_PARTY_NOTICES.md and the font licenses, shown in Acerca de.
    sourceSets.getByName("main").assets.srcDir("../licenses")
    // The /app examples and schemas (docs/protocol/app), read by the protocol tests as test resources.
    sourceSets.getByName("test").resources.srcDir("../../docs/protocol")
    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":relay"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tasks)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.datastore.preferences)  // PairingSaverTest: a real pairing store
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.turbine)
}

// Screenshot tests write their images (app/build/outputs/roborazzi/) on every test run.
tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", "true")
    // Declared as an output, so a test run restored from the build cache brings its screenshots back too
    // (otherwise compare-design-captures.py finds none).
    outputs.dir(layout.buildDirectory.dir("outputs/roborazzi"))
    // The chat end-to-end test (ChatEndToEndTest) runs only when android/scripts/e2e.sh starts the server.
    listOf("FB_E2E_URL", "FB_E2E_APP_TOKEN", "FB_E2E_CHAT").forEach { name -> System.getenv(name)?.let { environment(name, it) } }
    // -Pfb.skipSpanishScreenshots: the Tests workflow on pull requests to dev leaves out the Spanish screenshots,
    // which have no captures to compare, to keep the run within its time budget. Local runs and pull requests to
    // main take them all.
    if (providers.gradleProperty("fb.skipSpanishScreenshots").isPresent) {
        filter { excludeTestsMatching("*ScreenshotEsTest") }
    }
}
