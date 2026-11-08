package work.stdpi.smolkey

import androidx.annotation.StringRes

/** Available destinations for outgoing MIDI events. */
internal enum class Output { OFF, USB, BLUETOOTH }
internal enum class LaunchpadSurfaceKind { KEY, DRUM }
internal enum class PercussionSound(val note: Int, val resourceName: String, @StringRes val labelRes: Int) {
    KICK(36, "kick", R.string.kick),
    SNARE(38, "snare", R.string.snare),
    CLOSED_HI_HAT(42, "hi_hat", R.string.closed_hi_hat),
    LOW_TOM(45, "low_tom", R.string.low_tom),
    TAMBOURINE(54, "tambourine", R.string.tambourine),
    TAMBOURINE_SHAKE(54, "tambourine_shake", R.string.tambourine_shake),
}

/** Semantic performance events emitted by touch and motion producers. */
internal sealed interface InstrumentEvent {
    data class Note(val note: Int, val down: Boolean, val channel: Int, val velocity: Int = 96) : InstrumentEvent, MidiReadback
    data class Hit(val sound: PercussionSound, val channel: Int, val velocity: Int) : InstrumentEvent
    data class Roll(val velocity: Int?) : InstrumentEvent
}

internal fun interface InstrumentEventConsumer {
    fun consume(event: InstrumentEvent)
}

/** Byte-level destinations selected by the MIDI output router. */
internal fun interface MidiMessageConsumer {
    fun send(message: ByteArray)
}

internal object MidiNullConsumer : MidiMessageConsumer {
    override fun send(message: ByteArray) = Unit
}

internal class MidiUsbConsumer(private val sendToUsb: (ByteArray) -> Unit) : MidiMessageConsumer {
    override fun send(message: ByteArray) = sendToUsb(message)
}

internal class MidiBluetoothConsumer(private val enqueueForBluetooth: (ByteArray) -> Unit) : MidiMessageConsumer {
    override fun send(message: ByteArray) = enqueueForBluetooth(message)
}

/** Fans one producer event to the selected MIDI route and optional local instrument renderer. */
internal class MidiSamplerConsumer(private val instrument: AudioPort) : InstrumentEventConsumer {
    override fun consume(event: InstrumentEvent) = instrument.consume(event)
}

internal class MidiEventBus(
    private val midi: MidiPort,
    private val sampler: InstrumentEventConsumer,
    private val keys: MidiKeyConsumer = MidiKeyConsumer(),
    soundEnabled: Boolean = true,
    monitorKeys: Boolean = true,
    monitorInput: Boolean = false,
) {
    val visibleNotes = keys.visibleNotes
    private val localVoices = mutableSetOf<MidiKey>()
    private val hostVoices = mutableSetOf<MidiKey>()

    var soundEnabled = soundEnabled
        set(value) {
            if (!value) {
                releaseKeys(localVoices, hostVoices)
                releaseKeys(hostVoices, localVoices)
            }
            field = value
        }

    var monitorKeys = monitorKeys
        set(value) {
            if (!value) releaseKeys(localVoices, hostVoices)
            field = value
        }

    var monitorInput = monitorInput
        set(value) {
            if (!value) releaseKeys(hostVoices, localVoices)
            field = value
        }

    fun dispatch(event: InstrumentEvent) {
        if (event is InstrumentEvent.Note) {
            keys.note(MidiNoteSource.LOCAL, event)
            if (monitorKeys) play(event, localVoices, hostVoices)
        } else if (soundEnabled) {
            sampler.consume(event)
        }
        midi.consume(event)
    }

    fun receive(event: MidiReadback) {
        when (event) {
            is InstrumentEvent.Note -> {
                val held = keys.note(MidiNoteSource.HOST, event)
                if (monitorInput && (event.down || !held)) play(event, hostVoices, localVoices)
            }
            is MidiReadback.ReleaseChannel -> clearHost(event.channel)
            MidiReadback.Disconnected -> clearHost()
        }
    }

    fun clearHost(channel: Int? = null) {
        keys.clearHost(channel)
        releaseKeys(hostVoices, localVoices, channel)
    }

    private fun play(event: InstrumentEvent.Note, voices: MutableSet<MidiKey>, other: Set<MidiKey>) {
        val key = MidiKey(event.channel, event.note)
        if (event.down && soundEnabled) {
            voices += key
            if (key !in other) sampler.consume(event)
        } else if (!event.down) {
            releaseKey(key, voices, other)
        }
    }

    private fun releaseKey(key: MidiKey, voices: MutableSet<MidiKey>, other: Set<MidiKey>) {
        if (voices.remove(key) && key !in other) {
            sampler.consume(InstrumentEvent.Note(key.note, false, key.channel, 0))
        }
    }

    private fun releaseKeys(voices: MutableSet<MidiKey>, other: Set<MidiKey>, channel: Int? = null) {
        voices.filter { channel == null || it.channel == channel }.forEach { releaseKey(it, voices, other) }
    }
}

/** MIDI output contract consumed by the UI; Android transport details stay in MidiOutput. */
internal interface MidiPort : InstrumentEventConsumer {
    fun select(output: Output)
    fun note(note: Int, down: Boolean, channel: Int, velocity: Int = 96)
    fun send(message: ByteArray)
    fun startBluetooth()
    fun retry(output: Output)
    fun stop()

    override fun consume(event: InstrumentEvent) {
        when (event) {
            is InstrumentEvent.Note -> note(event.note, event.down, event.channel, event.velocity)
            is InstrumentEvent.Hit -> note(event.sound.note, true, event.channel, event.velocity)
            is InstrumentEvent.Roll -> Unit
        }
    }
}

/** Local audio renderer contract behind the MIDI sampler consumer. */
internal interface AudioPort : InstrumentEventConsumer {
    fun note(note: Int, down: Boolean, channel: Int = 1, velocity: Int = 96)
    fun hit(sound: PercussionSound, velocity: Int)
    fun roll(velocity: Int?)
    fun release()
    fun close()

    override fun consume(event: InstrumentEvent) {
        when (event) {
            is InstrumentEvent.Note -> note(event.note, event.down, event.channel, event.velocity)
            is InstrumentEvent.Hit -> hit(event.sound, event.velocity)
            is InstrumentEvent.Roll -> roll(event.velocity)
        }
    }
}
