package app.wordgame.server

import app.wordgame.engine.Action
import app.wordgame.engine.Board
import app.wordgame.engine.Game
import app.wordgame.engine.GameEnd
import app.wordgame.engine.GameState
import app.wordgame.engine.GameStatus
import app.wordgame.engine.Lexicon
import app.wordgame.engine.MoveOutcome
import app.wordgame.engine.MoveRecord
import app.wordgame.engine.MoveType
import app.wordgame.engine.RuleSet
import app.wordgame.engine.Solver
import app.wordgame.engine.WordList
import app.wordgame.protocol.ApiJson
import app.wordgame.protocol.AuthResponse
import app.wordgame.protocol.BestMove
import app.wordgame.protocol.BlockedPlayer
import app.wordgame.protocol.ReportRequest
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
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import java.util.UUID
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * [google]: linked to a Google account (only filled in for the signed-in user). [lang]: the language of the
 * request, for the signed-in user (names and messages of the answer).
 */
data class User(val id: String, val nickname: String, val tag: Int = 0, val google: Boolean = false, val lang: Lang = Lang.FR)

/** [text]: the message for the player, in their language (see [Lang]). */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    val text: Text,
    val unknownWords: List<String> = emptyList(),
) : Exception(text.of(Lang.FR)) {
    /** A message that reads the same in every language (a code). */
    constructor(status: HttpStatusCode, code: String, message: String, unknownWords: List<String> = emptyList()) :
        this(status, code, Text { message }, unknownWords)
}

