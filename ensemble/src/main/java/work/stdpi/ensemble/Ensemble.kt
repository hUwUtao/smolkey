package work.stdpi.ensemble

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/** One native sample library and output stream. Standalone clips live until [close]. */
class Ensemble private constructor(handle: Long, context: Context, val apiVersion: Int) : AutoCloseable {
    private val handle = AtomicLong(handle)
    val banks = BankManager(context, this.handle::get)
    val fx = FxRack(this.handle::get)
    internal val isOpen: Boolean get() = handle.get() != 0L

    val output: OutputState
        get() {
            val format = EnsembleBindings.outputFormat(handle.get())
            return when (format) {
                0L -> OutputState.Starting
                -1L -> OutputState.Retrying
                -2L -> OutputState.Suspended
                -3L -> OutputState.Closed
                else -> OutputState.Ready(
                    (format ushr 32).toInt(),
                    (format ushr 16).toInt() and 0xffff,
                    PcmEncoding.entries[(format.toInt() and 0xffff) - 1],
                )
            }
        }

    data class Metadata(val name: String, val tags: Map<String, String> = emptyMap())
    class Clip internal constructor(internal val owner: Long, internal val id: Int, val metadata: Metadata)
    class Voice internal constructor(internal val owner: Long, internal val id: Long)

    /** Decodes mono or stereo Ogg Opus. Call on a worker. Null means invalid data, full bank, or closed. */
    fun loadClip(opus: ByteArray, metadata: Metadata): Clip? {
        val owner = handle.get()
        if (owner == 0L) return null
        val id = EnsembleBindings.loadClip(owner, opus)
        return if (id >= 0 && handle.get() == owner) Clip(owner, id, metadata) else null
    }

    /** Starts an independent voice. The same clip may be fired repeatedly and overlap. */
    fun fire(clip: Clip, gain: Float = 1f, rate: Float = 1f, loop: Boolean = false): Voice? {
        val owner = handle.get()
        if (owner == 0L || clip.owner != owner) return null
        return EnsembleBindings.fire(owner, clip.id, gain, rate, loop)
            .takeIf { it != 0L }?.let { Voice(owner, it) }
    }

    /** Passes MIDI velocity to the engine. An existing loop can be updated in place. */
    fun pad(program: Int, note: Int, velocity: Int, loop: Boolean = false, current: Voice? = null): Voice? {
        val owner = handle.get()
        if (owner == 0L) return null
        val previous = current?.takeIf { it.owner == owner }?.id ?: 0L
        val id = EnsembleBindings.pad(owner, program, note, velocity.coerceIn(0, 127), loop, previous)
        return when (id) {
            0L -> null
            previous -> current
            else -> Voice(owner, id)
        }
    }

    fun setGain(voice: Voice, gain: Float): Boolean {
        val owner = handle.get()
        return owner == voice.owner && EnsembleBindings.gain(owner, voice.id, gain)
    }

    fun stop(voice: Voice) {
        val owner = handle.get()
        if (owner == voice.owner) EnsembleBindings.stop(owner, voice.id)
    }

    fun release() {
        val owner = handle.get()
        if (owner != 0L) EnsembleBindings.release(owner)
    }

    fun setActive(active: Boolean) {
        val owner = handle.get()
        if (owner != 0L) EnsembleBindings.setActive(owner, active)
    }

    fun note(channel: Int, note: Int, velocity: Int, program: Int = 0) {
        val owner = handle.get()
        if (owner != 0L) EnsembleBindings.note(owner, program, channel, note, velocity)
    }

    /** Sends a CC64 pedal position to a MIDI channel, numbered 1 through 16. */
    fun setPedal(channel: Int, value: Int) {
        require(channel in 1..16 && value in 0..127)
        val owner = handle.get()
        if (owner != 0L) EnsembleBindings.setPedal(owner, channel, value)
    }

    internal fun channelMix(channel: Int, gain: Float, pan: Float, semitones: Float) {
        val owner = handle.get()
        if (owner != 0L) EnsembleBindings.channelMix(owner, channel, gain, pan, semitones)
    }

    internal fun silenceChannel(channel: Int) {
        val owner = handle.get()
        if (owner != 0L) EnsembleBindings.silenceChannel(owner, channel)
    }

    override fun close() {
        val owner = handle.getAndSet(0)
        if (owner != 0L) EnsembleBindings.close(owner)
    }

    companion object {
        const val API_VERSION = 4

        fun open(context: Context, versions: IntRange = API_VERSION..API_VERSION): OpenResult {
            require(!versions.isEmpty() && versions.first > 0)
            return try {
                val version = EnsembleBindings.negotiate(versions.first, versions.last)
                if (version !in versions) return OpenResult.IncompatibleVersion(versions, -version)
                val app = context.applicationContext
                val handle = EnsembleBindings.open(app)
                if (handle == 0L) OpenResult.Unavailable else OpenResult.Opened(Ensemble(handle, app, version))
            } catch (error: LinkageError) {
                OpenResult.NativeLibraryError(error)
            }
        }
    }
}

sealed interface OpenResult {
    data class Opened(val engine: Ensemble) : OpenResult
    data class IncompatibleVersion(val requested: IntRange, val nativeVersion: Int) : OpenResult
    data class NativeLibraryError(val cause: LinkageError) : OpenResult
    data object Unavailable : OpenResult
}

enum class PcmEncoding { FLOAT32, SIGNED16, SIGNED32, UNSIGNED16 }

sealed interface OutputState {
    data object Starting : OutputState
    data object Retrying : OutputState
    data object Suspended : OutputState
    data object Closed : OutputState
    data class Ready(val sampleRate: Int, val channels: Int, val encoding: PcmEncoding) : OutputState
}

internal object EnsembleBindings {
    init {
        System.loadLibrary("ensemble")
    }

    external fun negotiate(minimum: Int, maximum: Int): Int
    external fun outputFormat(handle: Long): Long
    external fun open(context: Context): Long
    external fun inspectBank(fd: Int, offset: Long, length: Long): IntArray?
    external fun loadBank(handle: Long, fd: Int, offset: Long, length: Long, required: IntArray): IntArray?
    external fun loadClip(handle: Long, bytes: ByteArray): Int
    external fun fire(handle: Long, clip: Int, gain: Float, rate: Float, looping: Boolean): Long
    external fun pad(handle: Long, program: Int, note: Int, velocity: Int, loop: Boolean, current: Long): Long
    external fun note(handle: Long, program: Int, channel: Int, note: Int, velocity: Int)
    external fun setPedal(handle: Long, channel: Int, value: Int)
    external fun channelMix(handle: Long, channel: Int, gain: Float, pan: Float, semitones: Float)
    external fun silenceChannel(handle: Long, channel: Int)
    external fun gain(handle: Long, voice: Long, gain: Float): Boolean
    external fun stop(handle: Long, voice: Long)
    external fun release(handle: Long)
    external fun loadFx(handle: Long, kind: Int): Int
    external fun setFxFloat(handle: Long, slot: Int, parameter: Int, value: Float): Boolean
    external fun setActive(handle: Long, active: Boolean)
    external fun close(handle: Long)
}
