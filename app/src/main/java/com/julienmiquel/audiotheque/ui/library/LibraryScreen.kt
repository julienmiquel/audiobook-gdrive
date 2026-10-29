@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.julienmiquel.audiotheque.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.julienmiquel.audiotheque.AppContainer
import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.PairMode
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.model.effectiveParentId
import com.julienmiquel.audiotheque.core.settings.LibrarySort
import com.julienmiquel.audiotheque.core.settings.ListenerProfile
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.library.BookRecommendation
import com.julienmiquel.audiotheque.library.BookRecommender
import com.julienmiquel.audiotheque.library.Gesture
import com.julienmiquel.audiotheque.library.LibraryItem
import com.julienmiquel.audiotheque.library.LibraryRepository
import com.julienmiquel.audiotheque.sync.engine.EngineStatus
import com.julienmiquel.audiotheque.ui.common.BookCover
import com.julienmiquel.audiotheque.ui.common.BookProgress
import com.julienmiquel.audiotheque.ui.common.EmptyState
import com.julienmiquel.audiotheque.ui.common.Labels
import com.julienmiquel.audiotheque.ui.common.LocalAppContainer
import com.julienmiquel.audiotheque.ui.common.StatusTag
import com.julienmiquel.audiotheque.ui.common.Tag
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Onglet de la bibliothèque : un statut, ou une collection (paire ajoutée, _meditations). */
data class LibraryTab(val key: String, val label: String, val status: BookStatus, val pair: SyncPairEntity?) {
    fun matches(item: LibraryItem): Boolean =
        item.status == status && (status != BookStatus.COLLECTION || pair == null || item.book.effectiveParentId == pair.driveFolderId)

    val refreshesOnOpen: Boolean get() = pair != null && pair.enabled && pair.mode == PairMode.ON_OPEN
}

data class LibraryUiState(
    val loaded: Boolean = false,
    val tabs: List<LibraryTab> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val selected: LibraryTab? = null,
    val items: List<LibraryItem> = emptyList(),
    val recommendations: List<BookRecommendation> = emptyList(),
    val query: String = "",
    val narrator: String? = null,
    val narrators: List<String> = emptyList(),
    val offlineOnly: Boolean = false,
    val sort: LibrarySort = LibrarySort.DEFAULT,
    val gridView: Boolean = false,
    val engine: EngineStatus = EngineStatus(),
    val refreshingPairs: Set<Long> = emptySet(),
    val pendingMoves: Int = 0,
    val totalBooks: Int = 0,
    val hasMultipleListeners: Boolean = false,
    val activeListenerName: String = "Moi",
    val primaryListenerName: String = "Principal",
    val activeProfileKey: String? = null,
    val profiles: List<ListenerProfile> = emptyList(),
) {
    val searching: Boolean get() = query.isNotBlank()
}

private data class Filters(
    val query: String,
    val narrator: String?,
    val selectedKey: String?,
    val offlineOnly: Boolean,
)

