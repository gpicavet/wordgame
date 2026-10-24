package app.wordgame.dict

import app.wordgame.engine.DawgBuilder
import java.io.File
import java.text.Normalizer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Builds the French word list from the Grammalecte lexicon (MPL 2.0, https://grammalecte.net/):
 * inflected forms, accents removed, uppercase, 2 to 15 letters, no proper nouns, no hyphens or apostrophes.
 *
 * Also excluded: symbols and written abbreviations (km, kg, cf…), suffixes, error forms, and words that only
 * exist inside a Latin or foreign locution (dictionaries/locutions-exclues.txt: the "hoc" of "ad hoc").
 * Clipped words (topo, resto) and French locution words (jeun, antan) stay.
 * A word stays if at least one of its entries passes.
 * Then applies <out>/additions.txt and <out>/removals.txt (one word per line, # comments),
 * the hand-curated corrections fed by player reports.
 *
 * Outputs <out>/bin/fr-libre.dawg (bundled in the app, loaded by the server)
 * and <out>/fr-libre.txt.gz (the derived list, published to comply with the MPL).
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: <lexique-grammalecte-fr.txt> <output-dir>" }
    val source = File(args[0])
    val out = File(args[1])

    val words = sortedSetOf<String>()
    // Everyday vocabulary for the beginner bot: forms with a frequency index of at least COMMON_INDEX.
    val common = sortedSetOf<String>()
    val locutionOnly = HashSet<String>()
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
            val notes = cols[h.indexOf("Notes")].split(' ')
            if (tags.any { it in EXCLUDED_TAGS }) continue
            if (notes.any { it in EXCLUDED_NOTES }) continue
            val word = normalize(form) ?: continue
            // Words that only exist inside a locution: kept unless listed as Latin/foreign (see below).
            if (tags.any { it.startsWith("loc.") }) {
                locutionOnly.add(word)
                continue
            }
            words.add(word)
            if ((cols[h.indexOf("Indice de fréquence")].toIntOrNull() ?: 0) >= COMMON_INDEX) common.add(word)
        }
    }
    val excludedLocutions = readWordFile(File(out, "locutions-exclues.txt"))
    words.addAll(locutionOnly - excludedLocutions)
    val fromLexicon = words.size
    // Words from the French Wiktionary (CC BY-SA), extracted by tools/wiktionnaire.py.
    val wiktionary = File(out, "fr-wiktionnaire.txt.gz").takeIf { it.exists() }
        ?.let { f -> GZIPInputStream(f.inputStream()).bufferedReader().readLines().filter { it.isNotBlank() }.toSet() }
        .orEmpty()
    val beforeWiktionary = words.size
    words.addAll(wiktionary)
    println("+${words.size - beforeWiktionary} words from the Wiktionary list (${wiktionary.size} in it)")
    val additions = readWordFile(File(out, "additions.txt"))
    val removals = readWordFile(File(out, "removals.txt"))
    words.addAll(additions)
    words.removeAll(removals)
    println("$fromLexicon words from the lexicon, +${additions.size} additions, -${removals.size} removals")

    val builder = DawgBuilder()
    words.forEach(builder::add)
    val dawg = builder.build()

    File(out, "bin").mkdirs()
    File(out, "bin/fr-libre.dawg").writeBytes(dawg.toBytes())
    common.retainAll(words)
    val commonDawg = DawgBuilder().apply { common.forEach(::add) }.build()
    File(out, "bin/fr-courant.dawg").writeBytes(commonDawg.toBytes())
    println("${common.size} common words, ${commonDawg.edgeCount * 4 / 1024} KiB")
    GZIPOutputStream(File(out, "fr-libre.txt.gz").outputStream()).bufferedWriter().use { w ->
        words.forEach { w.write(it); w.newLine() }
    }
    println("${words.size} words, ${dawg.edgeCount} edges, ${dawg.edgeCount * 4 / 1024} KiB")
}

/** Grammalecte frequency index (0-9, from large corpora): 6+ reads as everyday vocabulary. */
private const val COMMON_INDEX = 6

/** Proper nouns, first names, surnames, affixes and miscellaneous entries. */
private val EXCLUDED_TAGS = setOf("npr", "prn_npr", "patr", "prenom", "nompr", "sfx", "pfx", "div", "err")

/**
 * Symbols (km, kg…) and written abbreviations (cf, janv, cos…).
 * Not "abr": those are clipped words used as words (accro, ado, apéro, topo, resto).
 */
private val EXCLUDED_NOTES = setOf("symb", "abty")

private fun readWordFile(file: File): Set<String> {
    if (!file.exists()) return emptySet()
    return file.readLines().flatMap { it.substringBefore('#').split(' ', '\t') }.map { it.trim() }.filter { it.isNotEmpty() }
        .map { line -> requireNotNull(normalize(line.lowercase())) { "Invalid word in ${file.name}: $line" } }
        .toSet()
}

private val LIGATURES = mapOf('œ' to "oe", 'æ' to "ae")

/** "Œuvrés" -> null (capitalized = proper noun or acronym), "œuvrés" -> "OEUVRES", "porte-clé" -> null. */
fun normalize(form: String): String? {
    // Single characters are letter names ("œ"), not words, even once the ligature is expanded.
    if (form.length < 2 || !form[0].isLowerCase()) return null
    val expanded = buildString { form.forEach { append(LIGATURES[it] ?: it) } }
    val plain = Normalizer.normalize(expanded, Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    if (!plain.all { it in 'a'..'z' }) return null
    if (plain.length !in 2..15) return null
    return plain.uppercase()
}
