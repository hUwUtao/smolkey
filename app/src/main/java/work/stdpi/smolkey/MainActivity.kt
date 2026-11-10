package work.stdpi.smolkey

import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var output: MidiPort
    private lateinit var audio: InstrumentAudio
    private lateinit var eventBus: MidiEventBus
    private val midiStatus = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = getSharedPreferences("piano", 0)
        // Request the saved orientation before Compose lays out the instrument.
        val orientation = settings.instrumentOrientation()
        if (requestedOrientation != orientation) requestedOrientation = orientation
        midiStatus.value = getString(R.string.usb_connect_hint)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        output = MidiOutput(
            this,
            status = { update -> runOnUiThread { midiStatus.value = update } },
            receive = { eventBus.receive(it) },
        )
        audio = InstrumentAudio(this)
        val monitorInput = settings.getBoolean("monitorHostReturn", false)
        eventBus = MidiEventBus(
            output,
            MidiSamplerConsumer(audio),
            soundEnabled = settings.getBoolean("sound", true),
            monitorKeys = settings.getBoolean("monitorLocalKeys", !monitorInput),
            monitorInput = monitorInput,
        )
        setContent {
            val prefs = remember { getSharedPreferences("piano", 0) }
            var dark by rememberSaveable { mutableStateOf(prefs.getBoolean("dark", false)) }
            SmolkeyTheme(dark) {
                InstrumentScreen(output, audio, eventBus, dark, midiStatus.value, { midiStatus.value = it }, onReverb = audio::setReverb, onTheme = {
                    dark = it
                    prefs.edit().putBoolean("dark", it).apply()
                })
            }
        }
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        audio.start()
    }

    override fun onStop() {
        audio.pause()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        output.stop()
        audio.close()
        super.onDestroy()
    }
}

internal fun SharedPreferences.instrumentOrientation(): Int = getInt(
    "orientationMode",
    if (getBoolean("landscapeLocked", false)) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
)
