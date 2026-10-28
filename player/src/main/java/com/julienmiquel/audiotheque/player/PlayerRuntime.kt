package com.julienmiquel.audiotheque.player

import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.chapterLabel
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.ProgressSyncRequester
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dépendances du service de lecture, fournies par l'Application. */
interface PlayerDependencies {
    val database: AppDatabase
    val settings: SettingsRepository

    /** Progression échangée avec Drive (null : aucun compte, ou tests). */
    val progressSync: ProgressSyncRequester? get() = null

    /** Appelé quand l'état de lecture, le chapitre ou la progression change (widget d'accueil). */
    val onPlaybackStateChanged: (() -> Unit)? get() = null
}

interface PlayerDependenciesProvider {
    val playerDependencies: PlayerDependencies
}

/**
 * Identifiants des éléments média : racine, onglet par statut (« tab:IN_PROGRESS »), livre
 * (« b:… ») et piste (« t:livre:piste »).
 */
object MediaIds {
    const val ROOT = "root"
    private const val TAB = "tab"
    private const val BOOK = "b"
    private const val TRACK = "t"

    /** Onglet de la racine (Android Auto) : les livres écoutables d'un statut. */
    fun tab(status: BookStatus): String = "$TAB:${status.name}"

    /** Demande de lecture d'un livre, éventuellement à une piste et une position données. */
    fun bookRequest(bookId: String, trackId: String? = null, positionMs: Long? = null): String =
        listOfNotNull(BOOK, bookId, trackId ?: if (positionMs != null) "" else null, positionMs?.toString())
            .joinToString(":")

    fun track(bookId: String, trackId: String, chapterIndex: Int? = null): String =
        if (chapterIndex != null) "$TRACK:$bookId:$trackId:$chapterIndex" else "$TRACK:$bookId:$trackId"

    data class BookRequest(val bookId: String, val trackId: String?, val positionMs: Long?)
    data class TrackRef(val bookId: String, val trackId: String, val chapterIndex: Int? = null)

    fun parseTab(mediaId: String): BookStatus? {
        val parts = mediaId.split(':')
        if (parts.size != 2 || parts[0] != TAB) return null
        return BookStatus.entries.firstOrNull { it.name == parts[1] }
    }

    fun parseBookRequest(mediaId: String): BookRequest? {
        val parts = mediaId.split(':')
        if (parts.size < 2 || parts[0] != BOOK || parts[1].isEmpty()) return null
        return BookRequest(parts[1], parts.getOrNull(2)?.ifEmpty { null }, parts.getOrNull(3)?.toLongOrNull())
    }

    fun parseTrack(mediaId: String): TrackRef? {
        val parts = mediaId.split(':')
        if ((parts.size != 3 && parts.size != 4) || parts[0] != TRACK || parts[1].isEmpty() || parts[2].isEmpty()) return null
        val chapter = if (parts.size == 4) parts[3].toIntOrNull() ?: return null else null
        return TrackRef(parts[1], parts[2], chapter)
    }
}

/** Minuterie de sommeil : 15, 30, 45, 60 min, durée libre ou fin du chapitre, prolongeable de 10 min. */
sealed interface SleepTimerState {
    data object Off : SleepTimerState
    data class Countdown(val endsAt: Long, val totalMs: Long) : SleepTimerState
    data object EndOfChapter : SleepTimerState
}

/** Événements de lecture pour l'interface. */
sealed interface PlaybackEvent {
    /** Fin de la dernière piste : proposer « Marquer comme écouté » (rien n'est déplacé sans confirmation). */
    data class BookFinished(val bookId: String) : PlaybackEvent

    /** Fin des pistes téléchargées : la suivante arrive (la lecture reprendra seule). */
    data class WaitingForDownload(val bookId: String) : PlaybackEvent

    /** Lecture reprise à la position enregistrée par un autre téléphone. */
    data class ProgressRestored(val bookId: String, val device: String?, val percent: Float) : PlaybackEvent

    /** Minuterie prolongée (par exemple en secouant le téléphone). */
    data class TimerExtended(val minutes: Int) : PlaybackEvent
}

/**
 * Recul automatique à la reprise selon la durée d'interruption : nul sous 2 s, 2 s sous 1 min,
 * 5 s sous 10 min, 10 s sous 1 h, 20 s sous 24 h, 30 s au-delà (ou 5 s fixes si désactivé).
 */
object SmartRewind {
    const val FIXED_REWIND_MS = 5_000L
    const val MIN_PAUSE_MS = 2_000L

    fun computeMs(pauseDurationMs: Long, smart: Boolean = true): Long {
        if (pauseDurationMs < MIN_PAUSE_MS) return 0L
        if (!smart) return FIXED_REWIND_MS
        return when {
            pauseDurationMs < 60_000L -> 2_000L
            pauseDurationMs < 10 * 60_000L -> 5_000L
            pauseDurationMs < 60 * 60_000L -> 10_000L
            pauseDurationMs < 24 * 60 * 60_000L -> 20_000L
            else -> 30_000L
        }
    }
}

