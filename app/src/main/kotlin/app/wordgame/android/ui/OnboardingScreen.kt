package app.wordgame.android.ui

import androidx.compose.ui.res.stringResource
import app.wordgame.android.R
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wordgame.android.ApiClient
import app.wordgame.android.ApiFailure
import app.wordgame.android.googleIdToken
import app.wordgame.android.googleSignInAvailable
import kotlinx.coroutines.launch

/**
 * A nickname to start playing, or the account linked to the player's Google account. When Google finds no account,
 * the nickname chosen next creates one linked to it.
 */
@Composable
fun OnboardingScreen(api: ApiClient, onRegistered: (String) -> Unit) {
    var nickname by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Google account without a WordWok account yet: linked to the one about to be created.
    var pendingGoogle by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.welcome), style = MaterialTheme.typography.headlineMedium)
            Text(
                if (pendingGoogle == null) stringResource(R.string.onboarding_pick)
                else stringResource(R.string.onboarding_google_new),
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it.trim().take(16) },
                label = { Text(stringResource(R.string.nickname)) },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: stringResource(R.string.nickname_rule)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && nickname.length >= 3,
                onClick = {
                    busy = true
                    scope.launch {
                        attempt { api.register(nickname, pendingGoogle) }
                            .onSuccess { onRegistered(it.nickname) }
                            .onFailure { error = it.userMessage() }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.start)) }
            if (googleSignInAvailable && pendingGoogle == null) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            attempt { googleIdToken(context) }
                                .onSuccess { idToken ->
                                    if (idToken != null) attempt { api.signInWithGoogle(idToken) }
                                        .onSuccess { onRegistered(it.nickname) }
                                        .onFailure { e ->
                                            if ((e as? ApiFailure)?.error?.code == "NO_ACCOUNT") pendingGoogle = idToken
                                            else error = e.userMessage()
                                        }
                                }
                                .onFailure { error = it.userMessage() }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.sign_in_google)) }
            }
        }
    }
}
