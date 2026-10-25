package app.wordgame.trainer

import app.wordgame.engine.Mlp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class AdamTrainerTest {
    /** Squared-error loss on one sample, in output units (as minimized by the trainer). */
    private fun loss(net: Mlp, x: DoubleArray, target: Double): Double {
        val d = net.evaluate(x) / net.scale - target / net.scale
        return d * d / 2
    }

    @Test
    fun backpropagationMatchesNumericalGradient() {
        val random = Random(1)
        val net = Mlp.random(intArrayOf(6, 5, 4, 1), 10.0, random)
        net.weights.last().indices.forEach { net.weights.last()[it] = random.nextDouble(-0.5, 0.5) }
        val x = DoubleArray(6) { if (it == 2) 0.0 else random.nextDouble(-1.0, 1.0) }
        val target = 7.0
        // Plain gradient descent with Adam on the first step: each parameter moves by -lr * sign(gradient).
        for (l in 0 until net.layers) for (i in net.weights[l].indices step 3) {
            val w = net.weights[l]
            val saved = w[i]
            w[i] = saved + 1e-6; val up = loss(net, x, target)
            w[i] = saved - 1e-6; val down = loss(net, x, target)
            w[i] = saved
            val numerical = (up - down) / 2e-6
            if (kotlin.math.abs(numerical) < 1e-6) continue
            val copy = net.copy()
            AdamTrainer(copy, learningRate = 1e-4).step(listOf(x), listOf(target))
            val moved = copy.weights[l][i] - saved
            assertTrue(moved * numerical < 0, "layer $l weight $i: moved $moved, gradient $numerical")
        }
    }

    @Test
    fun trainingFitsASimpleFunction() {
        val random = Random(2)
        val net = Mlp.random(intArrayOf(3, 16, 1), 10.0, random)
        val trainer = AdamTrainer(net, 1e-2)
        val xs = List(200) { DoubleArray(3) { random.nextDouble() } }
        val ys = xs.map { 10 * it[0] * it[1] - 5 * it[2] } // an interaction a linear model cannot fit
        repeat(300) { trainOn(trainer, xs, ys, random, augment = { it }) }
        val error = xs.zip(ys).map { (x, y) -> kotlin.math.abs(net.evaluate(x) - y) }.average()
        assertTrue(error < 0.5, "mean error $error")
    }
}
