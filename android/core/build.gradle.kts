// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.zeffin.core"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
}
