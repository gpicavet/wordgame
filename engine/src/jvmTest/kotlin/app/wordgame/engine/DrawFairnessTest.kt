package app.wordgame.engine

import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.asKotlinRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Draws must follow the bag composition, with the same random source as the server. */
class DrawFairnessTest {
    private val random = SecureRandom().asKotlinRandom()
    private val games = 50_000

    @Test
    fun firstRacksFollowTheBagComposition() {
        for (rules in listOf(RuleSet.CLASSIC, RuleSet.FRENCH)) {
            val bag = rules.fullBag()
            val expected = bag.groupingBy { it }.eachCount()
            val seen = HashMap<Char, Int>()
            repeat(games) {
                Game.new(rules, 2, random).players.forEach { p -> p.rack.forEach { seen.merge(it, 1, Int::plus) } }
            }
            val drawn = games * 2 * rules.rackSize
            for ((tile, count) in expected) {
                val p = count.toDouble() / bag.length
                val mean = drawn * p
                val sigma = sqrt(drawn * p * (1 - p))
                val actual = seen[tile] ?: 0
                // 5 sigma: a correct shuffle fails this about once in 3 million letters.
                assertTrue(abs(actual - mean) < 5 * sigma, "${rules.id} $tile: $actual drawn, expected ${mean.toInt()} ± ${sigma.toInt()}")
            }
        }
    }

    @Test
    fun everyRackPositionIsEquallyRandom() {
        // Position bias (e.g. the first rack tile is more often an A) would mean a broken shuffle.
        val rules = RuleSet.CLASSIC
        val eAtPosition = IntArray(rules.rackSize)
        repeat(games) { Game.new(rules, 2, random).players[0].rack.forEachIndexed { i, c -> if (c == 'E') eAtPosition[i]++ } }
        val p = 15.0 / 102
        val mean = games * p
        val sigma = sqrt(games * p * (1 - p))
        eAtPosition.forEachIndexed { i, n -> assertTrue(abs(n - mean) < 5 * sigma, "E at position $i: $n, expected ${mean.toInt()}") }
    }

    @Test
    fun tilesAreConservedThroughExchanges() {
        val rules = RuleSet.CLASSIC
        var state = Game.new(rules, 2, random)
        repeat(200) {
            val seat = state.currentPlayer
            val outcome = Game.apply(state, seat, Action.Exchange(state.players[seat].rack.take(3)), WordList { true }, random)
            state = (outcome as MoveOutcome.Applied).state.copy(scorelessTurns = 0)
            val all = (state.bag + state.players.joinToString("") { it.rack }).toList().sorted()
            assertEquals(rules.fullBag().toList().sorted(), all)
        }
    }
}
