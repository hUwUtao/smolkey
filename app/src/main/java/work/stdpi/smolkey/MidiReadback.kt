package work.stdpi.smolkey

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface MidiReadback {
    data class ReleaseChannel(val channel: Int) : MidiReadback
    data object Disconnected : MidiReadback
}

internal enum class MidiNoteSource { LOCAL, HOST }

internal data class MidiKey(val channel: Int, val note: Int)

/** Main-thread consumer. Source and channel ownership are projected onto visible pitches. */
internal class MidiKeyConsumer {
    private val local = mutableMapOf<MidiKey, Int>()
    private val host = mutableMapOf<MidiKey, Int>()
    private val notes = MutableStateFlow<Set<Int>>(emptySet())
    val visibleNotes = notes.asStateFlow()

    fun note(source: MidiNoteSource, event: InstrumentEvent.Note): Boolean {
        val held = if (source == MidiNoteSource.LOCAL) local else host
        val key = MidiKey(event.channel, event.note)
        val count = (held[key] ?: 0) + if (event.down) 1 else -1
        if (count > 0) held[key] = count else held.remove(key)
        publish()
        return count > 0
    }

    fun clearHost(channel: Int?) {
        if (channel == null) host.clear() else host.keys.removeAll { it.channel == channel }
        publish()
    }

    private fun publish() {
        notes.value = buildSet {
            local.keys.forEach { add(it.note) }
            host.keys.forEach { add(it.note) }
        }
    }
}

/** Framer for one MIDI 1.0 byte stream or BLE connection. */
internal class MidiReadbackDecoder(private val receive: (MidiReadback) -> Unit) {
    private var runningStatus = 0
    private var status = 0
    private var remaining = 0
    private var first = 0
    private var inSysEx = false

    fun reset() {
        runningStatus = 0
        remaining = 0
        inSysEx = false
    }

    fun usb(bytes: ByteArray) {
        bytes.forEach { accept(it.toInt() and 0xFF, ble = false) }
    }

    fun ble(packet: ByteArray) {
        if (packet.size < 2 || (packet[0].toInt() and 0x80) == 0) return
        // BLE running status is packet-local. SysEx can span packets.
        runningStatus = 0
        remaining = 0
        var afterTimestamp = false
        for (index in 1 until packet.size) {
            val byte = packet[index].toInt() and 0xFF
            if (byte >= 0x80 && !afterTimestamp) {
                afterTimestamp = true
            } else {
                afterTimestamp = false
                accept(byte, ble = true)
            }
        }
    }

    private fun accept(byte: Int, ble: Boolean) {
        if (byte >= 0xF8) return
        if (byte >= 0x80) {
            status = byte
            remaining = messageLength(byte)
            inSysEx = byte == 0xF0
            if (byte < 0xF0) runningStatus = byte
            else if (!ble || byte == 0xF0 || byte == 0xF7) runningStatus = 0
            return
        }
        if (inSysEx) return
        if (remaining == 0) {
            if (runningStatus == 0) return
            status = runningStatus
            remaining = messageLength(status)
        }
        if (remaining-- == 2) {
            first = byte
        } else {
            val channel = (status and 0x0F) + 1
            when (status and 0xF0) {
                0x80 -> receive(InstrumentEvent.Note(first, false, channel, 0))
                0x90 -> receive(InstrumentEvent.Note(first, byte != 0, channel, byte))
                0xB0 -> if (first == 120 || first == 123) receive(MidiReadback.ReleaseChannel(channel))
            }
        }
    }

    private fun messageLength(status: Int): Int = when (status) {
        in 0xC0..0xDF, 0xF1, 0xF3 -> 1
        in 0x80..0xEF, 0xF2 -> 2
        else -> 0
    }
}
