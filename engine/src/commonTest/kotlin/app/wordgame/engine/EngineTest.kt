package app.wordgame.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DawgTest {
    private val words = listOf("BAS", "BASE", "BASES", "CAS", "CASE", "CASES", "LA", "LAS", "OS", "ZA")

    @Test
    fun containsExactlyTheAddedWords() {
        val builder = DawgBuilder()
        words.forEach(builder::add)
        val dawg = Dawg.fromBytes(builder.build().toBytes())

        words.forEach { assertTrue(dawg.contains(it), it) }
        listOf("", "B", "BA", "BASESS", "CA", "Z", "ZAS", "ABC", "la").forEach { assertFalse(dawg.contains(it), it) }
    }

    @Test
    fun sharesSuffixes() {
        val builder = DawgBuilder()
        listOf("BASES", "CASES").forEach(builder::add)
        // root(B,C) + A + S + E + S: the suffix "ASES" is stored once.
        assertEquals(1 + 2 + 1 + 1 + 1 + 1, builder.build().edgeCount)
    }
}

class MoveEvaluatorTest {
    private val rules = RuleSet.FRENCH
    private val dict = WordList { it in setOf("CHAT", "CHATS", "AS", "TA", "SA", "ET") }
    private val c = rules.layout.center

    private fun word(row: Int, col: Int, text: String, horizontal: Boolean = true) =
        text.mapIndexed { i, ch -> Placement(if (horizontal) row else row + i, if (horizontal) col + i else col, ch) }

    @Test
    fun firstMoveMustCoverCenter() {
        val result = MoveEvaluator.evaluate(Board.empty(15), word(0, 0, "CHAT"), rules, dict)
        assertEquals(Evaluation.Invalid(InvalidReason.FIRST_MOVE_NOT_ON_CENTER), result)
    }

    @Test
    fun scoresFirstMoveWithPremiums() {
        val placements = word(c, c - 1, "CHAT")
        val result = MoveEvaluator.evaluate(Board.empty(15), placements, rules, dict)
        assertIs<Evaluation.Valid>(result)
        val expected = placements.sumOf { p ->
            val pts = rules.points(p.letter)
            when (rules.layout.premiumAt(p.row, p.col)) {
                Premium.DOUBLE_LETTER -> pts * 2
                Premium.TRIPLE_LETTER -> pts * 3
                else -> pts
            }
        } * placements.fold(1) { m, p ->
            m * when (rules.layout.premiumAt(p.row, p.col)) {
                Premium.DOUBLE_WORD -> 2
                Premium.TRIPLE_WORD -> 3
                else -> 1
            }
        }
        assertEquals(expected, result.score)
        assertEquals(listOf("CHAT"), result.words.map { it.word })
    }

    @Test
    fun rejectsUnknownWord() {
        val result = MoveEvaluator.evaluate(Board.empty(15), word(c, c, "TAC"), rules, dict)
        assertEquals(Evaluation.Invalid(InvalidReason.UNKNOWN_WORDS, listOf("TAC")), result)
    }

    @Test
    fun rejectsGapsAndDiagonals() {
        val gap = listOf(Placement(c, c, 'T'), Placement(c, c + 2, 'A'))
        assertEquals(Evaluation.Invalid(InvalidReason.GAP), MoveEvaluator.evaluate(Board.empty(15), gap, rules, dict))
        val diagonal = listOf(Placement(c, c, 'T'), Placement(c + 1, c + 1, 'A'))
        assertEquals(Evaluation.Invalid(InvalidReason.NOT_IN_LINE), MoveEvaluator.evaluate(Board.empty(15), diagonal, rules, dict))
    }

    @Test
    fun extendsExistingWordAndCountsCrossWords() {
        val board = Board.empty(15).with(word(c, c - 1, "CHAT"))
        // Add S after CHAT -> CHATS, existing letters count without premium.
        val result = MoveEvaluator.evaluate(board, listOf(Placement(c, c + 3, 'S')), rules, dict)
        assertIs<Evaluation.Valid>(result)
        assertEquals(listOf("CHATS"), result.words.map { it.word })

        // "AS" played vertically under the A of CHAT forms only AS (A already on board).
        val down = MoveEvaluator.evaluate(board, listOf(Placement(c + 1, c + 1, 'S')), rules, dict)
        assertIs<Evaluation.Valid>(down)
        assertEquals(listOf("AS"), down.words.map { it.word })
    }

