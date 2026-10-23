package app.wordgame.engine

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotTest {
    private val lexicon = Lexicon(Dawg.fromBytes(File("../dictionaries/bin/fr-libre.dawg").readBytes()))
    private val common = Dawg.fromBytes(File("../dictionaries/bin/fr-courant.dawg").readBytes())

    @Test
    fun beginnerPlaysAWholeGameWithEverydayWords() {
        val bot = BeginnerBot(lexicon, common, Random(7), rareChance = 0.0)
        var state = Game.new(RuleSet.CLASSIC, 2, Random(7))
        val played = ArrayList<String>()
        var turns = 0
        while (state.status == GameStatus.ACTIVE && turns < 200) {
            val bagBefore = state.bag.length
            val outcome = Game.apply(state, state.currentPlayer, bot.play(state, state.currentPlayer), lexicon.words, Random(turns))
            assertTrue(outcome is MoveOutcome.Applied, "bot move rejected: $outcome")
            val words = outcome.record.words.map { it.word }
            // Rare words only once exchanging is no longer possible.
            if (bagBefore >= RuleSet.CLASSIC.rackSize) assertTrue(words.all { common.contains(it) }, "rare word with a full bag: $words")
            played += words
            state = outcome.state
            turns++
        }
        assertEquals(GameStatus.FINISHED, state.status)
        println("bot vs bot: ${state.players.map { it.score }} in $turns turns, words: $played")
    }
}
