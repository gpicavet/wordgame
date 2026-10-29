package app.wordgame.engine

/**
 * A scrabble the rack can make on the board, described without giving it away: how hard it is to find.
 * [boardLetters]: letters of the board the word goes through (0 = the seven letters of the rack alone);
 * [crossWords]: other words formed along the way; [score]: its best placement.
 */
class ScrabbleHint(val word: String, val common: Boolean, val boardLetters: Int, val crossWords: Int, val score: Int) {
    /** Easiest first: an everyday word, then the fewest letters and words to combine. */
    val difficulty get() = (if (common) 0 else 10) + boardLetters + crossWords
}

/** What the player can be told before playing: the scrabbles of the rack and the best score possible. */
class Hints(val scrabbles: List<ScrabbleHint>, val bestScore: Int?) {

    companion object {
        /** Every scrabble [rack] can make on [board], one per word (its easiest placement), easiest first. */
        fun of(board: Board, rack: String, rules: RuleSet, lexicon: Lexicon, common: WordList): Hints {
            val moves = Solver(lexicon, rules).bestMoves(board, rack, Int.MAX_VALUE)
            val scrabbles = moves.filter { it.tiles.size == rules.rackSize }
                .map { c ->
                    ScrabbleHint(c.mainWord, common.contains(c.mainWord), c.mainWord.length - c.tiles.size, c.words.size - 1, c.score)
                }
                .groupBy { it.word }
                .map { (_, ways) -> ways.minWith(compareBy<ScrabbleHint> { it.difficulty }.thenByDescending { it.score }) }
                .sortedWith(compareBy<ScrabbleHint> { it.difficulty }.thenByDescending { it.score })
            return Hints(scrabbles, moves.firstOrNull()?.score)
        }
    }
}
