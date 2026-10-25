package com.julienmiquel.audiotheque.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
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
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    /** Pistes présentes dans la liste de lecture (téléchargées), dans l'ordre. */
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
     * Lit un livre : reprise à la position enregistrée (moins 5 s), ou piste et position données.
     * Avec [autoPlay] à faux, le livre est seulement positionné (barre de progression, chapitres).
     */
    fun playBook(bookId: String, trackId: String? = null, positionMs: Long? = null, autoPlay: Boolean = true) {
        scope.launch {
            val c = controller()
            c.setMediaItem(MediaItem.Builder().setMediaId(MediaIds.bookRequest(bookId, trackId, positionMs)).build())
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

    /** Va à une piste téléchargée ; retourne faux si elle n'est pas encore là. */
    fun seekToTrack(trackId: String, positionMs: Long): Boolean {
        val index = _state.value.playlistTrackIds.indexOf(trackId)
        if (index < 0) return false
        withController { it.seekTo(index, positionMs.coerceAtLeast(0)) }
        return true
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
        val ref = c.currentMediaItem?.mediaId?.let { MediaIds.parseTrack(it) }
        _state.value = PlayerUiState(
            connected = c.isConnected,
            bookId = ref?.bookId,
            trackId = ref?.trackId,
            isPlaying = c.isPlaying,
            playWhenReady = c.playWhenReady,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            ended = c.playbackState == Player.STATE_ENDED,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
            speed = c.playbackParameters.speed,
            playlistTrackIds = (0 until c.mediaItemCount).mapNotNull { MediaIds.parseTrack(c.getMediaItemAt(it).mediaId)?.trackId },
        )
    }
}
