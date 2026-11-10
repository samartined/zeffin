// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

/** The languages the app speaks. Anything else falls back to English. */
enum class Lang {
    EN, ES;

    /** The text in this language; English first. Keeping both side by side means none can be missing. */
    fun t(en: String, es: String): String = if (this == ES) es else en

    companion object {
        /** The first supported language in a preference list of BCP 47 tags ("es-ES", "en"…). */
        fun pick(tags: List<String>): Lang {
            for (tag in tags) {
                when (tag.substringBefore('-').substringBefore('_').lowercase()) {
                    "en" -> return EN
                    "es" -> return ES
                }
            }
            return EN
        }
    }
}
