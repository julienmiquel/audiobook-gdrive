package com.julienmiquel.audiotheque.ui.onboarding

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.julienmiquel.audiotheque.auth.DriveAuthorization
import com.julienmiquel.audiotheque.auth.GoogleIdentity
import com.julienmiquel.audiotheque.auth.GoogleSignIn
import com.julienmiquel.audiotheque.auth.SignInOutcome
import com.julienmiquel.audiotheque.auth.authErrorMessage
import com.julienmiquel.audiotheque.ui.common.LocalAppContainer
import kotlinx.coroutines.launch

/**
 * Premier lancement : connexion Google (Credential Manager si un ID client web est configuré,
 * puis AuthorizationClient pour le consentement Drive : drive et drive.appdata) ou mode démo.
 */
@Composable
fun OnboardingScreen() {
    val container = LocalAppContainer.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var identity by remember { mutableStateOf<GoogleIdentity?>(null) }

    suspend fun finish(granted: DriveAuthorization.Granted) {
        val about = if (identity?.email == null && granted.email == null) container.identify() else null
        val email = identity?.email ?: granted.email ?: about?.email
        if (email == null) {
            error = "Le compte Google autorisé n'a pas pu être identifié. Réessayez."
            busy = false
            return
        }
        container.onSignedIn(email, identity?.displayName ?: about?.displayName)
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scope.launch {
            if (result.resultCode != Activity.RESULT_OK) {
                error = "Autorisation Google Drive refusée. Elle est nécessaire pour lire la bibliothèque et déplacer les dossiers."
                busy = false
                return@launch
            }
            runCatching { container.auth.consentResult(result.data) }
                .onSuccess { auth ->
                    when (auth) {
                        is DriveAuthorization.Granted -> finish(auth)
                        is DriveAuthorization.NeedsConsent -> {
                            error = "Autorisation incomplète : réessayez."
                            busy = false
                        }
                    }
                }
                .onFailure {
                    error = "Autorisation impossible : ${it.message ?: it.javaClass.simpleName}"
                    busy = false
                }
        }
    }

    fun signIn() {
        val host = activity ?: return
        busy = true
        error = null
        scope.launch {
            when (val outcome = GoogleSignIn.signIn(host, container.auth.webClientId)) {
                is SignInOutcome.Success -> identity = outcome.identity
                SignInOutcome.Cancelled -> {
                    busy = false
                    return@launch
                }
                // Sans ID client web (ou sans compte enregistré), le choix du compte se fait à
                // l'écran d'autorisation Drive.
                SignInOutcome.NotConfigured, is SignInOutcome.Failed -> identity = null
            }
            runCatching { container.auth.authorize(identity?.email) }
                .onSuccess { auth ->
                    when (auth) {
                        is DriveAuthorization.Granted -> finish(auth)
                        is DriveAuthorization.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(auth.pendingIntent.intentSender).build())
                    }
                }
                .onFailure {
                    error = authErrorMessage(it)
                    busy = false
                }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Icon(Icons.Filled.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
            Text("Audiothèque", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Text(
                "Vos livres audio de Google Drive, classés par statut et écoutés hors ligne.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Feature(Icons.AutoMirrored.Filled.DriveFileMove, "Un geste dans l'app, un dossier déplacé dans /AudioBook : Commencer, Mettre de côté, Marquer écouté…")
            Feature(Icons.Filled.OfflinePin, "Seul _En écoute descend en entier sur le téléphone, pour une écoute hors ligne.")
            Feature(Icons.Filled.Lock, "Aucun serveur tiers, aucune publicité, aucune télémétrie.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = ::signIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Se connecter avec Google")
            }
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch { container.startDemo() }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Essayer en mode démo") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            Text(
                "Accès demandés : Google Drive (déplacer les dossiers de livres que l'app n'a pas créés) et l'espace caché de l'app (votre progression, un fichier par livre).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
