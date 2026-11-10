package work.stdpi.ensemble

/** MIDI 1.0 channel messages. Channels are numbered 1 through 16. */
sealed interface MidiMessage {
    val channel: Int

    data class Note(override val channel: Int, val key: Int, val velocity: Int, val down: Boolean) : MidiMessage
    data class ControlChange(override val channel: Int, val controller: Int, val value: Int) : MidiMessage
    data class ProgramChange(override val channel: Int, val program: Int) : MidiMessage
    data class PitchBend(override val channel: Int, val value: Int) : MidiMessage
    data class PolyPressure(override val channel: Int, val key: Int, val pressure: Int) : MidiMessage
    data class ChannelPressure(override val channel: Int, val pressure: Int) : MidiMessage

    companion object {
        internal fun decode(status: Int, first: Int, second: Int): MidiMessage {
            val channel = (status and 0x0f) + 1
            return when (status and 0xf0) {
                0x80 -> Note(channel, first, second, false)
                0x90 -> Note(channel, first, second, second != 0)
                0xa0 -> PolyPressure(channel, first, second)
                0xb0 -> ControlChange(channel, first, second)
                0xc0 -> ProgramChange(channel, first)
                0xd0 -> ChannelPressure(channel, first)
                0xe0 -> PitchBend(channel, first or (second shl 7))
                else -> error("Expected MIDI channel status")
            }
        }
    }
}

/** Frames a byte stream or BLE-MIDI connection. Use a separate decoder per connection. */
class MidiDecoder(private val receive: (MidiMessage) -> Unit) {
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

    fun bytes(bytes: ByteArray) {
        bytes.forEach { accept(it.toInt() and 0xff) }
    }

    fun bluetooth(packet: ByteArray) {
        if (packet.size < 2 || (packet[0].toInt() and 0x80) == 0) return
        runningStatus = 0
        remaining = 0
        var afterTimestamp = false
        for (index in 1 until packet.size) {
            val byte = packet[index].toInt() and 0xff
            if (byte >= 0x80 && !afterTimestamp) {
                afterTimestamp = true
            } else {
                afterTimestamp = false
                accept(byte)
            }
        }
    }

    private fun accept(byte: Int) {
        if (byte >= 0xf8) return
        if (byte >= 0x80) {
            status = byte
            first = 0
            remaining = dataLength(byte)
            inSysEx = byte == 0xf0
            runningStatus = if (byte < 0xf0) byte else 0
            return
        }
        if (inSysEx) return
        if (remaining == 0) {
            if (runningStatus == 0) return
            status = runningStatus
            remaining = dataLength(status)
        }
        if (remaining-- == 2) {
            first = byte
        } else if (status < 0xf0) {
            val oneByte = dataLength(status) == 1
            receive(MidiMessage.decode(status, if (oneByte) byte else first, if (oneByte) 0 else byte))
        }
    }

    companion object {
        internal fun dataLength(status: Int): Int = when (status) {
            in 0xc0..0xdf, 0xf1, 0xf3 -> 1
            in 0x80..0xef, 0xf2 -> 2
            else -> 0
        }
    }
}
