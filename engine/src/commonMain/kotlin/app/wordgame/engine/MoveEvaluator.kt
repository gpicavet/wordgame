package app.wordgame.engine

import kotlinx.serialization.Serializable

@Serializable
data class FormedWord(val word: String, val score: Int)

enum class InvalidReason {
    NO_TILES,
    OUT_OF_BOARD,
    INVALID_LETTER,
    OCCUPIED,
    DUPLICATE_SQUARE,
    NOT_IN_LINE,
    GAP,
    FIRST_MOVE_NOT_ON_CENTER,
    NOT_CONNECTED,
    NO_WORD,
    UNKNOWN_WORDS,
    TILES_NOT_IN_RACK,
}

sealed interface Evaluation {
    data class Valid(val words: List<FormedWord>, val score: Int, val bingo: Boolean) : Evaluation
    data class Invalid(val reason: InvalidReason, val unknownWords: List<String> = emptyList()) : Evaluation
}

/**
 * Checks the geometry of a play, finds every word it forms, and scores it.
 * Used by the server as the authority and by the app for the live score preview.
 * Rack ownership is checked separately by [Game].
 */
object MoveEvaluator {

    fun evaluate(board: Board, placements: List<Placement>, rules: RuleSet, words: WordList?): Evaluation {
        geometryError(board, placements)?.let { return Evaluation.Invalid(it) }

        val after = board.with(placements)
        val placed = placements.associateBy { it.row to it.col }
        val horizontal = isHorizontal(board, placements)

        val spans = buildList {
            val first = placements.first()
            add(wordSpan(after, first.row, first.col, horizontal))
            for (p in placements) add(wordSpan(after, p.row, p.col, !horizontal))
        }.filter { it.length >= 2 }

        if (spans.isEmpty()) return Evaluation.Invalid(InvalidReason.NO_WORD)

        val formed = spans.map { span -> FormedWord(span.text(after), span.score(after, placed, rules)) }
        if (words != null) {
            val unknown = formed.map { it.word }.filterNot(words::contains).distinct()
            if (unknown.isNotEmpty()) return Evaluation.Invalid(InvalidReason.UNKNOWN_WORDS, unknown)
        }
        val bingo = placements.size == rules.rackSize
        val score = formed.sumOf { it.score } + if (bingo) rules.bingoBonus else 0
        return Evaluation.Valid(formed, score, bingo)
    }

    private fun geometryError(board: Board, placements: List<Placement>): InvalidReason? {
        if (placements.isEmpty()) return InvalidReason.NO_TILES
        if (placements.any { !board.inBounds(it.row, it.col) }) return InvalidReason.OUT_OF_BOARD
        if (placements.any { it.letter !in 'A'..'Z' }) return InvalidReason.INVALID_LETTER
        if (placements.distinctBy { it.row to it.col }.size != placements.size) return InvalidReason.DUPLICATE_SQUARE
        if (placements.any { !board.isEmptyAt(it.row, it.col) }) return InvalidReason.OCCUPIED

        val sameRow = placements.all { it.row == placements[0].row }
        val sameCol = placements.all { it.col == placements[0].col }
        if (!sameRow && !sameCol) return InvalidReason.NOT_IN_LINE

        // Every square between the first and last placed tile must be filled.
        val after = board.with(placements)
        if (sameRow) {
            val row = placements[0].row
            val range = placements.minOf { it.col }..placements.maxOf { it.col }
            if (range.any { after.isEmptyAt(row, it) }) return InvalidReason.GAP
        } else {
            val col = placements[0].col
            val range = placements.minOf { it.row }..placements.maxOf { it.row }
            if (range.any { after.isEmptyAt(it, col) }) return InvalidReason.GAP
        }

        if (board.isEmpty()) {
            val c = board.size / 2
            if (placements.none { it.row == c && it.col == c }) return InvalidReason.FIRST_MOVE_NOT_ON_CENTER
        } else {
            val touches = placements.any { p ->
                NEIGHBOURS.any { (dr, dc) ->
                    val r = p.row + dr
                    val col = p.col + dc
                    board.inBounds(r, col) && !board.isEmptyAt(r, col)
                }
            }
            if (!touches) return InvalidReason.NOT_CONNECTED
        }
        return null
    }

    /** Direction of the main word. A single tile follows the direction where it touches other tiles. */
    private fun isHorizontal(board: Board, placements: List<Placement>): Boolean {
        if (placements.size > 1) return placements.all { it.row == placements[0].row }
        val p = placements[0]
        fun filled(r: Int, c: Int) = board.inBounds(r, c) && !board.isEmptyAt(r, c)
        return filled(p.row, p.col - 1) || filled(p.row, p.col + 1) ||
            !(filled(p.row - 1, p.col) || filled(p.row + 1, p.col))
    }

    private class Span(val row: Int, val col: Int, val horizontal: Boolean, val length: Int) {
        fun squares() = (0 until length).map { i -> if (horizontal) row to col + i else row + i to col }

        fun text(board: Board) = squares().map { (r, c) -> board.letterAt(r, c) }.joinToString("")

        fun score(board: Board, placed: Map<Pair<Int, Int>, Placement>, rules: RuleSet): Int {
            var sum = 0
            var wordMultiplier = 1
            for ((r, c) in squares()) {
                val cell = board[r, c]
                val letterPoints = if (cell.isLowerCase()) 0 else rules.points(cell)
                if ((r to c) in placed) {
                    when (rules.layout.premiumAt(r, c)) {
                        Premium.DOUBLE_LETTER -> sum += letterPoints * 2
                        Premium.TRIPLE_LETTER -> sum += letterPoints * 3
                        Premium.DOUBLE_WORD -> { sum += letterPoints; wordMultiplier *= 2 }
                        Premium.TRIPLE_WORD -> { sum += letterPoints; wordMultiplier *= 3 }
                        Premium.NONE -> sum += letterPoints
                    }
                } else {
                    sum += letterPoints
                }
            }
            return sum * wordMultiplier
        }
    }

    private fun wordSpan(board: Board, row: Int, col: Int, horizontal: Boolean): Span {
        val dr = if (horizontal) 0 else 1
        val dc = if (horizontal) 1 else 0
        var r = row
        var c = col
        while (board.inBounds(r - dr, c - dc) && !board.isEmptyAt(r - dr, c - dc)) { r -= dr; c -= dc }
        var length = 0
        while (board.inBounds(r + dr * length, c + dc * length) && !board.isEmptyAt(r + dr * length, c + dc * length)) length++
        return Span(r, c, horizontal, length)
    }

    private val NEIGHBOURS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
}
