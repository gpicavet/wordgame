package app.wordgame.trainer

import app.wordgame.engine.Afterstate
import app.wordgame.engine.BeginnerBot
import app.wordgame.engine.Board
import app.wordgame.engine.Dawg
import app.wordgame.engine.Decision
import app.wordgame.engine.Game
import app.wordgame.engine.GameState
import app.wordgame.engine.GameStatus
import app.wordgame.engine.Lexicon
import app.wordgame.engine.MoveOutcome
import app.wordgame.engine.MoveType
import app.wordgame.engine.RuleSet
import app.wordgame.engine.ValueBot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Self-play training of the afterstate value used by [ValueBot] (reinforcement learning, Monte Carlo targets):
 * after each of its moves, a player's afterstate is labelled with the score difference that followed until
 * the end of the game; the linear value is fitted to it by normalized SGD, while the bot keeps playing with
 * the latest weights (policy improvement). Greedy play is the same bot with zero weights.
 */
private val rules = RuleSet.CLASSIC
private val lexicon = Lexicon(Dawg.fromBytes(File("dictionaries/bin/fr-libre.dawg").readBytes()))
private val common = Dawg.fromBytes(File("dictionaries/bin/fr-courant.dawg").readBytes())

private typealias Player = (GameState, Int) -> Decision

/** Training sample: afterstate features and the score difference that followed it. */
private class Sample(val x: DoubleArray, val target: Double)

private class Result(val scores: List<Int>, val samples: List<Sample>, val bingos: List<Int>)

/** Plays one game; seat i is played by players[i]. */
private fun play(players: List<Player>, random: Random, record: Boolean): Result {
    var state = Game.new(rules, 2, random)
    // (seat, features, score difference for that seat right after the move)
    val trace = ArrayList<Triple<Int, DoubleArray, Int>>()
    var turns = 0
    val bingos = IntArray(2)
    while (state.status == GameStatus.ACTIVE && turns < 200) {
        val seat = state.currentPlayer
        val d = players[seat](state, seat)
        val outcome = Game.apply(state, seat, d.action, lexicon.words, random)
        check(outcome is MoveOutcome.Applied) { "rejected: $outcome" }
        state = outcome.state
        if (outcome.record.tiles.size == rules.rackSize) bingos[seat]++
        if (record && d.features != null) trace += Triple(seat, d.features!!, diff(state, seat))
        turns++
    }
    val samples = trace.map { (seat, x, after) -> Sample(x, (diff(state, seat) - after).toDouble()) }
    return Result(state.players.map { it.score }, samples, bingos.toList())
}

private fun diff(state: GameState, seat: Int) = state.players[seat].score - state.players[1 - seat].score

private fun greedy(random: Random): Player {
    val bot = ValueBot(lexicon, DoubleArray(Afterstate.size), random)
    return { s, seat -> bot.decide(s, seat) }
}

private fun valueBot(weights: DoubleArray, random: Random, epsilon: Double = 0.0): Player {
    val bot = ValueBot(lexicon, weights, random, epsilon = epsilon, vocabulary = common)
    return { s, seat -> bot.decide(s, seat) }
}

private fun greedyCommon(random: Random): Player {
    val bot = ValueBot(lexicon, DoubleArray(Afterstate.size), random, vocabulary = common)
    return { s, seat -> bot.decide(s, seat) }
}

private fun beginner(random: Random): Player {
    val bot = BeginnerBot(lexicon, common, random)
    return { s, seat -> Decision(bot.play(s, seat), 0, null) }
}

/** Plays [games] games between a and b (alternating who starts), in parallel; prints a's average margin. */
private fun match(name: String, games: Int, a: (Random) -> Player, b: (Random) -> Player) = runBlocking(Dispatchers.Default) {
    val margins = (0 until games).map { g ->
        async {
            val random = Random(1_000_000L + g)
            val players = if (g % 2 == 0) listOf(a(random), b(random)) else listOf(b(random), a(random))
            val r = play(players, random, record = false)
            val aSeat = g % 2
            Triple(r.scores[aSeat] - r.scores[1 - aSeat], r.bingos[aSeat], r.bingos[1 - aSeat])
        }
    }.awaitAll().also { list ->
        println("  scrabbles par partie : %.2f contre %.2f".format(list.map { it.second }.average(), list.map { it.third }.average()))
    }.map { it.first }
    val mean = margins.average()
    val sd = sqrt(margins.sumOf { (it - mean) * (it - mean) } / (games - 1))
    val wins = margins.count { it > 0 } * 100.0 / games
    println("%s : %+.1f points par partie (±%.1f à 95 %%), %.0f %% de victoires sur %d parties".format(name, mean, 1.96 * sd / sqrt(games.toDouble()), wins, games))
}

