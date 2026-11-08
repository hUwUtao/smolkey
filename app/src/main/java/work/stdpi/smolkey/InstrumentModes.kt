package work.stdpi.smolkey

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class InstrumentModeViewState(
    val modifier: Modifier,
    val octave: Int,
    val rootNote: Int,
    val visibleNotes: Set<Int>,
    val piano: PianoController,
    val dark: Boolean,
    val keyboardZoom: Float,
    val launchpad: LaunchpadLayout,
    val gridTurns: Int,
    val flipGridRows: Boolean,
    val launchpadSurfaceKind: LaunchpadSurfaceKind,
    val onLaunchpadKeyDown: (Long, Int) -> Unit,
    val onLaunchpadKeyUp: (Long) -> Unit,
    val onPercussionHit: (PercussionSound, Int) -> Unit,
    val drumKit: Boolean,
    val drumSound: PercussionSound,
    val context: Context,
    val sensorsEnabled: Boolean,
    val sensorAvailable: Boolean,
    val sensitivity: Float,
    val onSensorAvailable: (Boolean) -> Unit,
    val onRoll: (Int?) -> Unit,
)

internal data class InstrumentModeSettingsState(
    val drumKit: Boolean,
    val onDrumKitChanged: (Boolean) -> Unit,
    val drumSound: PercussionSound,
    val onDrumSoundChanged: (PercussionSound) -> Unit,
    val sensitivity: Float,
    val onSensitivityChanged: (Float) -> Unit,
)

internal data class LaunchpadPlacementState(
    val landscape: Boolean,
    val sharedTile: Boolean,
    val launchpad: LaunchpadLayout,
    val automaticUpright: Boolean,
    val flipRows: Boolean,
    val customPlacement: Boolean,
    val surfaceKind: LaunchpadSurfaceKind,
    val onSurfaceKindChanged: (LaunchpadSurfaceKind) -> Unit,
    val onChooseTile: (String, Int, Int, Int, Int) -> Unit,
    val onUseWholeGrid: () -> Unit,
    val onAutomaticUprightChanged: (Boolean) -> Unit,
    val onFlipRowsChanged: (Boolean) -> Unit,
    val onCustomPlacementChanged: (Boolean) -> Unit,
    val onRotateMapping: () -> Unit,
    val onUpdateGrid: (LaunchpadGridField, Int) -> Unit,
    val onDismiss: () -> Unit,
)

internal enum class LaunchpadGridField(@StringRes val labelRes: Int) {
    SHARED_ROWS(R.string.shared_rows_value),
    SHARED_COLUMNS(R.string.shared_columns_value),
    TILE_ROWS(R.string.tile_rows_value),
    TILE_COLUMNS(R.string.tile_columns_value),
    TILE_ROW(R.string.tile_row_value),
    TILE_COLUMN(R.string.tile_column_value),
}

