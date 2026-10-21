package app.wordgame.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wordgame.android.ApiClient
import app.wordgame.android.ApiFailure
import app.wordgame.android.DictionaryLoader
import app.wordgame.engine.Action
import app.wordgame.engine.BLANK
import app.wordgame.engine.Board
import app.wordgame.engine.Evaluation
import app.wordgame.engine.GameStatus
import app.wordgame.engine.MoveEvaluator
import app.wordgame.engine.Placement
import app.wordgame.engine.RuleSet
import app.wordgame.engine.WordList
import app.wordgame.protocol.GameView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A rack tile put on the board but not sent yet. */
data class Tentative(val rackIndex: Int, val placement: Placement)

data class Preview(val text: String, val valid: Boolean)

data class GameUiState(
    val game: GameView? = null,
    /** Local rack order (the player can shuffle it). */
    val rack: List<Char> = emptyList(),
    val tentative: List<Tentative> = emptyList(),
    val selected: Int? = null,
    /** Square waiting for the player to choose the letter of a blank. */
    val pendingBlank: Pair<Int, Int>? = null,
    val preview: Preview? = null,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val myTurn get() = game != null && game.status == GameStatus.ACTIVE && game.currentSeat == game.mySeat

    /** A game can be deleted until every player has made at least one move (same rule as the server). */
    val canDelete get() = game != null && game.moves.map { it.seat }.toSet().size < game.players.size
    fun tentativeAt(row: Int, col: Int) = tentative.firstOrNull { it.placement.row == row && it.placement.col == col }
    fun isUsed(rackIndex: Int) = tentative.any { it.rackIndex == rackIndex }
}

class GameViewModel(
    private val gameId: String,
    private val api: ApiClient,
    private val dictionary: DictionaryLoader,
) : ViewModel() {
    val state = MutableStateFlow(GameUiState())
    private var words: WordList? = null

    init {
        viewModelScope.launch { words = dictionary.get() }
    }

    suspend fun refresh() {
        runCatching { api.game(gameId) }
            .onSuccess(::show)
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }

    /** Keeps the local rack order and tentative tiles unless the game moved on. */
    private fun show(game: GameView) = state.update { s ->
        val changed = s.game == null || s.game.moveNumber != game.moveNumber
        if (!changed) s.copy(game = game)
        else GameUiState(game = game, rack = game.rack.toList(), message = s.message)
    }

    fun selectRackTile(index: Int) = state.update { s ->
        if (s.isUsed(index)) s else s.copy(selected = if (s.selected == index) null else index)
    }

    fun tapSquare(row: Int, col: Int) {
        val s = state.value
        val existing = s.tentativeAt(row, col)
        if (existing != null) {
            updateTentative(s.tentative - existing)
            return
        }
        placeFromRack(s.selected ?: return, row, col)
    }

    /** Puts rack tile [index] on an empty square; a blank first asks for its letter. */
    fun placeFromRack(index: Int, row: Int, col: Int) {
        val s = state.value
        if (!isFree(s, row, col) || s.isUsed(index)) return
        if (s.rack[index] == BLANK) {
            state.update { it.copy(selected = index, pendingBlank = row to col) }
        } else {
            updateTentative(s.tentative + Tentative(index, Placement(row, col, s.rack[index])))
        }
    }

    /** Moves a tile put this turn to another empty square. */
    fun moveTentative(fromRow: Int, fromCol: Int, row: Int, col: Int) {
        val s = state.value
        val moving = s.tentativeAt(fromRow, fromCol) ?: return
        if (!isFree(s, row, col)) return
        val moved = moving.copy(placement = moving.placement.copy(row = row, col = col))
        updateTentative(s.tentative.map { if (it == moving) moved else it })
    }

    /** Sends a tile put this turn back to the rack. */
    fun removeTentative(row: Int, col: Int) {
        val s = state.value
        val tile = s.tentativeAt(row, col) ?: return
        updateTentative(s.tentative - tile)
    }

    /** Moves rack tile [from] to slot [to], shifting the tiles in between. */
    fun moveRackTile(from: Int, to: Int) = state.update { s ->
        if (from == to || from !in s.rack.indices || to !in s.rack.indices) return@update s
        val order = s.rack.indices.toMutableList().apply { add(to, removeAt(from)) }
        val newIndex = order.withIndex().associate { (newPos, oldPos) -> oldPos to newPos }
        s.copy(
            rack = order.map { s.rack[it] },
            tentative = s.tentative.map { it.copy(rackIndex = newIndex.getValue(it.rackIndex)) },
            selected = s.selected?.let(newIndex::getValue),
        )
    }

    private fun isFree(s: GameUiState, row: Int, col: Int): Boolean {
        val game = s.game ?: return false
        return Board.fromRows(game.board)[row, col] == Board.EMPTY && s.tentativeAt(row, col) == null
    }

    fun chooseBlankLetter(letter: Char?) {
        val s = state.value
        val square = s.pendingBlank
        val index = s.selected
        state.update { it.copy(pendingBlank = null) }
        if (letter == null || square == null || index == null) return
        updateTentative(s.tentative + Tentative(index, Placement(square.first, square.second, letter, blank = true)))
    }

    fun recall() = updateTentative(emptyList())

    fun shuffle() = state.update { it.copy(rack = it.rack.shuffled(), tentative = emptyList(), selected = null, preview = null) }

    fun play() = send(Action.Play(state.value.tentative.map { it.placement }))

    fun pass() = send(Action.Pass)

    fun resign() = send(Action.Resign)

    fun exchange(rackIndexes: Set<Int>) {
        val rack = state.value.rack
        send(Action.Exchange(rackIndexes.map { rack[it] }.joinToString("")))
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { api.deleteGame(gameId) }
                .onSuccess { onDeleted() }
                .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
        }
    }

    fun dismissMessage() = state.update { it.copy(message = null) }

    private fun send(action: Action) {
        val game = state.value.game ?: return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { api.move(game, action) }
                .onSuccess { show(it); state.update { s -> s.copy(message = null) } }
                .onFailure { e ->
                    state.update { it.copy(message = e.userMessage()) }
                    if (e is ApiFailure && e.error.code == "STALE_GAME") refresh()
                }
            state.update { it.copy(busy = false) }
        }
    }

    private fun updateTentative(tentative: List<Tentative>) = state.update { s ->
        val usedSelected = s.selected != null && tentative.any { it.rackIndex == s.selected }
        s.copy(tentative = tentative, selected = if (usedSelected) null else s.selected, preview = preview(s.game, tentative))
    }

    private fun preview(game: GameView?, tentative: List<Tentative>): Preview? {
        if (game == null || tentative.isEmpty()) return null
        val rules = RuleSet.byId(game.rulesId)
        return when (val e = MoveEvaluator.evaluate(Board.fromRows(game.board), tentative.map { it.placement }, rules, words)) {
            is Evaluation.Valid -> Preview("${e.words.joinToString { it.word }} : ${e.score} pts", valid = true)
            is Evaluation.Invalid -> Preview(
                if (e.unknownWords.isNotEmpty()) "Inconnu : ${e.unknownWords.joinToString()}" else e.reason.userMessage(),
                valid = false,
            )
        }
    }
}
