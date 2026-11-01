package app.wordgame.server

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI

/** Checks an ID token from "Sign in with Google": the Google account's stable id ("sub"), or null when invalid. */
fun interface GoogleTokens {
    fun subject(idToken: String): String?
}

/**
 * The real check: signed by Google (keys fetched from Google and cached), issued for our [clientId] (the "Web"
 * OAuth client of the Google Cloud project, which the app passes as serverClientId), not expired.
 */
class GoogleIdTokens(clientId: String) : GoogleTokens {
    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        val keys = JWKSourceBuilder.create<SecurityContext>(URI(CERTS).toURL()).build()
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, keys)
        jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(clientId, null, setOf("sub", "iss", "exp"))
    }

    override fun subject(idToken: String): String? = runCatching {
        val claims = processor.process(idToken, null)
        claims.subject.takeIf { claims.issuer in ISSUERS }
    }.getOrNull()

    private companion object {
        const val CERTS = "https://www.googleapis.com/oauth2/v3/certs"
        val ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
    }
}
