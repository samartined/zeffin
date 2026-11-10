// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.zeffin.AppGraph
import dev.zeffin.notify.AndroidNotifications
import dev.zeffin.relay.RelayController
import dev.zeffin.ui.components.Tab
import dev.zeffin.ui.routes.AboutRoute
import dev.zeffin.ui.routes.AgentRoute
import dev.zeffin.ui.routes.AgentInfoRoute
import dev.zeffin.ui.routes.AgentsRoute
import dev.zeffin.ui.routes.ConversationRoute
import dev.zeffin.ui.routes.ConversationsRoute
import dev.zeffin.ui.routes.DeviceRoute
import dev.zeffin.ui.routes.DiagnosticsRoute
import dev.zeffin.ui.routes.FlipperRemoteRoute
import dev.zeffin.ui.routes.HomeRoute
import dev.zeffin.ui.routes.LogRoute
import dev.zeffin.ui.routes.PairRoute
import dev.zeffin.ui.routes.RelayRoute
import dev.zeffin.ui.routes.RelayServerRoute
import dev.zeffin.ui.routes.ServerRoute
import dev.zeffin.ui.routes.SettingsRoute

object Routes {
    const val HOME = "home"
    const val CONVERSATION = "conversation"
    const val CONVERSATIONS = "conversations"
    const val SETTINGS = "settings"
    const val RELAY = "relay"
    const val RELAY_SERVER = "relay/server"
    const val DEVICE = "relay/device"
    const val SERVER = "server"
    const val PAIR = "pair"
    const val DIAGNOSTICS = "diagnostics"
    const val LOG = "log"
    const val ABOUT = "about"
    const val AGENT = "agent"
    /** Settings, Connections, Agents, and one agent's page. */
    const val AGENTS = "agents"
    const val AGENT_INFO = "agents/{id}"
    fun agentInfo(id: String) = "agents/$id"
    /** The remote for the Flipper. */
    const val REMOTE = "remote"
}

/** What a route may ask of the navigation. */
class AppNavigator(private val nav: NavHostController) {
    fun go(route: String) = nav.navigate(route)

    fun back() {
        nav.popBackStack()
    }

    fun openConversation() = nav.navigate(Routes.CONVERSATION) { launchSingleTop = true }

    /** The bottom bar: each tab keeps its own back stack and state. */
    fun tab(t: Tab) {
        val route = when (t) {
            Tab.HOME -> Routes.HOME
            Tab.CONVERSATIONS -> Routes.CONVERSATIONS
            Tab.SETTINGS -> Routes.SETTINGS
        }
        nav.navigate(route) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/** The nav host and the route table; each route is a file in ui/routes. */
@Composable
fun AppNav(graph: AppGraph, openRoute: MutableState<String?>) {
    val controller = rememberNavController()
    val nav = remember(controller) { AppNavigator(controller) }
    val lang = LocalLang.current
    LaunchedEffect(lang) { graph.relay.refreshLang(); graph.notifications.ensureChannels() }

    LaunchedEffect(openRoute.value) {
        val open = openRoute.value
        if (open == RelayController.OPEN_RELAY) controller.navigate(Routes.RELAY) { launchSingleTop = true }
        // A notification about a conversation: open it.
        if (open != null && open.startsWith(AndroidNotifications.OPEN_CONVERSATION)) {
            graph.chat.open(open.removePrefix(AndroidNotifications.OPEN_CONVERSATION))
            nav.openConversation()
        }
        openRoute.value = null
    }

    // Screens change at once: Navigation's default is a 700 ms fade on every change, back included.
    NavHost(controller, startDestination = Routes.HOME,
        enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
        composable(Routes.HOME) { HomeRoute(graph, nav) }
        composable(Routes.CONVERSATION) { ConversationRoute(graph, nav) }
        composable(Routes.CONVERSATIONS) { ConversationsRoute(graph, nav) }
        composable(Routes.AGENT) { AgentRoute(graph, nav) }
        composable(Routes.AGENTS) { AgentsRoute(graph, nav) }
        composable(Routes.AGENT_INFO, arguments = listOf(navArgument("id") { defaultValue = "" })) { entry ->
            AgentInfoRoute(graph, nav, entry.arguments?.getString("id").orEmpty())
        }
        composable(Routes.SETTINGS) { SettingsRoute(graph, nav) }
        composable(Routes.RELAY) { RelayRoute(graph, nav) }
        composable(Routes.RELAY_SERVER) { RelayServerRoute(graph, nav) }
        composable(Routes.DEVICE) { DeviceRoute(graph, nav) }
        composable(Routes.SERVER) { ServerRoute(graph, nav) }
        composable("${Routes.PAIR}?paste={paste}", arguments = listOf(navArgument("paste") { defaultValue = "0" })) { entry ->
            PairRoute(graph, nav, startPaste = entry.arguments?.getString("paste") == "1")
        }
        composable(Routes.DIAGNOSTICS) { DiagnosticsRoute(graph, nav) }
        composable(Routes.LOG) { LogRoute(graph, nav) }
        composable(Routes.ABOUT) { AboutRoute(graph, nav) }
        composable(Routes.REMOTE) { FlipperRemoteRoute(graph, nav) }
    }
}
