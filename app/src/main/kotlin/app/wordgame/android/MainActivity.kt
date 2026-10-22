package app.wordgame.android

import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as WordgameApplication).container
        setContent {
            WordgameTheme { WordgameApp(container) }
        }
    }
}

@Composable
private fun WordgameApp(container: AppContainer) {
    var nickname by rememberSaveable { mutableStateOf(container.session.nickname) }
    var openGameId by rememberSaveable { mutableStateOf<String?>(null) }
    var showStats by rememberSaveable { mutableStateOf(false) }

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
