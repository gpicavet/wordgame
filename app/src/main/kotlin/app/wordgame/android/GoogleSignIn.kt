package app.wordgame.android

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** "Sign in with Google" is set up in this build (see GOOGLE_CLIENT_ID in app/build.gradle.kts). */
val googleSignInAvailable get() = BuildConfig.GOOGLE_CLIENT_ID.isNotEmpty()

/**
 * Asks Android for the player's Google account (the system sheet) and returns an ID token for the server, or null
 * when the player closes the sheet. [context] must be an activity.
 */
suspend fun googleIdToken(context: Context): String? {
    val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_CLIENT_ID).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = try {
        CredentialManager.create(context).getCredential(context, request).credential
    } catch (e: GetCredentialCancellationException) {
        return null
    }
    check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        Strings.get(R.string.google_unexpected)
    }
    return GoogleIdTokenCredential.createFrom(credential.data).idToken
}
