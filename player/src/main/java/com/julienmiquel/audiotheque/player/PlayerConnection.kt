package com.julienmiquel.audiotheque.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** État du lecteur vu par l'interface. */
data class PlayerUiState(
    val connected: Boolean = false,
    val bookId: String? = null,
    val trackId: String? = null,
    val chapterIndex: Int? = null,
    val chapterStartMs: Long = 0,
    val chapterTitle: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    /** Position dans l'élément en cours (chapitre si découpé, sinon piste). */
    val positionMs: Long = 0,
    /** Position non découpée dans la piste physique ([chapterStartMs] + [positionMs]). */
    val trackPositionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    /** Pistes présentes dans la liste de lecture (téléchargées), dans l'ordre (sans doublons). */
    val playlistTrackIds: List<String> = emptyList(),
)

/**
 * Connexion de l'interface au service de lecture (MediaController). Connectée quand l'interface
 * est visible ; la lecture continue dans le service une fois l'app en arrière-plan.
 */
class PlayerConnection(private val context: Context) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.w("PlayerConnection", "Service de lecture injoignable", e) },
    )
    private val mutex = Mutex()
    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            refresh()
        }
    }

    suspend fun controller(): MediaController = mutex.withLock {
        controller?.takeIf { it.isConnected } ?: run {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val created = MediaController.Builder(context, token).buildAsync().await()
            created.addListener(listener)
            controller = created
            startTicker()
            refresh()
            created
        }
    }

    fun connect() {
        scope.launch { runCatching { controller() } }
    }

    fun release() {
        ticker?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _state.value = _state.value.copy(connected = false)
    }

    /**
     * Lit un livre : reprise à la position enregistrée (avec recul intelligent), ou piste et position données.
     * Avec [autoPlay] à faux, le livre est seulement positionné (barre de progression, chapitres).
     */
    fun playBook(bookId: String, trackId: String? = null, positionMs: Long? = null, autoPlay: Boolean = true) {
        scope.launch {
            val c = controller()
            val item = MediaItem.Builder()
                .setMediaId(MediaIds.bookRequest(bookId, trackId, positionMs))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setExtras(Bundle().apply { putBoolean(PlaybackService.EXTRA_AUTO_PLAY, autoPlay) })
                        .build(),
                )
                .build()
            c.setMediaItem(item)
            c.prepare()
            if (autoPlay) c.play() else c.pause()
        }
    }

    fun togglePlayPause() = withController { c -> if (c.isPlaying) c.pause() else c.play() }

    fun pause() = withController { it.pause() }

    /** Déconnexion : arrêt de la lecture et liste vidée (les fichiers locaux vont être supprimés). */
    fun stopAndClear() = withController {
        it.stop()
        it.clearMediaItems()
    }

    fun seekBack() = withController { it.seekBack() }

    fun seekForward() = withController { it.seekForward() }

    fun nextChapter() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }

    /** Début du chapitre, ou chapitre précédent si on y est déjà. */
    fun previousChapter() = withController { it.seekToPrevious() }

    fun setSpeed(speed: Float) = withController {
        it.setPlaybackSpeed(speed.coerceIn(PlaybackService.MIN_SPEED, PlaybackService.MAX_SPEED))
    }

    /**
     * Va à une piste téléchargée à la position non découpée [positionMs] (convertie en élément de
     * chapitre si le fichier contient des chapitres intégrés) ; retourne faux si elle n'est pas encore là.
     */
    fun seekToTrack(trackId: String, positionMs: Long): Boolean {
        if (trackId !in _state.value.playlistTrackIds) return false
        val clamped = positionMs.coerceAtLeast(0L)
        withController { c ->
            findPlaylistTarget(c, trackId, clamped)?.let { (itemIndex, localPos) ->
                c.seekTo(itemIndex, localPos)
            }
        }
        return true
    }

    private fun findPlaylistTarget(c: MediaController, trackId: String, unclippedPosMs: Long): Pair<Int, Long>? {
        val matching = (0 until c.mediaItemCount).filter { idx ->
            MediaIds.parseTrack(c.getMediaItemAt(idx).mediaId)?.trackId == trackId
        }
        if (matching.isEmpty()) return null
        if (matching.size == 1 && MediaIds.parseTrack(c.getMediaItemAt(matching[0]).mediaId)?.chapterIndex == null) {
            return matching[0] to unclippedPosMs
        }
        val chosen = matching.lastOrNull { idx ->
            val start = c.getMediaItemAt(idx).mediaMetadata.extras?.getLong(PlaybackService.EXTRA_CHAPTER_START_MS, 0L) ?: 0L
            unclippedPosMs >= start
        } ?: matching.first()
        val chosenStart = c.getMediaItemAt(chosen).mediaMetadata.extras?.getLong(PlaybackService.EXTRA_CHAPTER_START_MS, 0L) ?: 0L
        return chosen to (unclippedPosMs - chosenStart).coerceAtLeast(0L)
    }

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch { block(controller()) }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(500)
                if (controller?.isPlaying == true) refresh()
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val ref = item?.mediaId?.let { MediaIds.parseTrack(it) }
        val chapterStart = item?.mediaMetadata?.extras?.getLong(PlaybackService.EXTRA_CHAPTER_START_MS, 0L) ?: 0L
        val pos = c.currentPosition.coerceAtLeast(0L)
        _state.value = PlayerUiState(
            connected = c.isConnected,
            bookId = ref?.bookId,
            trackId = ref?.trackId,
            chapterIndex = ref?.chapterIndex,
            chapterStartMs = chapterStart,
            chapterTitle = if (ref?.chapterIndex != null) item.mediaMetadata.title?.toString() else null,
            isPlaying = c.isPlaying,
            playWhenReady = c.playWhenReady,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            ended = c.playbackState == Player.STATE_ENDED,
            positionMs = pos,
            trackPositionMs = chapterStart + pos,
            durationMs = c.duration.coerceAtLeast(0),
            speed = c.playbackParameters.speed,
            playlistTrackIds = (0 until c.mediaItemCount)
                .mapNotNull { MediaIds.parseTrack(c.getMediaItemAt(it).mediaId)?.trackId }
                .distinct(),
        )
    }
}
