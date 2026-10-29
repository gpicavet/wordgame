package app.wordgame.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HintsTest {
    private val lexicon = Lexicon(Dawg.fromBytes(File("../dictionaries/bin/fr-libre.dawg").readBytes()))
    private val common = Dawg.fromBytes(File("../dictionaries/bin/fr-courant.dawg").readBytes())

    @Test
    fun findsTheScrabblesOfTheRackEasiestFirst() {
        val hints = Hints.of(Board.empty(15), "AEINRST", RuleSet.CLASSIC, lexicon, common)
        val words = hints.scrabbles.map { it.word }
        assertTrue("TRAINES" in words && "RENTAIS" in words, "$words")
        assertEquals(words.size, words.toSet().size)
        assertTrue(hints.scrabbles.all { it.boardLetters == 0 && it.crossWords == 0 && it.score >= 50 })
        assertTrue(hints.scrabbles.first().common, "${hints.scrabbles.first().word}")
        assertEquals(hints.scrabbles.maxOf { it.score }, hints.bestScore)
    }

    @Test
    fun countsTheBoardLettersAndCrossWords() {
        val board = Board.empty(15).with("ZOO".mapIndexed { i, c -> Placement(7, 6 + i, c) })
        val hints = Hints.of(board, "AEINRST", RuleSet.CLASSIC, lexicon, common)
        assertTrue(hints.scrabbles.any { it.boardLetters == 1 }, "${hints.scrabbles.map { it.word }}")
        assertTrue(hints.scrabbles.all { it.boardLetters > 0 || it.crossWords > 0 })
    }

    @Test
    fun noScrabble() {
        val hints = Hints.of(Board.empty(15), "ZZXWKQB", RuleSet.CLASSIC, lexicon, common)
        assertTrue(hints.scrabbles.isEmpty())
    }
}
