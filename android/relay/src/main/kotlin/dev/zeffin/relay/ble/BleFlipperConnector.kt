// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.ble

import dev.zeffin.core.cleanText
import dev.zeffin.relay.CreditWriter
import dev.zeffin.relay.FlipperConnector
import dev.zeffin.relay.FlipperException
import dev.zeffin.relay.FlipperFailure
import dev.zeffin.relay.GattOp
import dev.zeffin.relay.RelayStrings
import dev.zeffin.relay.FlipperLink
import dev.zeffin.relay.FlipperProblem
import dev.zeffin.relay.FlipperStep
import dev.zeffin.relay.RelayLog
import dev.zeffin.relay.RadioState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects to the chosen Flipper over Bluetooth LE, in this order:
 * connect, let pairing and encryption finish, MTU, services, subscribe, read the credit.
 */
class BleFlipperConnector(
    private val radio: BleRadio,
    private val address: String,
    private val knownName: String,
    private val log: RelayLog,
    /** Set when the Flipper dropped the link during the MTU exchange: the next attempt skips it, the one after tries again. */
    private val skipMtu: AtomicBoolean,
    private val scope: CoroutineScope = defaultScope(),
    /** The words for the log, in the user's language at the time. */
    private val strings: () -> RelayStrings = { RelayStrings.En },
) : FlipperConnector {
    override suspend fun connect(passive: Boolean, step: (FlipperStep) -> Unit): FlipperLink {
        when (radio.state()) {
            RadioState.MISSING -> fail(FlipperProblem.BLUETOOTH_OFF, FlipperFailure.NoBluetooth)
            RadioState.OFF -> fail(FlipperProblem.BLUETOOTH_OFF, FlipperFailure.BluetoothOff)
            RadioState.NO_PERMISSION -> fail(FlipperProblem.NO_PERMISSION, FlipperFailure.NoPermission)
            RadioState.ON -> Unit
        }
        if (radio.bond(address) == Bond.NONE) {
            fail(FlipperProblem.NOT_BONDED, FlipperFailure.NotBonded)
        }
        // Our previous link takes a moment to drop, and the Flipper opens a fresh RPC session on the
        // next one. A link that stays is shared: usually Android keeps it after pairing, and it
        // cannot be told apart from another app's, so it is not a reason to wait.
        if (withTimeoutOrNull(3_000) { while (radio.linkConnected(address)) delay(200) } == null) {
            log.info(strings().logLinkShared)
        }
        step(if (passive) FlipperStep.WAITING else FlipperStep.CONNECTING)
        val port = try {
            radio.connect(address, autoConnect = passive, timeoutMs = if (passive) null else 20_000)
        } catch (e: GattError) {
            if (isAuthFailure(e.status)) bondLost(e.status)
            fail(FlipperProblem.UNREACHABLE, if (e.status == TIMEOUT) FlipperFailure.NotResponding else FlipperFailure.ConnectFailed(e.status))
        }
        try {
            return open(port, step)
        } catch (e: Throwable) {
            withContext(NonCancellable) { port.close() }
            throw e
        }
    }

    private suspend fun open(port: GattPort, step: (FlipperStep) -> Unit): FlipperLink {
        // Let the Flipper finish its security request (pairing, encryption) before anything else.
        step(FlipperStep.SECURING)
        delay(600)
        val bondDeadline = 60_000L
        withTimeoutOrNull(bondDeadline) {
            while (radio.bond(address) == Bond.BONDING) delay(200)
        }
        when (radio.bond(address)) {
            Bond.NONE -> bondLost(null)
            Bond.BONDING -> fail(FlipperProblem.DROPPED, FlipperFailure.BondIncomplete)
            Bond.BONDED -> Unit
        }
        val mtu = if (skipMtu.getAndSet(false)) {
            log.info(strings().logMtuSkipped)
            23
        } else {
            step(FlipperStep.MTU)
            negotiateMtu(port)
        }
        // Each RPC reply is one indication confirmed by the phone: a short connection interval makes them cheap
        // (field test 2026-10-24: ~0.35–0.5 s per reply at the interval the peers settled on by themselves).
        log.info(if (port.requestFastConnection()) strings().logFastConnection else strings().logFastConnectionRefused)
        step(FlipperStep.SERVICES)
        val serial = op(port, GattOp.SERVICES) { withTimeoutOrNull(15_000) { port.discoverSerial() } }
            ?: fail(FlipperProblem.DROPPED, FlipperFailure.ServicesTimeout)
        if (!serial) fail(FlipperProblem.NOT_A_FLIPPER, FlipperFailure.NotAFlipper)

        step(FlipperStep.SUBSCRIBING)
        val credits = CreditWriter()
        val received = Channel<ByteArray>(RX_BUFFER_CHUNKS)
        val overflow = CompletableDeferred<FlipperException>()
        // The consumer listens before the subscriptions, so nothing is lost.
        port.onChanged { uuid, value ->
            when (uuid) {
                FlipperGatt.RX -> if (!received.trySend(value).isSuccess) {
                    overflow.complete(FlipperException(FlipperProblem.DROPPED, FlipperFailure.Overflow))
                }
                FlipperGatt.FLOW -> parseCredit(value)?.let(credits::announce)
            }
        }
        op(port, GattOp.SUBSCRIBE_RX) { timed { port.enable(FlipperGatt.RX, indication = true) } }
        op(port, GattOp.SUBSCRIBE_FLOW) { timed { port.enable(FlipperGatt.FLOW, indication = false) } }
        log.ok(strings().logNotifications)
        val initial = op(port, GattOp.READ_CREDIT) { timed { port.read(FlipperGatt.FLOW) } }
        parseCredit(initial)?.let(credits::announce)
            ?: fail(FlipperProblem.NOT_A_FLIPPER, FlipperFailure.BadFlowValue)
        val name = cleanText(knownName) ?: address
        val link = BleFlipperLink(port, name, mtu, credits, received, overflow, scope)
        // A grant that comes after the request's wait, or an exchange the Flipper starts, changes the chunk size.
        port.onMtu { changed ->
            if (changed in 23..517 && changed != link.mtu) {
                link.mtu = changed
                log.info(strings().logMtuChanged(changed))
            }
        }
        return link
    }

    /** The MTU the Flipper granted, or 23 with the reason in the log: refused, an error status, or no answer. */
    private suspend fun negotiateMtu(port: GattPort): Int {
        // The port gives up after 10 s on its own; this only guards a port that never answers at all.
        val result = withTimeoutOrNull(MTU_WAIT_MS) {
            try {
                Result.success(port.requestMtu(FlipperGatt.MTU_REQUEST))
            } catch (e: GattError) {
                Result.failure(e)
            }
        }
        if (port.disconnected.isCompleted) {
            skipMtu.set(true)
            val status = port.disconnected.await()
            if (isAuthFailure(status)) bondLost(status)
            fail(FlipperProblem.DROPPED, FlipperFailure.MtuDropped)
        }
        val mtu = result?.getOrNull()
        when {
            mtu != null -> log.info(strings().logMtu(mtu))
            result == null || (result.exceptionOrNull() as? GattError)?.status == TIMEOUT -> log.warn(strings().logMtuNoAnswer)
            else -> log.warn(strings().logMtuFailed((result.exceptionOrNull() as GattError).status))
        }
        return mtu ?: 23
    }

    /** Runs a GATT step; an authentication failure means the bond is gone, a drop is retried. */
    private suspend fun <T> op(port: GattPort, what: GattOp, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: GattError) {
            if (isAuthFailure(e.status)) bondLost(e.status)
            if (port.disconnected.isCompleted) {
                val status = port.disconnected.await()
                if (isAuthFailure(status)) bondLost(status)
                fail(FlipperProblem.DROPPED, FlipperFailure.OpDropped(what, status))
            }
            fail(FlipperProblem.DROPPED, FlipperFailure.OpFailed(what, e.status))
        }
    }

    private suspend fun <T> timed(block: suspend () -> T): T =
        withTimeoutOrNull(10_000) { block() } ?: throw GattError(TIMEOUT, GattOp.OPERATION)

    private fun bondLost(status: Int?): Nothing = fail(FlipperProblem.BOND_LOST, FlipperFailure.BondLost(status))

    private fun fail(kind: FlipperProblem, failure: FlipperFailure): Nothing = throw FlipperException(kind, failure)

    companion object {
        /** About 400 KB of indications waiting for the server. */
        const val RX_BUFFER_CHUNKS = 1024
        /** Longer than the port's own 10 s, so a late grant is not thrown away. */
        const val MTU_WAIT_MS = 12_000L

        fun parseCredit(value: ByteArray): Int? =
            if (value.size == 4) ByteBuffer.wrap(value).int.takeIf { it in 0..65536 } else null

        @OptIn(DelicateCoroutinesApi::class)
        private fun defaultScope(): CoroutineScope = GlobalScope
    }
}

