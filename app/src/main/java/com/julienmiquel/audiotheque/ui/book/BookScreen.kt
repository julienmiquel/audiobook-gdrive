@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.julienmiquel.audiotheque.ui.book

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.julienmiquel.audiotheque.AppContainer
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.BookmarkEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.CleanupPolicy
import com.julienmiquel.audiotheque.core.model.ProgressMath
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.model.chapterLabel
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.effectiveParentId
import com.julienmiquel.audiotheque.core.model.effectiveStatus
import com.julienmiquel.audiotheque.core.model.meaningfulTitle
import com.julienmiquel.audiotheque.core.settings.AppSettings
import com.julienmiquel.audiotheque.core.settings.StatusSyncMode
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.library.Gesture
import com.julienmiquel.audiotheque.library.LibraryItem
import com.julienmiquel.audiotheque.library.bookTimeline
import com.julienmiquel.audiotheque.player.Chapters
import com.julienmiquel.audiotheque.sync.engine.Transfer
import com.julienmiquel.audiotheque.ui.common.BookCover
import com.julienmiquel.audiotheque.ui.common.BookProgress
import com.julienmiquel.audiotheque.ui.common.InfoCard
import com.julienmiquel.audiotheque.ui.common.InfoRow
import com.julienmiquel.audiotheque.ui.common.Labels
import com.julienmiquel.audiotheque.ui.common.LocalAppContainer
import com.julienmiquel.audiotheque.ui.common.SectionTitle
import com.julienmiquel.audiotheque.ui.common.StatusTag
import com.julienmiquel.audiotheque.ui.common.Tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookUiState(
    val loaded: Boolean = false,
    val item: LibraryItem? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val embeddedChapters: List<com.julienmiquel.audiotheque.core.db.EmbeddedChapterEntity> = emptyList(),
    val progress: ProgressEntity? = null,
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val pairs: List<SyncPairEntity> = emptyList(),
    val duplicates: List<BookEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val transfers: Map<String, Transfer> = emptyMap(),
    val localRoot: String? = null,
) {
    val book: BookEntity? get() = item?.book
    val status: BookStatus? get() = item?.status
}

private data class Context(
    val pairs: List<SyncPairEntity>,
    val settings: AppSettings,
    val duplicates: List<BookEntity>,
    val transfers: Map<String, Transfer>,
    val localRoot: String?,
    val embeddedChapters: List<com.julienmiquel.audiotheque.core.db.EmbeddedChapterEntity>,
)

class BookViewModel(private val c: AppContainer, val bookId: String) : ViewModel() {
    private val duplicates = MutableStateFlow<List<BookEntity>>(emptyList())
    private val localRoot = MutableStateFlow<String?>(null)

    private val extras: kotlinx.coroutines.flow.Flow<Context> = combine(
        combine(c.library.observePairs(), c.settings.settings, duplicates) { p, s, d -> Triple(p, s, d) },
        c.engine.transfers.transfers,
        localRoot,
        c.library.observeEmbeddedChapters(bookId),
    ) { (p, s, d), t, r, emb -> Context(p, s, d, t, r, emb) }

