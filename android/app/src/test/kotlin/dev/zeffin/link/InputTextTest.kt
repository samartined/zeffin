// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.link

import org.junit.Assert.assertEquals
import org.junit.Test

class InputTextTest {
    @Test fun lineAndParagraphSeparatorsAreSpacesInOneLineText() {
        // U+2028 and U+2029 are neither control nor format characters, but they break a line where they are drawn:
        // a path or a command name shown on one line must not hide a second line.
        assertEquals("/ext/a b c", inputText("/ext/a b c"))
        assertEquals("rm -rf / tmp", inputText("rm -rf / tmp"))
    }

    @Test fun lineAndParagraphSeparatorsArePlainLineBreaksInMultilineText() {
        assertEquals("touch a\ntouch b\n\nok", inputText("touch a touch b \nok", multiline = true))
    }
}
