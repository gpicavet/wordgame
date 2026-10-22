package app.wordgame.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** A notification for one player, tied to a game (tapping it opens that game). */
data class Notification(val userId: String, val title: String, val body: String, val gameId: String)

fun interface Notifier {
    /** Fire and forget: delivery problems are logged, never surfaced to the player who moved. */
    fun send(notifications: List<Notification>)
}

val NoNotifier = Notifier { }

/**
 * Firebase Cloud Messaging, HTTP v1 API, with a service-account key.
 * Uses only the JDK (HTTP client, RSA signature): no Google SDK on a 1 GB server.
 */
class FcmNotifier(credentials: File, private val db: Database) : Notifier {
    private val log = LoggerFactory.getLogger("fcm")
    private val http = HttpClient.newHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val account = Json.parseToJsonElement(credentials.readText()).jsonObject
    private val projectId = account.getValue("project_id").jsonPrimitive.content
    private val clientEmail = account.getValue("client_email").jsonPrimitive.content
    private val tokenUri = account.getValue("token_uri").jsonPrimitive.content
    private val privateKey = KeyFactory.getInstance("RSA").generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                account.getValue("private_key").jsonPrimitive.content
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", ""),
            ),
        ),
    )

    private val tokenLock = Mutex()
    private var accessToken: String? = null
    private var accessTokenExpiry = 0L

    init {
        log.info("FCM notifications enabled for project {}", projectId)
    }

    override fun send(notifications: List<Notification>) {
        if (notifications.isEmpty()) return
        scope.launch {
            for (n in notifications) {
                runCatching { deliver(n) }.onFailure { log.warn("Notification to {} failed: {}", n.userId, it.message) }
            }
        }
    }

    private suspend fun deliver(n: Notification) {
        val devices = db.tx { query("SELECT token FROM devices WHERE user_id = ?", n.userId) { it.getString(1) } }
        for (device in devices) {
            val body = buildJsonObject {
                putJsonObject("message") {
                    put("token", device)
                    putJsonObject("notification") {
                        put("title", n.title)
                        put("body", n.body)
                    }
                    putJsonObject("data") { put("gameId", n.gameId) }
                    putJsonObject("android") {
                        put("priority", "high")
                        putJsonObject("notification") {
                            put("channel_id", "turns")
                            // One notification per game: a new move replaces the previous one.
                            put("tag", n.gameId)
                        }
                    }
                }
            }
            val response = http.send(
                HttpRequest.newBuilder(URI("https://fcm.googleapis.com/v1/projects/$projectId/messages:send"))
                    .header("Authorization", "Bearer ${accessToken()}")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            when {
                response.statusCode() == 200 -> {}
                // The app was uninstalled or the token rotated: forget it.
                response.statusCode() == 404 || "UNREGISTERED" in response.body() ->
                    db.tx { update("DELETE FROM devices WHERE token = ?", device) }
                else -> log.warn("FCM {}: {}", response.statusCode(), response.body().take(300))
            }
        }
    }

    /** OAuth access token from a signed JWT, cached until a minute before it expires. */
    private suspend fun accessToken(): String = tokenLock.withLock {
        val now = System.currentTimeMillis() / 1000
        accessToken?.takeIf { now < accessTokenExpiry - 60 }?.let { return it }

        val b64 = Base64.getUrlEncoder().withoutPadding()
        val header = b64.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64.encodeToString(
            buildJsonObject {
                put("iss", clientEmail)
                put("scope", "https://www.googleapis.com/auth/firebase.messaging")
                put("aud", tokenUri)
                put("iat", now)
                put("exp", now + 3600)
            }.toString().toByteArray(),
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update("$header.$claims".toByteArray())
            b64.encodeToString(sign())
        }
        val form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") +
            "&assertion=$header.$claims.$signature"
        val response = http.send(
            HttpRequest.newBuilder(URI(tokenUri))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == 200) { "OAuth token request failed: ${response.statusCode()} ${response.body().take(300)}" }
        val json = Json.parseToJsonElement(response.body()).jsonObject
        accessTokenExpiry = now + json.getValue("expires_in").jsonPrimitive.content.toLong()
        json.getValue("access_token").jsonPrimitive.content.also { accessToken = it }
    }
}
