// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Not applied (AGP 9 builds Kotlin itself); declared to pin the Kotlin version.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
}

// Robolectric (SDK 36) reaches into java.io and jdk.internal.access on JDK 21.
subprojects {
    tasks.withType<Test>().configureEach {
        jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        // Robolectric downloads its Android images itself; Google's Maven Central mirror avoids 429s.
        systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
    }
}

// detekt, check-only: `./gradlew detekt` (config/detekt.yml, findings already there in config/detekt-baseline.xml).
// Its command-line tool runs on its own classpath: the Gradle plugin of detekt's stable line predates AGP 9's built-in
// Kotlin. `./gradlew detektBaseline` rewrites the baseline; only for an agreed rule change.
val detektCli: Configuration by configurations.creating
dependencies { detektCli(libs.detekt.cli) }

fun JavaExec.detekt(vararg extra: String) {
    group = "verification"
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args("--input", listOf("app/src", "core/src", "relay/src").joinToString(",") { file(it).path },
        "--build-upon-default-config", "--config", file("config/detekt.yml").path,
        "--baseline", file("config/detekt-baseline.xml").path, *extra)
}

tasks.register<JavaExec>("detekt") {
    description = "Static analysis of the Kotlin sources with detekt; fails on a finding not in the baseline."
    detekt()
}

tasks.register<JavaExec>("detektBaseline") {
    description = "Writes every current detekt finding to config/detekt-baseline.xml."
    detekt("--create-baseline")
}
