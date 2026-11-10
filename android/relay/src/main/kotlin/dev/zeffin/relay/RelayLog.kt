// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.core.cleanText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class LogLevel { OK, INFO, WARN, ERROR }

data class LogEntry(val time: Long, val level: LogLevel, val text: String)

/**
 * The relay's own log: state changes and one traffic summary per minute (a line per frame
 * would wipe the history in a minute at 10 frames per second). Kept in a private file,
 * at most [keep] entries. Tokens never reach it: callers only pass descriptions.
 *
 * The file is read and written on [io], one task at a time and in order, never on the caller's thread (the app
 * creates the log on the main thread). Entries added before the file has been read come after its entries.
 */
class RelayLog(
    private val file: File?,
    private val clock: Clock = Clock.System,
    private val keep: Int = 500,
    private val io: Executor = Executors.newSingleThreadExecutor { Thread(it, "relay-log").apply { isDaemon = true } },
) {
    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>()
    private val _flow = MutableStateFlow<List<LogEntry>>(emptyList())
    val flow: StateFlow<List<LogEntry>> = _flow.asStateFlow()
    /** Lines in the file beyond [entries]: it is rewritten once they reach [REWRITE_AFTER]. */
    private var stale = 0

    init {
        file?.let { f -> io.execute { load(f) } }
    }

    private fun load(file: File) {
        val lines = runCatching { file.takeIf { it.exists() }?.readLines() }.getOrNull().orEmpty()
        val loaded = lines.takeLast(keep).mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            val time = parts.getOrNull(0)?.toLongOrNull()
            val level = LogLevel.entries.firstOrNull { it.name == parts.getOrNull(1) }
            if (time != null && level != null && parts.size == 3) LogEntry(time, level, parts[2]) else null
        }
        synchronized(lock) {
            loaded.asReversed().forEach(entries::addFirst)
            val dropped = trim()
            stale = lines.size - loaded.size + dropped
            _flow.value = entries.toList()
        }
    }

    private fun trim(): Int {
        var dropped = 0
        while (entries.size > keep) {
            entries.removeFirst()
            dropped++
        }
        return dropped
    }

    fun add(level: LogLevel, text: String) {
        val entry = LogEntry(clock.now(), level, cleanText(text.replace('\t', ' '), 200) ?: return)
        synchronized(lock) {
            entries.addLast(entry)
            stale += trim()
            _flow.value = entries.toList()
            val f = file ?: return
            if (stale >= REWRITE_AFTER) {
                val all = entries.toList()
                stale = 0
                io.execute { runCatching { f.writeText(all.joinToString("") { format(it) }) } }
            } else {
                io.execute { runCatching { f.appendText(format(entry)) } }
            }
        }
    }

    fun ok(text: String) = add(LogLevel.OK, text)
    fun info(text: String) = add(LogLevel.INFO, text)
    fun warn(text: String) = add(LogLevel.WARN, text)
    fun error(text: String) = add(LogLevel.ERROR, text)

    fun clear() = synchronized(lock) {
        entries.clear()
        stale = 0
        _flow.value = emptyList()
        file?.let { f -> io.execute { runCatching { f.delete() } } }
    }

    private fun format(e: LogEntry) = "${e.time}\t${e.level.name}\t${e.text}\n"

    private companion object {
        const val REWRITE_AFTER = 50
    }
}
