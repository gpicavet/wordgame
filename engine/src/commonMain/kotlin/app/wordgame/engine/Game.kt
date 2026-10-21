package app.wordgame.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
sealed interface Action {
    @Serializable @SerialName("play")
    data class Play(val tiles: List<Placement>) : Action

    /** [tiles] uses [BLANK] for blanks. */
    @Serializable @SerialName("exchange")
    data class Exchange(val tiles: String) : Action

    @Serializable @SerialName("pass")
    data object Pass : Action

    @Serializable @SerialName("resign")
    data object Resign : Action
}

@Serializable
enum class GameStatus { ACTIVE, FINISHED }

@Serializable
data class PlayerState(val score: Int, val rack: String)

/** Full, authoritative state. Contains the bag and every rack: never sent as is to a client. */
@Serializable
data class GameState(
    val rulesId: String,
    val board: List<String>,
    val bag: String,
    val players: List<PlayerState>,
    val currentPlayer: Int,
    val moveNumber: Int = 0,
    val scorelessTurns: Int = 0,
    val status: GameStatus = GameStatus.ACTIVE,
    /** Seat of the winner, null while active or on a draw. */
    val winner: Int? = null,
) {
    val rules get() = RuleSet.byId(rulesId)
    fun boardModel() = Board.fromRows(board)
}

@Serializable
enum class MoveType { PLAY, EXCHANGE, PASS, RESIGN, TIMEOUT }

@Serializable
data class MoveRecord(
    val seat: Int,
    val type: MoveType,
    val score: Int = 0,
    val words: List<FormedWord> = emptyList(),
    val tiles: List<Placement> = emptyList(),
    val exchangedCount: Int = 0,
)

sealed interface MoveOutcome {
    data class Applied(val state: GameState, val record: MoveRecord) : MoveOutcome
    data class Rejected(val error: MoveError, val unknownWords: List<String> = emptyList()) : MoveOutcome
}

enum class MoveError { GAME_FINISHED, NOT_YOUR_TURN, ILLEGAL_PLACEMENT, UNKNOWN_WORDS, TILES_NOT_IN_RACK, BAG_TOO_SMALL }

/** Pure game rules: (state, action) -> new state. The server persists, the app only previews. */
object Game {

    fun new(rules: RuleSet, playerCount: Int, random: Random): GameState {
        var bag = rules.fullBag().toList().shuffled(random).joinToString("")
        val players = List(playerCount) {
            val rack = bag.takeLast(rules.rackSize)
            bag = bag.dropLast(rules.rackSize)
            PlayerState(0, rack)
        }
        return GameState(
            rulesId = rules.id,
            board = Board.empty(rules.boardSize).toRows(),
            bag = bag,
            players = players,
            currentPlayer = 0,
        )
    }

    fun apply(state: GameState, seat: Int, action: Action, words: WordList, random: Random): MoveOutcome {
        if (state.status != GameStatus.ACTIVE) return MoveOutcome.Rejected(MoveError.GAME_FINISHED)
        if (action is Action.Resign) return resign(state, seat, MoveType.RESIGN)
        if (seat != state.currentPlayer) return MoveOutcome.Rejected(MoveError.NOT_YOUR_TURN)
        return when (action) {
            is Action.Play -> play(state, seat, action.tiles, words)
            is Action.Exchange -> exchange(state, seat, action.tiles, random)
            Action.Pass -> scoreless(state, MoveRecord(seat, MoveType.PASS), state.players)
            Action.Resign -> error("handled above")
        }
    }

    /** Resignation or turn timeout: the other player wins, whatever the score. */
    fun resign(state: GameState, seat: Int, type: MoveType): MoveOutcome {
        if (state.status != GameStatus.ACTIVE) return MoveOutcome.Rejected(MoveError.GAME_FINISHED)
        val others = state.players.indices.filter { it != seat }
        val finished = state.copy(
            status = GameStatus.FINISHED,
            moveNumber = state.moveNumber + 1,
            winner = others.singleOrNull() ?: others.maxBy { state.players[it].score },
        )
        return MoveOutcome.Applied(finished, MoveRecord(seat, type))
    }

