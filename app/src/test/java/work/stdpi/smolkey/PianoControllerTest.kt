package work.stdpi.smolkey

import org.junit.Assert.assertEquals
import org.junit.Test

class PianoControllerTest {
    @Test
    fun sharedPointerOwnershipDispatchesOnlyFirstPressAndFinalRelease() {
        val audio = FakeAudio()
        val midi = FakeMidi()
        val feedback = mutableListOf<Int>()
        val bus = MidiEventBus(midi, MidiSamplerConsumer(audio))
        val controller = PianoController(
            channel = { 3 },
            feedback = feedback::add,
            eventBus = bus,
        )

        controller.move(1, 60)
        controller.move(2, 60)
        controller.move(1, null)
        assertEquals(setOf(60), bus.visibleNotes.value)
        controller.move(2, null)

        assertEquals(listOf(60), feedback)
        assertEquals(listOf(60 to true, 60 to false), audio.events)
        assertEquals(listOf(Triple(60, true, 3), Triple(60, false, 3)), midi.events)
        assertEquals(emptySet<Int>(), bus.visibleNotes.value)
    }

    @Test
    fun hostResetAndReleasePreserveOtherChannelsAndLocalHolds() {
        val midi = FakeMidi()
        val sampled = mutableListOf<InstrumentEvent>()
        val bus = MidiEventBus(midi, sampled::add)
        val local = InstrumentEvent.Note(60, true, 1)
        bus.dispatch(local)
        bus.receive(local)
        bus.receive(InstrumentEvent.Note(62, true, 1))
        bus.receive(InstrumentEvent.Note(62, true, 2))
        bus.receive(MidiReadback.ReleaseChannel(1))
        assertEquals(setOf(60, 62), bus.visibleNotes.value)
        bus.receive(MidiReadback.Disconnected)
        assertEquals(setOf(60), bus.visibleNotes.value)
        assertEquals(listOf(local), sampled)
        assertEquals(listOf(Triple(60, true, 1)), midi.events)
    }

    @Test
    fun hostMonitoringReleasesLocalVoiceAndWaitsForFinalHostRelease() {
        val sampled = mutableListOf<InstrumentEvent>()
        val bus = MidiEventBus(FakeMidi(), sampled::add)
        val on = InstrumentEvent.Note(60, true, 1)
        val off = on.copy(down = false, velocity = 0)
        bus.dispatch(on)
        bus.monitorKeys = false
        bus.monitorInput = true
        assertEquals(listOf(on, off), sampled)
        bus.receive(on)
        bus.receive(on)
        bus.dispatch(off)
        bus.receive(off)
        assertEquals(listOf(on, off, on, on), sampled)
        assertEquals(setOf(60), bus.visibleNotes.value)
        bus.receive(off)
        assertEquals(listOf(on, off, on, on, off), sampled)
        bus.soundEnabled = false
        bus.receive(on)
        assertEquals(5, sampled.size)
        assertEquals(setOf(60), bus.visibleNotes.value)
    }

    @Test
    fun bothMonitorsShareMatchingVoiceUntilBothSourcesRelease() {
        val sampled = mutableListOf<InstrumentEvent>()
        val midi = FakeMidi()
        val bus = MidiEventBus(midi, sampled::add, monitorInput = true)
        val local = InstrumentEvent.Note(60, true, 1)
        val hostOnly = InstrumentEvent.Note(64, true, 1)
        bus.dispatch(local)
        bus.receive(local)
        bus.receive(hostOnly)
        assertEquals(listOf(local, hostOnly), sampled)
        assertEquals(setOf(60, 64), bus.visibleNotes.value)
        bus.receive(MidiReadback.Disconnected)
        assertEquals(listOf(local, hostOnly, hostOnly.copy(down = false, velocity = 0)), sampled)
        assertEquals(setOf(60), bus.visibleNotes.value)
        bus.dispatch(local.copy(down = false, velocity = 0))
        assertEquals(local.copy(down = false, velocity = 0), sampled.last())
        assertEquals(listOf(Triple(60, true, 1), Triple(60, false, 1)), midi.events)
    }

    private class FakeAudio : AudioPort {
        val events = mutableListOf<Pair<Int, Boolean>>()
        override fun note(note: Int, down: Boolean, channel: Int, velocity: Int) { events += note to down }
        override fun hit(sound: PercussionSound, velocity: Int) = Unit
        override fun roll(velocity: Int?) = Unit
        override fun release() = Unit
        override fun close() = Unit
    }

    private class FakeMidi : MidiPort {
        val events = mutableListOf<Triple<Int, Boolean, Int>>()
        override fun select(output: Output) = Unit
        override fun note(note: Int, down: Boolean, channel: Int, velocity: Int) {
            events += Triple(note, down, channel)
        }
        override fun send(message: ByteArray) = Unit
        override fun startBluetooth() = Unit
        override fun retry(output: Output) = Unit
        override fun stop() = Unit
    }
}
