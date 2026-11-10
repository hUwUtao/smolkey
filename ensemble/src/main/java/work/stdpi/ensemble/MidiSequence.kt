package work.stdpi.ensemble

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections

data class TimedMidiMessage(val nanoseconds: Long, val message: MidiMessage)

/** A merged, tempo-resolved Standard MIDI File timeline. */
class MidiSequence private constructor(events: List<TimedMidiMessage>, val durationNanoseconds: Long) {
    val events: List<TimedMidiMessage> = Collections.unmodifiableList(events)

    companion object {
        /** Reads SMF format 0 or 1 from the current position. The caller owns the stream. */
        @Throws(IOException::class)
        fun read(input: InputStream): MidiSequence {
            val file = DataInputStream(input)
            file.expect(0x4d546864, "MThd")
            val headerSize = file.readInt()
            midiCheck(headerSize in 6..65536, "Invalid MIDI header size")
            val format = file.readUnsignedShort()
            val trackCount = file.readUnsignedShort()
            val division = file.readUnsignedShort()
            midiCheck(format in 0..1 && trackCount > 0 && (format != 0 || trackCount == 1), "Unsupported MIDI file format")
            file.skipMidiBytes(headerSize - 6)

            val events = ArrayList<TickEvent>()
            var duration = 0L
            var bytesLeft = 32 * 1024 * 1024
            repeat(trackCount) {
                file.expect(0x4d54726b, "MTrk")
                val size = file.readInt()
                midiCheck(size in 0..bytesLeft, "MIDI tracks exceed the size limit")
                bytesLeft -= size
                val data = ByteArray(size)
                file.readFully(data)
                duration = maxOf(duration, readTrack(data, events))
            }
            events.sortBy { it.tick }
            return try {
                val clock = MidiClock(division)
                val timed = ArrayList<TimedMidiMessage>()
                for (event in events) {
                    clock.advance(event.tick)
                    val message = event.message
                    if (message != null) timed += TimedMidiMessage(clock.nanoseconds, message)
                    else clock.setTempo(event.tempo)
                }
                clock.advance(duration)
                MidiSequence(timed, clock.nanoseconds)
            } catch (overflow: ArithmeticException) {
                throw IOException("MIDI timeline exceeds the supported duration", overflow)
            }
        }
    }
}

private data class TickEvent(val tick: Long, val message: MidiMessage? = null, val tempo: Int = 0)

private fun readTrack(data: ByteArray, events: MutableList<TickEvent>): Long {
    val track = DataInputStream(ByteArrayInputStream(data))
    var tick = 0L
    var running = 0
    while (track.available() > 0) {
        tick += track.variableLength()
        val byte = track.readUnsignedByte()
        when (byte) {
            0xff -> {
                running = 0
                val type = track.readUnsignedByte()
                val size = track.variableLength()
                when (type) {
                    0x2f -> {
                        midiCheck(size == 0 && track.available() == 0, "Invalid end-of-track event")
                        return tick
                    }
                    0x51 -> {
                        midiCheck(size == 3, "Invalid MIDI tempo event")
                        val tempo = (track.readUnsignedShort() shl 8) or track.readUnsignedByte()
                        midiCheck(tempo > 0, "Invalid MIDI tempo")
                        events += TickEvent(tick, tempo = tempo)
                    }
                    else -> track.skipMidiBytes(size)
                }
            }
            0xf0, 0xf7 -> {
                running = 0
                track.skipMidiBytes(track.variableLength())
            }
            else -> {
                val first = if (byte >= 0x80) {
                    midiCheck(byte <= 0xef, "Unsupported track event")
                    running = byte
                    track.dataByte()
                } else {
                    midiCheck(running != 0, "MIDI data without running status")
                    byte
                }
                val second = if (MidiDecoder.dataLength(running) == 2) track.dataByte() else 0
                events += TickEvent(tick, MidiMessage.decode(running, first, second))
            }
        }
        midiCheck(events.size <= 1_000_000, "MIDI file exceeds the event limit")
    }
    throw IOException("Missing MIDI end-of-track event")
}

private class MidiClock(division: Int) {
    private val ppqn = division and 0x8000 == 0
    private val divisor: Long
    private var numerator = 500_000_000L
    private var remainder = 0L
    private var tick = 0L
    var nanoseconds = 0L
        private set

    init {
        divisor = if (ppqn) {
            division.toLong()
        } else {
            val fps = -(division shr 8).toByte().toInt()
            midiCheck(fps in setOf(24, 25, 29, 30), "Invalid MIDI SMPTE frame rate")
            numerator = if (fps == 29) 1_001_000_000_000L else 1_000_000_000L
            (if (fps == 29) 30_000L else fps.toLong()) * (division and 0xff)
        }
        midiCheck(divisor > 0, "Invalid MIDI time division")
    }

    fun advance(next: Long) {
        val delta = next - tick
        val units = Math.addExact(Math.multiplyExact(delta % divisor, numerator), remainder)
        val whole = Math.multiplyExact(delta / divisor, numerator)
        nanoseconds = Math.addExact(nanoseconds, Math.addExact(whole, units / divisor))
        remainder = units % divisor
        tick = next
    }

    fun setTempo(microseconds: Int) {
        if (ppqn) numerator = microseconds * 1000L
    }
}

private fun DataInputStream.expect(magic: Int, name: String) = midiCheck(readInt() == magic, "Expected $name")

private fun DataInputStream.variableLength(): Int {
    var value = 0
    repeat(4) {
        val byte = readUnsignedByte()
        value = (value shl 7) or (byte and 0x7f)
        if (byte and 0x80 == 0) return value
    }
    throw IOException("Invalid MIDI variable-length value")
}

private fun DataInputStream.dataByte(): Int = readUnsignedByte().also {
    midiCheck(it < 0x80, "Invalid MIDI data byte")
}

private fun midiCheck(valid: Boolean, message: String) {
    if (!valid) throw IOException(message)
}

private fun DataInputStream.skipMidiBytes(size: Int) {
    var left = size
    while (left > 0) {
        val skipped = skipBytes(left)
        if (skipped == 0) {
            readUnsignedByte()
            left--
        } else {
            left -= skipped
        }
    }
}
