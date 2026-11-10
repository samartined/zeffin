// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class RelayLogTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Runs the log's file work only when the test says so, like a background thread that has not run yet. */
    private class Queued : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private val clock = object : Clock {
        var t = 1_000L
        override fun now() = t++
        override fun elapsed() = t
    }

    @Test fun theFileIsReadAndWrittenOnItsExecutorNeverOnTheCallersThread() {
        val file = tmp.newFile("relay-log.tsv").apply { writeText("1\tINFO\tbefore\n") }
        val io = Queued()
        val log = RelayLog(file, clock, io = io)
        assertEquals(emptyList<LogEntry>(), log.flow.value)  // nothing read yet
        log.info("started")
        assertEquals("1\tINFO\tbefore\n", file.readText())  // nothing written yet
        io.runAll()
        assertEquals(listOf("before", "started"), log.flow.value.map { it.text })
        assertEquals(listOf("before", "started"), file.readLines().map { it.split('\t')[2] })
    }

    @Test fun theFileIsRewrittenOnceItHoldsFiftyLinesMoreThanItKeeps() {
        val file = tmp.newFile("relay-log.tsv")
        val io = Queued()
        val log = RelayLog(file, clock, keep = 3, io = io)
        repeat(60) { log.info("line $it") }
        io.runAll()
        assertEquals(listOf("line 57", "line 58", "line 59"), log.flow.value.map { it.text })
        val lines = file.readLines()
        assertTrue("${lines.size} lines", lines.size in 3 until 3 + 50)
        val again = RelayLog(file, clock, keep = 3, io = Executor { it.run() })
        assertEquals(listOf("line 57", "line 58", "line 59"), again.flow.value.map { it.text })
    }

    @Test fun clearingEmptiesTheLogAndDeletesTheFileInOrder() {
        val file = tmp.newFile("relay-log.tsv")
        val io = Queued()
        val log = RelayLog(file, clock, io = io)
        log.info("one")
        log.clear()
        log.info("two")
        io.runAll()
        assertEquals(listOf("two"), log.flow.value.map { it.text })
        assertEquals(listOf("two"), file.readLines().map { it.split('\t')[2] })
    }
}
