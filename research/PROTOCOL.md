# Creda / Glen Dimplex "Intelirad" BLE protocol

Derived from decompiling InteliApp (`com.heatstore.inteliapp`, Glen Dimplex), whose BLE
layer is the `com.eyespyfx.gdble` library, plus a GATT dump of two heaters
(`AL5897<Creda>`, `AL7403<Creda>`) on 2026-11-24.

## Discovery

- Advertised name: `<prefix><4 chars><Brand>`, e.g. `AL5897<Creda>`. No services advertised.
- Prefix gives type: `AL` Aluminium, `AH` Acronym, `EC` Convector, `SZ` Maxi, `CV` C4,
  `FI` Fire, `QB` Qube, `WC` Wall Controller, `BL` = in bootloader (don't touch).

## Security / pairing

- All setting characteristics need an **encrypted, bonded** link (ATT error 5,
  "insufficient authentication", until bonded).
- Standard Android bonding: `device.createBond()`, PIN passkey entry.
- **The PIN is derived from the MAC address** (InteliApp "auto pairing"):
  1. Take the last 3 bytes of the MAC as 6 hex chars, e.g. `..:12:AB:3F` -> `12AB3F`.
  2. Map each char: digit `d` -> `9 - d`; `A..F` -> `9 - (0..5)` i.e. A=9 B=8 C=7 D=6 E=5 F=4.
  3. Reverse the 6 resulting digits. That string is the PIN.
  - InteliApp only auto-pairs if characteristic `200D` exists; otherwise the user
    types the PIN shown on the heater display. *(Not yet verified on a real unit.)*
- MTU: app requests 276 (OTA size); minimum it accepts is 46.

## Characteristics

All are 16-bit UUIDs in the Bluetooth base (`0000xxxx-0000-1000-8000-00805F9B34FB`),
spread over services `00000000-000N-1000-8000-00805F9B34FB` (N = 0..3).
Values are little-endian bytes; temperatures are whole degrees.

| UUID | Name | Access | Format |
|------|------|--------|--------|
| `1001` | Mode | R/W | 6 bytes: `[modeType, scheduleMode, heatMode, boostMode, temperature, 0]` |
| `1002` | Schedule (per day) | R/W, write-to-read | 31 bytes, see below |
| `1003` | Target temperature (legacy) | R/W | `[temp, 0]` |
| `1023` | Target temperature (newer firmware) | R/W | `[temp, 0]` |
| `1005` | Temperature unit | R/W | `[unit]` |
| `1006` | Temperature range | R | `[min, 0, max, 0]` |
| `100D` | Advance / boost | R/W | `[advance]` |
| `0005` | Sound | R/W | `[setting, 0, 0]` |
| `0006` | Real-time clock | W | `[yearHi, yearLo, month(1-12), day, hour, minute, dst(0/1)]` e.g. 2026 -> `20, 26` |
| `2001` | Firmware version | R/W, write-to-read | write `[1,0,0]`, then read `[?, major, minor]` |
| `200D` | Software modification | R | ASCII string |

"Write-to-read": to read, first write a request value, then read the same characteristic.

### Enums

- **modeType** (`1001[0]`): 1 Schedule, 2 Manual, 3 Eco, 4 Frost, 5 Boost, 6 Standby (off),
  7 Back-to-last-mode.
- **scheduleMode** (`1001[1]`, `1002[1]`): 0 None, 1 Out all day, 2 Home all day, 3 User timer,
  4 Holiday, 5 Away.
- **heatMode** (`1001[2]`, schedule slots): 0 Off, 1 Eco, 2 Low, 3 High.
- **boostMode** (`1001[3]`): 0 Off, 1 30 min, 2 1 h, 3 2 h, 4 3 h, 5 4 h.
- **advance** (`100D`): 0 Off, 1 Advance, 2 1 h, 3 2 h, 4 3 h, 5 4 h, 6 Auto boost,
  12/13/14 Advance + 1/2/3 h boost, 16 Advance + auto boost.

### Schedule (`1002`), 31 bytes

```
[0]  1 = write data, 0 = read request
[1]  scheduleMode (3 = user timer)
[2]  day: 0 Mon .. 6 Sun
[3..26]  4 slots x 6 bytes: [startH, startM, endH, endM, temp, 0]
[27..30] heatMode for each of the 4 slots
```

To read a day: write `[0, scheduleMode, day, 0...]` (31 bytes), then read `1002`.
The reply's byte 2 says which day it is.

## Verified on real heaters (PSLFS6, firmware 51.0, 2026-11-24)

- `200D` = `"PSLFS6\0\0"` -> InteliApp's PLXE type. Temperature lives in `1023`.
- PIN derived from the MAC worked: Android raised PAIRING_VARIANT_PIN, `setPin` bonded silently.
- `1001` accepts modeType 1 (with scheduleMode 3), 2, 3, 4, 6. Byte 4 always reads back 0;
  the real set-point is in `1023`. In Schedule mode `1023` reports the current period's temp.
- `1001` modeType 5 (Boost) and `100D` values 2-5 (timed boost) are **ignored** (read back
  unchanged). `100D` = 1 (Advance) works.
- `1002` write persists; reading back after a read-request returns the written day.
  Real schedules use all 4 slots as back-to-back periods, e.g. 06:00-09:00, 09:00-12:00,
  12:00-17:00, 17:00-22:30.
- Connecting to two heaters at once sometimes fails first time with status 147; a retry works.

## InteliApp connect sequence

connect GATT (LE transport) -> wait 1 s -> discover services -> request MTU 276 ->
(on auth error) createBond -> answer pairing request with derived PIN -> rediscover ->
write RTC -> read Mode, Temperature, Range, Unit, Advance, Sound, Firmware, schedules.
