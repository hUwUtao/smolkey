package work.stdpi.smolkey

/** Produces MIDI key events from pointer ownership and triggers local haptics. */
internal class PianoController(
    private val channel: () -> Int,
    private val feedback: (Int) -> Unit,
    private val eventBus: MidiEventBus,
) {
    private val owners = NoteOwners(::dispatch)

    fun move(pointer: Long, note: Int?) = owners.move(pointer, note)

    fun releaseAll() = owners.releaseAll()

    private fun dispatch(note: Int, down: Boolean) {
        if (down) feedback(note)
        eventBus.dispatch(InstrumentEvent.Note(note, down, channel()))
    }
}
