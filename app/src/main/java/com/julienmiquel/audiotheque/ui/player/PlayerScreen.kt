@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package com.julienmiquel.audiotheque.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.julienmiquel.audiotheque.AppContainer
import com.julienmiquel.audiotheque.core.db.BookmarkEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.AudioPreset
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.ProgressMath
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.model.chapterLabel
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.library.Gesture
import com.julienmiquel.audiotheque.library.LibraryItem
import com.julienmiquel.audiotheque.library.LibraryRepository
import com.julienmiquel.audiotheque.player.Chapters
import com.julienmiquel.audiotheque.player.PlaybackService
import com.julienmiquel.audiotheque.player.PlayerRuntime
import com.julienmiquel.audiotheque.player.PlayerUiState
import com.julienmiquel.audiotheque.player.SleepTimerState
import com.julienmiquel.audiotheque.ui.common.BookCover
import com.julienmiquel.audiotheque.ui.common.EmptyState
import com.julienmiquel.audiotheque.ui.common.InfoCard
import com.julienmiquel.audiotheque.ui.common.LocalAppContainer
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Données du livre affiché, lues en base. */
private data class BookData(
    val item: LibraryItem?,
    val tracks: List<TrackEntity>,
    val progress: ProgressEntity?,
    val bookmarks: List<BookmarkEntity>,
)

data class PlayerScreenState(
    val loaded: Boolean = false,
    val bookId: String? = null,
    val item: LibraryItem? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val presentCount: Int = 0,
    val progress: ProgressEntity? = null,
    val bookmarks: List<BookmarkEntity> = emptyList(),
    /** Le service de lecture a ce livre chargé. */
    val isCurrent: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val currentTrack: TrackEntity? = null,
    /** Position dans le chapitre en cours. */
    val positionMs: Long = 0,
    /** Durée du chapitre en cours (0 si inconnue) : réelle quand le lecteur l'a, sinon estimée. */
    val chapterDurationMs: Long = 0,
    val globalMs: Long = 0,
    val totalMs: Long = 0,
    val percent: Float = 0f,
    val remainingMs: Long = 0,
    val speed: Float = 1f,
    val durations: List<Long> = emptyList(),
    val playlistTrackIds: List<String> = emptyList(),
    val sleep: SleepTimerState = SleepTimerState.Off,
    val skipSeconds: Int = 30,
    /** Durée de conservation des pistes d'un livre mis de côté (réglage). */
    val laterRetentionDays: Int = 7,
    val anotherBookPlaying: Boolean = false,
    /** Préréglage audio en cours (pour tous les livres). */
    val audioPreset: AudioPreset = AudioPreset.NONE,
)

/** Sous-ensemble des réglages utiles à l'écran de lecture. */
private data class PlayerPrefs(val skipSeconds: Int, val laterRetentionDays: Int, val audioPreset: AudioPreset)

class PlayerViewModel(private val c: AppContainer) : ViewModel() {

    /** Livre affiché : choisi par l'utilisateur ou lu par le service, sinon le dernier écouté. */
    private val bookId: Flow<String?> = combine(
        c.selectedBookId,
        c.settings.settings.map { it.lastPlayedBookId }.distinctUntilChanged(),
        c.library.observeLibrary().map { items ->
            items.filter { it.status == BookStatus.IN_PROGRESS && !it.isZip }.maxByOrNull { it.lastListenedAt ?: 0L }?.id
        }.distinctUntilChanged(),
    ) { selected, last, inProgress -> selected ?: last ?: inProgress }.distinctUntilChanged()

    private val data: Flow<Pair<String?, BookData?>> = bookId.flatMapLatest { id ->
        if (id == null) {
            flowOf(null to null)
        } else {
            combine(
                c.library.observeItem(id),
                c.library.observeTracks(id),
                c.library.observeProgress(id),
                c.library.observeBookmarks(id),
            ) { item, tracks, progress, bookmarks -> id to BookData(item, tracks, progress, bookmarks) }
        }
    }

