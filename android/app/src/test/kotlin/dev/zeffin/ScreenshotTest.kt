// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.zeffin.core.Addresses
import dev.zeffin.core.Lang
import dev.zeffin.link.PairExchange
import dev.zeffin.relay.NetError
import dev.zeffin.core.ScreenOrientation
import dev.zeffin.link.Conn
import dev.zeffin.ui.LocalLang
import dev.zeffin.ui.components.Txt
import dev.zeffin.ui.components.LocalTextCut
import dev.zeffin.ui.screens.AboutScreen
import dev.zeffin.ui.screens.FlipperRemoteScreen
import dev.zeffin.ui.screens.RemoteActions
import dev.zeffin.ui.screens.RemoteView
import dev.zeffin.ui.screens.HomeActions
import dev.zeffin.ui.screens.HomeView
import dev.zeffin.ui.components.GateActions
import dev.zeffin.ui.present.RemoteInputs
import dev.zeffin.ui.present.PresentFlipperRemote
import dev.zeffin.ui.present.RemoteGate
import dev.zeffin.relay.FlipperKey
import dev.zeffin.relay.KeyRefusal
import dev.zeffin.relay.KeysOff
import dev.zeffin.relay.RemoteKeys
import dev.zeffin.relay.ServerSide
import dev.zeffin.relay.Stage
import dev.zeffin.ui.screens.SettingsScreen
import dev.zeffin.ui.screens.ConversationsScreen
import dev.zeffin.ui.screens.AgentScreen
import dev.zeffin.ui.screens.AgentInfoScreen
import dev.zeffin.ui.screens.AgentsScreen
import dev.zeffin.ui.screens.DeleteManySheet
import dev.zeffin.ui.screens.SelectionActions
import dev.zeffin.ui.screens.SelectionWords
import dev.zeffin.ui.screens.Selecting
import dev.zeffin.ui.present.PresentAgents
import dev.zeffin.ui.present.PresentAgentChoice
import dev.zeffin.ui.screens.ConversationsSelecting
import dev.zeffin.link.ChatError
import dev.zeffin.link.Deletions
import dev.zeffin.ui.screens.DeleteConversationSheet
import dev.zeffin.ui.components.LocalStillFrames
import dev.zeffin.ui.screens.FiltersSheet
import androidx.compose.foundation.layout.BoxScope
import dev.zeffin.ui.screens.DiagnosticsScreen
import dev.zeffin.ui.screens.DeviceScreen
import dev.zeffin.ui.screens.PairScreen
import dev.zeffin.ui.present.PairActions
import dev.zeffin.ui.screens.ReplacePairingSheet
import dev.zeffin.ui.present.ServerActions
import dev.zeffin.ui.screens.HomeScreen
import dev.zeffin.ui.screens.LogScreen
import dev.zeffin.ui.screens.RelayScreen
import dev.zeffin.ui.screens.RelayServerScreen
import dev.zeffin.ui.screens.ServerScreen
import dev.zeffin.ui.theme.FbColors
import dev.zeffin.ui.theme.FbTheme
import dev.zeffin.ui.theme.FbType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.foundation.layout.fillMaxWidth
import dev.zeffin.ui.components.FbSheet
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.zeffin.Fixtures as F
import dev.zeffin.ChatFixtures as C
import dev.zeffin.link.ChatState
import dev.zeffin.link.Effort
import dev.zeffin.ui.screens.ChatCallbacks
import dev.zeffin.ui.screens.ChatSheet
import dev.zeffin.ui.screens.ConversationChat
import dev.zeffin.ui.screens.ConversationScreen
import dev.zeffin.ui.screens.LinkSheet
import dev.zeffin.ui.screens.AttachSheet
import dev.zeffin.ui.screens.LocalThumbnails
import dev.zeffin.ui.screens.Tray
import dev.zeffin.attach.Prepared
import dev.zeffin.attach.ThumbnailSource
import dev.zeffin.attach.Upload
import dev.zeffin.attach.ImageScaler
import dev.zeffin.attach.Thumbnails
import dev.zeffin.link.Attachment
import dev.zeffin.link.AttachmentKind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.zeffin.ui.present.BannerUi
import dev.zeffin.ui.present.ConversationFilters
import dev.zeffin.ui.present.DeviceListUi
import dev.zeffin.ui.present.DeviceUi
import dev.zeffin.ui.present.PairStepUi
import dev.zeffin.ui.present.PasteUi
import dev.zeffin.ui.present.Period
import dev.zeffin.ui.present.Present
import dev.zeffin.ui.present.PresentChat
import dev.zeffin.ui.present.PresentCharacter
import dev.zeffin.ui.present.PresentConversations
import dev.zeffin.ui.present.PresentRemote
import dev.zeffin.ui.present.RelayServerUi
import dev.zeffin.ui.present.keyTrouble
import dev.zeffin.ui.present.ReplaceUi
import dev.zeffin.ui.present.SortBy
import dev.zeffin.ui.present.Tone
import dev.zeffin.ui.present.noTunnel
import dev.zeffin.ui.present.Warnings

