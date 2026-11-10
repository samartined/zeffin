// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.relay.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import dev.zeffin.core.cleanText
import dev.zeffin.relay.GattOp
import dev.zeffin.relay.BondedDevice
import dev.zeffin.relay.RadioState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Android refused to start a GATT operation (busy or disconnected). */
const val REFUSED = -3

@SuppressLint("MissingPermission") // every entry point checks BLUETOOTH_CONNECT through state()
class AndroidBleRadio(private val context: Context) : BleRadio {
    private val manager = context.getSystemService(BluetoothManager::class.java)

    override fun state(): RadioState {
        val adapter = manager?.adapter ?: return RadioState.MISSING
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return RadioState.NO_PERMISSION
        }
        return if (adapter.isEnabled) RadioState.ON else RadioState.OFF
    }

    private fun device(address: String): BluetoothDevice = manager.adapter.getRemoteDevice(address)

    override fun bond(address: String): Bond = when (device(address).bondState) {
        BluetoothDevice.BOND_BONDED -> Bond.BONDED
        BluetoothDevice.BOND_BONDING -> Bond.BONDING
        else -> Bond.NONE
    }

    override fun linkConnected(address: String): Boolean =
        manager.getConnectionState(device(address), BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED

    override suspend fun connect(address: String, autoConnect: Boolean, timeoutMs: Long?): GattPort {
        val port = AndroidGattPort()
        val gatt = device(address).connectGatt(context, autoConnect, port, BluetoothDevice.TRANSPORT_LE)
            ?: throw GattError(REFUSED, GattOp.CONNECT)
        port.gatt = gatt
        try {
            val ok = if (timeoutMs == null) port.connected.await() else withTimeoutOrNull(timeoutMs) { port.connected.await() }
            if (ok == null) {
                gatt.disconnect()
                gatt.close()
                throw GattError(TIMEOUT, GattOp.CONNECT)
            }
        } catch (e: GattError) {
            gatt.close()
            throw e
        } catch (e: CancellationException) {
            gatt.disconnect()
            gatt.close()
            throw e
        }
        return port
    }

    /** Every bonded device, Flipper-looking ones first: a renamed Flipper may advertise another name. */
    fun bonded(): List<BondedDevice> {
        if (state() != RadioState.ON) return emptyList()
        return manager.adapter.bondedDevices
            .map { d ->
                val name = cleanText(d.alias ?: d.name) ?: d.address
                BondedDevice(name, d.address, FlipperGatt.looksLikeFlipper(d.address, name))
            }
            .sortedWith(compareByDescending<BondedDevice> { it.looksLikeFlipper }.thenBy { it.name.lowercase() })
    }
}

/**
 * One GATT connection. Operations are serialized; each waits for its own callback and
 * ignores unrelated ones (for instance an MTU exchange the Flipper starts by itself).
 */
@SuppressLint("MissingPermission")
class AndroidGattPort internal constructor() : BluetoothGattCallback(), GattPort {
    internal lateinit var gatt: BluetoothGatt
    internal val connected = CompletableDeferred<Unit>()
    private val _disconnected = CompletableDeferred<Int>()
    override val disconnected: Deferred<Int> get() = _disconnected
    private val mutex = Mutex()
    @Volatile private var pending: Triple<String, GattOp, CompletableDeferred<Any?>>? = null
    @Volatile private var handler: (UUID, ByteArray) -> Unit = { _, _ -> }
    @Volatile private var mtuHandler: (Int) -> Unit = {}
    @Volatile private var closed = false

    // ---------------------------------------------------------------- callbacks (binder threads)

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            connected.complete(Unit)
            return
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            val error = GattError(status, GattOp.CONNECT)
            if (!connected.isCompleted) connected.completeExceptionally(error)
            _disconnected.complete(status)
            pending?.third?.completeExceptionally(error)
        }
    }

    private fun finish(kind: String, status: Int, value: Any?) {
        val p = pending ?: return
        if (p.first != kind) return
        if (status == BluetoothGatt.GATT_SUCCESS) p.third.complete(value) else p.third.completeExceptionally(GattError(status, p.second))
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
        val ours = pending?.first == "mtu"
        finish("mtu", status, mtu)
        if (!ours && status == BluetoothGatt.GATT_SUCCESS && !closed) mtuHandler(mtu)
    }
    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish("services", status, null)
    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish("descriptor", status, null)
    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish("write", status, null)
    override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
        finish("read", status, value)

    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        if (!closed) handler(c.uuid, value)
    }

    // ---------------------------------------------------------------- operations

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> run(kind: String, what: GattOp, timeoutMs: Long = 10_000, start: () -> Boolean): T = mutex.withLock {
        if (_disconnected.isCompleted) throw GattError(_disconnected.getCompleted(), what)
        val d = CompletableDeferred<Any?>()
        pending = Triple(kind, what, d)
        try {
            if (!start()) throw GattError(REFUSED, what)
            (withTimeoutOrNull(timeoutMs) { d.await() } ?: if (d.isCompleted) d.getCompleted() else throw GattError(TIMEOUT, what)) as T
        } finally {
            pending = null
        }
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
        gatt.getService(FlipperGatt.SERVICE)?.getCharacteristic(uuid) ?: throw GattError(REFUSED, GattOp.CHARACTERISTIC)

    override suspend fun requestMtu(mtu: Int): Int = run<Int>("mtu", GattOp.MTU) { gatt.requestMtu(mtu) }

    override fun requestFastConnection(): Boolean =
        runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }.getOrDefault(false)

    override suspend fun discoverSerial(): Boolean {
        run<Any?>("services", GattOp.SERVICES, 15_000) { gatt.discoverServices() }
        val service = gatt.getService(FlipperGatt.SERVICE) ?: return false
        return listOf(FlipperGatt.RX, FlipperGatt.TX, FlipperGatt.FLOW).all { service.getCharacteristic(it) != null }
    }

    override suspend fun enable(characteristic: UUID, indication: Boolean) {
        val c = characteristic(characteristic)
        if (!gatt.setCharacteristicNotification(c, true)) throw GattError(REFUSED, GattOp.SUBSCRIBE)
        val ccc = c.getDescriptor(FlipperGatt.CCC) ?: throw GattError(REFUSED, GattOp.SUBSCRIBE)
        val useNotify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 &&
            !(indication && c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
        val value = if (useNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        run<Any?>("descriptor", GattOp.SUBSCRIBE) { gatt.writeDescriptor(ccc, value) == BluetoothStatusCodes.SUCCESS }
    }

    override suspend fun read(characteristic: UUID): ByteArray {
        val c = characteristic(characteristic)
        return run("read", GattOp.READ) { gatt.readCharacteristic(c) }
    }

    override suspend fun write(characteristic: UUID, value: ByteArray) {
        val c = characteristic(characteristic)
        run<Any?>("write", GattOp.WRITE) {
            gatt.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        }
    }

    override fun onChanged(handler: (UUID, ByteArray) -> Unit) {
        this.handler = handler
    }

    override fun onMtu(handler: (Int) -> Unit) {
        this.mtuHandler = handler
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        runCatching { gatt.disconnect() }
        withTimeoutOrNull(1_500) { _disconnected.await() }
        runCatching { gatt.close() }
        _disconnected.complete(0)
    }
}
