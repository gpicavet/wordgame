package app.wordgame.engine

/** A legal play and its score, as the server would compute it. */
data class Candidate(val tiles: List<Placement>, val words: List<FormedWord>, val score: Int) {
    /** Main word first: the one the tiles are laid along. */
    val mainWord get() = words.first().word
}

/**
 * Move generator of Appel & Jacobson (1988):
 * 1. anchors = empty squares next to a tile (the center on an empty board): every play covers one;
 * 2. cross-checks = per empty square, the letters that make a valid word across;
 * 3. per anchor, a left part (from the rack, or the tiles already there) then an extension to the right,
 *    following the DAWG so that only prefixes of real words are ever explored.
 * Vertical plays are horizontal plays on the transposed board. Every play is scored by [MoveEvaluator].
 */
class Solver(private val lexicon: Lexicon, private val rules: RuleSet) {

    fun bestMoves(board: Board, rack: String, limit: Int = 5): List<Candidate> {
        val found = HashMap<String, Candidate>()
        for (horizontal in listOf(true, false)) {
            Pass(board, rack, horizontal).run { plays ->
                val placements = plays.map { if (horizontal) it else it.copy(row = it.col, col = it.row) }
                val evaluation = MoveEvaluator.evaluate(board, placements, rules, lexicon.words)
                if (evaluation is Evaluation.Valid) {
                    // Same letters on the same squares: keep one (blank placement aside, keep the best).
                    val key = placements.sortedWith(compareBy({ it.row }, { it.col })).joinToString { "${it.row},${it.col},${it.letter}" }
                    val candidate = Candidate(placements, evaluation.words, evaluation.score)
                    if ((found[key]?.score ?: -1) < candidate.score) found[key] = candidate
                }
            }
        }
        return found.values.sortedByDescending { it.score }.take(limit)
    }