    val state: StateFlow<PlayerScreenState> = combine(
        data,
        c.player.state,
        PlayerRuntime.sleepTimer,
        c.settings.settings.map { PlayerPrefs(it.skipSeconds, it.laterRetentionDays, it.audioPreset) }.distinctUntilChanged(),
    ) { (id, book), player, sleep, prefs -> build(id, book, player, sleep, prefs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerScreenState())

    private fun build(id: String?, book: BookData?, player: PlayerUiState, sleep: SleepTimerState, prefs: PlayerPrefs): PlayerScreenState {
        if (id == null || book == null) {
            return PlayerScreenState(loaded = true, sleep = sleep, skipSeconds = prefs.skipSeconds, laterRetentionDays = prefs.laterRetentionDays, audioPreset = prefs.audioPreset)
        }
        val tracks = book.tracks
        val isCurrent = player.bookId == id && player.playlistTrackIds.isNotEmpty()
        val current = if (isCurrent) {
            tracks.firstOrNull { it.driveId == player.trackId }
        } else {
            tracks.firstOrNull { it.driveId == book.progress?.trackId } ?: tracks.getOrNull(book.progress?.trackIndex ?: 0)
        }
        val index = current?.let { tracks.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val position = if (isCurrent) player.positionMs else book.progress?.positionMs ?: 0
        val speed = if (isCurrent) player.speed else book.progress?.speed ?: 1f
        var durations = ProgressMath.durations(tracks, book.item?.book?.durationMs)
        if (isCurrent && current != null && player.durationMs > 0 && index < durations.size) {
            durations = durations.toMutableList().also { it[index] = player.durationMs }
        }
        return PlayerScreenState(
            loaded = true,
            bookId = id,
            item = book.item,
            tracks = tracks,
            presentCount = tracks.count { it.state == TrackState.PRESENT },
            progress = book.progress,
            bookmarks = book.bookmarks,
            isCurrent = isCurrent,
            isPlaying = isCurrent && player.isPlaying,
            buffering = isCurrent && player.buffering,
            currentTrack = current,
            positionMs = position,
            chapterDurationMs = durations.getOrElse(index) { 0L },
            globalMs = ProgressMath.globalPosition(durations, index, position),
            totalMs = ProgressMath.total(durations),
            percent = if (book.progress?.finished == true && !isCurrent) 100f else ProgressMath.percent(durations, index, position),
            remainingMs = ProgressMath.remainingMs(durations, index, position, speed),
            speed = speed,
            durations = durations,
            playlistTrackIds = if (isCurrent) player.playlistTrackIds else emptyList(),
            sleep = sleep,
            skipSeconds = prefs.skipSeconds,
            laterRetentionDays = prefs.laterRetentionDays,
            anotherBookPlaying = player.isPlaying && player.bookId != null && player.bookId != id,
            audioPreset = prefs.audioPreset,
        )
    }

    /** Positionne le livre affiché dans le service (sans lancer la lecture) dès qu'une piste est là. */
    fun ensureLoaded() {
        val s = state.value
        val id = s.bookId ?: return
        if (!s.isCurrent && s.presentCount > 0 && !s.anotherBookPlaying && s.item?.isZip != true) {
            c.playBook(id, autoPlay = false)
        }
    }

    fun playPause() {
        val s = state.value
        val id = s.bookId ?: return
        when {
            s.isCurrent -> c.player.togglePlayPause()
            s.presentCount > 0 -> c.playBook(id)
            else -> c.message("Aucune piste sur le téléphone pour l'instant : la lecture se fait hors ligne, après téléchargement.")
        }
    }

    fun skipBack() = ifCurrent { c.player.seekBack() }

    fun skipForward() = ifCurrent { c.player.seekForward() }

    fun previousChapter() = ifCurrent { c.player.previousChapter() }

    fun nextChapter() = ifCurrent { c.player.nextChapter() }

    private inline fun ifCurrent(action: () -> Unit) {
        if (state.value.isCurrent) action() else ensureLoaded()
    }

    /** Barre du chapitre en cours : position dans ce chapitre. */
    fun seekInChapter(positionMs: Long) {
        val track = state.value.currentTrack ?: return
        jumpTo(track, positionMs.coerceIn(0L, state.value.chapterDurationMs.coerceAtLeast(0L)))
    }

    fun jumpTo(track: TrackEntity, positionMs: Long = 0) {
        val s = state.value
        val id = s.bookId ?: return
        when {
            track.state != TrackState.PRESENT -> c.message("Ce chapitre n'est pas encore sur le téléphone.")
            s.isCurrent && c.player.seekToTrack(track.driveId, positionMs) -> Unit
            else -> c.playBook(id, track.driveId, positionMs, autoPlay = s.isPlaying)
        }
    }

    fun setSpeed(speed: Float) {
        val s = state.value
        val id = s.bookId ?: return
        val value = ((speed * 10).roundToInt() / 10f).coerceIn(PlaybackService.MIN_SPEED, PlaybackService.MAX_SPEED)
        if (s.isCurrent) c.player.setSpeed(value) else viewModelScope.launch { c.library.setSpeed(id, value) }
    }

    /** Préréglage audio, mémorisé pour tous les livres ; pris en compte aussitôt par le lecteur. */
    fun setAudioPreset(preset: AudioPreset) {
        viewModelScope.launch { c.settings.update { it.copy(audioPreset = preset) } }
    }

    fun startTimer(minutes: Int) = PlayerRuntime.startTimer(minutes)

    fun endOfChapter() = PlayerRuntime.startEndOfChapter()

    fun extendTimer() = PlayerRuntime.extend()

    fun cancelTimer() = PlayerRuntime.cancelTimer()

    fun addBookmark(note: String) {
        val s = state.value
        val id = s.bookId ?: return
        val track = s.currentTrack ?: return
        viewModelScope.launch {
            c.library.addBookmark(id, track, s.positionMs, note)
            c.message("Signet ajouté : ${track.chapterLabel} · ${Formats.clock(s.positionMs)}")
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { c.library.deleteBookmark(id) }
    }

    fun gesture(gesture: Gesture) {
        val s = state.value
        val id = s.bookId ?: return
        if (gesture == Gesture.METTRE_DE_COTE && s.isPlaying) c.player.pause()
        c.applyGesture(id, gesture)
    }
}

@Composable
fun PlayerScreen(onOpenBook: (String) -> Unit, onOpenLibrary: () -> Unit, contentPadding: PaddingValues) {
    val container = LocalAppContainer.current
    val vm: PlayerViewModel = viewModel { PlayerViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.bookId, state.presentCount, state.isCurrent) { vm.ensureLoaded() }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Lecteur") },
                actions = {
                    state.bookId?.let { id ->
                        IconButton(onClick = { onOpenBook(id) }) { Icon(Icons.Filled.Info, contentDescription = "Fiche du livre") }
                    }
                },
            )
        },
    ) { inner ->
        val item = state.item
        if (!state.loaded) return@Scaffold
        if (state.bookId == null || item == null) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Filled.Headphones,
                    "Aucun livre en cours",
                    "Choisissez un livre dans la Bibliothèque. Seul _En écoute est téléchargé en entier, pour une écoute hors ligne.",
                    action = { Button(onClick = onOpenLibrary) { Text("Ouvrir la bibliothèque") } },
                )
            }
            return@Scaffold
        }
        PlayerContent(state, item, vm, Modifier.padding(inner), onOpenSheet = { sheet = it })
    }

    when (sheet) {
        SHEET_SPEED -> SpeedSheet(state.speed, onSpeed = vm::setSpeed, onDismiss = { sheet = null })
        SHEET_TIMER -> TimerSheet(state.sleep, vm, onDismiss = { sheet = null })
        SHEET_CHAPTERS -> ChaptersSheet(state, onJump = { track ->
            vm.jumpTo(track)
            sheet = null
        }, onDismiss = { sheet = null })
        SHEET_BOOKMARKS -> BookmarksSheet(state, vm, onDismiss = { sheet = null })
        SHEET_SOUND -> SoundSheet(state.audioPreset, onSelect = vm::setAudioPreset, onDismiss = { sheet = null })
    }
}

