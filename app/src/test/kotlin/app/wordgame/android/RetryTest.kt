package app.wordgame.android

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class RetryTest {
    private var calls = 0

    private fun client(respond: suspend () -> HttpStatusCode) = HttpClient(MockEngine {
        calls++
        respond(content = "", status = respond())
    }) { retrySafeCalls(delayStepMs = 1) }

    @Test
    fun readsAndMovesAreRetriedOnServerErrors() = runBlocking {
        client { HttpStatusCode.ServiceUnavailable }.get("https://x/api/v1/games")
        assertEquals(3, calls)
        calls = 0
        client { HttpStatusCode.BadGateway }.post("https://x/api/v1/games/42/moves")
        assertEquals(3, calls)
    }

    @Test
    fun gameErrorsAreNeverRetried() = runBlocking {
        client { HttpStatusCode.UnprocessableEntity }.post("https://x/api/v1/games/42/moves")
        assertEquals(1, calls)
    }

    @Test
    fun signUpAndChallengesAreNotRetried() = runBlocking {
        client { HttpStatusCode.ServiceUnavailable }.post("https://x/api/v1/auth/register")
        assertEquals(1, calls)
        calls = 0
        client { HttpStatusCode.ServiceUnavailable }.post("https://x/api/v1/games")
        assertEquals(1, calls)
    }

    @Test
    fun networkErrorsAreRetriedThenReported() = runBlocking {
        val failing = HttpClient(MockEngine { calls++; throw IOException("réseau coupé") }) { retrySafeCalls(delayStepMs = 1) }
        val result = runCatching { failing.get("https://x/api/v1/games") }
        assertEquals(3, calls)
        assertEquals(true, result.exceptionOrNull() is IOException)
    }
}
