package app.wordgame.engine

import kotlin.random.Random

/**
 * Raw description of the position a player leaves after a move, from their side, for [NeuralBot]: no strategic
 * feature, only what the player sees. The network has to find by itself what matters (Q without U, vowel
 * balance, open premium squares…).
 */
object RawAfterstate {
    private const val LETTERS = 27 // A-Z + blank
    private const val BOARD = 15

    // Offsets of the blocks in the input vector.
    const val LEAVE = 0
    const val UNSEEN = LEAVE + LETTERS
    const val BAG = UNSEEN + LETTERS // bag / 100, then one flag per bag size 0..7
    const val DIFF = BAG + 9
    const val CELLS = DIFF + 1 // one per square: 1 when occupied
    const val SIZE = CELLS + BOARD * BOARD

    private fun index(tile: Char) = if (tile == BLANK) 26 else tile - 'A'

    fun encode(boardAfter: Board, leave: String, bagSize: Int, scoreDiff: Int, rules: RuleSet): DoubleArray {
        require(boardAfter.size == BOARD) { "board size ${boardAfter.size}" }
        val x = DoubleArray(SIZE)
        val full = IntArray(LETTERS)
        rules.fullBag().forEach { full[index(it)]++ }
        val unseen = full.copyOf()
        for (tile in leave) {
            x[LEAVE + index(tile)] += 1.0 / 3
            unseen[index(tile)]--
        }
        for (r in 0 until BOARD) for (c in 0 until BOARD) {
            val cell = boardAfter[r, c]
            if (cell == Board.EMPTY) continue
            x[CELLS + r * BOARD + c] = 1.0
            unseen[if (cell.isLowerCase()) 26 else index(cell)]--
        }
        for (i in 0 until LETTERS) if (full[i] > 0) x[UNSEEN + i] = unseen[i].toDouble() / full[i]
        x[BAG] = bagSize / 100.0
        if (bagSize <= 7) x[BAG + 1 + bagSize] = 1.0
        x[DIFF] = scoreDiff / 100.0
        return x
    }

    /**
     * The 8 symmetries of the square board (rotations and mirrors), applied to the squares only: the standard
     * layout is unchanged by them, so a position and its images have the same value.
     */
    fun symmetric(x: DoubleArray, symmetry: Int): DoubleArray {
        if (symmetry == 0) return x
        val y = x.copyOf()
        val n = BOARD - 1
        for (r in 0 until BOARD) for (c in 0 until BOARD) {
            var (rr, cc) = if (symmetry and 1 != 0) c to r else r to c
            if (symmetry and 2 != 0) rr = n - rr
            if (symmetry and 4 != 0) cc = n - cc
            y[CELLS + rr * BOARD + cc] = x[CELLS + r * BOARD + c]
        }
        return y
    }
}

/**
 * Small fully connected network: ReLU hidden layers, one linear output multiplied by [scale] (points).
 * Weights of layer l are stored row by row: weights[l][o * inputs + i].
 */
class Mlp(val sizes: IntArray, val weights: Array<DoubleArray>, val biases: Array<DoubleArray>, val scale: Double) {
    val layers get() = sizes.size - 1

    fun copy() = Mlp(sizes.copyOf(), Array(layers) { weights[it].copyOf() }, Array(layers) { biases[it].copyOf() }, scale)

    /** Value in points. */
    fun evaluate(x: DoubleArray): Double {
        var a = x
        for (l in 0 until layers) {
            val inputs = sizes[l]
            val outputs = sizes[l + 1]
            val w = weights[l]
            val z = biases[l].copyOf()
            for (i in 0 until inputs) {
                val v = a[i]
                if (v == 0.0) continue // most inputs (empty squares) are 0
                for (o in 0 until outputs) z[o] += w[o * inputs + i] * v
            }
            if (l < layers - 1) for (o in z.indices) if (z[o] < 0) z[o] = 0.0
            a = z
        }
        return a[0] * scale
    }

    /** Little-endian: layer count, sizes, scale, then the weights and biases of each layer as floats. */
    fun toBytes(): ByteArray {
        val out = ArrayList<Byte>()
        fun int(v: Int) = repeat(4) { out += (v ushr (8 * it)).toByte() }
        int(sizes.size)
        sizes.forEach(::int)
        int(scale.toFloat().toRawBits())
        for (l in 0 until layers) {
            weights[l].forEach { int(it.toFloat().toRawBits()) }
            biases[l].forEach { int(it.toFloat().toRawBits()) }
        }
        return out.toByteArray()
    }

