package dev.heatcontrol.protocol

import java.time.LocalDateTime
import java.util.UUID

/** Glen Dimplex / Creda "Intelirad" BLE protocol. See research/PROTOCOL.md. */
object Uuids {
    fun short(id: Int): UUID = UUID.fromString(String.format("%08X-0000-1000-8000-00805F9B34FB", id))

    val SOUND = short(0x0005)
    val RTC = short(0x0006)
    val MODE = short(0x1001)
    val SCHEDULE = short(0x1002)
    val TEMPERATURE = short(0x1003)
    val TEMPERATURE_UNIT = short(0x1005)
    val TEMPERATURE_RANGE = short(0x1006)
    val ADVANCE = short(0x100D)
    val NEW_TEMPERATURE = short(0x1023)
    val FIRMWARE = short(0x2001)
    val SW_MOD = short(0x200D)
}

enum class ModeType(val v: Int, val label: String) {
    SCHEDULE(1, "Schedule"), MANUAL(2, "Manual"), ECO(3, "Eco"), FROST(4, "Frost"),
    BOOST(5, "Boost"), STANDBY(6, "Off"), BACK_TO_LAST(7, "Last mode");

    companion object { fun of(v: Int) = entries.firstOrNull { it.v == v } ?: STANDBY }
}

enum class ScheduleMode(val v: Int) {
    NONE(0), OUT_ALL_DAY(1), HOME_ALL_DAY(2), USER_TIMER(3), HOLIDAY(4), AWAY(5);

    companion object { fun of(v: Int) = entries.firstOrNull { it.v == v } ?: NONE }
}

enum class HeatMode(val v: Int) {
    OFF(0), ECO(1), LOW(2), HIGH(3);

    companion object { fun of(v: Int) = entries.firstOrNull { it.v == v } ?: OFF }
}

/** Values for characteristic 100D. */
enum class Advance(val v: Int, val label: String) {
    OFF(0, "Off"), ADVANCE(1, "Advance"), ONE_HOUR(2, "1 h"), TWO_HOURS(3, "2 h"),
    THREE_HOURS(4, "3 h"), FOUR_HOURS(5, "4 h"), AUTO_BOOST(6, "Auto"),
    ADV_ONE_HOUR_BOOST(12, "Adv + 1 h"), ADV_TWO_HOUR_BOOST(13, "Adv + 2 h"),
    ADV_THREE_HOUR_BOOST(14, "Adv + 3 h"), ADV_AUTO_BOOST(16, "Adv + auto");

    val isBoost get() = this != OFF && this != ADVANCE

    companion object {
        fun of(v: Int) = entries.firstOrNull { it.v == v } ?: OFF
        fun boostHours(h: Int) = when (h) {
            1 -> ONE_HOUR; 2 -> TWO_HOURS; 3 -> THREE_HOURS; 4 -> FOUR_HOURS
            else -> throw IllegalArgumentException("boost hours must be 1-4")
        }
    }
}

private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF

/** Characteristic 1001: [modeType, scheduleMode, heatMode, boostMode, temperature, 0]. */
data class ModeState(
    val modeType: ModeType,
    val scheduleMode: ScheduleMode,
    val heatMode: HeatMode,
    val boostMode: Int,
    val temperature: Int,
) {
    fun encode() = byteArrayOf(
        modeType.v.toByte(), scheduleMode.v.toByte(), heatMode.v.toByte(),
        boostMode.toByte(), temperature.toByte(), 0,
    )

    companion object {
        fun parse(b: ByteArray): ModeState? = if (b.size != 6) null else ModeState(
            ModeType.of(b.u(0)), ScheduleMode.of(b.u(1)), HeatMode.of(b.u(2)), b.u(3), b.u(4),
        )
    }
}

/** One of four timer slots in a day. Minutes are 0-59; hours 0-23 (end 24:00 = 0:00 next day). */
data class Slot(
    val startH: Int, val startM: Int,
    val endH: Int, val endM: Int,
    val temperature: Int,
    val heatMode: HeatMode = HeatMode.OFF,
) {
    val isEmpty get() = startH == 0 && startM == 0 && endH == 0 && endM == 0

    fun encode() = byteArrayOf(startH.toByte(), startM.toByte(), endH.toByte(), endM.toByte(), temperature.toByte(), 0)

    companion object { val EMPTY = Slot(0, 0, 0, 0, 0) }
}

