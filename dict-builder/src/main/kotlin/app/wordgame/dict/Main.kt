package app.wordgame.dict

import app.wordgame.engine.DawgBuilder
import java.io.File
import java.text.Normalizer
import java.util.zip.GZIPOutputStream

/**
 * Builds the French word list from the Grammalecte lexicon (MPL 2.0, https://grammalecte.net/):
 * inflected forms, accents removed, uppercase, 2 to 15 letters, no proper nouns, no hyphens or apostrophes.
 *
 * Outputs <out>/bin/fr-libre.dawg (bundled in the app, loaded by the server)
 * and <out>/fr-libre.txt.gz (the derived list, published to comply with the MPL).
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: <lexique-grammalecte-fr.txt> <output-dir>" }
    val source = File(args[0])
    val out = File(args[1])

    val words = sortedSetOf<String>()
    var header: List<String>? = null
    source.useLines { lines ->
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val cols = line.split('\t')
            if (header == null) {
                if (cols.firstOrNull() == "id") header = cols
                continue
            }
            val h = header!!
            if (cols.size < h.size) continue
            val form = cols[h.indexOf("Flexion")]
            val tags = cols[h.indexOf("Étiquettes")].split(' ')
            if (tags.any { it in EXCLUDED_TAGS }) continue
            normalize(form)?.let(words::add)
        }
    }

    val builder = DawgBuilder()
    words.forEach(builder::add)
    val dawg = builder.build()

    File(out, "bin").mkdirs()
    File(out, "bin/fr-libre.dawg").writeBytes(dawg.toBytes())
    GZIPOutputStream(File(out, "fr-libre.txt.gz").outputStream()).bufferedWriter().use { w ->
        words.forEach { w.write(it); w.newLine() }
    }
    println("${words.size} words, ${dawg.edgeCount} edges, ${dawg.edgeCount * 4 / 1024} KiB")
}

/** Proper nouns, first names, surnames and the like. */
private val EXCLUDED_TAGS = setOf("npr", "prn_npr", "patr", "prenom", "nompr")

private val LIGATURES = mapOf('œ' to "oe", 'æ' to "ae")

/** "Œuvrés" -> null (capitalized = proper noun or acronym), "œuvrés" -> "OEUVRES", "porte-clé" -> null. */
fun normalize(form: String): String? {
    if (form.isEmpty() || !form[0].isLowerCase()) return null
    val expanded = buildString { form.forEach { append(LIGATURES[it] ?: it) } }
    val plain = Normalizer.normalize(expanded, Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    if (!plain.all { it in 'a'..'z' }) return null
    if (plain.length !in 2..15) return null
    return plain.uppercase()
}
