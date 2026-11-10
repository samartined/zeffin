// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The app's package rules (docs/architecture.md, "Android app"), read from the sources as server/tests/test_layers.py
 * reads the server's. The rule on the relay's internals is detekt's ForbiddenImport (config/detekt.yml).
 */
class AppLayersTest {
    private val root = File("src/main/kotlin/dev/zeffin")

    /** Every package of the app, by its folder; a new one goes here, with what it never uses. */
    private val forbidden: Map<String, List<String>> = mapOf(
        "" to emptyList(),
        "link" to SCREENS,
        "notify" to SCREENS,
        "attach" to SCREENS + "dev.zeffin.ui.present.",
        "ui" to emptyList(),
        "ui/present" to SCREENS + listOf("androidx.compose.", "dev.zeffin.notify."),
        "ui/routes" to emptyList(),
        "ui/screens" to emptyList(),
        "ui/components" to emptyList(),
        "ui/theme" to emptyList(),
    )

    /** The only classes that reach the graph through the Context: Android creates them, so nothing can hand it to them. */
    private val graphUsers = setOf("MainActivity.kt", "notify/NotifyService.kt", "notify/NotifyReceiver.kt")

    private fun sources(): List<File> = root.walk().filter { it.isFile && it.extension == "kt" }.toList()

    private fun packageOf(f: File) = f.parentFile.relativeTo(root).invariantSeparatorsPath

    /** The code without comments, so a KDoc that names a class is not a use. */
    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().map { it.substringBefore("//") }.joinToString("\n")

    @Test
    fun `every package has its rules`() {
        assertEquals(emptySet<String>(), sources().map(::packageOf).toSet() - forbidden.keys)
    }

    @Test
    fun `no package uses what it must not`() {
        val found = sources().flatMap { f ->
            val text = code(f)
            forbidden.getValue(packageOf(f)).filter { text.contains(it) }.map { "${f.relativeTo(root)}: $it" }
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `only the classes Android creates use graph`() {
        val found = sources().filter { f ->
            val path = f.relativeTo(root).invariantSeparatorsPath
            val text = code(f)
            path !in graphUsers && path != "AppGraph.kt" &&
                (text.contains("import dev.zeffin.graph") || Regex("""\.graph\b""").containsMatchIn(text))
        }.map { it.relativeTo(root).invariantSeparatorsPath }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `the relay's RPC codec stays inside the relay`() {
        // relay.rpc reads and writes the Flipper's bytes for the remote: the app gets RelayFrame and keys from RelayController
        val found = sources().filter { "dev.zeffin.relay.rpc" in code(it) }.map { it.relativeTo(root).invariantSeparatorsPath }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `nothing reads chat status's claude field`() {
        // chat.status's "claude" is Claude Code's own (deprecated): the app reads login and hello's agent instead
        val found = sources().filter { packageOf(it).let { p -> p == "link" || p.startsWith("ui") } }
            .filter { Regex(""""claude"""").containsMatchIn(code(it)) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `nothing reads a field or model named fable`() {
        // chat.status's "fable" is Claude Code's own (deprecated): each model carries its availability
        val found = sources().filter { packageOf(it).let { p -> p == "link" || p.startsWith("ui") } }
            .filter { Regex(""""fable"""").containsMatchIn(code(it)) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
        assertEquals(emptyList<String>(), found)
    }

    private companion object {
        /** Compose screens, components and the theme: only ui draws. */
        val SCREENS = listOf("dev.zeffin.ui.screens.", "dev.zeffin.ui.components.", "dev.zeffin.ui.theme.")
    }
}
