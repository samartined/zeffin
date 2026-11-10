// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import dev.zeffin.ui.present.Face

/**
 * The icons of the approved mockups (docs/design/mockups/parts.py), 24x24, drawn in the
 * current color by the Icon composable. Stroked ones use a 2 px round line, like the mockups.
 */
object FbIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0z"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r},${y}h${w - 2 * r}a$r,$r 0 0 1 $r,${r}v${h - 2 * r}a$r,$r 0 0 1 ${-r},${r}h${-(w - 2 * r)}a$r,$r 0 0 1 ${-r},${-r}v${-(h - 2 * r)}a$r,$r 0 0 1 $r,${-r}z"

    private fun stroke(name: String, vararg paths: String, width: Float = 2f) = build(name, paths.toList(), stroke = width)
    private fun fill(name: String, vararg paths: String, evenOdd: Boolean = false) = build(name, paths.toList(), evenOdd = evenOdd)

    private fun build(name: String, paths: List<String>, stroke: Float? = null, evenOdd: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                val nodes = PathParser().parsePathString(d).toNodes()
                if (stroke != null) {
                    addPath(nodes, stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
                } else {
                    addPath(nodes, fill = SolidColor(Color.Black), pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero)
                }
            }
        }.build()

    val back = stroke("back", "M20 12H5M11 6l-6 6 6 6")
    val plus = stroke("plus", "M12 5v14M5 12h14")
    val trash = stroke("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")
    val refresh = stroke("refresh", "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5")
    val sync = refresh
    val eye = stroke("eye", "M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z", circle(12f, 12f, 3f))
    val eyeOff = stroke("eyeOff", "M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z", circle(12f, 12f, 3f), "M4 4l16 16")
    val close = stroke("close", "M6 6l12 12M18 6L6 18")
    val open = stroke("open", "M14 4h6v6M20 4l-9 9M18 14v6H4V6h6")
    /** Remote Control: a signal, symmetric so it sits centred in its pill. */
    val remote = stroke("remote", circle(12f, 12f, 2f), "M8 8.5a5 5 0 0 0 0 7", "M16 8.5a5 5 0 0 1 0 7",
        "M5 5.5a9.2 9.2 0 0 0 0 13", "M19 5.5a9.2 9.2 0 0 1 0 13")
    val chart = stroke("chart", "M4 19h16M4 15l4-4 4 3 4-6 4 4")
    val chevron = stroke("chev", "M9 6l6 6-6 6")
    val flipper = stroke("flipper", "M6.5 6.5h11a4.5 4.5 0 0 1 0 9h-11a4.5 4.5 0 0 1 0-9z", rect(5.5f, 9f, 7f, 4f, 1f), circle(16.5f, 11f, 1.6f))
    val bluetooth = stroke("bt", "M7 7l10 10-5 5V2l5 5L7 17")
    val copy = stroke("copy", rect(9f, 9f, 11f, 11f, 2f), "M5 15V5a1 1 0 0 1 1-1h10")
    val list = stroke("list", "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01")
    val settings = stroke("settings", circle(12f, 12f, 3f), "M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2")
    val check = stroke("check", "M5 12l5 5 9-10")
    val pencil = stroke("pencil", "M4 20h4L19 9l-4-4L4 16v4zM14 6l4 4")
    val qr = stroke("qr", rect(4f, 4f, 6f, 6f, 1f), rect(14f, 4f, 6f, 6f, 1f), rect(4f, 14f, 6f, 6f, 1f), "M14 14h2v2h-2zM18 18h2v2h-2zM14 18h2M18 14h2")
    val battery = stroke("battery", rect(3f, 7f, 16f, 10f, 2f), "M21 11v2M7 10v4M11 10v4")
    val info = stroke("info", circle(12f, 12f, 9f), "M12 11v5M12 8h.01")
    // The chat (the approved mockups)
    val send = stroke("send", "M5 12h14M13 6l6 6-6 6")
    val stopFill = fill("stop_fill", rect(7f, 7f, 10f, 10f, 2f))
    val stop = stroke("stop", rect(6f, 6f, 12f, 12f, 2f))
    val chevronDown = stroke("chev_down", "M6 9l6 6 6-6")
    val clip = stroke("clip", "M20 11.5l-8.2 8.2a5 5 0 0 1-7.1-7.1l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7l-8.5 8.5a1.7 1.7 0 0 1-2.4-2.4l7.8-7.8")
    val camera = stroke("camera", "M4 8h3l2-3h6l2 3h3v11H4z", circle(12f, 13f, 3.5f))
    val image = stroke("image", rect(3.5f, 4.5f, 17f, 15f, 2f), "M3.5 16l5-5 4 4 3-3 5 5", circle(15.5f, 9f, 1.5f))
    val file = stroke("file", "M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6")
    val search = stroke("search", circle(11f, 11f, 7f), "M20 20l-4-4")
    val tune = stroke("tune", "M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1", circle(15f, 6f, 2f), circle(9f, 12f, 2f), circle(17f, 18f, 2f))
    val pin = stroke("pin", "M9 4h6l-1 5 3 3v2H7v-2l3-3-1-5zM12 14v6")
    val unpin = stroke("unpin", "M9 4h6l-1 5 3 3v2H7v-2l3-3-1-5zM12 14v6M4 4l16 16")
    val unstar = stroke("unstar", "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9zM4 4l16 16")
    val selectAll = stroke("select_all", rect(3.5f, 3.5f, 17f, 17f, 3f), "M8 12l3 3 5-6")
    val star = stroke("star", "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z")
    val starFill = fill("star_fill", "M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z")

    // Bottom bar (filled when active, outlined when not): the character's face (docs/design/system/components/Character.md)
    private const val SCREEN = "M5,6H19A3,3 0 0 1 22,9V15A3,3 0 0 1 19,18H5A3,3 0 0 1 2,15V9A3,3 0 0 1 5,6z"
    private const val BUBBLE = "M5,5H19A3,3 0 0 1 22,8V14A3,3 0 0 1 19,17H9L5,20.5V17A3,3 0 0 1 2,14V8A3,3 0 0 1 5,5z"

    /** The face at 24 px: two round eyes, centered on [y]. */
    private fun face(y: Float) = circle(8.5f, y, 1.6f) + circle(15.5f, y, 1.6f)

    /** An outline ([strokes], each with its width) with [fills] on top: the outlined icons, the face filled. */
    private fun outlined(name: String, strokes: List<Pair<String, Float>>, fills: String? = null): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for ((d, width) in strokes) addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black),
                strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            if (fills != null) addPath(PathParser().parsePathString(fills).toNodes(), fill = SolidColor(Color.Black))
        }.build()

    private val homeOn = fill("home_on", SCREEN + face(12f), evenOdd = true)
    private val homeOff = outlined("home_off", listOf(SCREEN to 2f), face(12f))
    private val connectingOn = fill("home_connecting_on", SCREEN + face(10.6f), evenOdd = true)
    private val connectingOff = outlined("home_connecting_off", listOf(SCREEN to 2f), face(10.6f))
    private val offline = outlined("home_offline", listOf(SCREEN to 2f, circle(8.5f, 12f, 1.5f) to 1.4f, circle(15.5f, 12f, 1.5f) to 1.4f))

    /** Home's tab: the screen with the face; its eye looks up while connecting and is empty with no relay. */
    fun homeFace(face: Face, active: Boolean): ImageVector = when (face) {
        Face.CONNECTING -> if (active) connectingOn else connectingOff
        Face.OFFLINE -> offline
        else -> if (active) homeOn else homeOff
    }

    val chatOn = fill("chat_on", BUBBLE + face(11f), evenOdd = true)
    val chatOff = outlined("chat_off", listOf(BUBBLE to 2f), face(11f))
    val settingsOn = fill("settings_on", SCREEN + "M6.5,11h3.4a1,1 0 0 1 0,2h-3.4a1,1 0 0 1 0,-2zM14.1,11h3.4a1,1 0 0 1 0,2h-3.4a1,1 0 0 1 0,-2z",
        evenOdd = true)
    val settingsOff = outlined("settings_off", listOf(SCREEN to 2f, "M7.2,12H9.4M14.6,12H16.8" to 2f))
}
