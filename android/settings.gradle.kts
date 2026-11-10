// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
pluginManagement {
    repositories {
        google()
        // Maven Central answers 429 now and then from some cloud machines; Google's mirror does not.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "zeffin"
include(":app", ":relay", ":core")
