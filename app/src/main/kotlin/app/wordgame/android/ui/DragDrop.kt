package app.wordgame.android.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Where a dragged tile comes from. */
sealed interface DragSource {
    data class Rack(val index: Int) : DragSource
    /** A tile put on the board this turn (tiles already played cannot move). */
    data class Board(val row: Int, val col: Int) : DragSource
}

/**
 * Drag-and-drop of tiles, everything in root coordinates.
 * Drop targets publish their bounds; the dragged tile is drawn by the screen above everything else.
 */
class DragState {
    var source by mutableStateOf<DragSource?>(null)
        private set
    var letter by mutableStateOf(' ')
        private set
    /** Finger position. */
    var position by mutableStateOf(Offset.Zero)
        private set

    var boardBounds: Rect? = null
    val rackSlots = mutableMapOf<Int, Rect>()

    fun isDragging(s: DragSource) = source == s

    fun start(s: DragSource, l: Char, at: Offset) {
        source = s; letter = l; position = at
    }

    fun moveTo(at: Offset) {
        position = at
    }

    fun end() {
        source = null
    }

    fun boardCell(p: Offset, size: Int): Pair<Int, Int>? {
        val b = boardBounds ?: return null
        if (!b.contains(p)) return null
        val row = ((p.y - b.top) / b.height * size).toInt().coerceIn(0, size - 1)
        val col = ((p.x - b.left) / b.width * size).toInt().coerceIn(0, size - 1)
        return row to col
    }

    /** Rack slot under [p], or the nearest one horizontally when [p] is at the rack's height. */
    fun rackSlot(p: Offset): Int? {
        if (rackSlots.isEmpty()) return null
        val top = rackSlots.values.minOf { it.top }
        val bottom = rackSlots.values.maxOf { it.bottom }
        if (p.y !in (top - 24f)..(bottom + 24f)) return null
        return rackSlots.minBy { (_, r) -> kotlin.math.abs(r.center.x - p.x) }.key
    }
}

fun Modifier.dropTargetBounds(onBounds: (Rect) -> Unit): Modifier =
    onGloballyPositioned { onBounds(it.boundsInRoot()) }

/** Makes the element draggable; [onDrop] receives the finger position in root coordinates. */
fun Modifier.dragSource(
    state: DragState,
    source: DragSource,
    letter: Char,
    enabled: Boolean,
    onDrop: (DragSource, Offset) -> Unit,
): Modifier = composed {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentLetter by rememberUpdatedState(letter)
    onGloballyPositioned { coordinates = it }
        .pointerInput(source, enabled) {
            if (!enabled) return@pointerInput
            // If the gesture is interrupted (recomposition, element removed), no callback runs: clean up here.
            try {
                detectDragGestures(
                    onDragStart = { offset ->
                        val origin = coordinates?.localToRoot(offset) ?: return@detectDragGestures
                        state.start(source, currentLetter, origin)
                    },
                    // Absolute position rather than summed deltas: no drift if move events are dropped.
                    onDrag = { change, _ ->
                        change.consume()
                        coordinates?.let { state.moveTo(it.localToRoot(change.position)) }
                    },
                    onDragEnd = {
                        if (state.source == source) currentOnDrop(source, state.position)
                        state.end()
                    },
                    onDragCancel = { state.end() },
                )
            } finally {
                if (state.source == source) state.end()
            }
        }
}
