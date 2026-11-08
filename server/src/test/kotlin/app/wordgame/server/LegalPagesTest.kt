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

    @Test
    fun englishPagesLinkToTheFrenchOnesAndBack() {
        val privacy = privacyPageEn("contact@example.org")
        assertTrue("<html lang=\"en\">" in privacy && "href=\"/confidentialite\"" in privacy && "contact@example.org" in privacy)
        assertTrue("href=\"/privacy\"" in privacyPage("contact@example.org"))
        val deletion = accountDeletionPageEn("client-id.apps.googleusercontent.com")
        assertTrue("Permanently delete this account" in deletion && "href=\"/supprimer-compte\"" in deletion)
        assertTrue("href=\"/delete-account\"" in accountDeletionPage(null))
    }
}
