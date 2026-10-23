package app.wordgame.engine

/**
 * Everything the game knows about words: lookups for validation, graphs for the move generator.
 * The main DAWG plus optional extra lists (each turned into its own small DAWG).
 */
class Lexicon(main: Dawg, extra: ExtraWords? = null) {
    val graphs: List<Dawg> = listOfNotNull(main, extra?.toDawg())
    val words: WordList = if (extra != null) main + extra else main
}
