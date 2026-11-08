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
            allowSharedNicknames(st)
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

    /**
     * Databases from before shared nicknames: rebuilds the users table without the unique nickname, then gives
     * every player a tag. Foreign keys are off meanwhile, so the games keep pointing at the same ids.
     */
    private fun allowSharedNicknames(st: java.sql.Statement) {
        val sql = st.executeQuery("SELECT sql FROM sqlite_master WHERE name = 'users'").use { if (it.next()) it.getString(1) else "" }
        if ("UNIQUE COLLATE NOCASE" in sql) {
            st.execute("PRAGMA foreign_keys=OFF")
            st.execute("BEGIN")
            st.execute(SCHEMA.first().replace("IF NOT EXISTS users", "users_new"))
            st.execute("INSERT INTO users_new(id, nickname, token_hash, created_at) SELECT id, nickname, token_hash, created_at FROM users")
            st.execute("DROP TABLE users")
            st.execute("ALTER TABLE users_new RENAME TO users")
            st.execute("COMMIT")
            st.execute("PRAGMA foreign_keys=ON")
        }
        val untagged = st.executeQuery("SELECT id FROM users WHERE tag = 0 AND id NOT LIKE 'bot-%' AND token_hash NOT LIKE 'deleted:%'")
            .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        for (id in untagged) {
            connection.prepareStatement("UPDATE users SET tag = ? WHERE id = ?").use { u ->
                u.setInt(1, Tags.random(kotlin.random.Random))
                u.setString(2, id)
                u.executeUpdate()
            }
        }
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_nickname_tag ON users(nickname, tag) WHERE tag > 0")
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_google ON users(google_sub) WHERE google_sub IS NOT NULL")
        // Tokens from before sessions (a hex hash, no "kind:" prefix) become the account's first session.
        st.execute(
            "INSERT OR IGNORE INTO sessions(token_hash, user_id, created_at) " +
                "SELECT token_hash, id, created_at FROM users WHERE token_hash NOT LIKE '%:%'",
        )
        st.execute("UPDATE users SET token_hash = 'account:' || id WHERE token_hash NOT LIKE '%:%'")
    }

    private companion object {
        val SCHEMA = listOf(
            // Several players may share a nickname: the tag tells them apart ("greg#4821"). Robots and deleted
            // accounts have tag 0. token_hash: the account's state ("account:", "bot:", "released:" or "deleted:"
            // then its id), or the token of its single phone before sessions; the tokens are in sessions.
            """CREATE TABLE IF NOT EXISTS users (
                id TEXT PRIMARY KEY,
                nickname TEXT NOT NULL COLLATE NOCASE,
                token_hash TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                tag INTEGER NOT NULL DEFAULT 0,
                google_sub TEXT)""",
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
            // One per phone signed in (several with Google sign-in), by the hash of its token.
            """CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id),
                created_at INTEGER NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id)",
            // Turns where the player asked for hints (games against robots only), counted in their stats.
            """CREATE TABLE IF NOT EXISTS hints (
                game_id TEXT NOT NULL REFERENCES games(id),
                user_id TEXT NOT NULL REFERENCES users(id),
                move_number INTEGER NOT NULL,
                PRIMARY KEY (game_id, user_id, move_number))""",
            // user_id blocked blocked_id: they can no longer challenge each other nor be paired (both ways).
            """CREATE TABLE IF NOT EXISTS blocks (
                user_id TEXT NOT NULL REFERENCES users(id),
                blocked_id TEXT NOT NULL REFERENCES users(id),
                created_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, blocked_id))""",
            "CREATE INDEX IF NOT EXISTS blocks_blocked ON blocks(blocked_id)",
            // Reports, for the moderator (see README, "Modération"); the nickname as it was when reported.
            """CREATE TABLE IF NOT EXISTS reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                reporter_id TEXT NOT NULL,
                reported_id TEXT NOT NULL,
                reported_nickname TEXT NOT NULL,
                game_id TEXT,
                reason TEXT NOT NULL,
                details TEXT,
                created_at INTEGER NOT NULL)""",
        )
        val MIGRATIONS = listOf(
            "ALTER TABLE matchmaking ADD COLUMN language TEXT NOT NULL DEFAULT 'fr'",
            // google_sub: the Google account linked by "Sign in with Google" (its stable id), if any.
            "ALTER TABLE users ADD COLUMN google_sub TEXT",
            // The most points the turn could score (for the stats); -1: not counted; NULL: not computed yet.
            "ALTER TABLE moves ADD COLUMN best_score INTEGER",
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

/** The number after a shared nickname ("greg#4821"), 4 digits. */
object Tags {
    fun random(random: kotlin.random.Random) = random.nextInt(1000, 10000)

    /** "greg#4821" → ("greg", 4821); "greg" → ("greg", null). */
    fun parse(name: String): Pair<String, Int?> = name.substringBefore('#').trim() to name.substringAfter('#', "").trim().toIntOrNull()
}
