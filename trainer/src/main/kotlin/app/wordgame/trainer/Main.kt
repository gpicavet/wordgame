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
import app.wordgame.engine.Mlp
import app.wordgame.engine.MoveOutcome
import app.wordgame.engine.MoveRecord
import app.wordgame.engine.MoveType
import app.wordgame.engine.NeuralBot
import app.wordgame.engine.RawAfterstate
import app.wordgame.engine.RuleSet
import app.wordgame.engine.ValueBot
import app.wordgame.engine.bingoAllowed
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
/** Language of the games ("fr" or "en"), chosen with --language=… before anything is loaded. */
private var language = "fr"
private val rules by lazy { RuleSet.defaultFor(language) }
private val lexicon by lazy {
    Lexicon(Dawg.fromBytes(File(if (language == "en") "dictionaries/bin/en-enable.dawg" else "dictionaries/bin/fr-libre.dawg").readBytes()))
}
private val common by lazy { Dawg.fromBytes(File("dictionaries/bin/$language-courant.dawg").readBytes()) }

/** Chooses a move from the state, the seat and the moves played so far. */
private typealias Player = (GameState, Int, List<MoveRecord>) -> Decision

/**
 * Training sample: afterstate features and the score difference that followed it until the end ([target]),
 * with the seat that played and the score difference right after its move ([after]), for TD(λ).
 */
private class Sample(val x: DoubleArray, val target: Double, val seat: Int = 0, val after: Int = 0)

private class Result(val scores: List<Int>, val samples: List<Sample>, val bingos: List<Int>)

/** Plays one game; seat i is played by players[i]. */
private fun play(players: List<Player>, random: Random, record: Boolean): Result {
    var state = Game.new(rules, 2, random)
    // (seat, features, score difference for that seat right after the move)
    val trace = ArrayList<Triple<Int, DoubleArray, Int>>()
    var turns = 0
    val bingos = IntArray(2)
    val history = ArrayList<MoveRecord>()
    while (state.status == GameStatus.ACTIVE && turns < 200) {
        val seat = state.currentPlayer
        val d = players[seat](state, seat, history)
        val outcome = Game.apply(state, seat, d.action, lexicon.words, random)
        check(outcome is MoveOutcome.Applied) { "rejected: $outcome" }
        state = outcome.state
        history += outcome.record
        if (outcome.record.tiles.size == rules.rackSize) bingos[seat]++
        if (record && d.features != null) trace += Triple(seat, d.features!!, diff(state, seat))
        turns++
    }
    val samples = trace.map { (seat, x, after) -> Sample(x, (diff(state, seat) - after).toDouble(), seat, after) }
    return Result(state.players.map { it.score }, samples, bingos.toList())
}

private fun diff(state: GameState, seat: Int) = state.players[seat].score - state.players[1 - seat].score

private fun greedy(random: Random): Player {
    val bot = ValueBot(lexicon, DoubleArray(Afterstate.size), random)
    return { s, seat, _ -> bot.decide(s, seat) }
}

/** [heldBack]: scrabbles only played sometimes, as by Belzé-bot in the app (see [bingoAllowed]). */
private fun valueBot(weights: DoubleArray, random: Random, epsilon: Double = 0.0, heldBack: Boolean = false): Player {
    val bot = ValueBot(lexicon, weights, random, epsilon = epsilon, vocabulary = common)
    return { s, seat, history -> bot.decide(s, seat, !heldBack || bingoAllowed(history, seat, random)) }
}

/** Belzé-bot as in the app: learned weights, common vocabulary, scrabbles held back. */
private fun belze(weights: DoubleArray, random: Random): Player {
    val bot = ValueBot(lexicon, weights, random, vocabulary = common)
    return { s, seat, history -> bot.decide(s, seat, bingoAllowed(history, seat, random)) }
}

private fun greedyCommon(random: Random): Player {
    val bot = ValueBot(lexicon, DoubleArray(Afterstate.size), random, vocabulary = common)
    return { s, seat, _ -> bot.decide(s, seat) }
}

