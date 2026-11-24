package dev.heatcontrol.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ProtocolTest {
    @Test fun pinFromMac() {
        // nibbles 1,2,A,B,3,F -> 9-1,9-2,9-0,9-1,9-3,9-5 = 8,7,9,8,6,4 -> reversed 468978
        assertEquals("468978", Pin.fromMac("00:11:22:12:AB:3F"))
        assertEquals("999999", Pin.fromMac("aa:bb:cc:00:00:00"))
        assertNull(Pin.fromMac("bad"))
    }

    @Test fun modeRoundTrip() {
        val m = ModeState(ModeType.MANUAL, ScheduleMode.USER_TIMER, HeatMode.OFF, 0, 21)
        assertArrayEquals(byteArrayOf(2, 3, 0, 0, 21, 0), m.encode())
        assertEquals(m, ModeState.parse(m.encode()))
        assertNull(ModeState.parse(byteArrayOf(1, 2)))
    }

    @Test fun scheduleRoundTrip() {
        val d = DaySchedule(
            2,
            listOf(Slot(6, 30, 8, 0, 21, HeatMode.HIGH), Slot(17, 15, 22, 45, 20), Slot.EMPTY, Slot.EMPTY),
        )
        val bytes = d.encode()
        assertEquals(31, bytes.size)
        assertEquals(1, bytes[0].toInt())
        assertEquals(3, bytes[1].toInt())
        assertEquals(2, bytes[2].toInt())
        assertArrayEquals(byteArrayOf(6, 30, 8, 0, 21, 0), bytes.copyOfRange(3, 9))
        assertEquals(3, bytes[27].toInt())
        // parse ignores the write flag in byte 0
        assertEquals(d, DaySchedule.parse(bytes))
    }

    @Test fun scheduleReadRequest() {
        val r = DaySchedule.readRequest(6)
        assertEquals(31, r.size)
        assertEquals(0, r[0].toInt())
        assertEquals(6, r[2].toInt())
    }

    @Test fun rtc() {
        assertArrayEquals(
            byteArrayOf(20, 26, 11, 24, 20, 5, 0),
            Codec.rtc(LocalDateTime.of(2026, 11, 24, 20, 5), false),
        )
    }

    @Test fun names() {
        assertTrue(HeaterNames.isHeater("AL5897<Creda>"))
        assertFalse(HeaterNames.isHeater("Govee_H70D1_A927"))
        assertFalse(HeaterNames.isHeater(null))
        assertEquals("AL5897", HeaterNames.shortName("AL5897<Creda>"))
    }

    @Test fun rangeAndTemp() {
        assertEquals(7..30, Codec.parseRange(byteArrayOf(7, 0, 30, 0)))
        assertEquals(19, Codec.parseTemperature(byteArrayOf(19, 0)))
        assertEquals(Advance.TWO_HOURS, Advance.boostHours(2))
    }
}