private data class Activity(val engine: EngineStatus, val refreshing: Set<Long>, val pendingMoves: Int)

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    private val query = MutableStateFlow("")
    private val narrator = MutableStateFlow<String?>(null)
    private val selectedKey = MutableStateFlow<String?>(null)
    private val offlineOnly = MutableStateFlow(false)
    private val lastRefresh = mutableMapOf<Long, Long>()

    /** Listes, onglets et compteurs : recalculés hors du thread principal, seulement si la bibliothèque ou les filtres changent. */
    private val content = combine(
        c.library.observeLibrary(),
        c.library.observePairs(),
        c.settings.settings,
        combine(query, narrator, selectedKey, offlineOnly) { q, n, s, o -> Filters(q, n, s, o) },
    ) { all, pairs, settings, filters ->
        val tabs = buildTabs(pairs, settings.showArchives)
        val visible = all.filter { settings.showHidden || !it.book.hidden }
        val baseCounts = tabs.associate { tab -> tab.key to visible.count { tab.matches(it) } }
        val counts = if (filters.offlineOnly) {
            tabs.associate { tab -> tab.key to visible.count { tab.matches(it) && it.presentTracks > 0 } }
        } else {
            baseCounts
        }
        val selected = tabs.firstOrNull { it.key == filters.selectedKey }
            ?: tabs.firstOrNull { it.status == BookStatus.IN_PROGRESS && (baseCounts[it.key] ?: 0) > 0 }
            ?: tabs.firstOrNull { it.status == BookStatus.NEW }
            ?: tabs.firstOrNull()
        val items = when {
            filters.query.isNotBlank() ->
                LibraryRepository.filter(
                    items = all,
                    status = null,
                    query = filters.query,
                    narrator = filters.narrator,
                    showHidden = settings.showHidden,
                    includeArchives = settings.showArchives,
                    offlineOnly = filters.offlineOnly,
                    sort = settings.librarySort,
                )
            selected != null ->
                LibraryRepository.filter(
                    items = all,
                    status = selected.status,
                    query = "",
                    narrator = filters.narrator,
                    showHidden = settings.showHidden,
                    offlineOnly = filters.offlineOnly,
                    sort = settings.librarySort,
                ).filter { selected.matches(it) }
            else -> emptyList()
        }
        val recommendations = if (
            filters.query.isBlank() &&
            filters.narrator == null &&
            !filters.offlineOnly &&
            selected?.status == BookStatus.NEW
        ) {
            BookRecommender.recommend(visible, limit = 5)
        } else {
            emptyList()
        }
        val primaryName = settings.accountName?.takeIf { it.isNotBlank() }
            ?: settings.accountEmail?.substringBefore('@')?.replaceFirstChar { it.titlecase() }
            ?: "Principal"
        LibraryUiState(
            loaded = true,
            tabs = tabs,
            counts = counts,
            selected = selected,
            items = items,
            recommendations = recommendations,
            query = filters.query,
            narrator = filters.narrator,
            narrators = LibraryRepository.narrators(visible),
            offlineOnly = filters.offlineOnly,
            sort = settings.librarySort,
            gridView = settings.libraryGrid,
            totalBooks = visible.size,
            hasMultipleListeners = settings.hasMultipleListeners,
            activeListenerName = settings.activeListenerName,
            primaryListenerName = primaryName,
            activeProfileKey = settings.activeProfileKey,
            profiles = settings.profiles,
        )
    }.flowOn(Dispatchers.Default)

    /** Activité du moteur, sans le détail (qui change à chaque livre) : fusion légère. */
    private val activity = combine(
        c.engine.status.map { it.copy(detail = null) }.distinctUntilChanged(),
        c.engine.refreshingPairs,
        c.library.observePendingOperations(),
    ) { e, r, p -> Activity(e, r, p) }

    val state: StateFlow<LibraryUiState> = combine(content, activity) { content, activity ->
        content.copy(engine = activity.engine, refreshingPairs = activity.refreshing, pendingMoves = activity.pendingMoves)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** Texte saisi dans la recherche (mis à jour aussitôt, pour le champ de saisie). */
    val queryText: StateFlow<String> = query

    fun setQuery(text: String) = query.update { text }

    fun setNarrator(name: String?) = narrator.update { name }

    fun toggleOfflineOnly() = offlineOnly.update { !it }

    fun switchProfile(profileKey: String?) = c.switchProfile(profileKey)

    fun setSort(sort: LibrarySort) {
        viewModelScope.launch {
            c.settings.update { it.copy(librarySort = sort) }
        }
    }

    fun toggleGridView() {
        viewModelScope.launch {
            c.settings.update { it.copy(libraryGrid = !it.libraryGrid) }
        }
    }

    fun select(tab: LibraryTab) {
        selectedKey.value = tab.key
        refreshIfStale(tab)
    }

    /** Archives, méditations, racine : métadonnées lues à l'ouverture de l'onglet. */
    fun refreshIfStale(tab: LibraryTab?) {
        val pair = tab?.pair ?: return
        if (!tab.refreshesOnOpen) return
        val now = System.currentTimeMillis()
        val last = maxOf(pair.lastInventoryAt ?: 0L, lastRefresh[pair.id] ?: 0L)
        if (now - last < STALE_MS) return
        lastRefresh[pair.id] = now
        c.refreshFolder(pair.id)
    }

    fun refresh(tab: LibraryTab?) {
        val pair = tab?.pair
        if (tab != null && pair != null && tab.refreshesOnOpen) {
            lastRefresh[pair.id] = System.currentTimeMillis()
            c.refreshFolder(pair.id, silent = false)
        } else {
            c.requestSync(SyncTrigger.MANUAL)
        }
    }

    fun gesture(item: LibraryItem, gesture: Gesture) = c.applyGesture(item.id, gesture)

    fun play(item: LibraryItem) {
        viewModelScope.launch { c.playBook(item.id) }
    }

    companion object {
        private const val STALE_MS = 10 * 60_000L

        private val STATUS_ORDER = listOf(
            BookStatus.IN_PROGRESS,
            BookStatus.NEW,
            BookStatus.LATER,
            BookStatus.DONE,
            BookStatus.ARCHIVED,
            BookStatus.UNSORTED,
        )

        fun buildTabs(pairs: List<SyncPairEntity>, showArchives: Boolean): List<LibraryTab> {
            val statusTabs = STATUS_ORDER.filter { it != BookStatus.ARCHIVED || showArchives }.map { status ->
                LibraryTab("status:${status.name}", Labels.statusTab(status), status, pairs.firstOrNull { it.status == status })
            }
            val collections = pairs.filter { it.status == BookStatus.COLLECTION }.map { pair ->
                LibraryTab("pair:${pair.id}", Labels.pairTitle(pair), BookStatus.COLLECTION, pair)
            }
            return statusTabs + collections
        }

        /** Geste proposé directement dans la liste. */
        fun inlineGesture(item: LibraryItem): Gesture? = when (item.status) {
            BookStatus.UNSORTED -> Gesture.CLASSER
            BookStatus.NEW -> Gesture.COMMENCER
            BookStatus.LATER -> Gesture.REPRENDRE
            else -> null
        }
    }
}

