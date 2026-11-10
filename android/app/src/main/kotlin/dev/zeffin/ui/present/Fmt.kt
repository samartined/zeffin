// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.present

import dev.zeffin.core.Lang
import dev.zeffin.relay.RelayStrings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Times and amounts in the phone's time zone; words in the given language. */
object Fmt {
    var zone: ZoneId = ZoneId.systemDefault()
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun hm(epochMs: Long): String = hm.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun hms(epochMs: Long): String = hms.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun hmSeconds(epochS: Double) = hm((epochS * 1000).toLong())
    fun hmsSeconds(epochS: Double) = hms((epochS * 1000).toLong())

    /** "12 min", "45 s", "2 h 5 min". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h ${s % 3600 / 60} min"
        }
    }

    /** "now" / "ahora", "14 s ago" / "hace 14 s". */
    fun ago(lang: Lang, ms: Long): String = when {
        ms < 2_000 -> lang.t("now", "ahora")
        else -> lang.t("${duration(ms)} ago", "hace ${duration(ms)}")
    }

    fun bytes(lang: Lang, n: Long): String = RelayStrings.of(lang).bytes(n)

    /** "17,230" / "17.230": thousands grouped as each language writes them. */
    fun count(lang: Lang, n: Int): String =
        n.toString().reversed().chunked(3).joinToString(lang.t(",", ".")).reversed()

    /** "1.4 s" / "1,4 s" */
    fun seconds(lang: Lang, ms: Long): String = RelayStrings.of(lang).decimal(ms / 1000.0) + " s"
}
