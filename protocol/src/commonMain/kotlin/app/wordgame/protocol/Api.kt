package app.wordgame.protocol

import app.wordgame.engine.Action
import app.wordgame.engine.FormedWord
import app.wordgame.engine.GameEnd
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

/**
 * The app's version code, sent with every request: the server refuses versions too old for its API
 * (426, UPGRADE_REQUIRED). Requests without it (web pages, tools) are not checked.
 */
const val APP_VERSION_HEADER = "X-App-Version"

/** [googleIdToken]: also link the new account to this Google account ("Sign in with Google" found none). */
@Serializable
data class RegisterRequest(val nickname: String, val googleIdToken: String? = null)

/** An ID token from "Sign in with Google", to sign in or to link the account. */
@Serializable
data class GoogleRequest(val idToken: String)

/** [tag]: tells apart the players with the same nickname ("greg#4821"); 0 from older servers. */
@Serializable
data class AuthResponse(val userId: String, val nickname: String, val token: String, val tag: Int = 0)

/** [google]: the account is linked to a Google account. */
@Serializable
data class Me(val userId: String, val nickname: String, val tag: Int = 0, val google: Boolean = false)

@Serializable
data class DeviceRequest(val token: String)

/**
 * A game with friends (by nickname, or "nickname#tag" when several players share it) and robots (by level: "nono", "iro", "belze"), 2 to 4 players with the caller,
 * in [language] ("fr" or "en"). [opponentNickname] is the single friend of older app versions.
 */
@Serializable
data class CreateGameRequest(
    val opponentNickname: String? = null,
    val friends: List<String> = emptyList(),
    val bots: List<String> = emptyList(),
    val language: String = "fr",
)

@Serializable
data class MatchmakingResponse(val gameId: String?)

@Serializable
data class MoveRequest(val expectedMoveNumber: Int, val action: Action)

@Serializable
data class PlayerView(
    val nickname: String,
    val score: Int,
    val rackCount: Int,
    /** Resigned or timed out in a game of 3 or more. */
    val eliminated: Boolean = false,
    val bot: Boolean = false,
)

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
    /** Score change of each seat when this move ended the game (going out, blocked game…). */
    val endAdjustments: List<Int> = emptyList(),
    val end: GameEnd? = null,
    /** Rack before the move: only for the player's own moves, and for everybody once the game is over. */
    val rack: String? = null,
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
    /** The other players' nicknames, comma-separated. */
    val opponent: String,
    val myScore: Int,
    /** Best score among the other players. */
    val opponentScore: Int,
    val myTurn: Boolean,
    val status: GameStatus,
    val won: Boolean?,
    val updatedAt: Long,
    /** Language of the words: "fr" or "en". */
    val language: String = "fr",
    /** Every other player is a robot. */
    val allBots: Boolean = false,
)

@Serializable
data class ApiError(val code: String, val message: String, val unknownWords: List<String> = emptyList())

/** Result of a finished game, from the requesting player's point of view. */
@Serializable
enum class GameResult { WIN, LOSS, DRAW }

@Serializable
data class FinishedGame(
    val gameId: String,
    val opponent: String,
    val myScore: Int,
    val opponentScore: Int,
    val result: GameResult,
    val finishedAt: Long,
    /** Turns where the player asked for hints. */
    val hints: Int = 0,
    /** Share (0 to 1) of the points the player could have scored on their turns; null when unknown. */
    val share: Double? = null,
)

@Serializable
data class BestMove(val words: List<String>, val score: Int, val opponent: String, val playedAt: Long)

@Serializable
data class OpponentRecord(val nickname: String, val wins: Int, val losses: Int, val draws: Int)

@Serializable
data class PlayerStats(
    val finishedGames: Int,
    val activeGames: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    /** Over finished games; null when there is none. */
    val averageScore: Double?,
    val averageMargin: Double?,
    /** Over all the player's turns (plays, passes, exchanges), finished or active games. */
    val pointsPerTurn: Double?,
    val bingos: Int,
    val bestMove: BestMove?,
    val bestGame: FinishedGame?,
    val longestWord: String?,
    /** Oldest first. */
    val history: List<FinishedGame>,
    val opponents: List<OpponentRecord>,
    /** Turns where the player asked for hints, finished or active games. */
    val hintTurns: Int = 0,
    /** Share (0 to 1) of the possible points scored over the finished games; null when unknown. */
    val share: Double? = null,
)

/** Why a player is reported. */
@Serializable
enum class ReportReason { NICKNAME, CHEATING, OTHER }

/** Reports the player at a seat of a game; [block]: also block them. */
@Serializable
data class ReportRequest(val reason: ReportReason, val details: String? = null, val block: Boolean = false)

/** A player the caller blocked: they can no longer challenge each other nor be paired. */
@Serializable
data class BlockedPlayer(val userId: String, val name: String)
