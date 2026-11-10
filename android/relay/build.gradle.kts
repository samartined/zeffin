// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.zeffin.relay"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
}

// The end-to-end test needs the Python server and the Flipper simulator; CI starts them
// and passes their addresses in FB_E2E_* environment variables. Without them it is skipped.
tasks.withType<Test>().configureEach {
    listOf("FB_E2E_URL", "FB_E2E_PHONE_TOKEN", "FB_E2E_APP_TOKEN", "FB_E2E_FLIPPER_PORT").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
