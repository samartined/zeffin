// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Tailscale as the private network: its two ranges, and the VPN network it brings up on the phone. The only code that
 * names it. The socket goes through the VPN network whose routes, inside those ranges, cover the server; with Tailscale
 * off or reconnecting there is none, even with Android's always-on VPN without lockdown, and nothing is sent.
 */
class TailscaleNetwork(context: Context) : PrivateNetwork, PrivateAddresses by TailscaleNetwork.Companion {
    companion object : PrivateAddresses {
        override val name = "Tailscale"
        override val ranges = listOf(IpPrefix.parse("100.64.0.0/10"), IpPrefix.parse("fd7a:115c:a1e0::/48"))
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    /** Every VPN network Android reports, with its unicast routes. */
    private val vpns = ConcurrentHashMap<Network, List<IpPrefix>>()
    /** Each VPN network's own addresses inside the ranges. */
    private val own = ConcurrentHashMap<Network, List<String>>()
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val changes: Flow<Unit> = _changes

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            vpns[network] = lp.routes.filter { it.type == RouteInfo.RTN_UNICAST }
                .map { IpPrefix(it.destination.address, it.destination.prefixLength) }
            own[network] = lp.linkAddresses.mapNotNull { it.address.hostAddress }.filter(::isPrivate)
            _changes.tryEmit(Unit)
        }

        override fun onLost(network: Network) {
            vpns.remove(network)
            own.remove(network)
            _changes.tryEmit(Unit)
        }
    }

    init {
        // Without NOT_VPN removed (clearCapabilities does it), Android never reports a VPN network.
        val request = NetworkRequest.Builder().clearCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_VPN).build()
        cm.registerNetworkCallback(request, callback)
    }

    override fun ownAddress(): String? = own.values.flatten().minByOrNull { it.contains(':') }  // IPv4 first, as the server shows it

    override fun route(host: String): Route {
        val address = literalAddress(host) ?: return Route.Down(NoTunnel.Why.NOT_PRIVATE)
        val (network, why) = chooseRoute(address, ranges, vpns.toMap())
        return network?.let(::through) ?: Route.Down(why ?: NoTunnel.Why.NOT_COVERED)
    }

    private fun through(network: Network) = Route.Through { socket ->
        try {
            network.bindSocket(socket)
        } catch (e: IOException) {
            throw NoTunnel(NoTunnel.Why.NO_VPN, e)  // the network went away meanwhile
        }
    }
}
