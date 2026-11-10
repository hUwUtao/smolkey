package work.stdpi.smolkey

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Vibrator
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.roundToInt

private val Evergreen = Color(0xFF00677F)
private val Paper = Color(0xFFF1F5F7)

@Composable
internal fun SmolkeyTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dynamic = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val colors = if (dynamic) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(
        primary = Color(0xFF78D4E9), onPrimary = Color(0xFF003640), background = Color(0xFF10191E),
        surface = Color(0xFF18262D), onSurface = Color(0xFFE6F1F4), secondary = Color(0xFFFFC285),
        onSurfaceVariant = Color(0xFFB4C8CE), outlineVariant = Color(0xFF3D555E),
    ) else lightColorScheme(
        primary = Evergreen, onPrimary = Color.White, background = Paper,
        surface = Color(0xFFFAFCFD), onSurface = Color(0xFF15252B), secondary = Color(0xFF9B4D00),
        onSurfaceVariant = Color(0xFF40565E), outlineVariant = Color(0xFFCFDCE0),
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun InstrumentScreen(
    output: MidiPort,
    audio: AudioPort,
    eventBus: MidiEventBus,
    dark: Boolean,
    message: String,
    onStatus: (String) -> Unit,
    onReverb: (Int) -> Unit,
    onTheme: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val activity = context as? ComponentActivity
    val view = LocalView.current
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    val hapticOutput = remember(view, vibrator) { HapticOutput(view, vibrator) }
    val prefs = remember { context.getSharedPreferences("piano", 0) }
    var sound by remember { mutableStateOf(eventBus.soundEnabled) }
    var monitorKeys by remember { mutableStateOf(eventBus.monitorKeys) }
    var monitorInput by remember { mutableStateOf(eventBus.monitorInput) }
    var reverb by remember { mutableIntStateOf(prefs.getInt(REVERB_PERCENT, 0).coerceIn(0, 100)) }
    var mode by rememberSaveable { mutableStateOf(runCatching { InstrumentMode.valueOf(prefs.getString("mode", "PIANO") ?: "PIANO") }.getOrDefault(InstrumentMode.PIANO)) }
    var modeMenu by remember { mutableStateOf(false) }
    var drumKit by rememberSaveable { mutableStateOf(false) }
    var drumSound by rememberSaveable { mutableStateOf(PercussionSound.SNARE) }
    var launchpadSurfaceKind by rememberSaveable {
        mutableStateOf(runCatching { LaunchpadSurfaceKind.valueOf(prefs.getString("launchpadSurfaceKind", "KEY") ?: "KEY") }.getOrDefault(LaunchpadSurfaceKind.KEY))
    }
    var launchpad by remember {
        val saved = LaunchpadLayout(
            sharedRows = prefs.getInt("gridRows", 4), sharedColumns = prefs.getInt("gridColumns", 4),
            firstNote = prefs.getInt("firstNote", 36), tileRow = prefs.getInt("tileRow", 0),
            tileColumn = prefs.getInt("tileColumn", 0), tileRows = prefs.getInt("tileRows", 4),
            tileColumns = prefs.getInt("tileColumns", 4),
        ).takeIf { it.isValid() } ?: LaunchpadLayout()
        mutableStateOf(if (prefs.getBoolean("sharedTile", false)) saved else saved.fullGrid())
    }
    var splitSide by rememberSaveable { mutableStateOf(prefs.getString("splitSide", inferSplitSide(launchpad)) ?: "FULL") }
    var pressed by remember { mutableStateOf(emptySet<Long>()) }
    var sensorAvailable by remember { mutableStateOf(false) }
    var sensitivity by rememberSaveable { mutableFloatStateOf(1f) }
    var placement by rememberSaveable { mutableStateOf(false) }
    var customPlacement by rememberSaveable { mutableStateOf(false) }
    var manualTurns by rememberSaveable { mutableIntStateOf(0) }
    var flipGridRows by rememberSaveable { mutableStateOf(prefs.getBoolean("flipGridRows", false)) }
    var sharedTile by rememberSaveable { mutableStateOf(prefs.getBoolean("sharedTile", false)) }
    var automaticUpright by rememberSaveable { mutableStateOf(prefs.getBoolean("automaticUpright", true)) }
    var physicalQuarterTurns by remember { mutableIntStateOf(0) }
    var haptics by remember { mutableStateOf(prefs.getBoolean("haptics", true)) }
    var hapticStyle by rememberSaveable { mutableStateOf(prefs.getString("hapticStyle", "RUMBLE")?.takeIf { it in setOf("CLICK", "RUMBLE") } ?: "RUMBLE") }
    var hapticPower by remember { mutableFloatStateOf(prefs.getFloat("hapticPower", 1f).coerceIn(0.2f, 1f)) }
    var rumblePower by remember { mutableFloatStateOf(prefs.getFloat("rumblePower", 1f).coerceIn(0.2f, 1f)) }
    var selected by rememberSaveable { mutableStateOf(prefs.getString("output", Output.USB.name)?.takeIf { saved -> Output.entries.any { it.name == saved } } ?: Output.USB.name) }
    var octave by rememberSaveable { mutableIntStateOf(prefs.getInt("octave", 4).coerceIn(0, 7)) }
    var keyboardZoom by rememberSaveable { mutableFloatStateOf(prefs.getFloat("keyboardZoom", 2f).coerceIn(1f, 4f)) }
    var channel by rememberSaveable { mutableIntStateOf(prefs.getInt("channel", 1).coerceIn(1, 16)) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCredits by rememberSaveable { mutableStateOf(false) }
    var orientationMode by rememberSaveable { mutableIntStateOf(prefs.instrumentOrientation()) }
    val visibleNotes by eventBus.visibleNotes.collectAsState()
    val currentHaptics by rememberUpdatedState(haptics)
    val currentHapticStyle by rememberUpdatedState(if (hapticStyle == "CLICK") HapticStyle.CLICK else HapticStyle.RUMBLE)
    val currentHapticPower by rememberUpdatedState(hapticPower)
    val currentRumblePower by rememberUpdatedState(rumblePower)
    val currentChannel by rememberUpdatedState(channel)
    val currentZoom by rememberUpdatedState(keyboardZoom)
    val configuration = LocalConfiguration.current
    val windowSize = LocalWindowInfo.current.containerSize
    val landscape = if (windowSize.width > 0 && windowSize.height > 0) {
        windowSize.width > windowSize.height
    } else {
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }
    val displayQuarterTurns = activity?.display?.rotation ?: Surface.ROTATION_0
    val gridTurns = if (!sharedTile) 0 else if (automaticUpright) {
        uprightGridTurns(physicalQuarterTurns, displayQuarterTurns)
    } else manualTurns
    val clickPrimitiveSupported = hapticOutput.clickPrimitiveSupported
    val clickAvailable = hapticOutput.clickAvailable
    fun hit(sound: PercussionSound, velocity: Int = 96) {
        if (currentHaptics) hapticOutput.play(currentHapticStyle, currentHapticPower, currentRumblePower)
        eventBus.dispatch(InstrumentEvent.Hit(sound, currentChannel, velocity))
    }
    val piano = remember(view, eventBus) {
        PianoController(
            channel = { currentChannel },
            feedback = {
                if (currentHaptics) hapticOutput.play(currentHapticStyle, currentHapticPower, currentRumblePower)
            },
            eventBus = eventBus,
        )
    }
    val lifecycle = LocalLifecycleOwner.current
    val currentSelected by rememberUpdatedState(selected)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { piano.releaseAll(); audio.release(); output.stop() }
            if (event == Lifecycle.Event.ON_START && currentSelected == Output.USB.name) output.select(Output.USB)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); piano.releaseAll() }
    }
    val bluetoothPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) output.startBluetooth() else onStatus(resources.getString(R.string.bluetooth_permission))
    }
    val modeTitle = stringResource(mode.titleRes)
    val modeDescription = stringResource(R.string.instrument_mode, modeTitle)
    val resizeDescription = stringResource(R.string.keyboard_resize_area)
    val reverbDescription = stringResource(R.string.room_reverb)
    val rangeStart = remember(octave) { (octave * 12 + 12).coerceIn(24, 96) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val cutoutLeft = with(density) { WindowInsets.displayCutout.getLeft(this, layoutDirection).toDp() }
    val cutoutRight = with(density) { WindowInsets.displayCutout.getRight(this, layoutDirection).toDp() }
    val pageGutter = if (landscape) 20.dp else 12.dp
    val leftControlInset = (cutoutLeft - pageGutter).coerceAtLeast(0.dp)
    val rightControlInset = (cutoutRight - pageGutter).coerceAtLeast(0.dp)
    val pianoEndInset = if (landscape && cutoutRight > 0.dp) 0.dp else pageGutter
    LaunchedEffect(selected, octave, keyboardZoom, channel, orientationMode, hapticPower, rumblePower, hapticStyle) {
        prefs.edit()
            .putString("output", selected)
            .putInt("octave", octave)
            .putFloat("keyboardZoom", keyboardZoom)
            .putInt("channel", channel)
            .putInt("orientationMode", orientationMode)
            .putFloat("hapticPower", hapticPower)
            .putFloat("rumblePower", rumblePower)
            .putString("hapticStyle", hapticStyle)
            .putString("mode", mode.name)
            .apply()
    }
    LaunchedEffect(activity, orientationMode) {
        if (activity != null && activity.requestedOrientation != orientationMode) {
            piano.releaseAll()
            activity.requestedOrientation = orientationMode
        }
    }
    LaunchedEffect(launchpad, sharedTile, splitSide, automaticUpright, manualTurns, flipGridRows, launchpadSurfaceKind) {
        prefs.edit().putBoolean("sharedTile", sharedTile).putBoolean("automaticUpright", automaticUpright)
            .putBoolean("flipGridRows", flipGridRows)
            .putString("splitSide", splitSide)
            .putString("launchpadSurfaceKind", launchpadSurfaceKind.name)
            .putInt("manualTurns", manualTurns).putInt("gridRows", launchpad.sharedRows)
            .putInt("gridColumns", launchpad.sharedColumns).putInt("tileRow", launchpad.tileRow)
            .putInt("tileColumn", launchpad.tileColumn).putInt("tileRows", launchpad.tileRows)
            .putInt("tileColumns", launchpad.tileColumns).putInt("firstNote", launchpad.firstNote).apply()
    }
    LaunchedEffect(landscape, sharedTile, splitSide) {
        if (sharedTile && splitSide in setOf("LEADING", "TRAILING")) {
            piano.releaseAll()
            val second = splitSide == "TRAILING"
            launchpad = if (landscape) launchpad.copy(
                sharedRows = 4, sharedColumns = 4, tileRows = 2, tileColumns = 4,
                tileRow = if (second) 2 else 0, tileColumn = 0,
            ) else launchpad.copy(
                sharedRows = 4, sharedColumns = 4, tileRows = 4, tileColumns = 2,
                tileRow = 0, tileColumn = if (second) 2 else 0,
            )
        }
    }
    DisposableEffect(sharedTile, automaticUpright, mode) {
        val listener = object : android.view.OrientationEventListener(context, android.hardware.SensorManager.SENSOR_DELAY_UI) {
            private var candidate = -1
            private var since = 0L
            override fun onOrientationChanged(degrees: Int) {
                if (!mode.usesPhysicalOrientation || !sharedTile || !automaticUpright || degrees == ORIENTATION_UNKNOWN) return
                val quarter = ((degrees + 45) / 90) % 4
                val distance = kotlin.math.abs(((degrees - quarter * 90 + 540) % 360) - 180)
                if (distance > 25) { candidate = -1; return }
                val now = android.os.SystemClock.elapsedRealtime()
                if (candidate != quarter) { candidate = quarter; since = now }
                else if (now - since >= 200) physicalQuarterTurns = quarter
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(start = pageGutter, end = pianoEndInset, top = 6.dp, bottom = 6.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = if (landscape) 60.dp + leftControlInset else 0.dp)) {
                    SmolkeyBrand(showWordmark = !mode.hasPlacement || landscape, onClick = {
                        piano.releaseAll()
                        showCredits = true
                    })
                }
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                    detectTransformGestures { _, _, scale, _ -> keyboardZoom = (currentZoom / scale).coerceIn(1f, 4f) }
                }.semantics { contentDescription = resizeDescription }, contentAlignment = Alignment.Center) {
                    Text(message, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = if (landscape) pageGutter + rightControlInset else 0.dp)) {
                    IconButton(onClick = { modeMenu = true }, modifier = Modifier.semantics { contentDescription = modeDescription }) {
                        Icon(mode.icon, contentDescription = null)
                    }
                    androidx.compose.material3.DropdownMenu(modeMenu, { modeMenu = false }) {
                        InstrumentMode.entries.forEach { entry -> androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(entry.titleRes)) }, onClick = { piano.releaseAll(); mode = entry; modeMenu = false; prefs.edit().putString("mode", entry.name).apply() }) }
                    }
                    if (mode.hasPlacement) IconButton(onClick = { piano.releaseAll(); placement = true }) {
                        Icon(androidx.compose.material.icons.Icons.Default.GridOn, contentDescription = stringResource(R.string.launchpad_placement))
                    }
                    IconButton(onClick = {
                        orientationMode = when (activity?.display?.rotation) {
                            Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    }) { Icon(androidx.compose.material.icons.Icons.Default.ScreenRotation, contentDescription = stringResource(R.string.rotate_clockwise)) }
                    IconButton(onClick = { showSettings = true }) { Icon(androidx.compose.material.icons.Icons.Default.Tune, contentDescription = stringResource(R.string.open_settings)) }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (landscape) 12.dp else 0.dp)) {
                if (landscape) Column(Modifier.width(48.dp + leftControlInset).padding(start = leftControlInset), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(onClick = { octave = (octave - 1).coerceAtLeast(0) }) { Icon(androidx.compose.material.icons.Icons.Default.Remove, stringResource(R.string.lower_octave)) }
                    Text(noteName(rangeStart), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { octave = (octave + 1).coerceAtMost(7) }) { Icon(androidx.compose.material.icons.Icons.Default.Add, stringResource(R.string.raise_octave)) }
                }
                mode.Content(
                    InstrumentModeViewState(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        octave = octave,
                        rootNote = rangeStart,
                        visibleNotes = visibleNotes,
                        piano = piano,
                        dark = dark,
                        keyboardZoom = keyboardZoom,
                        launchpad = launchpad,
                        gridTurns = gridTurns,
                        flipGridRows = flipGridRows,
                        launchpadSurfaceKind = launchpadSurfaceKind,
                        onLaunchpadKeyDown = { pointer, note -> if (pointer !in pressed) { pressed += pointer; piano.move(pointer, note) } },
                        onLaunchpadKeyUp = { pointer -> pressed -= pointer; piano.move(pointer, null) },
                        onPercussionHit = ::hit,
                        drumKit = drumKit,
                        drumSound = drumSound,
                        context = context,
                        sensorsEnabled = !showSettings && !showCredits,
                        sensorAvailable = sensorAvailable,
                        sensitivity = sensitivity,
                        onSensorAvailable = { sensorAvailable = it },
                        onRoll = { velocity -> eventBus.dispatch(InstrumentEvent.Roll(velocity)) },
                    )
                )
            }
        }
    }
    if (showCredits) CreditsDialog(onDismiss = { showCredits = false })
    if (showSettings) ModalBottomSheet(onDismissRequest = { showSettings = false }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.instrument_controls, modeTitle), style = MaterialTheme.typography.headlineSmall)
            mode.Settings(InstrumentModeSettingsState(drumKit, { drumKit = it }, drumSound, { drumSound = it }, sensitivity, { sensitivity = it }))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Output.entries.forEachIndexed { index, route ->
                    val label = when (route) {
                        Output.OFF -> R.string.output_off
                        Output.USB -> R.string.output_usb
                        Output.BLUETOOTH -> R.string.output_bluetooth
                    }
                    SegmentedButton(
                        selected == route.name,
                        onClick = {
                            piano.releaseAll()
                            selected = route.name
                            output.select(route)
                            val status = when (route) {
                                Output.OFF -> R.string.midi_output_off
                                Output.USB -> R.string.usb_connect_hint
                                Output.BLUETOOTH -> R.string.bluetooth_start_hint
                            }
                            onStatus(resources.getString(status))
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, Output.entries.size),
                    ) { Text(stringResource(label)) }
                }
            }
            if (selected == Output.BLUETOOTH.name) Button(onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 31) bluetoothPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)) else output.startBluetooth()
            }, Modifier.fillMaxWidth()) { Icon(androidx.compose.material.icons.Icons.Default.Bluetooth, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.start_bluetooth_midi)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { output.retry(Output.valueOf(selected)) }) { Icon(androidx.compose.material.icons.Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.retry)) }
                OutlinedButton(onClick = {
                    piano.releaseAll()
                    eventBus.clearHost(channel)
                    output.send(allNotesOff(channel))
                    onStatus(resources.getString(R.string.all_notes_off_sent))
                }) { Icon(androidx.compose.material.icons.Icons.Default.MusicNote, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.all_notes_off)) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.midi_channel, channel), style = MaterialTheme.typography.titleMedium)
            Slider(channel.toFloat(), { piano.releaseAll(); channel = it.toInt().coerceIn(1, 16) }, valueRange = 1f..16f, steps = 14)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.keyboard_width), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.keyboard_octaves, keyboardZoom))
            }
            Slider(keyboardZoom, { keyboardZoom = it }, valueRange = 1f..4f)
            Text(stringResource(R.string.keyboard_resize_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.phone_sound))
                Switch(sound, { enabled ->
                    eventBus.soundEnabled = enabled
                    if (!enabled) audio.release()
                    sound = enabled
                    prefs.edit().putBoolean("sound", enabled).apply()
                })
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Text(
                    stringResource(R.string.midi_monitoring),
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                MonitoringSwitch(stringResource(R.string.monitor_local_keys), monitorKeys) { enabled ->
                    eventBus.monitorKeys = enabled
                    monitorKeys = enabled
                    prefs.edit().putBoolean("monitorLocalKeys", enabled).apply()
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                MonitoringSwitch(stringResource(R.string.monitor_midi_input), monitorInput) { enabled ->
                    eventBus.monitorInput = enabled
                    monitorInput = enabled
                    prefs.edit().putBoolean("monitorLocalKeys", monitorKeys).putBoolean("monitorHostReturn", enabled).apply()
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.room_reverb), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.percentage, reverb))
            }
            Slider(
                value = reverb.toFloat(),
                onValueChange = { value ->
                    val percent = value.roundToInt()
                    if (percent != reverb) {
                        reverb = percent
                        onReverb(percent)
                        prefs.edit().putInt(REVERB_PERCENT, percent).apply()
                    }
                },
                valueRange = 0f..100f,
                enabled = sound,
                modifier = Modifier.semantics { contentDescription = reverbDescription },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.haptic_feedback)); Switch(haptics, { enabled -> haptics = enabled; prefs.edit().putBoolean("haptics", enabled).apply() })
            }
            Text(stringResource(R.string.haptic_feel), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("RUMBLE" to R.string.haptic_rumble, "CLICK" to R.string.haptic_click).forEachIndexed { index, (style, label) ->
                    SegmentedButton(hapticStyle == style, onClick = { hapticStyle = style }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(stringResource(label)) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.click_strength), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.percentage, (hapticPower * 100).roundToInt()))
            }
            Slider(hapticPower, { hapticPower = it }, valueRange = 0.2f..1f, steps = 7, enabled = haptics && hapticStyle == "CLICK" && clickPrimitiveSupported)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.rumble_power), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.percentage, (rumblePower * 100).roundToInt()))
            }
            Slider(rumblePower, { rumblePower = it }, valueRange = 0.2f..1f, steps = 7, enabled = haptics && (hapticStyle == "RUMBLE" || (hapticStyle == "CLICK" && !clickAvailable)))
            OutlinedButton(onClick = { hapticOutput.play(currentHapticStyle, hapticPower, rumblePower) }, enabled = haptics, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.test_haptic))
            }
            if (!hapticOutput.hasVibrator) Text(stringResource(R.string.haptics_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.dark_theme)); Switch(dark, onTheme)
            }
            FilledTonalButton(onClick = {
                orientationMode = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }, Modifier.fillMaxWidth()) { Icon(androidx.compose.material.icons.Icons.Default.ScreenRotation, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.follow_device_rotation)) }
        }
    }
    if (placement && mode.hasPlacement) ModalBottomSheet(onDismissRequest = { placement = false }, sheetState = sheetState) {
        mode.PlacementSettings(
            LaunchpadPlacementState(
                landscape = landscape,
                sharedTile = sharedTile,
                launchpad = launchpad,
                automaticUpright = automaticUpright,
                flipRows = flipGridRows,
                customPlacement = customPlacement,
                surfaceKind = launchpadSurfaceKind,
                onSurfaceKindChanged = { kind ->
                    if (launchpadSurfaceKind != kind) {
                        piano.releaseAll()
                        launchpadSurfaceKind = kind
                    }
                },
                onChooseTile = { side, rows, columns, tileRow, tileColumn ->
                    sharedTile = true
                    splitSide = side
                    automaticUpright = true
                    launchpad = launchpad.copy(
                        sharedRows = 4, sharedColumns = 4, tileRows = rows, tileColumns = columns,
                        tileRow = tileRow, tileColumn = tileColumn,
                    )
                },
                onUseWholeGrid = {
                    launchpad = launchpad.fullGrid()
                    sharedTile = false
                    splitSide = "FULL"
                    customPlacement = false
                },
                onAutomaticUprightChanged = { automaticUpright = it },
                onFlipRowsChanged = { flipGridRows = it },
                onCustomPlacementChanged = { customPlacement = it },
                onRotateMapping = { splitSide = "CUSTOM"; manualTurns = (manualTurns + 1) % 4; automaticUpright = false },
                onUpdateGrid = { field, delta ->
                    splitSide = "CUSTOM"
                    updateGrid(launchpad, field, delta)?.let { launchpad = it }
                },
                onDismiss = { placement = false },
            )
        )
    }
}

