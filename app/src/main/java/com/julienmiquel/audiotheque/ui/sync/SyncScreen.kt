@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalCoroutinesApi::class)

package com.julienmiquel.audiotheque.ui.sync

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.julienmiquel.audiotheque.AppContainer
import com.julienmiquel.audiotheque.core.db.EventBook
import com.julienmiquel.audiotheque.core.db.SyncEventEntity
import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.db.SyncRunEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.PairMode
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.SyncResult
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.settings.AppSettings
import com.julienmiquel.audiotheque.core.settings.SyncState
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.sync.engine.EngineStatus
import com.julienmiquel.audiotheque.sync.engine.Transfer
import com.julienmiquel.audiotheque.ui.common.EmptyState
import com.julienmiquel.audiotheque.ui.common.InfoCard
import com.julienmiquel.audiotheque.ui.common.InfoRow
import com.julienmiquel.audiotheque.ui.common.Labels
import com.julienmiquel.audiotheque.ui.common.LocalAppContainer
import com.julienmiquel.audiotheque.ui.common.Tag
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncUiState(
    val engine: EngineStatus = EngineStatus(),
    val transfers: List<Transfer> = emptyList(),
    val lastRun: SyncRunEntity? = null,
    val lastFinished: SyncRunEntity? = null,
    val pendingNow: Boolean = false,
    val nextRun: Long? = null,
    val syncState: SyncState = SyncState(),
    val settings: AppSettings = AppSettings(),
    val pendingMoves: Int = 0,
    val pairs: List<SyncPairEntity> = emptyList(),
    val localBytes: Long = 0,
    val freeBytes: Long? = null,
    val localRoot: String? = null,
    val sharedStorageGranted: Boolean = true,
    val events: List<SyncEventEntity> = emptyList(),
    val eventBooks: List<EventBook> = emptyList(),
    val bookFilter: String? = null,
)

private data class Live(val engine: EngineStatus, val transfers: List<Transfer>, val lastRun: SyncRunEntity?, val lastFinished: SyncRunEntity?, val pendingNow: Boolean)
private data class Schedule(val nextRun: Long?, val syncState: SyncState, val settings: AppSettings, val pendingMoves: Int, val pairs: List<SyncPairEntity>)
private data class Storage(val localBytes: Long, val freeBytes: Long?, val localRoot: String?, val granted: Boolean)
private data class StorageProbe(val freeBytes: Long?, val localRoot: String?, val granted: Boolean)
private data class History(val events: List<SyncEventEntity>, val books: List<EventBook>, val filter: String?)

class SyncViewModel(private val c: AppContainer) : ViewModel() {
    private val bookFilter = MutableStateFlow<String?>(null)
    private val runs = c.database.syncRunDao()
    private val eventsDao = c.database.syncEventDao()

    private val storageRefresh = MutableStateFlow(0)

    private val storageInfo: Flow<StorageProbe> = combine(c.settings.settings.map { it.useSharedStorage }, storageRefresh) { shared, tick -> shared to tick }.flatMapLatest {
        flow {
            while (true) {
                val root = runCatching { c.storage.root() }.getOrNull()
                emit(StorageProbe(root?.let { c.storage.freeBytes(it) }, root?.let { c.storage.displayPath(it) }, c.storage.sharedStorageGranted()))
                delay(30_000)
            }
        }
    }

    /** Retour à l'écran : l'autorisation « accès à tous les fichiers » a peut-être été accordée. */
    fun refreshStorage() {
        storageRefresh.value++
    }

