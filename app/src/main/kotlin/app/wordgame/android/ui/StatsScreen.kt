package app.wordgame.android.ui

import app.wordgame.android.attempt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wordgame.android.ApiClient
import app.wordgame.protocol.FinishedGame
import app.wordgame.protocol.GameResult
import app.wordgame.protocol.PlayerStats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH).withZone(ZoneId.systemDefault())

/** Window of the moving average drawn over the per-game scores. */
private const val AVERAGE_WINDOW = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(api: ApiClient, onBack: () -> Unit) {
    var language by remember { mutableStateOf(GameLanguage.FR) }
    var stats by remember { mutableStateOf<PlayerStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(language) {
        stats = null
        error = null
        attempt { api.stats(language.code) }.onSuccess { stats = it }.onFailure { error = it.userMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes statistiques") },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Games in each language are counted apart: the words and the values of the letters differ.
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameLanguage.entries.forEach { l ->
                    FilterChip(selected = language == l, onClick = { language = l }, label = { Text("${l.flag} ${l.label}") })
                }
            }
            val s = stats
            when {
                error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                s == null -> Text("Chargement…", Modifier.padding(16.dp))
                else -> StatsContent(s, Modifier)
            }
        }
    }
}

@Composable
private fun StatsContent(stats: PlayerStats, modifier: Modifier) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Victoires", "${stats.wins}", "sur ${stats.finishedGames} parties")
                StatTile("Score moyen", stats.averageScore?.let { "%.0f".format(it) } ?: "–", "par partie")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Points", stats.pointsPerTurn?.let { "%.1f".format(it) } ?: "–", "par tour")
                StatTile("Scrabbles", "${stats.bingos}", "7 lettres posées")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val helped = stats.history.count { it.hints > 0 }
                StatTile("Indices", "${stats.hintTurns}", if (stats.hintTurns == 1) "tour aidé" else "tours aidés")
                StatTile(
                    "Sans indice",
                    "${stats.history.count { it.result == GameResult.WIN && it.hints == 0 }}",
                    "victoires sur ${stats.finishedGames - helped} parties",
                )
            }
        }

        item { SectionTitle("Progression") }
        item {
            if (stats.history.size < 2) {
                Text(
                    "La courbe apparaîtra après 2 parties terminées.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ScoreChart(stats.history)
            }
        }

        item { SectionTitle("Records") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordLine(
                        "Meilleur coup",
                        stats.bestMove?.let { "${it.words.joinToString()} : ${it.score} pts contre ${it.opponent}" },
                    )
                    RecordLine(
                        "Meilleure partie",
                        stats.bestGame?.let { "${it.myScore} pts contre ${it.opponent} (${DATE.format(Instant.ofEpochMilli(it.finishedAt))})" },
                    )
                    RecordLine("Mot le plus long", stats.longestWord?.let { "$it (${it.length} lettres)" })
                    RecordLine(
                        "Écart moyen",
                        stats.averageMargin?.let { "%+.0f pts par partie".format(it) },
                    )
                }
            }
        }

        if (stats.opponents.isNotEmpty()) {
            item { SectionTitle("Par adversaire") }
            items(stats.opponents) { o ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(o.nickname, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(
                        buildString {
                            append("${o.wins} V · ${o.losses} D")
                            if (o.draws > 0) append(" · ${o.draws} N")
                        },
                    )
                }
            }
        }

        if (stats.history.isNotEmpty()) {
            item { SectionTitle("Parties terminées") }
            // The table behind the chart: every point, readable without the plot.
            items(stats.history.reversed(), key = { it.gameId }) { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(DATE.format(Instant.ofEpochMilli(g.finishedAt)), Modifier.weight(0.25f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (g.hints > 0) "${g.opponent} 💡${g.hints}" else g.opponent, Modifier.weight(0.35f))
                    Text("${g.myScore} – ${g.opponentScore}", Modifier.weight(0.25f))
                    Text(resultLabel(g.result), Modifier.weight(0.15f), fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text("") }
    }
}

private fun resultLabel(r: GameResult) = when (r) {
    GameResult.WIN -> "V"
    GameResult.LOSS -> "D"
    GameResult.DRAW -> "N"
}

@Composable
private fun RowScope.StatTile(label: String, value: String, caption: String) {
    Card(Modifier.weight(1f)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun RecordLine(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "–", style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Score of each finished game (dot: filled = win, hollow = loss or draw) and its moving average (line).
 * One series hue; win/loss is carried by the dot fill, not by color. Tap a dot for the details.
 */
@Composable
private fun ScoreChart(history: List<FinishedGame>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    var selected by remember(history) { mutableStateOf(history.lastIndex) }

    val scores = history.map { it.myScore }
    val averages = scores.indices.map { i -> scores.subList(maxOf(0, i - AVERAGE_WINDOW + 1), i + 1).average() }
    val yMax = (ceil(maxOf(scores.max(), 1) / 100.0) * 100).toFloat()
    // A game can end below zero (tiles left on the rack are subtracted).
    val yMin = if (scores.min() < 0) (floor(scores.min() / 10.0) * 10).toFloat() else 0f

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(200.dp).pointerInput(history) {
                detectTapGestures { tap ->
                    val left = 36.dp.toPx()
                    val step = if (history.size > 1) (size.width - left - 16.dp.toPx()) / (history.size - 1) else 0f
                    selected = history.indices.minBy { abs(left + it * step - tap.x) }
                }
            },
        ) {
            val left = 36.dp.toPx()
            val right = size.width - 16.dp.toPx() // room for the selection ring
            val top = 12.dp.toPx()
            val bottom = size.height - 12.dp.toPx()
            fun x(i: Int) = left + i * (right - left) / (history.size - 1)
            fun y(v: Double) = bottom - ((v - yMin) / (yMax - yMin) * (bottom - top)).toFloat()

            // Recessive grid: bottom, 0 when scores go negative, half, max.
            for (v in listOf(yMin, 0f, yMax / 2, yMax).distinct()) {
                val gy = y(v.toDouble())
                drawLine(gridColor, Offset(left, gy), Offset(right, gy), strokeWidth = 1.dp.toPx())
                val text = measurer.measure(v.toInt().toString(), labelStyle)
                drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), gy - text.size.height / 2))
            }

            val line = Path().apply {
                averages.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
            }
            drawPath(line, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            val r = 4.dp.toPx()
            history.forEachIndexed { i, g ->
                val c = Offset(x(i), y(g.myScore.toDouble()))
                drawCircle(surface, r + 2.dp.toPx(), c) // surface ring keeps dots readable over the line
                if (g.result == GameResult.WIN) drawCircle(dotColor, r, c)
                else drawCircle(dotColor, r - 0.75.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                if (i == selected) drawCircle(lineColor, r + 4.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            }
        }

        // Legend (two series: never color alone).
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("● victoire   ○ défaite", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("— moyenne sur $AVERAGE_WINDOW parties", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        history.getOrNull(selected)?.let { g ->
            Text(
                "${DATE.format(Instant.ofEpochMilli(g.finishedAt))} contre ${g.opponent} : ${g.myScore} – ${g.opponentScore} " +
                    "(${when (g.result) { GameResult.WIN -> "victoire"; GameResult.LOSS -> "défaite"; GameResult.DRAW -> "égalité" }}), " +
                    "moyenne ${"%.0f".format(averages[selected])}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
