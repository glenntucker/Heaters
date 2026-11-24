package dev.heatcontrol.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import dev.heatcontrol.protocol.Pin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles BLE bonding. Answers the pairing request with the PIN derived from the heater's
 * MAC address (InteliApp's "auto pairing"). If the phone shows its own pairing dialog anyway,
 * the user can type the PIN shown on the heater display (the app also shows the derived PIN).
 */
@SuppressLint("MissingPermission")
class BondingHelper(private val ctx: Context) {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
            val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
            Log.d(TAG, "${dev.address} bond $prev -> $state")
            when (state) {
                BluetoothDevice.BOND_BONDED -> waiting.remove(dev.address)?.complete(true)
                BluetoothDevice.BOND_NONE -> if (prev == BluetoothDevice.BOND_BONDING) {
                    waiting.remove(dev.address)?.complete(false)
                }
            }
        }
    }

    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (!waiting.containsKey(dev.address)) return
            val variant = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
            val pin = Pin.fromMac(dev.address)
            Log.d(TAG, "${dev.address} pairing request variant=$variant")
            if (variant == BluetoothDevice.PAIRING_VARIANT_PIN && pin != null) {
                if (dev.setPin(pin.toByteArray())) abortBroadcast()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            ctx, bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        val pf = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        ContextCompat.registerReceiver(ctx, pairingReceiver, pf, ContextCompat.RECEIVER_EXPORTED)
    }

    /** Returns true once bonded. Waits up to 90 s so a user can type the PIN manually. */
    suspend fun bond(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        val d = waiting.getOrPut(device.address) { CompletableDeferred() }
        if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
            waiting.remove(device.address)
            return false
        }
        return withTimeoutOrNull(90_000) { d.await() } ?: run {
            waiting.remove(device.address)
            false
        }
    }

    companion object { private const val TAG = "Bonding" }
}
