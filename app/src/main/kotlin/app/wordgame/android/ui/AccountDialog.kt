package app.wordgame.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wordgame.android.ApiClient
import app.wordgame.android.attempt
import kotlinx.coroutines.launch

/**
 * The player's full name ("greg#4821", what friends type to invite them: several players may share a nickname)
 * and the deletion of the account, confirmed first. [onDeleted] runs once the server has deleted it.
 */
@Composable
fun AccountDialog(api: ApiClient, nickname: String, onDeleted: suspend () -> Unit, onDismiss: () -> Unit) {
    var tag by remember { mutableStateOf(api.session.tag) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Accounts from before the tags: ask the server once.
    LaunchedEffect(Unit) { if (tag == 0) attempt { api.me() }.onSuccess { tag = it.tag } }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirming = false },
            title = { Text("Supprimer ton compte ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tu abandonnes tes parties en cours contre d'autres joueurs ; elles restent visibles pour eux sous le nom « Joueur supprimé ».")
                    Text("Tes parties contre les robots, tes statistiques et tes parties solo sont effacées. C'est définitif.")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        attempt { api.deleteAccount() }
                            .onSuccess { onDeleted() }
                            .onFailure { error = it.userMessage(); busy = false }
                    }
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirming = false }) { Text("Annuler") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mon compte") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (tag > 0) "$nickname#$tag" else nickname, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Donne ce nom à tes amis pour qu'ils t'invitent : d'autres joueurs peuvent avoir le même pseudo.")
                TextButton(onClick = { confirming = true }) { Text("Supprimer mon compte", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
