package work.stdpi.smolkey

import org.junit.Assert.assertEquals
import org.junit.Test
import work.stdpi.ensemble.MidiMessage
import work.stdpi.ensemble.MidiSequence
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class MidiSequenceTest {
    @Test
    fun mergesTracksWithTempoChangesAndRunningStatus() {
        val sequence = midiFile(
            1, 480,
            track(0x83, 0x60, 0xff, 0x51, 3, 0x03, 0xd0, 0x90, 0, 0xff, 0x2f, 0),
            track(0, 0xc0, 7, 0, 0x90, 60, 100, 0x83, 0x60, 64, 90,
                0x83, 0x60, 60, 0, 0, 64, 0, 0, 0xff, 0x2f, 0),
        )

        assertEquals(listOf(0L, 0L, 500_000_000L, 750_000_000L, 750_000_000L),
            sequence.events.map { it.nanoseconds })
        assertEquals(MidiMessage.ProgramChange(1, 7), sequence.events.first().message)
        assertEquals(MidiMessage.Note(1, 64, 90, true), sequence.events[2].message)
        assertEquals(MidiMessage.Note(1, 64, 0, false), sequence.events.last().message)
        assertEquals(750_000_000L, sequence.durationNanoseconds)
    }

    @Test
    fun usesSmpteTimingAndKeepsTrailingRest() {
        val sequence = midiFile(
            0, 0xe728,
            track(0, 0x90, 60, 100, 0x87, 0x68, 0x80, 60, 0, 100, 0xff, 0x2f, 0),
        )
        assertEquals(1_000_000_000L, sequence.events.last().nanoseconds)
        assertEquals(1_100_000_000L, sequence.durationNanoseconds)
    }

    @Test(expected = IOException::class)
    fun rejectsRunningStatusAfterMetaEvent() {
        midiFile(0, 480, track(0, 0x90, 60, 100, 0, 0xff, 1, 0, 0, 60, 0, 0, 0xff, 0x2f, 0))
    }

    private fun track(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun midiFile(format: Int, division: Int, vararg tracks: ByteArray): MidiSequence {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("MThd")
            out.writeInt(6)
            out.writeShort(format)
            out.writeShort(tracks.size)
            out.writeShort(division)
            for (track in tracks) {
                out.writeBytes("MTrk")
                out.writeInt(track.size)
                out.write(track)
            }
        }
        return MidiSequence.read(bytes.toByteArray().inputStream())
    }
}