/**
 * Every screen and state at the size of the design captures (390x844 at 1x), in both languages:
 * app/build/outputs/roborazzi/en/ and es/. The English ones are compared side by side with
 * docs/design/captures/ before a release.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-mdpi")
abstract class ScreenshotTest(private val lang: Lang) {
    private val present = Present(lang)
    private val log = F.log(lang)
    private fun t(en: String, es: String) = lang.t(en, es)

    private fun shot(name: String, content: @Composable () -> Unit) {
        val last = mutableMapOf<Any, String?>()  // each text's last layout: its text when cut, else null
        captureRoboImage("build/outputs/roborazzi/${lang.name.lowercase()}/$name.png") {
            CompositionLocalProvider(LocalLang provides lang, LocalStillFrames provides true,
                LocalTextCut provides { at, text, cut -> last[at] = text.takeIf { cut } }) { FbTheme { content() } }
        }
        val cuts = last.values.filterNotNull().toSet()
        cuts.forEach { TextCuts.add(lang, name, it) }
        // No label, button or title of the app shows cut, in either language; only content in a one-line slot may
        assertEquals("$name: texts cut", emptySet<String>(), cuts - TextCuts.CONTENT)
    }

    /** The pairing behind Home's screenshots: one with the server's key, unless a test says otherwise. */
    private var homePairing = F.pairing

    /** Home with the card of the last conversation: idle by default, as most of the time. */
    private fun home(name: String, relay: dev.zeffin.relay.RelayState, link: dev.zeffin.link.AppLinkState,
                     frame: dev.zeffin.link.Frame? = F.frame, landscape: Boolean = false, chat: ChatState? = C.blocked()) = shot(name) {
        HomeScreen(present.homeUi(relay, link, frame, F.settings, homePairing, F.now), HomeView(frame?.data, frame?.orientation ?: 0, landscape),
            HomeActions(GateActions({}, {}, PresentFlipperRemote(lang).homeButton to {}), {}, {}, {}),
            last = chat?.let { words.lastConversation(it, homeStreaming) })
    }

    /** The agent's text arriving in Home's conversation, when a test says so. */
    private var homeStreaming: Map<String, String> = emptyMap()

    @Test fun homeMain() {
        homeStreaming = C.streaming
        home("home-01-main", F.relayRunning, F.link, chat = C.writing())
    }

    /** The character: the agent presses keys; Home's card says it works. */
    @Test fun homeWorking() = home("home-10-working", F.relayRunning, F.link, chat = C.pressing())

    /** The relay connects: the bottom bar's face looks up, and the screen without an image shows the antenna. */
    @Test fun homeConnecting() = home("home-11-connecting", F.relayRunning.copy(stage = Stage.CONNECTING_FLIPPER),
        F.link.copy(status = F.status.copy(connected = false)), frame = null, chat = C.ended("ok"))

    @Test fun homePermission() = home("home-03-permission", F.relayRunning, F.link, chat = C.asking())
    @Test fun homePermissionLong() = home("home-03b-permission-long", F.relayRunning, F.link, chat = C.askingLong())
    @Test fun homePermissionEdit() = home("home-03c-permission-edit", F.relayRunning, F.link, chat = C.askingEdit())
    @Test fun homePermissionTooLong() = home("home-03d-permission-too-long", F.relayRunning, F.link, chat = C.askingTooLong())

    @Test fun homePaused() = home("home-02-paused", F.relayRunning, F.link.copy(
        status = F.status.copy(gate = "paused", gateBy = "app", gateChangedAt = F.now / 1000.0 - 148),
        audit = F.audit + dev.zeffin.link.AuditItem(F.now / 1000.0 - 148, "operator", null, "ok", null, null, "pause", "app") +
            dev.zeffin.link.AuditItem(F.now / 1000.0 - 120, "flipper_press", "keys=OK", "refused: gate", 2, 418, null, null)))

    @Test fun homeStopped() = home("home-02b-stopped", F.relayStopped, F.link.copy(
        status = F.status.copy(gate = "stopped", gateBy = "view", connected = false, phoneAttached = false, gateChangedAt = F.now / 1000.0 - 60)))

    @Test fun homeStale() = home("home-04-stale", F.relayRunning, F.link.copy(status = F.status.copy(screenStale = true, screenAgeS = 14.0)),
        F.frame.copy(stale = true))

    @Test fun homeNoRelay() = home("home-05-no-relay", F.relayStopped, F.link.copy(status = F.status.copy(connected = false, phoneAttached = false)))

    @Test fun homeNoServer() = home("home-05b-no-server", F.relayRunning, F.link.copy(conn = Conn.OFFLINE, status = null), chat = null)

    /** The private network is not connected on this phone, and nothing was sent (Home, the conversation, Relay, Pair). */
    @Test fun homeNoTunnel() = home("tunnel-01-home", F.relayNoTunnel, F.linkNoTunnel, chat = null)
    @Test fun conversationNoTunnel() {
        appLink = F.linkNoTunnel
        conversation("tunnel-02-conversation", C.blocked().copy(connected = false))
    }
    @Test fun relayNoTunnel() = relay("tunnel-03-relay", F.relayNoTunnel)
    @Test fun pairNoTunnel() = shot("tunnel-04-pair") { pair(banner = present.pairNoTunnel()) }

    @Test @Config(qualifiers = "w844dp-h390dp-land-mdpi")
    fun homeLandscape() = home("home-08-landscape", F.relayRunning, F.link, landscape = true)

    // ---- the remote: the same pad, in the same place, in every state
    private val remoteWords = PresentFlipperRemote(lang)
    private val pausedByRemote = F.link.copy(status = F.status.copy(gate = "paused", gateBy = "remote"))
    private val gateByRemote = RemoteGate(started = true, sentAt = F.now - 2_000, pausedByRemote = true)

    private val remoteBase = RemoteInputs(F.relayRunning, pausedByRemote, gateByRemote, RemoteKeys.Ready, null, showRefusal = false,
        hasFrame = true, nowMs = F.now)

    private fun remote(name: String, inputs: RemoteInputs = remoteBase, landscape: Boolean = false, leaving: Boolean = false) = shot(name) {
        val frame = F.frameData.takeIf { inputs.hasFrame }
        FlipperRemoteScreen(remoteWords.ui(inputs), remoteWords, RemoteView(frame, 0, landscape, leaving),
            RemoteActions({}, { _, _ -> }, {}, {}, {}, {}))
    }

    @Test fun remotePortrait() = remote("remote-01-portrait")
    @Test @Config(qualifiers = "w844dp-h390dp-land-mdpi")
    fun remoteLandscape() = remote("remote-02-landscape", landscape = true)
    @Test fun remoteCannotPause() = remote("remote-03-cannot-pause",
        remoteBase.copy(link = F.link.copy(conn = Conn.OFFLINE), gate = RemoteGate(started = true, sentAt = F.now - 1_000)))
    @Test fun remoteRefused() = remote("remote-04-refused", remoteBase.copy(showRefusal = true,
        refused = KeyRefusal(FlipperKey.OK, long = false, status = 17, statusName = "ERROR_APP_SYSTEM_LOCKED", serial = 1)))
    @Test fun remoteLeaveResume() = remote("remote-05-leave-resume", leaving = true)
    @Test fun remoteWaitingFlipper() = remote("remote-06-waiting-flipper", remoteBase.copy(keys = RemoteKeys.Off(KeysOff.WAITING_FLIPPER),
        link = pausedByRemote.copy(status = pausedByRemote.status!!.copy(busy = true))))
    @Test fun remoteFinishingTransfer() = remote("remote-07-finishing-transfer", remoteBase.copy(keys = RemoteKeys.Off(KeysOff.SERVER_COMMAND_OPEN)))
    // without a server session (A2): the relay holds the Flipper for the remote alone, and the keys work at once
    @Test fun remoteNoServer() = remote("remote-08-no-server", remoteBase.copy(
        relay = F.relayRunning.copy(stage = Stage.REMOTE, serverSide = ServerSide.UNREACHABLE, sessionStartedAt = null),
        link = F.link.copy(conn = Conn.OFFLINE, status = null), gate = RemoteGate()))
    @Test fun remoteRelayOff() = remote("remote-09-relay-off", remoteBase.copy(relay = F.relayStopped,
        keys = RemoteKeys.Off(KeysOff.NO_SESSION), link = F.link.copy(conn = Conn.OFFLINE, status = null), gate = RemoteGate(),
        hasFrame = false))

    private val convWords = PresentConversations(lang, C.agent.name)
    private fun conversations(name: String, favorites: Boolean = false, state: ChatState = C.listing(),
                              sheet: @Composable BoxScope.() -> Unit = {}) = shot(name) {
        val filters = ConversationFilters.defaults(favorites)
        Box(Modifier.fillMaxSize()) {
            ConversationsScreen(convWords.conversationsUi(state, favorites, filters, "", F.now), present.nav(F.relayRunning, F.link), false, {})
            sheet()
        }
    }

    @Test fun conversationsList() = conversations("conversations-01-list")

    /** No conversation yet: the character above the hint. */
    @Test fun conversationsEmpty() = conversations("conversations-07-empty", state = C.listing().copy(conversations = emptyList()))
    @Test fun conversationsFavorites() = conversations("conversations-02-favorites", favorites = true)
    @Test fun conversationsFilters() = conversations("conversations-03-filters") {
        FiltersSheet(ConversationFilters(), convWords.sortName(SortBy.ACTIVITY), convWords.periodName(Period.ALL),
            convWords.count(C.listing(), false, ConversationFilters(), "", F.now), {}, {})
    }
    @Test fun conversationsDelete() = conversations("conversations-05-delete") {
        val nfc = C.list[1]
        DeleteConversationSheet(convWords.deleteSub(nfc), convWords.deleteText(nfc, named = true, busy = false), {}, {})
    }

    /** Settings, Agent with the server's agents listed: only where new conversations start, and the notifications. */
    private val withAgents get() = C.twoAgents(C.writing())
    private val agentPage get() = words.agentUi(withAgents).copy(prefs = choice.prefs(withAgents, words))
    @Test fun settingsAgent() = shot("settings-agent") { AgentScreen(agentPage, notifyOn, {}, {}) }
    private val notifyOn = dev.zeffin.notify.NotifySettingsUi(turns = true, permissions = true, allowed = true)
    @Test fun settingsAgentNotifications() = shot("settings-agent-02-notifications") {
        AgentScreen(agentPage, notifyOn, {}, {}, scroll = androidx.compose.foundation.ScrollState(Int.MAX_VALUE))
    }
    @Test fun settingsAgentNotificationsOff() = shot("settings-agent-03-notifications-off") {
        AgentScreen(agentPage, notifyOn.copy(allowed = false), {}, {}, scroll = androidx.compose.foundation.ScrollState(Int.MAX_VALUE))
    }

    // ---------------------------------------------------------------- the agents in Settings

    private val agentWords = PresentAgents(lang)
    private val choice = PresentAgentChoice(lang)
    private val signedOut get() = C.twoAgents(C.writing(), C.openCodeSignedOut)
    @Test fun agentsList() = shot("agents-01-list") { AgentsScreen(agentWords.rows(signedOut), agentWords.rowsNote, F.link.serverName, {}, {}) }
    @Test fun agentsListReady() = shot("agents-01b-list-ready") {
        AgentsScreen(agentWords.rows(C.twoAgents(C.writing())), agentWords.rowsNote, F.link.serverName, {}, {})
    }
    @Test fun agentsClaude() = shot("agents-02-claude") { AgentInfoScreen(agentWords.info(C.claudeCode), F.link.serverName, {}) }
    @Test fun agentsOpenCode() = shot("agents-03-opencode") { AgentInfoScreen(agentWords.info(C.openCode), F.link.serverName, {}) }
    @Test fun agentsSignedOut() = shot("agents-04-not-signed-in") { AgentInfoScreen(agentWords.info(C.openCodeSignedOut), F.link.serverName, {}) }

    // ---------------------------------------------------------------- the agent per conversation

    @Test fun agentNewSettings() = conversation("agent-01-new-settings", C.newConversation(), sheet = ChatSheet.SETTINGS)
    @Test fun agentList() = conversation("agent-02-list", C.newConversation(), sheet = ChatSheet.AGENT)
    @Test fun agentListSignedOut() = conversation("agent-02b-list-not-signed-in", C.newConversation(C.openCodeSignedOut), sheet = ChatSheet.AGENT)
    @Test fun agentListNotSetUp() = conversation("agent-02c-list-not-set-up", C.newConversation(C.openCodeNotSetUp), sheet = ChatSheet.AGENT)
    @Test fun agentOpenCodeSettings() = conversation("agent-03-opencode-settings", C.withOpenCode(), C.streaming, ChatSheet.SETTINGS)
    @Test fun agentClaudeSettings() = conversation("agent-03b-claude-settings", C.withClaudeCode(), sheet = ChatSheet.SETTINGS)
    @Test fun agentModelsImages() = conversation("agent-04-models-images", C.withOpenCode(), C.streaming, ChatSheet.MODEL)
    @Test fun agentModelsPaid() = conversation("agent-04c-models-paid", C.withPaidModel(), C.streaming, ChatSheet.MODEL)
    @Test fun agentOpenCodeModes() = conversation("agent-04b-opencode-modes", C.withOpenCode(), C.streaming, ChatSheet.MODE)
    @Test fun agentNew() = conversation("agent-05-new", C.newConversation())
    @Test fun agentNewOpenCode() = conversation("agent-05b-new-opencode", C.newConversation(default = "opencode"))
    @Test fun agentNewNotReady() = conversation("agent-05c-new-not-ready", C.newConversation(C.openCodeSignedOut, default = "opencode"))

    // ---------------------------------------------------------------- selecting several conversations

    private val selectable get() = C.listing().copy(deletion = true)
    /** The list a selection is made in: All or Favorites, a search, and the chat's state. */
    private data class Listed(val favorites: Boolean = false, val query: String = "", val state: ChatState = C.listing().copy(deletion = true))
    private fun selecting(name: String, ids: List<String>, listed: Listed = Listed(), sheet: @Composable BoxScope.() -> Unit = {}) = shot(name) {
        val (favorites, query, state) = listed
        val ui = convWords.conversationsUi(state, favorites, ConversationFilters.defaults(favorites), query, F.now)
        val selection = Selecting(convWords.selectionUi(state, ids.toSet(), convWords.shownIds(ui), favorites, query),
            SelectionWords(convWords.leaveLabel, convWords.selectAllLabel, convWords::pinLabel, convWords::favoriteLabel,
                convWords.renameLabel, convWords.copyLabel, convWords.deleteLabel) { "" }, SelectionActions(), convWords.deletionsUi(state, words))
        Box(Modifier.fillMaxSize()) {
            ConversationsSelecting(convWords.selecting(ui, ids.toSet()), selection, searching = query.isNotEmpty())
            sheet()
        }
    }
    private val saved = C.list[0].id
    private val nfc = C.list[1].id
    private val notes = C.list[3].id
    @Test fun selectOne() = selecting("select-01-one", listOf(nfc))
    @Test fun selectSeveral() = selecting("select-02-several", listOf(notes, saved, nfc))
    @Test fun selectAll() = selecting("select-03-all", C.list.map { it.id })
    @Test fun selectDelete() = selecting("select-04-delete", listOf(notes, saved, nfc)) {
        DeleteManySheet(convWords.deleteMany(selectable, listOf(notes, saved, nfc)), {}, {})
    }
    @Test fun selectPartlyDeleted() = selecting("select-05-partly-deleted", listOf(saved), Listed(state = selectable.copy(
        conversations = selectable.conversations.filter { it.id != notes && it.id != nfc },
        deletions = Deletions(listOf(notes, saved, nfc), emptySet(), mapOf(saved to ChatError("not_connected", null, "chat.delete"))))))
    @Test fun selectNoDelete() = selecting("select-06-no-delete", listOf(notes, saved, nfc), Listed(state = selectable.copy(deletion = false)))
    @Test fun selectSearch() = selecting("select-07-search", listOf(nfc), Listed(query = "nfc"))
    @Test fun selectFavorites() = selecting("select-08-favorites", listOf(notes, nfc), Listed(favorites = true))

    // ---------------------------------------------------------------- a conversation

    private val words = PresentChat(lang, C.agent.name)
    /** /app's state behind a conversation's screenshot: connected, unless a test says otherwise. */
    private var appLink = F.link
    /** The turn has just ended well: the chat's tail bounces (the route keeps it for 3 s). */
    private var justEnded = false

    private fun conversation(name: String, state: ChatState, streaming: Map<String, String> = emptyMap(), sheet: ChatSheet = ChatSheet.NONE,
                             dragging: Boolean = false, landscape: Boolean = false, keyboard: Boolean = false, draft: String = "",
                             tray: List<Upload> = emptyList(), large: Boolean = false, link: String? = null) = shot(name) {
        val ui = present.conversationHome(present.homeUi(F.relayRunning, appLink, F.frame, F.settings, F.pairing, F.now))
        // as the route: the words name the shown agent (the open conversation's, or the default one)
        val words = PresentChat(lang, state.agentName ?: C.agent.name)
        val chat = PresentCharacter(lang).chat(
            words.composed(words.withTunnel(words.chatUi(state, streaming), state, appLink.noTunnel, keyTrouble(appLink, F.pairing)), state, tray, draft),
            state, streaming, ui.face, justEnded)
        val trayUi = words.trayUi(tray)
        CompositionLocalProvider(LocalThumbnails provides FakeThumbnails) {
            ConversationScreen(ui, ConversationChat(chat, draft, ChatCallbacks(remoteLabel = PresentRemote(lang, state.remote).fromElsewhere),
                sheet, dialDragging = dragging, keyboardOpen = keyboard, remote = state.remote,
                tray = { Tray(trayUi, { FakeThumbnails.photo }, {}, {}, words.trayNote(tray)) }, attachSheet = { AttachSheet(flipperScreen = true) {} },
                linkSheet = { link?.let { LinkSheet(it, {}, {}, {}) } },
                deleteSheet = state.current?.let { c -> {
                    DeleteConversationSheet(convWords.deleteSub(c), convWords.deleteText(c, named = false, busy = state.busyHere), {}, {})
                } }),
                F.frame.data, 0, landscape, large, {}, {})
        }
    }

    @Test fun conversationChat() = conversation("conversation-01-chat", C.writing(), C.streaming)

    /** The character at the chat's tail: a tool scene, the end of a turn, a failed turn. */
    @Test fun conversationWorking() = conversation("conversation-21-working", C.pressing())
    @Test fun conversationFinished() {
        justEnded = true
        conversation("conversation-22-finished", C.ended("ok"))
    }
    @Test fun conversationFailed() = conversation("conversation-23-failed", C.ended("error", "agent_failed"))
    @Test fun conversationLarge() = conversation("conversation-02-large", C.writing(), C.streaming, large = true)
    @Test fun conversationSettings() = conversation("conversation-03-settings", C.writing(), C.streaming, ChatSheet.SETTINGS)
    @Test fun conversationModel() = conversation("conversation-03b-model", C.writing(models = C.defaultModels), C.streaming, ChatSheet.MODEL)
    @Test fun conversationModelUnavailable() = conversation("conversation-20-model-unavailable", C.modelUnavailable())
    @Test fun conversationEffort() = conversation("conversation-03c-effort", C.writing(C.conversation.copy(effort = Effort.MAX)), C.streaming,
        ChatSheet.SETTINGS, dragging = true)
    @Test fun conversationMode() = conversation("conversation-03d-mode", C.writing(), C.streaming, ChatSheet.MODE)
    @Test fun conversationPermission() = conversation("conversation-04-permission", C.asking())
    @Test fun conversationPermissionLong() = conversation("conversation-04b-permission-long", C.askingLong())
    @Test fun conversationPermissionEdit() = conversation("conversation-04c-permission-edit", C.askingEdit())
    @Test fun conversationPermissionTooLong() = conversation("conversation-04d-permission-too-long", C.askingTooLong())
    @Test fun conversationAutoLock() = conversation("conversation-05-auto-lock", C.blocked())
    @Test fun conversationAttach() = conversation("conversation-06-attach", C.writing(), C.streaming, ChatSheet.ATTACH)
    @Test fun conversationAttachments() = conversation("conversation-07-attachments", C.withAttachments(),
        draft = t("And this is the manual for the TV remote", "Y este es el manual del mando"),
        tray = listOf(
            Upload(1, "IMG_2041.jpg", AttachmentKind.IMAGE, 1_468_000, Prepared("IMG_2041.jpg", "image/jpeg", ByteArray(0), 2000, 1500), progress = 0.6f),
            Upload(2, "notes.pdf", AttachmentKind.FILE, 184_320, null, progress = null,
                result = Attachment("3d4e5f60718293a4b5c6d7e8f9011223", "notes.pdf", AttachmentKind.FILE, "application/pdf", 184_320, null, null)),
        ))
    @Test fun conversationTyping() = conversation("conversation-08-typing", C.blocked(), keyboard = true,
        draft = t("Then use the Flipper's own app", "Entonces usa la app del propio Flipper"))
    @Test fun conversationTypingLong() = conversation("conversation-08b-typing-long", C.blocked(), keyboard = true,
        draft = t("Measure how fast the agent reacts to the Flipper:\nopen a tool whose screen changes quickly,\nwatch it for a few seconds,\n" +
            "tell me what it does,\nand how long each change took to reach you.\nThen try it again with the screen large.",
            "Mide lo rápido que reacciona el agente al Flipper:\nabre una herramienta cuya pantalla cambie rápido,\nobsérvala unos segundos,\n" +
            "dime qué hace,\ny cuánto tardó en llegarte cada cambio.\nLuego repítelo con la pantalla grande."))
    @Test fun conversationNew() = conversation("conversation-09-new", ChatState(supported = true, attachments = true, connected = true, status = C.status()))
    @Test @Config(qualifiers = "w844dp-h390dp-land-mdpi")
    fun conversationLandscape() = conversation("conversation-10-landscape", C.writing(), C.streaming, landscape = true)
    @Test @Config(qualifiers = "w844dp-h390dp-land-mdpi")
    fun conversationSettingsLandscape() = conversation("conversation-10b-settings-landscape", C.writing(), C.streaming, ChatSheet.SETTINGS,
        landscape = true)
    @Test fun conversationDelete() = conversation("conversation-13-delete", C.writing(), C.streaming, ChatSheet.DELETE)
    @Test fun conversationMarkdown() = conversation("conversation-12-markdown", C.markdown())
    @Test fun conversationLink() = conversation("conversation-12b-link", C.markdown(), sheet = ChatSheet.LINK,
        link = "https://example.com/ir-file-format")
    // Remote Control (mockups/remote.py)
    @Test fun conversationRemoteRow() = conversation("conversation-14-remote-row", C.remoteEnded().let {
        it.copy(open = it.open!!.copy(events = it.open!!.events.dropLast(1))) }, sheet = ChatSheet.SETTINGS)
    @Test fun conversationRemoteOn() = conversation("conversation-15-remote-on", C.remoteOn())
    @Test fun conversationRemoteLarge() = conversation("conversation-15b-remote-large", C.remoteOn(), large = true)
    @Test fun conversationRemotePermission() = conversation("conversation-16-remote-permission", C.remotePermission())
    @Test fun conversationRemoteOff() = conversation("conversation-17-remote-off", C.remoteOn(), sheet = ChatSheet.REMOTE_OFF)
    @Test fun conversationRemoteSettings() = conversation("conversation-17b-remote-settings", C.remoteIdle(), sheet = ChatSheet.SETTINGS)
    @Test fun conversationRemoteEnded() = conversation("conversation-18-remote-ended", C.remoteEnded())
    @Test fun conversationsRemote() = conversations("conversations-06-remote", state = C.remoteListing())

    /** A long paste over the limit, as a text file; the approved mockups conversation-19 to -19d. */
    private val pasted = Upload(1, "pasted-text.txt", AttachmentKind.FILE, 17_230, null, progress = null,
        result = C.pastedLog, chars = 17_230)
    @Test fun conversationLongPaste() = conversation("conversation-19-long-paste", C.relayLog(), tray = listOf(pasted))
    @Test fun conversationLongPasteText() = conversation("conversation-19b-long-paste-text", C.relayLog(),
        draft = "Here it is. Why does it drop at 09:14?", tray = listOf(pasted.copy(progress = 0.55f, result = null)))
    @Test fun conversationLongPasteSent() = conversation("conversation-19c-long-paste-sent", C.relayLogSent(), C.relayAnswer)
    @Test fun conversationLongPasteRemote() = conversation("conversation-19d-long-paste-remote", C.relayLogRemote(), draft = C.relayLogText)
    /** Remote Control on, with a server that takes attachments there: the paste is a file, as with it off. */
    @Test fun conversationLongPasteRemoteFile() = conversation("conversation-19f-long-paste-remote-file", C.relayLogRemoteFiles(),
        tray = listOf(pasted))
    /** Typed past the limit, with attachments allowed: the same error without the Remote Control sentence. */
    @Test fun conversationTooLong() = conversation("conversation-19e-too-long", C.relayLog(), draft = C.relayLogText.take(16_012))
    @Test fun homeRemote() = home("home-09-remote", F.relayRunning, F.link, chat = C.remoteListing().let {
        it.copy(open = dev.zeffin.link.OpenConversation(C.ID, loading = false), status = it.status!!.copy(turn = null)) })

    @Test fun conversationBusy() = conversation("conversation-11-busy",
        C.blocked().let { it.copy(status = it.status!!.copy(turn = C.writing().status!!.turn!!.copy(conversation = "other"))) })

    @Test fun settings() = shot("screens-05-settings") {
        SettingsScreen(present.settingsUi(F.relayRunning, F.link, F.settings, F.pairing, "0.1.0").copy(agent = words.agentLine(signedOut),
            agents = agentWords.summary(signedOut), agentPrefs = choice.prefsLine(signedOut, words)))
    }

    @Test fun settingsLanguage() = shot("settings-language") {
        Box(Modifier.fillMaxSize()) {
            SettingsScreen(present.settingsUi(F.relayRunning, F.link, F.settings, F.pairing, "0.1.0"))
            FbSheet("Language · Idioma", t("The app's language", "El idioma de la app"), onDismiss = {}) {
                for (option in listOf(t("System", "Sistema"), "English", "Español")) {
                    Box(Modifier.fillMaxWidth().padding(12.dp)) { Txt(option, FbType.heading) }
                }
            }
        }
    }

    private fun relay(name: String, relay: dev.zeffin.relay.RelayState, warnings: Warnings = Warnings(),
                      pairing: dev.zeffin.core.PairingRecord = F.pairing) = shot(name) {
        RelayScreen(present.relayUi(relay, F.link, F.settings, pairing, warnings, log.last(), F.now), {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun relayRunning() = relay("screens-06-relay", F.relayRunning)
    @Test fun relayConnecting() = relay("screens-07-relay-connecting", F.relayConnecting.copy(waitingSince = F.now - 16_000))
    @Test fun relayError() = relay("screens-08-relay-error", F.relayError)
    @Test fun relayStopped() = relay("screens-09-relay-stopped", F.relayStopped)
    @Test fun relayLocked() = relay("relay-locked", F.relayLocked)
    @Test fun relayBondLost() = relay("relay-bond-lost", F.relayBondLost)
    @Test fun relayWarnings() = relay("relay-warnings", F.relayRunning, Warnings(batteryOptimized = true, notifications = false))

    @Test fun relayServer() = shot("screens-10-relay-server") {
        RelayServerScreen(RelayServerUi("wss://100.98.12.7:9434/phone", null, "",
            t("Saved: •••• 2b7e. Leave it empty to keep it.", "Guardado: •••• 2b7e. Déjalo vacío para conservarlo."),
            null, false, BannerUi(Tone.SUCCESS, t("Server OK", "Servidor correcto"), null,
                t("my-server · ready in 0.3 s", "my-server · listo en 0,3 s"))), {}, {}, {}, {}, {})
    }

    @Test fun device() = shot("screens-11-relay-devices") {
        DeviceScreen(DeviceListUi(listOf(
            DeviceUi("Demo", "80:E1:26:00:00:01", true, true), DeviceUi("Flipper Abcdef", "80:E1:26:00:00:02", true, false),
            DeviceUi("Headphones", "02:00:00:00:00:03", false, false), DeviceUi("Keyboard", "02:00:00:00:00:04", false, false)), null),
            {}, {}, {}, {}, {})
    }

    private fun server(name: String, link: dev.zeffin.link.AppLinkState = F.link, pairing: dev.zeffin.core.PairingRecord = F.pairing,
                       choosing: Boolean = false) = shot(name) {
        ServerScreen(present.serverUi(link, F.settings, pairing).let { if (choosing) it.copy(orientation = ScreenOrientation.AUTO) else it },
            choosing, ServerActions())
    }

    @Test fun server() = server("pinkey-13-server")
    @Test fun serverOrientation() = server("server-orientation", choosing = true)

    // ---------------------------------------------------------------- pairing and the server's key

    private val camera: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Txt(t("Camera view", "Vista de la cámara"), FbType.body, color = FbColors.inkMuted)
        }
    }

    @Composable
    private fun pair(banner: BannerUi? = null, step: PairStepUi? = null, paste: PasteUi? = null) =
        PairScreen(paste, banner, step, PairActions(), camera)

    private fun pairShot(name: String, failure: Throwable) = shot(name) { pair(banner = present.pairFailure(failure)) }

    @Test fun pairCamera() = shot("pinkey-01-pair-camera") { pair() }
    @Test fun pairChecking() = shot("pinkey-02-pair-checking") { pair(step = PairStepUi.Checking("100.98.12.7")) }
    @Test fun pairWaiting() = shot("pinkey-03-pair-waiting") {
        pair(step = PairStepUi.Waiting(present.confirmNumber("482913"), "100.98.12.7:9434", F.key.fingerprint, "100.98.12.40", present.countdown(112)))
    }
    @Test fun pairRefused() = pairShot("pinkey-04-pair-refused", PairExchange.Failure.Refused)
    @Test fun pairExpired() = pairShot("pinkey-05-pair-expired", PairExchange.Failure.Expired)
    @Test fun pairCodeUsed() = pairShot("pinkey-06-pair-code-used", PairExchange.Failure.CodeUsed)
    @Test fun pairBadCode() = pairShot("pinkey-07-pair-code-not-current", PairExchange.Failure.BadCode)
    @Test fun pairCodeExpired() = pairShot("pinkey-07b-pair-code-expired", PairExchange.Failure.CodeExpired)
    @Test fun pairKeyMismatch() = pairShot("pinkey-08-pair-key-mismatch", PairExchange.Failure.KeyMismatch)
    @Test fun pairOldQr() = shot("pinkey-09-pair-old-qr") { pair(banner = present.invalidQr(Addresses.Invalid(Addresses.Problem.OLD_QR))) }
    @Test fun pairTlsFailed() = pairShot("pinkey-10-pair-tls-failed", PairExchange.Failure.Net(NetError.Tls))
    @Test fun pairNotFinished() = pairShot("pinkey-11-pair-not-finished", PairExchange.Failure.NotFinished)

    @Test fun pairReplace() = shot("pinkey-12-pair-replace") {
        Box(Modifier.fillMaxSize()) {
            pair()
            ReplacePairingSheet(ReplaceUi("100.98.12.7:9434", "100.98.12.9:9434", "81C0 5D2E 99A4 0B7F E312", "100.98.12.9"), {}, {})
        }
    }

    @Test fun pasteEmpty() = shot("pinkey-12b-paste-empty") { pair(paste = PasteUi()) }
    @Test fun pasteFilled() = shot("pinkey-12c-paste-filled") { pair(paste = PasteUi("100.98.12.7:9434", F.key.fingerprint)) }
    @Test fun pasteError() = shot("pinkey-12d-paste-error") { pair(paste = PasteUi(error = present.pasteError(Addresses.Invalid(Addresses.Problem.OLD_QR)))) }

    @Test fun serverKeyChanged() = server("pinkey-14-server-key-changed", F.linkKeyChanged)
    @Test fun serverPairAgain() = server("pinkey-15-server-pair-again", F.linkPairAgain, F.pairingWithoutKey)

    @Test fun settingsKeyChanged() = shot("pinkey-16-settings-key-changed") {
        SettingsScreen(present.settingsUi(F.relayKeyChanged, F.linkKeyChanged, F.settings, F.pairing, "0.8.0"))
    }
    @Test fun settingsPairAgain() = shot("pinkey-17-settings-pair-again") {
        SettingsScreen(present.settingsUi(F.relayPairAgain, F.linkPairAgain, F.settings, F.pairingWithoutKey, "0.8.0"))
    }

    @Test fun homeKeyChanged() = home("pinkey-18-home-key-changed", F.relayKeyChanged, F.linkKeyChanged, chat = null)
    @Test fun homePairAgain() {
        homePairing = F.pairingWithoutKey
        home("pinkey-19-home-pair-again", F.relayPairAgain, F.linkPairAgain, chat = null)
    }

    @Test fun conversationKeyChanged() {
        appLink = F.linkKeyChanged
        conversation("pinkey-20-conversation-key-changed", C.blocked().copy(connected = false))
    }

    @Test fun relayKeyChanged() = relay("pinkey-21-relay-key-changed", F.relayKeyChanged)
    @Test fun relayPairAgain() = relay("pinkey-22-relay-pair-again", F.relayPairAgain, pairing = F.pairingWithoutKey)

    @Test fun relayServerOther() = shot("pinkey-23-relay-server-other") {
        RelayServerScreen(RelayServerUi("wss://100.98.12.9:9434/phone",
            t("Another server: pair with it with its QR code", "Otro servidor: empareja con él con su QR"), "",
            t("Saved: •••• 2b7e. Leave it empty to keep it.", "Guardado: •••• 2b7e. Déjalo vacío para conservarlo."), null, false, null),
            {}, {}, {}, {}, {})
    }

    @Test fun diagnostics() = shot("screens-13-diagnostics") {
        DiagnosticsScreen(present.diagnosticsUi(F.relayRunning, F.link, log, F.now, F.now - 2_000), {}, {}, {})
    }

    @Test fun relayLog() = shot("screens-14-log") { LogScreen(present.logUi(0, log, F.link), {}, {}, {}, {}) }
    @Test fun serverLog() = shot("log-server") { LogScreen(present.logUi(1, log, F.link), {}, {}, {}, {}) }

    @Test fun about() = shot("about") {
        AboutScreen("0.1.0", "14", java.io.File("../licenses/THIRD_PARTY_NOTICES.md").readText()) {}
    }
}

