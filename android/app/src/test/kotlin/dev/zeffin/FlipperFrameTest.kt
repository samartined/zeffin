// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import dev.zeffin.link.FlipperFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.imageio.ImageIO

/** The app decodes the framebuffer exactly like server/bridge/screen.py (reference images made by it). */
class FlipperFrameTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("screen/$name")!!

    @Test fun matchesTheServerInAllFourOrientations() {
        val data = resource("frame.bin").readBytes()
        for (o in 0..3) {
            val expected = ImageIO.read(resource("orientation-$o.png"))
            val p = FlipperFrame.decode(data, o)!!
            assertEquals("width $o", expected.width, p.width)
            assertEquals("height $o", expected.height, p.height)
            for (y in 0 until p.height) for (x in 0 until p.width) {
                val dark = (expected.getRGB(x, y) and 0xFF) < 128
                assertEquals("orientation $o pixel ($x, $y)", dark, p.dark[y * p.width + x])
            }
        }
    }

    @Test fun rejectsOtherSizes() {
        assertNull(FlipperFrame.decode(ByteArray(10), 0))
    }
}
