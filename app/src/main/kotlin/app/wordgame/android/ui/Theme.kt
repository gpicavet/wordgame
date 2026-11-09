package app.wordgame.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.wordgame.android.ApiFailure
import app.wordgame.android.R
import app.wordgame.android.Strings
import app.wordgame.engine.InvalidReason
import app.wordgame.engine.Premium
import app.wordgame.engine.Refusal
import app.wordgame.engine.Refusals
import java.io.IOException

/** Own visual identity: slate tiles and a teal/amber/purple board, far from any commercial word game. */
object BoardColors {
    val square = Color(0xFFE8ECEF)
    val tile = Color(0xFF34495E)
    val tileText = Color.White
    val tentativeTile = Color(0xFF2A9D8F)
    val lastMoveTile = Color(0xFF4A6580)
    /** The player's own last move: a dark teal, the color of the tiles being placed, once played. */
    val myMoveTile = Color(0xFF1F6F66)
    val center = Color(0xFFCBD5DC)
    /** Scores in the players' header: a warm color that stands out from the teal of the interface. */
    val score = Color(0xFFC2410C)

    fun premium(p: Premium) = when (p) {
        Premium.DOUBLE_LETTER -> Color(0xFFB5D3C7)
        Premium.TRIPLE_LETTER -> Color(0xFFF2C46D)
        Premium.DOUBLE_WORD -> Color(0xFF8FC1E3)
        Premium.TRIPLE_WORD -> Color(0xFFB39CD0)
        Premium.NONE -> square
    }

    /** "DL", "TW"… in English, "LD", "MT"… in French. */
    fun label(p: Premium) = when (p) {
        Premium.DOUBLE_LETTER -> Strings.get(R.string.premium_double_letter)
        Premium.TRIPLE_LETTER -> Strings.get(R.string.premium_triple_letter)
        Premium.DOUBLE_WORD -> Strings.get(R.string.premium_double_word)
        Premium.TRIPLE_WORD -> Strings.get(R.string.premium_triple_word)
        Premium.NONE -> ""
    }
}

@Composable
fun WordgameTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF5CC8B8), secondary = Color(0xFFF2C46D))
    } else {
        lightColorScheme(primary = Color(0xFF1F7A6F), secondary = Color(0xFFB7791F))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

fun Throwable.userMessage(refusals: Refusals? = null): String = when (this) {
    is ApiFailure -> when (error.code) {
        "UNKNOWN_WORDS" -> Strings.get(R.string.err_refused, refusedWords(error.unknownWords, refusals))
        "ILLEGAL_PLACEMENT" -> Strings.get(R.string.err_illegal_placement)
        "NOT_YOUR_TURN" -> Strings.get(R.string.err_not_your_turn)
        "STALE_GAME" -> Strings.get(R.string.err_stale)
        "BAG_TOO_SMALL" -> Strings.get(R.string.err_bag_too_small)
        "TILES_NOT_IN_RACK" -> Strings.get(R.string.err_tiles_not_in_rack)
        "GAME_FINISHED" -> Strings.get(R.string.err_game_finished)
        "GAME_STARTED" -> Strings.get(R.string.err_game_started)
        // The server's message, in the language the app asked for.
        else -> error.message
    }
    is IOException -> Strings.get(R.string.err_offline)
    else -> message ?: Strings.get(R.string.err_unexpected)
}

/** "OCTOBER (nom propre), XQZ (n'existe pas)". */
fun refusedWords(words: List<String>, refusals: Refusals?): String =
    words.joinToString { word -> "$word (${refusals?.get(word).userMessage()})" }

fun Refusal?.userMessage(): String = when (this) {
    null -> Strings.get(R.string.refusal_none)
    Refusal.PROPER_NOUN -> Strings.get(R.string.refusal_proper_noun)
    Refusal.ABBREVIATION -> Strings.get(R.string.refusal_abbreviation)
    Refusal.COMPOUND -> Strings.get(R.string.refusal_compound)
    Refusal.MISSPELLING -> Strings.get(R.string.refusal_misspelling)
    Refusal.AFFIX -> Strings.get(R.string.refusal_affix)
    Refusal.LOCUTION -> Strings.get(R.string.refusal_locution)
    Refusal.NOT_LISTED -> Strings.get(R.string.refusal_not_listed)
}

fun InvalidReason.userMessage(): String = when (this) {
    InvalidReason.NO_TILES -> ""
    InvalidReason.NOT_IN_LINE -> Strings.get(R.string.invalid_not_in_line)
    InvalidReason.GAP -> Strings.get(R.string.invalid_gap)
    InvalidReason.FIRST_MOVE_NOT_ON_CENTER -> Strings.get(R.string.invalid_first_move)
    InvalidReason.NOT_CONNECTED -> Strings.get(R.string.invalid_not_connected)
    InvalidReason.NO_WORD -> Strings.get(R.string.invalid_no_word)
    InvalidReason.UNKNOWN_WORDS -> Strings.get(R.string.invalid_unknown_word)
    else -> Strings.get(R.string.err_illegal_placement)
}
