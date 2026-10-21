package app.wordgame.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.wordgame.android.ApiFailure
import app.wordgame.engine.InvalidReason
import app.wordgame.engine.Premium
import java.io.IOException

/** Own visual identity: slate tiles and a teal/amber/purple board, far from any commercial word game. */
object BoardColors {
    val square = Color(0xFFE8ECEF)
    val tile = Color(0xFF34495E)
    val tileText = Color.White
    val tentativeTile = Color(0xFF2A9D8F)
    val lastMoveTile = Color(0xFF4A6580)
    val center = Color(0xFFCBD5DC)

    fun premium(p: Premium) = when (p) {
        Premium.DOUBLE_LETTER -> Color(0xFFB5D3C7)
        Premium.TRIPLE_LETTER -> Color(0xFFF2C46D)
        Premium.DOUBLE_WORD -> Color(0xFF8FC1E3)
        Premium.TRIPLE_WORD -> Color(0xFFB39CD0)
        Premium.NONE -> square
    }

    fun label(p: Premium) = when (p) {
        Premium.DOUBLE_LETTER -> "LD"
        Premium.TRIPLE_LETTER -> "LT"
        Premium.DOUBLE_WORD -> "MD"
        Premium.TRIPLE_WORD -> "MT"
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

fun Throwable.userMessage(): String = when (this) {
    is ApiFailure -> when (error.code) {
        "UNKNOWN_WORDS" -> "Mot inconnu : ${error.unknownWords.joinToString()}"
        "ILLEGAL_PLACEMENT" -> "Placement invalide"
        "NOT_YOUR_TURN" -> "Ce n'est pas ton tour"
        "STALE_GAME" -> "La partie a changé, elle a été rechargée"
        "BAG_TOO_SMALL" -> "Moins de 7 lettres dans le sac : échange impossible"
        "TILES_NOT_IN_RACK" -> "Ces lettres ne sont pas sur ton chevalet"
        "GAME_FINISHED" -> "La partie est terminée"
        "GAME_STARTED" -> "Les deux joueurs ont déjà joué : la partie ne peut plus être supprimée"
        else -> error.message
    }
    is IOException -> "Serveur injoignable, vérifie ta connexion"
    else -> message ?: "Erreur inattendue"
}

fun InvalidReason.userMessage(): String = when (this) {
    InvalidReason.NO_TILES -> ""
    InvalidReason.NOT_IN_LINE -> "Les lettres doivent être sur une même ligne"
    InvalidReason.GAP -> "Les lettres doivent se suivre"
    InvalidReason.FIRST_MOVE_NOT_ON_CENTER -> "Le premier mot doit passer par le centre"
    InvalidReason.NOT_CONNECTED -> "Le mot doit toucher un mot déjà posé"
    InvalidReason.NO_WORD -> "Un mot fait au moins 2 lettres"
    InvalidReason.UNKNOWN_WORDS -> "Mot inconnu"
    else -> "Placement invalide"
}
