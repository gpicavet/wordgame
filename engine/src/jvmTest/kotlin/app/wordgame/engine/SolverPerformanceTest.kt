package app.wordgame.engine

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Real dictionary, positions from games played by the solver itself. */
class SolverPerformanceTest {
    @Test
    fun solvesRealPositionsQuickly() {
        val dawg = Dawg.fromBytes(File("../dictionaries/bin/fr-libre.dawg").readBytes())
        val lexicon = Lexicon(dawg)
        val rules = RuleSet.CLASSIC
        val solver = Solver(lexicon, rules)
        var state = Game.new(rules, 2, Random(42))
        val times = ArrayList<Long>()
        repeat(20) {
            if (state.status != GameStatus.ACTIVE) return@repeat
            val seat = state.currentPlayer
            val start = System.nanoTime()
            val best = solver.bestMoves(state.boardModel(), state.players[seat].rack, 5)
            times += (System.nanoTime() - start) / 1_000_000
            val action = best.firstOrNull()?.let { Action.Play(it.tiles) } ?: Action.Pass
            val outcome = Game.apply(state, seat, action, lexicon.words, Random(it))
            // The server must accept every move the solver proposes.
            assertTrue(outcome is MoveOutcome.Applied, "rejected: $outcome for ${best.firstOrNull()}")
            state = outcome.state
        }
        println("solver ms per position: $times, board:\n" + state.board.joinToString("\n"))
        assertTrue(times.drop(1).max() < 2000, "too slow: $times")
    }
}
