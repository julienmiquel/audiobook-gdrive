package com.julienmiquel.audiotheque.auth

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.julienmiquel.audiotheque.BuildConfig
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.sync.drive.AccessTokenProvider
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import java.security.MessageDigest
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
 * AuthorizationClient pour le consentement Drive. Une autorisation : « drive », nécessaire pour
 * déplacer des dossiers que l'app n'a pas créés et écrire progression et pochette dans le dossier
 * de chaque livre.
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

    /** Nom de paquet à déclarer dans l'ID client OAuth Android (Google Cloud). */
    val packageName: String get() = context.packageName

    /** Empreinte SHA-1 du certificat qui signe cette installation, à déclarer avec le nom de paquet. */
    val signingSha1: String? by lazy {
        runCatching {
            val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            val cert = context.packageManager.getPackageInfo(context.packageName, flags).signingInfo
                ?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
            MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
        }.getOrNull()
    }

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

        /**
         * « drive » suffit : déplacer des dossiers que l'app n'a pas créés, et écrire la
         * progression et la pochette dans le dossier de chaque livre. (L'espace caché
         * « drive.appdata » n'est plus utilisé ; demander moins qu'avant ne rouvre pas le consentement.)
         */
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/drive",
        )

        /** Les jetons OAuth Google durent une heure ; on les renouvelle avant. */
        private const val TOKEN_LIFETIME_MS = 50 * 60 * 1000L
    }
}

/**
 * Google ne reconnaît pas l'app : aucun ID client OAuth Android pour ce nom de paquet et cette
 * empreinte SHA-1 (DEVELOPER_ERROR, ou code 8 avec le statut UNREGISTERED_ON_API_CONSOLE).
 */
private fun Throwable.isUnregisteredApp(): Boolean {
    val code = (this as? ApiException)?.statusCode
    val text = message.orEmpty()
    return code == CommonStatusCodes.DEVELOPER_ERROR ||
        "UNREGISTERED_ON_API_CONSOLE" in text ||
        (code == null && ("10:" in text || "DEVELOPER_ERROR" in text))
}

/** Message d'erreur lisible pour une connexion ou une autorisation Google qui échoue. */
internal fun authErrorMessage(e: Throwable): String {
    val code = (e as? ApiException)?.statusCode
    val text = e.message.orEmpty()
    return when {
        e.isUnregisteredApp() ->
            "Google ne reconnaît pas l'app (UNREGISTERED_ON_API_CONSOLE) : elle doit être déclarée dans Google Cloud par un ID client OAuth de type Android, avec le nom de paquet et l'empreinte SHA-1 ci-dessous."
        code == CommonStatusCodes.NETWORK_ERROR || (code == null && ("7:" in text || "NETWORK" in text.uppercase())) ->
            "Réseau indisponible : la connexion Google nécessite Internet."
        else -> "Connexion Google impossible : ${text.ifBlank { e.javaClass.simpleName }}"
    }
}

/**
 * Écran d'autorisation Google fermé sans accord. Google ne donne pas toujours de raison : une
 * annulation et une app non déclarée dans Google Cloud (ID client OAuth Android absent) se
 * ressemblent alors, l'écran se refermant aussitôt.
 */
internal fun consentFailureMessage(reason: Throwable?, firstSignIn: Boolean): String {
    val code = (reason as? ApiException)?.statusCode
    return when {
        reason != null && (reason.isUnregisteredApp() || code == CommonStatusCodes.NETWORK_ERROR) -> authErrorMessage(reason)
        code == null || code == CommonStatusCodes.CANCELED ->
            if (firstSignIn) {
                "L'autorisation Google Drive n'a pas abouti. Si l'écran Google s'est refermé sans vous laisser choisir de compte, l'app n'est pas encore déclarée dans Google Cloud : voir ci-dessous."
            } else {
                "Autorisation Google Drive refusée : la synchro reste en pause."
            }
        else -> "Autorisation Google Drive impossible (code $code : ${reason?.message.orEmpty()})."
    }
}

/** Échec qui vient très probablement de la configuration Google Cloud (ID client OAuth Android). */
internal fun isSetupProblem(reason: Throwable?, consentClosed: Boolean): Boolean {
    if (reason?.isUnregisteredApp() == true) return true
    return consentClosed && (reason as? ApiException)?.statusCode != CommonStatusCodes.NETWORK_ERROR
}
