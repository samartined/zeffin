// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui.routes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.zeffin.AppGraph
import dev.zeffin.attach.AttachFlow
import dev.zeffin.attach.AttachTray
import dev.zeffin.attach.ImageScaler
import dev.zeffin.attach.Upload
import dev.zeffin.link.AttachmentKind
import dev.zeffin.link.ChatState
import dev.zeffin.link.Frame
import dev.zeffin.ui.AppNavigator
import dev.zeffin.ui.Routes
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.present.AttachmentUi
import dev.zeffin.ui.present.ChatAction
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.present.PresentConversations
import dev.zeffin.ui.present.PresentRemote
import dev.zeffin.ui.present.keyTrouble
import dev.zeffin.ui.present.noTunnel
import dev.zeffin.ui.screens.AttachSheet
import dev.zeffin.ui.screens.AttachSource
import dev.zeffin.ui.screens.ChatCallbacks
import dev.zeffin.ui.screens.ChatSheet
import dev.zeffin.ui.screens.ConversationChat
import dev.zeffin.ui.screens.ConversationScreen
import dev.zeffin.ui.screens.DeleteConversationSheet
import dev.zeffin.ui.screens.ImageViewer
import dev.zeffin.ui.screens.LinkSheet
import dev.zeffin.ui.screens.LocalThumbnails
import dev.zeffin.ui.screens.Tray
import dev.zeffin.ui.screens.previewOf
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun ConversationRoute(graph: AppGraph, nav: AppNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val relay by graph.relay.runtime.state.collectAsState()
    val link by graph.link.state.collectAsState()
    val frame by graph.link.frame.collectAsState()
    val settings by graph.settings.collectAsState()
    val pairing by graph.pairing.collectAsState()
    val chatState by graph.chat.state.collectAsState()
    val streaming by graph.chat.streaming.collectAsState()
    val draft by graph.chat.draft.collectAsState()
    val trayItems by graph.tray.items.collectAsState()
    val now = rememberNow()
    val lang = LocalLang.current
    val present = remember(lang) { Present(lang) }
    val presentChat = remember(lang, chatState.agentName) { PresentChat(lang, chatState.agentName) }
    val presentConversations = remember(lang, chatState.agentName) { PresentConversations(lang, chatState.agentName) }
    val attach = remember(scope) { AttachFlow(graph.tray, scope, ChatForAttachments(graph)) }
    val pick = rememberPickers(attach) { graph.link.frame.value }
    val overlays = rememberSaveable(saver = ChatOverlays.Saver) { ChatOverlays() }
    val previews = rememberPreviews(trayItems)
    val baseUi = remember(chatState, streaming, lang, link.noTunnel, keyTrouble(link, pairing, relay)) { presentChat.withTunnel(presentChat.chatUi(chatState, streaming), chatState, link.noTunnel, keyTrouble(link, pairing, relay)) }
    val home = present.conversationHome(present.homeUi(relay, link, frame, settings, pairing, now))
    val chatUi = withCharacter(presentChat.composed(baseUi, chatState, trayItems, draft), chatState, streaming, home.face)
    LaunchedEffect(chatState.open?.id) { attach.opened(chatState.open?.id) }
    BackWhenDeleted(chatState, nav)
    val chatParts = ConversationChat(
        ui = chatUi, draft = draft, sheet = overlays.sheet,
        callbacks = chatCallbacks(graph, attach, overlays, context).copy(onBanner = { action -> banner(graph, nav, chatState, action, overlays) },
            onDraft = { graph.chat.setDraft(attach.draftChanged(graph.chat.draft.value, it, baseUi.composer.canAttach)) },
            remoteLabel = PresentRemote(lang, chatState.remote).fromElsewhere),
        remote = chatState.remote,
        onRemote = { on -> chatState.open?.id?.let { graph.chat.remote(it, on) } },
        onSheet = { overlays.open(it, graph, chatState) },
        onEffort = { graph.chat.set(effort = it) },
        onModel = { graph.chat.set(model = it) },
        onMode = { graph.chat.set(mode = it) },
        onLoadOlder = graph.chat::loadOlder,
        tray = { Tray(presentChat.trayUi(trayItems), { previews[it] }, graph.tray::remove, graph.tray::retry, presentChat.trayNote(trayItems)) },
        attachSheet = attachSheet(frame != null, overlays, pick),
        linkSheet = { overlays.link?.let { url -> LinkSheetFor(url, overlays, context) } },
        deleteSheet = deleteSheet(graph, nav, chatState, overlays, presentConversations),
        overlay = { overlays.viewing?.let { ImageViewer(it) { overlays.viewing = null } } },
    )
    CompositionLocalProvider(LocalThumbnails provides graph.thumbnails) {
        ConversationScreen(
            home, chatParts, frame?.data,
            orientation = shownOrientation(settings, frame),
            landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
            large = settings.chatScreenLarge,
            onToggleScreen = { scope.launch { graph.core.settings.setChatScreenLarge(!settings.chatScreenLarge) } },
            onBack = nav::back,
        )
    }
}

