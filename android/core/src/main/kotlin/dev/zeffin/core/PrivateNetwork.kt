// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import kotlinx.coroutines.flow.Flow
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

/**
 * The private network between the phone and the server: the only way to the server (docs/architecture.md, "The
 * private network's boundary"). [PrivateAddresses] is its pure side (which addresses are its own, its name for the
 * words); [PrivateNetwork] adds how the app reaches the server through it. It routes; the pairing's
 * [ServerAuth.PinnedKey] proves the server. ServerHttp is the only caller of [PrivateNetwork.route].
 */
interface PrivateAddresses {
    /** The network's name, as the owner knows it: the words name it, never a resource string. */
    val name: String
    /** Its address ranges. Only a VPN route inside them counts as the tunnel. */
    val ranges: List<IpPrefix>

    /** A literal address inside [ranges]; a host name never is (no DNS here). */
    fun isPrivate(host: String): Boolean = literalAddress(host)?.let { a -> ranges.any { it.contains(a) } } == true
}

interface PrivateNetwork : PrivateAddresses {
    /** How a socket reaches [host] now: through the tunnel, or not at all. Asked again at every connect. */
    fun route(host: String): Route
    /** Emits when the tunnel comes, goes or changes its routes: the links try again, pooled sockets are dropped. */
    val changes: Flow<Unit>
    /** This phone's own address in the private network, when it is up: the server shows it next to a pairing request. */
    fun ownAddress(): String? = null
}

sealed interface Route {
    /** [bind] ties a socket, before it connects, to the network that carries the tunnel. */
    class Through(val bind: (Socket) -> Unit) : Route
    data class Down(val why: NoTunnel.Why) : Route
}

/** Thrown before any socket opens: nothing was written, no byte, header, token or code. */
class NoTunnel(val why: Why, cause: Throwable? = null) : IOException("no tunnel to the server: $why", cause) {
    enum class Why {
        /** No VPN network is up on the phone. */
        NO_VPN,
        /** A VPN is up, but none of its routes inside the private ranges covers the server. */
        NOT_COVERED,
        /** The address is not in the private ranges (parseBase refuses it before; kept for a caller that did not ask). */
        NOT_PRIVATE,
    }
}

/** An address prefix: [address] and its first [length] bits. */
data class IpPrefix(val address: InetAddress, val length: Int) {
    private val bits get() = address.address.size * 8

    init {
        require(length in 0..bits) { "bad prefix length" }
    }

    fun contains(a: InetAddress): Boolean = a.address.size == address.address.size && samePrefix(a.address, address.address, length)

    /** This prefix lies wholly inside [outer]: as long or longer, and starting inside it. */
    fun within(outer: IpPrefix): Boolean = length >= outer.length && outer.contains(address)

    companion object {
        fun parse(text: String): IpPrefix {
            val (a, n) = text.split('/')
            return IpPrefix(requireNotNull(literalAddress(a)) { "not a literal address" }, n.toInt())
        }

        private fun samePrefix(x: ByteArray, y: ByteArray, length: Int): Boolean {
            val whole = length / 8
            val rest = length % 8
            val mask = (0xFF shl (8 - rest)) and 0xFF
            return (0 until whole).all { x[it] == y[it] } &&
                (rest == 0 || (x[whole].toInt() and mask) == (y[whole].toInt() and mask))
        }
    }
}

/**
 * The route to [destination] among the VPN networks' routes, or why there is none. Only a route whose prefix lies inside
 * the private [ranges] counts: a default route or a wider prefix (a full-tunnel VPN) never does. The longest prefix wins.
 */
fun <N> chooseRoute(destination: InetAddress, ranges: List<IpPrefix>, vpns: Map<N, List<IpPrefix>>): Pair<N?, NoTunnel.Why?> {
    val best = vpns.flatMap { (n, routes) -> routes.map { n to it } }
        .filter { (_, r) -> r.contains(destination) && ranges.any { r.within(it) } }
        .maxByOrNull { (_, r) -> r.length }
    return when {
        ranges.none { it.contains(destination) } -> null to NoTunnel.Why.NOT_PRIVATE
        vpns.isEmpty() -> null to NoTunnel.Why.NO_VPN
        best == null -> null to NoTunnel.Why.NOT_COVERED
        else -> best.first to null
    }
}

/** [host] as an address if it is a literal IPv4 or IPv6 one (brackets allowed), without ever resolving a name. */
fun literalAddress(host: String): InetAddress? {
    val h = host.removePrefix("[").removeSuffix("]")
    val v4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(h)?.groupValues?.drop(1)?.map { it.toInt() }
    return when {
        v4 != null -> v4.takeIf { p -> p.all { it <= 255 } }?.let { p -> InetAddress.getByAddress(p.map { it.toByte() }.toByteArray()) }
        // In brackets, InetAddress takes only an IPv6 literal and never asks DNS; a zone (%) is not a server address.
        !h.contains(':') || h.contains('%') -> null
        else -> runCatching { InetAddress.getByName("[$h]") }.getOrNull()
    }
}
