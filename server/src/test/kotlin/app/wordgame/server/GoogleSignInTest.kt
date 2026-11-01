package app.wordgame.server

import kotlin.test.Test
import kotlin.test.assertNull

class GoogleSignInTest {
    @Test
    fun malformedTokensAreRefusedWithoutAsking() {
        val tokens = GoogleIdTokens("client.apps.googleusercontent.com")
        assertNull(tokens.subject("not-a-jwt"))
        // A token without a signature never reaches the key check.
        assertNull(tokens.subject("eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0."))
    }
}