class GameService(
    private val db: Database,
    /** Dictionary of each language games can be played in ("fr", "en"). */
    private val dictionaries: Map<String, WordList>,
    private val turnTimeout: Duration = 72.hours,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom().asKotlinRandom(),
    private val notifier: Notifier = NoNotifier,
    private val bots: List<ServerBot> = emptyList(),
    /** Checks "Sign in with Google" tokens; null: Google sign-in is off. */
    private val google: GoogleTokens? = null,
    /** Dictionaries by language for the best move of each turn (the stats' share of possible points); none: no share. */
    private val lexicons: Map<String, Lexicon> = emptyMap(),
) {
    private val botsById = bots.associateBy { it.userId }

    private fun words(state: GameState) = dictionaries.getValue(state.language)

    private fun checkLanguage(language: String) {
        if (language !in dictionaries) throw ApiException(HttpStatusCode.BadRequest, "UNKNOWN_LANGUAGE", tr("Langue non disponible : $language", "Language not available: $language"))
    }

    /** [deleted]: the player deleted their account, shown under a name in the reader's language. */
    private inner class Seat(val userId: String, val nickname: String, val deleted: Boolean = false) {
        val bot get() = botsById[userId]
        fun name(lang: Lang) = if (deleted) deletedPlayerName(lang) else nickname
    }

    private inner class StoredGame(val id: String, val state: GameState, val deadline: Long, val seats: List<Seat>) {
        /** The other players' nicknames and best score, from [seat]'s side. */
        fun opponents(seat: Int, lang: Lang) = seats.indices.filter { it != seat }.joinToString(", ") { seats[it].name(lang) }
        fun bestOpponentScore(seat: Int) = state.players.indices.filter { it != seat }.maxOf { state.players[it].score }
    }

    /**
     * A new account; the nickname may be taken, the tag tells the players apart. With [googleIdToken], the
     * account is linked to that Google account at once (signing in with Google found no account).
     */
    suspend fun register(nickname: String, googleIdToken: String? = null): AuthResponse {
        if (!NICKNAME.matches(nickname)) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_NICKNAME", tr("3 à 16 caractères : lettres, chiffres, _ ou -", "3 to 16 characters: letters, digits, _ or -"))
        }
        Nicknames.refusal(nickname)?.let { throw ApiException(HttpStatusCode.BadRequest, "NICKNAME_REFUSED", it) }
        val google = googleIdToken?.let { googleSubject(it) }
        return db.tx {
            if (google != null) checkGoogleFree(google, null)
            // Released by [releaseAccount]: the first to register it again gets the account back, games and stats.
            val released = query("SELECT id, nickname, tag FROM users WHERE nickname = ? AND token_hash = ? || id", nickname, RELEASED) {
                User(it.getString(1), it.getString(2), it.getInt(3))
            }.firstOrNull()
            val user = if (released != null) {
                update("UPDATE users SET token_hash = ? || id WHERE id = ?", ACCOUNT, released.id)
                released
            } else {
                val taken = query("SELECT tag FROM users WHERE nickname = ? AND tag > 0", nickname) { it.getInt(1) }.toSet()
                if (taken.size > 8000) throw ApiException(HttpStatusCode.Conflict, "NICKNAME_TAKEN", tr("Pseudo trop répandu, choisis-en un autre", "This nickname is too common, choose another one"))
                val tag = generateSequence { Tags.random(random) }.first { it !in taken }
                val id = UUID.randomUUID().toString()
                update(
                    "INSERT INTO users(id, nickname, token_hash, created_at, tag) VALUES (?, ?, ? || ?, ?, ?)",
                    id, nickname, ACCOUNT, id, now(), tag,
                )
                User(id, nickname, tag)
            }
            if (google != null) update("UPDATE users SET google_sub = ? WHERE id = ?", google, user.id)
            newSession(user)
        }
    }

    /** Signs in on a new phone (or after reinstalling) with the Google account linked to an account. */
    suspend fun signInWithGoogle(idToken: String): AuthResponse {
        val google = googleSubject(idToken)
        return db.tx {
            val user = query("SELECT id, nickname, tag FROM users WHERE google_sub = ?", google) {
                User(it.getString(1), it.getString(2), it.getInt(3))
            }.firstOrNull() ?: throw ApiException(HttpStatusCode.NotFound, "NO_ACCOUNT", tr("Aucun compte n'est associé à ce compte Google", "No account is linked to this Google account"))
            newSession(user)
        }
    }

    /** Links [user]'s account to a Google account, to find it again on another phone. */
    suspend fun linkGoogle(user: User, idToken: String) {
        val google = googleSubject(idToken)
        db.tx {
            checkGoogleFree(google, user)
            update("UPDATE users SET google_sub = ? WHERE id = ?", google, user.id)
        }
    }

    private fun googleSubject(idToken: String): String {
        val tokens = google ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "GOOGLE_DISABLED", tr("Connexion Google indisponible", "Google sign-in is unavailable"))
        return tokens.subject(idToken) ?: throw ApiException(HttpStatusCode.Unauthorized, "INVALID_GOOGLE_TOKEN", tr("Connexion Google refusée", "Google sign-in refused"))
    }

    /** A Google account belongs to one account at most. */
    private fun Connection.checkGoogleFree(google: String, user: User?) {
        val owner = query("SELECT id, nickname, tag FROM users WHERE google_sub = ?", google) {
            User(it.getString(1), it.getString(2), it.getInt(3))
        }.firstOrNull()
        if (owner != null && owner.id != user?.id) {
            throw ApiException(
                HttpStatusCode.Conflict, "GOOGLE_ALREADY_LINKED",
                tr("Ce compte Google est déjà associé à ${owner.nickname}#${owner.tag}", "This Google account is already linked to ${owner.nickname}#${owner.tag}"),
            )
        }
    }

    suspend fun signOut(token: String) = db.tx {
        update("DELETE FROM sessions WHERE token_hash = ?", sha256(token))
    }

    private fun Connection.newSession(user: User): AuthResponse {
        val token = randomToken()
        update("INSERT INTO sessions(token_hash, user_id, created_at) VALUES (?, ?, ?)", sha256(token), user.id, now())
        return AuthResponse(user.id, user.nickname, token, user.tag)
    }

    /**
     * For a player who lost their token (app deleted, new phone): the next registration of [nickname] takes the
     * account over, and the old tokens stop working. On the server, the same as:
     * `sqlite3 /var/lib/wordgame/wordgame.db "UPDATE users SET token_hash = 'released:' || id WHERE nickname = '…' AND tag = …;
     *  DELETE FROM sessions WHERE user_id IN (SELECT id FROM users WHERE token_hash LIKE 'released:%')"`
     */
    suspend fun releaseAccount(nickname: String, tag: Int): Boolean = db.tx {
        val released = update("UPDATE users SET token_hash = ? || id WHERE nickname = ? AND tag = ? AND tag > 0", RELEASED, nickname, tag) > 0
        update("DELETE FROM sessions WHERE user_id IN (SELECT id FROM users WHERE token_hash = ? || id)", RELEASED)
        released
    }

    /**
     * Deletes the account of [user]: their games against robots only disappear; in the others they resign (the
     * game goes on, or ends, for the other players) and appear as "Joueur supprimé". Phones, matchmaking and hints
     * are forgotten, and the token stops working.
     */
    suspend fun deleteAccount(user: User) = db.tx {
        val notifications = ArrayList<Notification>()
        val gameIds = query("SELECT DISTINCT game_id FROM game_players WHERE user_id = ?", user.id) { it.getString(1) }
        for (id in gameIds) {
            val game = load(id)
            if (game.seats.none { it.userId != user.id && it.bot == null }) {
                deleteGameRows(id)
                continue
            }
            if (game.state.status != GameStatus.ACTIVE) continue
            val outcome = Game.resign(game.state, game.seatOf(user), MoveType.RESIGN, random)
            if (outcome is MoveOutcome.Applied) {
                save(id, outcome.state, outcome.record)
                notifications += afterMove(game, outcome.state, outcome.record) + playBots(load(id))
            }
        }
        update("DELETE FROM devices WHERE user_id = ?", user.id)
        update("DELETE FROM sessions WHERE user_id = ?", user.id)
        update("DELETE FROM matchmaking WHERE user_id = ?", user.id)
        update("DELETE FROM hints WHERE user_id = ?", user.id)
        update("DELETE FROM blocks WHERE user_id = ? OR blocked_id = ?", user.id, user.id)
        update("DELETE FROM reports WHERE reporter_id = ? OR reported_id = ?", user.id, user.id)
        val stillInGames = query("SELECT 1 FROM game_players WHERE user_id = ? LIMIT 1", user.id) { true }.isNotEmpty()
        if (stillInGames) {
            update(
                "UPDATE users SET nickname = ?, tag = 0, google_sub = NULL, token_hash = ? || id WHERE id = ?",
                DELETED_NICKNAME, DELETED, user.id,
            )
        } else {
            update("DELETE FROM users WHERE id = ?", user.id)
        }
        notifications.filter { it.userId != user.id }
    }.let { notifier.send(it) }

    suspend fun authenticate(token: String): User? = db.tx {
        query(
            "SELECT u.id, u.nickname, u.tag, u.google_sub IS NOT NULL FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?",
            sha256(token),
        ) { User(it.getString(1), it.getString(2), it.getInt(3), it.getBoolean(4)) }.firstOrNull()
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
            val (createdAt, updatedAt) = query("SELECT created_at, updated_at FROM games WHERE id = ?", id) { it.getLong(1) to it.getLong(2) }.single()
            GameSummary(
                id = id,
                opponent = game.opponents(seat, user.lang),
                myScore = game.state.players[seat].score,
                opponentScore = game.bestOpponentScore(seat),
                myTurn = game.state.status == GameStatus.ACTIVE && game.state.currentPlayer == seat,
                status = game.state.status,
                won = game.state.winner?.let { it == seat },
                updatedAt = updatedAt,
                createdAt = createdAt,
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
        var scored = 0 // points scored on the turns whose best score is known, and that best
        var possible = 0
        var active = 0
        var hintTurns = 0
        for (id in ids) {
            val game = load(id)
            if (language != null && game.state.language != language) continue
            val seat = game.seatOf(user)
            val opponent = game.opponents(seat, user.lang)
            val state = game.state
            var gameScored = 0
            var gamePossible = 0
            query("SELECT record, played_at, best_score FROM moves WHERE game_id = ?", id) {
                Triple(ApiJson.decodeFromString(MoveRecord.serializer(), it.getString(1)), it.getLong(2), (it.getObject(3) as? Number)?.toInt() ?: -1)
            }.filter { it.first.seat == seat }.forEach { (record, at, best) ->
                myMoves += record to (opponent to at)
                if (best >= 0) {
                    gameScored += record.score
                    gamePossible += best
                }
            }
            val hints = query("SELECT COUNT(*) FROM hints WHERE game_id = ? AND user_id = ?", id, user.id) { it.getInt(1) }.single()
            hintTurns += hints
            if (state.status != GameStatus.FINISHED) {
                active++
                continue
            }
            scored += gameScored
            possible += gamePossible
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
                share = share(gameScored, gamePossible),
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
            share = share(scored, possible),
        )
    }

    /** Share of the possible points actually scored, at most 1 (a turn can't beat the best move). */
    private fun share(scored: Int, possible: Int) = if (possible > 0) minOf(1.0, scored.toDouble() / possible) else null

    /** A game with friends (each at most once) and robots (the same one possibly twice): 2 to 4 players with the caller. */
    suspend fun createGame(user: User, request: CreateGameRequest): GameView = db.tx {
        checkLanguage(request.language)
        val names = request.friends.ifEmpty { listOfNotNull(request.opponentNickname) }
        if (names.isEmpty() && request.bots.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "NO_OPPONENT", tr("Choisis au moins un adversaire", "Choose at least one opponent"))
        if (names.size + request.bots.size > 3) throw ApiException(HttpStatusCode.BadRequest, "TOO_MANY_PLAYERS", tr("4 joueurs au plus", "4 players at most"))
        val friends = names.map { name ->
            findPlayer(name).also { friend ->
                if (blocked(user.id, friend.id)) {
                    throw ApiException(HttpStatusCode.Forbidden, "PLAYER_BLOCKED", tr("Tu as bloqué ${friend.nickname} : débloque ce joueur dans Mon compte", "You blocked ${friend.nickname}: unblock them in My account"))
                }
                // Blocked by them: they do not exist for the caller.
                if (blocked(friend.id, user.id)) throw ApiException(HttpStatusCode.NotFound, "UNKNOWN_PLAYER", tr("Joueur introuvable : $name", "Player not found: $name"))
            }
        }
        if (friends.any { it.id == user.id }) throw ApiException(HttpStatusCode.BadRequest, "SELF_GAME", tr("Impossible de jouer contre soi-même", "You can't play against yourself"))
        if (friends.map { it.id }.toSet().size < friends.size) {
            throw ApiException(HttpStatusCode.BadRequest, "DUPLICATE_PLAYER", tr("Chaque joueur ne peut être choisi qu'une fois", "Each player can be chosen only once"))
        }
        val robots = request.bots.map { level ->
            val bot = bots.firstOrNull { it.level == level }
                ?: throw ApiException(HttpStatusCode.BadRequest, "UNKNOWN_BOT", tr("Robot inconnu : $level", "Unknown robot: $level"))
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
        val others = (friends + robots).joinToString { it.nickname }.takeIf { friends.size + robots.size > 1 }
        val invitations = friends.map {
            challenge(game, it, tr("Nouveau défi", "New challenge"), Text { l ->
                l.t("${user.nickname} te défie", "${user.nickname} challenges you") + (others?.let { o -> l.t(" (avec $o)", " (with $o)") } ?: "")
            })
        }
        view(game, user) to invitations + botMoves.filter { n -> n.userId != user.id && friends.none { it.id == n.userId } }
    }.let { (view, notifications) -> notifier.send(notifications); view }

    /** Pairs with the oldest player waiting for a game in the same [language], or queues the caller. */
    suspend fun matchmake(user: User, language: String = "fr"): MatchmakingResponse = db.tx {
        checkLanguage(language)
        checkActiveGameLimit(user)
        val waiting = query(
            """SELECT u.id, u.nickname FROM matchmaking m JOIN users u ON u.id = m.user_id
               WHERE m.user_id <> ? AND m.language = ?
                 AND NOT EXISTS (SELECT 1 FROM blocks b WHERE (b.user_id = m.user_id AND b.blocked_id = ?)
                                                         OR (b.user_id = ? AND b.blocked_id = m.user_id))
               ORDER BY m.queued_at LIMIT 1""",
            user.id, language, user.id, user.id,
        ) { User(it.getString(1), it.getString(2)) }.firstOrNull()
        if (waiting == null) {
            // Waiting for one language at a time: the latest request wins.
            update("INSERT OR REPLACE INTO matchmaking(user_id, queued_at, language) VALUES (?, ?, ?)", user.id, now(), language)
            MatchmakingResponse(null)
        } else {
            update("DELETE FROM matchmaking WHERE user_id IN (?, ?)", waiting.id, user.id)
            val id = insertGame(listOf(user, waiting), language)
            notifier.send(listOf(challenge(load(id), waiting, tr("Adversaire trouvé", "Opponent found"), tr("Partie contre ${user.nickname}", "Game against ${user.nickname}"))))
            MatchmakingResponse(id)
        }
    }

    suspend fun leaveMatchmaking(user: User) = db.tx {
        update("DELETE FROM matchmaking WHERE user_id = ?", user.id)
    }

    /** [blocker] blocked [other]. */
    private fun Connection.blocked(blocker: String, other: String) =
        query("SELECT 1 FROM blocks WHERE user_id = ? AND blocked_id = ?", blocker, other) { true }.isNotEmpty()

    /** The other human at [seat] of a game of [user]'s: the one players block or report, from the game screen. */
    private fun Connection.playerAt(user: User, gameId: String, seat: Int): User {
        val game = load(gameId)
        val mine = game.seatOf(user)
        val other = game.seats.getOrNull(seat)
        if (other == null || seat == mine || other.userId == user.id || other.bot != null) {
            throw ApiException(HttpStatusCode.BadRequest, "INVALID_PLAYER", tr("Ce joueur ne peut pas être signalé ni bloqué", "This player can't be reported or blocked"))
        }
        return query("SELECT id, nickname, tag, token_hash FROM users WHERE id = ?", other.userId) {
            User(it.getString(1), it.getString(2), it.getInt(3)) to it.getString(4)
        }.single().let { (player, state) ->
            if (state.startsWith(DELETED)) throw ApiException(HttpStatusCode.Gone, "PLAYER_DELETED", tr("Ce joueur a supprimé son compte", "This player deleted their account"))
            player
        }
    }

    /**
     * [user] blocks the player at [seat] of a game: neither can challenge the other any more, and matchmaking no
     * longer pairs them. Games already started go on (either may resign).
     */
    suspend fun block(user: User, gameId: String, seat: Int) = db.tx {
        val player = playerAt(user, gameId, seat)
        update("INSERT OR IGNORE INTO blocks(user_id, blocked_id, created_at) VALUES (?, ?, ?)", user.id, player.id, now())
    }

    /** [user] reports the player at [seat] of a game, for the moderator (and blocks them too if asked). */
    suspend fun report(user: User, gameId: String, seat: Int, request: ReportRequest) = db.tx {
        val player = playerAt(user, gameId, seat)
        val details = request.details?.trim()?.take(MAX_REPORT_DETAILS)?.takeIf { it.isNotEmpty() }
        update(
            "INSERT INTO reports(reporter_id, reported_id, reported_nickname, game_id, reason, details, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            user.id, player.id, "${player.nickname}#${player.tag}", gameId, request.reason.name, details, now(),
        )
        if (request.block) update("INSERT OR IGNORE INTO blocks(user_id, blocked_id, created_at) VALUES (?, ?, ?)", user.id, player.id, now())
        // The moderator reads them in the server log (journalctl -u wordgame | grep Report) then in the reports table.
        log.warn("Report: {}#{} by {}, reason {}", player.nickname, player.tag, user.id, request.reason)
    }

    suspend fun blockedPlayers(user: User): List<BlockedPlayer> = db.tx {
        query(
            "SELECT u.id, u.nickname, u.tag FROM blocks b JOIN users u ON u.id = b.blocked_id WHERE b.user_id = ? ORDER BY u.nickname",
            user.id,
        ) { BlockedPlayer(it.getString(1), if (it.getInt(3) > 0) "${it.getString(2)}#${it.getInt(3)}" else it.getString(2)) }
    }

    suspend fun unblock(user: User, blockedId: String) = db.tx {
        update("DELETE FROM blocks WHERE user_id = ? AND blocked_id = ?", user.id, blockedId)
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
            throw ApiException(HttpStatusCode.Conflict, "GAME_STARTED", tr("Tous les joueurs ont déjà joué", "Every player has already played"))
        }
        deleteGameRows(gameId)
    }

    private fun Connection.deleteGameRows(gameId: String) {
        update("DELETE FROM moves WHERE game_id = ?", gameId)
        update("DELETE FROM hints WHERE game_id = ?", gameId)
        update("DELETE FROM game_players WHERE game_id = ?", gameId)
        update("DELETE FROM games WHERE id = ?", gameId)
    }

    /** A friend by "greg#4821", or by "greg" alone when only one player has that nickname. */
    private fun Connection.findPlayer(name: String): User {
        val (nickname, tag) = Tags.parse(name)
        val matches = query(
            "SELECT id, nickname, tag FROM users WHERE nickname = ? AND tag > 0" + (if (tag != null) " AND tag = ?" else ""),
            *listOfNotNull<Any>(nickname, tag).toTypedArray(),
        ) { User(it.getString(1), it.getString(2), it.getInt(3)) }
        return when (matches.size) {
            0 -> throw ApiException(HttpStatusCode.NotFound, "UNKNOWN_PLAYER", tr("Joueur introuvable : $name", "Player not found: $name"))
            1 -> matches.single()
            else -> throw ApiException(
                HttpStatusCode.Conflict, "AMBIGUOUS_PLAYER",
                tr("Plusieurs joueurs s'appellent $nickname : ajoute son numéro (ex. $nickname#1234), visible dans son menu Mon compte", "Several players are called $nickname: add their number (e.g. $nickname#1234), shown in their My account menu"),
            )
        }
    }

    suspend fun view(user: User, gameId: String): GameView = db.tx { view(load(gameId), user) }

    /** The player asked for hints on their turn: counted once per turn. Only against robots, as the app allows. */
    suspend fun recordHint(user: User, gameId: String) = db.tx {
        val game = load(gameId)
        val seat = game.seatOf(user)
        if (game.seats.indices.any { it != seat && game.seats[it].bot == null }) {
            throw ApiException(HttpStatusCode.Forbidden, "HINTS_AGAINST_PEOPLE", tr("Pas d'indices contre d'autres joueurs", "No hints against other players"))
        }
        if (game.state.status != GameStatus.ACTIVE || game.state.currentPlayer != seat) {
            throw ApiException(HttpStatusCode.Conflict, "NOT_YOUR_TURN", tr("Ce n'est pas ton tour", "It's not your turn"))
        }
        update("INSERT OR IGNORE INTO hints (game_id, user_id, move_number) VALUES (?, ?, ?)", gameId, user.id, game.state.moveNumber)
    }

    suspend fun move(user: User, gameId: String, request: MoveRequest): GameView = db.tx {
        val game = load(gameId)
        val seat = game.seatOf(user)
        if (request.expectedMoveNumber != game.state.moveNumber) {
            throw ApiException(HttpStatusCode.Conflict, "STALE_GAME", tr("La partie a changé, rechargez-la", "The game has changed, reload it"))
        }
        val notifications = when (val outcome = Game.apply(game.state, seat, request.action, words(game.state), random)) {
            is MoveOutcome.Rejected -> throw ApiException(
                HttpStatusCode.UnprocessableEntity, outcome.error.name, outcome.error.name, outcome.unknownWords,
            )
            is MoveOutcome.Applied -> {
                save(game.id, outcome.state, outcome.record, bestScore(game.state.rules, game.state.boardModel(), outcome.record))
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
        if (token.isBlank() || token.length > 4096) throw ApiException(HttpStatusCode.BadRequest, "INVALID_TOKEN", tr("Jeton invalide", "Invalid token"))
        db.tx {
            update(
                "INSERT INTO devices(token, user_id, updated_at, lang) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(token) DO UPDATE SET user_id = excluded.user_id, updated_at = excluded.updated_at, lang = excluded.lang",
                token, user.id, now(), user.lang.code,
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

    /** The language of [userId]'s notifications: that of their latest phone. */
    private fun Connection.langOf(userId: String): Lang =
        Lang.of(query("SELECT lang FROM devices WHERE user_id = ? ORDER BY updated_at DESC LIMIT 1", userId) { it.getString(1) }.firstOrNull())

    private fun Connection.challenge(game: StoredGame, to: User, title: Text, body: Text): Notification {
        val lang = langOf(to.id)
        val theirTurn = game.seats[game.state.currentPlayer].userId == to.id
        val text = body.of(lang) + if (theirTurn) lang.t(". À toi de jouer !", ". Your turn!") else "."
        return Notification(to.id, title.of(lang), text, game.id)
    }

    /**
     * Who to tell what after a move: the next player, or everybody else when the game is over. Nobody when a
     * single human plays against robots: they answer at once, there is nothing to wait for.
     */
    private fun Connection.afterMove(game: StoredGame, state: GameState, record: MoveRecord): List<Notification> {
        if (game.seats.count { it.bot == null } <= 1) return emptyList()
        val mover = game.seats[record.seat]
        fun what(l: Lang): String {
            val who = mover.name(l)
            return when (record.type) {
                MoveType.PLAY -> l.t("$who a joué ", "$who played ") + "${record.words.joinToString { it.word }} (+${record.score}" +
                    (if (record.end == GameEnd.OUT) l.t(", +${record.endAdjustments[record.seat]} de bonus de fin)", ", +${record.endAdjustments[record.seat]} end bonus)") else ")")
                MoveType.EXCHANGE -> l.t("$who a échangé ${record.exchangedCount} lettre(s)", "$who exchanged ${record.exchangedCount} tile(s)")
                MoveType.PASS -> if (record.end == GameEnd.BLOCKED) l.t("Partie bloquée", "Game blocked") else l.t("$who a passé son tour", "$who passed")
                MoveType.RESIGN -> l.t("$who a abandonné", "$who resigned")
                MoveType.TIMEOUT -> l.t("$who n'a pas joué à temps", "$who ran out of time")
            }
        }
        if (state.status == GameStatus.ACTIVE) {
            val next = game.seats[state.currentPlayer]
            if (next.bot != null) return emptyList()
            val l = langOf(next.userId)
            return listOf(Notification(next.userId, l.t("À toi de jouer", "Your turn"), what(l), game.id))
        }
        return game.seats.indices.mapNotNull { seat ->
            val player = game.seats[seat]
            if (player.bot != null) return@mapNotNull null
            if (seat == record.seat && record.type != MoveType.TIMEOUT) return@mapNotNull null
            val l = langOf(player.userId)
            val opponent = game.opponents(seat, l)
            val score = if (game.seats.size == 2) {
                "${state.players[seat].score} – ${state.players[1 - seat].score}"
            } else {
                game.seats.indices.sortedByDescending { state.players[it].score }
                    .joinToString(", ") { "${game.seats[it].name(l)} ${state.players[it].score}" }
            }
            val result = when (state.winner) {
                seat -> l.t("Victoire contre $opponent", "Win against $opponent")
                null -> l.t("Égalité avec $opponent", "Draw with $opponent")
                else -> l.t("Défaite contre $opponent", "Loss against $opponent")
            }
            val title = if (record.type == MoveType.TIMEOUT && seat == record.seat) l.t("Temps écoulé", "Time's up") else l.t("Partie terminée", "Game over")
            Notification(player.userId, title, "$result, $score. ${what(l)}.", game.id)
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

    /**
     * The most points a player's turn could score (rack [MoveRecord.rack] on [board], before the move), to compare
     * with what they scored; -1 when not known or not counted (robots, resignations, no dictionary).
     */
    private fun bestScore(rules: RuleSet, board: Board, record: MoveRecord): Int {
        if (record.type !in TURNS) return -1
        val lexicon = lexicons[rules.language] ?: return -1
        val rack = record.rack ?: return -1
        return Solver(lexicon, rules).bestMoves(board, rack, 1).firstOrNull()?.score ?: 0
    }

    /**
     * Best scores of the turns played before they were recorded (once, at startup): each game is replayed from an
     * empty board. Robots' turns get -1.
     */
    suspend fun backfillBestScores(): Int {
        if (lexicons.isEmpty()) return 0
        val gameIds = db.tx { query("SELECT DISTINCT game_id FROM moves WHERE best_score IS NULL") { it.getString(1) } }
        for (id in gameIds) db.tx {
            val game = load(id)
            val rules = game.state.rules
            var board = Board.empty(rules.boardSize)
            val moves = query("SELECT move_number, record, best_score FROM moves WHERE game_id = ? ORDER BY move_number", id) {
                Triple(it.getInt(1), ApiJson.decodeFromString(MoveRecord.serializer(), it.getString(2)), it.getObject(3))
            }
            for ((number, record, known) in moves) {
                if (known == null) {
                    val best = if (game.seats[record.seat].bot != null) -1 else bestScore(rules, board, record)
                    update("UPDATE moves SET best_score = ? WHERE game_id = ? AND move_number = ?", best, id, number)
                }
                if (record.type == MoveType.PLAY) board = board.with(record.tiles)
            }
        }
        return gameIds.size
    }

    private fun Connection.save(id: String, state: GameState, record: MoveRecord, bestScore: Int = -1) {
        val t = now()
        update(
            "UPDATE games SET state = ?, status = ?, move_number = ?, deadline = ?, updated_at = ? WHERE id = ?",
            ApiJson.encodeToString(GameState.serializer(), state), state.status.name, state.moveNumber,
            t + turnTimeout.inWholeMilliseconds, t, id,
        )
        update(
            "INSERT INTO moves(game_id, move_number, record, played_at, best_score) VALUES (?, ?, ?, ?, ?)",
            id, state.moveNumber, ApiJson.encodeToString(MoveRecord.serializer(), record), t, bestScore,
        )
    }

    private fun Connection.load(id: String): StoredGame {
        val row = query("SELECT state, deadline FROM games WHERE id = ?", id) { it.getString(1) to it.getLong(2) }.firstOrNull()
            ?: throw ApiException(HttpStatusCode.NotFound, "UNKNOWN_GAME", tr("Partie introuvable", "Game not found"))
        val seats = query(
            "SELECT u.id, u.nickname, u.tag, u.token_hash FROM game_players p JOIN users u ON u.id = p.user_id WHERE p.game_id = ? ORDER BY p.seat",
            id,
        ) { User(it.getString(1), it.getString(2), it.getInt(3)) to it.getString(4).startsWith(DELETED) }
            .let { all -> val rows = all.map { it.first }; rows.mapIndexed { i, u ->
                val twins = rows.filter { it.id == u.id }
                val namesakes = rows.filter { it.nickname.equals(u.nickname, ignoreCase = true) && it.id != u.id && it.tag > 0 }
                Seat(u.id, when {
                    // The same robot twice: "NonoBot 1" and "NonoBot 2".
                    twins.size > 1 -> "${u.nickname} ${rows.take(i + 1).count { it.id == u.id }}"
                    // Two players with the same nickname: "greg#4821" and "greg#1307".
                    namesakes.isNotEmpty() && u.tag > 0 -> "${u.nickname}#${u.tag}"
                    else -> u.nickname
                }, deleted = all[i].second)
            } }
        return StoredGame(id, ApiJson.decodeFromString(GameState.serializer(), row.first), row.second, seats)
    }

    private fun StoredGame.seatOf(user: User): Int = seats.indexOfFirst { it.userId == user.id }
        .takeIf { it >= 0 } ?: throw ApiException(HttpStatusCode.NotFound, "UNKNOWN_GAME", tr("Partie introuvable", "Game not found"))

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
                PlayerView(game.seats[i].name(user.lang), p.score, p.rack.length, p.eliminated, game.seats[i].bot != null, game.seats[i].deleted)
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
            throw ApiException(HttpStatusCode.Conflict, "TOO_MANY_GAMES", tr("${user.nickname} a déjà $MAX_ACTIVE_GAMES parties en cours", "${user.nickname} already has $MAX_ACTIVE_GAMES games in progress"))
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
        const val MAX_REPORT_DETAILS = 500
        val log = LoggerFactory.getLogger("wordgame")
        /** A player's turns, where they chose what to play: the ones compared with the best move. */
        val TURNS = setOf(MoveType.PLAY, MoveType.PASS, MoveType.EXCHANGE)
        val NICKNAME = Regex("^[A-Za-z0-9_-]{3,16}$")
        /** Prefix of the token hash of an ordinary account (its tokens are in sessions), followed by its id. */
        const val ACCOUNT = "account:"
        /** Prefix of the token hash of a released account (see [GameService.releaseAccount]), followed by its id. */
        const val RELEASED = "released:"
        /** Prefix of the token hash of a deleted account still seen in other players' games, followed by its id. */
        const val DELETED = "deleted:"
        const val DELETED_NICKNAME = "Joueur supprimé"
    }
}
