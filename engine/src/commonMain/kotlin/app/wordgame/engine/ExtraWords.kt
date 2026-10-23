package app.wordgame.engine

/** Words accepted by either list. */
operator fun WordList.plus(other: WordList) = WordList { contains(it) || other.contains(it) }

/**
 * A small hand-maintained word list (e.g. the private 2-letter list) loaded on top of the main DAWG.
 * Text format: words separated by spaces or new lines, accents allowed, `#` starts a comment.
 */
class ExtraWords private constructor(val words: Set<String>) : WordList {
    override fun contains(word: String) = word in words

    /** The same words as a graph, so the move generator can follow them too. */
    fun toDawg(): Dawg = DawgBuilder().apply { words.sorted().forEach(::add) }.build()

    companion object {
        fun parse(text: String): ExtraWords = ExtraWords(
            text.lineSequence()
                .map { it.substringBefore('#') }
                .flatMap { it.split(' ', '\t', ',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { normalize(it) }
                .toSet(),
        )

        private val ACCENTS = mapOf(
            'À' to "A", 'Â' to "A", 'Ä' to "A", 'É' to "E", 'È' to "E", 'Ê' to "E", 'Ë' to "E",
            'Î' to "I", 'Ï' to "I", 'Ô' to "O", 'Ö' to "O", 'Ù' to "U", 'Û' to "U", 'Ü' to "U",
            'Ÿ' to "Y", 'Ç' to "C", 'Œ' to "OE", 'Æ' to "AE",
        )

        /** Same form as the main dictionary: uppercase A-Z, no accents. */
        fun normalize(word: String): String {
            val out = buildString { word.uppercase().forEach { append(ACCENTS[it] ?: it) } }
            require(out.isNotEmpty() && out.all { it in 'A'..'Z' }) { "Invalid word '$word'" }
            return out
        }
    }
}
