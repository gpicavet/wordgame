package app.wordgame.server

import app.wordgame.engine.Action
import app.wordgame.engine.BeginnerBot
import app.wordgame.engine.GameState
import app.wordgame.engine.Lexicon
import app.wordgame.engine.MoveRecord
import app.wordgame.engine.ValueBot
import app.wordgame.engine.WordList
import app.wordgame.engine.bingoAllowed
import kotlin.random.Random

/**
 * A robot that can take a seat in an online game, next to human players. It has a user row with a fixed id
 * (and no usable token); the server plays its turns as soon as they come.
 */
class ServerBot(
    /** As sent by the app in [app.wordgame.protocol.CreateGameRequest.bots]. */
    val level: String,
    val nickname: String,
    val play: (state: GameState, seat: Int, history: List<MoveRecord>) -> Action,
) {
    val userId get() = "bot-$level"
}

/** NonoBot and BelzéBot, as in the app's solo mode. */
fun serverBots(lexicon: Lexicon, common: WordList, belzeWeights: DoubleArray, random: Random): List<ServerBot> = listOf(
    ServerBot("nono", "NonoBot") { state, seat, _ -> BeginnerBot(lexicon, common, random).play(state, seat) },
    ServerBot("belze", "BelzéBot") { state, seat, history ->
        ValueBot(lexicon, belzeWeights, random, vocabulary = common).decide(state, seat, bingoAllowed(history, seat, random)).action
    },
)
