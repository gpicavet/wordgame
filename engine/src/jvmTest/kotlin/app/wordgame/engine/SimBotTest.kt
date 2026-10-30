package app.wordgame.engine

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimBotTest {
    private val lexicon = Lexicon(Dawg.fromBytes(File("../dictionaries/bin/fr-libre.dawg").readBytes()))
    private val weights = Afterstate.parseWeights(File("../models/value-v2.txt").readText())

    @Test
    fun playsAWholeGameOfLegalMoves() {
        val sim = SimBot(lexicon, weights, Random(1))
        val iro = ValueBot(lexicon, weights, Random(2))
        var state = Game.new(RuleSet.CLASSIC, 2, Random(3))
        var moves = 0
        val start = System.nanoTime()
        while (state.status == GameStatus.ACTIVE) {
            val seat = state.currentPlayer
            val action = if (seat == 0) sim.decide(state, seat, allowBingo = moves % 2 == 0).action else iro.decide(state, seat).action
            val outcome = Game.apply(state, seat, action, lexicon.words, Random(4))
            assertTrue(outcome is MoveOutcome.Applied, "$action: $outcome")
            state = outcome.state
            if (seat == 0) moves++
        }
        println("SimBot: %.2f s a move".format((System.nanoTime() - start) / 1e9 / moves))
    }

    @Test
    fun neverPlaysAScrabbleWhenNotAllowed() {
        val sim = SimBot(lexicon, weights, Random(1))
        val state = Game.new(RuleSet.CLASSIC, 2, Random(1)).let { s ->
            s.copy(players = s.players.mapIndexed { i, p -> if (i == 0) p.copy(rack = "AEINRST") else p }, currentPlayer = 0)
        }
        val play = sim.decide(state, 0, allowBingo = false).action as Action.Play
        assertTrue(play.tiles.size < 7)
    }

    @Test
    fun playsAsTheValueBotWithMorePlayers() {
        val state = Game.new(RuleSet.CLASSIC, 3, Random(5))
        val sim = SimBot(lexicon, weights, Random(1)).decide(state, state.currentPlayer).action
        val iro = ValueBot(lexicon, weights, Random(1)).decide(state, state.currentPlayer).action
        assertEquals(iro, sim)
    }
}
