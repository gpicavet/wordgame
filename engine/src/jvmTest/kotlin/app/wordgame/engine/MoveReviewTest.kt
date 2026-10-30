package app.wordgame.engine

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoveReviewTest {
    private val lexicon = Lexicon(Dawg.fromBytes(File("../dictionaries/bin/fr-libre.dawg").readBytes()))
    private val weights = Afterstate.parseWeights(File("../models/value-v2.txt").readText())

    @Test
    fun iroGetsTopGradesAndTheGreedyBotLess() {
        val iro = ValueBot(lexicon, weights, Random(1))
        val greedy = ValueBot(lexicon, DoubleArray(Afterstate.size), Random(2))
        val grades = mapOf(0 to ArrayList<Int>(), 1 to ArrayList())
        val tips = HashMap<TipKind, Int>()
        repeat(3) { g ->
            var state = Game.new(RuleSet.CLASSIC, 2, Random(g))
            while (state.status == GameStatus.ACTIVE) {
                val seat = state.currentPlayer
                val decision = (if (seat == 0) iro else greedy).decide(state, seat)
                val action = decision.action
                if (action is Action.Play) {
                    val review = MoveReview.of(
                        state.boardModel(), state.players[seat].rack, action.tiles, decision.score, state.bag.length,
                        state.rules, lexicon, weights,
                    )
                    grades.getValue(seat) += review.grade
                    review.tips.forEach { tips.merge(it.kind, 1, Int::plus) }
                }
                state = (Game.apply(state, seat, action, lexicon.words, Random(g)) as MoveOutcome.Applied).state
            }
        }
        val iroAverage = grades.getValue(0).average()
        val greedyAverage = grades.getValue(1).average()
        println("IroBot ${"%.2f".format(iroAverage)}, greedy ${"%.2f".format(greedyAverage)} " +
            "${grades.getValue(1).groupingBy { it }.eachCount().toSortedMap()}, tips $tips")
        assertTrue(iroAverage > 9.5, "IroBot: $iroAverage")
        assertTrue(greedyAverage < iroAverage - 0.3, "greedy: $greedyAverage")
    }

    @Test
    fun theBeginnerGetsLowerGrades() {
        val common = Dawg.fromBytes(File("../dictionaries/bin/fr-courant.dawg").readBytes())
        val beginner = BeginnerBot(lexicon, common, Random(3))
        val grades = ArrayList<Int>()
        val tips = HashMap<TipKind, Int>()
        repeat(3) { g ->
            var state = Game.new(RuleSet.CLASSIC, 2, Random(10 + g))
            while (state.status == GameStatus.ACTIVE) {
                val seat = state.currentPlayer
                val action = beginner.play(state, seat)
                val before = state
                val outcome = Game.apply(state, seat, action, lexicon.words, Random(g)) as MoveOutcome.Applied
                if (action is Action.Play) {
                    val review = MoveReview.of(
                        before.boardModel(), before.players[seat].rack, action.tiles, outcome.record.score, before.bag.length,
                        before.rules, lexicon, weights,
                    )
                    grades += review.grade
                    review.tips.forEach { tips.merge(it.kind, 1, Int::plus) }
                    assertEquals(-review.loss, review.criteria.sumOf { it.second }, 1e-6)
                }
                state = outcome.state
            }
        }
        println("beginner ${"%.2f".format(grades.average())} ${grades.groupingBy { it }.eachCount().toSortedMap()}, tips $tips")
        assertTrue(grades.average() < 7, "beginner: ${grades.average()}")
    }

    @Test
    fun theBestMoveGetsTen() {
        val state = Game.new(RuleSet.CLASSIC, 2, Random(5))
        val decision = ValueBot(lexicon, weights, Random(5)).decide(state, 0)
        val review = MoveReview.of(
            state.boardModel(), state.players[0].rack, (decision.action as Action.Play).tiles, decision.score,
            state.bag.length, state.rules, lexicon, weights,
        )
        assertEquals(10, review.grade)
        assertEquals(emptyList(), review.tips)
    }
}