    val state: StateFlow<BookUiState> = combine(
        c.library.observeItem(bookId),
        c.library.observeTracks(bookId),
        c.library.observeProgress(bookId),
        c.library.observeBookmarks(bookId),
        extras,
    ) { item, tracks, progress, bookmarks, ctx ->
        BookUiState(
            loaded = true,
            item = item,
            tracks = tracks,
            embeddedChapters = ctx.embeddedChapters,
            progress = progress,
            bookmarks = bookmarks,
            pairs = ctx.pairs,
            duplicates = ctx.duplicates,
            settings = ctx.settings,
            transfers = ctx.transfers.filterValues { it.bookId == bookId },
            localRoot = ctx.localRoot,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookUiState())

    init {
        viewModelScope.launch {
            c.library.observeItem(bookId).map { it?.book?.normalizedKey }.distinctUntilChanged().collect {
                duplicates.value = c.library.book(bookId)?.let { book -> c.library.duplicatesOf(book) }.orEmpty()
            }
        }
        viewModelScope.launch { localRoot.value = runCatching { c.storage.displayRoot() }.getOrNull() }
        // Pochette encore absente (Google Books injoignable la dernière fois ?) : nouvel essai.
        c.enrichIfNeeded(bookId)
    }

    fun gesture(gesture: Gesture) = c.applyGesture(bookId, gesture)

    fun move(pair: SyncPairEntity) = c.moveBook(bookId, pair)

    fun togglePinnedOffline() = c.togglePinnedOffline(bookId)

    fun play(trackId: String? = null, positionMs: Long? = null) = c.playBook(bookId, trackId, positionMs)

    fun saveCorrections(title: String, author: String, narrator: String, renameDriveTo: String? = null) {
        viewModelScope.launch {
            c.editor.save(bookId, title, author, narrator)
            if (renameDriveTo != null) {
                c.renameBookInDrive(bookId, renameDriveTo)
            }
            if (c.settings.current().googleBooksEnrichment) c.enrich(bookId, manual = false)
        }
    }

    fun renameInDrive(newName: String) = c.renameBookInDrive(bookId, newName)

    fun confirmTitle() {
        viewModelScope.launch {
            c.editor.confirm(bookId)
            if (c.settings.current().googleBooksEnrichment) c.enrich(bookId, manual = false)
        }
    }

    fun setHidden(hidden: Boolean) {
        viewModelScope.launch {
            c.editor.setHidden(bookId, hidden)
            c.message(if (hidden) "Masqué de la bibliothèque (Réglages › Afficher les dossiers masqués)" else "Affiché dans la bibliothèque")
        }
    }

    fun enrich() = c.enrich(bookId, manual = true)

    /** Supprime un exemplaire en double (cet exemplaire ou un autre) : corbeille Drive via la file d'opérations. */
    fun trash(id: String = bookId) = c.trashBook(id)

    /** Pochette choisie dans les photos du téléphone. */
    fun setCoverFromPhotos(uri: android.net.Uri) = c.setCoverFromPhotos(bookId, uri)

    /** Recherche Google Images ouverte dans le navigateur : titre, auteur, « livre audio couverture ». */
    fun imageSearchUrl(item: LibraryItem): String {
        val query = listOfNotNull(item.title, item.author, "livre audio couverture").joinToString(" ")
        return "https://www.google.com/search?tbm=isch&q=" + java.net.URLEncoder.encode(query, "UTF-8")
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { c.library.deleteBookmark(id) }
    }
}

@Composable
fun BookScreen(bookId: String, onBack: () -> Unit, onOpenBook: (String) -> Unit, onOpenPlayer: () -> Unit, contentPadding: PaddingValues) {
    val container = LocalAppContainer.current
    val vm: BookViewModel = viewModel(key = "book-$bookId") { BookViewModel(container, bookId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf(false) }

    /** Exemplaire dont la suppression est à confirmer (cet exemplaire ou un autre). */
    var trashing by remember { mutableStateOf<BookEntity?>(null) }
    val item = state.item
    val uriHandler = LocalUriHandler.current
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setCoverFromPhotos(uri)
    }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(item?.title ?: "Fiche livre", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
                },
                actions = {
                    if (item != null) {
                        val moveLabel = if (state.settings.statusSyncMode == StatusSyncMode.PERSONAL_FAMILY) {
                            "Déplacer le dossier dans Drive…"
                        } else {
                            "Déplacer vers…"
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'actions") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (item.pinnedOffline) {
                                                "Ne plus garder sur ce téléphone"
                                            } else {
                                                "Garder sur ce téléphone (hors ligne)"
                                            },
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Filled.OfflinePin, null) },
                                    onClick = {
                                        menu = false
                                        vm.togglePinnedOffline()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Corriger titre, auteur, voix") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = {
                                        menu = false
                                        editing = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Normaliser le nom sur Drive…") },
                                    leadingIcon = { Icon(Icons.Filled.CloudUpload, null) },
                                    onClick = {
                                        menu = false
                                        renaming = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(moveLabel) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                                    onClick = {
                                        menu = false
                                        moving = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Pochette et résumé en ligne") },
                                    leadingIcon = { Icon(Icons.Filled.Image, null) },
                                    onClick = {
                                        menu = false
                                        vm.enrich()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Chercher la pochette sur Google Images") },
                                    leadingIcon = { Icon(Icons.Filled.ImageSearch, null) },
                                    onClick = {
                                        menu = false
                                        uriHandler.openUri(vm.imageSearchUrl(item))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Choisir une image dans mes photos…") },
                                    leadingIcon = { Icon(Icons.Filled.AddPhotoAlternate, null) },
                                    onClick = {
                                        menu = false
                                        pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                )
                                if (item.book.hidden) {
                                    DropdownMenuItem(
                                        text = { Text("Réafficher dans la bibliothèque") },
                                        leadingIcon = { Icon(Icons.Filled.Visibility, null) },
                                        onClick = {
                                            menu = false
                                            vm.setHidden(false)
                                        },
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Ce n'est pas un livre (masquer)") },
                                        leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) },
                                        onClick = {
                                            menu = false
                                            vm.setHidden(true)
                                        },
                                    )
                                }
                                if (state.duplicates.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Supprimer ce doublon (corbeille Drive)…") },
                                        leadingIcon = { Icon(Icons.Filled.DeleteForever, null) },
                                        onClick = {
                                            menu = false
                                            trashing = item.book
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { inner ->
        when {
            !state.loaded -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            item == null -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("Ce livre n'est plus dans la bibliothèque Drive.", modifier = Modifier.padding(32.dp))
            }
            else -> BookContent(
                state = state,
                item = item,
                padding = inner,
                onPlay = { trackId, position ->
                    vm.play(trackId, position)
                    onOpenPlayer()
                },
                onGesture = vm::gesture,
                onMove = { moving = true },
                onTogglePin = vm::togglePinnedOffline,
                onEdit = { editing = true },
                onConfirm = vm::confirmTitle,
                onOpenBook = onOpenBook,
                onDeleteBookmark = vm::deleteBookmark,
                onTrash = { trashing = it },
            )
        }
    }

    if (editing && item != null) {
        EditDialog(
            item = item,
            onDismiss = { editing = false },
            onSave = { t, a, n, renameTarget ->
                vm.saveCorrections(t, a, n, renameTarget)
                editing = false
            },
        )
    }
    if (renaming && item != null) {
        RenameDriveDialog(
            item = item,
            onDismiss = { renaming = false },
            onRename = { newName ->
                vm.renameInDrive(newName)
                renaming = false
            },
        )
    }
    if (moving && item != null) {
        MoveDialog(
            book = item.book,
            pairs = state.pairs,
            familyMode = state.settings.statusSyncMode == StatusSyncMode.PERSONAL_FAMILY,
            onDismiss = { moving = false },
            onMove = { pair ->
                vm.move(pair)
                moving = false
            },
        )
    }
    trashing?.let { target ->
        val pair = state.pairs.firstOrNull { it.driveFolderId == target.effectiveParentId }
        val isThis = target.driveId == bookId
        AlertDialog(
            onDismissRequest = { trashing = null },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
            title = { Text("Supprimer ce doublon ?") },
            text = {
                Text(
                    "Le dossier « ${target.name} » (${pair?.name ?: Labels.status(target.effectiveStatus)}) sera mis à la corbeille Drive " +
                        "— restaurable pendant 30 jours depuis Drive — et ses pistes seront effacées du téléphone. " +
                        (if (isThis) "L'autre exemplaire reste dans la bibliothèque." else "Cet exemplaire-ci reste dans la bibliothèque."),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.trash(target.driveId)
                    trashing = null
                    if (isThis) onBack()
                }) { Text("Mettre à la corbeille") }
            },
            dismissButton = { TextButton(onClick = { trashing = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun BookContent(
    state: BookUiState,
    item: LibraryItem,
    padding: PaddingValues,
    onPlay: (String?, Long?) -> Unit,
    onGesture: (Gesture) -> Unit,
    onMove: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onOpenBook: (String) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onTrash: (BookEntity) -> Unit,
) {
    val book = item.book
    val progress = state.progress
    val groups = remember(state.tracks) { Chapters.group(state.tracks) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "header") { Header(item) }
        if (!book.titleConfirmed) {
            item(key = "confirm") {
                InfoCard(title = "Titre et auteur à confirmer", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        "Découpage proposé d'après « ${book.pendingName ?: book.name} ». Vous pouvez confirmer ou corriger ces informations (et normaliser le nom sur Drive si souhaité).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirm) { Text("Confirmer") }
                        OutlinedButton(onClick = onEdit) { Text("Corriger") }
                    }
                }
            }
        }
        item(key = "actions") { Actions(state, item, onPlay, onGesture, onMove, onTogglePin) }
        if (progress?.finished == true && item.status != BookStatus.DONE && item.status != BookStatus.ARCHIVED) {
            item(key = "finished") {
                InfoCard(title = "Livre terminé", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("Vous êtes arrivé à la fin de la dernière piste. Le marquer comme écouté ?", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onGesture(Gesture.MARQUER_ECOUTE) }) { Text("Marquer comme écouté") }
                }
            }
        }
        if (progress != null && (progress.percent > 0f || progress.lastListenedAt != null)) {
            item(key = "progress") { ProgressCard(state, progress) }
        }
        item(key = "info") { InfoSection(state, item) }
        if (state.duplicates.isNotEmpty()) {
            item(key = "duplicates") {
                InfoCard(title = "Doublon : ${state.duplicates.size + 1} exemplaires", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        "Même titre dans plusieurs dossiers Drive. Supprimer un exemplaire le met à la corbeille Drive (restaurable 30 jours).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    fun details(b: BookEntity): String = listOfNotNull(
                        b.trackCount.takeIf { it > 0 }?.let { "$it pistes" },
                        b.sizeBytes.takeIf { it > 0 }?.let { Formats.size(it) },
                        b.durationMs?.let { Formats.duration(it, approximate = !b.durationExact) },
                    ).joinToString(" · ")
                    state.duplicates.forEach { other ->
                        val pair = state.pairs.firstOrNull { it.driveFolderId == other.effectiveParentId }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(onClickLabel = "Ouvrir cet exemplaire") { onOpenBook(other.driveId) }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(
                                    "${other.name} — ${pair?.name ?: Labels.status(other.effectiveStatus)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                details(other).takeIf { it.isNotEmpty() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { onTrash(other) }) {
                                Icon(Icons.Filled.DeleteForever, contentDescription = "Supprimer cet exemplaire (corbeille Drive)", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    details(book).takeIf { it.isNotEmpty() }?.let {
                        Text("Celui-ci : $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { onTrash(book) }) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Supprimer celui-ci")
                    }
                }
            }
        }
        book.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            item(key = "summary") {
                InfoCard(title = "Résumé", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Réduire" else "Lire la suite") }
                    Text("Source : Google Books", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.bookmarks.isNotEmpty()) {
            item(key = "bookmarks-title") { SectionTitle("Signets") }
            items(state.bookmarks, key = { "bm-${it.id}" }) { bm ->
                val track = state.tracks.firstOrNull { it.driveId == bm.trackId }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Écouter depuis ce signet") { onPlay(bm.trackId, bm.positionMs) }
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bm.note.ifBlank { "Signet" }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${track?.chapterLabel ?: "Piste ${bm.trackIndex + 1}"} · ${Formats.clock(bm.positionMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onDeleteBookmark(bm.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer le signet") }
                }
            }
        }
        if (state.tracks.isNotEmpty()) {
            val embeddedByTrack = state.embeddedChapters.groupBy { it.trackId }
            val totalEmbedded = state.embeddedChapters.size
            val sectionHeader = if (state.tracks.size == 1 && totalEmbedded > 1) {
                "Chapitres intégrés · $totalEmbedded chapitres"
            } else {
                "Pistes · ${Labels.tracks(state.tracks.size)}"
            }
            item(key = "tracks-title") { SectionTitle(sectionHeader) }
            groups.forEach { group ->
                val label = group.label
                if (label != null) {
                    item(key = "group-$label-${group.tracks.first().driveId}") {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                                .semantics { heading() },
                        )
                    }
                }
                group.tracks.forEach { track ->
                    val embedded = embeddedByTrack[track.driveId].orEmpty()
                    item(key = "t-${track.driveId}") {
                        TrackRow(
                            track = track,
                            isCurrent = progress?.trackId == track.driveId && embedded.size <= 1,
                            transfer = state.transfers[track.driveId],
                            playable = true,
                            onPlay = { onPlay(track.driveId, 0L) },
                        )
                    }
                    if (embedded.size > 1) {
                        val present = track.state == TrackState.PRESENT
                        val activeCh = if (progress?.trackId == track.driveId) {
                            (embedded.lastOrNull { progress.positionMs >= it.startMs } ?: embedded.first()).chapterIndex
                        } else {
                            null
                        }
                        items(embedded, key = { "emb-${track.driveId}-${it.chapterIndex}" }) { ch ->
                            val isActive = ch.chapterIndex == activeCh
                            val dur = (ch.endMs - ch.startMs).coerceAtLeast(0L)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = present, onClickLabel = "Écouter ce chapitre") { onPlay(track.driveId, ch.startMs) }
                                    .padding(start = 44.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        ch.title.ifBlank { "Chapitre ${ch.chapterIndex + 1}" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        listOfNotNull(
                                            "Chapitre ${ch.chapterIndex + 1}",
                                            Formats.clock(ch.startMs),
                                            dur.takeIf { it > 0 }?.let { Formats.duration(it, approximate = false) },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (book.kind == com.julienmiquel.audiotheque.core.model.BookKind.ZIP) {
            item(key = "zip-pending") {
                val transfer = state.transfers[book.driveId]
                InfoCard(title = "Archive .zip", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    if (transfer != null && transfer.totalBytes > 0) {
                        Text(
                            "Téléchargement et décompression en cours : ${Formats.size(transfer.doneBytes)} / ${Formats.size(transfer.totalBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (transfer.doneBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            if (item.status == BookStatus.IN_PROGRESS) {
                                "L'archive .zip sera téléchargée puis décompressée sur le téléphone à la synchro en cours."
                            } else {
                                "Archive .zip sur Drive : touchez « Commencer » pour la télécharger et décompresser automatiquement ses pistes hors ligne."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else if (book.kind == com.julienmiquel.audiotheque.core.model.BookKind.FOLDER) {
            item(key = "no-tracks") {
                Text(
                    "Pistes non encore inventoriées : elles le seront à la prochaine synchro de ce statut.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(item: LibraryItem) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
        BookCover(item.cover, item.title, size = 112.dp, cornerRadius = 12.dp)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            item.author?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item.narrator?.let { Text("Lu par $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusTag(item.status)
                if (item.personalStatus != null && item.status != item.driveStatus) {
                    Tag("Drive : ${Labels.statusTab(item.driveStatus)}", icon = Icons.AutoMirrored.Filled.DriveFileMove)
                }
                if (item.pinnedOffline) {
                    Tag(
                        "gardé hors ligne",
                        icon = Icons.Filled.OfflinePin,
                        container = colors.primaryContainer,
                        content = colors.onPrimaryContainer,
                    )
                }
                item.series?.let { Tag(it.displayLabel, icon = Icons.Filled.Bookmarks) }
                if (item.pendingSync) {
                    val label = when {
                        item.book.pendingName != null && item.book.pendingStatus == null -> "renommage Drive en attente"
                        item.book.pendingName != null -> "opérations Drive en attente"
                        else -> "déplacement Drive en attente"
                    }
                    Tag(label, icon = Icons.Filled.CloudUpload)
                }
                if (item.isZip) Tag("zip", icon = Icons.Filled.FolderZip)
                if (item.finished) Tag("terminé", icon = Icons.Filled.DoneAll)
            }
        }
    }
}

@Composable
private fun Actions(
    state: BookUiState,
    item: LibraryItem,
    onPlay: (String?, Long?) -> Unit,
    onGesture: (Gesture) -> Unit,
    onMove: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val gestures = Gesture.forStatus(item.status)
    val familyMode = state.settings.statusSyncMode == StatusSyncMode.PERSONAL_FAMILY
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.presentTracks > 0) {
                val resume = (state.progress?.percent ?: 0f) > 0f && state.progress?.finished != true
                Button(onClick = { onPlay(null, null) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (resume) "Reprendre la lecture" else "Écouter")
                }
            }
            gestures.forEach { g ->
                FilledTonalButton(onClick = { onGesture(g) }) { Text(g.label) }
            }
            FilterChip(
                selected = item.pinnedOffline,
                onClick = onTogglePin,
                label = { Text(if (item.pinnedOffline) "Gardé sur ce téléphone" else "Garder hors ligne") },
                leadingIcon = { Icon(Icons.Filled.OfflinePin, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            TextButton(onClick = onMove) {
                Text(if (familyMode) "Déplacer dans Drive…" else "Déplacer vers…")
            }
        }
    }
}

@Composable
private fun ProgressCard(state: BookUiState, progress: ProgressEntity) {
    val durations = remember(state.tracks, state.book?.durationMs) { bookTimeline(state.tracks, state.book) }
    val track = state.tracks.firstOrNull { it.driveId == progress.trackId } ?: state.tracks.getOrNull(progress.trackIndex)
    val trackIndex = track?.sortIndex ?: progress.trackIndex
    val remaining = ProgressMath.remainingMs(durations, trackIndex, progress.positionMs, progress.speed)
    InfoCard(title = "Avancement", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        BookProgress(progress.percent)
        InfoRow("Avancement", Formats.percent(progress.percent))
        track?.let { InfoRow("Position", "${it.chapterLabel} · ${Formats.clock(progress.positionMs)}") }
        if (durations.sum() > 0) InfoRow("Temps restant", "${Formats.duration(remaining)} à ${Formats.speed(progress.speed)}")
        progress.lastListenedAt?.let { InfoRow("Dernière écoute", Formats.dateTime(it)) }
        progress.setAsideAtPercent?.let { InfoRow("Mis de côté à", Formats.percent(it)) }
    }
}

@Composable
private fun InfoSection(state: BookUiState, item: LibraryItem) {
    val book = item.book
    val pair = state.pairs.firstOrNull { it.driveFolderId == book.effectiveParentId }
    InfoCard(title = "Informations", modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (item.personalStatus != null) {
            InfoRow("Votre statut", Labels.status(item.status))
            InfoRow(
                "Emplacement Drive",
                Labels.status(item.driveStatus) + (pair?.let { " (${it.name})" } ?: ""),
            )
        } else {
            InfoRow("Statut", Labels.status(item.status) + (pair?.let { " (${it.name})" } ?: ""))
        }
        item.series?.let { InfoRow("Série", it.displayLabel) }
        InfoRow("Durée", Formats.duration(book.durationMs, approximate = book.durationMs != null && !book.durationExact) + if (book.durationMs != null && !book.durationExact) " (estimée)" else "")
        InfoRow("Taille", Formats.size(book.sizeBytes))
        InfoRow("Pistes", book.trackCount.toString())
        if (book.trackCount > 0) {
            val local = state.tracks.filter { it.state == TrackState.PRESENT }
            InfoRow("Sur le téléphone", "${local.size}/${book.trackCount} · ${Formats.size(local.sumOf { it.sizeBytes })}")
        }
        book.localDirName?.let { dir -> InfoRow("Dossier local", "${state.localRoot ?: "Audiobooks"}/$dir") }
        InfoRow(if (item.isZip) "Archive Drive" else "Dossier Drive", book.name)
        book.pendingName?.let { pending -> InfoRow("Renommage en attente", pending) }
        InfoRow("Ajouté le", Formats.date(book.addedAt))
        InfoRow("Statut changé le", Formats.date(item.statusChangedAt))
        cleanupInfo(state, item)?.let { (label, value) -> InfoRow(label, value) }
    }
}

/**
 * Nettoyage des pistes locales, avec la règle du moteur ([CleanupPolicy]) : rien tant que Drive
 * n'a pas confirmé le déplacement, jamais si « Gardé sur ce téléphone », puis 7 jours pour Plus tard,
 * 24 h pour Écouté (par défaut).
 */
private fun cleanupInfo(state: BookUiState, item: LibraryItem): Pair<String, String>? {
    if (item.presentTracks == 0) return null
    val book = item.book
    if (book.pinnedOffline) {
        return "Pistes gardées" to "toujours (gardé sur ce téléphone)"
    }
    if (book.pendingStatus != null) {
        return if (book.pendingStatus == BookStatus.IN_PROGRESS) null else "Pistes gardées" to "jusqu'à la confirmation du déplacement par Drive"
    }
    val mode = state.pairs.firstOrNull { it.driveFolderId == book.parentId }?.mode
    val deadline = CleanupPolicy.bookDeadline(
        book = book,
        pairMode = mode,
        settings = state.settings,
        personalStatus = state.progress?.personalStatus,
        personalStatusChangedAt = state.progress?.personalStatusChangedAt,
    ) ?: return null
    return "Pistes gardées jusqu'au" to Formats.dateTime(deadline)
}

@Composable
private fun TrackRow(track: TrackEntity, isCurrent: Boolean, transfer: Transfer?, playable: Boolean, onPlay: () -> Unit) {
    val present = track.state == TrackState.PRESENT
    val cleanTitle = track.meaningfulTitle()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = present && playable, onClickLabel = "Écouter cette piste", onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            transfer != null && transfer.totalBytes > 0 -> CircularProgressIndicator(
                progress = { (transfer.doneBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f) },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            present -> Icon(Icons.Filled.CheckCircle, contentDescription = "Sur le téléphone", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            track.state == TrackState.DOWNLOADING -> Icon(Icons.Filled.Downloading, contentDescription = "Téléchargement", modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Filled.CloudQueue, contentDescription = "Sur Drive uniquement", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                cleanTitle ?: track.chapterLabel,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    if (cleanTitle != null) track.chapterLabel else null,
                    track.durationMs?.let { Formats.duration(it, !track.durationExact) },
                    Formats.size(track.sizeBytes),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditDialog(item: LibraryItem, onDismiss: () -> Unit, onSave: (String, String, String, String?) -> Unit) {
    var title by rememberSaveable { mutableStateOf(item.title) }
    var author by rememberSaveable { mutableStateOf(item.author.orEmpty()) }
    var narrator by rememberSaveable { mutableStateOf(item.narrator.orEmpty()) }
    var renameOnDrive by rememberSaveable { mutableStateOf(false) }
    val currentDriveName = item.book.pendingName ?: item.book.name
    val normalizedPreview = com.julienmiquel.audiotheque.core.parse.FolderNameParser.normalizedDriveName(
        title = title.ifBlank { item.title },
        author = author.ifBlank { null },
        originalName = currentDriveName,
        kind = item.book.kind,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corriger le livre") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Nom actuel sur Drive : « $currentDriveName ». Ces corrections sont partagées avec tous les auditeurs de la famille.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("Auteur") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = narrator,
                    onValueChange = { narrator = it },
                    label = { Text("Voix (lu par)") },
                    supportingText = { Text("Lue dans les tags ID3 quand ils la portent") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { renameOnDrive = !renameOnDrive }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(checked = renameOnDrive, onCheckedChange = { renameOnDrive = it })
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Normaliser aussi le nom sur Drive", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "→ « $normalizedPreview »",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = if (renameOnDrive && normalizedPreview.isNotBlank() && normalizedPreview != currentDriveName) normalizedPreview else null
                    onSave(title, author, narrator, target)
                },
                enabled = title.isNotBlank(),
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun RenameDriveDialog(item: LibraryItem, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val currentDriveName = item.book.pendingName ?: item.book.name
    val suggested = remember(item.title, item.author, currentDriveName, item.book.kind) {
        com.julienmiquel.audiotheque.core.parse.FolderNameParser.normalizedDriveName(
            title = item.title,
            author = item.author,
            originalName = currentDriveName,
            kind = item.book.kind,
        )
    }
    var name by rememberSaveable { mutableStateOf(suggested) }
    val sanitized = com.julienmiquel.audiotheque.core.parse.FolderNameParser.sanitizeDriveName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Normaliser le nom sur Drive") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Renomme directement l'élément dans Google Drive (aussitôt ou dès le retour du réseau, annulable pendant 10 s).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Actuel : « $currentDriveName »",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nouveau nom sur Drive") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (name != suggested) {
                    TextButton(onClick = { name = suggested }) {
                        Text("Rétablir « $suggested »")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(sanitized) },
                enabled = sanitized.isNotBlank() && sanitized != currentDriveName,
            ) { Text("Renommer sur Drive") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun MoveDialog(
    book: BookEntity,
    pairs: List<SyncPairEntity>,
    familyMode: Boolean,
    onDismiss: () -> Unit,
    onMove: (SyncPairEntity) -> Unit,
) {
    val choices = pairs.filter { it.enabled || it.driveFolderId == book.effectiveParentId }
    var selected by remember { mutableStateOf(choices.firstOrNull { it.driveFolderId == book.effectiveParentId }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Déplacer « ${book.displayTitle} » sur Drive") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (familyMode) {
                        "Ce déplacement modifie physiquement le dossier parent dans Google Drive pour tous les membres de la famille."
                    } else {
                        "Le dossier change de parent dans Drive, aussitôt ou dès le retour du réseau."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                choices.forEach { pair ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected?.id == pair.id, role = Role.RadioButton, onClick = { selected = pair })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected?.id == pair.id, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(Labels.destination(pair), style = MaterialTheme.typography.bodyLarge)
                            Text(Labels.status(pair.status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onMove) },
                enabled = selected != null && selected?.driveFolderId != book.effectiveParentId,
            ) { Text("Déplacer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
