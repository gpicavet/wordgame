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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wordgame.android.R
import app.wordgame.engine.Hints
import app.wordgame.engine.ScrabbleHint

/** Scrabbles shown at most: past a few, the hardest ones are no help. */
private const val SHOWN_SCRABBLES = 5

@Composable
fun ScrabbleHint.level() = stringResource(
    when {
        difficulty == 0 -> R.string.hints_easy
        difficulty <= 2 -> R.string.hints_medium
        else -> R.string.hints_hard
    },
)

/** How to find it, without the word: "Common word · your 7 tiles alone · 74 points". */
@Composable
fun ScrabbleHint.description() = listOfNotNull(
    stringResource(if (common) R.string.hints_common else R.string.hints_rare),
    if (boardLetters == 0) stringResource(R.string.hints_rack_only)
    else pluralStringResource(R.plurals.hints_board_letters, boardLetters, boardLetters),
    if (crossWords == 0) null else pluralStringResource(R.plurals.hints_cross_words, crossWords, crossWords),
    stringResource(R.string.hints_points, score),
).joinToString(" · ")

/** Hints for the player's turn against robots: the scrabbles of the rack (each word hidden until asked) and the best score. */
@Composable
fun HintsDialog(load: suspend () -> Hints?, onDismiss: () -> Unit) {
    var hints by remember { mutableStateOf<Hints?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { hints = load(); loaded = true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hints_title)) },
        text = {
            val h = hints
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !loaded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.hints_searching))
                    }
                    h == null -> Text(stringResource(R.string.hints_unavailable))
                    h.scrabbles.isEmpty() -> Text(stringResource(R.string.hints_no_bingo))
                    else -> {
                        val n = h.scrabbles.size
                        Text(pluralStringResource(R.plurals.hints_bingo_count, n, n), fontWeight = FontWeight.Bold)
                        h.scrabbles.take(SHOWN_SCRABBLES).forEach { s ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (s.word in revealed) "${s.level()} · ${s.word}" else s.level(), fontWeight = FontWeight.Bold)
                                    Text(s.description(), style = MaterialTheme.typography.bodySmall)
                                }
                                if (s.word !in revealed) TextButton(onClick = { revealed = revealed + s.word }) { Text(stringResource(R.string.hints_show)) }
                            }
                        }
                        if (n > SHOWN_SCRABBLES) Text(pluralStringResource(R.plurals.hints_more, n - SHOWN_SCRABBLES, n - SHOWN_SCRABBLES), style = MaterialTheme.typography.bodySmall)
                    }
                }
                h?.bestScore?.let { Text(stringResource(R.string.hints_best_score, it), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
