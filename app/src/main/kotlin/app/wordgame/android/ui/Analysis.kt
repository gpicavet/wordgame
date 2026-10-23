package app.wordgame.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.wordgame.engine.Board
import app.wordgame.engine.Candidate
import app.wordgame.engine.Lexicon
import app.wordgame.engine.MoveType
import app.wordgame.engine.RuleSet
import app.wordgame.engine.Solver
import app.wordgame.protocol.GameView
import app.wordgame.protocol.MoveView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One of the player's moves next to the best ones that were possible with the same rack. */
data class MoveAnalysis(val move: MoveView, val best: List<Candidate>) {
    val played: String get() = when (move.type) {
        MoveType.PLAY -> "${move.words.first().word} (${move.score})"
        MoveType.EXCHANGE -> "Échange de ${move.exchangedCount} lettre(s)"
        MoveType.PASS -> "Passe"
        else -> move.type.name
    }
    val bestScore get() = best.firstOrNull()?.score ?: 0
    val wasBest get() = move.type == MoveType.PLAY && move.score >= bestScore
}

/**
 * Replays the game to get the board before each of [seat]'s moves and runs the solver on the rack they had.
 * Moves played before racks were recorded have no rack: they are skipped.
 */
suspend fun analyse(game: GameView, lexicon: Lexicon, seat: Int, onlyLast: Boolean = false): List<MoveAnalysis> =
    withContext(Dispatchers.Default) {
        val rules = RuleSet.byId(game.rulesId)
        val solver = Solver(lexicon, rules)
        val analysed = game.moves.withIndex().filter { (_, m) ->
            m.seat == seat && m.rack != null && m.type in setOf(MoveType.PLAY, MoveType.PASS, MoveType.EXCHANGE)
        }
        val targets = if (onlyLast) analysed.takeLast(1) else analysed
        targets.map { (index, move) ->
            val before = game.moves.take(index).fold(Board.empty(rules.boardSize)) { b, m -> b.with(m.tiles) }
            MoveAnalysis(move, solver.bestMoves(before, move.rack!!, 5))
        }
    }

private fun Candidate.label() = "${words.joinToString { it.word }} : $score pts" + if (tiles.any { it.blank }) " (joker)" else ""

/** Line under the board after the player's last move. */
@Composable
fun LastMoveAnalysisText(analysis: MoveAnalysis, modifier: Modifier = Modifier) {
    val best = analysis.best.firstOrNull()
    val text = when {
        best == null -> "Ton coup : ${analysis.played} · aucun mot n'était possible"
        analysis.wasBest -> "Ton coup : ${analysis.played} · c'était le meilleur coup !"
        else -> "Ton coup : ${analysis.played} · meilleur possible : ${best.mainWord} (${best.score}) ›"
    }
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2)
}

@Composable
fun BestMovesDialog(analysis: MoveAnalysis, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Meilleurs coups possibles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Avec ${analysis.move.rack?.replace('?', '★')} · tu as joué ${analysis.played}", style = MaterialTheme.typography.bodySmall)
                analysis.best.forEachIndexed { i, c -> Text("${i + 1}. ${c.label()}") }
                if (analysis.best.isEmpty()) Text("Aucun mot n'était possible.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

/** Whole-game review: every move of the player against the 5 best possible ones. */
@Composable
fun GameAnalysisDialog(game: GameView, lexicon: Lexicon, onDismiss: () -> Unit) {
    var analyses by remember(game.id) { mutableStateOf<List<MoveAnalysis>?>(null) }
    LaunchedEffect(game.id) { analyses = analyse(game, lexicon, game.mySeat) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Analyse de la partie", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Fermer") }
                }
                val list = analyses
                when {
                    list == null -> Text("Calcul des meilleurs coups…")
                    list.isEmpty() -> Text("Aucun coup à analyser : les chevalets n'étaient pas encore enregistrés pour cette partie.")
                    else -> {
                        val scored = list.sumOf { if (it.move.type == MoveType.PLAY) it.move.score else 0 }
                        val possible = list.sumOf { it.bestScore }
                        Text(
                            "Tu as marqué $scored points sur $possible possibles" +
                                (if (possible > 0) " (${scored * 100 / possible} %)" else "") +
                                " · ${list.count { it.wasBest }} meilleur(s) coup(s) sur ${list.size}",
                            fontWeight = FontWeight.Bold,
                        )
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(list, key = { it.move.moveNumber }) { a ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Coup ${a.move.moveNumber} · chevalet ${a.move.rack?.replace('?', '★')}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            "Joué : ${a.played}" + if (a.wasBest) " ✓" else "",
                                            fontWeight = FontWeight.Bold,
                                            modifier = if (a.wasBest) Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp) else Modifier,
                                        )
                                        a.best.forEachIndexed { i, c -> Text("${i + 1}. ${c.label()}", style = MaterialTheme.typography.bodyMedium) }
                                        if (a.best.isEmpty()) Text("Aucun mot n'était possible.", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
