package dev.heatcontrol.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import dev.heatcontrol.protocol.DaySchedule
import dev.heatcontrol.protocol.HeaterNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/** Finds heaters, remembers them, and fans commands out to one or all of them. */
@SuppressLint("MissingPermission")
class HeaterRepository(private val ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter
    private val prefs = ctx.getSharedPreferences("heaters", Context.MODE_PRIVATE)
    private val bonding = BondingHelper(ctx)

    init { DebugReceiver.registerIfDebuggable(ctx, this) }

    private val heaters = MutableStateFlow<Map<String, Heater>>(emptyMap())
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    @OptIn(ExperimentalCoroutinesApi::class)
    val states: StateFlow<List<HeaterState>> = heaters
        .flatMapLatest { map ->
            if (map.isEmpty()) flowOf(emptyList())
            else combine(map.values.map { it.state }) { arr -> arr.sortedBy { it.displayName.lowercase() } }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val bluetoothOn get() = adapter?.isEnabled == true

    /** Load remembered heaters, then refresh them all and scan for new ones. */
    @Volatile private var foreground = false

    fun start() {
        foreground = true
        loadKnown()
        scope.launch { scan() }
        scope.launch { refreshAll() }
        // Catch boosts that ended while the phone was away or the app was killed.
        boostManager.rearmAll()
    }

    fun stop() {
        foreground = false
        heaters.value.values.forEach { it.disconnect() }
    }

    private fun loadKnown() {
        val known = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty()
        known.forEach { addr ->
            val name = prefs.getString("name:$addr", null) ?: return@forEach
            add(addr, name)
        }
        adapter?.bondedDevices?.forEach { d -> if (HeaterNames.isHeater(d.name)) add(d.address, d.name) }
    }

    private fun add(address: String, advName: String) {
        if (heaters.value.containsKey(address) || adapter == null) return
        val device = adapter.getRemoteDevice(address)
        val friendly = prefs.getString("friendly:$address", "") ?: ""
        val h = Heater(ctx, device, advName, friendly, bonding, scope)
        heaters.value = heaters.value + (address to h)
        val known = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty() + address
        prefs.edit().putStringSet(KEY_KNOWN, known).putString("name:$address", advName).apply()
        Log.d(TAG, "added $advName $address")
    }

    suspend fun scan(seconds: Int = 10) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (_scanning.value) return
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name
                if (HeaterNames.isHeater(name)) {
                    val isNew = !heaters.value.containsKey(r.device.address)
                    add(r.device.address, name!!)
                    if (isNew) scope.launch { heaters.value[r.device.address]?.refresh() }
                }
            }
        }
        _scanning.value = true
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            delay(seconds * 1000L)
        } finally {
            runCatching { scanner.stopScan(cb) }
            _scanning.value = false
        }
    }

    fun rename(address: String, name: String) {
        prefs.edit().putString("friendly:$address", name.trim()).apply()
        heaters.value[address]?.rename(name.trim())
    }

    fun forget(address: String) {
        heaters.value[address]?.disconnect()
        heaters.value = heaters.value - address
        val known = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty() - address
        prefs.edit().putStringSet(KEY_KNOWN, known).remove("name:$address").remove("friendly:$address").apply()
    }

    /** Runs [action] on one heater (address) or on every heater in parallel (address = null). */
    fun command(address: String?, action: suspend (Heater) -> Unit) {
        val targets = if (address == null) heaters.value.values.toList() else listOfNotNull(heaters.value[address])
        scope.launch { targets.map { h -> async { action(h) } }.awaitAll() }
    }

    val boostManager = BoostManager.get(ctx)
    val boosts get() = boostManager.boosts

    fun refreshAll(includeSchedule: Boolean = false) = command(null) { it.refresh(includeSchedule) }

    // A manual change during a boost means the user has taken over: drop the pending restore.
    fun setTemperature(address: String?, t: Int) = command(address) { boostManager.clear(it.address); it.setTemperature(t) }
    fun setMode(address: String?, m: dev.heatcontrol.protocol.ModeType) = command(address) { boostManager.clear(it.address); it.setMode(m) }
    fun advance(address: String?, on: Boolean) = command(address) { it.advance(on) }
    fun readSchedules() = command(null) { it.readSchedule() }
    fun writeSchedule(address: String?, week: List<DaySchedule>) = command(address) { boostManager.clear(it.address); it.writeSchedule(week) }

    /** Boost one heater or all of them for [minutes]; the app restores the old mode afterwards. */
    fun boost(address: String?, minutes: Int) = command(address) { h ->
        h.startBoost(minutes, null, boostManager.get(h.address))?.let { boostManager.arm(it) }
    }

    /** End a boost now and put the previous mode back. */
    fun stopBoost(address: String?) = command(address) { h ->
        boostManager.get(h.address)?.let { b -> if (h.restore(b)) boostManager.clear(h.address) }
    }

    /** Called by RestoreWorker in the background. */
    suspend fun restoreBoost(b: Boost): Boolean {
        loadKnown()
        val h = heaters.value[b.address] ?: run { boostManager.clear(b.address, cancelWork = false); return true }
        val ok = h.restore(b)
        if (ok) boostManager.clear(b.address, cancelWork = false)
        if (!foreground) h.disconnect()
        return ok
    }

    companion object {
        private const val TAG = "HeaterRepo"
        private const val KEY_KNOWN = "known"

        @Volatile private var instance: HeaterRepository? = null
        fun get(ctx: Context) = instance ?: synchronized(this) {
            instance ?: HeaterRepository(ctx.applicationContext).also { instance = it }
        }
    }
}
