package com.julienmiquel.audiotheque.player

import android.app.PendingIntent
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.EmbeddedChapterEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.AudioPreset
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.ProgressMath
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.model.chapterLabel
import com.julienmiquel.audiotheque.core.model.displayAuthor
import com.julienmiquel.audiotheque.core.model.displayNarrator
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.effectiveStatus
import com.julienmiquel.audiotheque.core.model.isPlayableKind
import com.julienmiquel.audiotheque.core.model.partLabel
import com.julienmiquel.audiotheque.core.sync.BookInUse
import com.julienmiquel.audiotheque.core.sync.RemoteProgress
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.player.audio.PresetAudioProcessor
import com.julienmiquel.audiotheque.player.audio.PresetRenderersFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lecture hors ligne uniquement (fichiers locaux, jamais de streaming depuis Drive), avec
 * notification média, écran verrouillé, boutons du casque Bluetooth et Android Auto.
 *
 * Reprise à la seconde près : position enregistrée toutes les 10 s et à chaque pause ; à la
 * reprise, recul intelligent selon la durée d'interruption (ou fixe à 5 s). Charger un livre
 * (écran Lecteur, Android Auto) n'enregistre rien : seules l'écoute et les actions de
 * l'utilisateur (déplacement, vitesse) le font.
 *
 * D'un téléphone à l'autre : à chaque pause, déplacement ou fin de livre (et toutes les 5 min
 * pendant l'écoute), la position part dans le dossier Drive du livre ; juste avant de reprendre
 * un livre, elle y est relue, et la lecture repart de la position la plus récente.
 *
 * Catalogue proposé aux navigateurs média (Android Auto, Assistant) : onglets « En écoute » et
 * « Plus tard » (livres ayant des pistes sur le téléphone, grille de pochettes avec avancement),
 * puis les chapitres groupés par partie ; recherche par titre, auteur ou voix ; bouton
 * « Vitesse ». Vignette de reprise du système (Réglages rapides, après redémarrage) : le dernier
 * livre écouté, avec sa pochette, lancé sans attendre Drive.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PlaybackService : MediaLibraryService() {

    private val errors = CoroutineExceptionHandler { _, e -> Log.w(TAG, "Erreur du lecteur", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)

    /** Enregistrements faits un par un, dans l'ordre des demandes. */
    private val persistMutex = Mutex()

    private lateinit var deps: PlayerDependencies
    private lateinit var exo: ExoPlayer
    private lateinit var player: AudiobookPlayer
    private var session: MediaLibrarySession? = null

    private var currentBook: BookEntity? = null
    private val currentBookIdFlow = MutableStateFlow<String?>(null)
    private var currentTracks: List<TrackEntity> = emptyList()
    private var currentEmbedded: Map<String, List<EmbeddedChapterEntity>> = emptyMap()
    private var tracksJob: Job? = null
    private var tickerJob: Job? = null
    private var shakeGraceJob: Job? = null
    private var pausedAt: Long? = null
    private var waitingForNext = false

    /** Faux tant que le livre chargé n'a été ni écouté ni déplacé par l'utilisateur. */
    private var persistEnabled = false

    /** Écoute effective depuis le dernier enregistrement (date de « dernière écoute »). */
    private var listenedSinceSave = false

    /** Dernier envoi de la position dans Drive (pendant l'écoute : au plus toutes les 5 min). */
    @Volatile
    private var lastPushAt = 0L

    @Volatile
    private var skipMs = 30_000L

    @Volatile
    private var smartRewindEnabled = true

    @Volatile
    private var shakeToExtendEnabled = true

    /** Saut automatique (±30 s, recul à la reprise, reprise Drive) : ne pas encombrer l'historique. */
    @Volatile
    private var suppressNextSeekHistory = false

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var shakeRegistered = false
    private var timerPausedGraceUntilMs = 0L
    private var firstShakePeakAt = 0L
    private var shakePeakCount = 0
    private var lastShakeTriggeredAt = 0L

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val g = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
            if (abs(g - 1.0) < SHAKE_THRESHOLD_G) return
            val now = System.currentTimeMillis()
            if (now - lastShakeTriggeredAt < SHAKE_COOLDOWN_MS) return
            if (now - firstShakePeakAt > SHAKE_WINDOW_MS) {
                firstShakePeakAt = now
                shakePeakCount = 1
            } else if (now - firstShakePeakAt >= 120L) {
                shakePeakCount++
                if (shakePeakCount >= 2) {
                    lastShakeTriggeredAt = now
                    shakePeakCount = 0
                    scope.launch { onShakeDetected() }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Bouton « Vitesse » (Android Auto, notification) : un appui passe à la vitesse suivante du cycle. */
    private val speedCommand = SessionCommand(COMMAND_CYCLE_SPEED, Bundle.EMPTY)

    /** Préréglage audio (normalisation, voix, niveau très bas, binaural), appliqué avant la vitesse. */
    private val dsp = PresetAudioProcessor()

    override fun onCreate() {
        super.onCreate()
        deps = (application as PlayerDependenciesProvider).playerDependencies
        sensorManager = getSystemService(SensorManager::class.java)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        exo = ExoPlayer.Builder(this, PresetRenderersFactory(this, dsp))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(listener)
        player = AudiobookPlayer(exo)

        val notifications = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.player_channel_name)
            .build()
        notifications.setSmallIcon(R.drawable.ic_stat_play)
        setMediaNotificationProvider(notifications)

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity())
            .setMediaButtonPreferences(mediaButtons())
            // Pochettes lues depuis le fichier local (ou l'adresse content://), réduites : écran
            // verrouillé, notification, Bluetooth, Android Auto sans images de 1 200 px en mémoire.
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).setMaximumOutputDimension(ARTWORK_MAX_PX).build()))
            .build()

        scope.launch {
            deps.settings.settings.map { it.skipSeconds }.distinctUntilChanged().collect { seconds ->
                skipMs = seconds * 1000L
                session?.setMediaButtonPreferences(mediaButtons())
            }
        }
        scope.launch {
            deps.settings.settings.map { it.skipSilence }.distinctUntilChanged().collect { skip ->
                exo.skipSilenceEnabled = skip
            }
        }
        scope.launch {
            deps.settings.settings.map { it.smartRewind to it.shakeToExtendTimer }.distinctUntilChanged().collect { (smart, shake) ->
                smartRewindEnabled = smart
                shakeToExtendEnabled = shake
                updateShakeRegistration()
            }
        }
        scope.launch {
            // Changement pris en compte au tampon suivant (préréglage propre au livre, sinon global, et réglages binauraux).
            combine(deps.settings.settings, currentBookIdFlow) { s, bookId ->
                Triple(s.accountKey, s.audioPreset, s.binauralConfig) to bookId
            }
                .distinctUntilChanged()
                .flatMapLatest { (triple, bookId) ->
                    val (accountKey, globalPreset, binauralCfg) = triple
                    if (bookId == null) {
                        flowOf(globalPreset to binauralCfg)
                    } else {
                        deps.database.progressDao().observe(bookId, accountKey).map { p ->
                            (p?.audioPreset?.let(AudioPreset::fromNameOrNull) ?: globalPreset) to binauralCfg
                        }
                    }
                }
                .distinctUntilChanged()
                .collect { (preset, binauralCfg) ->
                    dsp.binauralConfig = binauralCfg
                    dsp.preset = preset
                }
        }
        scope.launch { observeSleepTimer() }
        scope.launch { observeCatalogChanges() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exo.playWhenReady || exo.mediaItemCount == 0) {
            persistNow(push = true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistNow(push = true)
        unregisterShake()
        shakeGraceJob?.cancel()
        tickerJob?.cancel()
        tracksJob?.cancel()
        session?.release()
        session = null
        exo.removeListener(listener)
        exo.release()
        BookInUse.set(null)
        scope.cancel()
        super.onDestroy()
    }

    private fun updateShakeRegistration() {
        val active = shakeToExtendEnabled &&
            (PlayerRuntime.sleepTimer.value != SleepTimerState.Off || System.currentTimeMillis() < timerPausedGraceUntilMs)
        if (active && !shakeRegistered) {
            val sensor = accelerometer ?: return
            shakeRegistered = sensorManager?.registerListener(shakeListener, sensor, SensorManager.SENSOR_DELAY_UI) == true
        } else if (!active && shakeRegistered) {
            unregisterShake()
        }
    }

    private fun unregisterShake() {
        if (shakeRegistered) {
            sensorManager?.unregisterListener(shakeListener)
            shakeRegistered = false
        }
    }

    private fun onShakeDetected() {
        if (!shakeToExtendEnabled) return
        val timerActive = PlayerRuntime.sleepTimer.value != SleepTimerState.Off
        val inGrace = System.currentTimeMillis() < timerPausedGraceUntilMs
        if (!timerActive && !inGrace) return
        timerPausedGraceUntilMs = 0L
        shakeGraceJob?.cancel()
        PlayerRuntime.extend()
        if (!exo.isPlaying && exo.mediaItemCount > 0) exo.play()
        updateShakeRegistration()
        PlayerRuntime.emit(PlaybackEvent.TimerExtended(10))
    }

    // ------------------------------------------------------------------ lecteur

    /** Saut réglable (10, 15 ou 30 s), qui franchit les limites de chapitre ; recul à la reprise. */
    private inner class AudiobookPlayer(base: ExoPlayer) : ForwardingPlayer(base) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            command == Player.COMMAND_SEEK_BACK || command == Player.COMMAND_SEEK_FORWARD || super.isCommandAvailable(command)

        override fun getSeekBackIncrement(): Long = skipMs

        override fun getSeekForwardIncrement(): Long = skipMs

        override fun seekBack() = seekBy(-skipMs)

        override fun seekForward() = seekBy(skipMs)

        override fun play() {
            rewindAfterPause()
            super.play()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) rewindAfterPause()
            super.setPlayWhenReady(playWhenReady)
        }
    }

    private fun seekBy(delta: Long) {
        if (exo.mediaItemCount == 0) return
        val index = exo.currentMediaItemIndex
        val target = exo.currentPosition + delta
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: durationOf(index)
        suppressNextSeekHistory = true
        when {
            target < 0 && index > 0 -> {
                val previous = durationOf(index - 1)
                if (previous > 0) exo.seekTo(index - 1, (previous + target).coerceAtLeast(0)) else exo.seekTo(index, 0)
            }
            target < 0 -> exo.seekTo(index, 0)
            duration > 0 && target >= duration && index < exo.mediaItemCount - 1 -> exo.seekTo(index + 1, target - duration)
            duration > 0 && target >= duration -> exo.seekTo(index, (duration - 1000).coerceAtLeast(0))
            else -> exo.seekTo(index, target)
        }
    }

    private fun durationOf(playlistIndex: Int): Long {
        if (playlistIndex !in 0 until exo.mediaItemCount) return 0
        val item = exo.getMediaItemAt(playlistIndex)
        val extras = item.mediaMetadata.extras
        if (extras != null && extras.containsKey(EXTRA_CHAPTER_START_MS)) {
            val start = extras.getLong(EXTRA_CHAPTER_START_MS, 0L)
            val end = extras.getLong(EXTRA_CHAPTER_END_MS, 0L)
            if (end > start) return end - start
        }
        val ref = MediaIds.parseTrack(item.mediaId) ?: return 0
        return currentTracks.firstOrNull { it.driveId == ref.trackId }?.durationMs ?: 0
    }

    private fun rewindAfterPause() {
        val since = pausedAt ?: return
        pausedAt = null
        val rewindMs = SmartRewind.computeMs(System.currentTimeMillis() - since, smartRewindEnabled)
        if (rewindMs > 0L && exo.playbackState != Player.STATE_ENDED && exo.mediaItemCount > 0) {
            seekBy(-rewindMs)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                pausedAt = null
                persistEnabled = true
                listenedSinceSave = true
                startTicker()
            } else {
                if (exo.playbackState != Player.STATE_ENDED && exo.playbackState != Player.STATE_BUFFERING) {
                    pausedAt = System.currentTimeMillis()
                }
                tickerJob?.cancel()
                // Pause : la position part dans Drive, pour reprendre sur l'autre téléphone.
                persistNow(push = true)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            finishedEmitted = false
            // Nouvelle liste (livre chargé) : rien à enregistrer, ce n'est pas une écoute.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) persistNow()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                val suppressed = suppressNextSeekHistory
                suppressNextSeekHistory = false
                val book = currentBook
                val oldRef = oldPosition.mediaItem?.mediaId?.let(MediaIds::parseTrack)
                if (!suppressed && book != null && oldRef != null && oldRef.bookId == book.driveId) {
                    val sameItem = oldPosition.mediaItemIndex == newPosition.mediaItemIndex
                    val delta = abs(newPosition.positionMs - oldPosition.positionMs)
                    if (!sameItem || delta >= MIN_SEEK_FOR_HISTORY_MS) {
                        val oldStart = oldPosition.mediaItem?.mediaMetadata?.extras?.getLong(EXTRA_CHAPTER_START_MS, 0L) ?: 0L
                        val unclippedOldPos = oldStart + oldPosition.positionMs.coerceAtLeast(0L)
                        val label = oldPosition.mediaItem?.mediaMetadata?.title?.toString()
                            ?: currentTracks.firstOrNull { it.driveId == oldRef.trackId }?.chapterLabel
                            ?: "Position"
                        PlayerRuntime.recordJump(
                            bookId = book.driveId,
                            trackId = oldRef.trackId,
                            chapterLabel = label,
                            positionMs = unclippedOldPos,
                            reason = if (sameItem) "Déplacement" else "Changement de chapitre",
                        )
                    }
                }
                // Position choisie par l'utilisateur (barre, chapitres, saut) : enregistrée et envoyée.
                finishedEmitted = false
                persistEnabled = true
                persistNow(push = true)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // Le bouton « Vitesse » affiche la vitesse en cours.
            session?.setMediaButtonPreferences(mediaButtons())
            val bookId = currentBook?.driveId ?: return
            val speed = playbackParameters.speed
            persistScope.launch(start = CoroutineStart.UNDISPATCHED) { persistMutex.withLock { persistSpeed(bookId, speed) } }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onEnded()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                PlayerRuntime.sleepTimer.value is SleepTimerState.EndOfChapter
            ) {
                startShakeGraceWindow()
                PlayerRuntime.cancelTimer()
                // Minuterie « fin du chapitre » sur le dernier chapitre : c'est aussi la fin du livre.
                if (isOnLastTrack()) onEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Fichier local illisible ou supprimé : on garde la position, l'interface affichera l'état.
            persistNow()
        }
    }

    /** Fin du livre déjà signalée pour la position actuelle (STATE_ENDED et minuterie). */
    private var finishedEmitted = false

    private fun currentTrack(): TrackEntity? {
        val ref = exo.currentMediaItem?.let { MediaIds.parseTrack(it.mediaId) } ?: return null
        return currentTracks.firstOrNull { it.driveId == ref.trackId }
    }

    private fun isOnLastTrack(): Boolean {
        val track = currentTrack() ?: return false
        val last = currentTracks.maxOfOrNull { it.sortIndex } ?: return false
        return track.sortIndex >= last && exo.currentMediaItemIndex >= exo.mediaItemCount - 1
    }

    private fun onEnded() {
        val book = currentBook ?: return
        currentTrack() ?: return
        if (isOnLastTrack()) {
            if (finishedEmitted) return
            finishedEmitted = true
            persistNow(markFinished = true, push = true)
            PlayerRuntime.emit(PlaybackEvent.BookFinished(book.driveId))
        } else {
            waitingForNext = true
            PlayerRuntime.emit(PlaybackEvent.WaitingForDownload(book.driveId))
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                persistNow()
            }
        }
    }

    private fun startShakeGraceWindow() {
        if (!shakeToExtendEnabled) return
        timerPausedGraceUntilMs = System.currentTimeMillis() + SHAKE_GRACE_AFTER_PAUSE_MS
        updateShakeRegistration()
        shakeGraceJob?.cancel()
        shakeGraceJob = scope.launch {
            delay(SHAKE_GRACE_AFTER_PAUSE_MS + 250L)
            updateShakeRegistration()
        }
    }

    private suspend fun observeSleepTimer() {
        var wasOff = true
        PlayerRuntime.sleepTimer.collectLatest { state ->
            updateShakeRegistration()
            if (wasOff && state != SleepTimerState.Off) {
                recordCurrentPositionForHistory("Début de la minuterie")
            }
            wasOff = state == SleepTimerState.Off
            exo.pauseAtEndOfMediaItems = state is SleepTimerState.EndOfChapter
            if (state is SleepTimerState.Countdown) {
                try {
                    delay((state.endsAt - System.currentTimeMillis() - FADE_MS).coerceAtLeast(0))
                    val steps = 20
                    repeat(steps) { step ->
                        exo.volume = 1f - (step + 1) / steps.toFloat()
                        delay(FADE_MS / steps)
                    }
                    exo.pause()
                    startShakeGraceWindow()
                    PlayerRuntime.cancelTimer()
                } finally {
                    exo.volume = 1f
                }
            }
        }
    }

    private fun recordCurrentPositionForHistory(reason: String) {
        val book = currentBook ?: return
        val item = exo.currentMediaItem ?: return
        val ref = MediaIds.parseTrack(item.mediaId) ?: return
        if (ref.bookId != book.driveId) return
        val chapterStart = item.mediaMetadata.extras?.getLong(EXTRA_CHAPTER_START_MS, 0L) ?: 0L
        val pos = chapterStart + exo.currentPosition.coerceAtLeast(0L)
        val label = item.mediaMetadata.title?.toString()
            ?: currentTracks.firstOrNull { it.driveId == ref.trackId }?.chapterLabel
            ?: "Position"
        PlayerRuntime.recordJump(book.driveId, ref.trackId, label, pos, reason)
    }

    // ------------------------------------------------------------------ progression

    private data class Snapshot(
        val book: BookEntity,
        val tracks: List<TrackEntity>,
        val trackId: String,
        val positionMs: Long,
        val speed: Float,
        val markFinished: Boolean,
        /** Écoute effective depuis le dernier enregistrement : met à jour « dernière écoute ». */
        val listened: Boolean,
        /** Envoyer la position dans Drive une fois enregistrée. */
        val push: Boolean,
    )

    private fun snapshot(markFinished: Boolean, listened: Boolean, push: Boolean): Snapshot? {
        val book = currentBook ?: return null
        val item = exo.currentMediaItem ?: return null
        val ref = MediaIds.parseTrack(item.mediaId) ?: return null
        if (ref.bookId != book.driveId) return null
        val chapterStart = item.mediaMetadata.extras?.getLong(EXTRA_CHAPTER_START_MS, 0L) ?: 0L
        val unclippedPos = chapterStart + exo.currentPosition.coerceAtLeast(0L)
        return Snapshot(book, currentTracks, ref.trackId, unclippedPos, exo.playbackParameters.speed, markFinished, listened, push)
    }

    /**
     * Enregistre la position (instantané pris tout de suite, écrit ensuite). Sans effet tant que
     * le livre chargé n'a été ni écouté ni déplacé. Les écritures se font une par une, dans
     * l'ordre : la fin du livre ne peut pas être effacée par une pause enregistrée en même temps.
     * Avec [push], ou si le dernier envoi date de plus de 5 min, la position part aussi dans Drive.
     */
    private fun persistNow(markFinished: Boolean = false, push: Boolean = false) {
        if (!persistEnabled && !markFinished) return
        val now = System.currentTimeMillis()
        val send = push || now - lastPushAt >= PUSH_INTERVAL_MS
        val snapshot = snapshot(markFinished, listened = listenedSinceSave || exo.isPlaying, push = send) ?: return
        listenedSinceSave = exo.isPlaying
        if (send) lastPushAt = now
        persistScope.launch(start = CoroutineStart.UNDISPATCHED) { persistMutex.withLock { persist(snapshot) } }
    }

    private suspend fun persist(s: Snapshot) {
        val track = s.tracks.firstOrNull { it.driveId == s.trackId } ?: return
        val settings = deps.settings.current()
        val account = settings.accountKey
        val dao = deps.database.progressDao()
        val now = System.currentTimeMillis()
        val durations = ProgressMath.durations(s.tracks, s.book.durationMs)
        val percent = if (s.markFinished) 100f else ProgressMath.percent(durations, track.sortIndex, s.positionMs)
        val existing = dao.get(s.book.driveId, account)
        dao.upsert(
            (existing ?: ProgressEntity(bookId = s.book.driveId, account = account, updatedAt = now)).copy(
                trackId = track.driveId,
                trackIndex = track.sortIndex,
                positionMs = s.positionMs,
                speed = s.speed,
                percent = percent,
                lastListenedAt = if (s.listened) now else existing?.lastListenedAt,
                finished = (existing?.finished == true && percent >= FINISHED_PERCENT) || s.markFinished,
                updatedAt = now,
                dirty = true,
            ),
        )
        if (s.listened && settings.lastPlayedBookId != s.book.driveId) {
            deps.settings.update { it.copy(lastPlayedBookId = s.book.driveId) }
        }
        // Après l'écriture locale : l'envoi relit la base, la position est à jour.
        if (s.push) deps.progressSync?.pushProgress(s.book.driveId)
    }

    /** Vitesse mémorisée par livre : écrite seule, et seulement si elle change. */
    private suspend fun persistSpeed(bookId: String, speed: Float) {
        val account = deps.settings.current().accountKey
        val dao = deps.database.progressDao()
        val existing = dao.get(bookId, account)
        if (existing?.speed == speed || (existing == null && speed == 1f)) return
        val now = System.currentTimeMillis()
        dao.upsert((existing ?: ProgressEntity(bookId = bookId, account = account, updatedAt = now)).copy(speed = speed, updatedAt = now, dirty = true))
        deps.progressSync?.pushProgress(bookId)
    }

    // ------------------------------------------------------------------ livres → liste de lecture

    /**
     * Prépare la liste de lecture d'un livre. Sans piste ni position imposées, c'est une reprise :
     * la position de l'autre téléphone est relue dans Drive avant de choisir où repartir — ou,
     * avec [deferRemote] (vignette de reprise, bouton du casque, « Reprends mon livre »), la lecture
     * part tout de suite de la position locale et saute à celle de Drive si elle arrive plus
     * récente.
     */
    private suspend fun resolveBook(
        bookId: String,
        trackId: String?,
        positionMs: Long?,
        chapterIndex: Int? = null,
        deferRemote: Boolean = false,
    ): MediaSession.MediaItemsWithStartPosition? {
        // Livre précédent : sa position est enregistrée avant d'en charger un autre, et les
        // enregistrements en cours sont terminés pour lire une position à jour.
        persistNow()
        persistMutex.withLock { }
        val db = deps.database
        val book = db.bookDao().get(bookId) ?: return null
        val tracks = db.trackDao().forBook(bookId)
        val present = tracks.filter { it.isPlayableLocally() }
        if (present.isEmpty()) return null
        val embeddedByTrack = db.embeddedChapterDao().forBook(bookId).groupBy { it.trackId }
        val settings = deps.settings.current()
        val resuming = trackId == null && positionMs == null && chapterIndex == null
        val restored = if (resuming && !deferRemote) pullRemoteProgress(bookId, PULL_TIMEOUT_MS) else null
        val progress = db.progressDao().get(bookId, settings.accountKey)
        val restart = resuming && progress?.finished == true
        val wantedId = when {
            trackId != null -> trackId
            restart -> tracks.first().driveId
            else -> progress?.trackId ?: tracks.getOrNull(progress?.trackIndex ?: 0)?.driveId
        }
        val items = present.flatMap { buildTrackItems(book, it, tracks.size, embeddedByTrack[it.driveId].orEmpty()) }
        val matchingIndices = items.indices.filter { idx -> MediaIds.parseTrack(items[idx].mediaId)?.trackId == wantedId }
        val startIndex: Int
        val startPosition: Long
        if (matchingIndices.isEmpty()) {
            val wantedSort = tracks.firstOrNull { it.driveId == wantedId }?.sortIndex ?: 0
            val fallbackTrack = present.firstOrNull { it.sortIndex >= wantedSort } ?: present.first()
            startIndex = items.indexOfFirst { MediaIds.parseTrack(it.mediaId)?.trackId == fallbackTrack.driveId }.coerceAtLeast(0)
            startPosition = 0L
        } else {
            val unclippedStartPos: Long = when {
                chapterIndex != null && trackId != null -> {
                    val chStart = embeddedByTrack[trackId]?.firstOrNull { it.chapterIndex == chapterIndex }?.startMs ?: 0L
                    chStart + (positionMs ?: 0L)
                }
                positionMs != null -> positionMs
                restart || trackId != null -> 0L
                progress != null && progress.trackId == wantedId -> {
                    val pauseElapsed = progress.lastListenedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 60_000L
                    val rewindMs = SmartRewind.computeMs(pauseElapsed, settings.smartRewind)
                    (progress.positionMs - rewindMs).coerceAtLeast(0L)
                }
                else -> 0L
            }
            val chosen = matchingIndices.lastOrNull { idx ->
                val start = items[idx].mediaMetadata.extras?.getLong(EXTRA_CHAPTER_START_MS, 0L) ?: 0L
                unclippedStartPos >= start
            } ?: matchingIndices.first()
            val chosenStart = items[chosen].mediaMetadata.extras?.getLong(EXTRA_CHAPTER_START_MS, 0L) ?: 0L
            startIndex = chosen
            startPosition = (unclippedStartPos - chosenStart).coerceAtLeast(0L)
        }
        bind(book, tracks, embeddedByTrack)
        exo.setPlaybackSpeed(progress?.speed?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f)
        pausedAt = null
        if (restored != null) PlayerRuntime.emit(PlaybackEvent.ProgressRestored(bookId, restored.device, restored.percent))
        if (resuming && deferRemote) scope.launch { adoptRemoteProgressLater(bookId) }
        return MediaSession.MediaItemsWithStartPosition(items, startIndex, startPosition)
    }

    /** Position du livre dans Drive, au plus [timeoutMs] : null si rien de plus récent (ou hors ligne). */
    private suspend fun pullRemoteProgress(bookId: String, timeoutMs: Long): RemoteProgress? {
        val sync = deps.progressSync ?: return null
        return try {
            sync.pullProgress(bookId, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Position Drive non relue", e)
            null
        }
    }

    /**
     * Reprise lancée sans attendre Drive : si l'autre téléphone y a enregistré une position plus
     * récente, la lecture y saute dès qu'elle est relue (le livre doit être toujours chargé).
     */
    private suspend fun adoptRemoteProgressLater(bookId: String) {
        val restored = pullRemoteProgress(bookId, DEFERRED_PULL_TIMEOUT_MS) ?: return
        if (currentBook?.driveId != bookId) return
        val settings = deps.settings.current()
        val progress = deps.database.progressDao().get(bookId, settings.accountKey) ?: return
        val matching = (0 until exo.mediaItemCount).filter { MediaIds.parseTrack(exo.getMediaItemAt(it).mediaId)?.trackId == progress.trackId }
        if (matching.isEmpty()) return
        val pauseElapsed = progress.lastListenedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 60_000L
        val rewindMs = SmartRewind.computeMs(pauseElapsed, settings.smartRewind)
        val unclippedPos = (progress.positionMs - rewindMs).coerceAtLeast(0L)
        val chosen = matching.lastOrNull { idx ->
            val start = exo.getMediaItemAt(idx).mediaMetadata.extras?.getLong(EXTRA_CHAPTER_START_MS, 0L) ?: 0L
            unclippedPos >= start
        } ?: matching.first()
        val chosenStart = exo.getMediaItemAt(chosen).mediaMetadata.extras?.getLong(EXTRA_CHAPTER_START_MS, 0L) ?: 0L
        suppressNextSeekHistory = true
        exo.seekTo(chosen, (unclippedPos - chosenStart).coerceAtLeast(0L))
        PlayerRuntime.emit(PlaybackEvent.ProgressRestored(bookId, restored.device, restored.percent))
    }

    /** Livre chargé : protégé du nettoyage, et complété au fil des téléchargements. */
    private fun bind(book: BookEntity, tracks: List<TrackEntity>, embeddedByTrack: Map<String, List<EmbeddedChapterEntity>>) {
        currentBook = book
        currentBookIdFlow.value = book.driveId
        currentTracks = tracks
        currentEmbedded = embeddedByTrack
        waitingForNext = false
        persistEnabled = false
        listenedSinceSave = false
        finishedEmitted = false
        BookInUse.set(book.driveId)
        tracksJob?.cancel()
        tracksJob = scope.launch {
            combine(
                deps.database.trackDao().observeForBook(book.driveId),
                deps.database.embeddedChapterDao().observeForBook(book.driveId),
            ) { updated, embedded -> updated to embedded.groupBy { it.trackId } }
                .collect { (updated, embeddedMap) ->
                    // Livre retiré de la base pendant l'écoute : on garde ses pistes pour enregistrer la position.
                    if (updated.isNotEmpty()) currentTracks = updated
                    currentEmbedded = embeddedMap
                    insertNewlyDownloaded(book, updated, embeddedMap)
                }
        }
    }

    private fun insertNewlyDownloaded(book: BookEntity, tracks: List<TrackEntity>, embeddedMap: Map<String, List<EmbeddedChapterEntity>>) {
        if (currentBook?.driveId != book.driveId) return
        val inPlaylist = (0 until exo.mediaItemCount).mapNotNull { MediaIds.parseTrack(exo.getMediaItemAt(it).mediaId)?.trackId }.toSet()
        if (inPlaylist.isEmpty()) return
        val sortById = tracks.associate { it.driveId to it.sortIndex }
        var inserted = false
        for (track in tracks.filter { it.isPlayableLocally() && it.driveId !in inPlaylist }) {
            val index = (0 until exo.mediaItemCount).count { i ->
                val id = MediaIds.parseTrack(exo.getMediaItemAt(i).mediaId)?.trackId
                (sortById[id] ?: Int.MAX_VALUE) < track.sortIndex
            }
            val newItems = buildTrackItems(book, track, tracks.size, embeddedMap[track.driveId].orEmpty())
            exo.addMediaItems(index, newItems)
            inserted = true
        }
        if (inserted && waitingForNext && exo.currentMediaItemIndex < exo.mediaItemCount - 1) {
            waitingForNext = false
            exo.seekTo(exo.currentMediaItemIndex + 1, 0)
            exo.play()
        }
    }

    private fun TrackEntity.isPlayableLocally(): Boolean =
        state == TrackState.PRESENT && localPath?.let { File(it).exists() } == true

    /**
     * Pochette : adresse `content://` servie par [CoverArtProvider] (lisible par Android Auto et le
     * système, qui tournent dans d'autres processus), sinon l'URL en ligne.
     */
    private fun coverUri(book: BookEntity): Uri? =
        book.coverPath?.let(::File)?.takeIf { it.isFile }?.let { CoverArtProvider.uriFor(this, book.driveId, it) }
            ?: book.coverUrl?.let(Uri::parse)

    private fun buildTrackItems(
        book: BookEntity,
        track: TrackEntity,
        totalTracks: Int,
        embedded: List<EmbeddedChapterEntity>,
    ): List<MediaItem> =
        if (embedded.size > 1) {
            embedded.map { ch -> embeddedChapterPlaylistItem(book, track, ch, embedded.size) }
        } else {
            listOf(playlistItem(book, track, totalTracks))
        }

    /**
     * Élément de la liste de lecture : ce qu'affichent la vignette système (écran verrouillé,
     * Réglages rapides), la montre, Android Auto (lecture en cours, file d'attente) et les
     * autoradios Bluetooth. Android prend la 1ʳᵉ ligne dans `title` et la 2ᵉ dans `artist` :
     * **numéro de piste ou chapitre** (« Piste 4 / 51 », « Chapitre 4 »), puis **titre du livre** ;
     * l'auteur passe en album (3ᵉ ligne d'Android Auto), le nom de piste d'origine en sous-titre.
     */
    private fun playlistItem(book: BookEntity, track: TrackEntity, total: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaIds.track(book.driveId, track.driveId))
            .setUri(Uri.fromFile(File(track.localPath!!)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(Chapters.label(track, total))
                    .setSubtitle(track.title)
                    .setArtist(book.displayTitle)
                    .setAlbumTitle(book.displayAuthor)
                    .setAlbumArtist(book.displayAuthor)
                    .setArtworkUri(coverUri(book))
                    .setTrackNumber(track.sortIndex + 1)
                    .setTotalTrackCount(total)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .setExtras(Bundle().apply { putString(EXTRA_BOOK_ID, book.driveId) })
                    .build(),
            )
            .build()

    /** Chapitre intégré d'un fichier `.m4b` / `.m4a` / `.mp3` : découpé sans coupure via [MediaItem.ClippingConfiguration]. */
    private fun embeddedChapterPlaylistItem(
        book: BookEntity,
        track: TrackEntity,
        ch: EmbeddedChapterEntity,
        totalChapters: Int,
    ): MediaItem {
        val title = ch.title.ifBlank { "Chapitre ${ch.chapterIndex + 1} / $totalChapters" }
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(ch.startMs.coerceAtLeast(0L))
            .setEndPositionMs(if (ch.endMs > ch.startMs) ch.endMs else C.TIME_END_OF_SOURCE)
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaIds.track(book.driveId, track.driveId, ch.chapterIndex))
            .setUri(Uri.fromFile(File(track.localPath!!)))
            .setClippingConfiguration(clipping)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle("Chapitre ${ch.chapterIndex + 1} / $totalChapters")
                    .setArtist(book.displayTitle)
                    .setAlbumTitle(book.displayAuthor)
                    .setAlbumArtist(book.displayAuthor)
                    .setArtworkUri(coverUri(book))
                    .setTrackNumber(ch.chapterIndex + 1)
                    .setTotalTrackCount(totalChapters)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .setExtras(
                        Bundle().apply {
                            putString(EXTRA_BOOK_ID, book.driveId)
                            putLong(EXTRA_CHAPTER_START_MS, ch.startMs)
                            putLong(EXTRA_CHAPTER_END_MS, ch.endMs)
                        },
                    )
                    .build(),
            )
            .build()
    }

    // ------------------------------------------------------------------ catalogue (Android Auto, Assistant, vignette)

    /** Un livre écoutable hors ligne, avec ce qu'il faut pour l'afficher dans un navigateur média. */
    private data class BookCard(val book: BookEntity, val progress: ProgressEntity?, val presentTracks: Int) {
        val lastListenedAt: Long get() = progress?.lastListenedAt ?: 0L
    }

    private fun rootItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaIds.ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(getString(R.string.player_root_title))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                    .build(),
            )
            .build()

    /** Onglet de la racine : ses enfants (les livres) s'affichent en grille de pochettes. */
    private fun tabItem(status: BookStatus): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaIds.tab(status))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(getString(if (status == BookStatus.LATER) R.string.player_tab_later else R.string.player_tab_in_progress))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                    .setExtras(
                        Bundle().apply {
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
                        },
                    )
                    .build(),
            )
            .build()

    /**
     * Un livre dans une liste ou une grille : toucher lance la reprise, le chevron ouvre ses
     * chapitres (en liste). Avancement, « hors ligne » et pochette lisibles par Android Auto.
     */
    private fun bookBrowseItem(card: BookCard): MediaItem {
        val book = card.book
        val completion = AutoCatalog.bookCompletion(card.progress?.percent, card.progress?.finished == true)
        val extras = Bundle().apply {
            putString(EXTRA_BOOK_ID, book.driveId)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, completion.status)
            putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, completion.fraction)
            if (book.trackCount > 0 && card.presentTracks >= book.trackCount) {
                putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
            }
        }
        val subtitle = when {
            book.trackCount > 0 && card.presentTracks < book.trackCount ->
                listOfNotNull(book.displayAuthor, getString(R.string.player_downloaded_tracks, card.presentTracks, book.trackCount)).joinToString(" · ")
            else -> book.displayAuthor
        }
        return MediaItem.Builder()
            .setMediaId(MediaIds.bookRequest(book.driveId))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.displayTitle)
                    .setDisplayTitle(book.displayTitle)
                    .setSubtitle(subtitle)
                    .setDescription(book.displayNarrator?.let { "Lu par $it" })
                    .setArtist(book.displayAuthor)
                    .setAlbumTitle(book.displayTitle)
                    .setArtworkUri(coverUri(book))
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    /** Un chapitre dans la liste d'un livre : groupé par partie, avec son avancement et sa durée. */
    private fun chapterBrowseItem(book: BookEntity, track: TrackEntity, duration: Long, completion: AutoCatalog.Completion): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_BOOK_ID, book.driveId)
            track.partLabel?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
            putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, completion.status)
            putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, completion.fraction)
            putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
        }
        val subtitle = if (duration > 0) {
            getString(R.string.player_chapter_duration, track.chapterLabel, Formats.duration(duration, approximate = !track.durationExact))
        } else {
            track.chapterLabel
        }
        return MediaItem.Builder()
            .setMediaId(MediaIds.track(book.driveId, track.driveId))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setDisplayTitle(track.title)
                    .setSubtitle(subtitle)
                    .setArtist(book.displayAuthor)
                    .setAlbumTitle(book.displayTitle)
                    .setArtworkUri(coverUri(book))
                    .setTrackNumber(track.sortIndex + 1)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun embeddedChapterBrowseItem(
        book: BookEntity,
        track: TrackEntity,
        ch: EmbeddedChapterEntity,
        totalChapters: Int,
        completion: AutoCatalog.Completion,
    ): MediaItem {
        val duration = (ch.endMs - ch.startMs).coerceAtLeast(0L)
        val chLabel = "Chapitre ${ch.chapterIndex + 1}"
        val title = ch.title.ifBlank { "$chLabel / $totalChapters" }
        val extras = Bundle().apply {
            putString(EXTRA_BOOK_ID, book.driveId)
            putLong(EXTRA_CHAPTER_START_MS, ch.startMs)
            putLong(EXTRA_CHAPTER_END_MS, ch.endMs)
            putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, completion.status)
            putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, completion.fraction)
            putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
        }
        val subtitle = if (duration > 0) {
            getString(R.string.player_chapter_duration, chLabel, Formats.duration(duration, approximate = false))
        } else {
            chLabel
        }
        return MediaItem.Builder()
            .setMediaId(MediaIds.track(book.driveId, track.driveId, ch.chapterIndex))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setSubtitle(subtitle)
                    .setArtist(book.displayAuthor)
                    .setAlbumTitle(book.displayTitle)
                    .setArtworkUri(coverUri(book))
                    .setTrackNumber(ch.chapterIndex + 1)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    /**
     * Vignette de reprise du système (Réglages rapides, écran verrouillé, après un redémarrage ou
     * l'arrêt de l'app) : le livre, puis « Piste 4 / 51 · 42 % » (où l'on reprend), avec sa
     * pochette embarquée — le système ne charge pas d'image à ce moment-là.
     */
    private fun resumeItem(card: BookCard, resumeAt: String?, artwork: ByteArray?): MediaItem {
        val book = card.book
        val finished = card.progress?.finished == true
        val completion = AutoCatalog.bookCompletion(card.progress?.percent, finished)
        val metadata = MediaMetadata.Builder()
            .setTitle(book.displayTitle)
            .setDisplayTitle(book.displayTitle)
            .setSubtitle(AutoCatalog.resumeSubtitle(resumeAt, card.progress?.percent, finished))
            .setArtist(book.displayTitle)
            .setAlbumTitle(book.displayAuthor)
            .setAlbumArtist(book.displayAuthor)
            .setArtworkUri(coverUri(book))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setExtras(
                Bundle().apply {
                    putString(EXTRA_BOOK_ID, book.driveId)
                    putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, completion.status)
                    putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, completion.fraction)
                },
            )
        if (artwork != null) metadata.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        return MediaItem.Builder().setMediaId(MediaIds.bookRequest(book.driveId)).setMediaMetadata(metadata.build()).build()
    }

    /** Piste où la lecture reprendra (« Piste 4 / 51 », « Chapitre 4 »), d'après la progression enregistrée. */
    private suspend fun resumeLabel(card: BookCard): String? {
        val tracks = deps.database.trackDao().forBook(card.book.driveId)
        if (tracks.isEmpty()) return null
        val progress = card.progress
        val track = if (progress?.finished == true) {
            tracks.first()
        } else {
            tracks.firstOrNull { it.driveId == progress?.trackId } ?: tracks.getOrNull(progress?.trackIndex ?: 0) ?: tracks.first()
        }
        val embedded = deps.database.embeddedChapterDao().forTrack(track.driveId)
        if (embedded.size > 1) {
            val pos = if (progress?.finished == true) 0L else progress?.positionMs ?: 0L
            val ch = embedded.lastOrNull { pos >= it.startMs } ?: embedded.first()
            return ch.title.ifBlank { "Chapitre ${ch.chapterIndex + 1} / ${embedded.size}" }
        }
        return Chapters.label(track, tracks.size)
    }

    /** Livres d'un statut ayant des pistes téléchargées, le dernier écouté en premier. */
    private suspend fun playableBooks(status: BookStatus): List<BookCard> =
        cards(deps.database.bookDao().byEffectiveStatus(status))

    /** Tous les livres écoutables hors ligne, quel que soit leur statut (recherche, reprise). */
    private suspend fun allPlayableBooks(): List<BookCard> = cards(deps.database.bookDao().all())

    private suspend fun cards(books: List<BookEntity>): List<BookCard> {
        val db = deps.database
        val account = deps.settings.current().accountKey
        return books
            .filter { it.isPlayableKind && !it.hidden && !it.trashPending }
            .mapNotNull { book ->
                val present = db.trackDao().presentCount(book.driveId)
                if (present == 0) null else BookCard(book, db.progressDao().get(book.driveId, account), present)
            }
            .sortedWith(compareByDescending<BookCard> { it.lastListenedAt }.thenByDescending { it.book.statusChangedAt })
    }

    /** Onglets proposés : « En écoute » toujours, « Plus tard » seulement s'il a de quoi écouter. */
    private suspend fun tabs(): List<MediaItem> =
        AutoCatalog.TAB_STATUSES
            .filter { it == BookStatus.IN_PROGRESS || playableBooks(it).isNotEmpty() }
            .map { tabItem(it) }

    private suspend fun chapterBrowseItems(bookId: String): List<MediaItem> {
        val db = deps.database
        val book = db.bookDao().get(bookId) ?: return emptyList()
        val tracks = db.trackDao().forBook(bookId)
        val embeddedByTrack = db.embeddedChapterDao().forBook(bookId).groupBy { it.trackId }
        val progress = db.progressDao().get(bookId, deps.settings.current().accountKey)
        val durations = ProgressMath.durations(tracks, book.durationMs)
        val isCurrent = currentBook?.driveId == bookId && exo.mediaItemCount > 0
        val currentTrackId = if (isCurrent) currentTrack()?.driveId else progress?.trackId
        val currentIndex = tracks.indexOfFirst { it.driveId == currentTrackId }.takeIf { it >= 0 }
        val unclippedPositionMs = if (isCurrent) {
            val chStart = exo.currentMediaItem?.mediaMetadata?.extras?.getLong(EXTRA_CHAPTER_START_MS, 0L) ?: 0L
            chStart + exo.currentPosition.coerceAtLeast(0L)
        } else {
            progress?.positionMs ?: 0L
        }
        val finished = progress?.finished == true && !isCurrent
        return tracks.withIndex()
            .filter { (_, track) -> track.isPlayableLocally() }
            .flatMap { (index, track) ->
                val embedded = embeddedByTrack[track.driveId].orEmpty()
                if (embedded.size > 1) {
                    val activeChIndex = when {
                        currentIndex == null -> null
                        index < currentIndex -> Int.MAX_VALUE
                        index > currentIndex -> -1
                        else -> (embedded.lastOrNull { unclippedPositionMs >= it.startMs } ?: embedded.first()).chapterIndex
                    }
                    embedded.map { ch ->
                        val chDur = (ch.endMs - ch.startMs).coerceAtLeast(0L)
                        val chPos = if (index == currentIndex && ch.chapterIndex == activeChIndex) (unclippedPositionMs - ch.startMs).coerceAtLeast(0L) else 0L
                        val completion = AutoCatalog.chapterCompletion(ch.chapterIndex, activeChIndex, chPos, chDur, finished)
                        embeddedChapterBrowseItem(book, track, ch, embedded.size, completion)
                    }
                } else {
                    val duration = durations.getOrElse(index) { 0L }
                    listOf(chapterBrowseItem(book, track, duration, AutoCatalog.chapterCompletion(index, currentIndex, unclippedPositionMs, duration, finished)))
                }
            }
    }

    /** Pistes téléchargées d'un livre, prêtes pour la liste de lecture. */
    private suspend fun playlistItems(bookId: String): List<MediaItem> {
        val db = deps.database
        val book = db.bookDao().get(bookId) ?: return emptyList()
        val tracks = db.trackDao().forBook(bookId)
        val embeddedByTrack = db.embeddedChapterDao().forBook(bookId).groupBy { it.trackId }
        return tracks.filter { it.isPlayableLocally() }.flatMap { buildTrackItems(book, it, tracks.size, embeddedByTrack[it.driveId].orEmpty()) }
    }

    /**
     * Dernier livre écouté encore écoutable hors ligne (vignette de reprise, bouton du casque,
     * « Reprends mon livre ») ; à défaut, le dernier livre « En écoute » ayant des pistes.
     */
    private suspend fun lastPlayableBook(): BookCard? {
        val db = deps.database
        val settings = deps.settings.current()
        val candidates = listOfNotNull(settings.lastPlayedBookId, db.progressDao().lastListened(settings.accountKey)?.bookId).distinct()
        for (id in candidates) {
            val book = db.bookDao().get(id) ?: continue
            cards(listOf(book)).firstOrNull()?.let { return it }
        }
        return playableBooks(BookStatus.IN_PROGRESS).firstOrNull()
    }

    /** Livres écoutables correspondant à une recherche (barre de recherche Android Auto, Assistant). */
    private suspend fun searchBooks(query: String): List<BookCard> =
        allPlayableBooks()
            .map { it to AutoCatalog.searchScore(query, it.book.displayTitle, it.book.displayAuthor, it.book.displayNarrator) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<BookCard, Int>> { it.second }.thenByDescending { it.first.lastListenedAt })
            .map { it.first }

    /** Signature du catalogue : ce qui, en changeant, modifie les listes proposées aux navigateurs. */
    private data class CatalogEntry(val bookId: String, val status: BookStatus, val presentTracks: Int, val percent: Int?, val finished: Boolean)

    /**
     * Téléchargement terminé, geste de statut, écoute : les navigateurs abonnés (Android Auto)
     * sont prévenus que la racine, les onglets et les chapitres du livre concerné ont changé.
     */
    private suspend fun observeCatalogChanges() {
        var previous: Map<String, CatalogEntry>? = null
        deps.settings.settings.map { it.accountKey }.distinctUntilChanged()
            .flatMapLatest { deps.database.bookDao().observeLibrary(it) }
            .map { rows ->
                rows.filter { it.presentTracks > 0 && !it.book.hidden && !it.book.trashPending }
                    .associate { it.book.driveId to CatalogEntry(it.book.driveId, it.book.effectiveStatus, it.presentTracks, it.percent?.toInt(), it.finished == true) }
            }
            .distinctUntilChanged()
            .debounce(CATALOG_NOTIFY_DEBOUNCE_MS)
            .collect { entries ->
                val before = previous
                previous = entries
                val s = session ?: return@collect
                if (before == null) return@collect
                s.notifyChildrenChanged(MediaIds.ROOT, Int.MAX_VALUE, null)
                AutoCatalog.TAB_STATUSES.forEach { s.notifyChildrenChanged(MediaIds.tab(it), Int.MAX_VALUE, null) }
                (before.keys + entries.keys)
                    .filter { before[it]?.presentTracks != entries[it]?.presentTracks }
                    .forEach { s.notifyChildrenChanged(MediaIds.bookRequest(it), Int.MAX_VALUE, null) }
            }
    }

    // ------------------------------------------------------------------ boutons et session

    /**
     * Boutons média : saut arrière/avant réglable et « Vitesse » (cycle), tous en boutons
     * supplémentaires. Les emplacements précédent/suivant restent aux chapitres (⏮/⏭ standard) :
     * occupés par les sauts, Media3 retire `ACTION_SKIP_TO_PREVIOUS/NEXT` de la session système,
     * et les contrôleurs qui n'affichent pas les boutons personnalisés — montre Wear OS,
     * autoradios Bluetooth — n'ont alors plus que ▶/⏸, parfois figé. Téléphone : ⏮ ▶ ⏭ −30 +30.
     */
    private fun mediaButtons(): List<CommandButton> {
        val seconds = (skipMs / 1000).toInt()
        val (back, forward) = when (seconds) {
            10 -> CommandButton.ICON_SKIP_BACK_10 to CommandButton.ICON_SKIP_FORWARD_10
            15 -> CommandButton.ICON_SKIP_BACK_15 to CommandButton.ICON_SKIP_FORWARD_15
            else -> CommandButton.ICON_SKIP_BACK_30 to CommandButton.ICON_SKIP_FORWARD_30
        }
        val speed = if (::exo.isInitialized) exo.playbackParameters.speed else 1f
        return listOf(
            CommandButton.Builder(back)
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName(getString(R.string.player_seek_back, seconds))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(forward)
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName(getString(R.string.player_seek_forward, seconds))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
                .setSessionCommand(speedCommand)
                .setDisplayName(getString(R.string.player_speed, Formats.speed(speed)))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    private fun sessionActivity(): PendingIntent {
        val intent = (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()).apply {
            putExtra(EXTRA_OPEN_PLAYER, true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun emptyPlaylist() = MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), C.INDEX_UNSET, C.TIME_UNSET)

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /**
         * Toute application connectée peut commander la lecture, comme avant Media3 1.11 (qui
         * limite par défaut à la lecture seule les contrôleurs sans accès aux notifications) : le
         * relais de la montre, un autoradio ou l'Assistant doivent pouvoir lancer, mettre en pause
         * et sauter. Les commandes de lecture sont celles du lecteur, plus le bouton « Vitesse ».
         */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // Android Auto, System UI, Bluetooth… liront les pochettes par le fournisseur de contenu.
            CoverArtProvider.grantTo(this@PlaybackService, controller.packageName)
            return MediaSession.ConnectionResult.AcceptedResultBuilder()
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(speedCommand).build(),
                )
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_CYCLE_SPEED) {
                exo.setPlaybackSpeed(AutoCatalog.nextSpeed(exo.playbackParameters.speed))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Styles par défaut du catalogue : listes ; les onglets passent leurs livres en grille.
            val hints = LibraryParams.Builder()
                .setExtras(
                    Bundle().apply {
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                    },
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), hints))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val tab = MediaIds.parseTab(parentId)
            val items = when {
                parentId == MediaIds.ROOT -> tabs()
                tab != null -> playableBooks(tab).map { bookBrowseItem(it) }
                else -> MediaIds.parseBookRequest(parentId)?.let { chapterBrowseItems(it.bookId) } ?: emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val tab = MediaIds.parseTab(mediaId)
            val book = MediaIds.parseBookRequest(mediaId)?.let { deps.database.bookDao().get(it.bookId) }
            val track = MediaIds.parseTrack(mediaId)
            val item = when {
                mediaId == MediaIds.ROOT -> rootItem()
                tab != null -> tabItem(tab)
                book != null -> cards(listOf(book)).firstOrNull()?.let { bookBrowseItem(it) }
                track != null -> chapterBrowseItems(track.bookId).firstOrNull { it.mediaId == mediaId }
                else -> null
            }
            if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            session.notifySearchResultChanged(browser, query, searchBooks(query).size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(ImmutableList.copyOf(searchBooks(query).map { bookBrowseItem(it) }), params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val first = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
            val explicitPosition = startPositionMs.takeIf { it != C.TIME_UNSET && it > 0 }
            val query = first?.requestMetadata?.searchQuery
            val book = first?.let { MediaIds.parseBookRequest(it.mediaId) }
            val track = first?.let { MediaIds.parseTrack(it.mediaId) }
            when {
                // Assistant, Android Auto : « Écoute Civilisations » cherche un livre écoutable ;
                // « Reprends mon livre » (ou rien) relance le dernier, sans attendre Drive.
                query != null -> {
                    val resume = AutoCatalog.isGenericRequest(query)
                    val card = if (resume) lastPlayableBook() else searchBooks(query).firstOrNull()
                    card?.let { resolveBook(it.book.driveId, null, null, deferRemote = resume) }
                }
                book != null -> resolveBook(book.bookId, book.trackId, book.positionMs ?: explicitPosition)
                track != null -> resolveBook(track.bookId, track.trackId, explicitPosition ?: 0, chapterIndex = track.chapterIndex)
                else -> null
            } ?: emptyPlaylist()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                val track = MediaIds.parseTrack(item.mediaId)
                val book = MediaIds.parseBookRequest(item.mediaId)
                // Lecture hors ligne uniquement : seules les pistes téléchargées de la bibliothèque
                // sont acceptées, jamais une adresse fournie par une autre app.
                when {
                    track != null -> playlistItems(track.bookId).filter { it.mediaId == item.mediaId }
                    book != null -> playlistItems(book.bookId)
                    else -> emptyList()
                }
            }.toMutableList()
        }

        /**
         * Reprise demandée sans livre chargé : vignette du système, bouton du casque après l'arrêt
         * du service, Assistant. Sans intention de lecture ([isForPlayback] faux : le système
         * garnit sa vignette), un seul élément décrit le livre avec sa pochette ; sinon la liste
         * de lecture part aussitôt de la position locale, Drive est relu en parallèle.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val card = lastPlayableBook() ?: return@future emptyPlaylist()
            if (isForPlayback) {
                resolveBook(card.book.driveId, null, null, deferRemote = true) ?: emptyPlaylist()
            } else {
                val artwork = withContext(Dispatchers.IO) { CoverThumbnails.jpegBytes(card.book.coverPath, RESUME_ART_PX) }
                MediaSession.MediaItemsWithStartPosition(ImmutableList.of(resumeItem(card, resumeLabel(card), artwork)), 0, C.TIME_UNSET)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "com.julienmiquel.audiotheque.OPEN_PLAYER"
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_CHAPTER_START_MS = "chapterStartMs"
        const val EXTRA_CHAPTER_END_MS = "chapterEndMs"
        const val COMMAND_CYCLE_SPEED = "com.julienmiquel.audiotheque.CYCLE_SPEED"
        const val SAVE_INTERVAL_MS = 10_000L
        const val RESUME_REWIND_MS = 5_000L
        const val MIN_PAUSE_FOR_REWIND_MS = 2_000L
        const val FADE_MS = 10_000L
        const val MIN_SPEED = 0.8f
        const val MAX_SPEED = 2.0f

        /** Déplacement dans le même chapitre enregistré dans l'historique à partir de 15 s. */
        private const val MIN_SEEK_FOR_HISTORY_MS = 15_000L

        /** Secousse pour prolonger la minuterie : seuil (en g au-dessus de la gravité), fenêtre et anti-rebond. */
        private const val SHAKE_THRESHOLD_G = 1.25
        private const val SHAKE_WINDOW_MS = 1_500L
        private const val SHAKE_COOLDOWN_MS = 3_000L

        /** Fenêtre de grâce après l'arrêt par la minuterie pendant laquelle secouer relance 10 min. */
        private const val SHAKE_GRACE_AFTER_PAUSE_MS = 60_000L

        /** Pendant l'écoute, la position part dans Drive au plus toutes les 5 min (et à chaque pause). */
        const val PUSH_INTERVAL_MS = 5 * 60_000L

        /** Relecture de la position Drive avant une lecture : au-delà, on repart de la position locale. */
        const val PULL_TIMEOUT_MS = 4_000L

        /** Reprise lancée sans attendre (vignette, casque) : la position Drive est relue en parallèle, au plus 15 s. */
        const val DEFERRED_PULL_TIMEOUT_MS = 15_000L

        /** Pochette embarquée dans la vignette de reprise. */
        const val RESUME_ART_PX = 320

        /** Côté maximal des pochettes chargées pour la session (notification, écran verrouillé, Android Auto). */
        const val ARTWORK_MAX_PX = 1024

        /** Les navigateurs sont prévenus d'un changement du catalogue au plus toutes les 1,5 s. */
        private const val CATALOG_NOTIFY_DEBOUNCE_MS = 1_500L

        /** Au-delà, le livre reste « terminé » malgré une petite marge de position. */
        private const val FINISHED_PERCENT = 99f
        private const val TAG = "PlaybackService"
    }
}
