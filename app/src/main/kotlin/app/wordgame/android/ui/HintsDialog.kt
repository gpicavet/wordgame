package app.wordgame.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wordgame.engine.Hints
import app.wordgame.engine.ScrabbleHint

/** Scrabbles shown at most: past a few, the hardest ones are no help. */
private const val SHOWN_SCRABBLES = 5

fun ScrabbleHint.level() = when {
    difficulty == 0 -> "Facile"
    difficulty <= 2 -> "Moyen"
    else -> "Difficile"
}

/** How to find it, without the word: "Mot courant · tes 7 lettres seules · 74 points". */
fun ScrabbleHint.description() = listOf(
    if (common) "Mot courant" else "Mot rare",
    when (boardLetters) {
        0 -> "tes 7 lettres seules"
        1 -> "avec 1 lettre de la grille"
        else -> "avec $boardLetters lettres de la grille"
    },
    when (crossWords) {
        0 -> null
        1 -> "forme 1 autre mot"
        else -> "forme $crossWords autres mots"
    },
    "$score points",
).filterNotNull().joinToString(" · ")

/** Hints for the player's turn against robots: the scrabbles of the rack (each word hidden until asked) and the best score. */
@Composable
fun HintsDialog(load: suspend () -> Hints?, onDismiss: () -> Unit) {
    var hints by remember { mutableStateOf<Hints?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { hints = load(); loaded = true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Indices") },
        text = {
            val h = hints
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !loaded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Recherche…")
                    }
                    h == null -> Text("Indices indisponibles.")
                    h.scrabbles.isEmpty() -> Text("Aucun scrabble possible avec ces lettres.")
                    else -> {
                        val n = h.scrabbles.size
                        Text(if (n == 1) "1 scrabble possible :" else "$n scrabbles possibles :", fontWeight = FontWeight.Bold)
                        h.scrabbles.take(SHOWN_SCRABBLES).forEach { s ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (s.word in revealed) "${s.level()} · ${s.word}" else s.level(), fontWeight = FontWeight.Bold)
                                    Text(s.description(), style = MaterialTheme.typography.bodySmall)
                                }
                                if (s.word !in revealed) TextButton(onClick = { revealed = revealed + s.word }) { Text("Voir") }
                            }
                        }
                        if (n > SHOWN_SCRABBLES) Text("…et ${n - SHOWN_SCRABBLES} plus difficiles.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                h?.bestScore?.let { Text("Meilleur score possible : $it points", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
