package work.stdpi.smolkey

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import work.stdpi.ensemble.MidiDecoder
import work.stdpi.ensemble.MidiMessage

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

/** Projects shared MIDI messages into the keyboard monitor. */
internal class MidiReadbackDecoder(receive: (MidiReadback) -> Unit) {
    private val decoder = MidiDecoder { message ->
        when (message) {
            is MidiMessage.Note -> receive(
                InstrumentEvent.Note(message.key, message.down, message.channel, if (message.down) message.velocity else 0),
            )
            is MidiMessage.ControlChange -> when (message.controller) {
                64 -> receive(InstrumentEvent.Pedal(message.channel, message.value))
                121 -> receive(InstrumentEvent.Pedal(message.channel, 0))
                120, 123 -> receive(MidiReadback.ReleaseChannel(message.channel))
            }
            else -> Unit
        }
    }

    fun reset() = decoder.reset()
    fun usb(bytes: ByteArray) = decoder.bytes(bytes)
    fun ble(packet: ByteArray) = decoder.bluetooth(packet)
}
