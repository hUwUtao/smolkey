package work.stdpi.smolkey

import java.io.ByteArrayOutputStream

internal const val FIRST_NOTE = 21
internal const val LAST_NOTE = 108
internal val WHITE_PITCH_CLASSES = setOf(0, 2, 4, 5, 7, 9, 11)

internal fun isWhite(note: Int) = note % 12 in WHITE_PITCH_CLASSES

internal fun noteName(note: Int): String =
    listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[note % 12] + (note / 12 - 1)

internal fun channelMessage(status: Int, channel: Int, note: Int, value: Int) =
    byteArrayOf((status or (channel - 1)).toByte(), note.toByte(), value.toByte())

internal fun allNotesOff(channel: Int) = byteArrayOf((0xB0 or (channel - 1)).toByte(), 123, 0)

internal data class TimedMidiMessage(val bytes: ByteArray, val milliseconds: Long)

internal data class GridCoordinate(val row: Int, val column: Int, val rows: Int, val columns: Int) {
    val cell: Int get() = row * columns + column
}

internal fun launchpadDrumSound(coordinate: GridCoordinate): PercussionSound = when {
    coordinate.row % 2 == 0 && coordinate.column % 2 == 0 -> PercussionSound.KICK
    coordinate.row % 2 == 0 -> PercussionSound.SNARE
    coordinate.column % 2 == 0 -> PercussionSound.CLOSED_HI_HAT
    else -> PercussionSound.LOW_TOM
}

/** Rotate shared-grid coordinates while leaving the on-screen pad arrangement fixed. */
internal fun mappedGridCoordinate(
    rows: Int,
    columns: Int,
    row: Int,
    column: Int,
    clockwiseTurns: Int,
): GridCoordinate {
    require(rows > 0 && columns > 0 && row in 0 until rows && column in 0 until columns)
    return when (clockwiseTurns.mod(4)) {
        0 -> GridCoordinate(row, column, rows, columns)
        1 -> GridCoordinate(column, rows - 1 - row, columns, rows)
        2 -> GridCoordinate(rows - 1 - row, columns - 1 - column, rows, columns)
        else -> GridCoordinate(columns - 1 - column, row, columns, rows)
    }
}

internal fun mappedTileCoordinate(
    layout: LaunchpadLayout,
    localRow: Int,
    localColumn: Int,
    clockwiseTurns: Int,
    flipRows: Boolean = false,
): GridCoordinate {
    require(localRow in 0 until layout.tileRows && localColumn in 0 until layout.tileColumns)
    val mappedLocalRow = if (flipRows) layout.tileRows - 1 - localRow else localRow
    return mappedGridCoordinate(
        layout.sharedRows,
        layout.sharedColumns,
        layout.tileRow + mappedLocalRow,
        layout.tileColumn + localColumn,
        clockwiseTurns,
    )
}

/** Combines clockwise physical orientation with Android's graphics rotation. */
internal fun uprightGridTurns(physicalQuarterTurns: Int, displayQuarterTurns: Int): Int =
    (physicalQuarterTurns + displayQuarterTurns).mod(4)

internal data class LaunchpadLayout(
    val sharedRows: Int = 4,
    val sharedColumns: Int = 4,
    val firstNote: Int = 36,
    val tileRow: Int = 0,
    val tileColumn: Int = 0,
    val tileRows: Int = 4,
    val tileColumns: Int = 4,
    val overrides: Map<Int, Int> = emptyMap(),
) {
    fun isValid(): Boolean = sharedRows > 0 && sharedColumns > 0 &&
        sharedRows.toLong() * sharedColumns <= 128 - firstNote && firstNote in 0..127 &&
        tileRows > 0 && tileColumns > 0 && tileRow >= 0 && tileColumn >= 0 &&
        tileRow.toLong() + tileRows <= sharedRows && tileColumn.toLong() + tileColumns <= sharedColumns &&
        overrides.all { (cell, note) -> cell in 0 until sharedRows * sharedColumns && note in 0..127 }

    fun noteAt(localRow: Int, localColumn: Int): Int {
        require(isValid() && localRow in 0 until tileRows && localColumn in 0 until tileColumns)
        val globalCell = (tileRow + localRow) * sharedColumns + tileColumn + localColumn
        return overrides[globalCell] ?: firstNote + globalCell
    }

    fun noteAt(coordinate: GridCoordinate): Int =
        overrides[coordinate.cell] ?: firstNote + coordinate.cell
}