private fun beginner(random: Random): Player {
    val bot = BeginnerBot(lexicon, common, random)
    return { s, seat, _ -> Decision(bot.play(s, seat), 0, null) }
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
                play(listOf(valueBot(snapshot, random, 0.05, heldBack = true), valueBot(snapshot, random, 0.05, heldBack = true)), random, record = true)
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

private fun neural(net: Mlp, random: Random, epsilon: Double = 0.0): Player {
    val bot = NeuralBot(lexicon, net, random, epsilon = epsilon, vocabulary = common)
    return { s, seat, history -> bot.decide(s, seat, bingoAllowed(history, seat, random)) }
}

/**
 * Same self-play Monte Carlo loop as [train], with the network of [NeuralBot]: each lot of games is played
 * with a frozen copy, then the network takes Adam steps on the new afterstates (each under a random symmetry).
 * The error on those afterstates is measured before training on them, split between midgame and endgame.
 */
/**
 * λ-returns of one game (TD(λ)): for each seat, in move order, the target of an afterstate mixes what the
 * next one of that seat is worth for [net] with what actually followed; λ = 1 is the Monte Carlo target.
 *   G(t) = Δ(t→t+1) + (1 − λ)·V(t+1) + λ·G(t+1), and G(last) = final difference − difference after the move.
 */
private fun lambdaReturns(samples: List<Sample>, net: Mlp, lambda: Double): List<Double> {
    if (lambda >= 1.0) return samples.map { it.target }
    val targets = DoubleArray(samples.size)
    for (seat in samples.map { it.seat }.distinct()) {
        val own = samples.indices.filter { samples[it].seat == seat }
        var g = 0.0
        for (k in own.indices.reversed()) {
            val s = samples[own[k]]
            g = if (k == own.lastIndex) s.target else {
                val next = samples[own[k + 1]]
                (next.after - s.after) + (1 - lambda) * net.evaluate(next.x) + lambda * g
            }
            targets[own[k]] = g
        }
    }
    return targets.toList()
}

private fun trainNet(games: Int, out: File, resume: File?, lambda: Double = 1.0) = runBlocking(Dispatchers.Default) {
    val net = resume?.let { Mlp.fromBytes(it.readBytes()) } ?: Mlp.random(intArrayOf(RawAfterstate.SIZE, 128, 64, 1), 50.0, Random(42))
    val trainer = AdamTrainer(net)
    val random = Random(7)
    val lot = Runtime.getRuntime().availableProcessors()
    var played = 0
    val err = DoubleArray(4) // midgame sum, count, endgame sum, count
    var trainErr = 0.0
    var lots = 0
    val start = System.currentTimeMillis()
    while (played < games) {
        val snapshot = net.copy()
        val results = (0 until lot).map { i ->
            async {
                val r = Random(1_000_000_000L + played + i)
                play(listOf(neural(snapshot, r, 0.05), neural(snapshot, r, 0.05)), r, record = true)
            }
        }.awaitAll()
        val samples = results.flatMap { it.samples }
        val targets = results.flatMap { lambdaReturns(it.samples, snapshot, lambda) }
        for (s in samples) {
            val k = if (isEndgame(s.x)) 2 else 0
            err[k] += kotlin.math.abs(snapshot.evaluate(s.x) - s.target); err[k + 1]++
        }
        trainer.learningRate = if (played < games * 2 / 3) 1e-3 else 3e-4
        trainErr += trainOn(trainer, samples.map { it.x }, targets, random)
        lots++
        played += lot
        if (lots % 50 == 0 || played >= games) {
            println("%7d parties · erreur %.1f pts (milieu %.1f, fin %.1f) · %.0f s".format(
                played, trainErr / lots, err[0] / err[1], err[2] / err[3], (System.currentTimeMillis() - start) / 1000.0))
            err.fill(0.0); trainErr = 0.0; lots = 0
            out.parentFile?.mkdirs()
            out.writeBytes(net.toBytes())
        }
    }
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
    val history = ArrayList<MoveRecord>()
    while (state.status == GameStatus.ACTIVE && turn < 200) {
        val seat = state.currentPlayer
        val rack = state.players[seat].rack
        val outcome = Game.apply(state, seat, players[seat](state, seat, history).action, lexicon.words, random)
        check(outcome is MoveOutcome.Applied) { "rejected: $outcome" }
        state = outcome.state
        val m = outcome.record
        history += m
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

fun main(arguments: Array<String>) {
    // --language=en anywhere: games in English (rules, dictionary, everyday words).
    arguments.firstOrNull { it.startsWith("--language=") }?.let { language = it.substringAfter('=') }
    val args = arguments.filterNot { it.startsWith("--language=") }.toTypedArray()
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
            match("Belzé-bot (scrabbles freinés) contre Nono-bot débutant", n, { r -> belze(w, r) }, ::beginner)
            match("Belzé-bot (scrabbles freinés) contre glouton", n, { r -> belze(w, r) }, ::greedyCommon)
            args.getOrNull(3)?.let { other ->
                val previous = load(File(other))
                match("Belzé-bot contre Belzé-bot $other", n, { r -> belze(w, r) }, { r -> belze(previous, r) })
            }
        }
        "show" -> {
            val w = load(File(args[1]))
            val seed = args.getOrElse(2) { "1" }.toLong()
            val r = Random(seed)
            show(listOf("Belzé", "Nono"), listOf(belze(w, r), beginner(r)), seed)
            show(listOf("Belzé-1", "Belzé-2"), listOf(belze(w, r), belze(w, r)), seed + 1)
        }
        // trainnet <games> <out> [--lambda=0.7] [--resume=net.bin]
        "trainnet" -> {
            val options = args.drop(3).associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=') }
            trainNet(args[1].toInt(), File(args[2]), options["resume"]?.let(::File), options["lambda"]?.toDouble() ?: 1.0)
        }
        "evalnet" -> {
            val net = Mlp.fromBytes(File(args[1]).readBytes())
            val w = load(File(args.getOrElse(3) { "models/value-v2.txt" }))
            val n = args.getOrElse(2) { "400" }.toInt()
            match("Réseau contre Belzé-bot", n, { r -> neural(net, r) }, { r -> belze(w, r) })
            match("Réseau contre Nono-bot débutant", n, { r -> neural(net, r) }, ::beginner)
            args.getOrNull(4)?.let { other ->
                val previous = Mlp.fromBytes(File(other).readBytes())
                match("Réseau contre $other", n, { r -> neural(net, r) }, { r -> neural(previous, r) })
            }
        }
        else -> println("usage: [--language=en] baseline [games] | train <games> <out> | eval <weights> [games] [other weights] | trainnet <games> <out.bin> [--lambda=x] [--resume=net.bin] | evalnet <net.bin> [games] [belze weights] [other net.bin]")
    }
}
