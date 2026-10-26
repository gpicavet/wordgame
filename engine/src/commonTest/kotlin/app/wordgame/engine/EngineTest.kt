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

class ClassicRulesTest {
    private val rules = RuleSet.CLASSIC
    private val layout = rules.layout

    @Test
    fun standardBagAndBonus() {
        assertEquals(102, rules.fullBag().length)
        assertEquals(15, rules.fullBag().count { it == 'E' })
        assertEquals(10, rules.points('Z'))
        assertEquals(50, rules.bingoBonus)
    }

    @Test
    fun englishBagOnTheSameBoard() {
        val english = RuleSet.defaultFor("en")
        assertEquals("en", english.language)
        assertEquals(100, english.fullBag().length)
        assertEquals(12, english.fullBag().count { it == 'E' })
        assertEquals(10, english.points('Q'))
        assertEquals(5, english.points('K'))
        assertEquals(layout.premiumAt(1, 5), english.layout.premiumAt(1, 5))
        assertEquals(english, RuleSet.byId("en-classic"))
    }

    @Test
    fun standardPremiumLayout() {
        assertEquals(Premium.DOUBLE_WORD, layout.premiumAt(7, 7))
        listOf(0 to 0, 0 to 7, 0 to 14, 7 to 0, 7 to 14, 14 to 0, 14 to 7, 14 to 14)
            .forEach { (r, c) -> assertEquals(Premium.TRIPLE_WORD, layout.premiumAt(r, c), "$r,$c") }
        listOf(1 to 5, 1 to 9, 5 to 1, 5 to 5, 5 to 9, 5 to 13, 9 to 1, 9 to 5, 9 to 9, 9 to 13, 13 to 5, 13 to 9)
            .forEach { (r, c) -> assertEquals(Premium.TRIPLE_LETTER, layout.premiumAt(r, c), "$r,$c") }
        listOf(0 to 3, 2 to 6, 3 to 0, 3 to 7, 6 to 2, 6 to 6, 7 to 3, 7 to 11, 8 to 8, 11 to 14, 12 to 6, 14 to 11)
            .forEach { (r, c) -> assertEquals(Premium.DOUBLE_LETTER, layout.premiumAt(r, c), "$r,$c") }
        val counts = (0 until 15).flatMap { r -> (0 until 15).map { c -> layout.premiumAt(r, c) } }.groupingBy { it }.eachCount()
        assertEquals(8, counts[Premium.TRIPLE_WORD])
        assertEquals(17, counts[Premium.DOUBLE_WORD])
        assertEquals(12, counts[Premium.TRIPLE_LETTER])
        assertEquals(24, counts[Premium.DOUBLE_LETTER])
    }

    @Test
    fun firstWordOnCenterIsDoubledAndSevenTilesGetFifty() {
        val c = layout.center
        // CHAT on (7,7)..(7,10): C on the center (double word), no other premium -> (3+4+1+1) * 2.
        val chat = "CHAT".mapIndexed { i, ch -> Placement(c, c + i, ch) }
        val result = MoveEvaluator.evaluate(Board.empty(15), chat, rules, WordList { true })
        assertIs<Evaluation.Valid>(result)
        assertEquals((3 + 4 + 1 + 1) * 2, result.score)

        // 7 tiles from (7,4): ENTRAIT, (7,4) is not a premium square, (7,7) doubles the word.
        val seven = "ENTRAIT".mapIndexed { i, ch -> Placement(c, 4 + i, ch) }
        val bingo = MoveEvaluator.evaluate(Board.empty(15), seven, rules, WordList { true })
        assertIs<Evaluation.Valid>(bingo)
        assertTrue(bingo.bingo)
        assertEquals(7 * 2 + 50, bingo.score)
    }
}

class EndOfGameTest {
    private val rules = RuleSet.CLASSIC
    private val anyWord = WordList { true }
    private val c = rules.layout.center

