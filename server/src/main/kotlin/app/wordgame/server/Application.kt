package app.wordgame.server

import app.wordgame.engine.Dawg
import app.wordgame.protocol.ApiError
import app.wordgame.protocol.ApiJson
import app.wordgame.protocol.CreateGameRequest
import app.wordgame.protocol.DeviceRequest
import app.wordgame.protocol.Me
import app.wordgame.protocol.MoveRequest
import app.wordgame.protocol.RegisterRequest
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("wordgame")

fun main() {
    val env = System.getenv()
    val dbPath = env["WORDGAME_DB"] ?: "data/wordgame.db"
    val dictPath = env["WORDGAME_DICT"] ?: "dictionaries/bin/fr-libre.dawg"
    File(dbPath).absoluteFile.parentFile.mkdirs()

    val dictionary = Dawg.fromBytes(File(dictPath).readBytes())
    val db = Database(dbPath)
    // Push notifications are optional: without credentials the game works, players just are not notified.
    val notifier = env["WORDGAME_FCM_CREDENTIALS"]?.let(::File)?.takeIf { it.exists() }
        ?.let { FcmNotifier(it, db) } ?: NoNotifier.also { log.info("Push notifications disabled (no WORDGAME_FCM_CREDENTIALS)") }
    val service = GameService(db, dictionary, notifier = notifier)
    log.info("Dictionary {}: {} edges, database {}", dictPath, dictionary.edgeCount, dbPath)

    embeddedServer(
        CIO,
        port = env["WORDGAME_PORT"]?.toInt() ?: 8080,
        host = env["WORDGAME_HOST"] ?: "0.0.0.0",
    ) {
        wordgame(service)
        launch {
            while (isActive) {
                delay(5.minutes)
                runCatching { service.expireTimeouts() }
                    .onSuccess { if (it > 0) log.info("{} games lost by timeout", it) }
                    .onFailure { log.error("Timeout job failed", it) }
            }
        }
    }.start(wait = true)
}

fun Application.wordgame(service: GameService) {
    install(ContentNegotiation) { json(ApiJson) }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path() != "/health" }
    }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            call.respond(e.status, ApiError(e.code, e.message ?: e.code, e.unknownWords))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("BAD_REQUEST", e.message ?: "Bad request"))
        }
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL", "Erreur serveur"))
        }
    }
    install(Authentication) {
        bearer("auth") { authenticate { service.authenticate(it.token) } }
    }

    routing {
        get("/health") { call.respondText("ok") }

        route("/api/v1") {
            post("/auth/register") {
                call.respond(service.register(call.receive<RegisterRequest>().nickname))
            }

            authenticate("auth") {
                get("/me") {
                    val user = call.user()
                    call.respond(Me(user.id, user.nickname))
                }
                get("/games") { call.respond(service.listGames(call.user())) }
                get("/me/stats") { call.respond(service.stats(call.user())) }
                post("/devices") {
                    service.registerDevice(call.user(), call.receive<DeviceRequest>().token)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/games") {
                    call.respond(service.createGame(call.user(), call.receive<CreateGameRequest>().opponentNickname))
                }
                get("/games/{id}") { call.respond(service.view(call.user(), call.parameters["id"]!!)) }
                delete("/games/{id}") {
                    service.deleteGame(call.user(), call.parameters["id"]!!)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/games/{id}/moves") {
                    call.respond(service.move(call.user(), call.parameters["id"]!!, call.receive<MoveRequest>()))
                }
                post("/matchmaking") { call.respond(service.matchmake(call.user())) }
                delete("/matchmaking") {
                    service.leaveMatchmaking(call.user())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun ApplicationCall.user(): User = principal<User>()!!
