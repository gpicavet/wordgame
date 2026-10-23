package app.wordgame.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.wordgame.android.ApiClient
import app.wordgame.android.push.Push
import app.wordgame.android.SOLO_GAME_ID
import app.wordgame.android.SoloStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.wordgame.android.R
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import app.wordgame.engine.GameStatus
import app.wordgame.protocol.GameSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

data class GamesUiState(
    val games: List<GameSummary> = emptyList(),
    val waitingForOpponent: Boolean = false,
    val message: String? = null,
)

class GamesViewModel(private val api: ApiClient, private val solo: SoloStore) : ViewModel() {
    val state = MutableStateFlow(GamesUiState())

    suspend fun refresh() {
        // The solo game lives on the phone: it is listed even when the server is unreachable.
        val soloGame = withContext(Dispatchers.IO) { solo.summary() }?.let {
            GameSummary(
                SOLO_GAME_ID, "Nono-bot (débutant)", it.myScore, it.botScore, it.myTurn,
                if (it.finished) GameStatus.FINISHED else GameStatus.ACTIVE, it.won, updatedAt = Long.MAX_VALUE,
            )
        }
        runCatching { api.games() }
            .onSuccess { games -> state.update { it.copy(games = listOfNotNull(soloGame) + games, message = null) } }
            .onFailure { e -> state.update { it.copy(games = listOfNotNull(soloGame), message = e.userMessage()) } }
    }

    /** Opens the solo game in progress, or starts a new one. */
    fun playSolo(onOpen: (String) -> Unit) = viewModelScope.launch {
        val current = withContext(Dispatchers.IO) { solo.summary() }
        if (current == null || current.finished) solo.start()
        onOpen(SOLO_GAME_ID)
    }

    fun newGame(opponent: String, onCreated: (String) -> Unit) = viewModelScope.launch {
        runCatching { api.createGame(opponent) }
            .onSuccess { onCreated(it.id) }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }

    fun findRandomOpponent(onCreated: (String) -> Unit) = viewModelScope.launch {
        runCatching { api.matchmake() }
            .onSuccess { r ->
                if (r.gameId != null) onCreated(r.gameId!!)
                else state.update { it.copy(waitingForOpponent = true, message = "En attente d'un adversaire…") }
            }
            .onFailure { e -> state.update { it.copy(message = e.userMessage()) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(api: ApiClient, solo: SoloStore, nickname: String, onOpenGame: (String) -> Unit, onOpenStats: () -> Unit) {
    val vm = viewModel { GamesViewModel(api, solo) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showNewGame by remember { mutableStateOf(false) }

    // Poll while visible. Push notifications will replace this.
    LaunchedEffect(Unit) { Push.gameUpdates.collect { vm.refresh() } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh()
                delay(15.seconds)
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Parties de $nickname") }, actions = {
        IconButton(onClick = onOpenStats) {
            Icon(painterResource(R.drawable.ic_stats), contentDescription = "Mes statistiques", tint = MaterialTheme.colorScheme.primary)
        }
    }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = { showNewGame = true }) { Text("Défier un ami") }
                OutlinedButton(onClick = { vm.findRandomOpponent(onOpenGame) }) { Text("Adversaire au hasard") }
            }
            OutlinedButton(onClick = { vm.playSolo(onOpenGame) }, modifier = Modifier.fillMaxWidth()) {
                Text("Jouer contre Nono-bot")
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }

            val (active, finished) = state.games.partition { it.status == GameStatus.ACTIVE }
            val (myTurn, theirTurn) = active.partition { it.myTurn }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                section("À toi de jouer", myTurn, onOpenGame)
                section("En attente de l'adversaire", theirTurn, onOpenGame)
                section("Terminées", finished, onOpenGame)
            }
        }
    }

    if (showNewGame) {
        var opponent by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewGame = false },
            title = { Text("Défier un ami") },
            text = {
                OutlinedTextField(opponent, { opponent = it.trim() }, label = { Text("Pseudo de ton ami") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = opponent.length >= 3, onClick = {
                    showNewGame = false
                    vm.newGame(opponent, onOpenGame)
                }) { Text("Lancer") }
            },
            dismissButton = { TextButton(onClick = { showNewGame = false }) { Text("Annuler") } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    games: List<GameSummary>,
    onOpenGame: (String) -> Unit,
) {
    if (games.isEmpty()) return
    item { Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
    items(games, key = { it.id }) { game ->
        Card(Modifier.fillMaxWidth().clickable { onOpenGame(game.id) }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(game.opponent, fontWeight = FontWeight.Bold)
                    val result = when (game.won) {
                        true -> "Gagné"
                        false -> "Perdu"
                        null -> if (game.status == GameStatus.FINISHED) "Égalité" else null
                    }
                    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Text("${game.myScore} – ${game.opponentScore}", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