    val state: StateFlow<SyncUiState> = combine(
        combine(
            c.engine.status,
            c.engine.transfers.transfers.map { it.values.sortedBy { t -> t.fileName } },
            runs.observeLast(),
            runs.observeLastFinished(),
            c.scheduler.pendingNow(),
        ) { e, t, last, finished, pending -> Live(e, t, last, finished, pending) },
        combine(
            c.scheduler.nextPeriodicRun(),
            c.settings.syncState,
            c.settings.settings,
            c.library.observePendingOperations(),
            c.library.observePairs(),
        ) { next, sync, settings, moves, pairs -> Schedule(next, sync, settings, moves, pairs) },
        combine(c.database.trackDao().observeLocalBytes(), storageInfo) { bytes, info -> Storage(bytes, info.freeBytes, info.localRoot, info.granted) },
        combine(
            bookFilter.flatMapLatest { id -> if (id == null) eventsDao.observeRecent(1000) else eventsDao.observeForBook(id, 1000) },
            eventsDao.observeBooksWithEvents(),
            bookFilter,
        ) { events, books, filter -> History(events, books, filter) },
    ) { live, schedule, storage, history ->
        SyncUiState(
            engine = live.engine,
            transfers = live.transfers,
            lastRun = live.lastRun,
            lastFinished = live.lastFinished,
            pendingNow = live.pendingNow,
            nextRun = schedule.nextRun,
            syncState = schedule.syncState,
            settings = schedule.settings,
            pendingMoves = schedule.pendingMoves,
            pairs = schedule.pairs,
            localBytes = storage.localBytes,
            freeBytes = storage.freeBytes,
            localRoot = storage.localRoot,
            sharedStorageGranted = storage.granted,
            events = history.events,
            eventBooks = history.books,
            bookFilter = history.filter,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState())

    fun syncNow() = c.requestSync(SyncTrigger.MANUAL)

    fun cancel() = c.scheduler.cancelRunning()

    fun setBookFilter(bookId: String?) {
        bookFilter.value = bookId
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { c.settings.update(transform) }
    }

    fun setStreamingMode(enabled: Boolean) = c.setStreamingMode(enabled)

    fun deleteAllLocalFiles() = c.deleteAllLocalFiles()

    fun fullInventory() {
        viewModelScope.launch {
            c.settings.updateSyncState { it.copy(lastFullInventoryAt = null) }
            c.requestSync(SyncTrigger.MANUAL)
            c.message("Inventaire complet demandé")
        }
    }

    fun setPairEnabled(pair: SyncPairEntity, enabled: Boolean) {
        viewModelScope.launch {
            c.database.syncPairDao().update(pair.copy(enabled = enabled))
            if (enabled) c.requestSync(SyncTrigger.MANUAL)
        }
    }

    fun setPairMode(pair: SyncPairEntity, mode: PairMode) {
        viewModelScope.launch {
            c.database.syncPairDao().update(pair.copy(mode = mode))
            c.requestSync(SyncTrigger.MANUAL)
        }
    }

    fun removePair(pair: SyncPairEntity) {
        viewModelScope.launch {
            // Fichiers locaux supprimés : hors du thread principal.
            withContext(Dispatchers.IO) { c.engine.removePair(pair.id) }
            c.message("Paire « ${pair.name} » retirée (rien n'est supprimé dans Drive)")
        }
    }

    fun restoreDefaults() {
        viewModelScope.launch {
            c.database.syncPairDao().replaceAll(DriveLibraryDefaults.defaultPairs())
            c.requestSync(SyncTrigger.MANUAL)
            c.message("Paires par défaut rétablies")
        }
    }

    /** Ajoute une paire à partir d'un lien ou d'un ID de dossier Drive ; [onDone] reçoit l'erreur éventuelle. */
    fun addPair(input: String, status: BookStatus, mode: PairMode, onDone: (String?) -> Unit) {
        val folderId = parseFolderId(input)
        if (folderId == null) {
            onDone("Lien ou ID de dossier Drive non reconnu")
            return
        }
        viewModelScope.launch {
            val dao = c.database.syncPairDao()
            dao.forFolder(folderId)?.let {
                onDone("Ce dossier est déjà synchronisé (${it.name})")
                return@launch
            }
            c.engine.lookupFolder(folderId).fold(
                onSuccess = { file ->
                    val path = if (DriveLibraryDefaults.ROOT_ID in file.parents) "${DriveLibraryDefaults.ROOT_PATH}/${file.name}" else "…/${file.name}"
                    val position = (dao.all().maxOfOrNull { it.position } ?: 0) + 1
                    val id = dao.insert(SyncPairEntity(status = status, name = file.name, driveFolderId = folderId, drivePath = path, mode = mode, position = position))
                    if (mode == PairMode.ON_OPEN) c.refreshFolder(id, silent = false) else c.requestSync(SyncTrigger.MANUAL)
                    c.message("Paire « ${file.name} » ajoutée")
                    onDone(null)
                },
                onFailure = { e -> onDone(e.message ?: "Dossier introuvable") },
            )
        }
    }

    companion object {
        private val folderInUrl = Regex("""folders/([A-Za-z0-9_-]{10,})""")
        private val idParam = Regex("""[?&]id=([A-Za-z0-9_-]{10,})""")
        private val bareId = Regex("""^[A-Za-z0-9_-]{10,}$""")

        /** « https://drive.google.com/drive/folders/<ID>?usp=… », « …open?id=<ID> » ou l'ID seul. */
        fun parseFolderId(input: String): String? {
            val s = input.trim()
            folderInUrl.find(s)?.let { return it.groupValues[1] }
            idParam.find(s)?.let { return it.groupValues[1] }
            return s.takeIf { bareId.matches(it) }
        }
    }
}

@Composable
fun SyncScreen(onOpenSettings: () -> Unit, contentPadding: PaddingValues) {
    val container = LocalAppContainer.current
    val vm: SyncViewModel = viewModel { SyncViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshStorage() }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Synchro") },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Réglages") } },
            )
        },
        floatingActionButton = {
            // Bouton flottant « Annuler », présent pendant toute synchro.
            if (state.engine.running) {
                ExtendedFloatingActionButton(
                    onClick = vm::cancel,
                    icon = { Icon(Icons.Filled.Close, contentDescription = null) },
                    text = { Text("Annuler") },
                )
            }
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Suivi", "Historique", "Dossiers synchronisés").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, textAlign = TextAlign.Center, maxLines = 2) })
                }
            }
            when (tab) {
                0 -> FollowTab(state, vm)
                1 -> HistoryTab(state, vm)
                else -> FoldersTab(state, vm)
            }
        }
    }
}

