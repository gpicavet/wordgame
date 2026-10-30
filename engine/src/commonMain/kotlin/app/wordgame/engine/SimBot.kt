package app.wordgame.engine

import kotlin.random.Random

/**
 * A [ValueBot] that looks one move further, in 2-player games: its [candidates] best moves by equity are each
 * answered by the opponent's best move (by the same equity) on [racks] racks drawn from the unseen tiles, the
 * same racks for every candidate. Value of a candidate = score + value of what it keeps (all but the board
 * part, which the replies now measure) − average (reply score + value of what the opponent keeps).
 * With 3 or more players, or fewer than 2 moves to compare, it plays as the [ValueBot].
 * About +10 points a game and 55 % of wins against the [ValueBot] alone (6 × 16, 2,000 games).
 */
class SimBot(
    private val lexicon: Lexicon,
    private val weights: DoubleArray,
    private val random: Random,
    private val candidates: Int = 6,
    private val racks: Int = 16,
    private val topK: Int = 30,
    /** Words both players are assumed to know (all formed words must be in it); null = the whole dictionary. */
    private val vocabulary: WordList? = null,
) {
    private val fallback = ValueBot(lexicon, weights, random, topK, vocabulary = vocabulary)
    private val boardFeatures = Afterstate.names().let { it.indexOf("open_tw")..it.indexOf("tw_lanes") }

    private fun full(x: DoubleArray): Double {
        var v = 0.0
        for (i in x.indices) v += weights[i] * x[i]
        return v
    }

    private fun kept(x: DoubleArray): Double {
        var v = 0.0
        for (i in x.indices) if (i !in boardFeatures) v += weights[i] * x[i]
        return v
    }

    /** The [topK] highest-scoring moves of [rack] on [board], each with its afterstate features. */
    private fun options(solver: Solver, board: Board, rack: String, bag: Int, rules: RuleSet, allowBingo: Boolean = true) =
        solver.bestMoves(board, rack, Int.MAX_VALUE)
            .filter { c -> vocabulary == null || c.words.all { vocabulary.contains(it.word) } }
            .filter { c -> allowBingo || c.tiles.size < rules.rackSize }
            .take(topK)
            .map { c ->
                val leave = Game.removeFromRack(rack, c.tiles.map { if (it.blank) BLANK else it.letter }) ?: ""
                c to Afterstate.features(board.with(c.tiles), leave, maxOf(0, bag - c.tiles.size), rules)
            }

    /** [allowBingo] false: never plays all the tiles of a full rack (see [bingoAllowed]). */
    fun decide(state: GameState, seat: Int, allowBingo: Boolean = true): Decision {
        if (state.players.size != 2) return fallback.decide(state, seat, allowBingo)
        val rules = state.rules
        val rack = state.players[seat].rack
        val solver = Solver(lexicon, rules)
        val before = state.boardModel()
        val mine = options(solver, before, rack, state.bag.length, rules, allowBingo)
            .sortedByDescending { (c, x) -> c.score + full(x) }
            .take(candidates)
        if (mine.size < 2) return fallback.decide(state, seat, allowBingo)
        // Unseen by this player: the bag and the opponent's rack (known exactly once the bag is empty).
        val onBoard = state.board.joinToString("").filter { it != Board.EMPTY }.map { if (it.isLowerCase()) BLANK else it }
        val unseen = Game.removeFromRack(Game.removeFromRack(rules.fullBag(), onBoard) ?: "", rack.toList()) ?: ""
        val opponentRack = state.players[1 - seat].rack.length
        val samples = if (unseen.length <= opponentRack) listOf(unseen)
        else List(racks) { unseen.toList().shuffled(random).take(opponentRack).joinToString("") }
        val (best, x) = mine.maxBy { (c, x) ->
            val after = before.with(c.tiles)
            val bagAfter = maxOf(0, state.bag.length - c.tiles.size)
            val replies = samples.map { opponent ->
                options(solver, after, opponent, bagAfter, rules).maxByOrNull { (r, y) -> r.score + full(y) }
                    ?.let { (r, y) -> r.score + kept(y) } ?: 0.0
            }
            c.score + kept(x) - replies.average()
        }
        return Decision(Action.Play(best.tiles), best.score, x)
    }
}
