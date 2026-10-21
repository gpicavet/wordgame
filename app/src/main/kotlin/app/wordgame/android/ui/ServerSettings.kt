package app.wordgame.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.wordgame.android.BuildConfig
import app.wordgame.android.Session

/** Debug builds only: the button that opens [ServerUrlDialog]. */
@Composable
fun ServerButton(session: Session) {
    if (!BuildConfig.DEBUG) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Serveur") }
    if (open) ServerUrlDialog(session, onDismiss = { open = false })
}

@Composable
private fun ServerUrlDialog(session: Session, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf(session.serverUrl) }
    val valid = url.startsWith("http://") || url.startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adresse du serveur") },
        text = {
            Column {
                OutlinedTextField(url, { url = it.trim() }, singleLine = true, isError = !valid)
                Text("Émulateur : http://10.0.2.2:8080", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { session.serverUrl = url; onDismiss() }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
