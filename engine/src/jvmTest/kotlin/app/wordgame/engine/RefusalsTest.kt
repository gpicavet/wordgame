package app.wordgame.engine

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RefusalsTest {
    private fun load(language: String) =
        Refusals.parse(GZIPInputStream(File("../dictionaries/bin/$language-refus.txt.gz").inputStream()).bufferedReader().readText())

    @Test
    fun textRoundTrip() {
        val refusals = Refusals(mapOf("OCTOBER" to Refusal.PROPER_NOUN, "KM" to Refusal.ABBREVIATION))
        val parsed = Refusals.parse(refusals.toText())
        assertEquals(Refusal.PROPER_NOUN, parsed["OCTOBER"])
        assertEquals(Refusal.ABBREVIATION, parsed["KM"])
        assertNull(parsed["XQZ"])
    }

    @Test
    fun frenchReasons() {
        val fr = load("fr")
        assertEquals(Refusal.PROPER_NOUN, fr["FRANCE"])
        assertEquals(Refusal.ABBREVIATION, fr["SNCF"])
        assertEquals(Refusal.COMPOUND, fr["AUJOURDHUI"])
        assertEquals(Refusal.LOCUTION, fr["HOC"])
        assertNull(fr["XQZ"])
    }

    @Test
    fun englishReasons() {
        val en = load("en")
        assertEquals(Refusal.PROPER_NOUN, en["OCTOBER"])
        assertEquals(Refusal.ABBREVIATION, en["NASA"])
        assertEquals(Refusal.COMPOUND, en["DONT"])
        assertEquals(Refusal.NOT_LISTED, en["SELFIE"])
    }

    @Test
    fun noValidWordIsRefused() {
        for ((language, dictionary) in listOf("fr" to "fr-libre", "en" to "en-enable")) {
            val words = Dawg.fromBytes(File("../dictionaries/bin/$dictionary.dawg").readBytes())
            val text = GZIPInputStream(File("../dictionaries/bin/$language-refus.txt.gz").inputStream()).bufferedReader().readText()
            text.lineSequence().filter { it.isNotEmpty() }.forEach { line -> assertFalse(words.contains(line.substring(2)), "$language: $line") }
        }
    }
}
