package app.wordgame.server

import app.wordgame.engine.Afterstate
import app.wordgame.engine.Dawg
import app.wordgame.engine.ExtraWords
import app.wordgame.engine.Lexicon
import app.wordgame.engine.WordList
import app.wordgame.engine.plus
import app.wordgame.protocol.ApiError
import app.wordgame.protocol.ApiJson
import app.wordgame.protocol.CreateGameRequest
import app.wordgame.protocol.DeviceRequest
import app.wordgame.protocol.GoogleRequest
import app.wordgame.protocol.Me
import app.wordgame.protocol.MoveRequest
import app.wordgame.protocol.RegisterRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlin.time.Duration.Companion.minutes

private val log = LoggerFactory.getLogger("wordgame")

fun main() {
    val env = System.getenv()
    val dbPath = env["WORDGAME_DB"] ?: "data/wordgame.db"
    val dictPath = env["WORDGAME_DICT"] ?: "dictionaries/bin/fr-libre.dawg"
    File(dbPath).absoluteFile.parentFile.mkdirs()

    val extraPath = env["WORDGAME_EXTRA_DICT"] ?: "dictionaries/private/fr-2lettres.txt"
    val dawg = Dawg.fromBytes(File(dictPath).readBytes())
    // Optional private supplement (e.g. the official 2-letter words), on top of the free dictionary.
    val extra = File(extraPath).takeIf { it.exists() }?.let { ExtraWords.parse(it.readText()) }
    val dictionary = if (extra != null) dawg + extra else dawg
    log.info("Extra words {}: {}", extraPath, extra?.words?.size ?: "absent")
    val db = Database(dbPath)
    // Push notifications are optional: without credentials the game works, players just are not notified.
    val notifier = env["WORDGAME_FCM_CREDENTIALS"]?.let(::File)?.takeIf { it.exists() }
        ?.let { FcmNotifier(it, db) } ?: NoNotifier.also { log.info("Push notifications disabled (no WORDGAME_FCM_CREDENTIALS)") }
    // English: optional, games in English are refused without it.
    val enPath = env["WORDGAME_EN_DICT"] ?: "dictionaries/bin/en-enable.dawg"
    val english = File(enPath).takeIf { it.exists() }?.let { Dawg.fromBytes(it.readBytes()) }
    log.info("English dictionary {}: {}", enPath, english?.let { "${it.edgeCount} edges" } ?: "absent")
    // Robots for games with friends: they need the everyday vocabulary and the learned weights.
    val commonPath = env["WORDGAME_COMMON_DICT"] ?: "dictionaries/bin/fr-courant.dawg"
    val enCommonPath = env["WORDGAME_EN_COMMON_DICT"] ?: "dictionaries/bin/en-courant.dawg"
    val weightsPath = env["WORDGAME_BOT_WEIGHTS"] ?: "models/value-v2.txt"
    val enWeightsPath = env["WORDGAME_EN_BOT_WEIGHTS"] ?: "models/value-en-v1.txt"
    val bots = if (File(commonPath).exists() && File(weightsPath).exists()) {
        val weights = Afterstate.parseWeights(File(weightsPath).readText())
        // Without weights learned on English games, the French ones.
        val enWeights = File(enWeightsPath).takeIf { it.exists() }?.let { Afterstate.parseWeights(it.readText()) } ?: weights
        val vocabularies = buildMap {
            put("fr", BotVocabulary(Lexicon(dawg, extra), Dawg.fromBytes(File(commonPath).readBytes()), weights))
            if (english != null && File(enCommonPath).exists()) {
                put("en", BotVocabulary(Lexicon(english), Dawg.fromBytes(File(enCommonPath).readBytes()), enWeights))
            }
        }
        serverBots(vocabularies, SecureRandom().asKotlinRandom())
    } else {
        emptyList()
    }
    log.info("Robots: {}", bots.joinToString { it.nickname }.ifEmpty { "none (missing $commonPath or $weightsPath)" })
    val dictionaries = buildMap<String, WordList> {
        put("fr", dictionary)
        english?.let { put("en", it) }
    }
    // "Sign in with Google": the Web OAuth client id of the Google Cloud project (the app's serverClientId).
    val googleClientId = env["WORDGAME_GOOGLE_CLIENT_ID"]?.takeIf { it.isNotBlank() }
    val google = googleClientId?.let(::GoogleIdTokens)
    log.info("Google sign-in: {}", if (google != null) "on" else "off (no WORDGAME_GOOGLE_CLIENT_ID)")
    // The whole dictionaries, for the best move of each turn (stats).
    val lexicons = buildMap {
        put("fr", Lexicon(dawg, extra))
        english?.let { put("en", Lexicon(it)) }
    }
    val service = GameService(db, dictionaries, notifier = notifier, bots = bots, google = google, lexicons = lexicons)
    log.info("Dictionary {}: {} edges, database {}", dictPath, dawg.edgeCount, dbPath)

    embeddedServer(
        CIO,
        port = env["WORDGAME_PORT"]?.toInt() ?: 8080,
        host = env["WORDGAME_HOST"] ?: "0.0.0.0",
    ) {
        wordgame(service, env["WORDGAME_CONTACT_EMAIL"], googleClientId)
        launch {
            runCatching { service.backfillBestScores() }
                .onSuccess { if (it > 0) log.info("Best scores computed for {} games", it) }
                .onFailure { log.error("Best scores backfill failed", it) }
        }
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

/**
 * [contactEmail]: shown on the privacy policy. [googleClientId]: for the Google button of the deletion page
 * (players whose account is linked to Google delete it there, without the app).
 */
fun Application.wordgame(service: GameService, contactEmail: String? = null, googleClientId: String? = null) {
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
        // Required by Google Play: how to delete one's account, reachable without the app.
        get("/supprimer-compte") { call.respondText(accountDeletionPage(googleClientId), ContentType.Text.Html) }
        // Required by Google Play too: the privacy policy, linked from the store listing and the app.
        get("/confidentialite") { call.respondText(privacyPage(contactEmail), ContentType.Text.Html) }

        route("/api/v1") {
            post("/auth/register") {
                val request = call.receive<RegisterRequest>()
                call.respond(service.register(request.nickname, request.googleIdToken))
            }
            post("/auth/google") { call.respond(service.signInWithGoogle(call.receive<GoogleRequest>().idToken)) }

            authenticate("auth") {
                get("/me") {
                    val user = call.user()
                    call.respond(Me(user.id, user.nickname, user.tag, user.google))
                }
                // Signs this phone (or the deletion page) out: its token stops working.
                delete("/me/session") {
                    service.signOut(call.request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ").trim())
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/me/google") {
                    service.linkGoogle(call.user(), call.receive<GoogleRequest>().idToken)
                    call.respond(HttpStatusCode.NoContent)
                }
                delete("/me") {
                    service.deleteAccount(call.user())
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/games") { call.respond(service.listGames(call.user())) }
                get("/me/stats") { call.respond(service.stats(call.user(), call.request.queryParameters["language"])) }
                post("/devices") {
                    service.registerDevice(call.user(), call.receive<DeviceRequest>().token)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/games") {
                    call.respond(service.createGame(call.user(), call.receive<CreateGameRequest>()))
                }
                get("/games/{id}") { call.respond(service.view(call.user(), call.parameters["id"]!!)) }
                delete("/games/{id}") {
                    service.deleteGame(call.user(), call.parameters["id"]!!)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/games/{id}/hints") {
                    service.recordHint(call.user(), call.parameters["id"]!!)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/games/{id}/moves") {
                    call.respond(service.move(call.user(), call.parameters["id"]!!, call.receive<MoveRequest>()))
                }
                post("/matchmaking") {
                    call.respond(service.matchmake(call.user(), call.request.queryParameters["language"] ?: "fr"))
                }
                delete("/matchmaking") {
                    service.leaveMatchmaking(call.user())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun ApplicationCall.user(): User = principal<User>()!!
