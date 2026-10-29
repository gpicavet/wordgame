package app.wordgame.server

import app.wordgame.engine.Action
import app.wordgame.engine.Game
import app.wordgame.engine.GameEnd
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
import app.wordgame.protocol.CreateGameRequest
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
    /** Dictionary of each language games can be played in ("fr", "en"). */
    private val dictionaries: Map<String, WordList>,
    private val turnTimeout: Duration = 72.hours,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom().asKotlinRandom(),
    private val notifier: Notifier = NoNotifier,
    private val bots: List<ServerBot> = emptyList(),
) {
    private val botsById = bots.associateBy { it.userId }

    private fun words(state: GameState) = dictionaries.getValue(state.language)

    private fun checkLanguage(language: String) {
        if (language !in dictionaries) throw ApiException(HttpStatusCode.BadRequest, "UNKNOWN_LANGUAGE", "Langue non disponible : $language")
    }

    private inner class Seat(val userId: String, val nickname: String) {
        val bot get() = botsById[userId]
    }

    private inner class StoredGame(val id: String, val state: GameState, val deadline: Long, val seats: List<Seat>) {
        /** The other players' nicknames and best score, from [seat]'s side. */
        fun opponents(seat: Int) = seats.indices.filter { it != seat }.joinToString(", ") { seats[it].nickname }
        fun bestOpponentScore(seat: Int) = state.players.indices.filter { it != seat }.maxOf { state.players[it].score }
    }

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
            GameSummary(
                id = id,
                opponent = game.opponents(seat),
                myScore = game.state.players[seat].score,
                opponentScore = game.bestOpponentScore(seat),
                myTurn = game.state.status == GameStatus.ACTIVE && game.state.currentPlayer == seat,
                status = game.state.status,
                won = game.state.winner?.let { it == seat },
                updatedAt = query("SELECT updated_at FROM games WHERE id = ?", id) { it.getLong(1) }.single(),
                language = game.state.language,
                allBots = game.seats.indices.all { it == seat || game.seats[it].bot != null },
            )
        }
    }

    /**
     * Over the games in [language] (all languages when null). Computed from the stored games and moves on each
     * call: cheap at this scale, nothing to keep in sync.
     */
    suspend fun stats(user: User, language: String? = null): PlayerStats = db.tx {
        val ids = query("SELECT game_id FROM game_players WHERE user_id = ?", user.id) { it.getString(1) }
        val finished = ArrayList<FinishedGame>()
        val myMoves = ArrayList<Pair<MoveRecord, Pair<String, Long>>>() // move, (opponent, playedAt)
        var active = 0
        var hintTurns = 0
        for (id in ids) {
            val game = load(id)
            if (language != null && game.state.language != language) continue
            val seat = game.seatOf(user)
            val opponent = game.opponents(seat)
            val state = game.state
            query("SELECT record, played_at FROM moves WHERE game_id = ?", id) {
                ApiJson.decodeFromString(MoveRecord.serializer(), it.getString(1)) to it.getLong(2)
            }.filter { it.first.seat == seat }.forEach { (record, at) -> myMoves += record to (opponent to at) }
            val hints = query("SELECT COUNT(*) FROM hints WHERE game_id = ? AND user_id = ?", id, user.id) { it.getInt(1) }.single()
            hintTurns += hints
            if (state.status != GameStatus.FINISHED) {
                active++
                continue
            }
            val finishedAt = query("SELECT updated_at FROM games WHERE id = ?", id) { it.getLong(1) }.single()
            finished += FinishedGame(
                gameId = id,
                opponent = opponent,
                myScore = state.players[seat].score,
                opponentScore = game.bestOpponentScore(seat),
                result = when (state.winner) {
                    seat -> GameResult.WIN
                    null -> GameResult.DRAW
                    else -> GameResult.LOSS
                },
                finishedAt = finishedAt,
                hints = hints,
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
            bingos = plays.count { it.first.tiles.size == RuleSet.DEFAULT.rackSize },
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
            hintTurns = hintTurns,
        )
    }

    /** A game with friends (each at most once) and robots (the same one possibly twice): 2 to 4 players with the caller. */
    suspend fun createGame(user: User, request: CreateGameRequest): GameView = db.tx {
        checkLanguage(request.language)
        val names = request.friends.ifEmpty { listOfNotNull(request.opponentNickname) }
        if (names.isEmpty() && request.bots.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "NO_OPPONENT", "Choisis au moins un adversaire")
        if (names.size + request.bots.size > 3) throw ApiException(HttpStatusCode.BadRequest, "TOO_MANY_PLAYERS", "4 joueurs au plus")
        val friends = names.map { name ->
            query("SELECT id, nickname FROM users WHERE nickname = ?", name) { User(it.getString(1), it.getString(2)) }
                .firstOrNull()?.takeIf { it.id !in botsById }
                ?: throw ApiException(HttpStatusCode.NotFound, "UNKNOWN_PLAYER", "Joueur introuvable : $name")
        }
        if (friends.any { it.id == user.id }) throw ApiException(HttpStatusCode.BadRequest, "SELF_GAME", "Impossible de jouer contre soi-même")
        if (friends.map { it.id }.toSet().size < friends.size) {
            throw ApiException(HttpStatusCode.BadRequest, "DUPLICATE_PLAYER", "Chaque joueur ne peut être choisi qu'une fois")
        }
        val robots = request.bots.map { level ->
            val bot = bots.firstOrNull { it.level == level }
                ?: throw ApiException(HttpStatusCode.BadRequest, "UNKNOWN_BOT", "Robot inconnu : $level")
            update(
                "INSERT OR IGNORE INTO users(id, nickname, token_hash, created_at) VALUES (?, ?, ?, ?)",
                bot.userId, bot.nickname, "bot:${bot.userId}", now(),
            )
            User(bot.userId, bot.nickname)
        }
        checkActiveGameLimit(user)
        friends.forEach { checkActiveGameLimit(it) }
        val id = insertGame(listOf(user) + friends + robots, request.language)
        val botMoves = playBots(load(id))
        val game = load(id)
        val others = if (friends.size + robots.size > 1) " (avec ${(friends + robots).joinToString { it.nickname }})" else ""
        val invitations = friends.map { challenge(game, it, "Nouveau défi", "${user.nickname} te défie$others") }
        view(game, user) to invitations + botMoves.filter { n -> n.userId != user.id && friends.none { it.id == n.userId } }
    }.let { (view, notifications) -> notifier.send(notifications); view }

    /** Pairs with the oldest player waiting for a game in the same [language], or queues the caller. */
    suspend fun matchmake(user: User, language: String = "fr"): MatchmakingResponse = db.tx {
        checkLanguage(language)
        checkActiveGameLimit(user)
        val waiting = query(
            """SELECT u.id, u.nickname FROM matchmaking m JOIN users u ON u.id = m.user_id
               WHERE m.user_id <> ? AND m.language = ? ORDER BY m.queued_at LIMIT 1""",
            user.id, language,
        ) { User(it.getString(1), it.getString(2)) }.firstOrNull()
        if (waiting == null) {
            // Waiting for one language at a time: the latest request wins.
            update("INSERT OR REPLACE INTO matchmaking(user_id, queued_at, language) VALUES (?, ?, ?)", user.id, now(), language)
            MatchmakingResponse(null)
        } else {
            update("DELETE FROM matchmaking WHERE user_id IN (?, ?)", waiting.id, user.id)
            val id = insertGame(listOf(user, waiting), language)
            notifier.send(listOf(challenge(load(id), waiting, "Adversaire trouvé", "Partie contre ${user.nickname}")))
            MatchmakingResponse(id)
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
        val humans = game.seats.indices.filter { game.seats[it].bot == null }
        if (humans.all { it in seatsThatPlayed }) {
            throw ApiException(HttpStatusCode.Conflict, "GAME_STARTED", "Tous les joueurs ont déjà joué")
        }
        update("DELETE FROM moves WHERE game_id = ?", gameId)
        update("DELETE FROM hints WHERE game_id = ?", gameId)
        update("DELETE FROM game_players WHERE game_id = ?", gameId)
        update("DELETE FROM games WHERE id = ?", gameId)
    }

    suspend fun view(user: User, gameId: String): GameView = db.tx { view(load(gameId), user) }

    /** The player asked for hints on their turn: counted once per turn. Only against robots, as the app allows. */
    suspend fun recordHint(user: User, gameId: String) = db.tx {
        val game = load(gameId)
        val seat = game.seatOf(user)
        if (game.seats.indices.any { it != seat && game.seats[it].bot == null }) {
            throw ApiException(HttpStatusCode.Forbidden, "HINTS_AGAINST_PEOPLE", "Pas d'indices contre d'autres joueurs")
        }
        if (game.state.status != GameStatus.ACTIVE || game.state.currentPlayer != seat) {
            throw ApiException(HttpStatusCode.Conflict, "NOT_YOUR_TURN", "Ce n'est pas ton tour")
        }
        update("INSERT OR IGNORE INTO hints (game_id, user_id, move_number) VALUES (?, ?, ?)", gameId, user.id, game.state.moveNumber)
    }

    suspend fun move(user: User, gameId: String, request: MoveRequest): GameView = db.tx {
        val game = load(gameId)
        val seat = game.seatOf(user)
        if (request.expectedMoveNumber != game.state.moveNumber) {
            throw ApiException(HttpStatusCode.Conflict, "STALE_GAME", "La partie a changé, rechargez-la")
        }
        val notifications = when (val outcome = Game.apply(game.state, seat, request.action, words(game.state), random)) {
            is MoveOutcome.Rejected -> throw ApiException(
                HttpStatusCode.UnprocessableEntity, outcome.error.name, outcome.error.name, outcome.unknownWords,
            )
            is MoveOutcome.Applied -> {
                save(game.id, outcome.state, outcome.record)
                afterMove(game, outcome.state, outcome.record) + playBots(load(gameId))
            }
        }
        view(load(gameId), user) to notifications
    }.let { (view, notifications) -> notifier.send(notifications); view }

    /** Current player loses when they have not played before the deadline. */
    suspend fun expireTimeouts(): Int = db.tx {
        val expired = query("SELECT id FROM games WHERE status = 'ACTIVE' AND deadline < ?", now()) { it.getString(1) }
        for (id in expired) {
            val state = load(id).state
            val outcome = Game.resign(state, state.currentPlayer, MoveType.TIMEOUT, random)
            if (outcome is MoveOutcome.Applied) {
                save(id, outcome.state, outcome.record)
                notifier.send(afterMove(load(id), outcome.state, outcome.record) + playBots(load(id)))
            }
        }
        expired.size
    }

    /** Remembers the phone of [user] for push notifications (a token belongs to one account at a time). */
    suspend fun registerDevice(user: User, token: String) {
        if (token.isBlank() || token.length > 4096) throw ApiException(HttpStatusCode.BadRequest, "INVALID_TOKEN", "Jeton invalide")
        db.tx {
            update(
                "INSERT INTO devices(token, user_id, updated_at) VALUES (?, ?, ?) " +
                    "ON CONFLICT(token) DO UPDATE SET user_id = excluded.user_id, updated_at = excluded.updated_at",
                token, user.id, now(),
            )
        }
    }

    /** Plays the robots' turns until a human is to play or the game is over; returns what to tell the humans. */
    private fun Connection.playBots(start: StoredGame): List<Notification> {
        var game = start
        val notifications = ArrayList<Notification>()
        while (game.state.status == GameStatus.ACTIVE) {
            val seat = game.state.currentPlayer
            val bot = game.seats[seat].bot ?: break
            val history = query("SELECT record FROM moves WHERE game_id = ? ORDER BY move_number", game.id) {
                ApiJson.decodeFromString(MoveRecord.serializer(), it.getString(1))
            }
            val words = words(game.state)
            val outcome = Game.apply(game.state, seat, bot.play(game.state, seat, history), words, random)
                .let { if (it is MoveOutcome.Applied) it else Game.apply(game.state, seat, Action.Pass, words, random) }
            check(outcome is MoveOutcome.Applied) { "Bot move rejected: $outcome" }
            save(game.id, outcome.state, outcome.record)
            notifications += afterMove(game, outcome.state, outcome.record)
            game = load(game.id)
        }
        // Only the latest news matters to each human.
        return notifications.reversed().distinctBy { it.userId }.reversed()
    }

    private fun challenge(game: StoredGame, to: User, title: String, body: String): Notification {
        val theirTurn = game.seats[game.state.currentPlayer].userId == to.id
        return Notification(to.id, title, if (theirTurn) "$body. À toi de jouer !" else "$body.", game.id)
    }

    /**
     * Who to tell what after a move: the next player, or everybody else when the game is over. Nobody when a
     * single human plays against robots: they answer at once, there is nothing to wait for.
     */
    private fun afterMove(game: StoredGame, state: GameState, record: MoveRecord): List<Notification> {
        if (game.seats.count { it.bot == null } <= 1) return emptyList()
        val mover = game.seats[record.seat]
        val what = when (record.type) {
            MoveType.PLAY -> "${mover.nickname} a joué ${record.words.joinToString { it.word }} (+${record.score}" +
                (if (record.end == GameEnd.OUT) ", +${record.endAdjustments[record.seat]} de bonus de fin)" else ")")
            MoveType.EXCHANGE -> "${mover.nickname} a échangé ${record.exchangedCount} lettre(s)"
            MoveType.PASS -> if (record.end == GameEnd.BLOCKED) "Partie bloquée" else "${mover.nickname} a passé son tour"
            MoveType.RESIGN -> "${mover.nickname} a abandonné"
            MoveType.TIMEOUT -> "${mover.nickname} n'a pas joué à temps"
        }
        if (state.status == GameStatus.ACTIVE) {
            val next = game.seats[state.currentPlayer]
            return if (next.bot != null) emptyList() else listOf(Notification(next.userId, "À toi de jouer", what, game.id))
        }
        return game.seats.indices.mapNotNull { seat ->
            val player = game.seats[seat]
            if (player.bot != null) return@mapNotNull null
            if (seat == record.seat && record.type != MoveType.TIMEOUT) return@mapNotNull null
            val opponent = game.opponents(seat)
            val score = if (game.seats.size == 2) {
                "${state.players[seat].score} – ${state.players[1 - seat].score}"
            } else {
                game.seats.indices.sortedByDescending { state.players[it].score }
                    .joinToString(", ") { "${game.seats[it].nickname} ${state.players[it].score}" }
            }
            val result = when (state.winner) {
                seat -> "Victoire contre $opponent"
                null -> "Égalité avec $opponent"
                else -> "Défaite contre $opponent"
            }
            val title = if (record.type == MoveType.TIMEOUT && seat == record.seat) "Temps écoulé" else "Partie terminée"
            Notification(player.userId, title, "$result, $score. $what.", game.id)
        }
    }

    private fun Connection.insertGame(players: List<User>, language: String): String {
        val id = UUID.randomUUID().toString()
        val seats = players.shuffled(random)
        val state = Game.new(RuleSet.defaultFor(language), seats.size, random)
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
        ) { it.getString(1) to it.getString(2) }
            // The same robot twice: "NonoBot 1" and "NonoBot 2".
            .let { rows -> rows.mapIndexed { i, (userId, nickname) ->
                val twins = rows.filter { it.first == userId }
                Seat(userId, if (twins.size > 1) "$nickname ${rows.take(i + 1).count { it.first == userId }}" else nickname)
            } }
        return StoredGame(id, ApiJson.decodeFromString(GameState.serializer(), row.first), row.second, seats)
    }

    private fun StoredGame.seatOf(user: User): Int = seats.indexOfFirst { it.userId == user.id }
        .takeIf { it >= 0 } ?: throw ApiException(HttpStatusCode.NotFound, "UNKNOWN_GAME", "Partie introuvable")

    private fun Connection.view(game: StoredGame, user: User): GameView {
        val seat = game.seatOf(user)
        val state = game.state
        val moves = query("SELECT move_number, record, played_at FROM moves WHERE game_id = ? ORDER BY move_number", game.id) {
            val r = ApiJson.decodeFromString(MoveRecord.serializer(), it.getString(2))
            // The opponent's racks stay secret while the game is on.
            val rack = r.rack?.takeIf { r.seat == seat || state.status == GameStatus.FINISHED }
            MoveView(it.getInt(1), r.seat, r.type, r.score, r.words, r.tiles, r.exchangedCount, it.getLong(3), r.endAdjustments, r.end, rack)
        }
        return GameView(
            id = game.id,
            rulesId = state.rulesId,
            board = state.board,
            rack = state.players[seat].rack,
            bagCount = state.bag.length,
            players = state.players.mapIndexed { i, p ->
                PlayerView(game.seats[i].nickname, p.score, p.rack.length, p.eliminated, game.seats[i].bot != null)
            },
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
