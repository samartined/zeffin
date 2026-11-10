// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

/**
 * The server's home folder written as "~" where a permission card or notification shows a path or a rule (hello's
 * server.home; display only: the answer sends the rules the server offered, as they are). Only the folder itself or a
 * path inside it: "/home/demo2/x" or "/srv/home/demo/x" stay as they are for the home "/home/demo".
 */
object HomePath {
    /** The home folder, usable: absolute, not the root; without a trailing slash. */
    private fun usable(home: String?): String? = home?.trimEnd('/')?.takeIf { home.startsWith('/') && it.isNotEmpty() }

    /** [path] with [home] as "~", when it is that folder or inside it. */
    fun path(path: String, home: String?): String {
        val h = usable(home) ?: return path
        return when {
            path == h -> "~"
            path.startsWith("$h/") -> "~" + path.substring(h.length)
            else -> path
        }
    }

    /**
     * A rule ("Edit(/home/demo/a.txt)", "Bash(cat /home/demo/a.txt)") with [home] as "~" wherever it starts a path:
     * after the rule's "(", a space, "=", ":" or a quote, and followed by "/", the end, ")", a space or a quote.
     * "//home/demo" (Claude Code's absolute form) stays as it is.
     */
    fun rule(rule: String, home: String?): String {
        val h = usable(home) ?: return rule
        return Regex("(?<=^|[\\s(=:\"'])" + Regex.escape(h) + "(?=/|$|[\\s)\"'])").replace(rule, "~")
    }
}
