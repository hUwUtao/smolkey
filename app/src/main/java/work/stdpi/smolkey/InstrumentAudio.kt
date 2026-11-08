package work.stdpi.smolkey

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

internal const val REVERB_PERCENT = "reverbPercent"

/** Adapts the app's local instrument events to the native sampler. Called from the UI thread. */
internal class InstrumentAudio(context: Context) : AudioPort {
    private val appContext = context.applicationContext
    private var sampler: NativeSampler? = null
    @Volatile private var drums = emptyMap<PercussionSound, NativeSampler.Stem>()
    private var rollVoice: NativeSampler.Voice? = null
    private var closed = false

    fun start() {
        if (closed) return
        sampler?.let {
            it.setActive(true)
            return
        }
        val engine = try {
            NativeSampler.open(appContext)
        } catch (error: LinkageError) {
            Log.e("Ensemble", "Could not load the native sampler", error)
            return
        } ?: return
        sampler = engine
        engine.setReverb(appContext.getSharedPreferences("piano", 0).getInt(REVERB_PERCENT, 0))
        thread(name = "SmolkeyBank") {
            drums = engine.loadBundledBank(appContext.assets)
        }
    }

    fun setReverb(percent: Int) {
        sampler?.setReverb(percent)
    }

    fun pause() {
        rollVoice = null
        sampler?.setActive(false)
    }

    override fun note(note: Int, down: Boolean, channel: Int, velocity: Int) {
        sampler?.note(channel, note, if (down) velocity.coerceIn(0, 127) else 0)
    }

    override fun hit(sound: PercussionSound, velocity: Int) {
        val stem = drums[sound] ?: return
        sampler?.percussion(stem, velocity)
    }

    override fun roll(velocity: Int?) {
        val engine = sampler ?: return
        val voice = rollVoice
        if (velocity == null) {
            voice?.let(engine::stop)
            rollVoice = null
            return
        }
        val stem = drums[PercussionSound.TAMBOURINE_SHAKE] ?: return
        rollVoice = engine.percussion(stem, velocity, loop = true, current = voice)
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
        thread(name = "SmolkeyAudioClose") { engine.close() }
    }
}
