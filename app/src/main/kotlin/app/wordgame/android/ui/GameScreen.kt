package app.wordgame.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.wordgame.android.AppContainer
import app.wordgame.engine.BLANK
import app.wordgame.engine.Board
import app.wordgame.engine.GameStatus
import app.wordgame.engine.MoveType
import app.wordgame.engine.Premium
import app.wordgame.engine.RuleSet
import app.wordgame.protocol.GameView
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/** Size of the tile under the finger, and how far above the finger it is drawn so it stays visible. */
private val DRAGGED_TILE = 44.dp
private val DRAG_LIFT = 48.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(container: AppContainer, gameId: String, onBack: () -> Unit) {
    val vm = viewModel(key = gameId) { GameViewModel(gameId, container.api, container.dictionary) }
    val state by vm.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmResign by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exchanging by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                // Only poll while waiting for the opponent.
                if (!vm.state.value.myTurn) vm.refresh()
                delay(10.seconds)
            }
        }
    }

    val game = state.game
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(game?.let { scoreLine(it) } ?: "Chargement…") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }, enabled = state.myTurn || state.canDelete) { Text("⋮", fontSize = 22.sp) }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (state.myTurn) {
                            DropdownMenuItem(text = { Text("Échanger des lettres") }, onClick = { menuOpen = false; exchanging = true })
                            DropdownMenuItem(text = { Text("Passer") }, onClick = { menuOpen = false; vm.pass() })
                            DropdownMenuItem(text = { Text("Abandonner") }, onClick = { menuOpen = false; confirmResign = true })
                        }
                        if (state.canDelete) {
                            DropdownMenuItem(text = { Text("Supprimer la partie") }, onClick = { menuOpen = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (game == null) {
            state.message?.let { Text(it, Modifier.padding(padding).padding(16.dp), color = MaterialTheme.colorScheme.error) }
            return@Scaffold
        }
        val rules = RuleSet.byId(game.rulesId)
        val drag = remember { DragState() }
        val liftPx = with(LocalDensity.current) { DRAG_LIFT.toPx() }
        // The dragged tile is drawn lifted above the finger; the drop point is the tile's center.
        val onDrop: (DragSource, Offset) -> Unit = { source, finger ->
            val p = finger - Offset(0f, liftPx)
            val cell = drag.boardCell(p, rules.boardSize)
            // On the rack the lifted tile sits above the slots: the finger itself is the better guess.
            val slot = drag.rackSlot(p) ?: drag.rackSlot(finger)
            when (source) {
                is DragSource.Rack -> when {
                    cell != null -> vm.placeFromRack(source.index, cell.first, cell.second)
                    // An empty slot at the end of a short rack means "move to the end".
                    slot != null -> vm.moveRackTile(source.index, minOf(slot, state.rack.lastIndex))
                }
                is DragSource.Board -> when {
                    cell != null -> vm.moveTentative(source.row, source.col, cell.first, cell.second)
                    else -> vm.removeTentative(source.row, source.col)
                }
            }
        }
        var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.padding(padding).fillMaxSize().onGloballyPositioned { overlayOrigin = it.positionInRoot() }) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BoardView(game, state, rules, drag, onTap = vm::tapSquare, onDrop = onDrop)
                Text(statusLine(game, state), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium)
                // Fixed height so the rack does not jump under the player's finger when a preview appears.
                Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp).clickable { vm.dismissMessage() }) {
                    val preview = state.preview
                    val message = state.message
                    when {
                        message != null -> Text(message, color = MaterialTheme.colorScheme.error, maxLines = 2)
                        preview != null -> Text(
                            preview.text,
                            color = if (preview.valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                        )
                    }
                }
                RackView(state, rules, drag, onTap = vm::selectRackTile, onDrop = onDrop)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::play,
                        enabled = state.myTurn && !state.busy && state.preview?.valid == true,
                        modifier = Modifier.weight(1f),
                    ) { Text("Jouer") }
                    if (state.tentative.isNotEmpty()) {
                        OutlinedButton(onClick = vm::recall) { Text("Reprendre") }
                    } else {
                        OutlinedButton(onClick = vm::shuffle) { Text("Mélanger") }
                    }
                }
            }
            if (drag.source != null) {
                val sizePx = with(LocalDensity.current) { DRAGGED_TILE.toPx() }
                val topLeft = drag.position - overlayOrigin - Offset(sizePx / 2, sizePx / 2 + liftPx)
                Box(
                    Modifier.offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                        .size(DRAGGED_TILE)
                        .shadow(8.dp, RoundedCornerShape(6.dp))
                        .background(BoardColors.tentativeTile, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (drag.letter == BLANK) "" else drag.letter.toString(), color = BoardColors.tileText, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
            }
        }
    }

    if (state.pendingBlank != null) BlankLetterDialog(onChoose = vm::chooseBlankLetter)
    if (exchanging) ExchangeDialog(state, onDismiss = { exchanging = false }, onConfirm = { exchanging = false; vm.exchange(it) })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer la partie ?") },
            text = { Text("Elle disparaîtra aussi chez ton adversaire.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onDeleted = onBack) }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            title = { Text("Abandonner la partie ?") },
            confirmButton = { TextButton(onClick = { confirmResign = false; vm.resign() }) { Text("Abandonner") } },
            dismissButton = { TextButton(onClick = { confirmResign = false }) { Text("Continuer") } },
        )
    }
}

