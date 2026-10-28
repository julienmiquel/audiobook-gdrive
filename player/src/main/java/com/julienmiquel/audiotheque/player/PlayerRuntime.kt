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

    fun track(bookId: String, trackId: String): String = "$TRACK:$bookId:$trackId"

    data class BookRequest(val bookId: String, val trackId: String?, val positionMs: Long?)
    data class TrackRef(val bookId: String, val trackId: String)

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
        if (parts.size != 3 || parts[0] != TRACK) return null
        return TrackRef(parts[1], parts[2])
    }
}

/** Minuterie de sommeil : 15, 30, 45, 60 min ou fin du chapitre, prolongeable de 10 min. */
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
}

/** État partagé entre le service de lecture et l'interface (même processus). */
object PlayerRuntime {
    const val EXTEND_MS = 10 * 60_000L
    val TIMER_CHOICES_MINUTES = listOf(15, 30, 45, 60)

    private val _sleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    fun startTimer(minutes: Int, now: Long = System.currentTimeMillis()) {
        _sleepTimer.value = SleepTimerState.Countdown(now + minutes * 60_000L, minutes * 60_000L)
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
