package app.wordgame.android.ui

import androidx.compose.ui.res.stringResource
import app.wordgame.android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wordgame.android.ApiClient
import app.wordgame.android.attempt
import app.wordgame.protocol.ReportReason
import kotlinx.coroutines.launch

private val REASONS = listOf(
    ReportReason.NICKNAME to R.string.report_reason_nickname,
    ReportReason.CHEATING to R.string.report_reason_cheating,
    ReportReason.OTHER to R.string.report_reason_other,
)

/**
 * Another player of an online game, opened by tapping their name: report them (to the moderator) or block them
 * (no more challenges nor matchmaking between the two of them; unblocked from "Mon compte").
 */
@Composable
fun PlayerDialog(api: ApiClient, gameId: String, seat: Int, nickname: String, onDismiss: () -> Unit) {
    var reporting by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var details by remember { mutableStateOf("") }
    var alsoBlock by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun send(action: suspend () -> Unit, success: String) {
        busy = true
        error = null
        scope.launch {
            attempt { action() }.onSuccess { done = success }.onFailure { error = it.userMessage() }
            busy = false
        }
    }

    val message = done
    if (message != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(nickname) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        )
        return
    }

    if (reporting) {
        val sentText = if (alsoBlock) stringResource(R.string.report_sent_blocked, nickname) else stringResource(R.string.report_sent)
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text(stringResource(R.string.report_title, nickname)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    REASONS.forEach { (r, label) ->
                        Row(Modifier.fillMaxWidth().clickable { reason = r }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = reason == r, onClick = { reason = r })
                            Text(stringResource(label))
                        }
                    }
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it.take(500) },
                        label = { Text(stringResource(R.string.report_details)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth().clickable { alsoBlock = !alsoBlock }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = alsoBlock, onCheckedChange = { alsoBlock = it })
                        Text(stringResource(R.string.also_block))
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && reason != null, onClick = {
                    val r = reason ?: return@TextButton
                    send({ api.report(gameId, seat, r, details.ifBlank { null }, alsoBlock) }, sentText)
                }) { Text(stringResource(R.string.send)) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(nickname, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.block_explain))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val blockedText = stringResource(R.string.blocked_done, nickname)
                TextButton(enabled = !busy, onClick = { send({ api.block(gameId, seat) }, blockedText) }) {
                    Text(stringResource(R.string.block))
                }
                TextButton(enabled = !busy, onClick = { reporting = true }) { Text(stringResource(R.string.report), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
