package work.stdpi.smolkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiTest {
    @Test fun oneFingerCannotReleaseAnotherFingersSharedNote() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val owners = NoteOwners { note, down -> events += note to down }
        owners.move(1, 60); owners.move(2, 60); owners.move(1, null)
        assertEquals(listOf(60 to true), events)
        owners.move(2, null)
        assertEquals(listOf(60 to true, 60 to false), events)
    }

    @Test fun glissandoReleasesPreviousKeyAndPressesNext() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val owners = NoteOwners { note, down -> events += note to down }
        owners.move(7, 60); owners.move(7, 62)
        assertEquals(listOf(60 to true, 60 to false, 62 to true), events)
    }

    @Test fun releaseAllReleasesEachActiveNoteOnce() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val owners = NoteOwners { note, down -> events += note to down }
        owners.move(1, 60); owners.move(2, 64); owners.move(3, 60); owners.releaseAll()
        assertEquals(listOf(60 to true, 64 to true, 60 to false, 64 to false), events)
    }

    @Test fun bleMergesChordAndWraps13BitTimestamp() {
        val chord = listOf(channelMessage(0x90, 1, 60, 96), channelMessage(0x90, 1, 64, 96))
        val packets = bleMidiPackets(chord, 8193)
        assertEquals(1, packets.size)
        assertTrue(packets[0].contentEquals(byteArrayOf(0x80.toByte(), 0x81.toByte(), 0x90.toByte(), 60, 96, 0x81.toByte(), 0x90.toByte(), 64, 96)))
    }

    @Test fun bleSplitsMessagesIntoDefaultMtuSizedPackets() {
        val messages = (48..60).map { channelMessage(0x90, 1, it, 96) }
        val packets = bleMidiPackets(messages, 511)
        assertTrue(packets.all { it.size <= 20 })
        assertEquals(13, packets.sumOf { (it.size - 1) / 4 })
    }

    @Test fun usbFramesSplitAndBatchedNotesWithRunningStatusAndRealtime() {
        val events = mutableListOf<MidiReadback>()
        val decoder = MidiReadbackDecoder(events::add)
        decoder.usb(bytes(0x92, 60))
        assertTrue(events.isEmpty())
        decoder.usb(bytes(0xF8, 96, 64, 0, 0x82, 60, 42, 0xB2, 123, 0, 120, 0))
        assertEquals(listOf(
            InstrumentEvent.Note(60, true, 3, 96),
            InstrumentEvent.Note(64, false, 3, 0),
            InstrumentEvent.Note(60, false, 3, 0),
            MidiReadback.ReleaseChannel(3),
            MidiReadback.ReleaseChannel(3),
        ), events)
    }

    @Test fun bleFramesTimestampedNotesAndPacketLocalRunningStatus() {
        val events = mutableListOf<MidiReadback>()
        val decoder = MidiReadbackDecoder(events::add)
        decoder.ble(bytes(0x80, 0xFC, 0x90, 60, 96, 64, 98, 0xFD, 60, 0, 0xFE, 0xF8, 64, 0))
        decoder.ble(bytes(0x80, 0xFF, 60, 96))
        decoder.ble(bytes(0x81, 0x80, 0x91, 62, 90))
        assertEquals(listOf(
            InstrumentEvent.Note(60, true, 1, 96),
            InstrumentEvent.Note(64, true, 1, 98),
            InstrumentEvent.Note(60, false, 1, 0),
            InstrumentEvent.Note(64, false, 1, 0),
            InstrumentEvent.Note(62, true, 2, 90),
        ), events)
    }

    @Test fun preservesHalfPedalValuesAndControllerReset() {
        val events = mutableListOf<MidiReadback>()
        val decoder = MidiReadbackDecoder(events::add)
        decoder.usb(bytes(0xB2, 64, 43, 64, 105, 121, 0))
        assertEquals(listOf(
            InstrumentEvent.Pedal(3, 43),
            InstrumentEvent.Pedal(3, 105),
            InstrumentEvent.Pedal(3, 0),
        ), events)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
