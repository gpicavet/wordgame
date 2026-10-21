package app.wordgame.engine

import kotlinx.serialization.Serializable

/** A tile put on the board this turn. [blank] tiles score 0 and carry the letter they stand for. */
@Serializable
data class Placement(val row: Int, val col: Int, val letter: Char, val blank: Boolean = false)

/**
 * Immutable board. Each cell is [EMPTY], an uppercase letter, or a lowercase letter for a blank.
 * Serialized as one string per row, which keeps game states small and readable.
 */
class Board private constructor(val size: Int, private val cells: CharArray) {

    operator fun get(row: Int, col: Int): Char = cells[row * size + col]

    fun inBounds(row: Int, col: Int) = row in 0 until size && col in 0 until size

    fun isEmptyAt(row: Int, col: Int) = this[row, col] == EMPTY

    fun isEmpty() = cells.all { it == EMPTY }

    fun letterAt(row: Int, col: Int): Char = this[row, col].uppercaseChar()

    fun with(placements: List<Placement>): Board {
        val copy = cells.copyOf()
        for (p in placements) copy[p.row * size + p.col] = if (p.blank) p.letter.lowercaseChar() else p.letter
        return Board(size, copy)
    }

    fun toRows(): List<String> = (0 until size).map { r -> cells.concatToString(r * size, (r + 1) * size) }

    companion object {
        const val EMPTY = '.'

        fun empty(size: Int) = Board(size, CharArray(size * size) { EMPTY })

        fun fromRows(rows: List<String>): Board {
            val size = rows.size
            require(rows.all { it.length == size }) { "Board must be square" }
            return Board(size, rows.joinToString("").toCharArray())
        }
    }
}
