package app.wordgame.server

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * One SQLite connection used from a single thread: no pool, no locking issues, and plenty
 * for turn-based traffic on one vCPU. Every call runs inside a transaction.
 */
class Database(path: String) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.execute("PRAGMA busy_timeout=5000")
            SCHEMA.forEach(st::execute)
            // Columns added after the first release: fail harmlessly once they exist.
            MIGRATIONS.forEach { runCatching { st.execute(it) } }
        }
        connection.autoCommit = false
    }

    suspend fun <T> tx(block: Connection.() -> T): T = withContext(dispatcher) {
        try {
            connection.block().also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        }
    }

    override fun close() = connection.close()

    private companion object {
        val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                nickname TEXT NOT NULL UNIQUE COLLATE NOCASE,
                token_hash TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS games (
                id TEXT PRIMARY KEY,
                state TEXT NOT NULL,
                status TEXT NOT NULL,
                move_number INTEGER NOT NULL,
                deadline INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS games_active_deadline ON games(status, deadline)",
            """CREATE TABLE IF NOT EXISTS game_players (
                game_id TEXT NOT NULL REFERENCES games(id),
                seat INTEGER NOT NULL,
                user_id TEXT NOT NULL REFERENCES users(id),
                PRIMARY KEY (game_id, seat))""",
            "CREATE INDEX IF NOT EXISTS game_players_user ON game_players(user_id)",
            """CREATE TABLE IF NOT EXISTS moves (
                game_id TEXT NOT NULL REFERENCES games(id),
                move_number INTEGER NOT NULL,
                record TEXT NOT NULL,
                played_at INTEGER NOT NULL,
                PRIMARY KEY (game_id, move_number))""",
            """CREATE TABLE IF NOT EXISTS devices (
                token TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id),
                updated_at INTEGER NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS devices_user ON devices(user_id)",
            """CREATE TABLE IF NOT EXISTS matchmaking (
                user_id TEXT PRIMARY KEY REFERENCES users(id),
                queued_at INTEGER NOT NULL)""",
        )
        val MIGRATIONS = listOf(
            "ALTER TABLE matchmaking ADD COLUMN language TEXT NOT NULL DEFAULT 'fr'",
        )
    }
}

fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { it.bind(params).executeUpdate() }

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(params).executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

private fun PreparedStatement.bind(params: Array<out Any?>): PreparedStatement {
    params.forEachIndexed { i, p -> setObject(i + 1, p) }
    return this
}
