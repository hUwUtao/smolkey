package work.stdpi.smolkey

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread
import work.stdpi.ensemble.BankResult
import work.stdpi.ensemble.BankSource
import work.stdpi.ensemble.Ensemble
import work.stdpi.ensemble.Fx
import work.stdpi.ensemble.FxInstance
import work.stdpi.ensemble.OpenResult

private const val PERCUSSION_PROGRAM = 128
internal const val REVERB_PERCENT = "reverbPercent"

/** Adapts the app's local instrument events to the native sampler. Called from the UI thread. */
internal class InstrumentAudio(context: Context) : AudioPort {
    private val appContext = context.applicationContext
    private var sampler: Ensemble? = null
    private var reverb: FxInstance<Fx.Reverb>? = null
    private var rollVoice: Ensemble.Voice? = null
    private var closed = false

    fun start() {
        if (closed) return
        sampler?.let {
            it.setActive(true)
            return
        }
        val engine = when (val opened = Ensemble.open(appContext)) {
            is OpenResult.Opened -> opened.engine
            is OpenResult.NativeLibraryError -> {
                Log.e("Ensemble", "Could not load the native sampler", opened.cause)
                return
            }
            else -> {
                Log.w("Ensemble", "Could not open the sampler: $opened")
                return
            }
        }
        val room = engine.fx.load(Fx.Reverb)
        if (room == null || engine.fx.load(Fx.MonoFold) == null || engine.fx.load(Fx.Limiter) == null) {
            Log.w("Ensemble", "Could not configure the instrument effects")
            thread(name = "SmolkeyAudioClose") { engine.close() }
            return
        }
        reverb = room
        setReverb(appContext.getSharedPreferences("piano", 0).getInt(REVERB_PERCENT, 0))
        sampler = engine
        thread(name = "SmolkeyBank") {
            val result = engine.banks.load(BankSource.Asset("instruments.smb"), setOf(0, PERCUSSION_PROGRAM))
            if (result !is BankResult.Loaded && result != BankResult.Closed) {
                Log.w("Ensemble", "Could not index the bundled instrument bank: $result")
            }
        }
    }

    fun setReverb(percent: Int) {
        reverb?.set(Fx.Reverb.Amount, percent.coerceIn(0, 100) / 100f)
    }

    fun pause() {
        rollVoice = null
        sampler?.setActive(false)
    }

    override fun note(note: Int, down: Boolean, channel: Int, velocity: Int) {
        sampler?.note(channel, note, if (down) velocity.coerceIn(0, 127) else 0)
    }

    override fun hit(sound: PercussionSound, velocity: Int) {
        sampler?.pad(PERCUSSION_PROGRAM, sound.bankKey, velocity)
    }

    override fun pedal(channel: Int, value: Int) {
        sampler?.setPedal(channel, value)
    }

    override fun roll(velocity: Int?) {
        val engine = sampler ?: return
        val voice = rollVoice
        if (velocity == null) {
            voice?.let(engine::stop)
            rollVoice = null
            return
        }
        rollVoice = engine.pad(
            PERCUSSION_PROGRAM, PercussionSound.TAMBOURINE_SHAKE.bankKey, velocity, loop = true, current = voice,
        )
    }

    override fun release() {
        rollVoice = null
        sampler?.release()
    }

    override fun close() {
        if (closed) return
        closed = true
        release()
        val engine = sampler ?: return
        sampler = null
        reverb = null
        thread(name = "SmolkeyAudioClose") { engine.close() }
    }
}
