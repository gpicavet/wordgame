package app.wordgame.android

import android.app.Application
import android.content.Context
import app.wordgame.engine.Afterstate
import app.wordgame.engine.Dawg
import app.wordgame.engine.ExtraWords
import app.wordgame.engine.Lexicon
import app.wordgame.protocol.ApiError
import app.wordgame.protocol.ApiJson
import app.wordgame.protocol.AuthResponse
import app.wordgame.protocol.CreateGameRequest
import app.wordgame.protocol.DeviceRequest
import app.wordgame.protocol.GameSummary
import app.wordgame.protocol.GameView
import app.wordgame.protocol.MatchmakingResponse
import app.wordgame.protocol.MoveRequest
import app.wordgame.protocol.PlayerStats
import app.wordgame.protocol.RegisterRequest
import app.wordgame.engine.Action
import app.wordgame.android.push.Push
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpMethod
import kotlin.coroutines.cancellation.CancellationException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WordgameApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        Push.createChannel(this)
        Push.syncToken(this)
    }
}

class AppContainer(context: Context) {
    val session = Session(context)
    val api = ApiClient(session)
    val dictionary = DictionaryLoader(context)
    val solo: List<SoloStore> = SoloBot.entries.map { SoloStore(context, dictionary, session, it) }

    fun backend(gameId: String): GameBackend =
        SoloBot.byGameId(gameId)?.let { bot -> solo.first { it.bot == bot }.backend } ?: ServerBackend(api, gameId)
}

/** Account token. Losing it means losing the account until account linking exists. */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    val token: String? get() = prefs.getString("token", null)
    val nickname: String? get() = prefs.getString("nickname", null)

    fun save(auth: AuthResponse) {
        prefs.edit().putString("token", auth.token).putString("nickname", auth.nickname).apply()
    }
}

private const val EXTRA_WORDS = "fr-2lettres.txt"

class DictionaryLoader(private val context: Context) {
    private val mutex = Mutex()
    private var lexicon: Lexicon? = null

    private var common: Dawg? = null

    private var expert: DoubleArray? = null

    /** BelzéBot's learned weights (models/value-v2.txt). */
    suspend fun expertWeights(): DoubleArray = mutex.withLock {
        expert ?: withContext(Dispatchers.IO) {
            Afterstate.parseWeights(context.assets.open("value-v2.txt").bufferedReader().use { it.readText() })
        }.also { expert = it }
    }

    /** Everyday vocabulary, for the bots. */
    suspend fun common(): Dawg = mutex.withLock {
        common ?: withContext(Dispatchers.IO) {
            Dawg.fromBytes(context.assets.open("fr-courant.dawg").use { it.readBytes() })
        }.also { common = it }
    }

    suspend fun get(): Lexicon = mutex.withLock {
        lexicon ?: withContext(Dispatchers.IO) {
            val dawg = Dawg.fromBytes(context.assets.open("fr-libre.dawg").use { it.readBytes() })
            // Private supplement, bundled only when dictionaries/private/ exists at build time.
            val extra = runCatching { context.assets.open(EXTRA_WORDS).bufferedReader().use { ExtraWords.parse(it.readText()) } }.getOrNull()
            Lexicon(dawg, extra)
        }.also { lexicon = it }
    }
}

class ApiFailure(val error: ApiError) : Exception(error.message)

/**
 * Calls that are safe to send twice: reads, moves (guarded by the expected move number, so a move can
 * never be played twice) and the phone registration (an upsert). Not sign-up, challenges or deletion.
 */
private fun retryable(method: HttpMethod, url: String) =
    method == HttpMethod.Get || url.endsWith("/moves") || url.endsWith("/api/v1/devices")

/** Up to 2 retries (after ~0.5 s then ~1 s) on network errors and 5xx, never on game errors (4xx). */
fun HttpClientConfig<*>.retrySafeCalls(delayStepMs: Long = 500) {
    install(HttpRequestRetry) {
        maxRetries = 2
        retryIf { request, response -> response.status.value >= 500 && retryable(request.method, request.url.toString()) }
        retryOnExceptionIf { request, cause ->
            cause !is CancellationException && retryable(request.method, request.url.buildString())
        }
        delayMillis { retry -> delayStepMs * retry }
    }
}

class ApiClient(private val session: Session) {
    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(ApiJson) }
        defaultRequest { url(BuildConfig.API_URL) }
        retrySafeCalls()
    }

    suspend fun register(nickname: String): AuthResponse =
        http.post("/api/v1/auth/register") { json(RegisterRequest(nickname)) }.result<AuthResponse>()
            .also(session::save)

    suspend fun games(): List<GameSummary> = http.get("/api/v1/games") { auth() }.result()

    suspend fun game(id: String): GameView = http.get("/api/v1/games/$id") { auth() }.result()

    suspend fun createGame(friends: List<String>, bots: List<String>): GameView =
        http.post("/api/v1/games") { auth(); json(CreateGameRequest(friends.singleOrNull()?.takeIf { bots.isEmpty() }, friends, bots)) }.result()

    suspend fun registerDevice(token: String) {
        http.post("/api/v1/devices") { auth(); json(DeviceRequest(token)) }.result<Unit>()
    }

    suspend fun stats(): PlayerStats = http.get("/api/v1/me/stats") { auth() }.result()

    suspend fun deleteGame(id: String) {
        http.delete("/api/v1/games/$id") { auth() }.result<Unit>()
    }

    suspend fun matchmake(): MatchmakingResponse = http.post("/api/v1/matchmaking") { auth() }.result()

    suspend fun move(game: GameView, action: Action): GameView =
        http.post("/api/v1/games/${game.id}/moves") { auth(); json(MoveRequest(game.moveNumber, action)) }.result()

    private fun HttpRequestBuilder.auth() {
        session.token?.let { bearerAuth(it) }
    }

    private inline fun <reified T> HttpRequestBuilder.json(body: T) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend inline fun <reified T> HttpResponse.result(): T {
        if (status.isSuccess()) return if (T::class == Unit::class) Unit as T else body()
        val error = runCatching { body<ApiError>() }.getOrNull() ?: ApiError("HTTP_${status.value}", status.description)
        throw ApiFailure(error)
    }
}
