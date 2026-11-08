package app.wordgame.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.wordgame.android.BuildConfig

/** Shown when the server refuses this version of the app (UPGRADE_REQUIRED): a link to the store listing. */
@Composable
fun UpgradeScreen() {
    val uriHandler = LocalUriHandler.current
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Mise à jour nécessaire", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Une nouvelle version de l'application est disponible. Mets-la à jour pour continuer tes parties : " +
                    "elles t'attendent.",
                textAlign = TextAlign.Center,
            )
            Button(onClick = {
                uriHandler.openUri("https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}")
            }) { Text("Mettre à jour") }
        }
    }
}