// ---------------------------------------------------------------------------------- Suivi

@Composable
private fun FollowTab(state: SyncUiState, vm: SyncViewModel) {
    var confirmDeleteLocal by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), state.engine.running) {
        while (true) {
            value = System.currentTimeMillis()
            if (!state.engine.running) break
            delay(1_000)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard(title = "Suivi de la synchro") {
            val run = state.lastRun
            val engine = state.engine
            val statusText = when {
                engine.running -> "Synchro en cours · ${engine.phase.label}"
                state.pendingNow -> "En attente du réseau"
                run == null -> "Jamais synchronisé"
                else -> Labels.result(run.result)
            }
            InfoRow("État", statusText)
            if (engine.running) engine.detail?.let { InfoRow("Étape", it) }
            run?.let { r ->
                InfoRow("Déclencheur", Labels.trigger(engine.trigger ?: r.trigger))
                InfoRow("Début", Formats.dateTime(engine.startedAt ?: r.startedAt))
                val end = if (engine.running) null else r.endedAt
                InfoRow("Fin", end?.let { Formats.dateTime(it) } ?: "—")
                val start = engine.startedAt ?: r.startedAt
                InfoRow("Durée", Formats.elapsed((end ?: now) - start))
            }
            InfoRow(
                "Prochaine synchro",
                when {
                    !state.settings.autoSync -> "Synchro auto désactivée"
                    state.nextRun != null -> Formats.upcoming(state.nextRun) + if (state.settings.wifiOnly) " (Wi-Fi)" else ""
                    else -> "Planifiée toutes les ${state.settings.syncIntervalHours} h"
                },
            )
            if (engine.running && engine.queuedTracks > 0) InfoRow("À recevoir", Labels.tracks(engine.queuedTracks))
            if (!engine.running) run?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.transfers.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Transferts en cours", style = MaterialTheme.typography.titleSmall)
                state.transfers.forEach { TransferRow(it) }
            }
            if (!engine.running) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = vm::syncNow) {
                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Synchroniser maintenant")
                }
            }
        }

        InfoCard(title = "Dernières modifications") {
            val r = state.lastFinished
            if (r == null) {
                Text("Aucune synchro terminée pour l'instant.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${Labels.result(r.result)} · ${Formats.dateTime(r.endedAt ?: r.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfoRow("Reçu", "${Labels.files(r.filesReceived)} / ${Formats.size(r.bytesReceived)}")
                InfoRow("Supprimé du téléphone", "${Labels.files(r.filesDeleted)} / ${Formats.size(r.bytesDeleted)}")
                InfoRow("Déplacements Drive", r.moves.toString())
                InfoRow("Statuts mis à jour depuis Drive", r.statusUpdates.toString())
                if (r.errors > 0) InfoRow("Erreurs", "${r.errors} (voir l'historique)")
                if (r.fullInventory) InfoRow("Inventaire", "Complet")
                r.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (state.pendingMoves > 0) {
                InfoRow("Déplacements en file", "${state.pendingMoves} (rejoués dès le retour du réseau)")
            }
        }

        InfoCard(title = "Espace Drive") {
            val used = state.syncState.driveQuotaUsed
            val limit = state.syncState.driveQuotaLimit
            if (used == null) {
                Text("Connu après la prochaine synchro.", style = MaterialTheme.typography.bodyMedium)
            } else {
                if (limit != null && limit > 0) {
                    LinearProgressIndicator(progress = { (used.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    InfoRow("Utilisé", "${Formats.size(used)} sur ${Formats.size(limit)} (${(used * 100 / limit)} %)")
                } else {
                    InfoRow("Utilisé", "${Formats.size(used)} (stockage illimité)")
                }
                state.syncState.quotaUpdatedAt?.let { InfoRow("Relevé le", Formats.dateTime(it)) }
            }
        }

        InfoCard(title = "Espace téléphone") {
            InfoRow("Livres sur le téléphone", Formats.size(state.localBytes))
            InfoRow("Espace libre", Formats.size(state.freeBytes))
            InfoRow("Emplacement", state.localRoot ?: "—")
            SwitchRow(
                "Mode streaming (pistes N-1, N et N+1)",
                "Par défaut : seules 3 pistes autour de l'écoute sont gardées sur le disque",
                state.settings.streamingMode,
                vm::setStreamingMode,
            )
            Text(
                "Un transfert ne démarre pas si l'espace libre est inférieur à la taille des pistes prévues plus 500 Mo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { confirmDeleteLocal = true },
                enabled = state.localBytes > 0L,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.localBytes > 0L) {
                        "Supprimer les fichiers locaux (${Formats.size(state.localBytes)})…"
                    } else {
                        "Aucun fichier audio sur le téléphone"
                    },
                )
            }
        }
        Spacer(Modifier.height(72.dp))
    }

    if (confirmDeleteLocal) {
        AlertDialog(
            onDismissRequest = { confirmDeleteLocal = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Supprimer les fichiers locaux ?") },
            text = {
                Text(
                    "Toutes les pistes audio téléchargées (${Formats.size(state.localBytes)}) seront effacées du téléphone pour libérer de la place. " +
                        "Vos fichiers sur Google Drive, vos progressions, vos pochettes et vos signets sont conservés.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteLocal = false
                    vm.deleteAllLocalFiles()
                }) { Text("Supprimer du téléphone") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteLocal = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun TransferRow(t: Transfer) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(t.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LinearProgressIndicator(
            progress = { if (t.totalBytes > 0) (t.doneBytes.toFloat() / t.totalBytes).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        Text(
            "${t.bookTitle} · ${Formats.megabytes(t.doneBytes)} / ${Formats.size(t.totalBytes)} · ${Formats.size(t.bytesPerSecond)}/s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------------- Historique

@Composable
private fun HistoryTab(state: SyncUiState, vm: SyncViewModel) {
    var filterMenu by remember { mutableStateOf(false) }
    val grouped = remember(state.events) { state.events.groupBy { Formats.dayLabel(it.timestamp) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        item(key = "filter") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    val label = state.bookFilter?.let { id -> state.eventBooks.firstOrNull { it.bookId == id }?.bookTitle } ?: "Tous les livres"
                    AssistChip(
                        onClick = { filterMenu = true },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    )
                    DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                        DropdownMenuItem(text = { Text("Tous les livres") }, onClick = {
                            vm.setBookFilter(null)
                            filterMenu = false
                        })
                        state.eventBooks.forEach { b ->
                            DropdownMenuItem(text = { Text(b.bookTitle ?: b.bookId) }, onClick = {
                                vm.setBookFilter(b.bookId)
                                filterMenu = false
                            })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Conservé 30 jours", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.events.isEmpty()) {
            item(key = "empty") { EmptyState(Icons.Filled.History, "Historique vide", "Réceptions, suppressions locales, déplacements Drive et erreurs s'afficheront ici.") }
        }
        grouped.forEach { (day, events) ->
            item(key = "day-$day-${events.first().id}") {
                Text(
                    day,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
            }
            items(events, key = { "e-${it.id}" }) { EventRow(it) }
        }
    }
}

@Composable
private fun EventRow(e: SyncEventEntity) {
    val error = e.result == EventResult.ERROR || e.type == SyncEventType.ERROR
    val icon: ImageVector = when (e.type) {
        SyncEventType.RECEIVED -> Icons.Filled.Download
        SyncEventType.LOCAL_DELETE -> Icons.Filled.Delete
        SyncEventType.DRIVE_MOVE -> Icons.AutoMirrored.Filled.DriveFileMove
        SyncEventType.STATUS_FROM_DRIVE -> Icons.Filled.Sync
        SyncEventType.LIBRARY_CHANGE -> Icons.AutoMirrored.Filled.LibraryBooks
        SyncEventType.PROGRESS -> Icons.Filled.History
        SyncEventType.ERROR -> Icons.Filled.Error
    }
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = if (error) "Erreur" else null,
            tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // L'erreur est dite en toutes lettres, pas seulement par la couleur.
            val kind = if (error && e.type != SyncEventType.ERROR) "Erreur · ${Labels.eventType(e.type)}" else Labels.eventType(e.type)
            Text(
                listOfNotNull(kind, e.bookTitle).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(e.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            e.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Formats.timeWithSeconds(e.timestamp), style = MaterialTheme.typography.labelMedium)
            e.sizeBytes?.let { Text("${Formats.megabytes(it)}\u00A0Mo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ---------------------------------------------------------------------------------- Dossiers synchronisés

@Composable
private fun FoldersTab(state: SyncUiState, vm: SyncViewModel) {
    var editing by remember { mutableStateOf<SyncPairEntity?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val s = state.settings
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "auto") {
            InfoCard(title = "Synchronisation automatique") {
                SwitchRow("Synchro auto", "Planifiée, et immédiate au lancement et à chaque changement de statut", s.autoSync) { v -> vm.updateSettings { it.copy(autoSync = v) } }
                SwitchRow("Wi-Fi uniquement", "Les téléchargements attendent le Wi-Fi", s.wifiOnly) { v -> vm.updateSettings { it.copy(wifiOnly = v) } }
                SwitchRow("Batterie non faible", "Pas de synchro planifiée batterie faible", s.requireBatteryNotLow) { v -> vm.updateSettings { it.copy(requireBatteryNotLow = v) } }
                Text("Fréquence", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppSettings.INTERVAL_CHOICES.forEach { h ->
                        FilterChip(
                            selected = s.syncIntervalHours == h,
                            onClick = { vm.updateSettings { it.copy(syncIntervalHours = h) } },
                            enabled = s.autoSync,
                            label = { Text(if (h == 1) "1 h" else "$h h") },
                        )
                    }
                }
            }
        }
        if (s.useSharedStorage && !state.sharedStorageGranted) {
            item(key = "storage-hint") {
                val context = LocalContext.current
                InfoCard(title = "Emplacement sur le téléphone") {
                    Text(
                        "Les livres sont enregistrés dans le dossier de l'app. Pour retrouver [Stockage interne]/Audiobooks comme l'app actuelle, autorisez l'accès à tous les fichiers.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                        runCatching { context.startActivity(intent) }
                    }) { Text("Autoriser l'accès") }
                }
            }
        }
        item(key = "pairs-title") {
            Text("Dossiers synchronisés · ${state.pairs.size} paires", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("Une paire par statut, sans limite. Changer de statut ne déplace rien sur le téléphone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(state.pairs, key = { it.id }) { pair ->
            PairCard(pair, state.localRoot, onClick = { editing = pair }, onToggle = { vm.setPairEnabled(pair, it) })
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ajouter une paire")
                }
                OutlinedButton(onClick = vm::fullInventory) { Text("Inventaire complet maintenant") }
                TextButton(onClick = { confirmRestore = true }) { Text("Rétablir les paires par défaut") }
                state.syncState.lastFullInventoryAt?.let {
                    Text("Dernier inventaire complet : ${Formats.dateTime(it)} (puis une fois par semaine)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(64.dp))
            }
        }
    }

    editing?.let { pair ->
        val removable = pair.status == BookStatus.COLLECTION || state.pairs.any { it.status == pair.status && it.id != pair.id && it.position < pair.position }
        PairDialog(
            pair = pair,
            removable = removable,
            onDismiss = { editing = null },
            onMode = { mode ->
                vm.setPairMode(pair, mode)
                editing = null
            },
            onRemove = {
                vm.removePair(pair)
                editing = null
            },
        )
    }
    if (adding) AddPairDialog(vm, onDismiss = { adding = false })
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Rétablir les paires par défaut ?") },
            text = { Text("Une paire par statut de /AudioBook, avec les IDs de la bibliothèque existante. Les paires ajoutées seront retirées.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.restoreDefaults()
                    confirmRestore = false
                }) { Text("Rétablir") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
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

@Composable
private fun PairCard(pair: SyncPairEntity, localRoot: String?, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    InfoCard(title = null, modifier = Modifier.clickable(onClickLabel = "Modifier la paire", onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(Labels.pairTitle(pair), style = MaterialTheme.typography.titleMedium)
                Tag(Labels.status(pair.status))
            }
            Switch(
                checked = pair.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = "Synchroniser ${Labels.pairTitle(pair)}" },
            )
        }
        InfoRow("Drive", pair.drivePath)
        InfoRow("Téléphone", if (pair.mode == PairMode.FULL) (localRoot ?: "Audiobooks") else "Métadonnées uniquement")
        InfoRow("Mode", Labels.mode(pair.mode))
        InfoRow("Dernier inventaire", pair.lastInventoryAt?.let { Formats.dateTime(it) } ?: "—")
    }
}

@Composable
private fun PairDialog(pair: SyncPairEntity, removable: Boolean, onDismiss: () -> Unit, onMode: (PairMode) -> Unit, onRemove: () -> Unit) {
    var mode by remember { mutableStateOf(pair.mode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Labels.pairTitle(pair)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${pair.drivePath}\nID : ${pair.driveFolderId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                PairMode.entries.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == m, role = Role.RadioButton, onClick = { mode = m })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(Labels.mode(m), style = MaterialTheme.typography.bodyLarge)
                            Text(Labels.modeDescription(m), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (removable) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    TextButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Retirer cette paire")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMode(mode) }, enabled = mode != pair.mode) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun AddPairDialog(vm: SyncViewModel, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf(BookStatus.COLLECTION) }
    var mode by rememberSaveable { mutableStateOf(PairMode.ON_OPEN) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Ajouter une paire") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    label = { Text("Lien ou ID du dossier Drive") },
                    isError = error != null,
                    supportingText = { Text(error ?: "drive.google.com/drive/folders/…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Statut des livres de ce dossier", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BookStatus.entries.forEach { st ->
                        FilterChip(selected = status == st, onClick = { status = st }, label = { Text(Labels.status(st)) })
                    }
                }
                Text("Mode", style = MaterialTheme.typography.titleSmall)
                PairMode.entries.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == m, role = Role.RadioButton, onClick = { mode = m }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(Labels.mode(m), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        vm.addPair(input, status, mode) { failure ->
                            busy = false
                            if (failure == null) onDismiss() else error = failure
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Vérifier et ajouter")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Annuler") } },
    )
}
