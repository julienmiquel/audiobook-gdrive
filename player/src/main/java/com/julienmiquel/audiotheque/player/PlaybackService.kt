package com.julienmiquel.audiotheque.player

import android.app.PendingIntent
import android.content.Intent
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.ProgressMath
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.model.chapterLabel
import com.julienmiquel.audiotheque.core.model.displayAuthor
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.sync.BookInUse
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lecture hors ligne uniquement (fichiers locaux, jamais de streaming depuis Drive), avec
 * notification média, écran verrouillé, boutons du casque Bluetooth et Android Auto.
 *
 * Reprise à la seconde près : position enregistrée toutes les 10 s et à chaque pause ; à la
 * reprise, recul automatique de 5 s. Charger un livre (écran Lecteur, Android Auto) n'enregistre
 * rien : seules l'écoute et les actions de l'utilisateur (déplacement, vitesse) le font.
 */
@OptIn(UnstableApi::class)
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
    private var currentTracks: List<TrackEntity> = emptyList()
    private var tracksJob: Job? = null
    private var tickerJob: Job? = null
    private var pausedAt: Long? = null
    private var waitingForNext = false

    /** Faux tant que le livre chargé n'a été ni écouté ni déplacé par l'utilisateur. */
    private var persistEnabled = false

    /** Écoute effective depuis le dernier enregistrement (date de « dernière écoute »). */
    private var listenedSinceSave = false

    @Volatile
    private var skipMs = 30_000L

    override fun onCreate() {
        super.onCreate()
        deps = (application as PlayerDependenciesProvider).playerDependencies
        exo = ExoPlayer.Builder(this)
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
            .setMediaButtonPreferences(skipButtons())
            .build()

        scope.launch {
            deps.settings.settings.map { it.skipSeconds }.distinctUntilChanged().collect { seconds ->
                skipMs = seconds * 1000L
                session?.setMediaButtonPreferences(skipButtons())
            }
        }
        scope.launch { observeSleepTimer() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exo.playWhenReady || exo.mediaItemCount == 0) {
            persistNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        persistNow()
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
        val ref = MediaIds.parseTrack(exo.getMediaItemAt(playlistIndex).mediaId) ?: return 0
        return currentTracks.firstOrNull { it.driveId == ref.trackId }?.durationMs ?: 0
    }

    private fun rewindAfterPause() {
        val since = pausedAt ?: return
        pausedAt = null
        if (System.currentTimeMillis() - since >= MIN_PAUSE_FOR_REWIND_MS && exo.playbackState != Player.STATE_ENDED) {
            exo.seekTo(exo.currentMediaItemIndex, (exo.currentPosition - RESUME_REWIND_MS).coerceAtLeast(0))
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
                persistNow()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            finishedEmitted = false
            // Nouvelle liste (livre chargé) : rien à enregistrer, ce n'est pas une écoute.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) persistNow()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                // Position choisie par l'utilisateur (barre, chapitres, saut) : enregistrée.
                finishedEmitted = false
                persistEnabled = true
                persistNow()
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
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
        return track.sortIndex >= last
    }

    private fun onEnded() {
        val book = currentBook ?: return
        currentTrack() ?: return
        if (isOnLastTrack()) {
            if (finishedEmitted) return
            finishedEmitted = true
            persistNow(markFinished = true)
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

    private suspend fun observeSleepTimer() {
        PlayerRuntime.sleepTimer.collectLatest { state ->
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
                    PlayerRuntime.cancelTimer()
                } finally {
                    exo.volume = 1f
                }
            }
        }
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
    )

    private fun snapshot(markFinished: Boolean, listened: Boolean): Snapshot? {
        val book = currentBook ?: return null
        val ref = exo.currentMediaItem?.let { MediaIds.parseTrack(it.mediaId) } ?: return null
        if (ref.bookId != book.driveId) return null
        return Snapshot(book, currentTracks, ref.trackId, exo.currentPosition.coerceAtLeast(0), exo.playbackParameters.speed, markFinished, listened)
    }

    /**
     * Enregistre la position (instantané pris tout de suite, écrit ensuite). Sans effet tant que
     * le livre chargé n'a été ni écouté ni déplacé. Les écritures se font une par une, dans
     * l'ordre : la fin du livre ne peut pas être effacée par une pause enregistrée en même temps.
     */
    private fun persistNow(markFinished: Boolean = false) {
        if (!persistEnabled && !markFinished) return
        val snapshot = snapshot(markFinished, listened = listenedSinceSave || exo.isPlaying) ?: return
        listenedSinceSave = exo.isPlaying
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
    }

    /** Vitesse mémorisée par livre : écrite seule, et seulement si elle change. */
    private suspend fun persistSpeed(bookId: String, speed: Float) {
        val account = deps.settings.current().accountKey
        val dao = deps.database.progressDao()
        val existing = dao.get(bookId, account)
        if (existing?.speed == speed || (existing == null && speed == 1f)) return
        val now = System.currentTimeMillis()
        dao.upsert((existing ?: ProgressEntity(bookId = bookId, account = account, updatedAt = now)).copy(speed = speed, updatedAt = now, dirty = true))
    }

    // ------------------------------------------------------------------ livres → éléments média

    private suspend fun resolveBook(bookId: String, trackId: String?, positionMs: Long?): MediaSession.MediaItemsWithStartPosition? {
        // Livre précédent : sa position est enregistrée avant d'en charger un autre, et les
        // enregistrements en cours sont terminés pour lire une position à jour.
        persistNow()
        persistMutex.withLock { }
        val db = deps.database
        val book = db.bookDao().get(bookId) ?: return null
        val tracks = db.trackDao().forBook(bookId)
        val present = tracks.filter { it.isPlayableLocally() }
        if (present.isEmpty()) return null
        val progress = db.progressDao().get(bookId, deps.settings.current().accountKey)
        val restart = trackId == null && positionMs == null && progress?.finished == true
        val wantedId = when {
            trackId != null -> trackId
            restart -> tracks.first().driveId
            else -> progress?.trackId ?: tracks.getOrNull(progress?.trackIndex ?: 0)?.driveId
        }
        var startIndex = present.indexOfFirst { it.driveId == wantedId }
        val startPosition: Long
        if (startIndex < 0) {
            val wantedSort = tracks.firstOrNull { it.driveId == wantedId }?.sortIndex ?: 0
            startIndex = present.indexOfFirst { it.sortIndex >= wantedSort }.takeIf { it >= 0 } ?: 0
            startPosition = 0
        } else {
            startPosition = when {
                positionMs != null -> positionMs
                restart || trackId != null -> 0
                progress != null && progress.trackId == wantedId -> (progress.positionMs - RESUME_REWIND_MS).coerceAtLeast(0)
                else -> 0
            }
        }
        bind(book, tracks)
        exo.setPlaybackSpeed(progress?.speed?.coerceIn(MIN_SPEED, MAX_SPEED) ?: 1f)
        pausedAt = null
        return MediaSession.MediaItemsWithStartPosition(present.map { mediaItem(book, it, tracks.size) }, startIndex, startPosition)
    }

    /** Livre chargé : protégé du nettoyage, et complété au fil des téléchargements. */
    private fun bind(book: BookEntity, tracks: List<TrackEntity>) {
        currentBook = book
        currentTracks = tracks
        waitingForNext = false
        persistEnabled = false
        listenedSinceSave = false
        finishedEmitted = false
        BookInUse.set(book.driveId)
        tracksJob?.cancel()
        tracksJob = scope.launch {
            deps.database.trackDao().observeForBook(book.driveId).collect { updated ->
                // Livre retiré de la base pendant l'écoute : on garde ses pistes pour enregistrer la position.
                if (updated.isNotEmpty()) currentTracks = updated
                insertNewlyDownloaded(book, updated)
            }
        }
    }

    private fun insertNewlyDownloaded(book: BookEntity, tracks: List<TrackEntity>) {
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
            exo.addMediaItem(index, mediaItem(book, track, tracks.size))
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

    private fun coverUri(book: BookEntity): Uri? =
        book.coverPath?.let { Uri.fromFile(File(it)) } ?: book.coverUrl?.let(Uri::parse)

    private fun mediaItem(book: BookEntity, track: TrackEntity, total: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaIds.track(book.driveId, track.driveId))
            .setUri(Uri.fromFile(File(track.localPath!!)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setSubtitle(track.chapterLabel)
                    .setArtist(book.displayAuthor)
                    .setAlbumTitle(book.displayTitle)
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

    private fun bookItem(book: BookEntity): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaIds.bookRequest(book.driveId))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.displayTitle)
                    .setArtist(book.displayAuthor)
                    .setArtworkUri(coverUri(book))
                    .setIsBrowsable(true)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .build(),
            )
            .build()

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

    /** Livres « En écoute » ayant des pistes téléchargées (Android Auto). */
    private suspend fun browsableBooks(): List<MediaItem> {
        val db = deps.database
        return db.bookDao().byEffectiveStatus(BookStatus.IN_PROGRESS)
            .filter { db.trackDao().presentCount(it.driveId) > 0 }
            .map { bookItem(it) }
    }

    private suspend fun chapterItems(bookId: String): List<MediaItem> {
        val db = deps.database
        val book = db.bookDao().get(bookId) ?: return emptyList()
        val tracks = db.trackDao().forBook(bookId)
        return tracks.filter { it.isPlayableLocally() }.map { mediaItem(book, it, tracks.size) }
    }

    private fun skipButtons(): List<CommandButton> {
        val seconds = (skipMs / 1000).toInt()
        val (back, forward) = when (seconds) {
            10 -> CommandButton.ICON_SKIP_BACK_10 to CommandButton.ICON_SKIP_FORWARD_10
            15 -> CommandButton.ICON_SKIP_BACK_15 to CommandButton.ICON_SKIP_FORWARD_15
            else -> CommandButton.ICON_SKIP_BACK_30 to CommandButton.ICON_SKIP_FORWARD_30
        }
        return listOf(
            CommandButton.Builder(back)
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName(getString(R.string.player_seek_back, seconds))
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(forward)
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName(getString(R.string.player_seek_forward, seconds))
                .setSlots(CommandButton.SLOT_FORWARD)
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

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items = if (parentId == MediaIds.ROOT) {
                browsableBooks()
            } else {
                MediaIds.parseBookRequest(parentId)?.let { chapterItems(it.bookId) } ?: emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val book = MediaIds.parseBookRequest(mediaId)?.let { deps.database.bookDao().get(it.bookId) }
            val track = MediaIds.parseTrack(mediaId)
            val item = when {
                mediaId == MediaIds.ROOT -> rootItem()
                book != null -> bookItem(book)
                track != null -> chapterItems(track.bookId).firstOrNull { it.mediaId == mediaId }
                else -> null
            }
            if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
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
            val book = first?.let { MediaIds.parseBookRequest(it.mediaId) }
            val track = first?.let { MediaIds.parseTrack(it.mediaId) }
            when {
                book != null -> resolveBook(book.bookId, book.trackId, book.positionMs ?: explicitPosition)
                track != null -> resolveBook(track.bookId, track.trackId, explicitPosition ?: 0)
                else -> null
            } ?: MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), C.INDEX_UNSET, C.TIME_UNSET)
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
                    track != null -> chapterItems(track.bookId).filter { it.mediaId == item.mediaId }
                    book != null -> chapterItems(book.bookId)
                    else -> emptyList()
                }
            }.toMutableList()
        }

        @Deprecated("Remplacée par la variante avec isForPlayback dans les versions récentes de Media3")
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val db = deps.database
            val settings = deps.settings.current()
            val bookId = settings.lastPlayedBookId ?: db.progressDao().lastListened(settings.accountKey)?.bookId
            bookId?.let { resolveBook(it, null, null) }
                ?: MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), C.INDEX_UNSET, C.TIME_UNSET)
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "com.julienmiquel.audiotheque.OPEN_PLAYER"
        const val EXTRA_BOOK_ID = "bookId"
        const val SAVE_INTERVAL_MS = 10_000L
        const val RESUME_REWIND_MS = 5_000L
        const val MIN_PAUSE_FOR_REWIND_MS = 2_000L
        const val FADE_MS = 10_000L
        const val MIN_SPEED = 0.8f
        const val MAX_SPEED = 2.0f

        /** Au-delà, le livre reste « terminé » malgré une petite marge de position. */
        private const val FINISHED_PERCENT = 99f
        private const val TAG = "PlaybackService"
    }
}
