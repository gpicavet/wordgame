package app.wordgame.trainer

import app.wordgame.engine.BeginnerBot
import app.wordgame.engine.Dawg
import app.wordgame.engine.Game
import app.wordgame.engine.GameStatus
import app.wordgame.engine.Lexicon
import app.wordgame.engine.MoveOutcome
import app.wordgame.engine.MoveRecord
import app.wordgame.engine.RuleSet
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Whole games with the real dictionary: the final score of each player is their moves plus the end bonuses. */
class FinalScoreTest {
    private val lexicon = Lexicon(Dawg.fromBytes(File("../dictionaries/bin/fr-libre.dawg").readBytes()))
    private val common = Dawg.fromBytes(File("../dictionaries/bin/fr-courant.dawg").readBytes())

    @Test
    fun finalScoresAddUp() {
        for (players in 2..4) for (g in 0 until 15) {
            val random = Random(100L * players + g)
            var state = Game.new(RuleSet.CLASSIC, players, random)
            val moves = ArrayList<MoveRecord>()
            while (state.status == GameStatus.ACTIVE) {
                val seat = state.currentPlayer
                val action = BeginnerBot(lexicon, common, random).play(state, seat)
                val outcome = Game.apply(state, seat, action, lexicon.words, random) as MoveOutcome.Applied
                state = outcome.state
                moves += outcome.record
            }
            val expected = (0 until players).map { seat ->
                moves.filter { it.seat == seat }.sumOf { it.score } + moves.sumOf { it.endAdjustments.getOrElse(seat) { 0 } }
            }
            assertEquals(expected, state.players.map { it.score }, "game $players-$g, end ${moves.last().end}")
            assertTrue(moves.dropLast(1).all { it.endAdjustments.isEmpty() })
            val best = state.players.maxOf { it.score }
            assertEquals(state.players.indices.filter { state.players[it].score == best }.singleOrNull(), state.winner)
        }
    }
}
