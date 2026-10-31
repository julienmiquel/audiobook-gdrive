@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.julienmiquel.audiotheque.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.julienmiquel.audiotheque.core.model.Accounts
import com.julienmiquel.audiotheque.core.model.AudioPreset
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.settings.AppSettings
import com.julienmiquel.audiotheque.core.settings.ListenerProfile
import com.julienmiquel.audiotheque.core.settings.StatusSyncMode
import com.julienmiquel.audiotheque.core.settings.ThemeMode
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.ui.common.InfoCard
import com.julienmiquel.audiotheque.ui.common.Labels
import com.julienmiquel.audiotheque.ui.common.LocalAppContainer
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val localBytes: StateFlow<Long> = c.database.trackDao().observeLocalBytes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _storageGranted = MutableStateFlow(c.storage.sharedStorageGranted())
    val storageGranted: StateFlow<Boolean> = _storageGranted.asStateFlow()

    private val _storagePath = MutableStateFlow<String?>(null)
    val storagePath: StateFlow<String?> = _storagePath.asStateFlow()

    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy.asStateFlow()

    private val _discoveringProfiles = MutableStateFlow(false)
    val discoveringProfiles: StateFlow<Boolean> = _discoveringProfiles.asStateFlow()

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

    fun setStreamingMode(enabled: Boolean) = c.setStreamingMode(enabled)

    fun deleteAllLocalFiles() = c.deleteAllLocalFiles()

    fun switchProfile(profileKey: String?) = c.switchProfile(profileKey)

    fun addProfile(displayName: String) = c.addOrSelectProfile(displayName)

    fun removeProfile(key: String) = c.removeProfile(key)

    fun setStatusSyncMode(mode: StatusSyncMode) = c.setStatusSyncMode(mode)

    fun discoverProfiles() {
        if (_discoveringProfiles.value) return
        _discoveringProfiles.value = true
        viewModelScope.launch {
            try {
                c.discoverProfilesFromDrive()
            } finally {
                _discoveringProfiles.value = false
            }
        }
    }

    fun exportBackup(context: Context, uri: Uri) {
        if (_backupBusy.value) return
        _backupBusy.value = true
        viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val res = c.backup.exportBackup()
                    val out = context.contentResolver.openOutputStream(uri)
                        ?: error("Impossible d'écrire dans le fichier choisi")
                    out.bufferedWriter(Charsets.UTF_8).use { it.write(res.json) }
                    res
                }
                c.message("Sauvegarde exportée : ${summary.booksCount} livre(s), ${summary.bookmarksCount} signet(s)")
            } catch (e: Exception) {
                c.message("Échec de l'exportation : ${e.localizedMessage ?: "erreur d'écriture"}")
            } finally {
                _backupBusy.value = false
            }
        }
    }

    fun importBackup(context: Context, uri: Uri) {
        if (_backupBusy.value) return
        _backupBusy.value = true
        viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val raw = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("Impossible de lire le fichier choisi")
                    c.backup.importBackup(raw)
                }
                c.message(
                    "Restauration terminée : ${summary.matchedBooks} livre(s) rapproché(s), " +
                        "${summary.restoredBookmarks} signet(s), ${summary.restoredCorrections} correction(s)",
                )
            } catch (e: Exception) {
                c.message("Fichier de sauvegarde invalide ou illisible")
            } finally {
                _backupBusy.value = false
            }
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
fun SettingsScreen(onBack: () -> Unit, onOpenStats: () -> Unit = {}, contentPadding: PaddingValues) {
    val container = LocalAppContainer.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val localBytes by vm.localBytes.collectAsStateWithLifecycle()
    val granted by vm.storageGranted.collectAsStateWithLifecycle()
    val path by vm.storagePath.collectAsStateWithLifecycle()
    val signingOut by vm.signingOut.collectAsStateWithLifecycle()
    val backupBusy by vm.backupBusy.collectAsStateWithLifecycle()
    val discoveringProfiles by vm.discoveringProfiles.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmDeleteAllLocal by remember { mutableStateOf(false) }
    var addingProfile by rememberSaveable { mutableStateOf(false) }
    var profileToRemove by remember { mutableStateOf<ListenerProfile?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) vm.exportBackup(context, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importBackup(context, uri)
    }

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
        val primaryLabel = s.accountName?.takeIf { it.isNotBlank() }
            ?: s.accountEmail?.substringBefore('@')?.replaceFirstChar { it.titlecase() }
            ?: "Principal"
        val activeSecondaryProfile = s.profiles.firstOrNull { it.key == s.activeProfileKey }
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard(title = "Statistiques d'écoute") {
                Text(
                    "Suivez votre temps d'écoute quotidien (7 et 30 jours), votre série active, vos livres terminés et vos auteurs et voix favoris.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onOpenStats) {
                    Icon(Icons.Filled.BarChart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Voir mes statistiques")
                }
            }

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
                    "Autorisation : Drive (déplacer les dossiers de livres ; progression, signets et métadonnées écrits dans le dossier de chaque livre).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (signingOut) SigningOut() else OutlinedButton(onClick = { confirmSignOut = true }) { Text("Se déconnecter") }
            }

            InfoCard(title = "Auditeurs & Partage familial") {
                Text(
                    "Chaque auditeur garde sa position d'écoute, ses signets, ses statistiques et ses statuts sur Google Drive, tandis que les corrections de titre, d'auteur et de pochette profitent à toute la famille.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Auditeur actif sur cet appareil", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = s.activeProfileKey == null,
                        onClick = { vm.switchProfile(null) },
                        label = { Text(primaryLabel) },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    s.profiles.forEach { profile ->
                        FilterChip(
                            selected = s.activeProfileKey == profile.key,
                            onClick = { vm.switchProfile(profile.key) },
                            label = { Text(profile.displayName) },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
                if (activeSecondaryProfile != null) {
                    TextButton(
                        onClick = { profileToRemove = activeSecondaryProfile },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retirer « ${activeSecondaryProfile.displayName} » de cet appareil")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { addingProfile = true }) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ajouter un auditeur")
                    }
                    OutlinedButton(
                        onClick = vm::discoverProfiles,
                        enabled = !discoveringProfiles && s.isConnected,
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Détecter sur Drive")
                    }
                }
                if (discoveringProfiles) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("Gestes de statut", style = MaterialTheme.typography.bodyLarge)
                val syncModes = listOf(
                    StatusSyncMode.PERSONAL_FAMILY to "Mode familial",
                    StatusSyncMode.SHARED_DRIVE to "Dossiers Drive",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    syncModes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = s.statusSyncMode == mode,
                            onClick = { vm.setStatusSyncMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, syncModes.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    if (s.statusSyncMode == StatusSyncMode.PERSONAL_FAMILY) {
                        "Mode familial actif : vos gestes (Commencer, Plus tard, Marquer écouté…) s'appliquent uniquement à votre profil, sans déplacer les dossiers Drive des autres membres. Pour ranger physiquement un dossier sur Drive, ouvrez la fiche livre › menu ⋮."
                    } else {
                        "Mode dossiers Drive : chaque geste déplace physiquement le dossier du livre dans _Nouveaux, _En écoute ou _Ecouté sur Google Drive pour tous les appareils."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            InfoCard(title = "Sauvegarde et restauration") {
                Text(
                    "Exporte ou restaure vos signets, corrections locales de titre/auteur/voix, préférences audio par livre et sessions d'écoute dans un fichier JSON, sans toucher à Google Drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { exportLauncher.launch("audiotheque-sauvegarde-${LocalDate.now()}.json") },
                        enabled = !backupBusy,
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Exporter (.json)")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        enabled = !backupBusy,
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Importer (.json)")
                    }
                }
                if (backupBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            InfoCard(title = "Nettoyage du téléphone") {
                ToggleRow(
                    "Mode streaming (pistes N-1, N et N+1)",
                    "Par défaut : seules la piste précédente, la piste en cours et la suivante sont chargées sur le téléphone. Désactivé : toutes les pistes à écouter sont téléchargées et chaque piste écoutée est supprimée.",
                    s.streamingMode,
                    vm::setStreamingMode,
                )
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
                    "Les livres épinglés (« Garder hors ligne ») conservent toujours toutes leurs pistes. Rien n'est supprimé lors d'un changement de statut avant que Drive ait confirmé le déplacement, ni dans les 10 minutes qui suivent ; la piste en cours de lecture n'est jamais supprimée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { confirmDeleteAllLocal = true },
                    enabled = localBytes > 0L,
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (localBytes > 0L) {
                            "Supprimer tous les fichiers locaux (${Formats.size(localBytes)})…"
                        } else {
                            "Aucun fichier audio sur le téléphone"
                        },
                    )
                }
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
                Text("Son (par défaut)", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioPreset.entries.forEach { preset ->
                        FilterChip(selected = s.audioPreset == preset, onClick = { vm.update { it.copy(audioPreset = preset) } }, label = { Text(preset.label) })
                    }
                }
                Text(
                    s.audioPreset.description + " Appliqué par défaut à tous les livres (personnalisable livre par livre depuis le lecteur).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.audioPreset == AudioPreset.BINAURAL) {
                    Text("Position de la voix (binaural)", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.julienmiquel.audiotheque.core.model.BinauralConfig.AZIMUTH_CHOICES.forEach { deg ->
                            FilterChip(
                                selected = s.binauralAzimuthDeg == deg,
                                onClick = { vm.update { it.copy(binauralAzimuthDeg = deg) } },
                                label = { Text(com.julienmiquel.audiotheque.core.model.BinauralConfig.azimuthLabel(deg)) },
                            )
                        }
                    }
                    Text("Acoustique de la pièce", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.julienmiquel.audiotheque.core.model.BinauralRoom.entries.forEach { room ->
                            FilterChip(
                                selected = s.binauralRoom == room,
                                onClick = { vm.update { it.copy(binauralRoom = room) } },
                                label = { Text(room.label) },
                            )
                        }
                    }
                }
                ToggleRow(
                    "Sauter les silences",
                    "Raccourcit les blancs entre phrases sans déformer la voix",
                    s.skipSilence,
                ) { v -> vm.update { it.copy(skipSilence = v) } }
                ToggleRow(
                    "Recul intelligent à la reprise",
                    "De 2 s après une courte pause jusqu'à 30 s le lendemain (sinon 5 s fixes)",
                    s.smartRewind,
                ) { v -> vm.update { it.copy(smartRewind = v) } }
                ToggleRow(
                    "Secouer pour prolonger la minuterie",
                    "Ajoute 10 min quand la minuterie est active ou vient tout juste de mettre en pause",
                    s.shakeToExtendTimer,
                ) { v -> vm.update { it.copy(shakeToExtendTimer = v) } }
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
                ToggleRow("Afficher les archives", "Onglet Archives (dossier _archives, lu à l'ouverture)", s.showArchives) { v -> vm.update { it.copy(showArchives = v) } }
                ToggleRow("Afficher les dossiers masqués", "Dossiers marqués « ce n'est pas un livre »", s.showHidden) { v -> vm.update { it.copy(showHidden = v) } }
                ToggleRow("Pochettes et résumés en ligne", "Google Books, Apple Books, Open Library (et Google Images si configuré) ; seuls le titre et l'auteur sont envoyés. Pochette au hasard exclue tant que le titre n'est pas confirmé", s.googleBooksEnrichment) { v -> vm.update { it.copy(googleBooksEnrichment = v) } }
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

    if (addingProfile) {
        var newName by rememberSaveable { mutableStateOf("") }
        val previewKey = Accounts.sanitizeProfileKey(newName)
        AlertDialog(
            onDismissRequest = { addingProfile = false },
            title = { Text("Nouvel auditeur") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Indiquez un prénom (ex. Julie, Clément). Sa progression, ses signets et ses statuts seront synchronisés dans un fichier dédié sur Google Drive sans modifier ceux des autres auditeurs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Prénom de l'auditeur") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (previewKey.isNotBlank() && previewKey != Accounts.LOCAL) {
                        Text(
                            "Fichier Drive : .audiotheque-progress-$previewKey.json",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.addProfile(newName)
                        addingProfile = false
                    },
                    enabled = previewKey.isNotBlank() && previewKey != Accounts.LOCAL,
                ) { Text("Ajouter") }
            },
            dismissButton = { TextButton(onClick = { addingProfile = false }) { Text("Annuler") } },
        )
    }

    profileToRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { profileToRemove = null },
            title = { Text("Retirer « ${target.displayName} » ?") },
            text = {
                Text(
                    "Ce profil ne sera plus proposé sur cet appareil. Sa progression et ses signets enregistrés sur Google Drive (.audiotheque-progress-${target.key}.json) restent intacts.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeProfile(target.key)
                    profileToRemove = null
                }) { Text("Retirer") }
            },
            dismissButton = { TextButton(onClick = { profileToRemove = null }) { Text("Annuler") } },
        )
    }

    if (confirmDeleteAllLocal) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAllLocal = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Supprimer tous les fichiers locaux ?") },
            text = {
                Text(
                    "Toutes les pistes audio téléchargées (${Formats.size(localBytes)}) seront effacées du téléphone pour libérer de la place. " +
                        "Vos fichiers sur Google Drive, vos progressions, vos pochettes et vos signets sont conservés.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAllLocal = false
                    vm.deleteAllLocalFiles()
                }) { Text("Supprimer du téléphone") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAllLocal = false }) { Text("Annuler") } },
        )
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
