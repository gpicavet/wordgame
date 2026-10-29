package app.wordgame.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import app.wordgame.engine.Criterion
import app.wordgame.engine.Lexicon
import app.wordgame.engine.MoveReview
import app.wordgame.engine.MoveType
import app.wordgame.engine.Placement
import app.wordgame.engine.Premium
import app.wordgame.engine.RuleSet
import app.wordgame.engine.Solver
import app.wordgame.engine.Tip
import app.wordgame.engine.TipKind
import app.wordgame.protocol.GameView
import app.wordgame.protocol.MoveView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One of the player's moves next to the best ones that were possible with the same rack, on the board [before] it.
 * [review]: its grade and advice, in games against one robot only (see [coached]).
 */
data class MoveAnalysis(val move: MoveView, val best: List<Candidate>, val before: Board, val review: MoveReview? = null) {
    val played: String get() = when (move.type) {
        MoveType.PLAY -> "${move.words.first().word} (${move.score})"
        MoveType.EXCHANGE -> "Échange de ${move.exchangedCount} lettre(s)"
        MoveType.PASS -> "Passe"
        else -> move.type.name
    }
    val bestScore get() = best.firstOrNull()?.score ?: 0
    val wasBest get() = move.type == MoveType.PLAY && move.score >= bestScore

    /**
     * Shown under the player's last move: only the best possible score, not the word, which could
     * still be played. The moves themselves are shown in the end-of-game analysis.
     */
    val verdict: String get() = when {
        best.isEmpty() -> "aucun mot n'était possible"
        wasBest -> "c'était le meilleur coup !"
        else -> "meilleur coup : $bestScore points"
    }
}

/** One against one robot: the games where each move gets a grade, to learn from the robot. */
val GameView.coached get() = players.size == 2 && players[1 - mySeat].bot

/**
 * Replays the game to get the board before each of [seat]'s moves and runs the solver on the rack they had.
 * Moves played before racks were recorded have no rack: they are skipped.
 * With [coachWeights] (BelzéBot's), plays and passes also get a [MoveReview].
 */
suspend fun analyse(
    game: GameView, lexicon: Lexicon, seat: Int, onlyLast: Boolean = false, coachWeights: DoubleArray? = null,
): List<MoveAnalysis> =
    withContext(Dispatchers.Default) {
        val rules = RuleSet.byId(game.rulesId)
        val solver = Solver(lexicon, rules)
        val analysed = game.moves.withIndex().filter { (_, m) ->
            m.seat == seat && m.rack != null && m.type in setOf(MoveType.PLAY, MoveType.PASS, MoveType.EXCHANGE)
        }
        val targets = if (onlyLast) analysed.takeLast(1) else analysed
        targets.map { (index, move) ->
            val before = game.moves.take(index).fold(Board.empty(rules.boardSize)) { b, m -> b.with(m.tiles) }
            // Each play draws as many tiles as it placed, while the bag lasts (exchanges leave it as it was).
            val bagBefore = rules.fullBag().length - rules.rackSize * game.players.size - game.moves.take(index).sumOf { it.tiles.size }
            val review = if (coachWeights == null || move.type == MoveType.EXCHANGE) null else MoveReview.of(
                before, move.rack!!, move.tiles, move.score, maxOf(0, bagBefore), rules, lexicon, coachWeights,
            )
            MoveAnalysis(move, solver.bestMoves(before, move.rack!!, 5), before, review)
        }
    }

private fun Candidate.label() = "${words.joinToString { it.word }} : $score pts" + if (tiles.any { it.blank }) " (joker)" else ""

