package app.wordgame.android.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.wordgame.android.MainActivity
import app.wordgame.android.R
import app.wordgame.android.WordgameApplication
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

const val CHANNEL_TURNS = "turns"

/** Intent extra carrying the game to open; FCM puts the message's data keys in the same place. */
const val EXTRA_GAME_ID = "gameId"

object Push {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Games that just changed (push received, or notification tapped): open screens reload them. */
    val gameUpdates = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_TURNS, "Tours de jeu", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Quand c'est à toi de jouer, et les fins de partie"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Sends this phone's FCM token to the server. No-op without Firebase config or before sign-up. */
    fun syncToken(context: Context) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { register(context, it) }
    }

    fun register(context: Context, token: String) {
        val container = (context.applicationContext as WordgameApplication).container
        if (container.session.token == null) return
        scope.launch { runCatching { container.api.registerDevice(token) } }
    }

    fun openGameIntent(context: Context, gameId: String): PendingIntent = PendingIntent.getActivity(
        context,
        gameId.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_GAME_ID, gameId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class WordgameMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Push.register(this, token)

    /** Only called while the app is in the foreground: FCM shows background notifications itself. */
    override fun onMessageReceived(message: RemoteMessage) {
        val gameId = message.data[EXTRA_GAME_ID] ?: return
        // The app is open: refresh what is on screen right away.
        Push.gameUpdates.tryEmit(gameId)
        val content = message.notification ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_TURNS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setAutoCancel(true)
            .setContentIntent(Push.openGameIntent(this, gameId))
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(gameId, 0, notification) }
            .onFailure { Log.w("push", "Could not show the notification", it) }
    }
}