@Composable
private fun MonitoringSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

private fun LaunchpadLayout.fullGrid() =
    copy(tileRow = 0, tileColumn = 0, tileRows = sharedRows, tileColumns = sharedColumns)

private fun updateGrid(layout: LaunchpadLayout, field: LaunchpadGridField, delta: Int): LaunchpadLayout? {
    val changed = when (field) {
        LaunchpadGridField.SHARED_ROWS -> layout.copy(sharedRows = (layout.sharedRows + delta).coerceIn(1, 128))
        LaunchpadGridField.SHARED_COLUMNS -> layout.copy(sharedColumns = (layout.sharedColumns + delta).coerceIn(1, 128))
        LaunchpadGridField.TILE_ROWS -> layout.copy(tileRows = (layout.tileRows + delta).coerceAtLeast(1))
        LaunchpadGridField.TILE_COLUMNS -> layout.copy(tileColumns = (layout.tileColumns + delta).coerceAtLeast(1))
        LaunchpadGridField.TILE_ROW -> layout.copy(tileRow = (layout.tileRow + delta).coerceAtLeast(0))
        LaunchpadGridField.TILE_COLUMN -> layout.copy(tileColumn = (layout.tileColumn + delta).coerceAtLeast(0))
    }
    return changed.takeIf { it.isValid() }
}

private fun inferSplitSide(layout: LaunchpadLayout): String = when {
    layout.tileRows == 2 && layout.tileColumns == 4 && layout.tileRow == 0 -> "LEADING"
    layout.tileRows == 2 && layout.tileColumns == 4 && layout.tileRow == 2 -> "TRAILING"
    layout.tileRows == 4 && layout.tileColumns == 2 && layout.tileColumn == 0 -> "LEADING"
    layout.tileRows == 4 && layout.tileColumns == 2 && layout.tileColumn == 2 -> "TRAILING"
    else -> "FULL"
}

