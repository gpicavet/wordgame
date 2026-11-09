package app.wordgame.android.ui

import android.text.format.DateFormat
import app.wordgame.android.R
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** "8 nov." in French, "Nov 8" in English: day and short month in the order of the phone's language. */
/** "8 nov.", with the year when it is not this one ("8 nov. 2025"). */
internal fun date(millis: Long): String {
    val locale = Locale.getDefault()
    val zone = ZoneId.systemDefault()
    val instant = Instant.ofEpochMilli(millis)
    val skeleton = if (instant.atZone(zone).year == java.time.Year.now(zone).value) "dMMM" else "dMMMy"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).withZone(zone).format(instant)
}

/** Window of the moving average drawn over the per-game scores. */
private const val AVERAGE_WINDOW = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(api: ApiClient, onBack: () -> Unit) {
    // The games in the phone's language first (English for the languages the game does not have).
    var language by remember { mutableStateOf(GameLanguage.entries.firstOrNull { it.code == Locale.getDefault().language } ?: GameLanguage.EN) }
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
                title = { Text(stringResource(R.string.stats_title)) },
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
                s == null -> Text(stringResource(R.string.stats_loading), Modifier.padding(16.dp))
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
                StatTile(
                    stringResource(R.string.stats_wins), "${stats.wins}",
                    pluralStringResource(R.plurals.stats_wins_caption, stats.finishedGames, stats.finishedGames),
                )
                StatTile(stringResource(R.string.stats_average_score), stats.averageScore?.let { "%.0f".format(it) } ?: "–", stringResource(R.string.stats_per_game))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(stringResource(R.string.stats_points), stats.pointsPerTurn?.let { "%.1f".format(it) } ?: "–", stringResource(R.string.stats_per_turn))
                StatTile(stringResource(R.string.stats_bingos), "${stats.bingos}", stringResource(R.string.stats_bingos_caption))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(stringResource(R.string.stats_share), stats.share?.let { percent(it) } ?: "–", stringResource(R.string.stats_share_caption))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val helped = stats.history.count { it.hints > 0 }
                StatTile(stringResource(R.string.stats_hints), "${stats.hintTurns}", pluralStringResource(R.plurals.stats_hint_turns, stats.hintTurns))
                val unhelped = stats.finishedGames - helped
                StatTile(
                    stringResource(R.string.stats_no_hints),
                    "${stats.history.count { it.result == GameResult.WIN && it.hints == 0 }}",
                    pluralStringResource(R.plurals.stats_no_hints_caption, unhelped, unhelped),
                )
            }
        }

        item { SectionTitle(stringResource(R.string.stats_progress)) }
        item {
            var byShare by remember { mutableStateOf(false) }
            val games = if (byShare) stats.history.filter { it.share != null } else stats.history
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !byShare, onClick = { byShare = false }, label = { Text(stringResource(R.string.stats_chart_score)) })
                    FilterChip(selected = byShare, onClick = { byShare = true }, label = { Text(stringResource(R.string.stats_chart_share)) })
                }
                if (games.size < 2) {
                    Text(
                        stringResource(R.string.stats_chart_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ScoreChart(games, byShare)
                }
            }
        }

        item { SectionTitle(stringResource(R.string.stats_records)) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordLine(
                        stringResource(R.string.stats_best_move),
                        stats.bestMove?.let { stringResource(R.string.stats_best_move_value, it.words.joinToString(), it.score, it.opponent) },
                    )
                    RecordLine(
                        stringResource(R.string.stats_best_game),
                        stats.bestGame?.let { stringResource(R.string.stats_best_game_value, it.myScore, it.opponent, date(it.finishedAt)) },
                    )
                    RecordLine(
                        stringResource(R.string.stats_longest_word),
                        stats.longestWord?.let { stringResource(R.string.stats_longest_word_value, it, it.length) },
                    )
                    RecordLine(
                        stringResource(R.string.stats_average_margin),
                        stats.averageMargin?.let { stringResource(R.string.stats_average_margin_value, "%+.0f".format(it)) },
                    )
                }
            }
        }

        if (stats.opponents.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.stats_by_opponent)) }
            items(stats.opponents) { o ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(o.nickname, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    val win = resultLabel(GameResult.WIN)
                    val loss = resultLabel(GameResult.LOSS)
                    val draw = resultLabel(GameResult.DRAW)
                    Text(
                        buildString {
                            append("${o.wins} $win · ${o.losses} $loss")
                            if (o.draws > 0) append(" · ${o.draws} $draw")
                        },
                    )
                }
            }
        }

        if (stats.history.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.stats_finished_games)) }
            // The table behind the chart: every point, readable without the plot.
            items(stats.history.reversed(), key = { it.gameId }) { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(date(g.finishedAt), Modifier.weight(0.25f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (g.hints > 0) "${g.opponent} 💡${g.hints}" else g.opponent, Modifier.weight(0.35f))
                    Text("${g.myScore} – ${g.opponentScore}", Modifier.weight(0.25f))
                    Text(resultLabel(g.result), Modifier.weight(0.15f), fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Text("") }
    }
}

/** One letter: W, L, D (V, D, N in French). */
@Composable
private fun resultLabel(r: GameResult) = stringResource(
    when (r) {
        GameResult.WIN -> R.string.stats_win_short
        GameResult.LOSS -> R.string.stats_loss_short
        GameResult.DRAW -> R.string.stats_draw_short
    },
)

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

/** "45 %" in French, "45%" in English. */
private fun percent(share: Double): String = NumberFormat.getPercentInstance().format(share)

/**
 * Score of each finished game, or with [byShare] its share of the possible points (in %), as dots (filled = win,
 * hollow = loss or draw) and their moving average (line). One series hue; win/loss is carried by the dot fill,
 * not by color. Tap a dot for the details.
 */
@Composable
private fun ScoreChart(history: List<FinishedGame>, byShare: Boolean) {
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    var selected by remember(history) { mutableStateOf(history.lastIndex) }

    val scores = history.map { if (byShare) it.share!! * 100 else it.myScore.toDouble() }
    val averages = scores.indices.map { i -> scores.subList(maxOf(0, i - AVERAGE_WINDOW + 1), i + 1).average() }
    val yMax = if (byShare) 100f else (ceil(maxOf(scores.max(), 1.0) / 100.0) * 100).toFloat()
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
                val c = Offset(x(i), y(scores[i]))
                drawCircle(surface, r + 2.dp.toPx(), c) // surface ring keeps dots readable over the line
                if (g.result == GameResult.WIN) drawCircle(dotColor, r, c)
                else drawCircle(dotColor, r - 0.75.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                if (i == selected) drawCircle(lineColor, r + 4.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            }
        }

        // Legend (two series: never color alone).
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.stats_legend_dots), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.stats_legend_average, AVERAGE_WINDOW), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        history.getOrNull(selected)?.let { g ->
            val average = if (byShare) percent(averages[selected] / 100) else "%.0f".format(averages[selected])
            val result = stringResource(
                when (g.result) {
                    GameResult.WIN -> R.string.stats_win
                    GameResult.LOSS -> R.string.stats_loss
                    GameResult.DRAW -> R.string.stats_draw
                },
            )
            Text(
                stringResource(R.string.stats_selected, date(g.finishedAt), g.opponent, g.myScore, g.opponentScore, result) +
                    (if (byShare) stringResource(R.string.stats_selected_share, percent(g.share!!)) else "") +
                    stringResource(R.string.stats_selected_average, average),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
