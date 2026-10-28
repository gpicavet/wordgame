package app.wordgame.engine

/** Why a word left out of the dictionary was left out, to tell the player more than "unknown word". */
enum class Refusal(val code: Char) {
    PROPER_NOUN('P'),
    ABBREVIATION('A'),
    /** Written with a hyphen or an apostrophe (porte-clé, aujourd'hui, don't). */
    COMPOUND('C'),
    MISSPELLING('F'),
    AFFIX('X'),
    /** Only exists inside a locution (the "ad" of "ad hoc"). */
    LOCUTION('L'),
    /** A real word that the official word list does not have. */
    NOT_LISTED('N');

    companion object {
        fun of(code: Char) = entries.firstOrNull { it.code == code }
    }
}

/**
 * Words that are not in the dictionary but that the sources know, with the reason they were left out.
 * Text format, one word per line: the [Refusal.code], a space, the word (uppercase A-Z).
 */
class Refusals(private val reasons: Map<String, Refusal>) {

    val size get() = reasons.size

    operator fun get(word: String): Refusal? = reasons[word]

    fun toText(): String = buildString {
        reasons.entries.sortedBy { it.key }.forEach { (word, reason) -> append(reason.code).append(' ').append(word).append('\n') }
    }

    companion object {
        fun parse(text: String) = Refusals(
            text.lineSequence().filter { it.length > 2 }
                .mapNotNull { line -> Refusal.of(line[0])?.let { line.substring(2) to it } }
                .toMap()
        )
    }
}
