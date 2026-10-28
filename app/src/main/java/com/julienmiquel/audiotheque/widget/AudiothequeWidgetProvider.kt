package com.julienmiquel.audiotheque.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.julienmiquel.audiotheque.AudiothequeApp
import com.julienmiquel.audiotheque.MainActivity
import com.julienmiquel.audiotheque.R
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.model.chapterLabel
import com.julienmiquel.audiotheque.core.model.displayAuthor
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.player.CoverThumbnails
import com.julienmiquel.audiotheque.player.MediaIds
import com.julienmiquel.audiotheque.player.PlaybackService
import com.julienmiquel.audiotheque.player.PlayerRuntime
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Widget d'écran d'accueil : affiche le livre audio en cours d'écoute (pochette, titre, chapitre,
 * pourcentage) et permet de lancer/mettre en pause ou de reculer/avancer sans ouvrir l'application.
 */
class AudiothequeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                renderAll(context.applicationContext, appWidgetManager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_TOGGLE || action == ACTION_REWIND || action == ACTION_FORWARD || action == ACTION_REFRESH) {
            val pending = goAsync()
            val appContext = context.applicationContext
            scope.launch {
                try {
                    if (action != ACTION_REFRESH) {
                        handleCommand(appContext, action)
                    }
                    val manager = AppWidgetManager.getInstance(appContext)
                    val ids = manager.getAppWidgetIds(ComponentName(appContext, AudiothequeWidgetProvider::class.java))
                    if (ids.isNotEmpty()) {
                        renderAll(appContext, manager, ids)
                    }
                } finally {
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    private suspend fun handleCommand(context: Context, action: String) {
        val app = context as? AudiothequeApp ?: return
        val targetBook = resolveTargetBook(app) ?: return
        val presentCount = app.container.database.trackDao().presentCount(targetBook.driveId)
        if (presentCount <= 0) {
            // Pas de piste locale : ouvre le lecteur pour afficher l'état.
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(PlaybackService.EXTRA_OPEN_PLAYER, true)
            }
            context.startActivity(open)
            return
        }

        withContext(Dispatchers.Main) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val controller = withTimeoutOrNull(4_000L) {
                MediaController.Builder(context, token).buildAsync().await()
            } ?: return@withContext
            try {
                val activeBookId = controller.currentMediaItem?.mediaId?.let { MediaIds.parseTrack(it)?.bookId }
                if (controller.mediaItemCount == 0 || activeBookId != targetBook.driveId) {
                    controller.setMediaItem(
                        MediaItem.Builder()
                            .setMediaId(MediaIds.bookRequest(targetBook.driveId))
                            .build(),
                    )
                    controller.prepare()
                    controller.play()
                } else {
                    when (action) {
                        ACTION_TOGGLE -> if (controller.isPlaying) controller.pause() else controller.play()
                        ACTION_REWIND -> controller.seekBack()
                        ACTION_FORWARD -> controller.seekForward()
                    }
                }
            } finally {
                controller.release()
            }
        }
    }

    private suspend fun renderAll(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val app = context as? AudiothequeApp ?: return
        val views = withContext(Dispatchers.IO) { buildRemoteViews(app) }
        for (id in ids) {
            manager.updateAppWidget(id, views)
        }
    }

    private suspend fun buildRemoteViews(app: AudiothequeApp): RemoteViews {
        val views = RemoteViews(app.packageName, R.layout.widget_audiotheque)
        val openAppIntent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(PlaybackService.EXTRA_OPEN_PLAYER, true)
        }
        val openPending = PendingIntent.getActivity(
            app,
            REQ_OPEN,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPending)
        views.setOnClickPendingIntent(R.id.widget_cover, openPending)

        views.setOnClickPendingIntent(R.id.widget_btn_play_pause, broadcastPending(app, ACTION_TOGGLE, REQ_TOGGLE))
        views.setOnClickPendingIntent(R.id.widget_btn_rewind, broadcastPending(app, ACTION_REWIND, REQ_REWIND))
        views.setOnClickPendingIntent(R.id.widget_btn_forward, broadcastPending(app, ACTION_FORWARD, REQ_FORWARD))