/** Characteristic 1002: weekly timer for one day (0 = Monday .. 6 = Sunday). */
data class DaySchedule(
    val day: Int,
    val slots: List<Slot>,
    val scheduleMode: ScheduleMode = ScheduleMode.USER_TIMER,
) {
    init {
        require(day in 0..6) { "day must be 0..6" }
        require(slots.size == 4) { "exactly 4 slots" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(LENGTH)
        out[0] = 1
        out[1] = scheduleMode.v.toByte()
        out[2] = day.toByte()
        slots.forEachIndexed { i, s -> s.encode().copyInto(out, 3 + i * 6) }
        slots.forEachIndexed { i, s -> out[27 + i] = s.heatMode.v.toByte() }
        return out
    }

    companion object {
        const val LENGTH = 31
        val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        /** Value written before reading 1002 to select which day the heater returns. */
        fun readRequest(day: Int, mode: ScheduleMode = ScheduleMode.USER_TIMER): ByteArray =
            ByteArray(LENGTH).also { it[0] = 0; it[1] = mode.v.toByte(); it[2] = day.toByte() }

        fun parse(b: ByteArray): DaySchedule? {
            if (b.size != LENGTH || b.u(2) !in 0..6) return null
            val slots = (0 until 4).map { i ->
                val o = 3 + i * 6
                Slot(b.u(o), b.u(o + 1), b.u(o + 2), b.u(o + 3), b.u(o + 4), HeatMode.of(b.u(27 + i)))
            }
            return DaySchedule(b.u(2), slots, ScheduleMode.of(b.u(1)))
        }

        /** A typical weekday: warm in the morning and evening. */
        fun default(day: Int, comfort: Int = 21) = DaySchedule(
            day,
            listOf(
                Slot(6, 30, 8, 30, comfort),
                Slot(17, 0, 22, 30, comfort),
                Slot.EMPTY, Slot.EMPTY,
            ),
        )
    }
}

object Codec {
    /** Characteristics 1003 / 1023. */
    fun temperature(t: Int) = byteArrayOf(t.toByte(), 0)
    fun parseTemperature(b: ByteArray): Int? = if (b.size == 2) b.u(0) else null

    /** Characteristic 1006: [min, 0, max, 0]. */
    fun parseRange(b: ByteArray): IntRange? = if (b.size == 4) b.u(0)..b.u(2) else null

    fun advance(a: Advance) = byteArrayOf(a.v.toByte())
    fun parseAdvance(b: ByteArray): Advance? = if (b.size == 1) Advance.of(b.u(0)) else null

    /** Characteristic 0006, write only. */
    fun rtc(now: LocalDateTime, dst: Boolean) = byteArrayOf(
        (now.year / 100).toByte(), (now.year % 100).toByte(), now.monthValue.toByte(),
        now.dayOfMonth.toByte(), now.hour.toByte(), now.minute.toByte(), if (dst) 1 else 0,
    )

    /** Characteristic 2001: write [1,0,0] then read [?, major, minor]. */
    val FIRMWARE_REQUEST = byteArrayOf(1, 0, 0)
    fun parseFirmware(b: ByteArray): String? = if (b.size == 3) "${b.u(1)}.${b.u(2)}" else null
}

object Pin {
    /**
     * InteliApp's "auto pairing" PIN: last three MAC bytes as hex; each hex char c maps to
     * 9 - value(c) where A..F count as 0..5; the six digits are then reversed.
     */
    fun fromMac(mac: String): String? {
        val parts = mac.split(":")
        if (parts.size != 6) return null
        val nibbles = (parts[3] + parts[4] + parts[5]).uppercase()
        val digits = nibbles.map { c ->
            val n = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'F' -> c - 'A'
                else -> return null
            }
            9 - n
        }
        return digits.reversed().joinToString("")
    }
}

object HeaterNames {
    private val PREFIXES = mapOf(
        "AL" to "Aluminium radiator", "AH" to "Acronym", "EC" to "Convector", "SZ" to "Maxi",
        "CV" to "C4", "FI" to "Fire", "QB" to "Qube", "WC" to "Wall controller",
    )

    /** True for names like "AL5897<Creda>". Bootloader ("BL...") devices are excluded. */
    fun isHeater(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return name.contains("<Creda>", ignoreCase = true) ||
            PREFIXES.keys.any { name.uppercase().startsWith(it) && name.length >= 6 }
    }

    fun shortName(name: String) = name.substringBefore('<').ifBlank { name }
    fun type(name: String) = PREFIXES[name.take(2).uppercase()] ?: "Heater"
}
