package work.stdpi.ensemble

/** Address in a MIDI patch map. A bank is the combined CC 0 and CC 32 value. */
data class MidiPatch(val bank: Int, val program: Int, val percussion: Boolean = false) {
    init {
        require(bank in 0..16383 && program in 0..127)
    }
}

/** Routes MIDI channel messages into an installed bank. Use one synth per engine. */
class MidiSynth(private val engine: Ensemble, patches: Map<MidiPatch, Int> = emptyMap()) {
    private val patches = patches.toMap().also { require(it.values.all { id -> id in 0..65535 }) }
    private val channels = Array(16) { Channel() }

    /** Returns false if a note has no matching program in the loaded bank. */
    @Synchronized
    fun send(message: MidiMessage): Boolean {
        require(message.channel in 1..16)
        val number = message.channel
        val channel = channels[number - 1]
        return when (message) {
            is MidiMessage.Note -> {
                require(message.key in 0..127 && message.velocity in 0..127)
                if (!message.down || message.velocity == 0) {
                    releaseKey(number, channel, message.key)
                    true
                } else {
                    val program = resolve(number, channel) ?: return false
                    channel.held[message.key]++
                    channel.sounding[message.key] = true
                    engine.note(number, message.key, message.velocity, program)
                    true
                }
            }
            is MidiMessage.ProgramChange -> {
                require(message.program in 0..127)
                channel.program = message.program
                channel.bank = channel.bankMsb * 128 + channel.bankLsb
                true
            }
            is MidiMessage.ControlChange -> {
                require(message.controller in 0..127 && message.value in 0..127)
                control(number, channel, message.controller, message.value)
                true
            }
            is MidiMessage.PitchBend -> {
                require(message.value in 0..16383)
                channel.bend = message.value
                updateMix(number, channel)
                true
            }
            is MidiMessage.PolyPressure, is MidiMessage.ChannelPressure -> true
        }
    }

    @Synchronized
    fun reset() {
        for (index in channels.indices) {
            val number = index + 1
            engine.silenceChannel(number)
            engine.setPedal(number, 0)
            channels[index] = Channel()
            updateMix(number, channels[index])
        }
    }

    /** Releases held and sustained notes and lets their sample tails decay. */
    @Synchronized
    fun release() {
        channels.forEachIndexed { index, channel ->
            channel.held.fill(0)
            engine.setPedal(index + 1, 0)
            releaseUnheld(index + 1, channel)
        }
    }

    private fun resolve(number: Int, channel: Channel): Int? {
        val address = MidiPatch(channel.bank, channel.program, percussion = number == 10)
        val id = patches[address] ?: when {
            address.bank != 0 -> return null
            !address.percussion -> address.program
            address.program == 0 -> 128
            else -> return null
        }
        return id.takeIf { engine.banks.current?.programs?.contains(it) == true }
    }

    private fun releaseKey(number: Int, channel: Channel, key: Int) {
        if (channel.held[key] > 0) channel.held[key]--
        if (channel.held[key] == 0 && channel.sounding[key]) {
            channel.sounding[key] = false
            engine.note(number, key, 0)
        }
    }

    private fun releaseUnheld(number: Int, channel: Channel) {
        for (key in channel.sounding.indices) {
            if (channel.held[key] == 0 && channel.sounding[key]) {
                channel.sounding[key] = false
                engine.note(number, key, 0)
            }
        }
    }

    private fun updateMix(number: Int, channel: Channel) {
        val pan = (channel.pan - 64) / (if (channel.pan >= 64) 63f else 64f)
        val range = channel.bendSemitones + channel.bendCents / 100f
        engine.channelMix(number, (channel.volume / 127f) * (channel.expression / 127f), pan,
            (channel.bend - 8192) / 8192f * range)
    }

    private fun control(number: Int, channel: Channel, controller: Int, value: Int) {
        when (controller) {
            0 -> channel.bankMsb = value
            32 -> channel.bankLsb = value
            6, 38 -> if (channel.rpnMsb == 0 && channel.rpnLsb == 0) {
                if (controller == 6) channel.bendSemitones = value.coerceAtMost(24)
                else channel.bendCents = value.coerceAtMost(99)
                updateMix(number, channel)
            }
            7, 10, 11 -> {
                when (controller) {
                    7 -> channel.volume = value
                    10 -> channel.pan = value
                    11 -> channel.expression = value
                }
                updateMix(number, channel)
            }
            64 -> engine.setPedal(number, value)
            98, 99 -> {
                channel.rpnMsb = 127
                channel.rpnLsb = 127
            }
            100 -> channel.rpnLsb = value
            101 -> channel.rpnMsb = value
            120 -> {
                channel.held.fill(0)
                channel.sounding.fill(false)
                engine.silenceChannel(number)
            }
            121 -> {
                channel.expression = 127
                channel.bend = 8192
                channel.rpnMsb = 127
                channel.rpnLsb = 127
                engine.setPedal(number, 0)
                releaseUnheld(number, channel)
                updateMix(number, channel)
            }
            in 123..127 -> {
                channel.held.fill(0)
                releaseUnheld(number, channel)
            }
        }
    }

    private class Channel {
        var program = 0
        var bank = 0
        var bankMsb = 0
        var bankLsb = 0
        var volume = 127
        var pan = 64
        var expression = 127
        var bend = 8192
        var bendSemitones = 2
        var bendCents = 0
        var rpnMsb = 127
        var rpnLsb = 127
        val held = IntArray(128)
        val sounding = BooleanArray(128)
    }
}
