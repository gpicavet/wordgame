package app.wordgame.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wordgame.engine.BLANK
import app.wordgame.engine.Board
import app.wordgame.engine.RuleSet
import app.wordgame.protocol.GameView

/**
 * Tiles the player has not seen: full bag minus the board minus their own rack.
 * That is the bag plus the opponent's rack — the server never says which is which.
 */
fun unseenTiles(game: GameView): Map<Char, Int> {
    val rules = RuleSet.byId(game.rulesId)
    val counts = rules.fullBag().groupingBy { it }.eachCount().toMutableMap()
    fun take(tile: Char) = counts.computeIfPresent(tile) { _, n -> n - 1 }
    game.board.joinToString("").filter { it != Board.EMPTY }
        .forEach { take(if (it.isLowerCase()) BLANK else it) }
    game.rack.forEach(::take)
    return counts
}

/** Outlined bag with the number of tiles left in it. */
@Composable
fun BagButton(bagCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier.size(44.dp).clickable(onClick = onClick).semantics { contentDescription = "$bagCount lettres dans le sac" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            // Drawn on a 24×24 grid, scaled to the box.
            val u = size.minDimension / 24f
            val sack = Path().apply {
                // Body: from the knot, bulging down to a flat-ish bottom and back up.
                moveTo(9f * u, 7.5f * u)
                cubicTo(2.5f * u, 11f * u, 2.5f * u, 21.5f * u, 12f * u, 21.5f * u)
                cubicTo(21.5f * u, 21.5f * u, 21.5f * u, 11f * u, 15f * u, 7.5f * u)
                // Gathered top above the knot.
                moveTo(9.5f * u, 6f * u)
                lineTo(7.5f * u, 2.5f * u)
                quadraticTo(12f * u, 4.5f * u, 16.5f * u, 2.5f * u)
                lineTo(14.5f * u, 6f * u)
                // Knot.
                moveTo(8.5f * u, 7f * u)
                lineTo(15.5f * u, 7f * u)
            }
            drawPath(sack, color, style = Stroke(width = 1.6f * u, join = StrokeJoin.Round))
        }
        Text(
            bagCount.toString(),
            Modifier.padding(top = 10.dp),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
fun UnseenTilesDialog(game: GameView, onDismiss: () -> Unit) {
    val rules = RuleSet.byId(game.rulesId)
    val unseen = unseenTiles(game)
    val opponentRack = game.players.filterIndexed { i, _ -> i != game.mySeat }.sumOf { it.rackCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lettres restantes : ${unseen.values.sum()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${game.bagCount} dans le sac et $opponentRack sur le chevalet adverse.",
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyVerticalGrid(
                    GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items((rules.letters.keys.sorted() + BLANK).toList()) { tile ->
                        val count = unseen[tile] ?: 0
                        Box(
                            Modifier.aspectRatio(1f).alpha(if (count == 0) 0.3f else 1f)
                                .background(BoardColors.tile, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            TileGlyph(if (tile == BLANK) "?" else tile.toString(), null, BoardColors.tileText, letterSize = 0.5f)
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(1.dp).size(15.dp)
                                    .background(MaterialTheme.colorScheme.secondary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(count.toString(), color = MaterialTheme.colorScheme.onSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
