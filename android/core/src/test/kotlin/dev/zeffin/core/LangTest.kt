// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LangTest {
    @Test fun picksTheFirstSupportedLanguage() {
        assertEquals(Lang.ES, Lang.pick(listOf("es-ES")))
        assertEquals(Lang.ES, Lang.pick(listOf("fr-FR", "es-419", "en-US")))
        assertEquals(Lang.EN, Lang.pick(listOf("en-GB", "es")))
        assertEquals(Lang.EN, Lang.pick(listOf("de-DE")))
        assertEquals(Lang.EN, Lang.pick(emptyList()))
    }
}
