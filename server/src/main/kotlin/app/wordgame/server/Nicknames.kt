package app.wordgame.server

import java.text.Normalizer

/**
 * Nicknames refused at sign-up: insults, slurs and names that pass for the staff or the robots. Google Play asks
 * apps showing what players write (here, nicknames) to filter it; the reports (see [GameService.report]) catch
 * what gets through.
 *
 * Words are compared once normalized: lower case, no accents, digits read as letters ("c0nnard") or dropped
 * ("con93"), separators removed, repeated letters collapsed ("connnard"). A [WORDS] entry must be the whole
 * nickname or one of its parts ("Gros_Con", "GrosCon"); a [ROOTS] entry may appear anywhere, so only words that
 * never hide in innocent ones go there.
 */
object Nicknames {
    /** Why [nickname] is refused, or null when it is fine. */
    fun refusal(nickname: String): Text? {
        val parts = nickname.split('_', '-').flatMap { it.split(CAMEL_CASE) }.filter { it.isNotEmpty() }
        val wholes = variants(nickname)
        val all = wholes + parts.flatMap(::variants)
        return when {
            all.any { it in RESERVED } || wholes.any { w -> RESERVED_ROOTS.any { it in w } } -> tr("Ce pseudo est réservé, choisis-en un autre", "This nickname is reserved, choose another one")
            all.any { it in WORDS } || wholes.any { w -> ROOTS.any { it in ALLOWED.fold(w) { s, ok -> s.replace(ok, "") } } } ->
                tr("Ce pseudo n'est pas autorisé, choisis-en un autre", "This nickname is not allowed, choose another one")
            else -> null
        }
    }

    /** [s] normalized with its digits read as letters, then with its digits dropped. */
    private fun variants(s: String): Set<String> {
        val base = plain(s).filter { it != '_' && it != '-' }
        return setOf(base.map { LEET[it] ?: it }.joinToString(""), base.filter { !it.isDigit() }).map(::collapse).toSet()
    }

    /** Lower case, without accents. */
    private fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

    private fun collapse(s: String) = s.replace(REPEATS, "$1")

    private fun normalized(words: String) = words.split(' ').filter { it.isNotBlank() }.map { collapse(plain(it)) }.toSet()

    private val CAMEL_CASE = Regex("(?<=[a-z])(?=[A-Z])")
    private val MARKS = Regex("\\p{M}+")
    private val REPEATS = Regex("(.)\\1+")
    private val LEET = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b', '9' to 'g')

    private val WORDS = normalized(
        // French
        "con conne connard connasse conasse salope salaud pute putain pd pede enculé enculer batard bite couille " +
            "couilles chatte nique niquer ntm fdp tg merde bougnoule negre youpin bicot gouine tapette tarlouze " +
            "nazi hitler sexe porno cul suceur suceuse branleur branlette pouf poufiasse catin pedophile violeur " +
            // English
            "fuck fucker shit bitch cunt dick cock pussy nigger nigga fag faggot whore slut retard rape kkk porn",
    )

    /** Found anywhere in the nickname. */
    private val ROOTS = normalized(
        "encule connard connasse conasse salope enculer fuck nigger nigga hitler nazi pedophil pedofil bougnoul " +
            "youpin batard branl porn bitch fdp ntm",
    )

    /** Innocent words hiding a root ("salopette"). */
    private val ALLOWED = normalized("salopette").toList()

    private val RESERVED = normalized(
        "admin administrateur administrator moderateur moderator modo support staff officiel official system systeme root " +
            "joueursupprime",
    )

    private val RESERVED_ROOTS = normalized("wordwok wordgame nonobot irobot belzebot")
}