private fun scoreLine(game: GameView): String {
    val me = game.players[game.mySeat]
    val other = game.players[1 - game.mySeat]
    return "${me.score} – ${other.score} ${other.nickname}"
}

private fun statusLine(game: GameView, state: GameUiState): String {
    val opponent = game.players[1 - game.mySeat].nickname
    val last = game.moves.lastOrNull()?.let { m ->
        val who = if (m.seat == game.mySeat) "Toi" else game.players[m.seat].nickname
        when (m.type) {
            MoveType.PLAY -> "$who : ${m.words.joinToString { it.word }} +${m.score}"
            MoveType.EXCHANGE -> "$who a échangé ${m.exchangedCount} lettre(s)"
            MoveType.PASS -> "$who a passé"
            MoveType.RESIGN -> "$who a abandonné"
            MoveType.TIMEOUT -> "$who a dépassé le temps"
        }
    }
    val turn = when {
        game.status == GameStatus.FINISHED -> when (game.winnerSeat) {
            game.mySeat -> "Partie gagnée !"
            null -> "Égalité"
            else -> "Partie perdue"
        }
        state.myTurn -> "À toi de jouer"
        else -> "Au tour de $opponent"
    }
    return listOfNotNull(turn, last, "${game.bagCount} lettres dans le sac").joinToString(" · ")
}

