package work.stdpi.ensemble

import java.util.concurrent.locks.LockSupport

/** Plays a MIDI timeline on a dedicated engine using its installed sample bank. */
class MidiPlayer(private val engine: Ensemble, patches: Map<MidiPatch, Int> = emptyMap()) {
    private val synth = MidiSynth(engine, patches)
    private var worker: Thread? = null
    @Volatile private var stopping = false

    /** Blocks the calling worker until the timeline ends or stop is requested. */
    @Throws(InterruptedException::class)
    fun play(sequence: MidiSequence) {
        synchronized(this) {
            check(worker == null) { "MIDI playback is already running" }
            check(engine.banks.current != null) { "Load a sample bank before playback" }
            check(engine.output is OutputState.Ready) { "Wait for the audio output to open before playback" }
            stopping = false
            worker = Thread.currentThread()
        }

        var completed = false
        try {
            synth.reset()
            val start = System.nanoTime()
            for (event in sequence.events) {
                if (!waitUntil(start, event.nanoseconds)) return
                synth.send(event.message)
            }
            completed = waitUntil(start, sequence.durationNanoseconds)
        } finally {
            try {
                if (completed) synth.release() else synth.reset()
            } finally {
                synchronized(this) { worker = null }
            }
        }
    }

    @Synchronized
    fun stop() {
        stopping = true
        worker?.let(LockSupport::unpark)
    }

    private fun waitUntil(start: Long, target: Long): Boolean {
        while (!stopping && engine.isOpen) {
            if (Thread.interrupted()) throw InterruptedException()
            val remaining = target - (System.nanoTime() - start)
            if (remaining <= 0) return true
            LockSupport.parkNanos(minOf(remaining, 100_000_000L))
        }
        return false
    }
}
