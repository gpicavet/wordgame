package app.wordgame.server

import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseTest {
    @Test
    fun oldDatabaseGetsSharedNicknamesTagsAndSessions() = runBlocking {
        val file = File.createTempFile("wordgame-old", ".db").apply { deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE users (id TEXT PRIMARY KEY, nickname TEXT NOT NULL UNIQUE COLLATE NOCASE, token_hash TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL)")
                st.execute("CREATE TABLE games (id TEXT PRIMARY KEY, state TEXT NOT NULL, status TEXT NOT NULL, move_number INTEGER NOT NULL, deadline INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                st.execute("CREATE TABLE game_players (game_id TEXT NOT NULL REFERENCES games(id), seat INTEGER NOT NULL, user_id TEXT NOT NULL REFERENCES users(id), PRIMARY KEY (game_id, seat))")
                st.execute("INSERT INTO users VALUES ('u1', 'greg', 'h1', 1), ('bot-nono', 'NonoBot', 'bot:bot-nono', 1)")
                st.execute("INSERT INTO games VALUES ('g1', '{}', 'ACTIVE', 0, 0, 0, 0)")
                st.execute("INSERT INTO game_players VALUES ('g1', 0, 'u1'), ('g1', 1, 'bot-nono')")
            }
        }
        Database(file.path).use { db ->
            db.tx {
                val tags = query("SELECT id, tag FROM users ORDER BY id") { it.getString(1) to it.getInt(2) }.toMap()
                assertEquals(0, tags["bot-nono"])
                assertTrue(tags.getValue("u1") in 1000..9999)
                update("INSERT INTO users(id, nickname, token_hash, created_at, tag) VALUES ('u2', 'GREG', 'h2', 2, ?)", tags.getValue("u1") % 9000 + 1000)
                assertEquals(2, query("SELECT COUNT(*) FROM users WHERE nickname = 'greg'") { it.getInt(1) }.single())
                assertEquals(2, query("SELECT COUNT(*) FROM game_players p JOIN users u ON u.id = p.user_id") { it.getInt(1) }.single())
                assertTrue(query("PRAGMA foreign_key_check") { true }.isEmpty())
                // The old token is now the account's first session.
                assertEquals(listOf("u1"), query("SELECT user_id FROM sessions WHERE token_hash = 'h1'") { it.getString(1) })
                assertEquals("account:u1", query("SELECT token_hash FROM users WHERE id = 'u1'") { it.getString(1) }.single())
            }
        }
        // Opening it again changes nothing.
        Database(file.path).use { db ->
            db.tx { assertEquals(3, query("SELECT COUNT(*) FROM users") { it.getInt(1) }.single()) }
        }
    }
}