class ScreenshotEnTest : ScreenshotTest(Lang.EN)

class ScreenshotEsTest : ScreenshotTest(Lang.ES)

/** The chat's images in the screenshots: the mockup's drawn TV remote (no real photo goes into the repository) and the
 *  Flipper screen of the fixtures. */
private object FakeThumbnails : ThumbnailSource {
    val photo: ImageBitmap by lazy {
        val scale = 2f
        val bitmap = android.graphics.Bitmap.createBitmap(264, 198, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap).apply { scale(scale, scale) }
        fun paint(color: Int) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawColor(0xFF585048.toInt())
        canvas.drawRect(0f, 62f, 132f, 99f, paint(0xFF786A5C.toInt()))
        canvas.drawRoundRect(44f, 10f, 88f, 94f, 10f, 10f, paint(0xFF202020.toInt()))
        canvas.drawCircle(66f, 24f, 5f, paint(0xFFDD3333.toInt()))
        for (y in listOf(40f, 52f, 64f, 76f)) for (x in listOf(51f, 62f, 73f))
            canvas.drawRoundRect(x, y, x + 8f, y + 6f, 2f, 2f, paint(0xFF777777.toInt()))
        bitmap.asImageBitmap()
    }
    private val screen: ImageBitmap? by lazy { Thumbnails.decode(ImageScaler.flipperScreen(F.frameData, 0)!!.bytes, 512) }

    override suspend fun load(conversation: String, id: String, maxPx: Int): ImageBitmap? =
        if (id == C.screen.id) screen else if (id == C.photo.id) photo else null
}