        val book = resolveTargetBook(app)
        if (book == null) {
            views.setTextViewText(R.id.widget_title, app.getString(R.string.widget_empty_title))
            views.setTextViewText(R.id.widget_subtitle, app.getString(R.string.widget_empty_subtitle))
            views.setViewVisibility(R.id.widget_progress, View.GONE)
            views.setViewVisibility(R.id.widget_btn_rewind, View.GONE)
            views.setViewVisibility(R.id.widget_btn_forward, View.GONE)
            views.setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_headphones)
            views.setImageViewResource(R.id.widget_btn_play_pause, R.drawable.ic_widget_play)
            views.setOnClickPendingIntent(R.id.widget_btn_play_pause, openPending)
            return views
        }

        val live = PlayerRuntime.activePlayback.value
        val isCurrentLive = live.bookId == book.driveId
        val isPlaying = isCurrentLive && live.isPlaying

        val account = app.container.settings.current().accountKey
        val db = app.container.database
        val progress = db.progressDao().get(book.driveId, account)
        val tracks = db.trackDao().forBook(book.driveId)
        val presentTracks = tracks.count { it.state == TrackState.PRESENT }
        val pct = (progress?.percent ?: 0f).coerceIn(0f, 100f).roundToInt()

        val chapterText = when {
            isCurrentLive && !live.chapterLabel.isNullOrBlank() -> live.chapterLabel
            progress != null && tracks.isNotEmpty() -> {
                val track = tracks.firstOrNull { it.driveId == progress.trackId }
                    ?: tracks.getOrNull(progress.trackIndex)
                track?.chapterLabel
            }
            tracks.isNotEmpty() -> tracks.first().chapterLabel
            else -> null
        }

        val subtitleParts = buildList {
            if (!chapterText.isNullOrBlank()) add(chapterText)
            else book.displayAuthor?.let(::add)
            if (pct > 0) add("$pct %")
            else if (presentTracks == 0) add("À télécharger")
        }

        views.setTextViewText(R.id.widget_title, book.displayTitle)
        views.setTextViewText(
            R.id.widget_subtitle,
            subtitleParts.joinToString(" · ").ifBlank { book.displayAuthor ?: app.getString(R.string.app_name) },
        )
        views.setViewVisibility(R.id.widget_progress, View.VISIBLE)
        views.setProgressBar(R.id.widget_progress, 100, pct, false)

        val showSkip = presentTracks > 0
        views.setViewVisibility(R.id.widget_btn_rewind, if (showSkip) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_btn_forward, if (showSkip) View.VISIBLE else View.GONE)
        views.setImageViewResource(
            R.id.widget_btn_play_pause,
            if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )

        val coverBytes = CoverThumbnails.jpegBytes(book.coverPath, maxPx = 192)
        val bitmap = coverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_cover, bitmap)
        } else {
            views.setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_headphones)
        }
        return views
    }

    private suspend fun resolveTargetBook(app: AudiothequeApp): BookEntity? {
        val db = app.container.database
        val bookDao = db.bookDao()
        val settings = app.container.settings.current()

        // 1. Livre actuellement chargé dans le service ou sélectionné dans l'app.
        PlayerRuntime.activePlayback.value.bookId?.let { id ->
            bookDao.get(id)?.takeIf { !it.trashPending && !it.hidden }?.let { return it }
        }
        app.container.selectedBookId.value?.let { id ->
            bookDao.get(id)?.takeIf { !it.trashPending && !it.hidden }?.let { return it }
        }
        // 2. Dernier livre écouté mémorisé dans les réglages ou en base.
        settings.lastPlayedBookId?.let { id ->
            bookDao.get(id)?.takeIf { !it.trashPending && !it.hidden }?.let { return it }
        }
        db.progressDao().lastListened(settings.accountKey)?.bookId?.let { id ->
            bookDao.get(id)?.takeIf { !it.trashPending && !it.hidden }?.let { return it }
        }
        // 3. Premier livre « En écoute » disponible.
        return bookDao.byEffectiveStatus(BookStatus.IN_PROGRESS)
            .firstOrNull { !it.trashPending && !it.hidden }
    }

    private fun broadcastPending(context: Context, action: String, reqCode: Int): PendingIntent {
        val intent = Intent(context, AudiothequeWidgetProvider::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(
            context,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_TOGGLE = "com.julienmiquel.audiotheque.widget.ACTION_TOGGLE"
        const val ACTION_REWIND = "com.julienmiquel.audiotheque.widget.ACTION_REWIND"
        const val ACTION_FORWARD = "com.julienmiquel.audiotheque.widget.ACTION_FORWARD"
        const val ACTION_REFRESH = "com.julienmiquel.audiotheque.widget.ACTION_REFRESH"

        private const val REQ_OPEN = 100
        private const val REQ_TOGGLE = 101
        private const val REQ_REWIND = 102
        private const val REQ_FORWARD = 103

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Demande le rafraîchissement des widgets présents sur l'écran d'accueil (sans coût s'il n'y en a aucun). */
        fun requestUpdate(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, AudiothequeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(appContext, AudiothequeWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            appContext.sendBroadcast(intent)
        }
    }
}
