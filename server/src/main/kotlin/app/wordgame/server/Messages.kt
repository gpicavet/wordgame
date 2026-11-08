package app.wordgame.server

import java.util.Locale

/**
 * Language of what the server writes for a player: error messages, names shown in games, notifications.
 * Requests say it with Accept-Language; phones register theirs with their notification token (devices.lang).
 */
enum class Lang(val code: String) {
    FR("fr"),
    EN("en");

    /** [fr] or [en], in this language. */
    fun t(fr: String, en: String) = if (this == EN) en else fr

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: FR

        /**
         * The first of French and English in [header], by preference. Without the header (apps from before English,
         * tools): French. A header asking for neither (Spanish, German…): English.
         */
        fun fromAcceptLanguage(header: String?): Lang {
            if (header.isNullOrBlank()) return FR
            val ranges = runCatching { Locale.LanguageRange.parse(header) }.getOrNull() ?: return FR
            for (range in ranges) {
                when (range.range.substringBefore('-').lowercase()) {
                    "fr" -> return FR
                    "en" -> return EN
                }
            }
            return EN
        }
    }
}

/** A text written in the language of whoever reads it. */
fun interface Text {
    fun of(lang: Lang): String
}

fun tr(fr: String, en: String) = Text { it.t(fr, en) }

/** Shown instead of the nickname of a player who deleted their account. */
fun deletedPlayerName(lang: Lang) = lang.t("Joueur supprimé", "Deleted player")