@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit = {},
    contentPadding: PaddingValues,
) {
    val container = LocalAppContainer.current
    val vm: LibraryViewModel = viewModel { LibraryViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Texte de recherche lu directement (sans attendre le calcul des résultats en arrière-plan).
    val queryText by vm.queryText.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var narratorMenu by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }

    LaunchedEffect(state.selected?.key) { vm.refreshIfStale(state.selected) }
    LaunchedEffect(pulling, state.engine.running) {
        if (pulling) {
            delay(if (state.engine.running) 600 else 2_500)
            pulling = false
        }
    }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (searchOpen) {
                SearchTopBar(
                    query = queryText,
                    onQuery = vm::setQuery,
                    onClose = {
                        vm.setQuery("")
                        searchOpen = false
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Bibliothèque") },
                    actions = {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Rechercher")
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Trier la bibliothèque")
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                LibrarySort.entries.forEach { sortOption ->
                                    val active = sortOption == state.sort
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                sortOption.label,
                                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        },
                                        leadingIcon = if (active) {
                                            { Icon(Icons.Filled.Check, contentDescription = null) }
                                        } else {
                                            null
                                        },
                                        onClick = {
                                            vm.setSort(sortOption)
                                            sortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = vm::toggleGridView) {
                            if (state.gridView) {
                                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "Affichage en liste")
                            } else {
                                Icon(Icons.Filled.GridView, contentDescription = "Affichage en grille")
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Réglages")
                        }
                    },
                )
            }
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            if (!state.searching && state.tabs.isNotEmpty()) {
                val index = state.tabs.indexOfFirst { it.key == state.selected?.key }.coerceAtLeast(0)
                PrimaryScrollableTabRow(selectedTabIndex = index, edgePadding = 8.dp) {
                    state.tabs.forEachIndexed { i, tab ->
                        val count = state.counts[tab.key] ?: 0
                        Tab(
                            selected = i == index,
                            onClick = { vm.select(tab) },
                            text = { Text(if (count > 0) "${tab.label} · $count" else tab.label, maxLines = 1) },
                            modifier = Modifier.semantics { contentDescription = "${tab.label}, ${Labels.books(count)}" },
                        )
                    }
                }
            }
            ActivityBar(state)
            QuickFilterRow(
                state = state,
                profileMenu = profileMenu,
                onProfileMenuChange = { profileMenu = it },
                onSwitchProfile = vm::switchProfile,
                onManageProfiles = onOpenSettings,
                narratorMenu = narratorMenu,
                onNarratorMenuChange = { narratorMenu = it },
                onToggleOffline = vm::toggleOfflineOnly,
                onSelectNarrator = vm::setNarrator,
                onResetSort = { vm.setSort(LibrarySort.DEFAULT) },
                onOpenStats = onOpenStats,
            )
            val refreshing = pulling || (state.selected?.pair?.id?.let { it in state.refreshingPairs } ?: false)
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    pulling = true
                    vm.refresh(state.selected)
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                val onPlayBook: (LibraryItem) -> Unit = { item ->
                    vm.play(item)
                    onOpenPlayer()
                }
                if (state.gridView && !(state.loaded && state.items.isEmpty())) {
                    BookGrid(
                        state = state,
                        onOpenBook = onOpenBook,
                        onGesture = vm::gesture,
                        onPlay = onPlayBook,
                    )
                } else {
                    BookList(
                        state = state,
                        onOpenBook = onOpenBook,
                        onGesture = vm::gesture,
                        onPlay = onPlayBook,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickFilterRow(
    state: LibraryUiState,
    profileMenu: Boolean,
    onProfileMenuChange: (Boolean) -> Unit,
    onSwitchProfile: (String?) -> Unit,
    onManageProfiles: () -> Unit,
    narratorMenu: Boolean,
    onNarratorMenuChange: (Boolean) -> Unit,
    onToggleOffline: () -> Unit,
    onSelectNarrator: (String?) -> Unit,
    onResetSort: () -> Unit,
    onOpenStats: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.hasMultipleListeners) {
            item(key = "chip-profile") {
                Box {
                    FilterChip(
                        selected = true,
                        onClick = { onProfileMenuChange(true) },
                        label = { Text(state.activeListenerName, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = profileMenu, onDismissRequest = { onProfileMenuChange(false) }) {
                        val primarySelected = state.activeProfileKey == null
                        DropdownMenuItem(
                            text = {
                                Text(
                                    state.primaryListenerName,
                                    fontWeight = if (primarySelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            leadingIcon = if (primarySelected) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else {
                                { Icon(Icons.Filled.Person, contentDescription = null) }
                            },
                            onClick = {
                                onSwitchProfile(null)
                                onProfileMenuChange(false)
                            },
                        )
                        state.profiles.forEach { profile ->
                            val selected = state.activeProfileKey == profile.key
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        profile.displayName,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else {
                                    { Icon(Icons.Filled.Person, contentDescription = null) }
                                },
                                onClick = {
                                    onSwitchProfile(profile.key)
                                    onProfileMenuChange(false)
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Gérer les auditeurs…") },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = {
                                onProfileMenuChange(false)
                                onManageProfiles()
                            },
                        )
                    }
                }
            }
        }
        item(key = "chip-offline") {
            FilterChip(
                selected = state.offlineOnly,
                onClick = onToggleOffline,
                label = { Text("Hors ligne", maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.OfflinePin, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (state.narrators.isNotEmpty() || state.narrator != null) {
            item(key = "chip-narrator") {
                Box {
                    FilterChip(
                        selected = state.narrator != null,
                        onClick = {
                            if (state.narrator != null && state.narrators.isEmpty()) {
                                onSelectNarrator(null)
                            } else {
                                onNarratorMenuChange(true)
                            }
                        },
                        label = { Text(state.narrator?.let { "Lu par $it" } ?: "Voix", maxLines = 1) },
                        leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = if (state.narrator != null) {
                            { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                    )
                    DropdownMenu(expanded = narratorMenu, onDismissRequest = { onNarratorMenuChange(false) }) {
                        DropdownMenuItem(
                            text = { Text("Toutes les voix") },
                            onClick = {
                                onSelectNarrator(null)
                                onNarratorMenuChange(false)
                            },
                        )
                        state.narrators.forEach { name ->
                            DropdownMenuItem(
                                text = { Text("Lu par $name") },
                                onClick = {
                                    onSelectNarrator(name)
                                    onNarratorMenuChange(false)
                                },
                            )
                        }
                    }
                }
            }
        }
        if (state.sort != LibrarySort.DEFAULT) {
            item(key = "chip-sort") {
                FilterChip(
                    selected = true,
                    onClick = onResetSort,
                    label = { Text(state.sort.label, maxLines = 1) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Réinitialiser le tri", modifier = Modifier.size(16.dp)) },
                )
            }
        }
        item(key = "chip-stats") {
            AssistChip(
                onClick = onOpenStats,
                label = { Text("Statistiques", maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.BarChart, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun SearchTopBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Fermer la recherche") }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Titre, auteur, série, voix…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Effacer") }
            }
        },
    )
}

/** Synchro en cours et opérations Drive en attente, sous les onglets. */
@Composable
private fun ActivityBar(state: LibraryUiState) {
    val engine = state.engine
    if (engine.running) {
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            val text = buildString {
                append("Synchro : ").append(engine.phase.label)
                if (engine.queuedTracks > 0) append(" · ${Labels.tracks(engine.queuedTracks)} à recevoir")
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    if (state.pendingMoves > 0) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.pendingMoves == 1) "1 opération Drive en attente" else "${state.pendingMoves} opérations Drive en attente",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BookList(
    state: LibraryUiState,
    onOpenBook: (String) -> Unit,
    onGesture: (LibraryItem, Gesture) -> Unit,
    onPlay: (LibraryItem) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (state.searching) {
            item(key = "search-header") {
                Text(
                    "Résultats dans toute la bibliothèque · ${Labels.books(state.items.size)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else if (state.recommendations.isNotEmpty()) {
            item(key = "recommendations", contentType = "recommendations") {
                RecommendationsStrip(
                    recommendations = state.recommendations,
                    onOpenBook = onOpenBook,
                    onGesture = onGesture,
                )
            }
        }
        if (state.loaded && state.items.isEmpty()) {
            item(key = "empty") { EmptyLibrary(state) }
        }
        items(state.items, key = { it.id }, contentType = { "book" }) { item ->
            BookRow(
                item = item,
                showStatus = state.searching,
                onClick = { onOpenBook(item.id) },
                onGesture = { onGesture(item, it) },
                onPlay = { onPlay(item) },
            )
            HorizontalDivider(Modifier.padding(start = 88.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun BookGrid(
    state: LibraryUiState,
    onOpenBook: (String) -> Unit,
    onGesture: (LibraryItem, Gesture) -> Unit,
    onPlay: (LibraryItem) -> Unit,
) {
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.searching) {
            item(key = "search-header", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Résultats dans toute la bibliothèque · ${Labels.books(state.items.size)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        } else if (state.recommendations.isNotEmpty()) {
            item(key = "recommendations", span = { GridItemSpan(maxLineSpan) }, contentType = "recommendations") {
                RecommendationsStrip(
                    recommendations = state.recommendations,
                    onOpenBook = onOpenBook,
                    onGesture = onGesture,
                )
            }
        }
        items(state.items, key = { it.id }, contentType = { "book-grid" }) { item ->
            BookGridCard(
                item = item,
                showStatus = state.searching,
                onClick = { onOpenBook(item.id) },
                onGesture = { onGesture(item, it) },
                onPlay = { onPlay(item) },
            )
        }
    }
}

@Composable
private fun BookGridCard(
    item: LibraryItem,
    showStatus: Boolean,
    onClick: () -> Unit,
    onGesture: (Gesture) -> Unit,
    onPlay: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val gestures = Gesture.forStatus(item.status)
    Box {
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardDefaults.outlinedShape)
                .combinedClickable(
                    onClickLabel = "Ouvrir la fiche",
                    onLongClickLabel = "Changer de statut",
                    onLongClick = { if (gestures.isNotEmpty()) menu = true },
                    onClick = onClick,
                ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = gridInitials(item.title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    if (!item.cover.isNullOrBlank()) {
                        AsyncImage(
                            model = if (item.cover!!.startsWith("http")) item.cover else File(item.cover!!),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (item.isDownloaded) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.OfflinePin,
                                contentDescription = "Disponible hors ligne",
                                modifier = Modifier
                                    .padding(4.dp)
                                    .size(16.dp),
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (showStatus) {
                        StatusTag(item.status)
                    }
                    item.series?.let { series ->
                        Tag(series.displayLabel, icon = Icons.Filled.Bookmarks)
                    }
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.author?.let { author ->
                        Text(
                            author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val percent = item.percent
                    if (percent != null && percent > 0f && !item.finished) {
                        BookProgress(percent, label = Formats.percent(percent))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.durationMs?.let { Formats.duration(it, approximate = !item.durationExact) } ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        val gesture = LibraryViewModel.inlineGesture(item)
                        when {
                            item.presentTracks > 0 && (item.status == BookStatus.IN_PROGRESS || item.status == BookStatus.COLLECTION) ->
                                FilledTonalIconButton(
                                    onClick = onPlay,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = "Écouter ${item.title}",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            gesture != null ->
                                FilledTonalButton(
                                    onClick = { onGesture(gesture) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp),
                                ) {
                                    Text(gesture.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            gestures.forEach { g ->
                DropdownMenuItem(
                    text = { Text(g.label) },
                    onClick = {
                        menu = false
                        onGesture(g)
                    },
                )
            }
        }
    }
}

private fun gridInitials(title: String): String {
    val words = title.split(' ', '-', '\'', '’').filter { w -> w.any { it.isLetterOrDigit() } }
    val significant = words.filter { it.length > 2 }.ifEmpty { words }
    return significant.take(2).joinToString("") { w -> w.first { it.isLetterOrDigit() }.uppercase() }.ifEmpty { "♪" }
}

@Composable
private fun RecommendationsStrip(
    recommendations: List<BookRecommendation>,
    onOpenBook: (String) -> Unit,
    onGesture: (LibraryItem, Gesture) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
        Text(
            "À écouter ensuite",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(recommendations, key = { "rec-${it.item.id}" }) { rec ->
                OutlinedCard(
                    onClick = { onOpenBook(rec.item.id) },
                    modifier = Modifier.width(248.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BookCover(rec.item.cover, rec.item.title, size = 48.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rec.item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                rec.item.author?.let { author ->
                                    Text(
                                        author,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                rec.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            val gesture = if (rec.item.status == BookStatus.LATER) Gesture.REPRENDRE else Gesture.COMMENCER
                            FilledTonalButton(
                                onClick = { onGesture(rec.item, gesture) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text(gesture.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun EmptyLibrary(state: LibraryUiState) {
    val status = state.selected?.status
    when {
        state.searching -> EmptyState(Icons.Filled.SearchOff, "Aucun résultat", "Rien ne correspond à « ${state.query} » dans la bibliothèque.")
        state.totalBooks == 0 && state.engine.running -> EmptyState(Icons.Filled.Sync, "Première synchro en cours", "Inventaire de /AudioBook et de ses dossiers de statut…")
        state.offlineOnly -> EmptyState(Icons.Filled.OfflinePin, "Aucun livre hors ligne", "Aucun livre de cet onglet n'a encore de piste téléchargée sur le téléphone.")
        state.narrator != null -> EmptyState(Icons.Filled.RecordVoiceOver, "Aucun livre lu par ${state.narrator}", "dans cet onglet.")
        status == BookStatus.IN_PROGRESS -> EmptyState(Icons.Filled.Headphones, "Aucun livre en écoute", "Commencez un livre depuis l'onglet Nouveaux : il sera téléchargé en entier pour l'écoute hors ligne.")
        status == BookStatus.NEW -> EmptyState(Icons.Filled.Inbox, "Aucun nouveau livre", "Les livres déposés dans _Nouveaux sur Drive apparaissent ici.")
        status == BookStatus.LATER -> EmptyState(Icons.Filled.WatchLater, "Rien à écouter plus tard", "Un livre mis de côté garde sa position et reprend là où il s'était arrêté.")
        status == BookStatus.DONE -> EmptyState(Icons.Filled.DoneAll, "Aucun livre écouté", "Les livres terminés sont rangés dans _Ecouté.")
        status == BookStatus.ARCHIVED -> EmptyState(Icons.Filled.Archive, "Aucune archive", "Les métadonnées de _archives sont lues à l'ouverture de cet onglet.")
        status == BookStatus.UNSORTED -> EmptyState(Icons.AutoMirrored.Filled.LibraryBooks, "Tout est classé", "Les dossiers posés à la racine /AudioBook apparaissent ici.")
        else -> EmptyState(Icons.AutoMirrored.Filled.LibraryBooks, "Collection vide", "Tirez vers le bas pour lire le dossier Drive.")
    }
}

@Composable
fun BookRow(
    item: LibraryItem,
    showStatus: Boolean,
    onClick: () -> Unit,
    onGesture: (Gesture) -> Unit,
    onPlay: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val gestures = Gesture.forStatus(item.status)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = "Ouvrir la fiche",
                    onLongClickLabel = "Changer de statut",
                    onLongClick = { if (gestures.isNotEmpty()) menu = true },
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(item.cover, item.title, size = 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.author?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                item.narrator?.let {
                    Text("Lu par $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showStatus) StatusTag(item.status)
                    val duration = Formats.duration(item.durationMs, approximate = !item.durationExact)
                    if (item.durationMs != null) {
                        Text(duration, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BookBadges(item)
                }
                val percent = item.percent
                if (percent != null && percent > 0f && !item.finished) {
                    Spacer(Modifier.height(4.dp))
                    BookProgress(percent, label = Formats.percent(percent))
                }
            }
            Spacer(Modifier.width(8.dp))
            InlineAction(item, onGesture, onPlay)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            gestures.forEach { g ->
                DropdownMenuItem(
                    text = { Text(g.label) },
                    onClick = {
                        menu = false
                        onGesture(g)
                    },
                )
            }
        }
    }
}

@Composable
private fun BookBadges(item: LibraryItem) {
    val colors = MaterialTheme.colorScheme
    item.series?.let { Tag(it.displayLabel, icon = Icons.Filled.Bookmarks) }
    if (item.isZip) Tag("zip", icon = Icons.Filled.FolderZip)
    if (item.isDuplicate) Tag("doublon", icon = Icons.Filled.ContentCopy, container = colors.tertiaryContainer, content = colors.onTertiaryContainer)
    if (item.pendingSync) Tag("en attente", icon = Icons.Filled.CloudUpload)
    if (item.finished) Tag("terminé", icon = Icons.Filled.DoneAll)
    when {
        item.pinnedOffline && item.isDownloaded ->
            Tag("gardé hors ligne", icon = Icons.Filled.OfflinePin, container = colors.primaryContainer, content = colors.onPrimaryContainer)
        item.pinnedOffline ->
            Tag(
                if (item.trackCount > 0) "épinglé (${item.presentTracks}/${item.trackCount})" else "épinglé",
                icon = Icons.Filled.OfflinePin,
            )
        item.isDownloaded ->
            Tag("hors ligne", icon = Icons.Filled.OfflinePin, container = colors.primaryContainer, content = colors.onPrimaryContainer)
        item.presentTracks > 0 ->
            Tag("${item.presentTracks}/${item.trackCount}", icon = Icons.Filled.Downloading)
    }
}

@Composable
private fun InlineAction(item: LibraryItem, onGesture: (Gesture) -> Unit, onPlay: () -> Unit) {
    val gesture = LibraryViewModel.inlineGesture(item)
    when {
        item.presentTracks > 0 && (item.status == BookStatus.IN_PROGRESS || item.status == BookStatus.COLLECTION) ->
            FilledTonalIconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = "Écouter ${item.title}") }
        gesture != null ->
            FilledTonalButton(onClick = { onGesture(gesture) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text(gesture.label, maxLines = 1)
            }
    }
}
