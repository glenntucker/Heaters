# Heaters

An Android app for controlling Creda / Glen Dimplex Bluetooth panel heaters
(the "Intelirad" range normally used with the InteliApp app), built for two
heaters at home: **One** (AL5897) and **Two** (AL7403), model PSLFS6, firmware 51.0.

## Install

Download `Heaters.apk` from the [latest release](../../releases/latest), open it on
the phone and allow installing from that source. Android 13 or newer.

Close InteliApp before using this app: the heaters accept only one Bluetooth
connection at a time.

## Features

- Finds the heaters and **pairs automatically**: the PIN is derived from the
  heater's Bluetooth address, so no code needs typing.
- Per heater and **both at once**: target temperature, Manual / Eco / Frost /
  Schedule / Off.
- **Weekly schedule editor**: 4 heating periods per day, copy to weekdays /
  weekend / every day. Saved into the heaters, which then run it on their own
  clock without the phone.
- **Advance**: jump to the next schedule period.
- **Timed boost** (30 min - 3 h): the heaters' firmware can't time a boost, so the
  app does it - Manual at +2° now, previous mode restored afterwards, even with the
  app closed. The phone must be in Bluetooth range when the boost ends.
- Syncs the heaters' clocks on every connect.

## Project layout

| Path | What |
|------|------|
| `app/src/main/java/dev/heatcontrol/protocol/` | Byte-level protocol (pure Kotlin, unit tested) |
| `app/src/main/java/dev/heatcontrol/ble/` | GATT connection, pairing, heater commands, boost timer |
| `app/src/main/java/dev/heatcontrol/ui/` | Jetpack Compose UI and theme |
| `research/PROTOCOL.md` | The heater BLE protocol, including what was verified on real units |

## Build

Needs JDK 17 and the Android SDK (platform 35).

```bash
export JAVA_HOME=$(brew --prefix openjdk@17) ANDROID_HOME=~/Library/Android/sdk
./gradlew testDebugUnitTest assembleRelease
```

Use `./gradlew`, not a system Gradle 9.x, which can't load AGP 8.

Debug builds add an adb hook for protocol experiments (see
`ble/DebugReceiver.kt`); release builds don't register it.

## Not done yet

Remote control from outside the house (e.g. via Telegram) needs a device at home
in Bluetooth range of the heaters to act as a bridge.
