package dev.heatcontrol.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

sealed interface Link {
    data object Disconnected : Link
    data object Connecting : Link
    data object Pairing : Link
    data object Ready : Link
    data class Failed(val reason: String) : Link
}

class BleException(msg: String) : Exception(msg)

/**
 * One GATT connection to one heater. All GATT operations are serialised through [opLock]
 * because Android only allows one outstanding operation per connection.
 */
@SuppressLint("MissingPermission")
class HeaterConnection(
    private val ctx: Context,
    val device: BluetoothDevice,
    private val bonding: BondingHelper,
) {
    private val _link = MutableStateFlow<Link>(Link.Disconnected)
    val link: StateFlow<Link> = _link

    @Volatile private var gatt: BluetoothGatt? = null
    private val main = android.os.Handler(ctx.mainLooper)
    private val chars = mutableMapOf<UUID, BluetoothGattCharacteristic>()
    private val opLock = Mutex()
    private val connectLock = Mutex()
    private var pending: CompletableDeferred<Pair<Int, ByteArray>>? = null

    val isReady get() = _link.value == Link.Ready

    fun has(uuid: UUID) = chars.containsKey(uuid)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "${device.address} state=$newState status=$status")
            if (g !== gatt) {
                // A connection attempt we already gave up on finally answered - drop it.
                g.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Let the link settle before discovery; InteliApp waits 1 s too.
                main.postDelayed({ if (g === gatt) g.discoverServices() }, 800)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                chars.clear()
                g.close()
                if (gatt === g) gatt = null
                pending?.complete(-1 to ByteArray(0))
                if (_link.value !is Link.Failed) _link.value = Link.Disconnected
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            chars.clear()
            g.services.forEach { s -> s.characteristics.forEach { chars[it.uuid] = it } }
            Log.d(TAG, "${device.address} discovered ${chars.size} characteristics: " +
                g.services.joinToString(" | ") { s ->
                    s.uuid.toString().substring(4, 13) + ":" + s.characteristics.joinToString(",") { it.uuid.toString().substring(4, 8) }
                })
            if (!g.requestMtu(MTU)) {
                _link.value = Link.Ready
            } else {
                // The MTU reply sometimes never comes; every value here fits the default MTU.
                main.postDelayed({
                    if (g === gatt && _link.value == Link.Connecting) {
                        Log.d(TAG, "${device.address} no MTU reply, carrying on")
                        _link.value = Link.Ready
                    }
                }, 3000)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt) return
            Log.d(TAG, "${device.address} mtu=$mtu")
            _link.value = Link.Ready
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (g === gatt) pending?.complete(status to value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (g === gatt) pending?.complete(status to ByteArray(0))
        }
    }

    suspend fun connect() = connectLock.withLock {
        if (isReady) return@withLock
        // Heaters sometimes refuse the first attempt (status 133/147), especially when two
        // links start together; InteliApp also retries up to 3 times.
        for (attempt in 1..MAX_TRIES) {
            _link.value = Link.Connecting
            gatt?.close()
            gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeout(CONNECT_TIMEOUT_MS) {
                    link.first { it == Link.Ready || it == Link.Disconnected || it is Link.Failed }
                }
            } catch (e: Exception) {
                Log.d(TAG, "${device.address} connect attempt $attempt timed out")
            }
            if (isReady) break
            gatt?.close()
            gatt = null
            if (attempt < MAX_TRIES) delay(1500L * attempt)
        }
        if (!isReady) fail("could not connect - is it in range?")
        // Pair up front so later reads don't race the pairing dialog.
        if (device.bondState != BluetoothDevice.BOND_BONDED) pair()
    }

    private suspend fun pair() {
        _link.value = Link.Pairing
        val ok = bonding.bond(device)
        if (!ok) fail("pairing failed - check the PIN on the heater display")
        _link.value = if (gatt != null) Link.Ready else Link.Disconnected
        if (!isReady) connectAgain()
    }

    private suspend fun connectAgain() {
        delay(500)
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        _link.value = Link.Connecting
        withTimeout(CONNECT_TIMEOUT_MS) { link.first { it == Link.Ready || it is Link.Failed } }
    }

    private fun fail(reason: String): Nothing {
        _link.value = Link.Failed(reason)
        disconnect(keepFailure = true)
        throw BleException(reason)
    }

    fun disconnect(keepFailure: Boolean = false) {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        chars.clear()
        if (!keepFailure || _link.value !is Link.Failed) _link.value = Link.Disconnected
    }

    suspend fun read(uuid: UUID): ByteArray = op(uuid) { g, c -> g.readCharacteristic(c) }

    suspend fun write(uuid: UUID, value: ByteArray) {
        op(uuid) { g, c ->
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        }
    }

    private suspend fun op(uuid: UUID, start: (BluetoothGatt, BluetoothGattCharacteristic) -> Boolean): ByteArray {
        repeat(2) { attempt ->
            if (!isReady) connect()
            val (status, value) = opLock.withLock {
                val g = gatt ?: throw BleException("not connected")
                val c = chars[uuid] ?: throw BleException("heater has no ${uuid.toString().substring(4, 8)}")
                val d = CompletableDeferred<Pair<Int, ByteArray>>()
                pending = d
                if (!start(g, c)) throw BleException("Bluetooth busy")
                try {
                    withTimeout(OP_TIMEOUT_MS) { d.await() }
                } finally {
                    pending = null
                }
            }
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.d(TAG, "${device.address} ${uuid.toString().substring(4, 8)} ok [${value.joinToString(",") { (it.toInt() and 0xFF).toString() }}]")
                    return value
                }
                GATT_INSUFFICIENT_AUTHENTICATION, BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION ->
                    if (attempt == 0) pair() else throw BleException("heater refused - not paired")
                else -> if (attempt == 1) throw BleException("GATT error $status")
            }
        }
        throw BleException("unreachable")
    }

    companion object {
        private const val TAG = "HeaterConnection"
        private const val MTU = 276
        private const val MAX_TRIES = 3
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val OP_TIMEOUT_MS = 8_000L
    }
}