    private fun play(state: GameState, seat: Int, tiles: List<Placement>, words: WordList): MoveOutcome {
        val rules = state.rules
        val player = state.players[seat]
        val rackAfter = removeFromRack(player.rack, tiles.map { if (it.blank) BLANK else it.letter })
            ?: return MoveOutcome.Rejected(MoveError.TILES_NOT_IN_RACK)

        val board = state.boardModel()
        val evaluation = MoveEvaluator.evaluate(board, tiles, rules, words)
        if (evaluation is Evaluation.Invalid) {
            return if (evaluation.reason == InvalidReason.UNKNOWN_WORDS) {
                MoveOutcome.Rejected(MoveError.UNKNOWN_WORDS, evaluation.unknownWords)
            } else {
                MoveOutcome.Rejected(MoveError.ILLEGAL_PLACEMENT)
            }
        }
        evaluation as Evaluation.Valid

        val drawn = state.bag.takeLast(minOf(tiles.size, state.bag.length))
        val players = state.players.toMutableList()
        players[seat] = PlayerState(player.score + evaluation.score, rackAfter + drawn)
        val record = MoveRecord(seat, MoveType.PLAY, evaluation.score, evaluation.words, tiles)
        val next = state.copy(
            board = board.with(tiles).toRows(),
            bag = state.bag.dropLast(drawn.length),
            players = players,
            currentPlayer = (seat + 1) % players.size,
            moveNumber = state.moveNumber + 1,
            scorelessTurns = 0,
        )
        if (next.bag.isEmpty() && players[seat].rack.isEmpty()) {
            return MoveOutcome.Applied(finishByEmptyRack(next, seat), record)
        }
        return MoveOutcome.Applied(next, record)
    }

    private fun exchange(state: GameState, seat: Int, tiles: String, random: Random): MoveOutcome {
        val rules = state.rules
        if (tiles.isEmpty()) return MoveOutcome.Rejected(MoveError.TILES_NOT_IN_RACK)
        if (state.bag.length < rules.rackSize) return MoveOutcome.Rejected(MoveError.BAG_TOO_SMALL)
        val player = state.players[seat]
        val kept = removeFromRack(player.rack, tiles.toList())
            ?: return MoveOutcome.Rejected(MoveError.TILES_NOT_IN_RACK)

        val drawn = state.bag.takeLast(tiles.length)
        val bag = (state.bag.dropLast(tiles.length) + tiles).toList().shuffled(random).joinToString("")
        val players = state.players.toMutableList()
        players[seat] = player.copy(rack = kept + drawn)
        return scoreless(
            state.copy(bag = bag),
            MoveRecord(seat, MoveType.EXCHANGE, exchangedCount = tiles.length),
            players,
        )
    }

    private fun scoreless(state: GameState, record: MoveRecord, players: List<PlayerState>): MoveOutcome {
        val next = state.copy(
            players = players,
            currentPlayer = (record.seat + 1) % players.size,
            moveNumber = state.moveNumber + 1,
            scorelessTurns = state.scorelessTurns + 1,
        )
        if (next.scorelessTurns < state.rules.maxScorelessTurns) return MoveOutcome.Applied(next, record)
        // Everybody loses the value of the tiles left on their rack.
        val rules = state.rules
        val final = next.players.map { it.copy(score = it.score - rackValue(it.rack, rules)) }
        return MoveOutcome.Applied(finish(next.copy(players = final)), record)
    }

    /** The player who went out gains the opponents' remaining tiles, which they lose. */
    private fun finishByEmptyRack(state: GameState, seat: Int): GameState {
        val rules = state.rules
        val leftovers = state.players.map { rackValue(it.rack, rules) }
        val players = state.players.mapIndexed { i, p ->
            if (i == seat) p.copy(score = p.score + leftovers.sum()) else p.copy(score = p.score - leftovers[i])
        }
        return finish(state.copy(players = players))
    }

    private fun finish(state: GameState): GameState {
        val best = state.players.maxOf { it.score }
        val leaders = state.players.indices.filter { state.players[it].score == best }
        return state.copy(status = GameStatus.FINISHED, winner = leaders.singleOrNull())
    }

    private fun rackValue(rack: String, rules: RuleSet) = rack.sumOf { rules.points(it) }

    /** Rack minus [tiles], or null if a tile is missing. */
    fun removeFromRack(rack: String, tiles: List<Char>): String? {
        val remaining = rack.toMutableList()
        for (t in tiles) if (!remaining.remove(t)) return null
        return remaining.joinToString("")
    }
}
