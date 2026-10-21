package app.wordgame.android

import android.app.Application
import android.content.Context
import app.wordgame.engine.Dawg
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
import app.wordgame.engine.Action
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
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
}

class AppContainer(context: Context) {
    val session = Session(context)
    val api = ApiClient(session)
    val dictionary = DictionaryLoader(context)
}

/** Account token. Losing it means losing the account until account linking exists. */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    val token: String? get() = prefs.getString("token", null)
    val nickname: String? get() = prefs.getString("nickname", null)

    /** Editable in debug builds so a new tunnel address does not need a new APK. */
    var serverUrl: String
        get() = prefs.getString("serverUrl", null) ?: BuildConfig.API_URL
        set(value) = prefs.edit().putString("serverUrl", value.trim().trimEnd('/')).apply()

    fun save(auth: AuthResponse) {
        prefs.edit().putString("token", auth.token).putString("nickname", auth.nickname).apply()
    }
}

class DictionaryLoader(private val context: Context) {
    private val mutex = Mutex()
    private var words: WordList? = null

    suspend fun get(): WordList = mutex.withLock {
        words ?: withContext(Dispatchers.IO) {
            Dawg.fromBytes(context.assets.open("fr-libre.dawg").use { it.readBytes() })
        }.also { words = it }
    }
}

class ApiFailure(val error: ApiError) : Exception(error.message)

class ApiClient(private val session: Session) {
    private val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(ApiJson) }
        // Evaluated for every request, so a server address change applies immediately.
        defaultRequest { url(session.serverUrl) }
    }

    suspend fun register(nickname: String): AuthResponse =
        http.post("/api/v1/auth/register") { json(RegisterRequest(nickname)) }.result<AuthResponse>()
            .also(session::save)

    suspend fun games(): List<GameSummary> = http.get("/api/v1/games") { auth() }.result()

    suspend fun game(id: String): GameView = http.get("/api/v1/games/$id") { auth() }.result()

    suspend fun createGame(opponent: String): GameView =
        http.post("/api/v1/games") { auth(); json(CreateGameRequest(opponent)) }.result()

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