    @Test
    fun goingOutWinsTheOtherRackAndRecordsTheBonus() {
        // Bag empty, seat 0 plays its last two tiles; seat 1 keeps Z (10) + E (1).
        val state = Game.new(rules, 2, Random(1)).copy(
            bag = "",
            players = listOf(PlayerState(100, "LA"), PlayerState(90, "ZE")),
        )
        val outcome = Game.apply(state, 0, Action.Play(listOf(Placement(c, c, 'L'), Placement(c, c + 1, 'A'))), anyWord, Random(2))
        assertIs<MoveOutcome.Applied>(outcome)
        assertEquals(GameEnd.OUT, outcome.record.end)
        assertEquals(listOf(11, -11), outcome.record.endAdjustments)
        assertEquals(100 + outcome.record.score + 11, outcome.state.players[0].score)
        assertEquals(90 - 11, outcome.state.players[1].score)
        assertEquals(GameStatus.FINISHED, outcome.state.status)
    }

    @Test
    fun aRoundOfPassesWithASmallBagBlocksTheGame() {
        // 3 tiles left in the bag: nobody can exchange.
        var state = Game.new(rules, 2, Random(1)).copy(
            bag = "AEI",
            players = listOf(PlayerState(50, "KZ"), PlayerState(60, "E")),
        )
        state = (Game.apply(state, 0, Action.Pass, anyWord, Random(2)) as MoveOutcome.Applied).state
        assertEquals(GameStatus.ACTIVE, state.status)
        val outcome = Game.apply(state, 1, Action.Pass, anyWord, Random(2))
        assertIs<MoveOutcome.Applied>(outcome)
        assertEquals(GameEnd.BLOCKED, outcome.record.end)
        assertEquals(listOf(-20, -1), outcome.record.endAdjustments)
        assertEquals(listOf(30, 59), outcome.state.players.map { it.score })
        assertEquals(1, outcome.state.winner)
    }

    @Test
    fun passesWithAFullBagDoNotBlock() {
        var state = Game.new(rules, 2, Random(1))
        repeat(5) { state = (Game.apply(state, state.currentPlayer, Action.Pass, anyWord, Random(2)) as MoveOutcome.Applied).state }
        assertEquals(GameStatus.ACTIVE, state.status)
        val outcome = Game.apply(state, state.currentPlayer, Action.Pass, anyWord, Random(2))
        assertIs<MoveOutcome.Applied>(outcome)
        assertEquals(GameEnd.SCORELESS, outcome.record.end)
    }

    @Test
    fun resigningInAThreePlayerGameEliminatesThePlayer() {
        val state = Game.new(rules, 3, Random(1))
        val bag = state.bag.length
        val rack = state.players[0].rack
        val after = (Game.apply(state, 0, Action.Resign, anyWord, Random(2)) as MoveOutcome.Applied).state
        assertEquals(GameStatus.ACTIVE, after.status)
        assertTrue(after.players[0].eliminated)
        assertEquals("", after.players[0].rack)
        assertEquals(bag + rack.length, after.bag.length)
        assertEquals(1, after.currentPlayer)
        // Turns skip the eliminated seat.
        val s2 = (Game.apply(after, 1, Action.Pass, anyWord, Random(3)) as MoveOutcome.Applied).state
        assertEquals(2, s2.currentPlayer)
        val s3 = (Game.apply(s2, 2, Action.Pass, anyWord, Random(3)) as MoveOutcome.Applied).state
        assertEquals(1, s3.currentPlayer)
        // The last opponent standing wins, even with fewer points.
        val ahead = s3.copy(players = s3.players.mapIndexed { i, p -> if (i == 2) p.copy(score = 50) else p })
        val end = (Game.apply(ahead, 2, Action.Resign, anyWord, Random(4)) as MoveOutcome.Applied).state
        assertEquals(GameStatus.FINISHED, end.status)
        assertEquals(1, end.winner)
    }
}

class ExtraWordsTest {
    @Test
    fun parsesAccentsCommentsAndSeparators() {
        val extra = ExtraWords.parse("# comment\nBÊ ÇA, HÉ\n  NÔ  # trailing\nvs\n")
        assertEquals(setOf("BE", "CA", "HE", "NO", "VS"), extra.words)
    }

    @Test
    fun unionAcceptsWordsFromBothLists() {
        val words = WordList { it == "CHAT" } + ExtraWords.parse("OM TO")
        assertTrue(words.contains("CHAT"))
        assertTrue(words.contains("OM"))
        assertFalse(words.contains("XYZ"))
    }
}
