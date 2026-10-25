package app.wordgame.trainer

import app.wordgame.engine.Mlp
import app.wordgame.engine.RawAfterstate
import kotlin.math.sqrt
import kotlin.random.Random

/** Adam on the squared error between the network output and target / scale (backpropagation by hand). */
class AdamTrainer(private val net: Mlp, var learningRate: Double = 1e-3) {
    private val beta1 = 0.9
    private val beta2 = 0.999
    private var step = 0
    private val mW = Array(net.layers) { DoubleArray(net.weights[it].size) }
    private val vW = Array(net.layers) { DoubleArray(net.weights[it].size) }
    private val mB = Array(net.layers) { DoubleArray(net.biases[it].size) }
    private val vB = Array(net.layers) { DoubleArray(net.biases[it].size) }

    /** One step on a minibatch; returns the mean absolute error in points before the step. */
    fun step(xs: List<DoubleArray>, targets: List<Double>): Double {
        val gW = Array(net.layers) { DoubleArray(net.weights[it].size) }
        val gB = Array(net.layers) { DoubleArray(net.biases[it].size) }
        var absErr = 0.0
        for ((x, target) in xs.zip(targets)) {
            // Forward, keeping the activations.
            val acts = ArrayList<DoubleArray>().apply { add(x) }
            for (l in 0 until net.layers) {
                val inputs = net.sizes[l]
                val w = net.weights[l]
                val a = acts[l]
                val z = net.biases[l].copyOf()
                for (i in 0 until inputs) {
                    val v = a[i]
                    if (v == 0.0) continue
                    for (o in z.indices) z[o] += w[o * inputs + i] * v
                }
                if (l < net.layers - 1) for (o in z.indices) if (z[o] < 0) z[o] = 0.0
                acts += z
            }
            val out = acts.last()[0]
            var delta = doubleArrayOf(out - target / net.scale)
            absErr += kotlin.math.abs(delta[0]) * net.scale
            // Backward.
            for (l in net.layers - 1 downTo 0) {
                val inputs = net.sizes[l]
                val w = net.weights[l]
                val a = acts[l]
                val prev = DoubleArray(inputs)
                for (o in delta.indices) {
                    val d = delta[o]
                    if (d == 0.0) continue
                    gB[l][o] += d
                    val row = o * inputs
                    for (i in 0 until inputs) {
                        if (a[i] != 0.0) gW[l][row + i] += d * a[i]
                        if (l > 0) prev[i] += w[row + i] * d
                    }
                }
                if (l > 0) for (i in 0 until inputs) if (a[i] <= 0.0) prev[i] = 0.0 // ReLU
                delta = prev
            }
        }
        step++
        val n = xs.size.toDouble()
        val c1 = 1 - Math.pow(beta1, step.toDouble())
        val c2 = 1 - Math.pow(beta2, step.toDouble())
        fun update(p: DoubleArray, g: DoubleArray, m: DoubleArray, v: DoubleArray) {
            for (i in p.indices) {
                val gi = g[i] / n
                m[i] = beta1 * m[i] + (1 - beta1) * gi
                v[i] = beta2 * v[i] + (1 - beta2) * gi * gi
                p[i] -= learningRate * (m[i] / c1) / (sqrt(v[i] / c2) + 1e-8)
            }
        }
        for (l in 0 until net.layers) {
            update(net.weights[l], gW[l], mW[l], vW[l])
            update(net.biases[l], gB[l], mB[l], vB[l])
        }
        return absErr / n
    }
}

/** Whether an encoded afterstate is in the endgame (7 tiles or fewer in the bag). */
fun isEndgame(x: DoubleArray) = (1..8).any { x[RawAfterstate.BAG + it] != 0.0 }

/** Each sample once, under a random board symmetry by default, in shuffled minibatches. Returns the mean absolute error. */
fun trainOn(
    trainer: AdamTrainer,
    xs: List<DoubleArray>,
    targets: List<Double>,
    random: Random,
    batch: Int = 32,
    augment: (DoubleArray) -> DoubleArray = { RawAfterstate.symmetric(it, random.nextInt(8)) },
): Double {
    val order = xs.indices.shuffled(random)
    var sum = 0.0
    for (chunk in order.chunked(batch)) {
        sum += trainer.step(chunk.map { augment(xs[it]) }, chunk.map { targets[it] }) * chunk.size
    }
    return sum / xs.size
}
