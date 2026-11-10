// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay

import dev.zeffin.relay.rpc.RpcEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.coroutines.cancellation.CancellationException

/** Why the remote's own use of the link ended. */
enum class RemoteEnd {
    /** The remote closed: the relay stopped its own stream; the link goes back to the cycle. */
    CLOSED,
    /** The Flipper locked: its RPC session ended; the link waits for a new one. */
    LOCKED,
    /** The link is gone. */
    LOST,
}

/**
 * The remote without a server session (docs/architecture.md, "The relay's own use of the link"): the relay holds the
 * link alone while the remote is open, and the engine does not dial `/phone` meanwhile.
 *
 * - **Sync:** the link may be mid-stream (Android keeps a link, and its RPC session and stream live on). The relay writes
 *   its own `gui_stop_screen_stream_request` with an id from its range and drops bytes until that stop's whole reply, as the
 *   server does with its settle stop ([RemoteSession.settleOn], `SettleMatch`); then it starts the stream with another id.
 * - **Keys:** the same rules as in a server session ([RemoteSession]): one in flight, no late burst, the read-back check.
 *   There is no server, so no server frame waits and no `written` is ever sent.
 * - **One writer:** this session's writer is the only one on the link; the reader starts over with each session, so with
 *   each link and each RPC session (a lock ends it). A parse error stops the reader for the link: [readerStopped].
 * - **Closing** writes a stop with the relay's id for the stream it started; a server session that opens later settles the
 *   stream as it always does.
 */
internal class RemoteLinkSession(
    private val link: FlipperLink,
    private val remote: RelayRemote,
    private val log: RelayLog?,
    private val strings: () -> RelayStrings,
    private val clock: Clock,
    private val onTraffic: (fromFlipper: Long, toFlipper: Long) -> Unit = { _, _ -> },
) {
    /** The reader could not read this link's stream: the engine does not keep the link. */
    @Volatile var readerStopped = false
        private set

    /** Runs until [closed] returns, the Flipper locks or the link is gone. */
    suspend fun run(closed: suspend () -> Unit): RemoteEnd = coroutineScope {
        val end = CompletableDeferred<RemoteEnd>()
        val side = RemoteSession(remote, log, strings, clock)
        val closing = async { closed() }
        launch { readFlipper(side) }
        launch { writeFlipper(side, end, closing, this) }
        launch {
            link.ended.await()
            end.complete(RemoteEnd.LOST)
        }
        launch {
            link.rpcOpen.first { !it }
            end.complete(RemoteEnd.LOCKED)
        }
        try {
            end.await()
        } finally {
            readerStopped = side.readerStopped
            side.end()
            coroutineContext.cancelChildren()
        }
    }

    private suspend fun readFlipper(side: RemoteSession) {
        for (bytes in link.received) {
            onTraffic(bytes.size.toLong(), 0)
            side.flipperChunk(bytes)
        }
    }

    private suspend fun writeFlipper(
        side: RemoteSession,
        end: CompletableDeferred<RemoteEnd>,
        closing: kotlinx.coroutines.Deferred<Unit>,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        try {
            val stop = remote.nextId()
            side.settleOn(stop)  // before the write, so its reply is never missed
            write(RpcEncoder.stopScreenStream(stop))
            write(RpcEncoder.startScreenStream(remote.nextId()))
            while (true) {
                val keys = side.takeIf { it.keysFree }
                val out: Any = select {
                    closing.onAwait { Closing }
                    if (keys != null) remote.queue.onReceive { press -> keys.takeKey(press) ?: Again }
                    side.wake.onReceive { Again }
                }
                when (out) {
                    Closing -> {
                        write(RpcEncoder.stopScreenStream(remote.nextId()))
                        end.complete(RemoteEnd.CLOSED)
                        return
                    }
                    is RemoteSession.KeyFrame -> {
                        write(out.bytes)
                        scope.launch { delay(RemoteSession.KEY_REPLY_MS); side.keyTimeout(out) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: FlipperException) {
            end.complete(if (!link.rpcOpen.value) RemoteEnd.LOCKED else RemoteEnd.LOST)
        }
    }

    private suspend fun write(bytes: ByteArray) {
        link.write(bytes)
        onTraffic(0, bytes.size.toLong())
    }

    private object Closing
    private object Again
}
