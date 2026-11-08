# MIDI readback in Smolkey

Local keys are highlighted from the same app events that feed the outgoing MIDI route, before transport encoding. This works even if the output is Off. A host return is a separate stream. A DAW can send its own notes or route Smolkey's notes back using MIDI Thru.

| Stream | Highlights keys | Plays the sampler | Goes out over MIDI |
| --- | --- | --- | --- |
| Local keys | Yes | With Local key monitoring enabled | Yes, over the selected route |
| Host return | Yes | With MIDI input monitoring enabled | No |

**Local key monitoring** is on by default. Enable **MIDI input monitoring** to hear host notes alongside local keys. If both sources hold the same pitch and channel, they share the sampler voice until both release it. Disable local monitoring to hear keys only after the host returns them. **Phone sound** mutes the sampler. Highlights combine local and host ownership across channels and follow Note On and Note Off rather than the duration of a sampler voice.

## USB peripheral

Android names ports from the device's perspective. An app sends through `MidiInputPort` and receives by connecting a `MidiReceiver` to a `MidiOutputPort`. AOSP registers its USB MIDI gadget with one port in each direction. Opening the output port subscribes to bytes the host sends toward the phone. [Android MIDI API](https://developer.android.com/reference/android/media/midi/package-summary), [AOSP USB gadget registration](https://android.googlesource.com/platform/frameworks/base/+/HEAD/services/usb/java/com/android/server/usb/UsbAlsaManager.java#463)

A receive callback can contain multiple messages, part of a message, or interleaved real-time bytes. Keep parsing state per connection. Smolkey applies decoded events on the main thread in arrival order. [Android MIDI receive guide](https://developer.android.com/reference/android/media/midi/package-summary#receive-a-note), [AOSP MIDI output-port reader](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/midi/MidiOutputPort.java)

## Bluetooth peripheral

The host writes to the existing BLE-MIDI characteristic. Android delivers writes through `onCharacteristicWriteRequest`. BLE-MIDI adds a packet header and timestamp bytes, which must be distinguished from MIDI status bytes. BLE running status is packet-local. [Android GATT server callback](https://developer.android.com/reference/android/bluetooth/BluetoothGattServerCallback), [BLE-MIDI specification](https://midi.org/midi-over-bluetooth-low-energy-ble-midi), [AOSP BLE packet decoder](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-biometric-release/com/android/bluetoothmidiservice/BluetoothPacketDecoder.java)

## Note ownership

A Note On with velocity zero releases a note just like a Note Off. Smolkey counts matching host presses and releases per channel and pitch. All Notes Off and All Sound Off clear that channel's host holds. Disconnect clears the host's entire stream, preserving any locally held keys. [MIDI 1.0 Detailed Specification](https://midi.org/midi-1-0-detailed-specification)