    @Test
    fun rejectsDisconnectedMove() {
        val board = Board.empty(15).with(word(c, c - 1, "CHAT"))
        val result = MoveEvaluator.evaluate(board, word(0, 0, "ET"), rules, dict)
        assertEquals(Evaluation.Invalid(InvalidReason.NOT_CONNECTED), result)
    }

    @Test
    fun blankScoresZero() {
        val placements = listOf(Placement(c, c, 'E', blank = true), Placement(c, c + 1, 'T'))
        val result = MoveEvaluator.evaluate(Board.empty(15), placements, rules, dict)
        assertIs<Evaluation.Valid>(result)
        val tPoints = rules.points('T') * when (rules.layout.premiumAt(c, c + 1)) {
            Premium.DOUBLE_LETTER -> 2; Premium.TRIPLE_LETTER -> 3; else -> 1
        }
        assertEquals(tPoints, result.score)
    }
}

class GameTest {
    private val rules = RuleSet.FRENCH
    private val anyWord = WordList { true }

    @Test
    fun newGameDealsRacksFromAFullBag() {
        val state = Game.new(rules, 2, Random(1))
        assertEquals(104, rules.fullBag().length)
        assertEquals(listOf(7, 7), state.players.map { it.rack.length })
        assertEquals(104 - 14, state.bag.length)
    }

    @Test
    fun playUsesRackTilesAndRefills() {
        val state = Game.new(rules, 2, Random(1)).let { it.copy(players = listOf(PlayerState(0, "CHATXYZ"), it.players[1])) }
        val c = rules.layout.center
        val tiles = "CHAT".mapIndexed { i, ch -> Placement(c, c + i, ch) }
        val outcome = Game.apply(state, 0, Action.Play(tiles), anyWord, Random(2))
        assertIs<MoveOutcome.Applied>(outcome)
        assertEquals(7, outcome.state.players[0].rack.length)
        assertTrue(outcome.state.players[0].rack.startsWith("XYZ"))
        assertEquals(1, outcome.state.currentPlayer)
        assertEquals(state.bag.length - 4, outcome.state.bag.length)
    }

    @Test
    fun rejectsTilesNotInRackAndWrongTurn() {
        val state = Game.new(rules, 2, Random(1)).let { it.copy(players = listOf(PlayerState(0, "AAAAAAA"), it.players[1])) }
        val c = rules.layout.center
        val tiles = listOf(Placement(c, c, 'Z'), Placement(c, c + 1, 'A'))
        assertEquals(MoveOutcome.Rejected(MoveError.TILES_NOT_IN_RACK), Game.apply(state, 0, Action.Play(tiles), anyWord, Random(2)))
        assertEquals(MoveOutcome.Rejected(MoveError.NOT_YOUR_TURN), Game.apply(state, 1, Action.Pass, anyWord, Random(2)))
    }

    @Test
    fun sixPassesEndTheGame() {
        var state = Game.new(rules, 2, Random(1))
        repeat(6) {
            val outcome = Game.apply(state, state.currentPlayer, Action.Pass, anyWord, Random(2))
            assertIs<MoveOutcome.Applied>(outcome)
            state = outcome.state
        }
        assertEquals(GameStatus.FINISHED, state.status)
        assertTrue(state.players.all { it.score <= 0 })
    }

    @Test
    fun resignGivesTheWinToTheOpponent() {
        val state = Game.new(rules, 2, Random(1))
        val outcome = Game.apply(state, 1, Action.Resign, anyWord, Random(2))
        assertIs<MoveOutcome.Applied>(outcome)
        assertEquals(0, outcome.state.winner)
    }

    @Test
    fun exchangeKeepsTileCount() {
        val state = Game.new(rules, 2, Random(1))
        val rack = state.players[0].rack
        val outcome = Game.apply(state, 0, Action.Exchange(rack.take(3)), anyWord, Random(2))
        assertIs<MoveOutcome.Applied>(outcome)
        assertEquals(7, outcome.state.players[0].rack.length)
        assertEquals(state.bag.length, outcome.state.bag.length)
        assertNull(outcome.state.winner)
    }
}
