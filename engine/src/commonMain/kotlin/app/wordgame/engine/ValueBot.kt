package app.wordgame.engine

import kotlin.random.Random

/**
 * Describes the position a player leaves after a move ("afterstate"): what stays on their rack and what
 * the board now offers the opponent. A learned linear value of these features estimates the score
 * difference still to come, which is what the score of the move alone ignores.
 */
object Afterstate {
    private const val LETTERS = 27 // A-Z + blank
    private val VOWELS = setOf('A', 'E', 'I', 'O', 'U', 'Y')

    private val NAMES: List<String> = buildList {
        for (i in 0 until LETTERS) add("count_" + if (i == 26) "?" else ('A' + i).toString())
        for (i in 0 until LETTERS) add("pair_" + if (i == 26) "?" else ('A' + i).toString())
        addAll(
            listOf(
                "vowels", "consonants", "balance", "leave_size", "q_without_u",
                "open_tw", "open_dw", "open_tl", "open_dl", "tw_lanes",
                "bag", "bag_empty", "endgame_leave_points", "bias",
            ),
        )
    }

    val size get() = NAMES.size
    fun names(): List<String> = NAMES

    /** Weights file: one "feature=weight" per line, as written by the trainer. Unknown features are ignored. */
    fun parseWeights(text: String): DoubleArray {
        val byName = text.lineSequence().filter { '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().toDouble() }
        return DoubleArray(size) { byName[NAMES[it]] ?: 0.0 }
    }

    fun features(boardAfter: Board, leave: String, bagSize: Int, rules: RuleSet): DoubleArray {
        val x = DoubleArray(size)
        val counts = IntArray(LETTERS)
        leave.forEach { counts[if (it == BLANK) 26 else it - 'A']++ }
        for (i in 0 until LETTERS) {
            x[i] = counts[i].toDouble()
            x[LETTERS + i] = if (counts[i] >= 2) 1.0 else 0.0
        }
        var k = 2 * LETTERS
        val vowels = leave.count { it in VOWELS }
        val consonants = leave.count { it != BLANK && it !in VOWELS }
        x[k++] = vowels.toDouble()
        x[k++] = consonants.toDouble()
        x[k++] = kotlin.math.abs(vowels - consonants).toDouble()
        x[k++] = leave.length.toDouble()
        x[k++] = if ('Q' in leave && 'U' !in leave) 1.0 else 0.0

        // Premium squares the opponent can now reach: empty, next to a tile.
        val open = IntArray(Premium.entries.size)
        var lanes = 0
        val size = boardAfter.size
        val rowHasTile = BooleanArray(size) { r -> (0 until size).any { c -> !boardAfter.isEmptyAt(r, c) } }
        val colHasTile = BooleanArray(size) { c -> (0 until size).any { r -> !boardAfter.isEmptyAt(r, c) } }
        for (r in 0 until size) for (c in 0 until size) {
            if (!boardAfter.isEmptyAt(r, c)) continue
            val premium = rules.layout.premiumAt(r, c)
            if (premium == Premium.TRIPLE_WORD && (rowHasTile[r] || colHasTile[c])) lanes++
            val touches = (r > 0 && !boardAfter.isEmptyAt(r - 1, c)) || (r < size - 1 && !boardAfter.isEmptyAt(r + 1, c)) ||
                (c > 0 && !boardAfter.isEmptyAt(r, c - 1)) || (c < size - 1 && !boardAfter.isEmptyAt(r, c + 1))
            if (touches) open[premium.ordinal]++
        }
        x[k++] = open[Premium.TRIPLE_WORD.ordinal].toDouble()
        x[k++] = open[Premium.DOUBLE_WORD.ordinal].toDouble()
        x[k++] = open[Premium.TRIPLE_LETTER.ordinal].toDouble()
        x[k++] = open[Premium.DOUBLE_LETTER.ordinal].toDouble()
        x[k++] = lanes.toDouble()
        x[k++] = bagSize / 20.0
        x[k++] = if (bagSize == 0) 1.0 else 0.0
        x[k++] = if (bagSize == 0) leave.sumOf { rules.points(it) }.toDouble() else 0.0
        x[k] = 1.0
        return x
    }
}

/** One decision of a [ValueBot], with what training needs. */
class Decision(val action: Action, val score: Int, val features: DoubleArray?)

/**
 * Plays the move maximizing score + w·features(afterstate) among the [topK] highest-scoring legal moves.
 * With zero weights it is the greedy bot (best immediate score). [epsilon] adds exploration for training.
 */
class ValueBot(
    private val lexicon: Lexicon,
    val weights: DoubleArray,
    private val random: Random,
    private val topK: Int = 30,
    private val epsilon: Double = 0.0,
    /** Words the bot knows (all formed words must be in it); null = the whole dictionary. */
    private val vocabulary: WordList? = null,
) {
    fun value(features: DoubleArray): Double {
        var v = 0.0
        for (i in features.indices) v += weights[i] * features[i]
        return v
    }

    fun decide(state: GameState, seat: Int): Decision {
        val rules = state.rules
        val rack = state.players[seat].rack
        val board = state.boardModel()
        val candidates = if (vocabulary == null) {
            Solver(lexicon, rules).bestMoves(board, rack, topK)
        } else {
            Solver(lexicon, rules).bestMoves(board, rack, Int.MAX_VALUE)
                .filter { c -> c.words.all { vocabulary.contains(it.word) } }.take(topK)
        }
        if (candidates.isEmpty()) {
            if (state.bag.length < rules.rackSize) return Decision(Action.Pass, 0, null)
            // Keep the blank and an S, swap the rest.
            val keep = buildString { if (BLANK in rack) append(BLANK); if ('S' in rack) append('S') }
            val swap = Game.removeFromRack(rack, keep.toList()) ?: rack
            return Decision(Action.Exchange(swap), 0, Afterstate.features(board, keep, state.bag.length, rules))
        }
        val scored = candidates.map { c ->
            val leave = Game.removeFromRack(rack, c.tiles.map { if (it.blank) BLANK else it.letter }) ?: ""
            val bagAfter = maxOf(0, state.bag.length - c.tiles.size)
            val x = Afterstate.features(board.with(c.tiles), leave, bagAfter, rules)
            Triple(c, x, c.score + value(x))
        }
        val chosen = if (epsilon > 0 && random.nextDouble() < epsilon) scored.take(5).random(random) else scored.maxBy { it.third }
        return Decision(Action.Play(chosen.first.tiles), chosen.first.score, chosen.second)
    }
}
