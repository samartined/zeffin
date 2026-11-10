// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.link

/**
 * The Flipper framebuffer: 128x64, 1 bit per pixel, packed in 8 pages of 128 bytes; each byte is
 * a column of 8 pixels with the least significant bit on top; a set bit is a dark pixel. The
 * same decoding as server/bridge/screen.py.
 */
object FlipperFrame {
    const val WIDTH = 128
    const val HEIGHT = 64
    const val BYTES = WIDTH * HEIGHT / 8

    /** The LCD's colors as ARGB (design tokens screen-pixel and screen-paper); the theme and the attached PNG use them. */
    const val INK = 0xFF000000.toInt()
    const val PAPER = 0xFFFF8C29.toInt()

    /** Clockwise degrees for pb.gui.ScreenOrientation, as the view is turned.
     *  (screen.py uses PIL's counterclockwise angles: 2 -> 270, 3 -> 90; the result is the same.) */
    fun clockwise(orientation: Int): Int = when (orientation) {
        1 -> 180
        2 -> 90
        3 -> 270
        else -> 0
    }

    class Pixels(val width: Int, val height: Int, val dark: BooleanArray)

    /** Decodes and rotates; null if the data is not a framebuffer. */
    fun decode(data: ByteArray, orientation: Int): Pixels? {
        if (data.size != BYTES) return null
        val src = BooleanArray(WIDTH * HEIGHT)
        for (i in 0 until BYTES) {
            val b = data[i].toInt()
            val page = i / WIDTH
            val x = i % WIDTH
            for (bit in 0 until 8) if (b and (1 shl bit) != 0) src[(page * 8 + bit) * WIDTH + x] = true
        }
        return when (clockwise(orientation)) {
            180 -> Pixels(WIDTH, HEIGHT, BooleanArray(src.size) { src[src.size - 1 - it] })
            90 -> Pixels(HEIGHT, WIDTH, BooleanArray(src.size) { i ->
                val x = i % HEIGHT; val y = i / HEIGHT // destination (x, y) in a 64x128 image
                src[(HEIGHT - 1 - x) * WIDTH + y]
            })
            270 -> Pixels(HEIGHT, WIDTH, BooleanArray(src.size) { i ->
                val x = i % HEIGHT; val y = i / HEIGHT
                src[x * WIDTH + (WIDTH - 1 - y)]
            })
            else -> Pixels(WIDTH, HEIGHT, src)
        }
    }
}