/** The chat as the attachments see it. */
private class ChatForAttachments(private val graph: AppGraph) : AttachFlow.Chat {
    override fun openId() = graph.chat.state.value.open?.id
    override fun newConversation(then: (String) -> Unit) = graph.chat.newConversation(then)
    override fun send(text: String, attachments: List<String>) = graph.chat.send(text, attachments)
}

/** The conversation's open sheet, the image being viewed and the tapped link; the sheet and the link survive a rotation. */
private class ChatOverlays(sheet: ChatSheet = ChatSheet.NONE, link: String? = null) {
    var sheet by mutableStateOf(sheet)
    var viewing by mutableStateOf<AttachmentUi?>(null)
    var link by mutableStateOf(link)

    /** The settings of a conversation not created yet: it is created first. */
    fun open(next: ChatSheet, graph: AppGraph, chatState: ChatState) {
        if (next == ChatSheet.SETTINGS && chatState.open == null) graph.chat.newConversation { sheet = ChatSheet.SETTINGS } else sheet = next
    }

    companion object {
        val Saver = listSaver<ChatOverlays, String>(
            save = { listOf(it.sheet.name, it.link ?: "") },
            restore = { ChatOverlays(ChatSheet.valueOf(it[0]), it[1].ifEmpty { null }) },
        )
    }
}

/** What the conversation asks of the app. */
private fun chatCallbacks(graph: AppGraph, attach: AttachFlow, overlays: ChatOverlays, context: Context) = ChatCallbacks(
    onSend = { attach.send(graph.chat.draft.value) },
    onInterrupt = { graph.chat.interrupt() },
    onAttach = { overlays.sheet = ChatSheet.ATTACH },
    onOpenAttachment = { if (it.kind == AttachmentKind.IMAGE) overlays.viewing = it },
    onAnswer = { id, answer -> graph.chat.answer(id, answer) },
    onRetryManual = { graph.chat.retryInManual() },
    onLoadOlder = graph.chat::loadOlder,
    onLink = { overlays.link = it; overlays.sheet = ChatSheet.LINK },
    onCopy = { context.copy(it, "Code") },
    onAgent = { graph.chat.chooseAgent(it) },
)

/** The banner's buttons; "busy" is the conversation where the agent works, or the one an error names. */
private fun banner(graph: AppGraph, nav: AppNavigator, chatState: ChatState, action: ChatAction, overlays: ChatOverlays) {
    val busy = chatState.status?.turn?.conversation ?: (chatState.error?.data?.get("conversation") as? JsonPrimitive)?.content
    when (action) {
        ChatAction.DISMISS -> graph.chat.dismissError()
        ChatAction.OPEN_BUSY -> { graph.chat.dismissError(); busy?.let(graph.chat::open) }
        ChatAction.STOP_BUSY -> busy?.let { graph.chat.interrupt(it) }
        ChatAction.PAIR -> nav.go(Routes.PAIR)
        ChatAction.CHOOSE_MODEL -> overlays.sheet = ChatSheet.MODEL
        ChatAction.CHOOSE_AGENT -> overlays.sheet = ChatSheet.AGENT
    }
}

