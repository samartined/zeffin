// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.zeffin.attach.ThumbnailSource
import dev.zeffin.attach.Thumbnails
import dev.zeffin.link.AttachmentKind
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.FbSheet
import dev.zeffin.ui.components.Icon
import dev.zeffin.ui.components.Note
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbIcons
import dev.zeffin.ui.theme.FbType
import dev.zeffin.ui.present.AttachmentUi
import dev.zeffin.ui.present.TrayUi

val LocalThumbnails = staticCompositionLocalOf<ThumbnailSource?> { null }

@Composable
fun rememberThumbnail(a: AttachmentUi, maxPx: Int = 264): ImageBitmap? {
    val source = LocalThumbnails.current
    val image by produceState<ImageBitmap?>(null, a.conversation, a.id, source) {
        value = source?.load(a.conversation, a.id, maxPx)
    }
    return image
}

/** An attachment of a sent message: photos 132x99, Flipper screens on their orange paper, files as chips. */
@Composable
fun MessageAttachment(a: AttachmentUi, modifier: Modifier = Modifier) {
    if (a.kind != AttachmentKind.IMAGE) return AttachmentChip(a, modifier)
    val image = rememberThumbnail(a)
    if (a.isScreen) {
        val wide = (a.width ?: 2) >= (a.height ?: 1)
        Box(modifier.clip(RoundedCornerShape(10.dp)).background(FbColors.screenPaper).padding(4.dp)) {
            Box(Modifier.size(if (wide) 124.dp else 58.dp, if (wide) 58.dp else 124.dp)) {
                if (image != null) Image(image, a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            }
        }
    } else {
        Box(modifier.size(132.dp, 99.dp).clip(RoundedCornerShape(10.dp)).background(FbColors.surfaceSunken), contentAlignment = Alignment.Center) {
            if (image != null) Image(image, a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(FbIcons.image, FbColors.inkFaint, size = 28.dp)
        }
    }
}

/** The files attached to the next message, above the composer. Tap a failed one to try again. [preview]: a chip's decoded picture. */
@Composable
fun Tray(items: List<TrayUi>, preview: (Int) -> ImageBitmap?, onRemove: (Int) -> Unit, onRetry: (Int) -> Unit, note: String? = null) {
    if (items.isEmpty()) return
    LazyRow(Modifier.fillMaxWidth().background(FbColors.surfaceRaised).padding(top = 8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.localId }) { t ->
            val chip = if (t.error != null) t.chip.copy(meta = t.error) else t.chip
            AttachmentChip(chip, Modifier.clickable(enabled = t.error != null, role = Role.Button) { onRetry(t.localId) },
                onRemove = { onRemove(t.localId) }, progress = t.progress,
                thumb = (if (t.hasPreview) preview(t.localId) else null)?.let { p -> { Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } },
                error = t.error != null)
        }
    }
    if (note != null) Txt(note, FbType.caption, Modifier.fillMaxWidth().background(FbColors.surfaceRaised).padding(start = 14.dp, end = 14.dp, top = 6.dp),
        color = FbColors.inkFaint)
}

enum class AttachSource { CAMERA, GALLERY, FILE, FLIPPER }

/** Attach: four large buttons and the limits. */
@Composable
fun BoxScope.AttachSheet(flipperScreen: Boolean, onPick: (AttachSource?) -> Unit) {
    val l = LocalLang.current
    FbSheet(l.t("Attach", "Adjuntar"), l.t("For your next message", "Para tu próximo mensaje"), onDismiss = { onPick(null) }) {
        val buttons: List<Triple<AttachSource, ImageVector, String>> = listOf(
            Triple(AttachSource.CAMERA, FbIcons.camera, l.t("Take photo", "Hacer foto")),
            Triple(AttachSource.GALLERY, FbIcons.image, l.t("Gallery", "Galería")),
            Triple(AttachSource.FILE, FbIcons.file, l.t("File", "Fichero")),
            Triple(AttachSource.FLIPPER, FbIcons.flipper, l.t("Flipper screen", "Pantalla del Flipper")),
        )
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (pair in buttons.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((source, icon, label) in pair) {
                    val enabled = source != AttachSource.FLIPPER || flipperScreen
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).border(1.dp, FbColors.line, RoundedCornerShape(16.dp))
                        .background(FbColors.surface).clickable(enabled = enabled, role = Role.Button) { onPick(source) }
                        .padding(vertical = 16.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(icon, if (enabled) FbColors.accent else FbColors.line, size = 28.dp)
                        Txt(label, FbType.bodyStrong, color = if (enabled) FbColors.ink else FbColors.inkFaint, align = TextAlign.Center)
                    }
                }
            }
        }
        Note(l.t("Photos are reduced to 2000 px before they are sent. Flipper screen attaches the screen shown above. " +
            "Up to 10 files of 10 MB each; images go to the agent as images, other files are saved on the server for the agent to read.",
            "Las fotos se reducen a 2000 px antes de enviarse. Pantalla del Flipper adjunta la pantalla que ves arriba. " +
                "Hasta 10 ficheros de 10 MB cada uno; las imágenes le llegan al agente como imágenes, los demás ficheros se guardan en el servidor para que el agente los lea."),
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
    }
}

/** An image of the chat, full screen; a tap or Back closes it. */
@Composable
fun BoxScope.ImageViewer(a: AttachmentUi, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val image = rememberThumbnail(a, maxPx = 2000)
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClickLabel = LocalLang.current.t("Close", "Cerrar"), onClick = onClose),
        contentAlignment = Alignment.Center) {
        if (image != null) Image(image, a.name, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit,
            filterQuality = if (a.isScreen) FilterQuality.None else FilterQuality.Medium)
        else Txt(a.name, FbType.body, color = FbColors.inkMuted)
        Txt(a.name + " · " + a.meta, FbType.caption, Modifier.align(Alignment.BottomCenter).padding(16.dp), color = FbColors.inkMuted)
    }
}

/** A small preview of a picked image for its chip, decoded off the main thread. */
suspend fun previewOf(bytes: ByteArray): ImageBitmap? = Thumbnails.decodeOffMain(bytes, 132)