private fun train(games: Int, out: File) = runBlocking(Dispatchers.Default) {
    val w = DoubleArray(Afterstate.size)
    val batch = Runtime.getRuntime().availableProcessors()
    var played = 0
    var errSum = 0.0
    var errCount = 0
    val start = System.currentTimeMillis()
    while (played < games) {
        val snapshot = w.copyOf()
        val results = (0 until batch).map { i ->
            async {
                val random = Random(played + i.toLong())
                play(listOf(valueBot(snapshot, random, 0.05), valueBot(snapshot, random, 0.05)), random, record = true)
            }
        }.awaitAll()
        val lr = if (played < games / 2) 0.02 else 0.005
        for (r in results) for (s in r.samples) {
            var v = 0.0
            var norm = 1e-6
            for (i in w.indices) { v += w[i] * s.x[i]; norm += s.x[i] * s.x[i] }
            val err = s.target - v
            for (i in w.indices) w[i] += lr * err * s.x[i] / norm
            errSum += kotlin.math.abs(err); errCount++
        }
        played += batch
        if (played % (batch * 50) < batch) {
            println("%6d parties · erreur moyenne %.1f pts · %.0f s".format(played, errSum / errCount, (System.currentTimeMillis() - start) / 1000.0))
            errSum = 0.0; errCount = 0
            save(w, out)
        }
    }
    save(w, out)
}

private fun save(w: DoubleArray, out: File) {
    out.parentFile?.mkdirs()
    out.writeText(Afterstate.names().zip(w.toList()).joinToString("\n") { (n, v) -> "$n=$v" } + "\n")
}

private fun occupied(b: List<String>, r: Int, c: Int) = c in b[r].indices && b[r][c] != Board.EMPTY

/** Plays one game between two named players and prints every move, then the final board. */
private fun show(names: List<String>, players: List<Player>, seed: Long) {
    val random = Random(seed)
    var state = Game.new(rules, 2, random)
    println("=== ${names[0]} contre ${names[1]} (graine $seed) ===")
    var turn = 1
    while (state.status == GameStatus.ACTIVE && turn < 200) {
        val seat = state.currentPlayer
        val rack = state.players[seat].rack
        val outcome = Game.apply(state, seat, players[seat](state, seat).action, lexicon.words, random)
        check(outcome is MoveOutcome.Applied) { "rejected: $outcome" }
        state = outcome.state
        val m = outcome.record
        val what = when (m.type) {
            MoveType.PLAY -> {
                val first = m.tiles.minWith(compareBy({ it.row }, { it.col }))
                val horizontal = m.tiles.all { it.row == first.row } && (m.tiles.size > 1 || occupied(state.board, first.row, first.col - 1) || occupied(state.board, first.row, first.col + 1))
                val pos = if (horizontal) "${'A' + first.row}${first.col + 1}" else "${first.col + 1}${'A' + first.row}"
                val bingo = if (m.tiles.size == rules.rackSize) " SCRABBLE" else ""
                "%-4s %s +%d%s".format(pos, m.words.joinToString(", ") { it.word }, m.score, bingo)
            }
            MoveType.EXCHANGE -> "échange ${m.exchangedCount} lettres"
            else -> m.type.name.lowercase()
        }
        val end = if (m.endAdjustments.isNotEmpty()) "  fin ${m.end} ${m.endAdjustments}" else ""
        println("%3d %-10s %-8s %-40s %3d – %d%s".format(turn, names[seat], rack, what, state.players[0].score, state.players[1].score, end))
        turn++
    }
    for (row in state.board) println("    " + row.toList().joinToString(" ") {
        it.let { if (it == Board.EMPTY) "·" else it.toString() }
    })
    println()
}

private fun load(file: File): DoubleArray = Afterstate.parseWeights(file.readText())

fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "baseline" -> {
            val n = args.getOrElse(1) { "400" }.toInt()
            match("Glouton contre Nono-bot débutant", n, ::greedy, ::beginner)
            match("Glouton vocabulaire courant contre Nono-bot débutant", n, ::greedyCommon, ::beginner)
            match("Glouton vocabulaire courant contre glouton", n, ::greedyCommon, ::greedy)
        }
        "train" -> train(args[1].toInt(), File(args[2]))
        "eval" -> {
            val w = load(File(args[1]))
            val n = args.getOrElse(2) { "1000" }.toInt()
            match("Bot entraîné contre glouton (même vocabulaire courant)", n, { r -> valueBot(w, r) }, ::greedyCommon)
            match("Bot entraîné contre Nono-bot débutant", n, { r -> valueBot(w, r) }, ::beginner)
        }
        "show" -> {
            val w = load(File(args[1]))
            val seed = args.getOrElse(2) { "1" }.toLong()
            val r = Random(seed)
            show(listOf("Belzé", "Nono"), listOf(valueBot(w, r), beginner(r)), seed)
            show(listOf("Belzé-1", "Belzé-2"), listOf(valueBot(w, r), valueBot(w, r)), seed + 1)
        }
        else -> println("usage: baseline [games] | train <games> <out> | eval <weights> [games]")
    }
}
