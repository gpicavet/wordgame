package app.wordgame.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolverTest {
    private val rules = RuleSet.CLASSIC
    private val dictWords = listOf(
        "AS", "CAS", "CHAT", "CHATS", "CHAS", "HA", "LA", "LAS", "OS", "SA", "SAC", "TA", "TAS", "TE", "TES", "THE", "ET", "ES",
    )
    private val lexicon = Lexicon(DawgBuilder().apply { dictWords.sorted().forEach(::add) }.build())
    private val solver = Solver(lexicon, rules)

    /** Every legal play, by trying every dictionary word at every position and direction. */
    private fun bruteForce(board: Board, rack: String): Map<String, Int> {
        val found = HashMap<String, Int>()
        for (word in dictWords) for (horizontal in listOf(true, false)) for (r in 0 until 15) for (c in 0 until 15) {
            val cells = word.indices.map { i -> if (horizontal) r to c + i else r + i to c }
            if (cells.any { (cr, cc) -> !board.inBounds(cr, cc) }) continue
            if (cells.any { (cr, cc) -> !board.isEmptyAt(cr, cc) && board.letterAt(cr, cc) != word[cells.indexOf(cr to cc)] }) continue
            // Rack must provide the missing letters (blanks for the rest).
            val left = rack.toMutableList()
            val tiles = ArrayList<Placement>()
            var ok = true
            cells.forEachIndexed { i, (cr, cc) ->
                if (!board.isEmptyAt(cr, cc)) return@forEachIndexed
                when {
                    left.remove(word[i]) -> tiles += Placement(cr, cc, word[i])
                    left.remove(BLANK) -> tiles += Placement(cr, cc, word[i], blank = true)
                    else -> ok = false
                }
            }
            if (!ok || tiles.isEmpty()) continue
            val e = MoveEvaluator.evaluate(board, tiles, rules, lexicon.words)
            if (e is Evaluation.Valid && e.words.first().word == word) {
                val key = tiles.sortedWith(compareBy({ it.row }, { it.col })).joinToString { "${it.row},${it.col},${it.letter}" }
                found[key] = maxOf(found[key] ?: 0, e.score)
            }
        }
        return found
    }

    private fun keyOf(c: Candidate) = c.tiles.sortedWith(compareBy({ it.row }, { it.col })).joinToString { "${it.row},${it.col},${it.letter}" }

    private fun assertSameAsBruteForce(board: Board, rack: String) {
        val expected = bruteForce(board, rack)
        val actual = solver.bestMoves(board, rack, limit = Int.MAX_VALUE).associate { keyOf(it) to it.score }
        assertEquals(expected, actual, "rack $rack")
    }

    @Test
    fun firstMoveFindsEveryPlayThroughTheCenter() {
        assertSameAsBruteForce(Board.empty(15), "CHATSEL")
    }

    @Test
    fun midGameFindsExtensionsHooksAndParallelPlays() {
        val board = Board.empty(15)
            .with("CHAT".mapIndexed { i, ch -> Placement(7, 6 + i, ch) })
            .with("ES".mapIndexed { i, ch -> Placement(8 + i, 8, ch) }) // a column under the A: hooks on both axes
        assertSameAsBruteForce(board, "SATLE?")
    }

    @Test
    fun bestMoveIsTheHighestScore() {
        val best = solver.bestMoves(Board.empty(15), "CHATSEL", limit = 5)
        assertEquals(5, best.size)
        assertTrue(best.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertEquals("CHATS", best.first().mainWord)
    }
}
