package app.wordgame.android.ui

import androidx.compose.ui.res.stringResource
import app.wordgame.android.R
import app.wordgame.android.Strings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.wordgame.android.SoloBot

/** Who sits in a seat. [RANDOM] only for player 2, and then the game is a duel. */
enum class SeatKind(private val icon: String, private val labelName: Int) {
    FRIEND(FRIEND_ICON, R.string.seat_friend),
    RANDOM("", R.string.seat_random),
    ROBOT(ROBOT_ICON, R.string.seat_robot);

    val label: String get() = listOf(icon, Strings.get(labelName)).filter { it.isNotEmpty() }.joinToString(" ")
}

const val ROBOT_ICON = "🤖"
const val FRIEND_ICON = "🤸"

/** Language of the words of a game. */
enum class GameLanguage(val code: String, val flag: String, val label: String) {
    FR("fr", "🇫🇷", "Français"),
    EN("en", "🇬🇧", "English");

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code } ?: FR

        /** The language of the app (the phone's), for new games: French on a French phone, English otherwise. */
        fun ofApp() = if (java.util.Locale.getDefault().language == "fr") FR else EN
    }
}

/** An opponent seat: a friend (by nickname), a random player or a robot (picked in a list). */
class SeatChoice(kind: SeatKind = SeatKind.FRIEND) {
    var kind by mutableStateOf(kind)
    var nickname by mutableStateOf("")
    var bot by mutableStateOf(SoloBot.NONO)
}

/** What the player chose: the language, and the opponents in seat order (players 2 to 4). */
class GameSetup(val seats: List<SeatChoice>, val language: GameLanguage) {
    val random get() = seats.first().kind == SeatKind.RANDOM
    val friends get() = seats.filter { it.kind == SeatKind.FRIEND }.map { it.nickname }
    val bots get() = seats.filter { it.kind == SeatKind.ROBOT }.map { it.bot }

    /** A duel against one robot: played on the phone, like before. */
    val solo get() = seats.size == 1 && seats.single().kind == SeatKind.ROBOT
}

/** Game setup behind the "Jouer" button: player 2, then optionally players 3 and 4. */
@Composable
fun NewGameDialog(onDismiss: () -> Unit, onStart: (GameSetup) -> Unit) {
    val seats = remember { mutableStateListOf(SeatChoice()) }
    var language by remember { mutableStateOf(GameLanguage.ofApp()) }
    val setup = GameSetup(seats, language)
    val problem = when {
        setup.friends.any { it.length < 3 } -> stringResource(R.string.problem_friend_nickname)
        setup.friends.map { it.lowercase() }.toSet().size < setup.friends.size -> stringResource(R.string.problem_duplicate_friend)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_game)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LanguagePicker(language) { language = it }
                seats.forEachIndexed { i, seat ->
                    SeatEditor(
                        number = i + 2,
                        seat = seat,
                        kinds = if (i == 0) SeatKind.entries else SeatKind.entries - SeatKind.RANDOM,
                        onRemove = if (i == 0) null else ({ seats.remove(seat) }),
                        onSelect = { kind ->
                            seat.kind = kind
                            // A random opponent is matched for a duel only.
                            if (kind == SeatKind.RANDOM) seats.removeRange(1, seats.size)
                        },
                    )
                }
                if (setup.random) {
                    Text(stringResource(R.string.random_hint), style = MaterialTheme.typography.bodySmall)
                } else if (seats.size < 3) {
                    TextButton(onClick = { seats += SeatChoice() }) { Text(stringResource(R.string.add_player)) }
                }
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = { onDismiss(); onStart(setup) }) { Text(stringResource(R.string.start_game)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagePicker(language: GameLanguage, onSelect: (GameLanguage) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GameLanguage.entries.forEach { l ->
                FilterChip(selected = language == l, onClick = { onSelect(l) }, label = { Text("${l.flag} ${l.label}", maxLines = 1) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SeatEditor(number: Int, seat: SeatChoice, kinds: List<SeatKind>, onRemove: (() -> Unit)?, onSelect: (SeatKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.player_n, number), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            onRemove?.let { TextButton(onClick = it) { Text(stringResource(R.string.remove)) } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            kinds.forEach { kind ->
                FilterChip(selected = seat.kind == kind, onClick = { onSelect(kind) }, label = { Text(kind.label, maxLines = 1) })
            }
        }
        when (seat.kind) {
            SeatKind.FRIEND -> OutlinedTextField(
                seat.nickname, { seat.nickname = it.trim() }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.friend_nickname)) }, singleLine = true,
                supportingText = { Text(stringResource(R.string.friend_nickname_hint)) },
            )
            SeatKind.ROBOT -> {
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                    OutlinedTextField(
                        value = botLabel(seat.bot),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.seat_robot)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        SoloBot.entries.forEach { bot ->
                            DropdownMenuItem(text = { Text(botLabel(bot)) }, onClick = { seat.bot = bot; open = false })
                        }
                    }
                }
            }
            SeatKind.RANDOM -> {}
        }
    }
}

private fun botLabel(bot: SoloBot) = "$ROBOT_ICON ${bot.displayName} (${bot.level})"
