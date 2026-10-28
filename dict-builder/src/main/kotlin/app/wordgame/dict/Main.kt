package app.wordgame.dict

import app.wordgame.engine.DawgBuilder
import app.wordgame.engine.Refusal
import app.wordgame.engine.Refusals
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
 * Outputs <out>/bin/fr-libre.dawg (bundled in the app, loaded by the server),
 * <out>/bin/fr-refus.txt.gz (why the excluded entries were excluded, shown to the player by the app)
 * and <out>/fr-libre.txt.gz (the derived list, published to comply with the MPL).
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "en") return buildEnglish(args.drop(1))
    require(args.size == 2) { "usage: <lexique-grammalecte-fr.txt> <output-dir> | en <enable1.txt> <scowl-final-dir> <output-dir>" }
    val source = File(args[0])
    val out = File(args[1])

    val words = sortedSetOf<String>()
    // Everyday vocabulary for the beginner bot: forms with a frequency index of at least COMMON_INDEX.
    val common = sortedSetOf<String>()
    val locutionOnly = HashSet<String>()
    val refused = RefusalCollector()
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
            frenchRefusal(form, tags, notes)?.let { refused.note(form, it) }
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
    (locutionOnly intersect excludedLocutions).forEach { refused.note(it, Refusal.LOCUTION) }
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
    refused.write(File(out, "bin/fr-refus.txt.gz"), words)
    GZIPOutputStream(File(out, "fr-libre.txt.gz").outputStream()).bufferedWriter().use { w ->
        words.forEach { w.write(it); w.newLine() }
    }
    println("${words.size} words, ${dawg.edgeCount} edges, ${dawg.edgeCount * 4 / 1024} KiB")
}

/**
 * Builds the English word list: ENABLE (public domain), 2 to 15 letters, as <out>/bin/en-enable.dawg.
 * Everyday vocabulary for the beginner bot (<out>/bin/en-courant.dawg): the ENABLE words that are also in
 * the SCOWL lists (Kevin Atkinson, permissive license) of size 35 or less, English and American spellings.
 * Why other words are refused (<out>/bin/en-refus.txt.gz): the SCOWL lists of proper nouns, abbreviations and
 * contractions, and the SCOWL words of size [REFUSAL_SCOWL_SIZE] or less that ENABLE does not have.
 */
private fun buildEnglish(args: List<String>) {
    require(args.size == 3) { "usage: en <enable1.txt> <scowl-final-dir> <output-dir>" }
    val (enable, scowl, out) = args.map(::File)
    val words = enable.readLines().map { it.trim().uppercase() }.filter { w -> w.length in 2..15 && w.all { it in 'A'..'Z' } }.toSortedSet()
    val common = scowl.listFiles()!!
        .filter { f -> f.name.startsWith("english-words.") || f.name.startsWith("american-words.") }
        .filter { f -> f.name.substringAfterLast('.').toInt() <= COMMON_SCOWL_SIZE }
        .flatMap { it.readLines(Charsets.ISO_8859_1) }
        .map { it.trim() }
        .filter { w -> w.isNotEmpty() && w.all { it in 'a'..'z' } }
        .map { it.uppercase() }
        .filterTo(sortedSetOf()) { it in words }
    File(out, "bin").mkdirs()
    val dawg = DawgBuilder().apply { words.forEach(::add) }.build()
    File(out, "bin/en-enable.dawg").writeBytes(dawg.toBytes())
    val refused = RefusalCollector()
    for (f in scowl.listFiles()!!.sortedBy { it.name }) {
        if (f.name.substringAfterLast('.').toInt() > REFUSAL_SCOWL_SIZE) continue
        val kind = f.name.substringBefore('.').substringAfter('-')
        for (entry in f.readLines(Charsets.ISO_8859_1).map { it.trim() }) {
            if (entry.isEmpty() || entry.endsWith("'s")) continue // possessives: "October's" is not OCTOBERS
            refused.note(entry, when {
                kind == "abbreviations" || entry.length > 1 && entry.all { it.isUpperCase() } -> Refusal.ABBREVIATION
                kind == "upper" || kind == "proper-names" -> Refusal.PROPER_NOUN
                kind == "contractions" || entry.any { it in "-'" } -> Refusal.COMPOUND
                else -> Refusal.NOT_LISTED
            })
        }
    }
    refused.write(File(out, "bin/en-refus.txt.gz"), words)
    val commonDawg = DawgBuilder().apply { common.forEach(::add) }.build()
    File(out, "bin/en-courant.dawg").writeBytes(commonDawg.toBytes())
    println("${words.size} words, ${dawg.edgeCount * 4 / 1024} KiB; ${common.size} common words, ${commonDawg.edgeCount * 4 / 1024} KiB")
}

/** SCOWL sizes up to 35: the words of an ordinary small dictionary. */
private const val COMMON_SCOWL_SIZE = 35

/** SCOWL sizes up to 70: the words of a large dictionary, beyond that names and words few players would try. */
private const val REFUSAL_SCOWL_SIZE = 70

/** Reason of a Grammalecte entry that the French list leaves out (the filters of [main] and [normalize]). */
private fun frenchRefusal(form: String, tags: List<String>, notes: List<String>): Refusal? = when {
    tags.any { it in PROPER_NOUN_TAGS } -> Refusal.PROPER_NOUN
    notes.any { it in EXCLUDED_NOTES } -> Refusal.ABBREVIATION
    form.length > 1 && form.filter { it.isLetter() }.all { it.isUpperCase() } -> Refusal.ABBREVIATION // SNCF
    form[0].isUpperCase() -> Refusal.PROPER_NOUN
    "err" in tags -> Refusal.MISSPELLING
    tags.any { it == "sfx" || it == "pfx" } -> Refusal.AFFIX
    form.any { it in "-'’" } -> Refusal.COMPOUND
    else -> null
}

private val PROPER_NOUN_TAGS = setOf("npr", "prn_npr", "patr", "prenom", "nompr")

/**
 * Refused words keyed the way a player would type them: "Saint-Malo" -> SAINTMALO, "aujourd'hui" -> AUJOURDHUI.
 * A word refused for several reasons keeps the first one in [Refusal] order (proper noun before compound…).
 */
private class RefusalCollector {
    private val reasons = HashMap<String, Refusal>()

    fun note(form: String, reason: Refusal) {
        val expanded = buildString { form.lowercase().forEach { append(LIGATURES[it] ?: it) } }
        val key = Normalizer.normalize(expanded, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() && it !in "-'’. " }
        if (key.length !in 2..15 || !key.all { it in 'a'..'z' }) return
        reasons.merge(key.uppercase(), reason, ::minOf)
    }

    /** Writes the words that are not in [dictionary], gzipped, and prints the count per reason. */
    fun write(file: File, dictionary: Set<String>) {
        val refusals = Refusals(reasons.filterKeys { it !in dictionary })
        GZIPOutputStream(file.outputStream()).bufferedWriter().use { it.write(refusals.toText()) }
        val counts = reasons.filterKeys { it !in dictionary }.values.groupingBy { it }.eachCount()
        println("${refusals.size} refused words ($counts), ${file.length() / 1024} KiB")
    }
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