class BleFlipperLink(
    private val port: GattPort,
    override val name: String,
    @Volatile override var mtu: Int,
    private val credits: CreditWriter,
    override val received: Channel<ByteArray>,
    overflow: CompletableDeferred<FlipperException>,
    scope: CoroutineScope,
) : FlipperLink {
    override val rpcOpen: StateFlow<Boolean> get() = credits.rpcOpen
    override val rpcGeneration: Int get() = credits.generation
    override val ended = CompletableDeferred<FlipperException>()
    private val closing = AtomicBoolean(false)
    /** The link's own watchers: they end with the link, whatever scope it was given. */
    private val watchers = Job(scope.coroutineContext[Job])

    init {
        val own = scope + watchers
        // Once the link has ended nothing is left to watch: what the disconnect watcher still had to do is close the channel.
        ended.invokeOnCompletion {
            received.close()
            watchers.cancel()
        }
        own.launch(Dispatchers.Default) {
            val status = port.disconnected.await()
            ended.complete(
                if (closing.get()) FlipperException(FlipperProblem.CLOSED, FlipperFailure.ClosedByRelay)
                else FlipperException(if (isAuthFailure(status)) FlipperProblem.BOND_LOST else FlipperProblem.DROPPED,
                    FlipperFailure.Disconnected(status)))
            received.close()
        }
        own.launch(Dispatchers.Default) {
            val e = overflow.await()
            if (ended.complete(e)) port.close()
        }
    }

    override suspend fun write(data: ByteArray) {
        if (!alive) throw ended.getCompleted()
        credits.write(data, (mtu - 3).coerceAtLeast(20)) { chunk ->
            try {
                port.write(FlipperGatt.TX, chunk)
            } catch (e: GattError) {
                throw FlipperException(FlipperProblem.DROPPED, FlipperFailure.WriteFailed(e.status))
            }
        }
    }

    override suspend fun close() {
        closing.set(true)
        port.close()
        ended.complete(FlipperException(FlipperProblem.CLOSED, FlipperFailure.ClosedByRelay))
    }
}
