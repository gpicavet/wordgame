package app.wordgame.engine

import kotlin.random.Random

/**
 * Beginner opponent: plays everyday words, and does not always find the best one.
 * - moves whose words are all in [common] (everyday vocabulary); with probability [rareChance], any word;
 *   with no everyday move it exchanges, and only plays a rarer word once exchanges are impossible;
 * - picks at random among the [choices] best of those moves, so it scores like a casual player.
 */
class BeginnerBot(
    private val lexicon: Lexicon,
    private val common: WordList,
    private val random: Random,
    private val rareChance: Double = 0.05,
    private val choices: Int = 15,
) {
    fun play(state: GameState, seat: Int): Action {
        val rules = state.rules
        val rack = state.players[seat].rack
        val all = Solver(lexicon, rules).bestMoves(state.boardModel(), rack, Int.MAX_VALUE)
        val everyday = all.filter { move -> move.words.all { common.contains(it.word) } }
        val canExchange = state.bag.length >= rules.rackSize
        val pool = when {
            random.nextDouble() < rareChance -> all
            everyday.isNotEmpty() -> everyday
            // No everyday word: a beginner swaps letters rather than playing an obscure word…
            canExchange -> emptyList()
            // …unless the bag is nearly empty and swapping is no longer allowed.
            else -> all
        }
        if (pool.isNotEmpty()) return Action.Play(pool.take(choices).random(random).tiles)
        return if (canExchange) Action.Exchange(rack) else Action.Pass
    }
}
