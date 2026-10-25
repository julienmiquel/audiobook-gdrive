@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.julienmiquel.audiotheque.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.julienmiquel.audiotheque.AppContainer
import com.julienmiquel.audiotheque.BuildConfig
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.settings.AppSettings
import com.julienmiquel.audiotheque.core.settings.ThemeMode
import com.julienmiquel.audiotheque.ui.common.InfoCard
import com.julienmiquel.audiotheque.ui.common.Labels
import com.julienmiquel.audiotheque.ui.common.LocalAppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _storageGranted = MutableStateFlow(c.storage.sharedStorageGranted())
    val storageGranted: StateFlow<Boolean> = _storageGranted.asStateFlow()

    private val _storagePath = MutableStateFlow<String?>(null)
    val storagePath: StateFlow<String?> = _storagePath.asStateFlow()

    fun refreshStorage() {
        _storageGranted.value = c.storage.sharedStorageGranted()
        viewModelScope.launch { _storagePath.value = runCatching { c.storage.displayRoot() }.getOrNull() }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            c.settings.update(transform)
            refreshStorage()
        }
    }

    /** Aperçu activé : la première piste des _Nouveaux descend à la prochaine synchro. */
    fun setPreview(enabled: Boolean) {
        viewModelScope.launch {
            c.settings.update { it.copy(previewNew = enabled) }
            c.requestSync(SyncTrigger.MANUAL)
        }
    }

    private val _signingOut = MutableStateFlow(false)

    /** Déconnexion en cours : fin de la synchro, dernière écriture de la progression, effacement. */
    val signingOut: StateFlow<Boolean> = _signingOut

    fun signOut() {
        if (_signingOut.value) return
        _signingOut.value = true
        c.appScope.launch {
            try {
                c.signOut()
            } finally {
                _signingOut.value = false
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, contentPadding: PaddingValues) {
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val granted by vm.storageGranted.collectAsStateWithLifecycle()
    val path by vm.storagePath.collectAsStateWithLifecycle()
    val signingOut by vm.signingOut.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStorage() }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") } },
            )
        },
    ) { inner ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard(title = "Compte Google") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(s.accountName ?: s.accountEmail ?: "—", style = MaterialTheme.typography.bodyLarge)
                        s.accountEmail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Text(
                    "Autorisations : Drive (déplacer les dossiers de livres) et espace caché de l'app (progression, un fichier par livre, propre à votre compte).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (signingOut) SigningOut() else OutlinedButton(onClick = { confirmSignOut = true }) { Text("Se déconnecter") }
            }

            InfoCard(title = "Nettoyage du téléphone") {
                Text("Pistes de _A écouter plus tard gardées", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.LATER_RETENTION_CHOICES.forEach { d ->
                        FilterChip(selected = s.laterRetentionDays == d, onClick = { vm.update { it.copy(laterRetentionDays = d) } }, label = { Text(Labels.days(d)) })
                    }
                }
                Text("Pistes de _Ecouté supprimées après", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.DONE_RETENTION_CHOICES.forEach { h ->
                        FilterChip(selected = s.doneRetentionHours == h, onClick = { vm.update { it.copy(doneRetentionHours = h) } }, label = { Text(Labels.hours(h)) })
                    }
                }
                Text(
                    "Seul _En écoute garde toutes ses pistes. Rien n'est supprimé avant que Drive ait confirmé le déplacement, ni dans les 10 minutes qui suivent ; le livre en cours de lecture n'est jamais nettoyé.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            InfoCard(title = "Lecture") {
                Text("Pas du saut", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AppSettings.SKIP_CHOICES.forEachIndexed { i, sec ->
                        SegmentedButton(
                            selected = s.skipSeconds == sec,
                            onClick = { vm.update { it.copy(skipSeconds = sec) } },
                            shape = SegmentedButtonDefaults.itemShape(i, AppSettings.SKIP_CHOICES.size),
                        ) { Text(Labels.skip(sec)) }
                    }
                }
            }

            InfoCard(title = "Apparence") {
                Text("Thème", style = MaterialTheme.typography.bodyLarge)
                val themes = listOf(ThemeMode.DARK to "Sombre", ThemeMode.LIGHT to "Clair", ThemeMode.SYSTEM to "Système")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    themes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = s.theme == mode,
                            onClick = { vm.update { it.copy(theme = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                        ) { Text(label) }
                    }
                }
            }

            InfoCard(title = "Bibliothèque") {
                ToggleRow("Aperçu des Nouveaux", "Télécharger la première piste des livres de _Nouveaux", s.previewNew, vm::setPreview)
                ToggleRow("Afficher les archives", "Onglet _archives (masqué par défaut)", s.showArchives) { v -> vm.update { it.copy(showArchives = v) } }
                ToggleRow("Afficher les dossiers masqués", "Dossiers marqués « ce n'est pas un livre »", s.showHidden) { v -> vm.update { it.copy(showHidden = v) } }
                ToggleRow("Pochettes et résumés Google Books", "Après confirmation du titre et de l'auteur ; seuls ces deux champs sont envoyés", s.googleBooksEnrichment) { v -> vm.update { it.copy(googleBooksEnrichment = v) } }
            }

            InfoCard(title = "Stockage") {
                ToggleRow("Enregistrer dans [Stockage interne]/Audiobooks", "Comme l'app actuelle ; sinon dans le dossier de l'app", s.useSharedStorage) { v -> vm.update { it.copy(useSharedStorage = v) } }
                if (s.useSharedStorage && !granted) {
                    Text("L'accès à tous les fichiers est nécessaire pour écrire dans [Stockage interne]/Audiobooks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                        val opened = runCatching { context.startActivity(intent) }.isSuccess ||
                            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }.isSuccess
                        if (!opened) container.message("Réglage introuvable : autorisez l'accès à tous les fichiers dans les paramètres du téléphone")
                    }) { Text("Autoriser l'accès") }
                }
                Text("Emplacement : ${path ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("Les livres déjà téléchargés restent où ils sont. Changer de statut ne déplace rien sur le téléphone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            InfoCard(title = "Confidentialité") {
                Text(
                    "Aucun serveur tiers, aucune publicité, aucune télémétrie. Les données restent sur le téléphone et sur le Drive de votre compte.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Audiothèque ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Se déconnecter ?") },
            text = {
                Text(
                    "La bibliothèque locale et les pistes téléchargées seront effacées du téléphone. Votre progression est d'abord enregistrée dans votre Drive, et vos signets restent sur le téléphone. Rien n'est supprimé dans Drive.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    vm.signOut()
                }) { Text("Se déconnecter") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Annuler") } },
        )
    }
}

/** Déconnexion en cours (fin de la synchro, progression enregistrée dans Drive, effacement). */
@Composable
private fun SigningOut() {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Déconnexion… (progression enregistrée dans Drive)", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