internal data class MotionStroke(val timestampNs: Long, val strength: Float, val direction: FloatArray)

/** Reversal detector. Input samples already have gravity removed and a short low-pass applied. */
internal class TambourineStrokeDetector(
    private val threshold: Float = 4f,
    private val refractoryMs: Long = 40,
) {
    private var previous = FloatArray(3)
    private var armed = true
    private var lastHitNs = Long.MIN_VALUE

    fun sample(timestampNs: Long, acceleration: FloatArray): MotionStroke? {
        if (acceleration.size < 3) return null
        val magnitude = kotlin.math.sqrt(acceleration.take(3).sumOf { (it * it).toDouble() }).toFloat()
        val dot = (0..2).sumOf { (acceleration[it] * previous[it]).toDouble() }.toFloat()
        val previousMagnitude = kotlin.math.sqrt(previous.sumOf { (it * it).toDouble() }).toFloat()
        val reversed = previousMagnitude >= threshold && magnitude >= threshold && dot < -0.45f * magnitude * previousMagnitude
        val hit = magnitude >= threshold && (armed || reversed) &&
            (lastHitNs == Long.MIN_VALUE || timestampNs - lastHitNs >= refractoryMs * 1_000_000L)
        if (magnitude < threshold * 0.45f) armed = true
        if (hit) {
            armed = false
            lastHitNs = timestampNs
        }
        previous = acceleration.copyOfRange(0, 3)
        return if (hit) MotionStroke(timestampNs, magnitude, acceleration.copyOfRange(0, 3)) else null
    }

    fun reset() {
        previous.fill(0f)
        armed = true
        lastHitNs = Long.MIN_VALUE
    }
}

/** BLE MIDI packets with a fresh 13-bit millisecond timestamp for each event. */
internal fun bleMidiPackets(messages: List<ByteArray>, milliseconds: Long): List<ByteArray> {
    return bleMidiPackets(messages.map { TimedMidiMessage(it, milliseconds) })
}

internal fun bleMidiPackets(messages: List<TimedMidiMessage>): List<ByteArray> {
    val packets = mutableListOf<ByteArray>()
    var packet = ByteArrayOutputStream()
    var timestamp = -1
    for ((message, milliseconds) in messages) {
        require(message.size == 3)
        val time = (milliseconds % 8192).toInt()
        val high = 0x80 or ((time shr 7) and 0x3F)
        val low = 0x80 or (time and 0x7F)
        if (packet.size() + 5 > 20 || (timestamp != -1 && timestamp != high)) {
            packets += packet.toByteArray()
            packet = ByteArrayOutputStream()
            timestamp = -1
        }
        if (timestamp == -1) {
            packet.write(high)
            timestamp = high
        }
        packet.write(low)
        message.forEach { packet.write(it.toInt() and 255) }
    }
    if (packet.size() > 0) packets += packet.toByteArray()
    return packets
}

/** Notes stay down while any pointer that started them remains over the key. */
internal class NoteOwners(private val send: (Int, Boolean) -> Unit) {
    private val pointers = mutableMapOf<Long, Int>()
    private val owners = mutableMapOf<Int, Int>()

    fun move(pointer: Long, note: Int?) {
        val previous = pointers[pointer]
        if (previous == note) return
        if (previous != null) {
            val remaining = (owners[previous] ?: 1) - 1
            if (remaining == 0) {
                owners.remove(previous)
                send(previous, false)
            } else owners[previous] = remaining
        }
        if (note == null) pointers.remove(pointer) else {
            pointers[pointer] = note
            val count = owners[note] ?: 0
            owners[note] = count + 1
            if (count == 0) send(note, true)
        }
    }

    fun releaseAll() {
        owners.keys.toList().forEach { send(it, false) }
        owners.clear()
        pointers.clear()
    }
}
