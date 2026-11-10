package work.stdpi.ensemble

/** Built-in processors available to an output chain. */
sealed class Fx(internal val id: Int) {
    data object Reverb : Fx(1) {
        /** Room send from 0 to 1. Defaults to 0. */
        val Amount = FxFloat<Reverb>(0)
    }

    /** Folds stereo to mono at this position when the output device is mono. */
    data object MonoFold : Fx(2)

    data object Limiter : Fx(3) {
        /** Release in milliseconds, from 5 to 2000. Defaults to 80. */
        val ReleaseMs = FxFloat<Limiter>(0)
    }
}

class FxFloat<E : Fx> internal constructor(internal val id: Int)

class FxInstance<E : Fx> internal constructor(private val handle: () -> Long, val slot: Int) {
    /** Publishes a value for the next audio block. Returns false for an invalid value or closed engine. */
    fun set(parameter: FxFloat<E>, value: Float): Boolean {
        val owner = handle()
        return owner != 0L && EnsembleBindings.setFxFloat(owner, slot, parameter.id, value)
    }
}

/** Append effects in processing order. Slots and parameter values persist until the engine closes. */
class FxRack internal constructor(private val handle: () -> Long) {
    /** Returns null when the rack is full or the engine is closed. */
    fun <E : Fx> load(effect: E): FxInstance<E>? {
        val owner = handle()
        if (owner == 0L) return null
        val slot = EnsembleBindings.loadFx(owner, effect.id)
        return if (slot >= 0 && handle() == owner) FxInstance(handle, slot) else null
    }
}
