package app.wordgame.server

import app.wordgame.engine.Game
import app.wordgame.engine.GameState
import app.wordgame.engine.GameStatus
import app.wordgame.engine.MoveOutcome
import app.wordgame.engine.MoveRecord
import app.wordgame.engine.MoveType
import app.wordgame.engine.RuleSet
import app.wordgame.engine.WordList
import app.wordgame.protocol.ApiJson
import app.wordgame.protocol.AuthResponse
import app.wordgame.protocol.BestMove
import app.wordgame.protocol.FinishedGame
import app.wordgame.protocol.GameResult
import app.wordgame.protocol.OpponentRecord
import app.wordgame.protocol.PlayerStats
import app.wordgame.protocol.GameSummary
import app.wordgame.protocol.GameView
import app.wordgame.protocol.MatchmakingResponse
import app.wordgame.protocol.MoveRequest
import app.wordgame.protocol.MoveView
import app.wordgame.protocol.PlayerView
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import java.util.UUID
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

data class User(val id: String, val nickname: String)

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    message: String,
    val unknownWords: List<String> = emptyList(),
) : Exception(message)

class GameService(
    private val db: Database,
    private val words: WordList,
    private val rules: RuleSet = RuleSet.DEFAULT,
    private val turnTimeout: Duration = 72.hours,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom().asKotlinRandom(),
) {
    private class Seat(val userId: String, val nickname: String)
    private class StoredGame(val id: String, val state: GameState, val deadline: Long, val seats: List<Seat>)

    suspend fun register(nickname: String): AuthResponse {
        if (!NICKNAME.matches(nickname)) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_NICKNAME", "3 à 16 caractères : lettres, chiffres, _ ou -")
        }
        val token = randomToken()
        val id = UUID.randomUUID().toString()
        db.tx {
            val taken = query("SELECT 1 FROM users WHERE nickname = ?", nickname) { true }.isNotEmpty()
            if (taken) throw ApiException(HttpStatusCode.Conflict, "NICKNAME_TAKEN", "Pseudo déjà utilisé")
            update("INSERT INTO users(id, nickname, token_hash, created_at) VALUES (?, ?, ?, ?)", id, nickname, sha256(token), now())
        }
        return AuthResponse(id, nickname, token)
    }

    suspend fun authenticate(token: String): User? = db.tx {
        query("SELECT id, nickname FROM users WHERE token_hash = ?", sha256(token)) { User(it.getString(1), it.getString(2)) }
            .firstOrNull()
    }

    suspend fun listGames(user: User): List<GameSummary> = db.tx {
        val ids = query(
            """SELECT g.id FROM games g JOIN game_players p ON p.game_id = g.id
               WHERE p.user_id = ? ORDER BY g.status, g.updated_at DESC LIMIT 100""",
            user.id,
        ) { it.getString(1) }
        ids.map { id ->
            val game = load(id)
            val seat = game.seatOf(user)
            val opponent = 1 - seat
            GameSummary(
                id = id,
                opponent = game.seats[opponent].nickname,
                myScore = game.state.players[seat].score,
                opponentScore = game.state.players[opponent].score,
                myTurn = game.state.status == GameStatus.ACTIVE && game.state.currentPlayer == seat,
                status = game.state.status,
                won = game.state.winner?.let { it == seat },
                updatedAt = query("SELECT updated_at FROM games WHERE id = ?", id) { it.getLong(1) }.single(),
            )
        }
    }

    /** Computed from the stored games and moves on each call: cheap at this scale, nothing to keep in sync. */
    suspend fun stats(user: User): PlayerStats = db.tx {
        val ids = query("SELECT game_id FROM game_players WHERE user_id = ?", user.id) { it.getString(1) }
        val finished = ArrayList<FinishedGame>()
        val myMoves = ArrayList<Pair<MoveRecord, Pair<String, Long>>>() // move, (opponent, playedAt)
        var active = 0
        for (id in ids) {
            val game = load(id)
            val seat = game.seatOf(user)
            val opponent = game.seats[1 - seat].nickname
            val state = game.state
            query("SELECT record, played_at FROM moves WHERE game_id = ?", id) {
                ApiJson.decodeFromString(MoveRecord.serializer(), it.getString(1)) to it.getLong(2)
            }.filter { it.first.seat == seat }.forEach { (record, at) -> myMoves += record to (opponent to at) }
            if (state.status != GameStatus.FINISHED) {
                active++
                continue
            }
            val finishedAt = query("SELECT updated_at FROM games WHERE id = ?", id) { it.getLong(1) }.single()
            finished += FinishedGame(
                gameId = id,
                opponent = opponent,
                myScore = state.players[seat].score,
                opponentScore = state.players[1 - seat].score,
                result = when (state.winner) {
                    seat -> GameResult.WIN
                    null -> GameResult.DRAW
                    else -> GameResult.LOSS
                },
                finishedAt = finishedAt,
            )
        }
        finished.sortBy { it.finishedAt }
        val turns = myMoves.filter { it.first.type in setOf(MoveType.PLAY, MoveType.PASS, MoveType.EXCHANGE) }
        val plays = myMoves.filter { it.first.type == MoveType.PLAY }
        val best = plays.maxByOrNull { it.first.score }
        PlayerStats(
            finishedGames = finished.size,
            activeGames = active,
            wins = finished.count { it.result == GameResult.WIN },
            losses = finished.count { it.result == GameResult.LOSS },
            draws = finished.count { it.result == GameResult.DRAW },
            averageScore = finished.takeIf { it.isNotEmpty() }?.map { it.myScore }?.average(),
            averageMargin = finished.takeIf { it.isNotEmpty() }?.map { it.myScore - it.opponentScore }?.average(),
            pointsPerTurn = turns.takeIf { it.isNotEmpty() }?.map { it.first.score }?.average(),
            bingos = plays.count { it.first.tiles.size == rules.rackSize },
            bestMove = best?.let { (m, ctx) -> BestMove(m.words.map { it.word }, m.score, ctx.first, ctx.second) },
            bestGame = finished.maxByOrNull { it.myScore },
            longestWord = plays.flatMap { it.first.words }.maxWithOrNull(compareBy({ it.word.length }, { it.score }))?.word,
            history = finished,
            opponents = finished.groupBy { it.opponent }.map { (name, games) ->
                OpponentRecord(
                    name,
                    games.count { it.result == GameResult.WIN },
                    games.count { it.result == GameResult.LOSS },
                    games.count { it.result == GameResult.DRAW },
                )
            }.sortedByDescending { it.wins + it.losses + it.draws },
        )
    }

    suspend fun createGame(user: User, opponentNickname: String): GameView = db.tx {
        val opponent = query("SELECT id, nickname FROM users WHERE nickname = ?", opponentNickname) {
            User(it.getString(1), it.getString(2))
        }.firstOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "UNKNOWN_PLAYER", "Joueur introuvable")
        if (opponent.id == user.id) throw ApiException(HttpStatusCode.BadRequest, "SELF_GAME", "Impossible de jouer contre soi-même")
        checkActiveGameLimit(user)
        checkActiveGameLimit(opponent)
        val id = insertGame(listOf(user, opponent))
        view(load(id), user)
    }

    /** Pairs with the oldest waiting player, or queues the caller. */
    suspend fun matchmake(user: User): MatchmakingResponse = db.tx {
        checkActiveGameLimit(user)
        val waiting = query(
            """SELECT u.id, u.nickname FROM matchmaking m JOIN users u ON u.id = m.user_id
               WHERE m.user_id <> ? ORDER BY m.queued_at LIMIT 1""",
            user.id,
        ) { User(it.getString(1), it.getString(2)) }.firstOrNull()
        if (waiting == null) {
            update("INSERT OR IGNORE INTO matchmaking(user_id, queued_at) VALUES (?, ?)", user.id, now())
            MatchmakingResponse(null)
        } else {
            update("DELETE FROM matchmaking WHERE user_id IN (?, ?)", waiting.id, user.id)
            MatchmakingResponse(insertGame(listOf(user, waiting)))
        }
    }

    suspend fun leaveMatchmaking(user: User) = db.tx {
        update("DELETE FROM matchmaking WHERE user_id = ?", user.id)
    }

    /** A player may delete a game until every player has made at least one move (an ignored challenge). */
    suspend fun deleteGame(user: User, gameId: String) = db.tx {
        val game = load(gameId)
        game.seatOf(user)
        val seatsThatPlayed = query("SELECT record FROM moves WHERE game_id = ?", gameId) {
            ApiJson.decodeFromString(MoveRecord.serializer(), it.getString(1)).seat
        }.toSet()
        if (seatsThatPlayed.size >= game.seats.size) {
            throw ApiException(HttpStatusCode.Conflict, "GAME_STARTED", "Les deux joueurs ont déjà joué")
        }
        update("DELETE FROM moves WHERE game_id = ?", gameId)
        update("DELETE FROM game_players WHERE game_id = ?", gameId)
        update("DELETE FROM games WHERE id = ?", gameId)
    }

    suspend fun view(user: User, gameId: String): GameView = db.tx { view(load(gameId), user) }

    suspend fun move(user: User, gameId: String, request: MoveRequest): GameView = db.tx {
        val game = load(gameId)
        val seat = game.seatOf(user)
        if (request.expectedMoveNumber != game.state.moveNumber) {
            throw ApiException(HttpStatusCode.Conflict, "STALE_GAME", "La partie a changé, rechargez-la")
        }
        when (val outcome = Game.apply(game.state, seat, request.action, words, random)) {
            is MoveOutcome.Rejected -> throw ApiException(
                HttpStatusCode.UnprocessableEntity, outcome.error.name, outcome.error.name, outcome.unknownWords,
            )
            is MoveOutcome.Applied -> save(game.id, outcome.state, outcome.record)
        }
        view(load(gameId), user)
    }

    /** Current player loses when they have not played before the deadline. */
    suspend fun expireTimeouts(): Int = db.tx {
        val expired = query("SELECT id FROM games WHERE status = 'ACTIVE' AND deadline < ?", now()) { it.getString(1) }
        for (id in expired) {
            val state = load(id).state
            val outcome = Game.resign(state, state.currentPlayer, MoveType.TIMEOUT)
            if (outcome is MoveOutcome.Applied) save(id, outcome.state, outcome.record)
        }
        expired.size
    }

    private fun Connection.insertGame(players: List<User>): String {
        val id = UUID.randomUUID().toString()
        val seats = players.shuffled(random)
        val state = Game.new(rules, seats.size, random)
        val t = now()
        update(
            "INSERT INTO games(id, state, status, move_number, deadline, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, ApiJson.encodeToString(GameState.serializer(), state), state.status.name, state.moveNumber,
            t + turnTimeout.inWholeMilliseconds, t, t,
        )
        seats.forEachIndexed { seat, u -> update("INSERT INTO game_players(game_id, seat, user_id) VALUES (?, ?, ?)", id, seat, u.id) }
        return id
    }

    private fun Connection.save(id: String, state: GameState, record: MoveRecord) {
        val t = now()
        update(
            "UPDATE games SET state = ?, status = ?, move_number = ?, deadline = ?, updated_at = ? WHERE id = ?",
            ApiJson.encodeToString(GameState.serializer(), state), state.status.name, state.moveNumber,
            t + turnTimeout.inWholeMilliseconds, t, id,
        )
        update(
            "INSERT INTO moves(game_id, move_number, record, played_at) VALUES (?, ?, ?, ?)",
            id, state.moveNumber, ApiJson.encodeToString(MoveRecord.serializer(), record), t,
        )
    }

    private fun Connection.load(id: String): StoredGame {
        val row = query("SELECT state, deadline FROM games WHERE id = ?", id) { it.getString(1) to it.getLong(2) }.firstOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "UNKNOWN_GAME", "Partie introuvable")
        val seats = query(
            "SELECT u.id, u.nickname FROM game_players p JOIN users u ON u.id = p.user_id WHERE p.game_id = ? ORDER BY p.seat",
            id,
        ) { Seat(it.getString(1), it.getString(2)) }
        return StoredGame(id, ApiJson.decodeFromString(GameState.serializer(), row.first), row.second, seats)
    }

    private fun StoredGame.seatOf(user: User): Int = seats.indexOfFirst { it.userId == user.id }
        .takeIf { it >= 0 } ?: throw ApiException(HttpStatusCode.NotFound, "UNKNOWN_GAME", "Partie introuvable")

    private fun Connection.view(game: StoredGame, user: User): GameView {
        val seat = game.seatOf(user)
        val state = game.state
        val moves = query("SELECT move_number, record, played_at FROM moves WHERE game_id = ? ORDER BY move_number", game.id) {
            val r = ApiJson.decodeFromString(MoveRecord.serializer(), it.getString(2))
            MoveView(it.getInt(1), r.seat, r.type, r.score, r.words, r.tiles, r.exchangedCount, it.getLong(3))
        }
        return GameView(
            id = game.id,
            rulesId = state.rulesId,
            board = state.board,
            rack = state.players[seat].rack,
            bagCount = state.bag.length,
            players = state.players.mapIndexed { i, p -> PlayerView(game.seats[i].nickname, p.score, p.rack.length) },
            mySeat = seat,
            currentSeat = state.currentPlayer,
            moveNumber = state.moveNumber,
            status = state.status,
            winnerSeat = state.winner,
            deadline = game.deadline,
            moves = moves,
        )
    }

    private fun Connection.checkActiveGameLimit(user: User) {
        val active = query(
            "SELECT COUNT(*) FROM games g JOIN game_players p ON p.game_id = g.id WHERE p.user_id = ? AND g.status = 'ACTIVE'",
            user.id,
        ) { it.getInt(1) }.single()
        if (active >= MAX_ACTIVE_GAMES) {
            throw ApiException(HttpStatusCode.Conflict, "TOO_MANY_GAMES", "${user.nickname} a déjà $MAX_ACTIVE_GAMES parties en cours")
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_ACTIVE_GAMES = 30
        val NICKNAME = Regex("^[A-Za-z0-9_-]{3,16}$")
    }
}
