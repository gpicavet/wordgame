package app.wordgame.engine

fun interface WordList {
    /** [word] is uppercase A-Z, without accents. */
    fun contains(word: String): Boolean
}

/**
 * Minimal DAWG stored as a flat edge array, the format used for lookups now and by the solver later.
 *
 * Each edge is one Int: bits 0-4 letter (1 = 'A'), bit 5 = a word ends after this edge,
 * bit 6 = last edge of its node, bits 7-31 = index of the child's first edge (0 = no child).
 * Index 0 is a reserved dummy edge so that 0 can mean "no child".
 *
 * File layout (big-endian): magic "WGD1", edge count, root index, edges.
 */
class Dawg(private val edges: IntArray, val root: Int) : WordList {

    val edgeCount get() = edges.size

    override fun contains(word: String): Boolean {
        if (word.isEmpty()) return false
        var node = root
        for (i in word.indices) {
            val letter = word[i] - 'A' + 1
            if (letter !in 1..26 || node == 0) return false
            val edge = findEdge(node, letter) ?: return false
            if (i == word.lastIndex) return edges[edge] and TERMINAL != 0
            node = edges[edge] ushr CHILD_SHIFT
        }
        return false
    }

    // Traversal API for the move generator. A node is the index of its first edge; 0 = no children.

    /** Edge leaving [node] labelled [letter], or -1. */
    fun edgeFor(node: Int, letter: Char): Int = if (node == 0) -1 else findEdge(node, letter - 'A' + 1) ?: -1

    /** A word ends after [edge]. */
    fun isTerminal(edge: Int) = edges[edge] and TERMINAL != 0

    /** Node reached through [edge] (0 when the word cannot be extended). */
    fun target(edge: Int) = edges[edge] ushr CHILD_SHIFT

    private fun findEdge(node: Int, letter: Int): Int? {
        var e = node
        while (true) {
            val v = edges[e]
            if (v and LETTER_MASK == letter) return e
            if (v and LAST != 0) return null
            e++
        }
    }

    fun toBytes(): ByteArray {
        val out = ByteArray(12 + edges.size * 4)
        MAGIC.encodeToByteArray().copyInto(out, 0)
        writeInt(out, 4, edges.size)
        writeInt(out, 8, root)
        for (i in edges.indices) writeInt(out, 12 + i * 4, edges[i])
        return out
    }

    companion object {
        const val LETTER_MASK = 0x1F
        const val TERMINAL = 1 shl 5
        const val LAST = 1 shl 6
        const val CHILD_SHIFT = 7
        private const val MAGIC = "WGD1"

        fun edge(letter: Char, terminal: Boolean, last: Boolean, child: Int): Int {
            require(child < (1 shl (32 - CHILD_SHIFT))) { "DAWG too large" }
            return (letter - 'A' + 1) or
                (if (terminal) TERMINAL else 0) or
                (if (last) LAST else 0) or
                (child shl CHILD_SHIFT)
        }

        fun fromBytes(bytes: ByteArray): Dawg {
            require(bytes.decodeToString(0, 4) == MAGIC) { "Not a DAWG file" }
            val count = readInt(bytes, 4)
            val root = readInt(bytes, 8)
            return Dawg(IntArray(count) { readInt(bytes, 12 + it * 4) }, root)
        }

        private fun writeInt(b: ByteArray, at: Int, v: Int) {
            b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte()
            b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
        }

        private fun readInt(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
                (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)
    }
}