/** Position enregistrée avant un déplacement (chapitre, curseur, signet, minuterie) pour pouvoir y revenir. */
data class JumpEntry(
    val id: Long,
    val bookId: String,
    val trackId: String,
    val chapterLabel: String,
    /** Position non découpée dans la piste physique (compatible avec la progression). */
    val positionMs: Long,
    val reason: String,
    val timestamp: Long,
)

/** Instantané léger de la lecture active (pour le widget d'accueil, sans démarrer le service). */
data class ActivePlaybackSnapshot(
    val bookId: String? = null,
    val isPlaying: Boolean = false,
    val chapterLabel: String? = null,
)

/** État partagé entre le service de lecture et l'interface (même processus). */
object PlayerRuntime {
    const val EXTEND_MS = 10 * 60_000L
    val TIMER_CHOICES_MINUTES = listOf(15, 30, 45, 60)
    private const val MAX_HISTORY = 15
    private const val DEDUP_WINDOW_MS = 5_000L

    private var nextJumpId = 1L

    private val _sleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    private val _seekHistory = MutableStateFlow<List<JumpEntry>>(emptyList())
    val seekHistory: StateFlow<List<JumpEntry>> = _seekHistory.asStateFlow()

    private val _activePlayback = MutableStateFlow(ActivePlaybackSnapshot())
    val activePlayback: StateFlow<ActivePlaybackSnapshot> = _activePlayback.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    internal fun updateActivePlayback(snapshot: ActivePlaybackSnapshot) {
        _activePlayback.value = snapshot
    }

    fun startTimer(minutes: Int, now: Long = System.currentTimeMillis()) {
        val clamped = minutes.coerceIn(1, 240)
        _sleepTimer.value = SleepTimerState.Countdown(now + clamped * 60_000L, clamped * 60_000L)
    }

    fun startEndOfChapter() {
        _sleepTimer.value = SleepTimerState.EndOfChapter
    }

    fun extend(now: Long = System.currentTimeMillis()) {
        _sleepTimer.value = when (val s = _sleepTimer.value) {
            is SleepTimerState.Countdown -> s.copy(endsAt = maxOf(s.endsAt, now) + EXTEND_MS, totalMs = s.totalMs + EXTEND_MS)
            else -> SleepTimerState.Countdown(now + EXTEND_MS, EXTEND_MS)
        }
    }

    fun cancelTimer() {
        _sleepTimer.value = SleepTimerState.Off
    }

    @Synchronized
    fun recordJump(
        bookId: String,
        trackId: String,
        chapterLabel: String,
        positionMs: Long,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val current = _seekHistory.value
        val head = current.firstOrNull()
        if (head != null &&
            head.bookId == bookId &&
            head.trackId == trackId &&
            kotlin.math.abs(head.positionMs - positionMs) < DEDUP_WINDOW_MS
        ) {
            return
        }
        val entry = JumpEntry(
            id = nextJumpId++,
            bookId = bookId,
            trackId = trackId,
            chapterLabel = chapterLabel,
            positionMs = positionMs.coerceAtLeast(0L),
            reason = reason,
            timestamp = now,
        )
        _seekHistory.value = (listOf(entry) + current).take(MAX_HISTORY)
    }

    @Synchronized
    fun clearHistoryForBook(bookId: String) {
        _seekHistory.value = _seekHistory.value.filterNot { it.bookId == bookId }
    }

    internal fun emit(event: PlaybackEvent) {
        _events.tryEmit(event)
    }
}

/** Chapitres groupés par partie (« Partie 4 »), sans motif : une seule liste dans l'ordre. */
data class ChapterGroup(val label: String?, val tracks: List<TrackEntity>)

object Chapters {
    /**
     * Repère d'une piste pour les commandes système (vignette, montre, autoradio) : partie et
     * chapitre quand le nom de fichier les donne, sinon « Piste 4 / 51 » — jamais le nom de
     * fichier brut.
     */
    fun label(track: TrackEntity, total: Int): String = when {
        track.part != null || track.chapter != null -> track.chapterLabel
        total > 0 -> "Piste ${track.sortIndex + 1} / $total"
        else -> track.chapterLabel
    }

    fun group(tracks: List<TrackEntity>): List<ChapterGroup> {
        if (tracks.none { it.part != null }) return listOf(ChapterGroup(null, tracks))
        val groups = mutableListOf<ChapterGroup>()
        var currentPart: Int? = null
        var current = mutableListOf<TrackEntity>()
        for (track in tracks) {
            if (current.isNotEmpty() && track.part != currentPart) {
                groups += ChapterGroup(currentPart?.let { "Partie $it" }, current)
                current = mutableListOf()
            }
            currentPart = track.part
            current += track
        }
        if (current.isNotEmpty()) groups += ChapterGroup(currentPart?.let { "Partie $it" }, current)
        return groups
    }
}