    companion object {
        /** He initialization for the hidden layers; the output layer starts at 0, so the first value is 0 everywhere. */
        fun random(sizes: IntArray, scale: Double, random: Random): Mlp {
            val weights = Array(sizes.size - 1) { l ->
                val inputs = sizes[l]
                val std = if (l == sizes.size - 2) 0.0 else kotlin.math.sqrt(2.0 / inputs)
                DoubleArray(inputs * sizes[l + 1]) { gaussian(random) * std }
            }
            return Mlp(sizes, weights, Array(sizes.size - 1) { DoubleArray(sizes[it + 1]) }, scale)
        }

        fun fromBytes(bytes: ByteArray): Mlp {
            var p = 0
            fun int(): Int {
                var v = 0
                repeat(4) { v = v or ((bytes[p++].toInt() and 0xff) shl (8 * it)) }
                return v
            }
            fun float() = Float.fromBits(int()).toDouble()
            val sizes = IntArray(int()) { int() }
            val scale = float()
            val weights = arrayOfNulls<DoubleArray>(sizes.size - 1)
            val biases = arrayOfNulls<DoubleArray>(sizes.size - 1)
            for (l in 0 until sizes.size - 1) {
                weights[l] = DoubleArray(sizes[l] * sizes[l + 1]) { float() }
                biases[l] = DoubleArray(sizes[l + 1]) { float() }
            }
            return Mlp(sizes, weights.requireNoNulls(), biases.requireNoNulls(), scale)
        }

        private fun gaussian(random: Random): Double {
            val u = 1.0 - random.nextDouble()
            return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * kotlin.math.PI * random.nextDouble())
        }
    }
}

/**
 * Plays the option maximizing score + network value of the afterstate, among the [maxMoves] highest-scoring
 * moves and every exchange (all the subsets of the rack it can keep). Nothing strategic is coded: the value
 * is learned by self-play from [RawAfterstate].
 */
class NeuralBot(
    private val lexicon: Lexicon,
    val net: Mlp,
    private val random: Random,
    private val epsilon: Double = 0.0,
    /** Words the bot knows (all formed words must be in it); null = the whole dictionary. */
    private val vocabulary: WordList? = null,
    private val maxMoves: Int = 400,
) {
    private class Option(val action: Action, val score: Int, val x: DoubleArray, val value: Double)

    fun decide(state: GameState, seat: Int, allowBingo: Boolean = true): Decision {
        val rules = state.rules
        val rack = state.players[seat].rack
        val board = state.boardModel()
        val diff = state.players[seat].score - state.bestOpponentScore(seat)
        val options = ArrayList<Option>()
        fun add(action: Action, score: Int, boardAfter: Board, leave: String, bagAfter: Int) {
            val x = RawAfterstate.encode(boardAfter, leave, bagAfter, diff + score, rules)
            options += Option(action, score, x, score + net.evaluate(x))
        }
        Solver(lexicon, rules).bestMoves(board, rack, Int.MAX_VALUE).asSequence()
            .filter { c -> vocabulary == null || c.words.all { vocabulary.contains(it.word) } }
            .filter { c -> allowBingo || c.tiles.size < rules.rackSize }
            .take(maxMoves)
            .forEach { c ->
                val leave = Game.removeFromRack(rack, c.tiles.map { if (it.blank) BLANK else it.letter }) ?: return@forEach
                add(Action.Play(c.tiles), c.score, board.with(c.tiles), leave, maxOf(0, state.bag.length - c.tiles.size))
            }
        if (state.bag.length >= rules.rackSize) {
            for (keep in subsets(rack)) {
                if (keep.length == rack.length) continue
                val swap = Game.removeFromRack(rack, keep.toList()) ?: continue
                add(Action.Exchange(swap), 0, board, keep, state.bag.length)
            }
        }
        if (options.isEmpty()) return Decision(Action.Pass, 0, null)
        options.sortByDescending { it.value }
        val chosen = if (epsilon > 0 && random.nextDouble() < epsilon) options.take(5).random(random) else options[0]
        return Decision(chosen.action, chosen.score, chosen.x)
    }

    /** Distinct sub-multisets of the rack, as sorted strings. */
    private fun subsets(rack: String): Set<String> {
        val sorted = rack.toCharArray().sorted()
        val result = HashSet<String>()
        for (mask in 0 until (1 shl sorted.size)) {
            result += buildString { for (i in sorted.indices) if (mask and (1 shl i) != 0) append(sorted[i]) }
        }
        return result
    }
}
