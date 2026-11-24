package dev.heatcontrol.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import dev.heatcontrol.protocol.Advance
import dev.heatcontrol.protocol.Codec
import dev.heatcontrol.protocol.DaySchedule
import dev.heatcontrol.protocol.HeatMode
import dev.heatcontrol.protocol.HeaterNames
import dev.heatcontrol.protocol.ModeState
import dev.heatcontrol.protocol.ModeType
import dev.heatcontrol.protocol.Pin
import dev.heatcontrol.protocol.ScheduleMode
import dev.heatcontrol.protocol.Uuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId

data class HeaterState(
    val address: String,
    val advName: String,
    val friendlyName: String,
    val link: Link = Link.Disconnected,
    val mode: ModeState? = null,
    val target: Int? = null,
    val range: IntRange = 5..30,
    val advance: Advance? = null,
    val firmware: String? = null,
    val model: String? = null,
    val schedule: Map<Int, DaySchedule> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val pin get() = Pin.fromMac(address)
    val type get() = HeaterNames.type(advName)
    val displayName get() = friendlyName.ifBlank { HeaterNames.shortName(advName) }
}

/** State and commands for one heater. Every command connects on demand. */
class Heater(
    ctx: Context,
    device: BluetoothDevice,
    advName: String,
    friendlyName: String,
    bonding: BondingHelper,
    private val scope: CoroutineScope,
) {
    val conn = HeaterConnection(ctx, device, bonding)
    private val _state = MutableStateFlow(HeaterState(device.address, advName, friendlyName))
    val state: StateFlow<HeaterState> = _state
    val address: String = device.address
    private val cmdLock = Mutex()

    init {
        scope.launch { conn.link.collect { l -> _state.update { it.copy(link = l) } } }
    }

    fun rename(name: String) = _state.update { it.copy(friendlyName = name) }

    /** Runs [block] with a connected heater, recording busy/error state. */
    suspend fun run(label: String, block: suspend Heater.() -> Unit): Boolean = cmdLock.withLock {
        _state.update { it.copy(busy = true, error = null) }
        try {
            conn.connect()
            // The model decides which temperature characteristic to use; a fresh process
            // (e.g. the background boost restore) hasn't read it yet.
            if (_state.value.model == null && conn.has(Uuids.SW_MOD)) {
                val m = conn.read(Uuids.SW_MOD).toString(Charsets.US_ASCII).trim { it <= ' ' }
                _state.update { it.copy(model = m) }
            }
            block()
            true
        } catch (e: Exception) {
            Log.w(TAG, "$address $label failed", e)
            _state.update { it.copy(error = "$label: ${e.message ?: e.javaClass.simpleName}") }
            false
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /**
     * InteliApp picks the screen from the 200D model string: "P..." (PLXE) and "S..." (XLE)
     * set temperature through 1023; plain aluminium radiators use 1003.
     */
    private val tempUuid: java.util.UUID
        get() {
            val newStyle = _state.value.model?.let { it.startsWith("P") || it.startsWith("S") } == true
            return when {
                newStyle && conn.has(Uuids.NEW_TEMPERATURE) -> Uuids.NEW_TEMPERATURE
                conn.has(Uuids.TEMPERATURE) -> Uuids.TEMPERATURE
                else -> Uuids.NEW_TEMPERATURE
            }
        }

    suspend fun refresh(includeSchedule: Boolean = false) = run("Refresh") {
        syncClock()
        readStatus()
        if (_state.value.firmware == null && conn.has(Uuids.FIRMWARE)) {
            conn.write(Uuids.FIRMWARE, Codec.FIRMWARE_REQUEST)
            val fw = Codec.parseFirmware(conn.read(Uuids.FIRMWARE))
            _state.update { it.copy(firmware = fw) }
        }
        if (conn.has(Uuids.TEMPERATURE_RANGE)) {
            Codec.parseRange(conn.read(Uuids.TEMPERATURE_RANGE))?.let { r ->
                if (r.first in 1..r.last) _state.update { it.copy(range = r) }
            }
        }
        if (includeSchedule) readScheduleInternal()
    }

    private suspend fun readStatus() {
        val mode = ModeState.parse(conn.read(Uuids.MODE))
        val target = Codec.parseTemperature(conn.read(tempUuid))
        val adv = if (conn.has(Uuids.ADVANCE)) Codec.parseAdvance(conn.read(Uuids.ADVANCE)) else null
        _state.update { it.copy(mode = mode, target = target ?: mode?.temperature, advance = adv) }
    }

    private suspend fun syncClock() {
        if (!conn.has(Uuids.RTC)) return
        val now = LocalDateTime.now()
        val dst = ZoneId.systemDefault().rules.isDaylightSavings(java.time.Instant.now())
        conn.write(Uuids.RTC, Codec.rtc(now, dst))
    }

    private fun currentMode() = _state.value.mode
        ?: ModeState(ModeType.MANUAL, ScheduleMode.USER_TIMER, HeatMode.OFF, 0, _state.value.target ?: 21)

    /** Manual mode at [temp]. Wakes the heater from standby/schedule if needed. */
    suspend fun setTemperature(temp: Int) = run("Set temperature") {
        val t = temp.coerceIn(_state.value.range)
        val m = currentMode()
        if (m.modeType != ModeType.MANUAL) {
            conn.write(Uuids.MODE, m.copy(modeType = ModeType.MANUAL, heatMode = HeatMode.OFF, temperature = t).encode())
        }
        conn.write(tempUuid, Codec.temperature(t))
        readStatus()
    }

    /**
     * Changes mode only. Tested on PSLFS6: the heater accepts modeType 1/2/3/4/6 in 1001 and
     * keeps its own set temperatures, so we don't touch 1023 here. Mode 5 (Boost) is ignored.
     */
    suspend fun setMode(type: ModeType) = run("Set ${type.label}") {
        val m = currentMode()
        val sched = if (type == ModeType.SCHEDULE) ScheduleMode.USER_TIMER else ScheduleMode.NONE
        val temp = _state.value.target?.takeIf { it >= 5 } ?: 21
        conn.write(Uuids.MODE, m.copy(modeType = type, scheduleMode = sched, heatMode = HeatMode.OFF, boostMode = 0, temperature = temp).encode())
        readStatus()
    }

    /**
     * Advance: in Schedule mode, jump to the next schedule period now. The heater ignores the
     * timed-boost values (2-5) on PSLFS6 firmware 51, so only on/off is offered.
     */
    suspend fun advance(on: Boolean) = run(if (on) "Advance" else "Cancel advance") {
        conn.write(Uuids.ADVANCE, Codec.advance(if (on) Advance.ADVANCE else Advance.OFF))
        readStatus()
    }

    /**
     * App-timed boost: remember the current mode, switch to Manual at [temp] for [minutes].
     * Returns the record needed to restore, or null if the heater couldn't be reached.
     */
    suspend fun startBoost(minutes: Int, temp: Int?, existing: Boost?): Boost? {
        var boost: Boost? = null
        val ok = run("Boost") {
            readStatus()
            val m = currentMode()
            val current = _state.value.target ?: 20
            val t = (temp ?: maxOf(current + 2, 22)).coerceIn(_state.value.range)
            // If already boosting, keep the original "before" state so Stop goes back to it.
            boost = existing?.copy(until = System.currentTimeMillis() + minutes * 60_000L, temp = t, restoreFailed = false)
                ?: Boost(address, System.currentTimeMillis() + minutes * 60_000L, t, m.modeType, m.scheduleMode, current)
            conn.write(Uuids.MODE, m.copy(modeType = ModeType.MANUAL, scheduleMode = ScheduleMode.NONE, heatMode = HeatMode.OFF, boostMode = 0, temperature = t).encode())
            conn.write(tempUuid, Codec.temperature(t))
            readStatus()
        }
        return if (ok) boost else null
    }

    /** Puts back the mode (and, for Manual, the temperature) from before the boost. */
    suspend fun restore(b: Boost): Boolean = run("End boost") {
        val m = currentMode()
        val sched = if (b.prevMode == ModeType.SCHEDULE) ScheduleMode.USER_TIMER else b.prevScheduleMode
        conn.write(Uuids.MODE, m.copy(modeType = b.prevMode, scheduleMode = sched, heatMode = HeatMode.OFF, boostMode = 0, temperature = b.prevTarget).encode())
        if (b.prevMode == ModeType.MANUAL) conn.write(tempUuid, Codec.temperature(b.prevTarget))
        readStatus()
    }

    suspend fun readSchedule() = run("Read schedule") { readScheduleInternal() }

    private suspend fun readScheduleInternal() {
        val days = mutableMapOf<Int, DaySchedule>()
        for (day in 0..6) {
            conn.write(Uuids.SCHEDULE, DaySchedule.readRequest(day))
            DaySchedule.parse(conn.read(Uuids.SCHEDULE))?.let { days[it.day] = it }
        }
        _state.update { it.copy(schedule = days) }
    }

    /** Writes the week to the heater and switches it to schedule mode. */
    suspend fun writeSchedule(week: List<DaySchedule>, activate: Boolean = true) = run("Save schedule") {
        syncClock()
        for (d in week) conn.write(Uuids.SCHEDULE, d.encode())
        if (activate) {
            conn.write(Uuids.MODE, currentMode().copy(modeType = ModeType.SCHEDULE, scheduleMode = ScheduleMode.USER_TIMER).encode())
        }
        readScheduleInternal()
        readStatus()
    }

    fun disconnect() = conn.disconnect()

    companion object { private const val TAG = "Heater" }
}
