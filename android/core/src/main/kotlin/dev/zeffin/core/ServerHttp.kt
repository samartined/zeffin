// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import okhttp3.Cache
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Every request to the server, from the relay and from the app: the only holder of an OkHttpClient (a test holds the
 * rule), and the only reader of the pinned key ([ServerAuth.PinnedKey]).
 *
 * - **Only TLS 1.3, only with the pinned key:** the server must show the key whose hash the pairing holds, checked during
 *   the handshake, before the request is written; anything else fails with [ServerKeyChanged]. No system CA, no
 *   "continue anyway", no cleartext: an http URL, or a server with no pinned key, fails with [NoServerKey] before any
 *   socket opens.
 * - **Only through the private network's tunnel:** each socket is bound to the network that carries the tunnel when it
 *   connects, by its destination, or refused with [NoTunnel], before a byte is written.
 * - **Never through a proxy:** a Wi-Fi's or a VPN's would see the connections.
 */
class ServerHttp(
    private val network: PrivateNetwork,
    /** The pairing's key for each server; [newPairCall] brings the QR code's before there is a pairing. */
    private val keys: ServerKeys,
    cacheDir: File? = null,
    /** The relay socket's ping; tests make it short. */
    private val relayPingMs: Long = 20_000L,
) {
    /** What a request is for: its timeouts, and the cache for the attachments. */
    enum class Use { RELAY_SOCKET, RELAY_CHECK, APP_SOCKET, TRANSFER, PAIR }

    private val pool = ConnectionPool()
    private val cache = cacheDir?.let { Cache(it, CACHE_BYTES) }
    private val base = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectionPool(pool)
        .connectTimeout(10, TimeUnit.SECONDS)
        .connectionSpecs(listOf(TLS_1_3))
        .socketFactory(TunnelSockets(network))
        .addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps) throw NoServerKey()
            val down = network.route(url.host) as? Route.Down
            if (down != null) throw NoTunnel(down.why)
            try {
                chain.proceed(chain.request())
            } catch (e: IOException) {
                throw if (e.isKeyMismatch()) ServerKeyChanged(e) else e
            }
        })
        .build()
    /** For a request with no pinned key: it fails before anything else happens. */
    private val refusing = base.newBuilder().apply { interceptors().add(0, Interceptor { throw NoServerKey() }) }.build()
    private val pinnings = ConcurrentHashMap<ServerAuth.PinnedKey, Pinning>()
    private val clients = ConcurrentHashMap<Pair<Use, ServerAuth.PinnedKey?>, OkHttpClient>()

    fun newCall(use: Use, request: Request): Call = client(use, pinned(request.url)).newCall(request)

    fun newWebSocket(use: Use, request: Request, listener: WebSocketListener): WebSocket =
        client(use, pinned(request.url)).newWebSocket(request, listener)

    /** The pairing's first request, before there is a pairing: checked against the QR code's [key]. */
    fun newPairCall(request: Request, key: ServerAuth): Call = client(Use.PAIR, key.pinned()).newCall(request)

    /** The tunnel or the pairing changed: no pooled socket is reused (a bound socket never moves to another network). */
    fun dropConnections() = pool.evictAll()

    private fun pinned(url: HttpUrl) = if (url.isHttps) keys.keyFor(url)?.pinned() else null

    private fun ServerAuth.pinned() = when (this) {
        is ServerAuth.PinnedKey -> this
    }

    private fun client(use: Use, key: ServerAuth.PinnedKey?): OkHttpClient = clients.getOrPut(use to key) {
        val pinning = key?.let { pinnings.getOrPut(it) { Pinning(it) } }
        (if (pinning == null) refusing else base).newBuilder().apply {
            if (pinning != null) sslSocketFactory(pinning.context.socketFactory, pinning.trust).hostnameVerifier(pinning.verifier)
            when (use) {
                // The ping every 20 s ends a session whose server went silent.
                Use.RELAY_SOCKET -> readTimeout(0, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).pingInterval(relayPingMs, TimeUnit.MILLISECONDS)
                Use.RELAY_CHECK -> readTimeout(10, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
                Use.APP_SOCKET -> readTimeout(0, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
                Use.TRANSFER -> readTimeout(0, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).cache(cache)
                // The server holds a wait for the confirmation 20 s at most.
                Use.PAIR -> readTimeout(30, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            }
        }.build()
    }

    companion object {
        private const val CACHE_BYTES = 20L * 1024 * 1024
        private val TLS_1_3 = ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()
    }
}

/**
 * TLS that trusts one key only. The leaf certificate's key must be the pinned one; nothing else of the certificate is
 * read (issuer, dates, names, constraints, uses): the key is the identity, and the handshake proves the server holds its
 * private half. Its own SSLContext, so no session made under another pin is ever resumed.
 */
private class Pinning(private val key: ServerAuth.PinnedKey) {
    val trust = object : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = refuseClient()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = refuseClient()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = refuseClient()
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val context: SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    /** The same check again once the session is up: OkHttp asks it of every connection, resumed ones too. */
    val verifier = HostnameVerifier { _, session ->
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
        leaf != null && key.matches(leaf.publicKey.encoded)
    }

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw KeyMismatch()
        if (!key.matches(leaf.publicKey.encoded)) throw KeyMismatch()
    }

    private fun refuseClient(): Unit = throw CertificateException("the app accepts no clients")
}

private class KeyMismatch : CertificateException("the server's key is not the pinned one")

/** A handshake that failed on the pin, or a session whose key the verifier refused, anywhere in the causes. */
private fun Throwable.isKeyMismatch(): Boolean {
    val seen = mutableSetOf<Throwable>()
    fun walk(t: Throwable?): Boolean = t != null && seen.add(t) &&
        (t is KeyMismatch || t is SSLPeerUnverifiedException || walk(t.cause) || t.suppressed.any(::walk))
    return walk(this)
}

/** Sockets that, when they connect, bind to the network carrying the tunnel to their destination, or refuse. */
private class TunnelSockets(private val network: PrivateNetwork) : SocketFactory() {
    override fun createSocket(): Socket = TunnelSocket(network)
    override fun createSocket(host: String, port: Int): Socket = connected(literal(host), port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket = connected(literal(host), port)
    override fun createSocket(host: InetAddress, port: Int): Socket = connected(host, port)
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress?, localPort: Int): Socket = connected(host, port)

    private fun literal(host: String) = literalAddress(host) ?: throw NoTunnel(NoTunnel.Why.NOT_PRIVATE)  // never a DNS lookup
    private fun connected(host: InetAddress, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
}

private class TunnelSocket(private val network: PrivateNetwork) : Socket() {
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val address = (endpoint as? InetSocketAddress)?.address ?: throw NoTunnel(NoTunnel.Why.NOT_PRIVATE)
        when (val route = network.route(address.hostAddress.orEmpty())) {
            is Route.Through -> route.bind(this)
            is Route.Down -> throw NoTunnel(route.why)
        }
        super.connect(endpoint, timeout)
    }
}
