package app.wordgame.engine

/** Symbol used for a blank tile in racks and bags. */
const val BLANK = '?'

enum class Premium { NONE, DOUBLE_LETTER, TRIPLE_LETTER, DOUBLE_WORD, TRIPLE_WORD }

class LetterSpec(val count: Int, val points: Int)

/**
 * Premium squares. The layout is our own (not the one of any commercial word game):
 * it is defined on one octant and mirrored on both axes and on the diagonal.
 */
class BoardLayout(val size: Int, private val octant: Map<Pair<Int, Int>, Premium>) {
    val center = size / 2

    fun premiumAt(row: Int, col: Int): Premium {
        val r = minOf(row, size - 1 - row)
        val c = minOf(col, size - 1 - col)
        return octant[minOf(r, c) to maxOf(r, c)] ?: Premium.NONE
    }
}

class RuleSet(
    val id: String,
    val layout: BoardLayout,
    val letters: Map<Char, LetterSpec>,
    val blankCount: Int,
    val rackSize: Int = 7,
    val bingoBonus: Int,
    /** Consecutive passes/exchanges (all players together) that end the game. */
    val maxScorelessTurns: Int = 6,
) {
    val boardSize get() = layout.size

    fun points(tile: Char): Int = if (tile == BLANK) 0 else letters.getValue(tile).points

    fun fullBag(): String = buildString {
        letters.forEach { (letter, spec) -> repeat(spec.count) { append(letter) } }
        repeat(blankCount) { append(BLANK) }
    }

    companion object {
        private val all = mutableMapOf<String, RuleSet>()

        fun byId(id: String): RuleSet = all[id] ?: error("Unknown rule set $id")

        /**
         * French rules, own distribution (104 tiles) and values.
         * To be tuned with play statistics once bots exist.
         */
        val FRENCH = RuleSet(
            id = "fr-v1",
            layout = BoardLayout(
                size = 15,
                octant = mapOf(
                    (0 to 0) to Premium.TRIPLE_LETTER,
                    (1 to 6) to Premium.TRIPLE_LETTER,
                    (4 to 4) to Premium.TRIPLE_LETTER,
                    (0 to 7) to Premium.DOUBLE_LETTER,
                    (2 to 3) to Premium.DOUBLE_LETTER,
                    (5 to 6) to Premium.DOUBLE_LETTER,
                    (1 to 3) to Premium.DOUBLE_WORD,
                    (6 to 6) to Premium.DOUBLE_WORD,
                    (0 to 4) to Premium.TRIPLE_WORD,
                ),
            ),
            letters = mapOf(
                'A' to LetterSpec(10, 1), 'B' to LetterSpec(2, 3), 'C' to LetterSpec(3, 3),
                'D' to LetterSpec(3, 2), 'E' to LetterSpec(14, 1), 'F' to LetterSpec(2, 4),
                'G' to LetterSpec(2, 3), 'H' to LetterSpec(2, 4), 'I' to LetterSpec(8, 1),
                'J' to LetterSpec(1, 8), 'K' to LetterSpec(1, 10), 'L' to LetterSpec(5, 2),
                'M' to LetterSpec(3, 2), 'N' to LetterSpec(7, 1), 'O' to LetterSpec(6, 1),
                'P' to LetterSpec(2, 3), 'Q' to LetterSpec(1, 8), 'R' to LetterSpec(6, 1),
                'S' to LetterSpec(7, 1), 'T' to LetterSpec(6, 1), 'U' to LetterSpec(5, 2),
                'V' to LetterSpec(2, 5), 'W' to LetterSpec(1, 10), 'X' to LetterSpec(1, 9),
                'Y' to LetterSpec(1, 9), 'Z' to LetterSpec(1, 9),
            ),
            blankCount = 2,
            bingoBonus = 40,
        )

        init {
            all[FRENCH.id] = FRENCH
        }
    }
}
