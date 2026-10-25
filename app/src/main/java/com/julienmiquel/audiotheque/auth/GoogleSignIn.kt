package com.julienmiquel.audiotheque.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** Identité obtenue par Credential Manager (« Se connecter avec Google »). */
data class GoogleIdentity(val email: String, val displayName: String?)

sealed interface SignInOutcome {
    data class Success(val identity: GoogleIdentity) : SignInOutcome

    /** Aucun ID client web configuré : le compte sera choisi à l'écran d'autorisation Drive. */
    data object NotConfigured : SignInOutcome

    data object Cancelled : SignInOutcome

    data class Failed(val message: String) : SignInOutcome
}

/** Connexion avec Credential Manager (bouton « Se connecter avec Google »). */
object GoogleSignIn {

    suspend fun signIn(activity: Activity, webClientId: String): SignInOutcome {
        if (webClientId.isBlank()) return SignInOutcome.NotConfigured
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                SignInOutcome.Success(GoogleIdentity(google.id, google.displayName))
            } else {
                SignInOutcome.Failed("Type d'identifiant inattendu")
            }
        } catch (e: GetCredentialCancellationException) {
            SignInOutcome.Cancelled
        } catch (e: GetCredentialException) {
            SignInOutcome.Failed(e.message ?: e.type)
        }
    }
}
