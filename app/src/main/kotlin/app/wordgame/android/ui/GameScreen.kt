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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
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
import app.wordgame.android.push.Push
import app.wordgame.engine.BLANK
import app.wordgame.engine.Board
import app.wordgame.engine.Lexicon
import app.wordgame.engine.GameEnd
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
    val vm = viewModel(key = gameId) { GameViewModel(container.backend(gameId), container.dictionary) }
    val state by vm.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmResign by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showUnseen by remember { mutableStateOf(false) }
    var showAnalysis by remember { mutableStateOf(false) }
    var lexicon by remember { mutableStateOf<Lexicon?>(null) }
    LaunchedEffect(Unit) { lexicon = vm.lexicon() }
    var exchanging by remember { mutableStateOf(false) }

    LaunchedEffect(gameId) { Push.gameUpdates.collect { if (it == gameId) vm.refresh() } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Always reload when the game is (re)opened: the opponent may have resigned during our turn.
            vm.refresh()
            while (true) {
                delay(10.seconds)
                // Then only poll while waiting for the opponent.
                if (!vm.state.value.myTurn) vm.refresh()
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
        // Slot a rack tile would land in while it slides along the rack (the others make room live).
        val rackTarget = if (drag.source is DragSource.Rack) drag.rackSlot(drag.position)?.let { minOf(it, state.rack.lastIndex) } else null
        val onDrop: (DragSource, Offset) -> Unit = { source, finger ->
            val p = finger - Offset(0f, liftPx)
            val cell = drag.boardCell(p, rules.boardSize)
            when (source) {
                is DragSource.Rack -> {
                    val slot = drag.rackSlot(finger)
                    when {
                        // An empty slot at the end of a short rack means "move to the end".
                        slot != null -> vm.moveRackTile(source.index, minOf(slot, state.rack.lastIndex))
                        cell != null -> vm.placeFromRack(source.index, cell.first, cell.second)
                    }
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(statusLine(game, state), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    BagButton(game.bagCount, onClick = { showUnseen = true })
                    // Game actions next to the bag, where the player's eyes already are.
                    val actionsEnabled = state.myTurn || state.canDelete
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        enabled = actionsEnabled,
                        shape = CircleShape,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.padding(start = 8.dp).size(44.dp),
                    ) { Text("⋯", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                }
                // Fixed height so the rack does not jump under the player's finger when a preview appears.
                Box(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp).clickable {
                        if (state.message != null) vm.dismissMessage()
                    },
                ) {
                    val preview = state.preview
                    val message = state.message
                    val analysis = state.lastMoveAnalysis
                    when {
                        message != null -> Text(message, color = MaterialTheme.colorScheme.error, maxLines = 2)
                        preview == null && analysis != null -> LastMoveAnalysisText(analysis)
                        preview != null -> Text(
                            preview.text,
                            color = if (preview.valid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                        )
                    }
                }
                RackView(state, rules, drag, rackTarget, onTap = vm::selectRackTile, onDrop = onDrop)
                if (game.status == GameStatus.FINISHED) {
                    Button(onClick = { showAnalysis = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        Text("Analyse de la partie")
                    }
                } else Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                val density = LocalDensity.current
                val rackRow = drag.rackSlots[0]
                // Sliding along the rack: the tile stays in the row, under the finger, at rack size.
                // Elsewhere: board-sized tile lifted above the finger so it stays visible.
                val inRack = rackTarget != null && rackRow != null
                val sizePx = if (inRack) rackRow!!.width else with(density) { DRAGGED_TILE.toPx() }
                val center = if (inRack) Offset(drag.position.x, rackRow!!.center.y) else drag.position - Offset(0f, liftPx)
                val topLeft = center - overlayOrigin - Offset(sizePx / 2, sizePx / 2)
                Box(
                    Modifier.offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                        .size(with(density) { sizePx.toDp() })
                        .shadow(8.dp, RoundedCornerShape(6.dp))
                        .background(BoardColors.tentativeTile, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    TileGlyph(if (drag.letter == BLANK) "" else drag.letter.toString(), null, BoardColors.tileText, letterSize = 0.5f)
                }
            }
        }
    }

    if (state.pendingBlank != null) BlankLetterDialog(onChoose = vm::chooseBlankLetter)
    if (exchanging) ExchangeDialog(state, onDismiss = { exchanging = false }, onConfirm = { exchanging = false; vm.exchange(it) })
    if (menuOpen) {
        GameActionsDialog(
            state,
            onDismiss = { menuOpen = false },
            onExchange = { menuOpen = false; exchanging = true },
            onPass = { menuOpen = false; vm.pass() },
            onResign = { menuOpen = false; confirmResign = true },
            onDelete = { menuOpen = false; confirmDelete = true },
        )
    }
    if (showAnalysis) {
        val g = state.game
        val lex = lexicon
        if (g != null && lex != null) GameAnalysisDialog(g, lex, onDismiss = { showAnalysis = false })
    }
    if (showUnseen) state.game?.let { UnseenTilesDialog(it, onDismiss = { showUnseen = false }) }
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
    val others = game.players.filterIndexed { i, _ -> i != game.mySeat }
    others.singleOrNull()?.let { return "${me.score} – ${it.score} ${it.nickname}" }
    // 3 or 4 players: my score, then each opponent's (eliminated ones crossed out).
    return "${me.score} · " + others.joinToString(" · ") { "${it.nickname.take(8)}${if (it.eliminated) "✕" else ""} ${it.score}" }
}

private fun statusLine(game: GameView, state: GameUiState): String {
    val opponent = game.players[game.currentSeat].nickname
    val last = game.moves.lastOrNull()?.let { m ->
        val mine = m.seat == game.mySeat
        val who = if (mine) "Toi" else game.players[m.seat].nickname
        // "Tu as passé" / "NonoBot a passé"
        val subject = if (mine) "Tu as" else "$who a"
        val text = when (m.type) {
            MoveType.PLAY -> "$who : ${m.words.joinToString { it.word }} +${m.score}" +
                (if (m.end == GameEnd.OUT) " (+${m.endAdjustments[m.seat]} de bonus de fin)" else "")
            MoveType.EXCHANGE -> "$subject échangé ${m.exchangedCount} lettre(s)"
            MoveType.PASS -> if (m.end == GameEnd.BLOCKED) "Partie bloquée" else "$subject passé"
            MoveType.RESIGN -> "$subject abandonné"
            MoveType.TIMEOUT -> "$subject dépassé le temps"
        }
        // Rack values deducted at the end (blocked game, or the loser's rack when the other went out).
        val deductions = m.endAdjustments.withIndex().filter { it.value < 0 }.joinToString { (seat, delta) ->
            "${if (seat == game.mySeat) "toi" else game.players[seat].nickname} $delta"
        }
        if (deductions.isEmpty()) text else "$text · lettres restantes : $deductions"
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
    return listOfNotNull(turn, last).joinToString(" · ")
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
internal fun RowScope.Square(
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
            TileGlyph(letter.toString(), points, BoardColors.tileText)
        } else if (label.isNotEmpty()) {
            TileGlyph(label, null, Color(0xFF2B3A46), letterSize = if (label.length > 1) 0.3f else 0.45f)
        }
    }
}

@Composable
private fun RackView(
    state: GameUiState,
    rules: RuleSet,
    drag: DragState,
    /** Slot the dragged rack tile is hovering, or null. */
    target: Int?,
    onTap: (Int) -> Unit,
    onDrop: (DragSource, Offset) -> Unit,
) {
    // While a rack tile slides along the rack, the others shift to open a gap at its target.
    // Each tile stays in its own slot in the layout and only moves visually: reordering the
    // composition would cancel the drag gesture of the tile being dragged.
    val dragged = (drag.source as? DragSource.Rack)?.index
    val order = state.rack.indices.toMutableList()
    if (dragged != null && target != null) order.add(target, order.removeAt(dragged))
    val pitch = drag.rackSlots[1]?.left?.minus(drag.rackSlots[0]?.left ?: 0f) ?: 0f

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
            // Animated while dragging; reset without animation when the rack itself changes (on drop),
            // since the tiles are then already where the drag preview had moved them.
            val shift = remember(state.rack) { Animatable(0f) }
            val shiftTarget = (order.indexOf(i) - i) * pitch
            LaunchedEffect(shift, shiftTarget) { shift.animateTo(shiftTarget) }
            val hidden = state.isUsed(i) || i == dragged
            Box(
                Modifier.weight(1f).aspectRatio(1f)
                    // Slot bounds are measured before the visual shift.
                    .dropTargetBounds { drag.rackSlots[i] = it }
                    .graphicsLayer { translationX = shift.value }
                    .dragSource(drag, DragSource.Rack(i), tile, enabled = !state.isUsed(i), onDrop = onDrop)
                    .background(if (hidden) BoardColors.square else BoardColors.tile, RoundedCornerShape(6.dp))
                    .then(if (state.selected == i) Modifier.border(3.dp, BoardColors.tentativeTile, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable(enabled = !hidden) { onTap(i) },
                contentAlignment = Alignment.Center,
            ) {
                if (!hidden) {
                    TileGlyph(if (tile == BLANK) "" else tile.toString(), if (tile == BLANK) null else rules.points(tile), BoardColors.tileText, letterSize = 0.5f, pointsSize = 0.2f)
                }
            }
        }
    }
}

/** Game actions in a centered popup, one full-width button per action. */
@Composable
private fun GameActionsDialog(
    state: GameUiState,
    onDismiss: () -> Unit,
    onExchange: () -> Unit,
    onPass: () -> Unit,
    onResign: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Actions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.myTurn) {
                    OutlinedButton(onClick = onExchange, modifier = Modifier.fillMaxWidth()) { Text("Échanger des lettres") }
                    OutlinedButton(onClick = onPass, modifier = Modifier.fillMaxWidth()) { Text("Passer mon tour") }
                    OutlinedButton(onClick = onResign, modifier = Modifier.fillMaxWidth()) { Text("Abandonner") }
                }
                if (state.canDelete) {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("Supprimer la partie", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
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
                    ) { TileGlyph(if (tile == BLANK) "?" else tile.toString(), null, BoardColors.tileText, letterSize = 0.5f) }
                }
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onConfirm(chosen) }) { Text("Échanger") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
