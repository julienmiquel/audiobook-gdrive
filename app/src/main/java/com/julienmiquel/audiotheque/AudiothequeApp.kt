package com.julienmiquel.audiotheque

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import com.julienmiquel.audiotheque.auth.DriveAuthorization
import com.julienmiquel.audiotheque.auth.GoogleAuthManager
import com.julienmiquel.audiotheque.auth.authErrorMessage
import com.julienmiquel.audiotheque.auth.consentFailureMessage
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.Accounts
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.PairMode
import com.julienmiquel.audiotheque.core.model.ProgressMath
import com.julienmiquel.audiotheque.core.model.SyncResult
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.model.chapterLabel
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.effectiveParentId
import com.julienmiquel.audiotheque.core.model.userEffectiveStatus
import com.julienmiquel.audiotheque.core.settings.ListenerProfile
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.settings.StatusSyncMode
import com.julienmiquel.audiotheque.core.settings.SyncState
import com.julienmiquel.audiotheque.core.sync.BookInUse
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.core.sync.ProgressSyncRequester
import com.julienmiquel.audiotheque.core.sync.RemoteProgress
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.covers.CoverImporter
import com.julienmiquel.audiotheque.library.AppleBooksClient
import com.julienmiquel.audiotheque.library.BookEditor
import com.julienmiquel.audiotheque.library.CoverSource
import com.julienmiquel.audiotheque.library.EnrichOutcome
import com.julienmiquel.audiotheque.library.Enricher
import com.julienmiquel.audiotheque.library.Gesture
import com.julienmiquel.audiotheque.library.GoogleBooksClient
import com.julienmiquel.audiotheque.library.GoogleImageSearchClient
import com.julienmiquel.audiotheque.library.LibraryRepository
import com.julienmiquel.audiotheque.library.MoveResult
import com.julienmiquel.audiotheque.library.OpenLibraryClient
import com.julienmiquel.audiotheque.library.StatusGestures
import com.julienmiquel.audiotheque.library.TrashResult
import com.julienmiquel.audiotheque.player.PlaybackService
import com.julienmiquel.audiotheque.player.PlayerConnection
import com.julienmiquel.audiotheque.player.PlayerDependencies
import com.julienmiquel.audiotheque.player.PlayerDependenciesProvider
import com.julienmiquel.audiotheque.sync.drive.DriveAbout
import com.julienmiquel.audiotheque.sync.drive.RestDriveService
import com.julienmiquel.audiotheque.sync.engine.AndroidNetworkStatus
import com.julienmiquel.audiotheque.sync.engine.DriveProvider
import com.julienmiquel.audiotheque.sync.engine.LocalStorage
import com.julienmiquel.audiotheque.sync.engine.MediaMetadataInspector
import com.julienmiquel.audiotheque.sync.engine.SyncEngine
import com.julienmiquel.audiotheque.sync.work.SyncComponent
import com.julienmiquel.audiotheque.sync.work.SyncComponentProvider
import com.julienmiquel.audiotheque.sync.work.SyncNotifications
import com.julienmiquel.audiotheque.sync.work.SyncScheduler
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** Assemblage des modules (injection manuelle). */
class AppContainer(private val app: Application) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock: Clock = Clock.SYSTEM
    val database: AppDatabase = AppDatabase.build(app)
    val settings: SettingsRepository = SettingsRepository.create(app)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val auth = GoogleAuthManager(app, settings)
    val storage = LocalStorage(app, settings)

    /** Google Drive API v3, appelée directement depuis le téléphone avec le jeton du compte connecté. */
    private val restDrive: RestDriveService by lazy { RestDriveService(http, auth) }

    /** Seul accès à Drive : celui du moteur de synchro. */
    val engine = SyncEngine(
        db = database,
        settings = settings,
        driveProvider = DriveProvider { if (settings.current().accountEmail.isNullOrBlank()) null else restDrive },
        storage = storage,
        network = AndroidNetworkStatus(app),
        inspector = MediaMetadataInspector(),
        clock = clock,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    val scheduler = SyncScheduler(app) { engine }
    val syncNotifications = SyncNotifications(app)
    val syncComponent = SyncComponent(engine, scheduler, syncNotifications)

    /**
     * Progression échangée avec Drive hors synchro complète : envoi après une pause (sans
     * attendre), relecture avant une lecture (délai borné par le lecteur). Seul le moteur parle à
     * Drive ; ce pont ne fait que lui transmettre les demandes du lecteur et de la bibliothèque.
     */
    val progressBridge: ProgressSyncRequester = object : ProgressSyncRequester {
        override suspend fun pullProgress(bookId: String, timeoutMs: Long): RemoteProgress? =
            withTimeoutOrNull(timeoutMs) { engine.pullProgress(bookId) }

        override fun pushProgress(bookId: String?) {
            appScope.launch { withTimeoutOrNull(PUSH_TIMEOUT_MS) { engine.pushProgress(bookId) } }
        }
    }

    val library = LibraryRepository(
        db = database,
        settings = settings,
        onProgressDirty = { id -> progressBridge.pushProgress(id) },
    )
    val gestures = StatusGestures(
        db = database,
        settings = settings,
        sync = scheduler,
        clock = clock,
        onProgressDirty = { id -> progressBridge.pushProgress(id) },
    )
    val editor = BookEditor(
        db = database,
        clock = clock,
        onMetadataDirty = { id ->
            appScope.launch { withTimeoutOrNull(PUSH_TIMEOUT_MS) { engine.pushMetadata(id) } }
        },
    )
    val backup = com.julienmiquel.audiotheque.library.BackupManager(database, settings, clock)

    /**
     * Sources de pochettes, dans l'ordre : Google Books (résumé compris), Apple Books (vraies
     * pochettes de livres audio), Open Library, puis Google Images si une clé est configurée.
     */
    private val coverSources: List<CoverSource> = listOfNotNull(
        GoogleBooksClient(http),
        AppleBooksClient(http),
        OpenLibraryClient(http),
        BuildConfig.CUSTOM_SEARCH_KEY.takeIf { it.isNotBlank() && BuildConfig.CUSTOM_SEARCH_CX.isNotBlank() }
            ?.let { GoogleImageSearchClient(http, it, BuildConfig.CUSTOM_SEARCH_CX) },
    )

    /** Recherche d'images Google disponible (clé configurée dans local.properties). */
    val imageSearchConfigured: Boolean = coverSources.any { it is GoogleImageSearchClient }

    val enricher = Enricher(database, coverSources, http, storage.coversDir(), clock)
    private val coverImporter = CoverImporter(app)
    val player = PlayerConnection(app)

    val playerDependencies = object : PlayerDependencies {
        override val database: AppDatabase get() = this@AppContainer.database
        override val settings: SettingsRepository get() = this@AppContainer.settings
        override val progressSync: ProgressSyncRequester get() = progressBridge
        override val onPlaybackStateChanged: () -> Unit = {
            com.julienmiquel.audiotheque.widget.AudiothequeWidgetProvider.requestUpdate(app)
        }
        override val onTrackTransition: (String, Int, Boolean) -> Unit = { bookId, _, finished ->
            appScope.launch {
                withContext(Dispatchers.IO) { engine.onPlaybackTrackChanged(bookId) }
                if (!finished) scheduler.requestSync(SyncTrigger.PLAYBACK)
            }
        }
        override val onMissingTargetTrack: (String, String, Long, Boolean) -> Unit = { bookId, trackId, positionMs, autoPlay ->
            appScope.launch {
                val book = database.bookDao().get(bookId) ?: return@launch
                val tracks = database.trackDao().forBook(bookId)
                val track = tracks.firstOrNull { it.driveId == trackId } ?: return@launch
                val existing = database.progressDao().get(bookId, settings.current().accountKey)
                val needsProgressReset = existing == null || existing.finished || existing.trackId != track.driveId
                prepareMissingTrackDownload(
                    book = book,
                    tracks = tracks,
                    track = track,
                    positionMs = positionMs,
                    autoPlay = autoPlay,
                    updateProgress = needsProgressReset,
                )
            }
        }
    }

    private val _moves = MutableSharedFlow<MoveResult>(extraBufferCapacity = 4)

    /** Gestes de statut récents, pour le bandeau « Annuler » (10 s). */
    val moves: SharedFlow<MoveResult> = _moves.asSharedFlow()

    private val _renames = MutableSharedFlow<com.julienmiquel.audiotheque.library.RenameResult>(extraBufferCapacity = 4)

    /** Renommages Drive récents, pour le bandeau « Annuler » (10 s). */
    val renames: SharedFlow<com.julienmiquel.audiotheque.library.RenameResult> = _renames.asSharedFlow()

    private val _trashes = MutableSharedFlow<TrashResult>(extraBufferCapacity = 4)

    /** Doublons envoyés à la corbeille Drive, pour le bandeau « Annuler » (10 s). */
    val trashes: SharedFlow<TrashResult> = _trashes.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Messages courts affichés en bas de l'écran (erreurs, confirmations). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _selectedBookId = MutableStateFlow<String?>(null)

    /** Livre de l'onglet Lecteur : le dernier demandé, ou celui que lit le service. */
    val selectedBookId: StateFlow<String?> = _selectedBookId.asStateFlow()

    private val _pendingPlaybackBookId = MutableStateFlow<String?>(null)

    /** Livre dont le lancement automatique attend la réception de la piste courante. */
    val pendingPlaybackBookId: StateFlow<String?> = _pendingPlaybackBookId.asStateFlow()

    private var pendingAutoPlayJob: Job? = null

    /** Dernier passage au premier plan traité (progression relue au plus une fois par minute). */
    @Volatile
    private var lastForegroundSyncAt = 0L

    init {
        // Lecture lancée ailleurs (notification, casque, Android Auto) : l'onglet Lecteur suit.
        appScope.launch {
            player.state.map { it.bookId }.distinctUntilChanged().collect { id -> if (id != null) _selectedBookId.value = id }
        }
        // Fin de chaque synchro : pochettes manquantes reprises (Google Books joignable à nouveau ?)
        // et pochettes téléchargées déposées dans Drive ; le widget d'accueil est rafraîchi.
        appScope.launch {
            engine.status.map { it.running }.distinctUntilChanged().drop(1).filter { !it }.collect {
                com.julienmiquel.audiotheque.widget.AudiothequeWidgetProvider.requestUpdate(app)
                enrichMissingCovers()
            }
        }
    }

    fun announceMove(result: MoveResult) {
        if (result.changed) _moves.tryEmit(result)
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    /** Geste de statut : file d'opérations ou statut personnel familial, bandeau « Annuler ». */
    fun applyGesture(bookId: String, gesture: Gesture) {
        appScope.launch {
            val result = gestures.apply(bookId, gesture)
            if (result == null) message("Dossier de statut introuvable : vérifiez les dossiers synchronisés") else announceMove(result)
        }
    }

    /** Renomme le dossier ou le fichier du livre dans Google Drive via la file d'opérations (annulable 10 s). */
    fun renameBookInDrive(bookId: String, newName: String) {
        appScope.launch {
            val result = gestures.rename(bookId, newName)
            when {
                result == null -> message("Renommage impossible")
                result.changed -> _renames.tryEmit(result)
                else -> message("Le dossier porte déjà le nom « ${result.newName} » sur Drive")
            }
        }
    }

    fun undoRename(result: com.julienmiquel.audiotheque.library.RenameResult) {
        appScope.launch { gestures.undoRename(result) }
    }

    /**
     * Supprime un doublon : son dossier part à la corbeille Drive (restaurable 30 jours) via la
     * file d'opérations ; il disparaît aussitôt de la bibliothèque, bandeau « Annuler » 10 s.
     */
    fun trashBook(bookId: String) {
        appScope.launch {
            val result = gestures.trash(bookId, isInUse = BookInUse.isInUse(bookId))
            if (result == null) message("Impossible : ce livre est chargé dans le lecteur. Chargez-en un autre d'abord.") else _trashes.tryEmit(result)
        }
    }

    fun undoTrash(result: TrashResult) {
        appScope.launch {
            if (!gestures.undoTrash(result.bookId)) {
                message("« ${result.title} » est déjà dans la corbeille Drive : restaurable depuis Drive pendant 30 jours.")
            }
        }
    }

    /**
     * Pochette choisie dans les photos du téléphone (sélecteur système, Google Photos) :
     * enregistrée sur le téléphone puis déposée dans le dossier Drive du livre.
     */
    fun setCoverFromPhotos(bookId: String, uri: android.net.Uri) {
        appScope.launch {
            val target = File(storage.coversDir(), "$bookId.jpg")
            val written = coverImporter.import(uri, target)
            if (written == null) {
                message("Image illisible")
                return@launch
            }
            database.bookDao().setCover(bookId, written.path, uploadPending = true)
            com.julienmiquel.audiotheque.widget.AudiothequeWidgetProvider.requestUpdate(app)
            message("Pochette mise à jour : elle sera déposée dans le dossier Drive du livre")
            engine.uploadPendingCovers()
        }
    }

    /** « Déplacer le dossier dans Drive… » : déplace physiquement le dossier pour tout le monde. */
    fun moveBook(bookId: String, pair: SyncPairEntity) {
        appScope.launch {
            val result = gestures.moveDriveFolder(bookId, pair.id)
            if (result == null) message("Déplacement impossible") else announceMove(result)
        }
    }

    fun undoMove(result: MoveResult) {
        appScope.launch { gestures.undo(result) }
    }

    /** Épingle ou retire l'épinglage hors ligne d'un livre sur cet appareil, sans changer son statut. */
    fun togglePinnedOffline(bookId: String) {
        appScope.launch {
            val book = database.bookDao().get(bookId) ?: return@launch
            val next = !book.pinnedOffline
            library.setPinnedOffline(bookId, next, scheduler)
            com.julienmiquel.audiotheque.widget.AudiothequeWidgetProvider.requestUpdate(app)
            message(
                if (next) {
                    "Gardé sur ce téléphone : toutes les pistes seront téléchargées et conservées hors ligne"
                } else {
                    "Épinglage hors ligne retiré"
                },
            )
        }
    }

    /** Bascule vers le profil d'auditeur [profileKey] (`null` = compte Google principal). */
    fun switchProfile(profileKey: String?) {
        appScope.launch {
            val current = settings.current()
            val normalizedKey = profileKey?.takeIf { key -> current.profiles.any { it.key == key } }
            val targetAccountKey = normalizedKey ?: current.accountEmail ?: Accounts.LOCAL
            if (targetAccountKey == current.accountKey && normalizedKey == current.activeProfileKey) return@launch
            cancelPendingPlayback()
            withTimeoutOrNull(PROFILE_SWITCH_PUSH_MS) { engine.pushProgress(null) }
            val lastPlayed = database.progressDao().lastListened(targetAccountKey)?.bookId
            settings.update {
                it.copy(
                    activeProfileKey = normalizedKey,
                    lastPlayedBookId = lastPlayed,
                )
            }
            val updated = settings.current()
            _selectedBookId.value = lastPlayed
            com.julienmiquel.audiotheque.widget.AudiothequeWidgetProvider.requestUpdate(app)
            message("Auditeur actif : ${updated.activeListenerName}")
            if (updated.isConnected) {
                engine.syncProgress()
                scheduler.requestSync(SyncTrigger.MANUAL)
            }
        }
    }

    /** Crée un profil d'auditeur (ou sélectionne celui qui porte déjà la même clé) et active le mode familial. */
    fun addOrSelectProfile(displayName: String, explicitKey: String? = null) {
        val cleanName = displayName.trim()
        val key = Accounts.sanitizeProfileKey(explicitKey ?: cleanName)
        if (cleanName.isEmpty() || key.isEmpty() || key == Accounts.LOCAL) {
            message("Nom de profil invalide")
            return
        }
        appScope.launch {
            val current = settings.current()
            if (current.accountEmail?.equals(key, ignoreCase = true) == true) {
                switchProfile(null)
                return@launch
            }
            val exists = current.profiles.any { it.key == key }
            val newProfiles = if (exists) {
                current.profiles.map { if (it.key == key) ListenerProfile(key, cleanName) else it }
            } else {
                current.profiles + ListenerProfile(key, cleanName)
            }
            settings.update {
                it.copy(
                    profiles = newProfiles,
                    statusSyncMode = StatusSyncMode.PERSONAL_FAMILY,
                )
            }
            switchProfile(key)
        }
    }

    /** Retire un profil secondaire de l'appareil (ses données locales et son fichier Drive sont conservés). */
    fun removeProfile(key: String) {
        appScope.launch {
            val current = settings.current()
            val remaining = current.profiles.filterNot { it.key == key }
            val wasActive = current.activeProfileKey == key
            if (wasActive) {
                cancelPendingPlayback()
                val primaryKey = current.accountEmail ?: Accounts.LOCAL
                val lastPlayed = database.progressDao().lastListened(primaryKey)?.bookId
                settings.update {
                    it.copy(
                        profiles = remaining,
                        activeProfileKey = null,
                        lastPlayedBookId = lastPlayed,
                    )
                }
                val updated = settings.current()
                _selectedBookId.value = lastPlayed
                com.julienmiquel.audiotheque.widget.AudiothequeWidgetProvider.requestUpdate(app)
                message("Profil retiré · Auditeur actif : ${updated.activeListenerName}")
                if (updated.isConnected) {
                    engine.syncProgress()
                    scheduler.requestSync(SyncTrigger.MANUAL)
                }
            } else {
                settings.update { it.copy(profiles = remaining) }
                message("Profil retiré de cet appareil")
            }
        }
    }

    /** Modifie le mode de synchronisation des gestes de statut (mode familial vs dossiers Drive partagés). */
    fun setStatusSyncMode(mode: StatusSyncMode) {
        appScope.launch {
            settings.update { it.copy(statusSyncMode = mode) }
            scheduler.requestSync(SyncTrigger.MANUAL)
        }
    }

    /** Recherche sur Google Drive les profils `.audiotheque-progress-<profil>.json` déjà créés par la famille. */
    suspend fun discoverProfilesFromDrive(): Int {
        val current = settings.current()
        if (!current.isConnected) {
            message("Connectez d'abord votre compte Google Drive")
            return 0
        }
        val remoteKeys = engine.discoverRemoteProfiles()
        val localKeys = database.progressDao().distinctAccounts()
        val emailLower = current.accountEmail?.lowercase()
        val existingKeys = current.profiles.map { it.key }.toSet()
        val additions = (remoteKeys + localKeys)
            .map { it.trim().lowercase() }
            .distinct()
            .filter { key ->
                key.isNotBlank() &&
                    key != Accounts.LOCAL &&
                    key != emailLower &&
                    key !in existingKeys
            }
            .map { key -> ListenerProfile(key = key, displayName = Accounts.displayNameForKey(key)) }
        if (additions.isNotEmpty()) {
            settings.update {
                it.copy(
                    profiles = it.profiles + additions,
                    statusSyncMode = StatusSyncMode.PERSONAL_FAMILY,
                )
            }
            message(
                if (additions.size == 1) {
                    "1 profil détecté : ${additions.first().displayName}"
                } else {
                    "${additions.size} profils détectés : ${additions.joinToString(", ") { p -> p.displayName }}"
                },
            )
        } else {
            message("Aucun nouveau profil détecté sur Google Drive")
        }
        return additions.size
    }

    /** Active ou désactive le mode streaming (fenêtre glissante N-1, N et N+1 sur le téléphone). */
    fun setStreamingMode(enabled: Boolean) {
        appScope.launch {
            settings.update { it.copy(streamingMode = enabled) }
            withContext(Dispatchers.IO) { engine.cleanupNow() }
            scheduler.requestSync(SyncTrigger.MANUAL)
            message(
                if (enabled) {
                    "Mode streaming activé : seules les pistes N-1, N et N+1 sont gardées sur le téléphone"
                } else {
                    "Mode streaming désactivé : les pistes à écouter sont téléchargées et chaque piste écoutée est supprimée"
                },
            )
        }
    }

    /** Annule un lancement automatique en attente de téléchargement. */
    fun cancelPendingPlayback() {
        pendingAutoPlayJob?.cancel()
        pendingAutoPlayJob = null
        _pendingPlaybackBookId.value = null
    }

    private fun scheduleAutoPlayWhenDownloaded(
        bookId: String,
        trackId: String,
        positionMs: Long,
        resumeMode: Boolean,
    ) {
        cancelPendingPlayback()
        _pendingPlaybackBookId.value = bookId
        pendingAutoPlayJob = appScope.launch {
            database.trackDao().observeForBook(bookId)
                .map { tracks ->
                    tracks.firstOrNull {
                        it.driveId == trackId &&
                            it.state == TrackState.PRESENT &&
                            it.localPath?.let(::File)?.exists() == true
                    }
                }
                .filter { it != null }
                .first()
            pendingAutoPlayJob = null
            _pendingPlaybackBookId.value = null
            if (_selectedBookId.value == bookId) {
                if (resumeMode) {
                    player.playBook(bookId, null, null, autoPlay = true)
                } else {
                    player.playBook(bookId, trackId, positionMs, autoPlay = true)
                }
            }
        }
    }

    /** Supprime manuellement tous les fichiers audio locaux d'un livre pour libérer de la place (rien n'est supprimé sur Drive). */
    fun deleteLocalBookFiles(bookId: String) {
        appScope.launch {
            if (_pendingPlaybackBookId.value == bookId) cancelPendingPlayback()
            if (BookInUse.isInUse(bookId) || player.state.value.bookId == bookId) {
                player.stopAndClear()
                BookInUse.set(null)
            }
            val freed = withContext(Dispatchers.IO) { engine.deleteLocalBookFiles(bookId) }
            com.julienmiquel.audiotheque.widget.AudiothequeWidgetProvider.requestUpdate(app)
            message("Fichiers locaux supprimés (${Formats.size(freed)} libérés)")
        }
    }

    /** Supprime manuellement tous les fichiers audio téléchargés sur le téléphone (rien n'est supprimé sur Drive). */
    fun deleteAllLocalFiles() {
        appScope.launch {
            cancelPendingPlayback()
            player.stopAndClear()
            BookInUse.set(null)
            val freed = withContext(Dispatchers.IO) { engine.deleteAllLocalAudioFiles() }
            com.julienmiquel.audiotheque.widget.AudiothequeWidgetProvider.requestUpdate(app)
            message("Espace libéré : ${Formats.size(freed)} supprimés du téléphone")
        }
    }

    /** Lecture (fichiers locaux uniquement) ; sans [autoPlay], le livre est seulement positionné. */
    fun playBook(bookId: String, trackId: String? = null, positionMs: Long? = null, autoPlay: Boolean = true) {
        cancelPendingPlayback()
        _selectedBookId.value = bookId
        player.playBook(bookId, trackId, positionMs, autoPlay)
    }

    /**
     * Reprend ou lance [bookId] : joue immédiatement si la piste cible (locale ou après relecture
     * Drive) est déjà sur le téléphone, sinon programme son téléchargement prioritaire et lance
     * automatiquement la lecture dès réception.
     */
    fun playOrDownloadBook(bookId: String, positionMs: Long? = null, autoPlay: Boolean = true) {
        appScope.launch {
            _selectedBookId.value = bookId
            val book = database.bookDao().get(bookId) ?: return@launch
            val tracks = database.trackDao().forBook(bookId)
            if (tracks.isEmpty()) {
                cancelPendingPlayback()
                scheduler.requestSync(SyncTrigger.PLAYBACK)
                message("Aucune piste inventoriée pour l'instant : synchronisation lancée…")
                return@launch
            }
            if (positionMs == null) {
                val restored = progressBridge.pullProgress(bookId, PlaybackService.PULL_TIMEOUT_MS)
                if (restored != null) {
                    message("« ${book.displayTitle} » : position reprise depuis ${restored.device ?: "un autre appareil"} (${restored.percent.roundToInt()} %)")
                }
            }
            val account = settings.current().accountKey
            val progress = database.progressDao().get(bookId, account)
            val restart = positionMs == null && progress?.finished == true
            val targetTrack = if (restart) {
                tracks.first()
            } else {
                tracks.firstOrNull { it.driveId == progress?.trackId }
                    ?: tracks.getOrNull(progress?.trackIndex ?: 0)
                    ?: tracks.first()
            }
            val matchesSavedTrack = progress != null &&
                (progress.trackId == targetTrack.driveId || (progress.trackId == null && progress.trackIndex == targetTrack.sortIndex))
            val targetPosMs = when {
                positionMs != null -> positionMs.coerceAtLeast(0L)
                restart -> 0L
                matchesSavedTrack -> progress.positionMs.coerceAtLeast(0L)
                else -> 0L
            }
            val localExists = targetTrack.state == TrackState.PRESENT && targetTrack.localPath?.let(::File)?.exists() == true
            if (localExists) {
                if (positionMs == null) {
                    playBook(bookId, null, null, autoPlay)
                } else {
                    playBook(bookId, targetTrack.driveId, targetPosMs, autoPlay)
                }
            } else {
                val needsProgressUpdate = positionMs != null || progress == null || restart || progress.trackId != targetTrack.driveId
                prepareMissingTrackDownload(
                    book = book,
                    tracks = tracks,
                    track = targetTrack,
                    positionMs = targetPosMs,
                    autoPlay = autoPlay,
                    updateProgress = needsProgressUpdate,
                )
            }
        }
    }

    /**
     * Se place sur [trackId] (à [positionMs]) : lance la lecture immédiatement si le fichier est
     * présent sur le téléphone, ou déplace la fenêtre de téléchargement sur cette piste et lance
     * sa synchronisation (avec démarrage automatique si [autoPlay] est vrai).
     */
    fun jumpToTrackAndSync(bookId: String, trackId: String, positionMs: Long = 0L, autoPlay: Boolean = true) {
        appScope.launch {
            val book = database.bookDao().get(bookId) ?: return@launch
            val tracks = database.trackDao().forBook(bookId)
            val track = tracks.firstOrNull { it.driveId == trackId } ?: return@launch
            val localExists = track.state == TrackState.PRESENT && track.localPath?.let(::File)?.exists() == true
            if (localExists) {
                playBook(bookId, track.driveId, positionMs, autoPlay)
                return@launch
            }
            prepareMissingTrackDownload(
                book = book,
                tracks = tracks,
                track = track,
                positionMs = positionMs,
                autoPlay = autoPlay,
                updateProgress = true,
            )
        }
    }

    private suspend fun prepareMissingTrackDownload(
        book: BookEntity,
        tracks: List<TrackEntity>,
        track: TrackEntity,
        positionMs: Long,
        autoPlay: Boolean,
        updateProgress: Boolean,
    ) {
        val bookId = book.driveId
        _selectedBookId.value = bookId
        if (BookInUse.isInUse(bookId) || player.state.value.bookId == bookId) {
            player.stopAndClear()
            BookInUse.set(null)
        }
        val account = settings.current().accountKey
        val existing = database.progressDao().get(bookId, account)
        val pair = database.syncPairDao().forFolder(book.effectiveParentId)
        val isFullCollection = pair?.status == BookStatus.COLLECTION && pair.mode == PairMode.FULL
        if (book.userEffectiveStatus(existing?.personalStatus) != BookStatus.IN_PROGRESS && !book.pinnedOffline && !isFullCollection) {
            gestures.moveTo(bookId, BookStatus.IN_PROGRESS)?.let(::announceMove)
        }
        val pos = positionMs.coerceAtLeast(0L)
        if (updateProgress) {
            val refreshed = database.progressDao().get(bookId, account)
            val durations = ProgressMath.durations(tracks, book.durationMs)
            val percent = ProgressMath.percent(durations, track.sortIndex, pos)
            val now = clock.now()
            database.progressDao().upsert(
                (refreshed ?: ProgressEntity(bookId = bookId, account = account, updatedAt = now)).copy(
                    trackId = track.driveId,
                    trackIndex = track.sortIndex,
                    positionMs = pos,
                    percent = percent,
                    finished = false,
                    updatedAt = now,
                    dirty = true,
                ),
            )
            progressBridge.pushProgress(bookId)
        }
        if (autoPlay) {
            scheduleAutoPlayWhenDownloaded(
                bookId = bookId,
                trackId = track.driveId,
                positionMs = pos,
                resumeMode = !updateProgress,
            )
        } else {
            cancelPendingPlayback()
        }
        withContext(Dispatchers.IO) { engine.onPlaybackTrackChanged(bookId) }
        scheduler.requestSync(SyncTrigger.PLAYBACK)
        com.julienmiquel.audiotheque.widget.AudiothequeWidgetProvider.requestUpdate(app)
        if (autoPlay) {
            message("Téléchargement de « ${track.chapterLabel} » lancé : la lecture démarrera dès réception…")
        } else {
            message("Téléchargement de « ${track.chapterLabel} » lancé…")
        }
    }

    fun requestSync(trigger: SyncTrigger = SyncTrigger.MANUAL) = scheduler.requestSync(trigger)

    /** Métadonnées d'un dossier « à l'ouverture » (archives, méditations, racine). */
    fun refreshFolder(pairId: Long, silent: Boolean = true) {
        appScope.launch {
            val outcome = engine.refreshPair(pairId)
            if (outcome.result == SyncResult.FAILED && (!silent || outcome.needsAuth)) {
                message(outcome.message ?: "Actualisation impossible")
            }
        }
    }

    /**
     * Pochette et résumé en ligne (Google Books, Apple Books, Open Library, Google Images si
     * configuré), à partir du titre et de l'auteur affichés — correspondance stricte tant que le
     * titre n'est pas confirmé. La pochette est téléchargée sur le téléphone puis déposée dans le
     * dossier Drive du livre, pour les autres téléphones.
     */
    fun enrich(bookId: String, manual: Boolean) {
        appScope.launch {
            val outcome = try {
                enricher.enrich(bookId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                EnrichOutcome.UNREACHABLE
            }
            if (outcome == EnrichOutcome.FOUND) engine.uploadPendingCovers()
            if (manual) {
                message(
                    when (outcome) {
                        EnrichOutcome.UNREACHABLE -> "Services de pochettes injoignables pour le moment : nouvel essai automatique plus tard"
                        EnrichOutcome.FOUND -> "Pochette et résumé mis à jour"
                        EnrichOutcome.NOT_FOUND -> "Aucune pochette dans Google Books, Apple Books ni Open Library : essayez « Chercher sur Google Images » ou « Choisir une image »"
                    },
                )
            }
        }
    }

    /** Fiche ouverte : pochette cherchée si elle manque encore (au plus une fois par demi-heure). */
    fun enrichIfNeeded(bookId: String) {
        appScope.launch {
            if (!settings.current().googleBooksEnrichment) return@launch
            val book = library.book(bookId) ?: return@launch
            if (enricher.wants(book)) enrich(bookId, manual = false)
        }
    }

    /** Livres confirmés sans pochette : repris par petits lots, puis pochettes déposées dans Drive. */
    private suspend fun enrichMissingCovers() {
        val s = settings.current()
        if (!s.isConnected || !s.googleBooksEnrichment) return
        val found = try {
            enricher.enrichPending()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }
        if (found > 0) engine.uploadPendingCovers()
    }

    /**
     * App au premier plan : les positions à envoyer partent, celles changées dans Drive sont
     * relues (une requête), au plus une fois par minute ; une position reprise d'un autre
     * téléphone est annoncée. Puis reprise des pochettes manquantes.
     */
    fun onForeground() {
        val now = System.currentTimeMillis()
        if (now - lastForegroundSyncAt < FOREGROUND_SYNC_MIN_INTERVAL_MS) return
        lastForegroundSyncAt = now
        appScope.launch {
            if (!settings.current().isConnected) return@launch
            val adopted = engine.syncProgress()
            adopted.firstOrNull()?.let { r ->
                val title = library.book(r.bookId)?.displayTitle ?: return@let
                message("« $title » : position reprise depuis ${r.device ?: "un autre appareil"} (${r.percent.roundToInt()} %)")
            }
            enrichMissingCovers()
        }
    }

    /**
     * Adresse du compte qui vient d'autoriser Drive, quand l'autorisation ne la donne pas : about.get
     * par le moteur (seul accès à Drive), avec le jeton qui vient d'être accordé.
     */
    suspend fun identify(): DriveAbout? = engine.identifyAccount(restDrive)

    /** Autorisation Google Drive refusée ou expirée : bandeau « Renouveler » jusqu'à la prochaine synchro réussie. */
    val authRequired: Flow<Boolean> = settings.syncState.map { it.authRequired }.distinctUntilChanged()

    /** Bandeau « Renouveler » : autorisation silencieuse si possible, sinon écran de consentement Google. */
    suspend fun renewAuthorization(): AuthRenewal {
        val email = settings.current().accountEmail ?: return AuthRenewal.Failed("Aucun compte connecté")
        return runCatching { auth.authorize(email) }.fold(
            onSuccess = { authorization -> onAuthorization(authorization, email, consentShown = false) },
            onFailure = { AuthRenewal.Failed(authErrorMessage(it)) },
        )
    }

    /** Retour de l'écran de consentement Google ouvert par [renewAuthorization]. */
    suspend fun completeRenewal(accepted: Boolean, data: Intent?): AuthRenewal {
        if (!accepted) {
            val reason = data?.let { runCatching { auth.consentResult(it) }.exceptionOrNull() }
            return AuthRenewal.Failed(consentFailureMessage(reason, firstSignIn = false))
        }
        val email = settings.current().accountEmail ?: return AuthRenewal.Failed("Aucun compte connecté")
        return runCatching { auth.consentResult(data) }.fold(
            onSuccess = { authorization -> onAuthorization(authorization, email, consentShown = true) },
            onFailure = { AuthRenewal.Failed(authErrorMessage(it)) },
        )
    }

    private suspend fun onAuthorization(authorization: DriveAuthorization, email: String, consentShown: Boolean): AuthRenewal =
        when (authorization) {
            is DriveAuthorization.NeedsConsent ->
                if (consentShown) AuthRenewal.Failed("Autorisation incomplète : réessayez.") else AuthRenewal.NeedsConsent(authorization.pendingIntent)
            is DriveAuthorization.Granted -> {
                val granted = authorization.email
                if (granted != null && !granted.equals(email, ignoreCase = true)) {
                    // Jamais le Drive d'un autre compte face à cette bibliothèque.
                    auth.invalidate(authorization.accessToken)
                    AuthRenewal.Failed("Choisissez le compte $email : la bibliothèque synchronisée est la sienne.")
                } else {
                    renewed()
                }
            }
        }

    /** Sans interruption : effacer le drapeau retire le bandeau, et avec lui la portée qui l'appelle. */
    private suspend fun renewed(): AuthRenewal = withContext(NonCancellable) {
        settings.updateSyncState { it.copy(authRequired = false) }
        syncNotifications.dismissAuthRequired()
        // Déplacements en file et progression : envoyés aussitôt.
        scheduler.requestSync(SyncTrigger.MANUAL)
        AuthRenewal.Done
    }

    /** Connexion et déconnexion ne se chevauchent pas. */
    private val accountLock = Mutex()

    /** Compte connecté : la progression locale lui est rattachée. */
    suspend fun onSignedIn(email: String, name: String?) = accountLock.withLock {
        settings.update { it.copy(accountEmail = email, accountName = name, onboardingDone = true) }
        database.progressDao().migrateAccount(Accounts.LOCAL, email)
        database.listeningSessionDao().migrateAccount(Accounts.LOCAL, email)
        database.bookmarkDao().migrateAccount(Accounts.LOCAL, email)
        settings.updateSyncState { it.copy(pageToken = null, lastFullInventoryAt = null, authRequired = false) }
        syncNotifications.dismissAuthRequired()
        scheduler.schedulePeriodic(settings.current())
        scheduler.requestSync(SyncTrigger.LAUNCH)
    }

    /**
     * Déconnexion : plus aucune tâche de fond, fin de la synchro en cours, progression écrite une
     * dernière fois dans les dossiers Drive des livres, puis bibliothèque locale et pistes effacées du
     * téléphone. La progression (propre à chaque compte) et les signets restent.
     */
    suspend fun signOut() = accountLock.withLock {
        cancelPendingPlayback()
        scheduler.cancelAll()
        syncNotifications.dismissAuthRequired()
        player.stopAndClear()
        BookInUse.set(null)
        _selectedBookId.value = null
        withTimeoutOrNull(SIGN_OUT_WAIT_MS) { engine.pushProgressBeforeSignOut() }
        // Plus de compte : aucune synchro ne peut repartir pendant l'effacement.
        settings.update { it.copy(accountEmail = null, accountName = null, activeProfileKey = null, lastPlayedBookId = null) }
        withTimeoutOrNull(SIGN_OUT_WAIT_MS) { engine.awaitIdle() }
        auth.signOut()
        withContext(Dispatchers.IO) {
            val dirs = mutableSetOf<File>()
            database.trackDao().withLocalData().forEach { t ->
                t.localPath?.let { path ->
                    File(path).delete()
                    File("$path.part").delete()
                    File(path).parentFile?.let(dirs::add)
                }
            }
            // Dossiers de livres vidés, jusqu'à la racine Audiobooks (gardée), et pochettes locales.
            dirs.forEach { start ->
                var dir: File? = start
                while (dir != null && dir.name != LocalStorage.ROOT_NAME && dir.isDirectory && dir.list()?.isEmpty() == true) {
                    dir.delete()
                    dir = dir.parentFile
                }
            }
            storage.coversDir().listFiles()?.forEach { it.delete() }
        }
        database.clearLibrary()
        settings.updateSyncState { SyncState() }
        settings.update { it.copy(onboardingDone = false) }
        engine.ensureDefaultPairs()
    }

    companion object {
        /** Attente maximale de la fin de la synchro et de l'écriture de la progression. */
        private const val SIGN_OUT_WAIT_MS = 20_000L

        /** Envoi de la position avant bascule de profil. */
        private const val PROFILE_SWITCH_PUSH_MS = 5_000L

        /** Envoi de la position après une pause : abandonné au-delà (il sera refait à la prochaine occasion). */
        private const val PUSH_TIMEOUT_MS = 30_000L

        /** Progression relue au retour au premier plan, au plus une fois par minute. */
        private const val FOREGROUND_SYNC_MIN_INTERVAL_MS = 60_000L
    }
}

/** Issue du renouvellement de l'autorisation Drive lancé depuis le bandeau. */
sealed interface AuthRenewal {
    data object Done : AuthRenewal

    /** Écran de consentement Google à ouvrir (choix du compte, autorisation drive). */
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthRenewal

    data class Failed(val message: String) : AuthRenewal
}

class AudiothequeApp : Application(), SyncComponentProvider, PlayerDependenciesProvider {

    lateinit var container: AppContainer
        private set

    override val syncComponent: SyncComponent get() = container.syncComponent
    override val playerDependencies: PlayerDependencies get() = container.playerDependencies

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.syncNotifications.createChannels()
        container.appScope.launch {
            container.engine.ensureDefaultPairs()
            // Synchro auto : reprogrammée à chaque changement des réglages concernés.
            container.settings.settings
                .map { listOf(it.autoSync, it.wifiOnly, it.syncIntervalHours, it.requireBatteryNotLow, it.isConnected) }
                .distinctUntilChanged()
                .collect { container.scheduler.schedulePeriodic(container.settings.current()) }
        }
    }
}