internal enum class InstrumentMode(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    val hasPlacement: Boolean = false,
    val usesPhysicalOrientation: Boolean = false,
) {
    PIANO(R.string.piano, Icons.Default.Keyboard) {
        @Composable override fun Content(state: InstrumentModeViewState) {
            PianoKeyboard(state.octave, state.rootNote, state.visibleNotes, state.piano, state.dark, state.keyboardZoom, state.modifier)
        }
    },
    LAUNCHPAD(R.string.launchpad, Icons.Default.GridView, hasPlacement = true, usesPhysicalOrientation = true) {
        @Composable override fun Content(state: InstrumentModeViewState) {
            Column(state.modifier) {
                LaunchpadSurface(
                    state.launchpad, state.gridTurns, state.flipGridRows, state.launchpadSurfaceKind,
                    state.visibleNotes, state.onLaunchpadKeyDown, state.onLaunchpadKeyUp,
                ) { state.onPercussionHit(it, 96) }
            }
        }

        @Composable override fun PlacementSettings(state: LaunchpadPlacementState) {
            val leading = stringResource(if (state.landscape) R.string.top else R.string.left)
            val trailing = stringResource(if (state.landscape) R.string.bottom else R.string.right)
            val rows = if (state.landscape) 2 else 4
            val columns = if (state.landscape) 4 else 2
            val secondRow = if (state.landscape) 2 else 0
            val secondColumn = if (state.landscape) 0 else 2
            val layout = state.launchpad
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.launchpad_placement), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.pad_surface), style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(LaunchpadSurfaceKind.KEY to R.string.keys, LaunchpadSurfaceKind.DRUM to R.string.drums).forEachIndexed { index, (kind, label) ->
                        SegmentedButton(state.surfaceKind == kind, onClick = { state.onSurfaceKindChanged(kind) }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(stringResource(label)) }
                    }
                }
                val leadingSelected = if (state.landscape) layout.tileRow == 0 else layout.tileColumn == 0
                Text(
                    if (state.sharedTile) stringResource(R.string.tile_on_phone, if (leadingSelected) leading else trailing, layout.tileRows, layout.tileColumns)
                    else stringResource(R.string.whole_grid_on_phone, layout.sharedRows, layout.sharedColumns)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { state.onChooseTile("LEADING", rows, columns, 0, 0) }, enabled = !state.sharedTile || !leadingSelected || layout.tileRows != rows || layout.tileColumns != columns) { Text(stringResource(R.string.tile_dimensions, leading, rows, columns)) }
                    Button(onClick = { state.onChooseTile("TRAILING", rows, columns, secondRow, secondColumn) }, enabled = !state.sharedTile || leadingSelected || layout.tileRows != rows || layout.tileColumns != columns) { Text(stringResource(R.string.tile_dimensions, trailing, rows, columns)) }
                }
                OutlinedButton(onClick = state.onUseWholeGrid) { Text(stringResource(R.string.use_full_grid)) }
                Row { Text(stringResource(R.string.flip_vertically)); Spacer(Modifier.weight(1f)); Switch(state.flipRows, state.onFlipRowsChanged) }
                if (state.sharedTile) {
                    Row { Text(stringResource(R.string.auto_upright)); Spacer(Modifier.weight(1f)); Switch(state.automaticUpright, state.onAutomaticUprightChanged) }
                    TextButton(onClick = { state.onCustomPlacementChanged(!state.customPlacement) }) { Text(stringResource(if (state.customPlacement) R.string.hide_custom_layout else R.string.custom_layout)) }
                    if (state.customPlacement) {
                        TextButton(onClick = state.onRotateMapping) { Text(stringResource(R.string.rotate_note_mapping)) }
                        listOf(
                            LaunchpadGridField.SHARED_ROWS to layout.sharedRows,
                            LaunchpadGridField.SHARED_COLUMNS to layout.sharedColumns,
                            LaunchpadGridField.TILE_ROWS to layout.tileRows,
                            LaunchpadGridField.TILE_COLUMNS to layout.tileColumns,
                            LaunchpadGridField.TILE_ROW to layout.tileRow + 1,
                            LaunchpadGridField.TILE_COLUMN to layout.tileColumn + 1,
                        ).forEach { (field, value) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(field.labelRes, value)); Spacer(Modifier.weight(1f))
                                TextButton(onClick = { state.onUpdateGrid(field, -1) }) { Text(stringResource(R.string.minus_symbol)) }
                                TextButton(onClick = { state.onUpdateGrid(field, 1) }) { Text(stringResource(R.string.plus_symbol)) }
                            }
                        }
                    }
                }
                TextButton(onClick = state.onDismiss) { Text(stringResource(R.string.done)) }
            }
        }
    },
    DRUM(R.string.drum, Icons.Default.GraphicEq) {
        @Composable override fun Content(state: InstrumentModeViewState) {
            Column(state.modifier) { DrumSurface(state.drumKit, state.drumSound) { state.onPercussionHit(it, 96) } }
        }

        @Composable override fun Settings(state: InstrumentModeSettingsState) {
            Row { Text(stringResource(R.string.single_pad_or_kit)); Spacer(Modifier.weight(1f)); Switch(state.drumKit, state.onDrumKitChanged) }
            PercussionSound.entries.take(4).forEach { sound ->
                TextButton(onClick = { state.onDrumSoundChanged(sound) }) { Text(stringResource(sound.labelRes)) }
            }
        }
    },
    TAMBOURINE(R.string.tambourine, Icons.Default.MusicNote) {
        @Composable override fun Content(state: InstrumentModeViewState) {
            var lastStroke by remember { mutableStateOf<FloatArray?>(null) }
            var alternatingStrokes by remember { mutableIntStateOf(0) }
            var rollStop by remember { mutableStateOf<Job?>(null) }
            val scope = rememberCoroutineScope()
            DisposableEffect(Unit) {
                onDispose { rollStop?.cancel(); state.onRoll(null) }
            }
            Column(state.modifier) {
                TambourineSensors(state.context, state.sensorsEnabled, state.sensitivity, { velocity, _, direction ->
                    state.onPercussionHit(PercussionSound.TAMBOURINE_SHAKE, velocity)
                    val previous = lastStroke
                    if (previous != null && (0..2).sumOf { (previous[it] * direction[it]).toDouble() } < 0.0) alternatingStrokes++ else alternatingStrokes = 1
                    lastStroke = direction
                    if (alternatingStrokes >= 3) state.onRoll(velocity)
                    rollStop?.cancel()
                    rollStop = scope.launch {
                        delay(180)
                        state.onRoll(null)
                        alternatingStrokes = 0
                        lastStroke = null
                    }
                }, state.onSensorAvailable)
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp))
                        .pointerInput(state.sensorsEnabled) {
                            awaitEachGesture {
                                awaitFirstDown()
                                state.onPercussionHit(PercussionSound.TAMBOURINE, 96)
                                waitForUpOrCancellation()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(if (state.sensorAvailable) R.string.shake_or_tap else R.string.tap_no_motion_sensor)) }
            }
        }

        @Composable override fun Settings(state: InstrumentModeSettingsState) {
            Text(stringResource(R.string.motion_sensitivity), style = MaterialTheme.typography.titleMedium)
            Slider(state.sensitivity, state.onSensitivityChanged, valueRange = .5f..2f)
        }
    };

    @Composable
    abstract fun Content(state: InstrumentModeViewState)

    @Composable
    open fun Settings(state: InstrumentModeSettingsState) = Unit

    @Composable
    open fun PlacementSettings(state: LaunchpadPlacementState) = Unit
}
