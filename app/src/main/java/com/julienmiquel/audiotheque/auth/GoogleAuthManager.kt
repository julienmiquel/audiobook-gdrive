package com.julienmiquel.audiotheque.auth

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.julienmiquel.audiotheque.BuildConfig
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.sync.drive.AccessTokenProvider
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Résultat d'une demande d'autorisation Drive. */
sealed interface DriveAuthorization {
    data class Granted(val accessToken: String, val email: String?) : DriveAuthorization

    /** L'utilisateur doit choisir son compte et accepter les autorisations. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuthorization
}

/**
 * Accès Google : Credential Manager pour la connexion (si un ID client web est configuré),
 * AuthorizationClient pour le consentement Drive. Deux autorisations : « drive », nécessaire
 * pour déplacer des dossiers que l'app n'a pas créés, et « drive.appdata » pour la progression.
 */
class GoogleAuthManager(
    private val context: Context,
    private val settings: SettingsRepository,
) : AccessTokenProvider {

    private val authorizationClient get() = Identity.getAuthorizationClient(context)
    private val mutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAt = 0L

    val webClientId: String get() = BuildConfig.WEB_CLIENT_ID

    private fun request(email: String?): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES.map { Scope(it) })
            .apply { if (!email.isNullOrBlank()) setAccount(Account(email, ACCOUNT_TYPE)) }
            .build()

    /** Demande les autorisations Drive (silencieuse si elles ont déjà été accordées). */
    suspend fun authorize(email: String?): DriveAuthorization {
        val result = authorizationClient.authorize(request(email)).await()
        return toAuthorization(result)
    }

    /** Résultat de l'écran de consentement. */
    fun consentResult(data: Intent?): DriveAuthorization {
        val result = authorizationClient.getAuthorizationResultFromIntent(data)
        return toAuthorization(result)
    }

    @Suppress("DEPRECATION")
    private fun toAuthorization(result: AuthorizationResult): DriveAuthorization {
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) return DriveAuthorization.NeedsConsent(pending)
        val token = result.accessToken ?: throw DriveAuthException("Jeton Google Drive absent")
        cache(token)
        return DriveAuthorization.Granted(token, result.toGoogleSignInAccount()?.email)
    }

    private fun cache(token: String) {
        cachedToken = token
        tokenExpiresAt = System.currentTimeMillis() + TOKEN_LIFETIME_MS
    }

    override suspend fun accessToken(): String = mutex.withLock {
        cachedToken?.takeIf { System.currentTimeMillis() < tokenExpiresAt }?.let { return it }
        val email = settings.current().accountEmail
        when (val auth = runCatching { authorize(email) }.getOrElse { throw DriveAuthException(it.message ?: "Autorisation Google impossible") }) {
            is DriveAuthorization.Granted -> auth.accessToken
            is DriveAuthorization.NeedsConsent -> throw DriveAuthException("Autorisation Google Drive à renouveler")
        }
    }

    override suspend fun invalidate(token: String) {
        mutex.withLock {
            if (cachedToken == token) {
                cachedToken = null
                tokenExpiresAt = 0
            }
        }
        withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(context, token) } }
    }

    suspend fun signOut() {
        cachedToken?.let { invalidate(it) }
        cachedToken = null
        runCatching {
            androidx.credentials.CredentialManager.create(context)
                .clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
        }
    }

    companion object {
        const val ACCOUNT_TYPE = "com.google"
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/drive.appdata",
        )

        /** Les jetons OAuth Google durent une heure ; on les renouvelle avant. */
        private const val TOKEN_LIFETIME_MS = 50 * 60 * 1000L
    }
}

/** Message d'erreur lisible pour une connexion ou une autorisation Google qui échoue. */
internal fun authErrorMessage(e: Throwable): String {
    val text = e.message.orEmpty()
    return when {
        "10:" in text || "DEVELOPER_ERROR" in text ->
            "Client OAuth Android non configuré pour cette app (nom de paquet + empreinte SHA-1). Voir le README."
        "7:" in text || "NETWORK" in text.uppercase() -> "Réseau indisponible : la connexion Google nécessite Internet."
        else -> "Connexion Google impossible : ${text.ifBlank { e.javaClass.simpleName }}"
    }
}
