package app.wordgame.server

import app.wordgame.engine.Action
import app.wordgame.engine.GameStatus
import app.wordgame.engine.Placement
import app.wordgame.engine.RuleSet
import app.wordgame.engine.WordList
import app.wordgame.protocol.ApiError
import app.wordgame.protocol.ApiJson
import app.wordgame.protocol.AuthResponse
import app.wordgame.protocol.CreateGameRequest
import app.wordgame.protocol.GameSummary
import app.wordgame.protocol.GameView
import app.wordgame.protocol.MatchmakingResponse
import app.wordgame.protocol.MoveRequest
import app.wordgame.protocol.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApiTest {
    /** Accepts any word of 2+ letters except "ZZZ", so tests do not depend on random racks. */
    private val words = WordList { it.length >= 2 && it != "ZZZ" }

    private fun withApp(block: suspend ApplicationTestBuilder.(HttpClient, GameService) -> Unit) {
        val dbFile = File.createTempFile("wordgame", ".db").apply { deleteOnExit() }
        val service = GameService(Database(dbFile.path), words)
        testApplication {
            application { wordgame(service) }
            val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
            block(client, service)
        }
    }

    private suspend fun HttpClient.register(nickname: String): AuthResponse =
        post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(nickname))
        }.body()

    private suspend fun HttpClient.play(token: String, game: GameView, action: Action) =
        post("/api/v1/games/${game.id}/moves") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(MoveRequest(game.moveNumber, action))
        }

    @Test
    fun fullTurnCycle() = withApp { client, _ ->
        val alice = client.register("alice")
        val bob = client.register("bob")

        val created: GameView = client.post("/api/v1/games") {
            bearerAuth(alice.token)
            contentType(ContentType.Application.Json)
            setBody(CreateGameRequest("BOB"))
        }.body()
        assertEquals(7, created.rack.length)
        assertEquals(RuleSet.DEFAULT.fullBag().length - 14, created.bagCount)

        // Whoever got seat 0 starts.
        val (starter, other) = if (created.mySeat == created.currentSeat) alice to bob else bob to alice
        val game: GameView = client.get("/api/v1/games/${created.id}") { bearerAuth(starter.token) }.body()

        // The opponent cannot play out of turn.
        assertEquals(HttpStatusCode.UnprocessableEntity, client.play(other.token, game, Action.Pass).status)

        // Play the first two non-blank tiles of the rack on the center.
        val letters = game.rack.filter { it != '?' }.take(2)
        val tiles = letters.mapIndexed { i, ch -> Placement(7, 7 + i, ch) }
        val response = client.play(starter.token, game, Action.Play(tiles))
        assertEquals(HttpStatusCode.OK, response.status)
        val after: GameView = response.body()
        assertEquals(1, after.moveNumber)
        assertEquals(7, after.rack.length)
        assertTrue(after.players[after.mySeat].score > 0)
        assertEquals(letters[0], after.board[7][7])

        // Replaying with the old move number is rejected.
        assertEquals(HttpStatusCode.Conflict, client.play(starter.token, game, Action.Pass).status)

        val list: List<GameSummary> = client.get("/api/v1/games") { bearerAuth(other.token) }.body()
        assertTrue(list.single().myTurn)
    }

    @Test
    fun deleteAllowedOnlyUntilBothPlayersHavePlayed() = withApp { client, _ ->
        val alice = client.register("alice")
        val bob = client.register("bob")
        suspend fun newGame(): GameView = client.post("/api/v1/games") {
            bearerAuth(alice.token)
            contentType(ContentType.Application.Json)
            setBody(CreateGameRequest("bob"))
        }.body()

        // Nobody played yet: the challenged player can delete it.
        val ignored = newGame()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/games/${ignored.id}") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/games/${ignored.id}") { bearerAuth(alice.token) }.status)

        // Both players have played: deletion is refused.
        val game = newGame()
        // Seats as seen by alice, the creator.
        val tokenBySeat = mapOf(game.mySeat to alice.token, 1 - game.mySeat to bob.token)
        repeat(2) {
            val current: GameView = client.get("/api/v1/games/${game.id}") { bearerAuth(alice.token) }.body()
            val token = tokenBySeat.getValue(current.currentSeat)
            val view: GameView = client.get("/api/v1/games/${game.id}") { bearerAuth(token) }.body()
            assertEquals(HttpStatusCode.OK, client.play(token, view, Action.Pass).status)
        }
        val refused = client.delete("/api/v1/games/${game.id}") { bearerAuth(alice.token) }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("GAME_STARTED", refused.body<ApiError>().code)
    }

    @Test
    fun hidesOpponentRack() = withApp { client, _ ->
        val alice = client.register("alice")
        client.register("bob")
        val game: GameView = client.post("/api/v1/games") {
            bearerAuth(alice.token)
            contentType(ContentType.Application.Json)
            setBody(CreateGameRequest("bob"))
        }.body()
        val json = ApiJson.encodeToString(GameView.serializer(), game)
        assertTrue("\"bag\"" !in json)
        assertEquals(listOf(7, 7), game.players.map { it.rackCount })
    }

    @Test
    fun matchmakingPairsTwoPlayers() = withApp { client, _ ->
        val alice = client.register("alice")
        val bob = client.register("bob")
        val first: MatchmakingResponse = client.post("/api/v1/matchmaking") { bearerAuth(alice.token) }.body()
        assertEquals(null, first.gameId)
        val second: MatchmakingResponse = client.post("/api/v1/matchmaking") { bearerAuth(bob.token) }.body()
        assertNotNull(second.gameId)
    }

    @Test
    fun rejectsDuplicateNicknameAndMissingToken() = withApp { client, _ ->
        client.register("alice")
        val dup = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("ALICE"))
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertEquals("NICKNAME_TAKEN", dup.body<ApiError>().code)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/games").status)
    }

    @Test
    fun timeoutMakesTheCurrentPlayerLose() {
        var clock = 0L
        val dbFile = File.createTempFile("wordgame", ".db").apply { deleteOnExit() }
        testApplication {
            val service = GameService(Database(dbFile.path), words, now = { clock })
            application { wordgame(service) }
            val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
            val alice = client.register("alice")
            client.register("bob")
            val game: GameView = client.post("/api/v1/games") {
                bearerAuth(alice.token)
                contentType(ContentType.Application.Json)
                setBody(CreateGameRequest("bob"))
            }.body()

            clock = game.deadline + 1
            assertEquals(1, service.expireTimeouts())
            val after: GameView = client.get("/api/v1/games/${game.id}") { bearerAuth(alice.token) }.body()
            assertEquals(GameStatus.FINISHED, after.status)
            assertEquals(1 - game.currentSeat, after.winnerSeat)
        }
    }
}
