package work.stdpi.smolkey

import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicLong

/** One native sample library and output stream. Loaded stems live until [close]. */
internal class NativeSampler private constructor(handle: Long) : AutoCloseable {
    private val handle = AtomicLong(handle)

    data class Metadata(val name: String, val tags: Map<String, String> = emptyMap())
    class Stem internal constructor(internal val owner: Long, internal val id: Int, val metadata: Metadata)
    class Voice internal constructor(internal val owner: Long, internal val id: Long)

    /** Decodes mono or stereo Ogg Opus. Call on a worker. Null means invalid data, full bank, or closed. */
    fun loadStem(opus: ByteArray, metadata: Metadata): Stem? {
        val owner = handle.get()
        if (owner == 0L) return null
        val id = EnsembleBindings.loadStem(owner, opus)
        return if (id >= 0 && handle.get() == owner) Stem(owner, id, metadata) else null
    }

    /** Starts an independent voice. The same stem may be fired repeatedly and overlap. */
    fun fire(stem: Stem, gain: Float = 1f, rate: Float = 1f, loop: Boolean = false): Voice? {
        val owner = handle.get()
        if (owner == 0L || stem.owner != owner) return null
        return EnsembleBindings.fire(owner, stem.id, gain, rate, loop)
            .takeIf { it != 0L }?.let { Voice(owner, it) }
    }

    /** Passes MIDI velocity to the engine. An existing loop can be updated in place. */
    fun percussion(stem: Stem, velocity: Int, loop: Boolean = false, current: Voice? = null): Voice? {
        val owner = handle.get()
        if (owner == 0L || stem.owner != owner) return null
        val previous = current?.takeIf { it.owner == owner }?.id ?: 0L
        val id = EnsembleBindings.percussion(owner, stem.id, velocity.coerceIn(0, 127), loop, previous)
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

    fun setReverb(percent: Int) {
        val owner = handle.get()
        if (owner != 0L) EnsembleBindings.setReverb(owner, percent)
    }

    fun setActive(active: Boolean) {
        val owner = handle.get()
        if (owner != 0L) EnsembleBindings.setActive(owner, active)
    }

    fun note(channel: Int, note: Int, velocity: Int) {
        val owner = handle.get()
        if (owner != 0L) EnsembleBindings.note(owner, channel, note, velocity)
    }

    fun loadBundledBank(assets: AssetManager): Map<PercussionSound, Stem> {
        val owner = handle.get()
        if (owner == 0L) return emptyMap()
        val slots = EnsembleBindings.loadBank(owner, assets) ?: return emptyMap()
        return PercussionSound.entries.zip(slots.asList()).mapNotNull { (sound, id) ->
            if (id >= 0) sound to Stem(owner, id, Metadata(sound.resourceName)) else null
        }.toMap()
    }

    override fun close() {
        val owner = handle.getAndSet(0)
        if (owner != 0L) EnsembleBindings.close(owner)
    }

    companion object {
        fun open(context: Context): NativeSampler? =
            EnsembleBindings.open(context.applicationContext).takeIf { it != 0L }?.let(::NativeSampler)
    }
}

@Keep
private object EnsembleBindings {
    init {
        System.loadLibrary("ensemble")
    }

    external fun open(context: Context): Long
    external fun loadBank(handle: Long, assets: AssetManager): IntArray?
    external fun loadStem(handle: Long, bytes: ByteArray): Int
    external fun fire(handle: Long, stem: Int, gain: Float, rate: Float, looping: Boolean): Long
    external fun percussion(handle: Long, stem: Int, velocity: Int, loop: Boolean, current: Long): Long
    external fun note(handle: Long, channel: Int, note: Int, velocity: Int)
    external fun gain(handle: Long, voice: Long, gain: Float): Boolean
    external fun stop(handle: Long, voice: Long)
    external fun release(handle: Long)
    external fun setReverb(handle: Long, percent: Int)
    external fun setActive(handle: Long, active: Boolean)
    external fun close(handle: Long)
}
