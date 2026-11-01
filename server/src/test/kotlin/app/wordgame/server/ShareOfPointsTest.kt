package app.wordgame.server

import app.wordgame.engine.Action
import app.wordgame.engine.Dawg
import app.wordgame.engine.Lexicon
import app.wordgame.engine.RuleSet
import app.wordgame.engine.Solver
import app.wordgame.protocol.CreateGameRequest
import app.wordgame.protocol.MoveRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareOfPointsTest {
    private val lexicon = Lexicon(Dawg.fromBytes(File("../dictionaries/bin/fr-libre.dawg").readBytes()))
    private val db = Database(File.createTempFile("wordgame", ".db").apply { deleteOnExit() }.path)
    private val service = GameService(db, mapOf("fr" to lexicon.words), lexicons = mapOf("fr" to lexicon))

    private suspend fun user(nickname: String) = service.register(nickname).let { User(it.userId, it.nickname, it.tag) }

    /** Alice's single turn ([best]: the best move, else a pass), then Bob resigns. */
    private suspend fun game(alice: User, bob: User, best: Boolean): String {
        val id = service.createGame(alice, CreateGameRequest(friends = listOf(bob.nickname))).id
        var view = service.view(bob, id)
        if (view.currentSeat == view.mySeat) service.move(bob, id, MoveRequest(view.moveNumber, Action.Pass))
        view = service.view(alice, id)
        val board = app.wordgame.engine.Board.fromRows(view.board)
        val move = Solver(lexicon, RuleSet.byId(view.rulesId)).bestMoves(board, view.rack, 1).first()
        service.move(alice, id, MoveRequest(view.moveNumber, if (best) Action.Play(move.tiles) else Action.Pass))
        service.move(bob, id, MoveRequest(service.view(bob, id).moveNumber, Action.Resign))
        return id
    }

    @Test
    fun shareOfPossiblePointsPerGameAndOverall() = runBlocking {
        val alice = user("alice")
        val bob = user("bob")
        game(alice, bob, best = true)
        game(alice, bob, best = false)
        val stats = service.stats(alice)
        assertEquals(listOf(1.0, 0.0), stats.history.map { it.share })
        assertEquals(true, stats.share!! in 0.01..0.99)
        // Bob only passed or resigned: nothing possible was counted against him but his passes.
        assertEquals(2, service.stats(bob).history.size)
    }

    @Test
    fun turnsPlayedBeforeTheBestScoresAreComputedAtStartup() = runBlocking {
        val alice = user("alice")
        val bob = user("bob")
        game(alice, bob, best = true)
        db.tx { update("UPDATE moves SET best_score = NULL") }
        assertNull(service.stats(alice).history.single().share)
        assertEquals(1, service.backfillBestScores())
        assertEquals(1.0, service.stats(alice).history.single().share)
        assertEquals(0, service.backfillBestScores())
    }
}
