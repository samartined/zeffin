// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * A private network for the tests on the JVM: up (every socket goes "through" it, counted) or down for a reason. It lives
 * in test sources only: production builds TailscaleNetwork in CoreGraph, with no switch.
 */
class FakeNetwork(@Volatile var down: NoTunnel.Why? = null) : PrivateNetwork {
    override val name = "Test network"
    override val ranges = listOf(IpPrefix.parse("127.0.0.0/8"))
    val routes = AtomicInteger()
    val binds = AtomicInteger()
    /** Answers before each route(); a test changes the network between the interceptor and the connect with it. */
    @Volatile var before: (Int) -> Unit = {}
    override val changes = MutableSharedFlow<Unit>()

    override fun route(host: String): Route {
        before(routes.incrementAndGet())
        return down?.let { Route.Down(it) } ?: Route.Through { binds.incrementAndGet() }
    }
}