    /** One direction. Coordinates are in the (possibly transposed) frame where plays go left to right. */
    private inner class Pass(private val board: Board, rack: String, private val horizontal: Boolean) {
        private val size = board.size
        private val rack = IntArray(27).also { counts -> rack.forEach { counts[slot(it)]++ } }
        private val cross = Array(size) { r -> IntArray(size) { c -> crossCheck(r, c) } }
        private val placed = ArrayList<Placement>()
        private lateinit var emit: (List<Placement>) -> Unit

        private fun slot(tile: Char) = if (tile == BLANK) 26 else tile - 'A'
        private fun cell(r: Int, c: Int) = if (horizontal) board[r, c] else board[c, r]
        private fun empty(r: Int, c: Int) = cell(r, c) == Board.EMPTY
        private fun inside(i: Int) = i in 0 until size

        fun run(onPlay: (List<Placement>) -> Unit) {
            emit = onPlay
            val start = intArrayOf(*lexicon.graphs.map { it.root }.toIntArray())
            for (r in 0 until size) {
                for (c in 0 until size) {
                    if (!isAnchor(r, c)) continue
                    if (c > 0 && !empty(r, c - 1)) {
                        // Tiles already on the left: they are the prefix.
                        var start2 = c
                        while (start2 > 0 && !empty(r, start2 - 1)) start2--
                        var nodes: IntArray? = start
                        for (k in start2 until c) nodes = nodes?.let { step(it, cell(r, k).uppercaseChar())?.first }
                        nodes?.let { extendRight(r, c, c, it) }
                    } else {
                        // Free squares on the left (up to the previous anchor) take a prefix from the rack.
                        var limit = 0
                        while (c - limit - 1 >= 0 && empty(r, c - limit - 1) && !isAnchor(r, c - limit - 1)) limit++
                        leftPart(r, c, start, limit)
                    }
                }
            }
        }

        private fun isAnchor(r: Int, c: Int): Boolean {
            if (!empty(r, c)) return false
            if (board.isEmpty()) return r == size / 2 && c == size / 2
            return (r > 0 && !empty(r - 1, c)) || (r < size - 1 && !empty(r + 1, c)) ||
                (c > 0 && !empty(r, c - 1)) || (c < size - 1 && !empty(r, c + 1))
        }

        /** Letters allowed on (r, c) by the word formed across (vertically in this frame), as a 26-bit mask. */
        private fun crossCheck(r: Int, c: Int): Int {
            if (!empty(r, c)) return 0
            var top = r
            while (top > 0 && !empty(top - 1, c)) top--
            var bottom = r
            while (bottom < size - 1 && !empty(bottom + 1, c)) bottom++
            if (top == r && bottom == r) return ALL
            val above = (top until r).joinToString("") { cell(it, c).uppercaseChar().toString() }
            val below = (r + 1..bottom).joinToString("") { cell(it, c).uppercaseChar().toString() }
            var mask = 0
            for (l in 'A'..'Z') if (lexicon.words.contains(above + l + below)) mask = mask or (1 shl (l - 'A'))
            return mask
        }

        /** Prefixes from the rack ending just left of the anchor, then extension from the anchor. */
        private fun leftPart(r: Int, anchor: Int, nodes: IntArray, limit: Int) {
            // The prefix occupies the squares just before the anchor.
            extendRight(r, anchor - placed.size, anchor, nodes, shiftPrefix = true)
            if (limit == 0) return
            forEachRackLetter(ALL) { letter, blank ->
                val (next, _) = step(nodes, letter) ?: return@forEachRackLetter
                placed += Placement(r, -1, letter, blank)
                leftPart(r, anchor, next, limit - 1)
                placed.removeAt(placed.lastIndex)
            }
        }

        /**
         * Walks right from column [col] with graph positions [nodes]. With [shiftPrefix], the tiles already in
         * [placed] are a rack prefix still without columns: they are laid just before the anchor first.
         */
        private fun extendRight(r: Int, col: Int, anchor: Int, nodes: IntArray, shiftPrefix: Boolean = false) {
            if (shiftPrefix) {
                val prefix = placed.toList()
                placed.clear()
                prefix.forEachIndexed { i, p -> placed += p.copy(col = col + i) }
                extend(r, anchor, anchor, nodes, terminal = false)
                placed.clear()
                placed += prefix
            } else {
                extend(r, col, anchor, nodes, terminal = false)
            }
        }

        private fun extend(r: Int, col: Int, anchor: Int, nodes: IntArray, terminal: Boolean) {
            // A word ends here if the last letter closed a word, the anchor is covered and the next square is free.
            if (terminal && col > anchor && placed.isNotEmpty() && (!inside(col) || empty(r, col))) emit(placed.toList())
            if (!inside(col)) return
            if (!empty(r, col)) {
                val (next, end) = step(nodes, cell(r, col).uppercaseChar()) ?: return
                extend(r, col + 1, anchor, next, end)
                return
            }
            forEachRackLetter(cross[r][col]) { letter, blank ->
                val (next, end) = step(nodes, letter) ?: return@forEachRackLetter
                placed += Placement(r, col, letter, blank)
                extend(r, col + 1, anchor, next, end)
                placed.removeAt(placed.lastIndex)
            }
        }

        /** Tries each distinct rack tile allowed by [mask]; a blank tries every allowed letter. */
        private inline fun forEachRackLetter(mask: Int, action: (Char, Boolean) -> Unit) {
            for (i in 0 until 26) {
                if (rack[i] == 0 || mask and (1 shl i) == 0) continue
                rack[i]--
                action('A' + i, false)
                rack[i]++
            }
            if (rack[26] > 0) {
                rack[26]--
                for (i in 0 until 26) if (mask and (1 shl i) != 0) action('A' + i, true)
                rack[26]++
            }
        }

        /** Follows [letter] in every graph; null when no graph has it. Second = some word ends here. */
        private fun step(nodes: IntArray, letter: Char): Pair<IntArray, Boolean>? {
            var any = false
            var terminal = false
            val next = IntArray(nodes.size) { i ->
                val node = nodes[i]
                val graph = lexicon.graphs[i]
                val edge = if (node < 0) -1 else graph.edgeFor(node, letter)
                if (edge < 0) -1 else {
                    any = true
                    if (graph.isTerminal(edge)) terminal = true
                    graph.target(edge)
                }
            }
            return if (any) next to terminal else null
        }
    }

    private companion object {
        const val ALL = (1 shl 26) - 1
    }
}
