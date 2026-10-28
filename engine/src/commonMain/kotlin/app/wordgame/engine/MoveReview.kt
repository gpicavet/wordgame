package app.wordgame.engine

import kotlin.math.exp
import kotlin.math.roundToInt

/** Advice drawn from what separates a move from the best one; [amount] is a number of points where relevant. */
data class Tip(val kind: TipKind, val amount: Int = 0)

enum class TipKind {
    /** The best move scored [Tip.amount] points more. */
    MORE_POINTS,
    Q_WITHOUT_U,
    /** A blank spent for few points: kept, it helps make a scrabble later. */
    BLANK_SPENT,
    TOO_MANY_VOWELS,
    TOO_MANY_CONSONANTS,
    DUPLICATES,
    /** Letters kept that are hard to play, without a more precise reason. */
    WEAK_LEAVE,
    OPENS_TRIPLE_WORD,
    OPENS_PREMIUMS,
    /** Expensive letters left on the rack when the bag is empty. */
    ENDGAME_LEAVE,
}

/**
 * Grade of a move out of 10, from how much "equity" it gave up: equity = score + BelzéBot's value of the
 * position left (letters kept, premium squares opened, end of game), in points. [loss] is the gap with the
 * best move by equity; the grade falls quickly for small losses, then slowly, so that a missed big move does
 * not always give 0: 5 points lost = 8/10, 10 = 6/10, 20 = 4/10, 40 = 1/10.
 */
class MoveReview(val grade: Int, val loss: Double, val bestScore: Int, val tips: List<Tip>) {

    companion object {
        /** Candidates looked at: the highest-scoring moves, as BelzéBot does with a larger margin. */
        private const val CANDIDATES = 100
        /** Below this gap in points, a part of the equity is not worth a tip. */
        private const val TIP_THRESHOLD = 3.0

        /**
         * Reviews [played] (the tiles of a move, empty for a pass) made with [rack] on [before], [bagBefore] tiles in
         * the bag. Exchanges are not reviewed: which letters were kept is not recorded.
         */
        fun of(
            before: Board, rack: String, played: List<Placement>, score: Int, bagBefore: Int,
            rules: RuleSet, lexicon: Lexicon, weights: DoubleArray,
        ): MoveReview {
            fun features(tiles: List<Placement>): DoubleArray {
                val leave = Game.removeFromRack(rack, tiles.map { if (it.blank) BLANK else it.letter }) ?: ""
                return Afterstate.features(before.with(tiles), leave, maxOf(0, bagBefore - tiles.size), rules)
            }
            fun value(x: DoubleArray) = x.indices.sumOf { weights[it] * x[it] }

            val mine = features(played)
            val myEquity = score + value(mine)
            val candidates = Solver(lexicon, rules).bestMoves(before, rack, CANDIDATES)
            val best = candidates.map { c -> features(c.tiles).let { x -> Triple(c, x, c.score + value(x)) } }
                .maxByOrNull { it.third }
            if (best == null || best.third <= myEquity) return MoveReview(10, 0.0, candidates.firstOrNull()?.score ?: score, emptyList())
            val (bestMove, theirs, bestEquity) = best
            val loss = bestEquity - myEquity
            val grade = (10 * exp(-loss / 20)).roundToInt().coerceIn(0, 10)

            // What the move gave up, part by part: positive = worse than the best move.
            fun lost(range: IntRange) = range.sumOf { weights[it] * (theirs[it] - mine[it]) }
            val names = Afterstate.names()
            fun index(name: String) = names.indexOf(name)
            fun leaveCount(x: DoubleArray, letter: Char) = x[if (letter == BLANK) 26 else letter - 'A']
            val leaveLost = lost(0..index("q_without_u"))
            val boardLost = lost(index("open_tw")..index("tw_lanes"))
            val endLost = lost(index("endgame_leave_points")..index("endgame_leave_points"))

            val tips = ArrayList<Pair<Double, Tip>>()
            val morePoints = bestMove.score - score
            if (morePoints >= TIP_THRESHOLD) tips += morePoints.toDouble() to Tip(TipKind.MORE_POINTS, morePoints)
            if (leaveLost >= TIP_THRESHOLD) {
                val vowels = mine[index("vowels")]
                val consonants = mine[index("consonants")]
                val kind = when {
                    mine[index("q_without_u")] > 0 -> TipKind.Q_WITHOUT_U
                    leaveCount(mine, BLANK) < leaveCount(theirs, BLANK) -> TipKind.BLANK_SPENT
                    vowels - consonants >= 3 -> TipKind.TOO_MANY_VOWELS
                    consonants - vowels >= 3 -> TipKind.TOO_MANY_CONSONANTS
                    ('A'..'Z').any { mine[index("pair_$it")] > 0 } -> TipKind.DUPLICATES
                    else -> TipKind.WEAK_LEAVE
                }
                tips += leaveLost to Tip(kind)
            }
            if (boardLost >= TIP_THRESHOLD) {
                val triple = mine[index("open_tw")] + mine[index("tw_lanes")] > theirs[index("open_tw")] + theirs[index("tw_lanes")]
                tips += boardLost to Tip(if (triple) TipKind.OPENS_TRIPLE_WORD else TipKind.OPENS_PREMIUMS)
            }
            if (endLost >= TIP_THRESHOLD) tips += endLost to Tip(TipKind.ENDGAME_LEAVE)
            return MoveReview(grade, loss, candidates.first().score, tips.sortedByDescending { it.first }.take(2).map { it.second })
        }
    }
}
