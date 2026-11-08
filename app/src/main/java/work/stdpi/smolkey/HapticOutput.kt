package work.stdpi.smolkey

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class HapticStyle { CLICK, RUMBLE }
internal enum class HapticRoute { VIEW, CLICK_PRIMITIVE, PREDEFINED_CLICK, RUMBLE }

internal fun chooseHapticRoute(
    style: HapticStyle,
    hasVibrator: Boolean,
    clickPrimitiveSupported: Boolean,
    predefinedClickAvailable: Boolean,
): HapticRoute = when {
    !hasVibrator -> HapticRoute.VIEW
    style == HapticStyle.CLICK && clickPrimitiveSupported -> HapticRoute.CLICK_PRIMITIVE
    style == HapticStyle.CLICK && predefinedClickAvailable -> HapticRoute.PREDEFINED_CLICK
    else -> HapticRoute.RUMBLE
}

/** Shared fallback ladder for held key notes and one-shot percussion hits. */
internal class HapticOutput(private val view: View, private val vibrator: Vibrator?) {
    var hasVibrator by mutableStateOf(false)
        private set
    var clickPrimitiveSupported by mutableStateOf(false)
        private set
    var predefinedClickAvailable by mutableStateOf(false)
        private set
    private var capabilitiesReady by mutableStateOf(false)
    val clickAvailable get() = clickPrimitiveSupported || predefinedClickAvailable

    init {
        Thread({
            val active = vibrator?.takeIf { runCatching { it.hasVibrator() }.getOrDefault(false) }
            val primitive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && active?.let {
                runCatching { it.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK) }.getOrDefault(false)
            } == true
            val predefined = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && active != null && runCatching {
                val support = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    active.areEffectsSupported(VibrationEffect.EFFECT_CLICK).firstOrNull()
                } else null
                support != Vibrator.VIBRATION_EFFECT_SUPPORT_NO
            }.getOrDefault(false)
            view.post {
                hasVibrator = active != null
                clickPrimitiveSupported = primitive
                predefinedClickAvailable = predefined
                capabilitiesReady = true
            }
        }, "SmolkeyHapticProbe").apply { isDaemon = true }.start()
    }

    fun play(style: HapticStyle, clickPower: Float, rumblePower: Float) {
        if (!capabilitiesReady) {
            val active = vibrator
            if (style == HapticStyle.CLICK && active != null && tryPredefinedClick(active)) return
            if (active != null && tryRumble(active, rumblePower)) return
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            return
        }
        val active = vibrator?.takeIf { hasVibrator } ?: run {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            return
        }
        val route = chooseHapticRoute(style, true, clickPrimitiveSupported, predefinedClickAvailable)

        if (route == HapticRoute.CLICK_PRIMITIVE && tryClickPrimitive(active, clickPower)) return
        if (route != HapticRoute.RUMBLE && tryPredefinedClick(active)) return
        if (tryRumble(active, rumblePower)) return
        if (predefinedClickAvailable && tryPredefinedClick(active)) return

        // Last device-independent fallback. Android applies its own haptics setting here.
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun tryClickPrimitive(vibrator: Vibrator, power: Float): Boolean = runCatching {
        vibrator.vibrate(
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, power.coerceIn(0.2f, 1f))
                .compose()
        )
    }.isSuccess

    private fun tryPredefinedClick(vibrator: Vibrator): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching { vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) }.isSuccess
    }

    private fun tryRumble(vibrator: Vibrator, power: Float): Boolean {
        val strength = power.coerceIn(0.2f, 1f)
        val duration = (18f + 32f * strength).toLong()
        val amplitude = (96f + 159f * strength).toInt().coerceIn(1, 255)
        if (runCatching {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            }.isSuccess) return true
        if (runCatching {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, duration), intArrayOf(0, amplitude), -1))
            }.isSuccess) return true
        return runCatching { vibrator.vibrate(longArrayOf(0, duration), -1) }.isSuccess
    }
}