/** The board before a move, with [tiles] (the move played or a better one) highlighted. */
@Composable
fun MoveOnBoardDialog(title: String, before: Board, tiles: List<Placement>, rules: RuleSet, onDismiss: () -> Unit) {
    val placed = tiles.associateBy { it.row to it.col }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().aspectRatio(1f)) {
                for (r in 0 until before.size) {
                    Row(Modifier.weight(1f)) {
                        for (c in 0 until before.size) {
                            val tile = placed[r to c]
                            val cell = before[r, c]
                            val premium = rules.layout.premiumAt(r, c)
                            val isCenter = r == rules.layout.center && c == rules.layout.center
                            Square(
                                letter = tile?.letter ?: cell.takeIf { it != Board.EMPTY }?.uppercaseChar(),
                                points = when {
                                    tile != null -> if (tile.blank) 0 else rules.points(tile.letter)
                                    cell == Board.EMPTY || cell.isLowerCase() -> 0
                                    else -> rules.points(cell)
                                },
                                background = when {
                                    tile != null -> BoardColors.tentativeTile
                                    cell != Board.EMPTY -> BoardColors.tile
                                    isCenter && premium == Premium.NONE -> BoardColors.center
                                    else -> BoardColors.premium(premium)
                                },
                                label = if (isCenter) "★" else BoardColors.label(premium),
                                onClick = {},
                                dragModifier = Modifier,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(8.dp),
    )
}

fun MoveReview.label() = when (grade) {
    10 -> "Parfait"
    8, 9 -> "Très bien"
    6, 7 -> "Bien"
    4, 5 -> "Moyen"
    else -> "À revoir"
}

fun Tip.text() = when (kind) {
    TipKind.MORE_POINTS -> "Un autre coup rapportait $amount points de plus."
    TipKind.Q_WITHOUT_U -> "Tu gardes un Q sans U : il est très difficile à placer."
    TipKind.BLANK_SPENT -> "Tu as joué un joker pour peu de points : garde-le plutôt pour un scrabble."
    TipKind.TOO_MANY_VOWELS -> "Tu gardes trop de voyelles : vise autant de voyelles que de consonnes."
    TipKind.TOO_MANY_CONSONANTS -> "Tu gardes trop de consonnes : vise autant de voyelles que de consonnes."
    TipKind.DUPLICATES -> "Tu gardes des lettres en double : elles se combinent mal."
    TipKind.WEAK_LEAVE -> "Les lettres que tu gardes se placent mal : un autre coup laissait un meilleur tirage."
    TipKind.OPENS_TRIPLE_WORD -> "Ton coup ouvre une case mot compte triple à l'adversaire."
    TipKind.OPENS_PREMIUMS -> "Ton coup ouvre des cases bonus à l'adversaire."
    TipKind.ENDGAME_LEAVE -> "Le sac est vide : place d'abord tes lettres chères, elles comptent contre toi à la fin."
}

fun Criterion.label() = when (this) {
    Criterion.SCORE -> "Points marqués"
    Criterion.LEAVE -> "Lettres gardées"
    Criterion.BOARD -> "Cases laissées à l'adversaire"
    Criterion.BAG -> "Tirage et fin de partie"
}

/** The 3 criteria that weigh most in the grade: "Points marqués : −20", in points against the best move. */
fun MoveReview.mainCriteria(): List<String> = criteria.take(3).map { (criterion, points) ->
    val rounded = points.roundToInt()
    "${criterion.label()} : ${if (rounded > 0) "+" else if (rounded < 0) "−" else ""}${abs(rounded)}"
}

/** The advice of a review, or praise when there is nothing to say. */
fun MoveReview.advice(): List<String> = when {
    tips.isNotEmpty() -> tips.map { it.text() }
    grade == 10 -> listOf("C'était le meilleur coup !")
    else -> listOf("Un autre coup était un peu meilleur, en points et en lettres gardées.")
}

/** Grade of the player's last move, its advice and the best possible score (not the word, which could still be played). */
@Composable
fun MoveReviewDialog(analysis: MoveAnalysis, review: MoveReview, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${review.grade}/10 · ${review.label()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ton coup : ${analysis.played}", fontWeight = FontWeight.Bold)
                review.advice().forEach { Text(it) }
                if (review.criteria.isNotEmpty()) {
                    Text("Écart avec le meilleur coup, en points :", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    review.mainCriteria().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (analysis.best.isNotEmpty()) Text("Meilleur score possible : ${analysis.bestScore} points", style = MaterialTheme.typography.bodySmall)
                Text(
                    "La note compare ton coup au meilleur selon BelzéBot : les points marqués, mais aussi les lettres gardées et les cases laissées à l'adversaire.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Whole-game review: every move of the player against the 5 best possible ones, each shown on the board on a tap. */
@Composable
fun GameAnalysisDialog(game: GameView, lexicon: Lexicon, coachWeights: DoubleArray?, onDismiss: () -> Unit) {
    var analyses by remember(game.id) { mutableStateOf<List<MoveAnalysis>?>(null) }
    var shown by remember { mutableStateOf<Triple<String, Board, List<Placement>>?>(null) }
    val rules = RuleSet.byId(game.rulesId)
    LaunchedEffect(game.id) { analyses = analyse(game, lexicon, game.mySeat, coachWeights = coachWeights) }

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
                        val grades = list.mapNotNull { it.review?.grade }
                        if (grades.isNotEmpty()) Text("Note moyenne : ${"%.1f".format(grades.average())}/10", fontWeight = FontWeight.Bold)
                        Text("Touche un coup pour le voir sur le plateau.", style = MaterialTheme.typography.bodySmall)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(list, key = { it.move.moveNumber }) { a ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Coup ${a.move.moveNumber} · chevalet ${a.move.rack?.replace('?', '★')}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            "Joué : ${a.played}" + if (a.wasBest) " ✓" else "",
                                            fontWeight = FontWeight.Bold,
                                            modifier = (if (a.wasBest) Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp) else Modifier)
                                                .then(if (a.move.tiles.isNotEmpty()) Modifier.clickable { shown = Triple("Joué : ${a.played}", a.before, a.move.tiles) } else Modifier),
                                        )
                                        a.best.forEachIndexed { i, c ->
                                            Text(
                                                "${i + 1}. ${c.label()} ›",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.fillMaxWidth().clickable { shown = Triple(c.label(), a.before, c.tiles) }.padding(vertical = 2.dp),
                                            )
                                        }
                                        if (a.best.isEmpty()) Text("Aucun mot n'était possible.", style = MaterialTheme.typography.bodyMedium)
                                        a.review?.let { r ->
                                            Text("Note : ${r.grade}/10 · ${r.label()}", fontWeight = FontWeight.Bold)
                                            r.advice().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                                            if (r.criteria.isNotEmpty()) Text(r.mainCriteria().joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    shown?.let { (title, before, tiles) -> MoveOnBoardDialog(title, before, tiles, rules) { shown = null } }
}

