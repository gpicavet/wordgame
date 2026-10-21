package app.wordgame.engine

/**
 * Builds a minimal DAWG from words added in strictly increasing order
 * (incremental algorithm of Daciuk et al., 2000): suffixes are merged as soon as they are complete.
 */
class DawgBuilder {
    private class Node {
        var isWord = false
        val letters = StringBuilder()
        val children = ArrayList<Node>()
        var id = -1
    }

    private data class Signature(val isWord: Boolean, val letters: String, val children: List<Int>)

    private val root = Node()
    private val register = HashMap<Signature, Node>()
    private var previous = ""
    private var nextId = 0
    var wordCount = 0
        private set

    fun add(word: String) {
        require(word.isNotEmpty() && word.all { it in 'A'..'Z' }) { "Invalid word '$word'" }
        require(word > previous) { "Words must be added in increasing order ('$previous' then '$word')" }
        val common = previous.commonPrefixWith(word).length
        val last = nodeAt(common)
        minimize(last)
        var node = last
        for (ch in word.substring(common)) {
            val child = Node()
            node.letters.append(ch)
            node.children.add(child)
            node = child
        }
        node.isWord = true
        previous = word
        wordCount++
    }

    fun build(): Dawg {
        minimize(root)
        // Lay out every node that has children: its edges are contiguous.
        val offsets = HashMap<Int, Int>()
        val order = ArrayList<Node>()
        var size = 1 // edge 0 is a dummy so that child index 0 means "none"
        fun visit(n: Node) {
            if (n.children.isEmpty() || n.id in offsets) return
            offsets[n.id] = size
            order.add(n)
            size += n.children.size
            n.children.forEach(::visit)
        }
        root.id = nextId++
        visit(root)
        val edges = IntArray(size)
        for (n in order) {
            val base = offsets.getValue(n.id)
            n.children.forEachIndexed { i, child ->
                edges[base + i] = Dawg.edge(
                    letter = n.letters[i],
                    terminal = child.isWord,
                    last = i == n.children.lastIndex,
                    child = if (child.children.isEmpty()) 0 else offsets.getValue(child.id),
                )
            }
        }
        return Dawg(edges, offsets.getValue(root.id))
    }

    /** Node reached by following the previous word for [depth] letters (always the last children). */
    private fun nodeAt(depth: Int): Node {
        var node = root
        repeat(depth) { node = node.children.last() }
        return node
    }

    /** Replaces the last child chain below [node] by registered equivalent nodes. */
    private fun minimize(node: Node) {
        val child = node.children.lastOrNull() ?: return
        minimize(child)
        val signature = Signature(child.isWord, child.letters.toString(), child.children.map { it.id })
        val existing = register[signature]
        if (existing != null) {
            node.children[node.children.lastIndex] = existing
        } else {
            child.id = nextId++
            register[signature] = child
        }
    }
}
