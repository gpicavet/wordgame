package app.wordgame.android

import android.content.Context
import app.wordgame.engine.Action
import app.wordgame.engine.BeginnerBot
import app.wordgame.engine.ValueBot
import app.wordgame.engine.bingoAllowed
import app.wordgame.engine.Game
import app.wordgame.engine.GameState
import app.wordgame.engine.GameStatus
import app.wordgame.engine.MoveOutcome
import app.wordgame.engine.MoveRecord
import app.wordgame.engine.RuleSet
import app.wordgame.protocol.ApiError
import app.wordgame.protocol.ApiJson
import app.wordgame.protocol.GameView
import app.wordgame.protocol.MoveView
import app.wordgame.protocol.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

/** Where a game lives: on the server (online) or on the phone (solo). The game screen does not care. */
interface GameBackend {
    val solo: Boolean
    suspend fun load(): GameView
    suspend fun move(game: GameView, action: Action): GameView
    suspend fun delete()
}

class ServerBackend(private val api: ApiClient, private val gameId: String) : GameBackend {
    override val solo = false
    override suspend fun load() = api.game(gameId)
    override suspend fun move(game: GameView, action: Action) = api.move(game, action)
    override suspend fun delete() = api.deleteGame(gameId)
}

/** Solo opponents. Each has its own game in progress, saved in its own file. */
/** [serverLevel]: the same robot in online games ([app.wordgame.protocol.CreateGameRequest.bots]). */
enum class SoloBot(val gameId: String, val displayName: String, val level: String, val file: String, val serverLevel: String) {
    NONO("solo", "NonoBot", "débutant", "solo.json", "nono"),
    BELZE("solo-belze", "BelzéBot", "expert", "solo-belze.json", "belze");

    companion object {
        fun byGameId(id: String) = entries.firstOrNull { it.gameId == id }
    }
}

@Serializable
private data class SoloMove(val record: MoveRecord, val playedAt: Long)

@Serializable
private data class SoloGame(val state: GameState, val moves: List<SoloMove>)

/** Short description for the games list. */
data class SoloSummary(val myScore: Int, val botScore: Int, val myTurn: Boolean, val finished: Boolean, val won: Boolean?)

/**
 * One solo game against [bot], saved in a file on the phone. Same rules engine as the server.
 * The player is seat 0. The bot plays when the game is loaded and it is its turn.
 * - NonoBot: [BeginnerBot] (everyday words, not always the best move);
 * - BelzéBot: [ValueBot] with the weights learned by self-play (models/value-v2.txt), everyday words too.
 */
class SoloStore(
    context: Context,
    private val dictionary: DictionaryLoader,
    private val session: Session,
    val bot: SoloBot,
) {
    private val file = File(context.filesDir, bot.file)
    private val random = SecureRandom().asKotlinRandom()
    private val lock = Mutex()

    private fun read(): SoloGame? = runCatching { ApiJson.decodeFromString(SoloGame.serializer(), file.readText()) }.getOrNull()
    private fun write(game: SoloGame) = file.writeText(ApiJson.encodeToString(SoloGame.serializer(), game))

    fun summary(): SoloSummary? = read()?.state?.let {
        SoloSummary(it.players[0].score, it.players[1].score, it.status == GameStatus.ACTIVE && it.currentPlayer == 0,
            it.status == GameStatus.FINISHED, it.winner?.let { w -> w == 0 })
    }

    /** Starts a new game (replacing a finished or abandoned one); the player or the bot starts at random. */
    suspend fun start() = lock.withLock {
        withContext(Dispatchers.IO) {
            val state = Game.new(RuleSet.DEFAULT, 2, random)
            write(SoloGame(state.copy(currentPlayer = random.nextInt(2)), emptyList()))
        }
    }

    suspend fun delete() {
        lock.withLock { withContext(Dispatchers.IO) { file.delete() } }
    }

    val backend: GameBackend = object : GameBackend {
        override val solo = true

        override suspend fun load(): GameView = lock.withLock {
            var game = withContext(Dispatchers.IO) { read() } ?: error("Aucune partie solo")
            if (game.state.status == GameStatus.ACTIVE && game.state.currentPlayer == 1) {
                delay(700) // let the player see their own move first
                game = withContext(Dispatchers.Default) { botTurn(game) }
                withContext(Dispatchers.IO) { write(game) }
            }
            view(game)
        }

        override suspend fun move(game: GameView, action: Action): GameView = lock.withLock {
            val current = withContext(Dispatchers.IO) { read() } ?: error("Aucune partie solo")
            if (game.moveNumber != current.state.moveNumber) throw ApiFailure(ApiError("STALE_GAME", "La partie a changé"))
            val lexicon = dictionary.get()
            when (val outcome = Game.apply(current.state, 0, action, lexicon.words, random)) {
                is MoveOutcome.Rejected -> throw ApiFailure(ApiError(outcome.error.name, outcome.error.name, outcome.unknownWords))
                is MoveOutcome.Applied -> {
                    val next = SoloGame(outcome.state, current.moves + SoloMove(outcome.record, System.currentTimeMillis()))
                    withContext(Dispatchers.IO) { write(next) }
                    view(next)
                }
            }
        }

        override suspend fun delete() = this@SoloStore.delete()
    }

    private suspend fun botTurn(game: SoloGame): SoloGame {
        val action = when (bot) {
            SoloBot.NONO -> BeginnerBot(dictionary.get(), dictionary.common(), random).play(game.state, 1)
            SoloBot.BELZE -> ValueBot(dictionary.get(), dictionary.expertWeights(), random, vocabulary = dictionary.common())
                .decide(game.state, 1, bingoAllowed(game.moves.map { it.record }, 1, random)).action
        }
        val outcome = Game.apply(game.state, 1, action, dictionary.get().words, random)
        check(outcome is MoveOutcome.Applied) { "Bot move rejected: $outcome" }
        return SoloGame(outcome.state, game.moves + SoloMove(outcome.record, System.currentTimeMillis()))
    }

    private fun view(game: SoloGame): GameView {
        val s = game.state
        val names = listOf(session.nickname ?: "Toi", bot.displayName)
        return GameView(
            id = bot.gameId,
            rulesId = s.rulesId,
            board = s.board,
            rack = s.players[0].rack,
            bagCount = s.bag.length,
            players = s.players.mapIndexed { i, p -> PlayerView(names[i], p.score, p.rack.length) },
            mySeat = 0,
            currentSeat = s.currentPlayer,
            moveNumber = s.moveNumber,
            status = s.status,
            winnerSeat = s.winner,
            deadline = Long.MAX_VALUE,
            moves = game.moves.mapIndexed { i, (r, at) ->
                MoveView(
                    i + 1, r.seat, r.type, r.score, r.words, r.tiles, r.exchangedCount, at, r.endAdjustments, r.end,
                    // Like online: the bot's racks stay hidden until the end.
                    r.rack?.takeIf { r.seat == 0 || s.status == GameStatus.FINISHED },
                )
            },
        )
    }
}