@Composable
private fun BoardView(
    game: GameView,
    state: GameUiState,
    rules: RuleSet,
    drag: DragState,
    onTap: (Int, Int) -> Unit,
    onDrop: (DragSource, Offset) -> Unit,
) {
    val board = Board.fromRows(game.board)
    val lastMove = game.moves.lastOrNull()?.tiles.orEmpty().map { it.row to it.col }.toSet()
    Column(Modifier.fillMaxWidth().aspectRatio(1f).dropTargetBounds { drag.boardBounds = it }) {
        for (r in 0 until board.size) {
            Row(Modifier.weight(1f)) {
                for (c in 0 until board.size) {
                    val placed = state.tentativeAt(r, c)
                    // Hidden while dragged, but still draggable: changing `enabled` mid-gesture would cancel it.
                    val tentative = placed?.takeUnless { drag.isDragging(DragSource.Board(r, c)) }
                    val cell = board[r, c]
                    val premium = rules.layout.premiumAt(r, c)
                    val isCenter = r == rules.layout.center && c == rules.layout.center
                    Square(
                        letter = tentative?.placement?.letter ?: cell.takeIf { it != Board.EMPTY }?.uppercaseChar(),
                        points = when {
                            tentative != null -> if (tentative.placement.blank) 0 else rules.points(tentative.placement.letter)
                            cell == Board.EMPTY || cell.isLowerCase() -> 0
                            else -> rules.points(cell)
                        },
                        background = when {
                            tentative != null -> BoardColors.tentativeTile
                            cell != Board.EMPTY -> if ((r to c) in lastMove) BoardColors.lastMoveTile else BoardColors.tile
                            isCenter && premium == Premium.NONE -> BoardColors.center
                            else -> BoardColors.premium(premium)
                        },
                        label = if (isCenter) "★" else BoardColors.label(premium),
                        onClick = { onTap(r, c) },
                        dragModifier = Modifier.dragSource(
                            drag, DragSource.Board(r, c), placed?.placement?.letter ?: ' ', enabled = placed != null, onDrop = onDrop,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.Square(
    letter: Char?,
    points: Int,
    background: Color,
    label: String,
    onClick: () -> Unit,
    dragModifier: Modifier,
) {
    Box(
        Modifier.weight(1f).fillMaxSize().padding(0.5.dp).background(background, RoundedCornerShape(2.dp))
            .then(dragModifier).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (letter != null) {
            Text(letter.toString(), color = BoardColors.tileText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (points > 0) {
                Text(
                    points.toString(),
                    Modifier.align(Alignment.BottomEnd).padding(end = 1.dp),
                    color = BoardColors.tileText,
                    fontSize = 7.sp,
                )
            }
        } else if (label.isNotEmpty()) {
            Text(label, fontSize = 8.sp, color = Color(0xFF2B3A46))
        }
    }
}

@Composable
private fun RackView(
    state: GameUiState,
    rules: RuleSet,
    drag: DragState,
    onTap: (Int) -> Unit,
    onDrop: (DragSource, Offset) -> Unit,
) {
    // Always rackSize slots: tiles keep their size when the bag is empty and the rack shrinks.
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until rules.rackSize) {
            val tile = state.rack.getOrNull(i)
            if (tile == null) {
                Box(
                    Modifier.weight(1f).aspectRatio(1f)
                        .dropTargetBounds { drag.rackSlots[i] = it }
                        .background(BoardColors.square, RoundedCornerShape(6.dp)),
                )
                continue
            }
            val used = state.isUsed(i) || drag.isDragging(DragSource.Rack(i))
            Box(
                Modifier.weight(1f).aspectRatio(1f)
                    .dropTargetBounds { drag.rackSlots[i] = it }
                    .dragSource(drag, DragSource.Rack(i), tile, enabled = !state.isUsed(i), onDrop = onDrop)
                    .background(if (used) BoardColors.square else BoardColors.tile, RoundedCornerShape(6.dp))
                    .then(if (state.selected == i) Modifier.border(3.dp, BoardColors.tentativeTile, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable(enabled = !used) { onTap(i) },
                contentAlignment = Alignment.Center,
            ) {
                if (!used) {
                    Text(if (tile == BLANK) "" else tile.toString(), color = BoardColors.tileText, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    if (tile != BLANK) {
                        Text(rules.points(tile).toString(), Modifier.align(Alignment.BottomEnd).padding(4.dp), color = BoardColors.tileText, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlankLetterDialog(onChoose: (Char?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text("Lettre du joker") },
        text = {
            LazyVerticalGrid(GridCells.Fixed(6)) {
                items(('A'..'Z').toList()) { letter ->
                    TextButton(onClick = { onChoose(letter) }) { Text(letter.toString(), fontSize = 18.sp) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onChoose(null) }) { Text("Annuler") } },
    )
}

@Composable
private fun ExchangeDialog(state: GameUiState, onDismiss: () -> Unit, onConfirm: (Set<Int>) -> Unit) {
    var chosen by remember { mutableStateOf(emptySet<Int>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Échanger des lettres") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                state.rack.forEachIndexed { i, tile ->
                    Box(
                        Modifier.size(36.dp)
                            .background(if (i in chosen) BoardColors.tentativeTile else BoardColors.tile, RoundedCornerShape(4.dp))
                            .clickable { chosen = if (i in chosen) chosen - i else chosen + i },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (tile == BLANK) "?" else tile.toString(), color = BoardColors.tileText, fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onConfirm(chosen) }) { Text("Échanger") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
