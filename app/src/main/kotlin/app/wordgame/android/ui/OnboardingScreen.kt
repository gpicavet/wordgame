package app.wordgame.android.ui

import app.wordgame.android.attempt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.wordgame.android.ApiClient
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(api: ApiClient, onRegistered: (String) -> Unit) {
    var nickname by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Bienvenue", style = MaterialTheme.typography.headlineMedium)
            Text("Choisis un pseudo pour jouer avec tes amis.")
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it.trim().take(16) },
                label = { Text("Pseudo") },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: "3 à 16 caractères : lettres, chiffres, _ ou -") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && nickname.length >= 3,
                onClick = {
                    busy = true
                    scope.launch {
                        attempt { api.register(nickname) }
                            .onSuccess { onRegistered(it.nickname) }
                            .onFailure { error = it.userMessage() }
                        busy = false
                    }
                },
            ) { Text("Commencer") }
        }
    }
}
