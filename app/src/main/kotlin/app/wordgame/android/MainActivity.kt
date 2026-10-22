package app.wordgame.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.wordgame.android.push.EXTRA_GAME_ID
import app.wordgame.android.push.Push
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.wordgame.android.ui.GameScreen
import app.wordgame.android.ui.GamesScreen
import app.wordgame.android.ui.OnboardingScreen
import app.wordgame.android.ui.StatsScreen
import app.wordgame.android.ui.WordgameTheme

class MainActivity : ComponentActivity() {
    /** Game to open, from a tapped notification. */
    private val notifiedGame = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) notifiedGame.value = intent.getStringExtra(EXTRA_GAME_ID)
        val container = (application as WordgameApplication).container
        setContent {
            WordgameTheme {
                WordgameApp(container, notifiedGame.value, onNotifiedGameOpened = { notifiedGame.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_GAME_ID)?.let { notifiedGame.value = it }
    }
}

@Composable
private fun WordgameApp(container: AppContainer, notifiedGame: String?, onNotifiedGameOpened: () -> Unit) {
    var nickname by rememberSaveable { mutableStateOf(container.session.nickname) }
    var openGameId by rememberSaveable { mutableStateOf<String?>(null) }
    var showStats by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(notifiedGame) {
        if (notifiedGame != null && nickname != null) {
            showStats = false
            openGameId = notifiedGame
            onNotifiedGameOpened()
        }
    }

    // Once signed up: ask for the notification permission (Android 13+) and register this phone.
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(nickname) {
        if (nickname == null) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Push.syncToken(context)
    }

    val gameId = openGameId
    when {
        nickname == null -> OnboardingScreen(container.api, container.session, onRegistered = { nickname = it })
        gameId != null -> {
            BackHandler { openGameId = null }
            GameScreen(container, gameId, onBack = { openGameId = null })
        }
        showStats -> {
            BackHandler { showStats = false }
            StatsScreen(container.api, onBack = { showStats = false })
        }
        else -> GamesScreen(container.api, container.session, nickname!!, onOpenGame = { openGameId = it }, onOpenStats = { showStats = true })
    }
}
