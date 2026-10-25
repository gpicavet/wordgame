package app.wordgame.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NeuralBotTest {
    @Test
    fun networkSurvivesSerialization() {
        val net = Mlp.random(intArrayOf(RawAfterstate.SIZE, 8, 1), 50.0, Random(3))
        net.weights.last().indices.forEach { net.weights.last()[it] = 0.1 * it }
        val x = DoubleArray(RawAfterstate.SIZE) { (it % 7) / 7.0 }
        val back = Mlp.fromBytes(net.toBytes())
        assertEquals(net.evaluate(x), back.evaluate(x), 1e-3)
    }

    @Test
    fun boardSymmetriesKeepTheLayoutAndAreDistinct() {
        val rules = RuleSet.CLASSIC
        val board = Board.empty(15).with(listOf(Placement(7, 7, 'A'), Placement(7, 8, 'B'), Placement(3, 1, 'C')))
        val x = RawAfterstate.encode(board, "QE", 50, 10, rules)
        val images = (0 until 8).map { RawAfterstate.symmetric(x, it).toList() }
        assertEquals(8, images.toSet().size)
        // Every image of an occupied square is a square with the same premium.
        for (s in 0 until 8) for (cell in 0 until 225) {
            val probe = DoubleArray(RawAfterstate.SIZE).also { it[RawAfterstate.CELLS + cell] = 1.0 }
            val target = RawAfterstate.symmetric(probe, s).indexOfFirst { it == 1.0 } - RawAfterstate.CELLS
            assertEquals(rules.layout.premiumAt(cell / 15, cell % 15), rules.layout.premiumAt(target / 15, target % 15))
        }
        assertTrue(images.all { it.subList(0, RawAfterstate.CELLS) == x.toList().subList(0, RawAfterstate.CELLS) })
    }
}
