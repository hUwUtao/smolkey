package work.stdpi.smolkey

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

@Composable
internal fun LaunchpadSurface(
    layout: LaunchpadLayout,
    quarterTurns: Int,
    flipRows: Boolean,
    surfaceKind: LaunchpadSurfaceKind,
    held: Set<Int>,
    onDown: (Long, Int) -> Unit,
    onUp: (Long) -> Unit,
    onDrumHit: (PercussionSound) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(4.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(layout.tileRows) { displayRow ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(layout.tileColumns) { displayColumn ->
                        val coordinate = mappedTileCoordinate(layout, displayRow, displayColumn, quarterTurns, flipRows)
                        val cell = displayRow * layout.tileColumns + displayColumn
                        val note = layout.noteAt(coordinate)
                        val drumSound = launchpadDrumSound(coordinate)
                        val isDrum = surfaceKind == LaunchpadSurfaceKind.DRUM
                        val padLabel = if (isDrum) stringResource(drumSound.labelRes) else stringResource(R.string.note_number, note)
                        val row = coordinate.row + 1
                        val column = coordinate.column + 1
                        val description = stringResource(R.string.pad_description, row, column, padLabel)
                        androidx.compose.foundation.layout.Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .background(if (!isDrum && note in held) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .semantics { contentDescription = description }
                                .pointerInput(layout, quarterTurns, flipRows, surfaceKind, cell, note, drumSound) {
                                    awaitEachGesture {
                                        val active = mutableSetOf<Long>()
                                        try {
                                            do {
                                                val event = awaitPointerEvent()
                                                event.changes.forEach { change ->
                                                    val pointer = change.id.value
                                                    val inside = change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()
                                                    if (change.pressed && !change.previousPressed && inside) {
                                                        active += pointer
                                                        if (isDrum) onDrumHit(drumSound) else onDown(pointer, note)
                                                    } else if (pointer in active && change.pressed && !inside) {
                                                        active -= pointer
                                                        if (!isDrum) onUp(pointer)
                                                    } else if (!change.pressed && change.previousPressed && active.remove(pointer)) {
                                                        if (!isDrum) onUp(pointer)
                                                    }
                                                }
                                            } while (event.changes.any { it.pressed })
                                        } finally {
                                            if (!isDrum) active.forEach(onUp)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(R.string.pad_label, row, column, padLabel)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DrumSurface(
    kit: Boolean,
    singleSound: PercussionSound,
    onHit: (PercussionSound) -> Unit,
) {
    val sounds = listOf(PercussionSound.KICK, PercussionSound.SNARE, PercussionSound.CLOSED_HI_HAT, PercussionSound.LOW_TOM)
    if (!kit) DrumPad(singleSound, onHit, Modifier.fillMaxSize().padding(12.dp))
    else Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sounds.chunked(2).forEach { row ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { sound -> DrumPad(sound, onHit, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun DrumPad(sound: PercussionSound, onHit: (PercussionSound) -> Unit, modifier: Modifier) {
    val label = stringResource(sound.labelRes)
    val description = stringResource(R.string.drum_pad_description, label)
    val play = stringResource(R.string.play_sound, label)
    androidx.compose.foundation.layout.Box(
        modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(20.dp))
            .pointerInput(sound) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        event.changes.filter { it.pressed && !it.previousPressed }.forEach { onHit(sound) }
                    } while (event.changes.any { it.pressed })
                }
            }
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick(label = play) { onHit(sound); true }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label) }
}

@Composable
internal fun TambourineSensors(
    context: Context,
    enabled: Boolean,
    sensitivity: Float,
    onHit: (Int, Long, FloatArray) -> Unit,
    onAvailable: (Boolean) -> Unit,
) {
    DisposableEffect(context, enabled, sensitivity) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val motion = TambourineStrokeDetector(threshold = 4f / sensitivity.coerceIn(.5f, 2f))
        val acceleration = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gravitySensor = if (acceleration?.type == Sensor.TYPE_ACCELEROMETER) manager.getDefaultSensor(Sensor.TYPE_GRAVITY) else null
        val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gameRotation = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val accelDetector = TambourineStrokeDetector(threshold = 4f / sensitivity.coerceIn(.5f, 2f))
        val gyroDetector = TambourineStrokeDetector(threshold = 4f / sensitivity.coerceIn(.5f, 2f))
        val gravity = FloatArray(3)
        val gravityTracker = FloatArray(3)
        var lastGyro = FloatArray(3)
        var lastGyroTime = 0L
        var lastGameQuaternion: FloatArray? = null
        var lastGameAngularVelocity = FloatArray(3)
        var lastGameTime = 0L
        var lastMotionHit = 0L
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == gravitySensor?.type) {
                    for (index in 0..2) gravity[index] = event.values[index]
                    return
                }
                if (event.timestamp - startedAt < 100_000_000L) return
                val candidate = when (event.sensor.type) {
                    acceleration?.type -> {
                        val linear = FloatArray(3) { index ->
                            if (acceleration.type == Sensor.TYPE_ACCELEROMETER) {
                                if (gravitySensor != null) event.values[index] - gravity[index]
                                else {
                                    gravityTracker[index] = .92f * gravityTracker[index] + .08f * event.values[index]
                                    event.values[index] - gravityTracker[index]
                                }
                            } else event.values[index]
                        }
                        accelDetector.sample(event.timestamp, linear)
                    }
                    gyroscope?.type -> {
                        val current = event.values.copyOfRange(0, 3)
                        val elapsed = event.timestamp - lastGyroTime
                        val result = if (lastGyroTime > 0L && elapsed in 1_000_000L..100_000_000L) {
                            val angularAcceleration = FloatArray(3) { (current[it] - lastGyro[it]) / (elapsed / 1_000_000_000f) * .07f }
                            gyroDetector.sample(event.timestamp, angularAcceleration)
                        } else null
                        lastGyro = current
                        lastGyroTime = event.timestamp
                        result
                    }
                    gameRotation?.type -> {
                        val current = FloatArray(4) { index -> if (index < event.values.size) event.values[index] else 0f }
                        val previous = lastGameQuaternion
                        val elapsed = event.timestamp - lastGameTime
                        val result = if (previous != null && elapsed in 1_000_000L..100_000_000L) {
                            val dot = (0..3).sumOf { (current[it] * previous[it]).toDouble() }.toFloat().coerceIn(-1f, 1f)
                            val angle = 2f * kotlin.math.acos(kotlin.math.abs(dot))
                            val axis = FloatArray(3) { current[it] - previous[it] }
                            val length = sqrt(axis.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(.0001f)
                            val angularVelocity = FloatArray(3) { axis[it] / length * angle / (elapsed / 1_000_000_000f) }
                            val accelerationVector = FloatArray(3) { (angularVelocity[it] - lastGameAngularVelocity[it]) / (elapsed / 1_000_000_000f) * .07f }
                            lastGameAngularVelocity = angularVelocity
                            gyroDetector.sample(event.timestamp, accelerationVector)
                        } else null
                        lastGameQuaternion = current
                        lastGameTime = event.timestamp
                        result
                    }
                    else -> null
                }
                if (candidate != null && event.timestamp - lastMotionHit >= 40_000_000L) {
                    lastMotionHit = event.timestamp
                    onHit(96, candidate.timestampNs, candidate.direction)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = enabled && acceleration != null && manager.registerListener(listener, acceleration, 10_000, 0)
        if (enabled && gravitySensor != null) manager.registerListener(listener, gravitySensor, 10_000, 0)
        if (enabled && gyroscope != null) manager.registerListener(listener, gyroscope, 10_000, 0)
        if (enabled && gameRotation != null) manager.registerListener(listener, gameRotation, 10_000, 0)
        onAvailable(registered)
        onDispose { manager.unregisterListener(listener); accelDetector.reset(); gyroDetector.reset() }
    }
}
