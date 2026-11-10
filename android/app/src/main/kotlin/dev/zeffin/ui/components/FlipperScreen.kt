// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.components

import dev.zeffin.ui.LocalLang
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.zeffin.link.FlipperFrame
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.present.ScreenMode

/** A decoded Flipper screen as an image, one pixel per Flipper pixel. */
fun flipperBitmap(p: FlipperFrame.Pixels): ImageBitmap {
    val bmp = android.graphics.Bitmap.createBitmap(p.width, p.height, android.graphics.Bitmap.Config.ARGB_8888)
    bmp.setPixels(IntArray(p.dark.size) { if (p.dark[it]) FlipperFrame.INK else FlipperFrame.PAPER }, 0, p.width, 0, 0, p.width, p.height)
    return bmp.asImageBitmap()
}

/**
 * Orange LCD with black pixels inside a half-orange frame: 3 dp border, 6 dp gap, 12 dp
 * inner radius. Drawn at an integer scale without smoothing.
 */
@Composable
fun FlipperScreen(
    data: ByteArray?,
    orientation: Int,
    mode: ScreenMode,
    badge: String? = null,
    scale: Int = 2,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    val pixels = remember(data, orientation) { data?.let { FlipperFrame.decode(it, orientation) } }
    val image = remember(pixels) { pixels?.let(::flipperBitmap) }
    val w = (pixels?.width ?: FlipperFrame.WIDTH) * scale
    val h = (pixels?.height ?: FlipperFrame.HEIGHT) * scale
    val label = description ?: LocalLang.current.t("Flipper screen", "Pantalla del Flipper")
    Box(modifier.border(3.dp, FbColors.screenBezel, RoundedCornerShape(16.dp)).padding(9.dp).semantics { contentDescription = label }) {
        val lcd = Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (mode == ScreenMode.OFF || image == null) FbColors.line else FbColors.screenPaper)
            .padding(8.dp)
        Box(lcd.then(if (mode == ScreenMode.OFF) Modifier.alpha(0.5f) else if (mode == ScreenMode.STALE) Modifier.alpha(0.4f) else Modifier)) {
            Canvas(Modifier.size(w.dp, h.dp)) {
                if (image != null && mode != ScreenMode.OFF) {
                    drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero,
                        filterQuality = FilterQuality.None)
                }
            }
        }
        if (badge != null) ScreenBadge(badge, Modifier.align(Alignment.Center))
    }
}

/** The label over a Flipper screen that is old or has no image: warning text on black. */
@Composable
fun ScreenBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f))
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
        Txt(text, FbType.label, color = FbColors.warning, maxLines = 1)
    }
}