/** Deleting this conversation asks first; null when the server cannot delete (an older one) or nothing is open. */
private fun deleteSheet(graph: AppGraph, nav: AppNavigator, chatState: ChatState, overlays: ChatOverlays,
                        words: PresentConversations): (@Composable BoxScope.() -> Unit)? {
    val current = chatState.current
    if (current == null || !chatState.deletion) return null
    return {
        DeleteConversationSheet(words.deleteSub(current), words.deleteText(current, named = false, busy = chatState.busyHere),
            onDelete = { if (graph.chat.delete(current.id)) { overlays.sheet = ChatSheet.NONE; nav.back() } },
            onDismiss = { overlays.sheet = ChatSheet.NONE })
    }
}

/** The Attach sheet: it closes, then the chosen source opens (the camera, the gallery, a file, the Flipper's screen). */
private fun attachSheet(flipperScreen: Boolean, overlays: ChatOverlays, pick: (AttachSource) -> Unit): @Composable BoxScope.() -> Unit = {
    AttachSheet(flipperScreen = flipperScreen) { source ->
        overlays.sheet = ChatSheet.NONE
        source?.let(pick)
    }
}

@Composable
private fun BoxScope.LinkSheetFor(url: String, overlays: ChatOverlays, context: Context) {
    LinkSheet(url, onOpen = {
        overlays.sheet = ChatSheet.NONE
        context.startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }, onCopy = { context.copy(url, "Link"); overlays.sheet = ChatSheet.NONE }, onDismiss = { overlays.sheet = ChatSheet.NONE })
}

/** Deleted from another app or zf chat while open here: back to where the owner came from. */
@Composable
private fun BackWhenDeleted(chatState: ChatState, nav: AppNavigator) {
    var shown by rememberSaveable { mutableStateOf(chatState.open?.id) }
    LaunchedEffect(chatState.open?.id, chatState.conversations) {
        val id = chatState.open?.id
        if (id != null) shown = id
        else if (shown != null && chatState.conversations.none { it.id == shown }) {
            shown = null
            nav.back()
        }
    }
}

/** The tray's pictures, decoded off the main thread as items arrive; a chip shows its preview once it is ready. */
@Composable
private fun rememberPreviews(items: List<Upload>): Map<Int, ImageBitmap?> {
    val previews = remember { mutableStateMapOf<Int, ImageBitmap?>() }
    LaunchedEffect(items) {
        for (u in items) if (u.localId !in previews) u.preview?.let { previews[u.localId] = previewOf(it.bytes) }
    }
    return previews
}

/** The Attach sheet's four sources: the camera app, the photo picker, files and the Flipper screen shown above. */
@Composable
private fun rememberPickers(attach: AttachFlow, frame: () -> Frame?): (AttachSource) -> Unit {
    val context = LocalContext.current
    val attachUris: (List<Uri>) -> Unit = { uris ->
        attach.attach(uris, prepare = { ImageScaler.prepare(context.contentResolver, it) },
            nameOf = { ImageScaler.displayName(context.contentResolver, it) })
    }
    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachTray.MAX_PER_MESSAGE), attachUris)
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), attachUris)
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { attachUris(listOf(it)) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) photoUri = takePhotoWith(context, takePhoto)
    }
    return { source ->
        when (source) {
            AttachSource.CAMERA -> if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                photoUri = takePhotoWith(context, takePhoto) else cameraPermission.launch(Manifest.permission.CAMERA)
            AttachSource.GALLERY -> pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            AttachSource.FILE -> pickFiles.launch(arrayOf("*/*"))
            AttachSource.FLIPPER -> frame()?.let { f -> attach.attachScreen(ImageScaler.flipperScreen(f.data, f.orientation)) }
        }
    }
}

/** Opens the phone's camera app to take a photo into the app's cache; returns where it will be. */
private fun takePhotoWith(context: Context, launcher: ActivityResultLauncher<Uri>): Uri? {
    val folder = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
    folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
    val file = java.io.File(folder, "IMG_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return runCatching { launcher.launch(uri); uri }.getOrNull()
}
