package work.stdpi.smolkey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun PianoKeyboard(
    octave: Int,
    rootNote: Int,
    pressed: Set<Int>,
    owners: PianoController,
    dark: Boolean,
    keyboardZoom: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.padding(top = 8.dp)) {
        val visibleWhiteKeys = (keyboardZoom * 7).roundToInt().coerceIn(7, 28)
        val first = (octave * 12 + 12).coerceIn(24, 96)
        val start = if (visibleWhiteKeys > 7) (first - 12).coerceAtLeast(12) else first
        val semitones = (visibleWhiteKeys * 12f / 7f).roundToInt()
        val notes = (start until (start + semitones)).filter { it in FIRST_NOTE..LAST_NOTE }
        val whites = notes.filter(::isWhite)
        val blackPositions = notes.filterNot(::isWhite).associateWith { note ->
            (start until note).count(::isWhite)
        }
        val rangeDescription = stringResource(R.string.piano_range, noteName(notes.first()), noteName(notes.last()))
        val pressedDescription = if (pressed.isEmpty()) {
            stringResource(R.string.no_keys_pressed)
        } else {
            pressed.sorted().joinToString(stringResource(R.string.note_list_separator), transform = ::noteName)
        }
        val playActions = notes.map { note ->
            CustomAccessibilityAction(stringResource(R.string.play_sound, noteName(note))) {
                owners.move(Long.MIN_VALUE + note, note)
                owners.move(Long.MIN_VALUE + note, null)
                true
            }
        }
        val surface = Modifier.fillMaxSize().semantics {
            contentDescription = rangeDescription
            stateDescription = pressedDescription
            customActions = playActions
        }.pointerInput(owners, notes, constraints) {
            fun hit(position: Offset): Int? {
                if (position.x < 0f || position.x >= size.width ||
                    position.y < 0f || position.y >= size.height
                ) return null

                val keyPosition = position.x / (size.width.toFloat() / whites.size)
                if (position.y < size.height * BLACK_KEY_HEIGHT) {
                    val black = blackPositions.entries.firstOrNull { (_, boundary) ->
                        keyPosition >= boundary - BLACK_KEY_WIDTH / 2 &&
                            keyPosition < boundary + BLACK_KEY_WIDTH / 2
                    }
                    if (black != null) return black.key
                }
                return whites.getOrNull(keyPosition.toInt())
            }

            awaitEachGesture {
                val heldPointers = mutableSetOf<Long>()
                try {
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            val id = change.id.value
                            if (change.pressed && (!change.previousPressed || id in heldPointers)) {
                                heldPointers += id
                                owners.move(id, hit(change.position))
                                change.consume()
                            } else if (heldPointers.remove(id)) {
                                owners.move(id, null)
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                } finally {
                    heldPointers.forEach { owners.move(it, null) }
                }
            }
        }
        Canvas(surface) {
            val whiteWidthPx = size.width / whites.size
            val blackWidth = whiteWidthPx * BLACK_KEY_WIDTH
            val gap = 2.dp.toPx()
            whites.forEachIndexed { index, note ->
                val x = index * whiteWidthPx
                val keyColor = when {
                    note in pressed -> if (dark) Color(0xFF7ACDE0) else Color(0xFF9CDBEA)
                    note == rootNote -> if (dark) Color(0xFF345C68) else Color(0xFFCDECF3)
                    else -> if (dark) Color(0xFFD9E4E7) else Color(0xFFFBFDFE)
                }
                drawRoundRect(keyColor, Offset(x + gap, 0f), Size(whiteWidthPx - gap * 2, size.height), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                drawRoundRect(if (dark) Color(0xFF52646A) else Color(0xFFB9C8CC), Offset(x + gap, size.height - 5.dp.toPx()), Size(whiteWidthPx - gap * 2, 5.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(0f))
            }
            blackPositions.forEach { (note, preceding) ->
                val x = preceding * whiteWidthPx - blackWidth / 2
                drawRoundRect(if (note in pressed) Color(0xFF007C96) else if (dark) Color(0xFF17262D) else Color(0xFF26373E), Offset(x, 0f), Size(blackWidth, size.height * BLACK_KEY_HEIGHT), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx(), 5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxSize()) {
            whites.forEach { note ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    if (note % 12 == 0) {
                        val keyDescription = stringResource(R.string.piano_key, noteName(note))
                        Text(
                            text = noteName(note),
                            modifier = Modifier.padding(bottom = 14.dp).semantics { contentDescription = keyDescription },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (note == rootNote && dark) Color(0xFFE8FBFF) else if (note == rootNote) Color(0xFF00566A) else Color(0xFF304950),
                            fontWeight = if (note == rootNote) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

private const val BLACK_KEY_WIDTH = .60f
private const val BLACK_KEY_HEIGHT = .62f
