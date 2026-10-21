package app.wordgame.protocol

import app.wordgame.engine.Action
import app.wordgame.engine.FormedWord
import app.wordgame.engine.GameStatus
import app.wordgame.engine.MoveType
import app.wordgame.engine.Placement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

@Serializable
data class RegisterRequest(val nickname: String)

@Serializable
data class AuthResponse(val userId: String, val nickname: String, val token: String)

@Serializable
data class Me(val userId: String, val nickname: String)

@Serializable
data class CreateGameRequest(val opponentNickname: String)

@Serializable
data class MatchmakingResponse(val gameId: String?)

@Serializable
data class MoveRequest(val expectedMoveNumber: Int, val action: Action)

@Serializable
data class PlayerView(val nickname: String, val score: Int, val rackCount: Int)

@Serializable
data class MoveView(
    val moveNumber: Int,
    val seat: Int,
    val type: MoveType,
    val score: Int,
    val words: List<FormedWord>,
    val tiles: List<Placement>,
    val exchangedCount: Int,
    val playedAt: Long,
)

/** What one player is allowed to see: their own rack only, and the bag size. */
@Serializable
data class GameView(
    val id: String,
    val rulesId: String,
    val board: List<String>,
    val rack: String,
    val bagCount: Int,
    val players: List<PlayerView>,
    val mySeat: Int,
    val currentSeat: Int,
    val moveNumber: Int,
    val status: GameStatus,
    val winnerSeat: Int?,
    /** Epoch millis after which the current player loses by timeout. */
    val deadline: Long,
    val moves: List<MoveView>,
)

@Serializable
data class GameSummary(
    val id: String,
    val opponent: String,
    val myScore: Int,
    val opponentScore: Int,
    val myTurn: Boolean,
    val status: GameStatus,
    val won: Boolean?,
    val updatedAt: Long,
)

@Serializable
data class ApiError(val code: String, val message: String, val unknownWords: List<String> = emptyList())
