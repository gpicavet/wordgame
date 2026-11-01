package app.wordgame.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegalPagesTest {
    @Test
    fun deletionPageOffersGoogleOnlyWhenSetUp() {
        val withGoogle = accountDeletionPage("client-id.apps.googleusercontent.com")
        assertTrue("data-client_id=\"client-id.apps.googleusercontent.com\"" in withGoogle)
        assertFalse("accounts.google.com/gsi" in accountDeletionPage(null))
    }
}
