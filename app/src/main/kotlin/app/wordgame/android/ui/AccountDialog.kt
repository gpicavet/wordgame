package app.wordgame.android.ui

import app.wordgame.android.ads.Ads
import app.wordgame.android.ads.findActivity
import androidx.compose.ui.res.stringResource
import app.wordgame.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.wordgame.protocol.BlockedPlayer
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wordgame.android.ApiClient
import app.wordgame.android.BuildConfig
import app.wordgame.android.attempt
import app.wordgame.android.googleIdToken
import app.wordgame.android.googleSignInAvailable
import kotlinx.coroutines.launch

/**
 * The player's full name ("greg#4821", what friends type to invite them: several players may share a nickname)
 * and the deletion of the account, confirmed first. [onDeleted] runs once the server has deleted it.
 */
@Composable
fun AccountDialog(api: ApiClient, nickname: String, ads: Ads, onDeleted: suspend () -> Unit, onDismiss: () -> Unit) {
    var tag by remember { mutableStateOf(api.session.tag) }
    var google by remember { mutableStateOf<Boolean?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var blocked by remember { mutableStateOf<List<BlockedPlayer>>(emptyList()) }
    LaunchedEffect(Unit) { attempt { api.me() }.onSuccess { tag = it.tag; google = it.google } }
    LaunchedEffect(Unit) { attempt { api.blockedPlayers() }.onSuccess { blocked = it } }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirming = false },
            title = { Text(stringResource(R.string.delete_account_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.delete_account_resign))
                    Text(stringResource(R.string.delete_account_erased))
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
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.my_account)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (tag > 0) "$nickname#$tag" else nickname, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.account_name_hint))
                when {
                    google == true -> Text(stringResource(R.string.google_saved))
                    google == false && googleSignInAvailable -> {
                        Text(stringResource(R.string.google_unsaved))
                        TextButton(enabled = !busy, onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                attempt { googleIdToken(context)?.let { api.linkGoogle(it); true } ?: false }
                                    .onSuccess { linked -> if (linked) google = true }
                                    .onFailure { error = it.userMessage() }
                                busy = false
                            }
                        }) { Text(stringResource(R.string.save_with_google)) }
                    }
                }
                if (blocked.isNotEmpty()) {
                    Text(stringResource(R.string.blocked_players), fontWeight = FontWeight.Bold)
                    blocked.forEach { player ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(player.name, Modifier.weight(1f))
                            TextButton(enabled = !busy, onClick = {
                                scope.launch {
                                    attempt { api.unblock(player.userId) }
                                        .onSuccess { blocked = blocked - player }
                                        .onFailure { error = it.userMessage() }
                                }
                            }) { Text(stringResource(R.string.unblock)) }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val uriHandler = LocalUriHandler.current
                // Required by the consent rules wherever consent to ads was asked.
                if (ads.privacyOptionsRequired) {
                    TextButton(onClick = { context.findActivity()?.let(ads::showPrivacyOptions) }) { Text(stringResource(R.string.ads_privacy_options)) }
                }
                val privacyPath = stringResource(R.string.privacy_path)
                TextButton(onClick = { uriHandler.openUri("${BuildConfig.API_URL}$privacyPath") }) { Text(stringResource(R.string.privacy_policy)) }
                TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.delete_my_account), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