private const val SHEET_SPEED = "speed"
private const val SHEET_TIMER = "timer"
private const val SHEET_CHAPTERS = "chapters"
private const val SHEET_BOOKMARKS = "bookmarks"
private const val SHEET_SOUND = "sound"

@Composable
private fun PlayerContent(state: PlayerScreenState, item: LibraryItem, vm: PlayerViewModel, modifier: Modifier, onOpenSheet: (String) -> Unit) {
    val canPlay = !item.isZip && state.presentCount > 0
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BookCover(
            item.cover,
            item.title,
            size = 220.dp,
            cornerRadius = 16.dp,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(4.dp))
        Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        item.author?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        state.currentTrack?.let { track ->
            Text(track.chapterLabel, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Text(track.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        ChapterSeekBar(state, enabled = canPlay, onSeek = vm::seekInChapter)
        BookProgressBar(state)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::previousChapter, enabled = canPlay) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Chapitre précédent", modifier = Modifier.size(32.dp))
            }
            TextButton(
                onClick = vm::skipBack,
                enabled = canPlay,
                modifier = Modifier.semantics { contentDescription = "Reculer de ${state.skipSeconds} secondes" },
            ) { Text("−${state.skipSeconds}\u00A0s", style = MaterialTheme.typography.titleMedium) }
            FilledIconButton(
                onClick = vm::playPause,
                enabled = canPlay,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Lecture",
                    modifier = Modifier.size(40.dp),
                )
            }
            TextButton(
                onClick = vm::skipForward,
                enabled = canPlay,
                modifier = Modifier.semantics { contentDescription = "Avancer de ${state.skipSeconds} secondes" },
            ) { Text("+${state.skipSeconds}\u00A0s", style = MaterialTheme.typography.titleMedium) }
            IconButton(onClick = vm::nextChapter, enabled = canPlay) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Chapitre suivant", modifier = Modifier.size(32.dp))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToolButton(Icons.Filled.Speed, Formats.speed(state.speed), "Vitesse de lecture ${Formats.speed(state.speed)}") { onOpenSheet(SHEET_SPEED) }
            SleepButton(state.sleep) { onOpenSheet(SHEET_TIMER) }
            ToolButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Chapitres", "Chapitres") { onOpenSheet(SHEET_CHAPTERS) }
            ToolButton(Icons.Filled.Bookmark, "Signets", "Signets") { onOpenSheet(SHEET_BOOKMARKS) }
            ToolButton(Icons.Filled.GraphicEq, "Son", "Son : ${state.audioPreset.label}") { onOpenSheet(SHEET_SOUND) }
        }

        StatusAction(item, vm)

        if (state.presentCount < item.trackCount && !item.isZip) {
            DownloadStatus(state, item)
        }
        if (state.progress?.finished == true && item.status != BookStatus.DONE && item.status != BookStatus.ARCHIVED) {
            InfoCard(title = "Livre terminé") {
                Text("Fin de la dernière piste atteinte. Rien n'est déplacé sans votre confirmation.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { vm.gesture(Gesture.MARQUER_ECOUTE) }) { Text("Marquer comme écouté") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Avancement dans le chapitre en cours (écoulé à gauche, restant à droite), déplaçable : plus
 * précis que la barre du livre entier sur un livre de dix heures. Masquée tant que la durée du
 * chapitre n'est pas connue.
 */
@Composable
private fun ChapterSeekBar(state: PlayerScreenState, enabled: Boolean, onSeek: (Long) -> Unit) {
    val total = state.chapterDurationMs
    if (total <= 0L) return
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragValue else state.positionMs.toFloat().coerceIn(0f, total.toFloat())
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Chapitre", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.weight(1f))
            Text(
                "${Formats.clock(shown.toLong())} · reste ${Formats.clock((total - shown.toLong()).coerceAtLeast(0))}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                onSeek(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..total.toFloat(),
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondary,
                activeTrackColor = MaterialTheme.colorScheme.secondary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .semantics {
                    contentDescription = "Position dans le chapitre"
                    stateDescription = "${Formats.clock(shown.toLong())} sur ${Formats.clock(total)}"
                },
        )
    }
}

/**
 * Avancement dans le livre entier, en lecture seule : sur un livre de dix heures, un simple
 * effleurement d'un curseur suffirait à perdre sa page. Pour se déplacer, on utilise la barre du
 * chapitre ou les boutons.
 *
 * La ligne « Livre · … » est placée **au-dessus** de la barre : la zone tactile du curseur du
 * chapitre fait 48 dp de haut et déborde de 12 dp sous sa piste ; sans cet écart, toucher la
 * barre du livre déplacerait encore la lecture dans le chapitre.
 */
@Composable
private fun BookProgressBar(state: PlayerScreenState) {
    val total = state.totalMs.coerceAtLeast(1L).toFloat()
    val shown = state.globalMs.toFloat().coerceIn(0f, total)
    val fraction = if (state.totalMs > 0) shown / total else 0f
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Livre · ${Formats.clock(shown.toLong())}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(Formats.clock(state.totalMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .semantics {
                    contentDescription = "Position dans le livre"
                    stateDescription = "${Formats.clock(shown.toLong())} sur ${Formats.clock(state.totalMs)}"
                },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${Formats.percent(state.percent)} · reste ${Formats.duration(state.remainingMs)} à ${Formats.speed(state.speed)}",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, description: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun SleepButton(sleep: SleepTimerState, onClick: () -> Unit) {
    val remaining by produceState(initialValue = remainingOf(sleep), sleep) {
        while (true) {
            value = remainingOf(sleep)
            if (sleep !is SleepTimerState.Countdown) break
            delay(1_000)
        }
    }
    val label = when (sleep) {
        SleepTimerState.Off -> "Minuterie"
        SleepTimerState.EndOfChapter -> "Fin chap."
        is SleepTimerState.Countdown -> Formats.clock(remaining)
    }
    val description = when (sleep) {
        SleepTimerState.Off -> "Minuterie de sommeil, désactivée"
        SleepTimerState.EndOfChapter -> "Minuterie de sommeil : arrêt à la fin du chapitre"
        is SleepTimerState.Countdown -> "Minuterie de sommeil : arrêt dans ${Formats.elapsed(remaining)}"
    }
    ToolButton(Icons.Filled.Bedtime, label, description, onClick)
}

private fun remainingOf(sleep: SleepTimerState): Long =
    (sleep as? SleepTimerState.Countdown)?.let { (it.endsAt - System.currentTimeMillis()).coerceAtLeast(0) } ?: 0L

/**
 * Bouton de statut du lecteur, seulement pour ramener un livre dans « En écoute » (Reprendre,
 * Commencer). « Mettre de côté » et « Marquer écouté » restent dans la fiche livre et le menu
 * d'appui long de la bibliothèque : pas de bouton de rangement sur l'écran d'écoute.
 */
@Composable
private fun StatusAction(item: LibraryItem, vm: PlayerViewModel) {
    val gesture = when (item.status) {
        BookStatus.LATER -> Gesture.REPRENDRE
        BookStatus.NEW, BookStatus.UNSORTED -> Gesture.COMMENCER
        else -> null
    } ?: return
    OutlinedButton(onClick = { vm.gesture(gesture) }) { Text(gesture.label) }
    val hint = when (gesture) {
        Gesture.REPRENDRE -> "Le dossier revient dans « En écoute » : ses pistes sont (re)téléchargées et la lecture reprend à la position gardée."
        Gesture.COMMENCER -> "Le dossier passe dans « En écoute » : toutes ses pistes sont téléchargées pour une écoute hors ligne."
        else -> null
    }
    hint?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DownloadStatus(state: PlayerScreenState, item: LibraryItem) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val text = when {
            item.trackCount == 0 -> "Pistes en cours d'inventaire…"
            state.presentCount == 0 && item.status == BookStatus.IN_PROGRESS -> "Téléchargement en attente : la lecture démarre dès la première piste reçue."
            state.presentCount == 0 -> "Ce livre n'est pas sur le téléphone. Commencez-le pour le télécharger en entier."
            else -> "Sur le téléphone : ${state.presentCount}/${item.trackCount} pistes"
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (item.trackCount > 0) {
            LinearProgressIndicator(progress = { state.presentCount.toFloat() / item.trackCount }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SpeedSheet(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Vitesse de lecture", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(Formats.speed(speed), style = MaterialTheme.typography.displaySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onSpeed(Math.round((speed - 0.1f) * 10) / 10f) },
                    enabled = speed > PlaybackService.MIN_SPEED + 0.01f,
                    modifier = Modifier.semantics { contentDescription = "Ralentir de 0,1" },
                ) { Text("−0,1", style = MaterialTheme.typography.titleMedium) }
                Slider(
                    value = speed,
                    onValueChange = { onSpeed(Math.round(it * 10) / 10f) },
                    valueRange = PlaybackService.MIN_SPEED..PlaybackService.MAX_SPEED,
                    steps = 11,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Vitesse" },
                )
                TextButton(
                    onClick = { onSpeed(Math.round((speed + 0.1f) * 10) / 10f) },
                    enabled = speed < PlaybackService.MAX_SPEED - 0.01f,
                    modifier = Modifier.semantics { contentDescription = "Accélérer de 0,1" },
                ) { Text("+0,1", style = MaterialTheme.typography.titleMedium) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.8f, 1.0f, 1.2f, 1.5f, 2.0f).forEach { preset ->
                    FilterChip(selected = kotlin.math.abs(speed - preset) < 0.05f, onClick = { onSpeed(preset) }, label = { Text(Formats.speed(preset)) })
                }
            }
            Text("Mémorisée pour ce livre", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Préréglages audio : un seul à la fois, mémorisé pour tous les livres (téléphone, voiture, montre). */
@Composable
private fun SoundSheet(current: AudioPreset, onSelect: (AudioPreset) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text("Son", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() })
            Text(
                "Traitement appliqué à la lecture, pour tous les livres — aussi en voiture et sur la montre.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            AudioPreset.entries.forEach { preset ->
                val selected = preset == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Choisir ${preset.label}") { onSelect(preset) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(preset.label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerSheet(sleep: SleepTimerState, vm: PlayerViewModel, onDismiss: () -> Unit) {
    val remaining by produceState(initialValue = remainingOf(sleep), sleep) {
        while (true) {
            value = remainingOf(sleep)
            if (sleep !is SleepTimerState.Countdown) break
            delay(1_000)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text("Minuterie de sommeil", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() })
            Text(
                when (sleep) {
                    SleepTimerState.Off -> "Désactivée"
                    SleepTimerState.EndOfChapter -> "Arrêt à la fin du chapitre"
                    is SleepTimerState.Countdown -> "Arrêt dans ${Formats.clock(remaining)}"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (sleep != SleepTimerState.Off) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sleep is SleepTimerState.Countdown) {
                        Button(onClick = vm::extendTimer) { Text("+10 min") }
                    }
                    OutlinedButton(onClick = {
                        vm.cancelTimer()
                        onDismiss()
                    }) { Text("Désactiver") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            PlayerRuntime.TIMER_CHOICES_MINUTES.forEach { minutes ->
                SheetChoice("$minutes minutes") {
                    vm.startTimer(minutes)
                    onDismiss()
                }
            }
            SheetChoice("Fin du chapitre") {
                vm.endOfChapter()
                onDismiss()
            }
        }
    }
}

@Composable
private fun SheetChoice(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

@Composable
private fun ChaptersSheet(state: PlayerScreenState, onJump: (TrackEntity) -> Unit, onDismiss: () -> Unit) {
    val groups = remember(state.tracks) { Chapters.group(state.tracks) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Chapitres", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() })
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
            groups.forEach { group ->
                val label = group.label
                if (label != null) {
                    item(key = "g-$label-${group.tracks.first().driveId}") {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 24.dp, top = 16.dp, bottom = 4.dp)
                                .semantics { heading() },
                        )
                    }
                }
                items(group.tracks, key = { it.driveId }) { track ->
                    val current = track.driveId == state.currentTrack?.driveId
                    val present = track.state == TrackState.PRESENT
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = present, onClickLabel = "Aller à ce chapitre") { onJump(track) }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    current -> MaterialTheme.colorScheme.primary
                                    present -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                listOfNotNull(track.chapterLabel, track.durationMs?.let { Formats.duration(it, !track.durationExact) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!present) Icon(Icons.Filled.CloudQueue, contentDescription = "Pas encore téléchargé", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksSheet(state: PlayerScreenState, vm: PlayerViewModel, onDismiss: () -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Signets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            state.currentTrack?.let { track ->
                Text("Ici : ${track.chapterLabel} · ${Formats.clock(state.positionMs)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(LibraryRepository.MAX_NOTE_LENGTH) },
                    label = { Text("Note courte") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    vm.addBookmark(note)
                    note = ""
                }) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ajouter un signet")
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (state.bookmarks.isEmpty()) {
                item { Text("Aucun signet pour ce livre.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.bookmarks, key = { it.id }) { bm ->
                val track = state.tracks.firstOrNull { it.driveId == bm.trackId }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Aller au signet") {
                            track?.let { vm.jumpTo(it, bm.positionMs) }
                            onDismiss()
                        }
                        .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bm.note.ifBlank { "Signet" }, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${track?.chapterLabel ?: "Piste ${bm.trackIndex + 1}"} · ${Formats.clock(bm.positionMs)} · ${Formats.date(bm.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.deleteBookmark(bm.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Supprimer le signet") }
                }
            }
        }
    }
}
