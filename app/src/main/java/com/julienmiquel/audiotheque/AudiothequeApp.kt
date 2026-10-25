package com.julienmiquel.audiotheque

import android.app.Application
import android.os.Build
import com.julienmiquel.audiotheque.auth.GoogleAuthManager
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.model.Accounts
import com.julienmiquel.audiotheque.core.model.SyncResult
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.settings.SyncState
import com.julienmiquel.audiotheque.core.sync.BookInUse
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.library.BookEditor
import com.julienmiquel.audiotheque.library.Enricher
import com.julienmiquel.audiotheque.library.Gesture
import com.julienmiquel.audiotheque.library.GoogleBooksClient
import com.julienmiquel.audiotheque.library.LibraryRepository
import com.julienmiquel.audiotheque.library.MoveResult
import com.julienmiquel.audiotheque.library.StatusGestures
import com.julienmiquel.audiotheque.player.PlayerConnection
import com.julienmiquel.audiotheque.player.PlayerDependencies
import com.julienmiquel.audiotheque.player.PlayerDependenciesProvider
import com.julienmiquel.audiotheque.sync.demo.FakeDriveService
import com.julienmiquel.audiotheque.sync.drive.AccessTokenProvider
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    private val demoAssets = ConcurrentHashMap<String, ByteArray>()

    /** Drive simulé du mode démo (bibliothèque calquée sur la spécification). */
    val demoDrive: FakeDriveService by lazy {
        FakeDriveService(
            content = { asset -> demoAssets.getOrPut(asset) { app.assets.open("demo/$asset").use { it.readBytes() } } },
            stateFile = File(app.filesDir, "demo-drive.json"),
            clock = clock,
        )
    }

    private val restDrive: RestDriveService by lazy { RestDriveService(http, auth) }

    /** Seul accès à Drive : celui du moteur de synchro. */
    val engine = SyncEngine(
        db = database,
        settings = settings,
        driveProvider = DriveProvider {
            val s = settings.current()
            when {
                s.demoMode -> demoDrive
                !s.accountEmail.isNullOrBlank() -> restDrive
                else -> null
            }
        },
        storage = storage,
        network = AndroidNetworkStatus(app),
        inspector = MediaMetadataInspector(),
        clock = clock,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    val scheduler = SyncScheduler(app) { engine }
    val syncNotifications = SyncNotifications(app)
    val syncComponent = SyncComponent(engine, scheduler, syncNotifications)

    val library = LibraryRepository(database, settings)
    val gestures = StatusGestures(database, settings, scheduler, clock)
    val editor = BookEditor(database)
    val enricher = Enricher(database, GoogleBooksClient(http), clock)
    val player = PlayerConnection(app)

    val playerDependencies = object : PlayerDependencies {
        override val database: AppDatabase get() = this@AppContainer.database
        override val settings: SettingsRepository get() = this@AppContainer.settings
    }

    private val _moves = MutableSharedFlow<MoveResult>(extraBufferCapacity = 4)

    /** Gestes de statut récents, pour le bandeau « Annuler » (10 s). */
    val moves: SharedFlow<MoveResult> = _moves.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Messages courts affichés en bas de l'écran (erreurs, confirmations). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _selectedBookId = MutableStateFlow<String?>(null)

    /** Livre de l'onglet Lecteur : le dernier demandé, ou celui que lit le service. */
    val selectedBookId: StateFlow<String?> = _selectedBookId.asStateFlow()

    init {
        // Lecture lancée ailleurs (notification, casque, Android Auto) : l'onglet Lecteur suit.
        appScope.launch {
            player.state.map { it.bookId }.distinctUntilChanged().collect { id -> if (id != null) _selectedBookId.value = id }
        }
    }

    fun announceMove(result: MoveResult) {
        if (result.changed) _moves.tryEmit(result)
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    /** Geste de statut : file d'opérations, statut affiché aussitôt, bandeau « Annuler ». */
    fun applyGesture(bookId: String, gesture: Gesture) {
        appScope.launch {
            val result = gestures.apply(bookId, gesture)
            if (result == null) message("Dossier de statut introuvable : vérifiez les dossiers synchronisés") else announceMove(result)
        }
    }

    /** « Déplacer vers… » : n'importe quel statut, depuis la fiche livre. */
    fun moveBook(bookId: String, pair: SyncPairEntity) {
        appScope.launch {
            val result = gestures.moveTo(bookId, pair.status, pair.id)
            if (result == null) message("Déplacement impossible") else announceMove(result)
        }
    }

    fun undoMove(result: MoveResult) {
        appScope.launch { gestures.undo(result) }
    }

    fun selectBook(bookId: String) {
        _selectedBookId.value = bookId
    }

    /** Lecture (fichiers locaux uniquement) ; sans [autoPlay], le livre est seulement positionné. */
    fun playBook(bookId: String, trackId: String? = null, positionMs: Long? = null, autoPlay: Boolean = true) {
        _selectedBookId.value = bookId
        player.playBook(bookId, trackId, positionMs, autoPlay)
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

    /** Pochette et résumé Google Books, à partir du titre et de l'auteur confirmés. */
    fun enrich(bookId: String, manual: Boolean) {
        appScope.launch {
            val found = runCatching { enricher.enrich(bookId) }
            if (manual) {
                message(
                    when {
                        found.isFailure -> "Google Books est injoignable"
                        found.getOrDefault(false) -> "Pochette et résumé mis à jour"
                        else -> "Aucun résultat dans Google Books"
                    },
                )
            }
        }
    }

    /** Adresse du compte autorisé (about.get), quand l'autorisation ne la fournit pas. */
    suspend fun identify(accessToken: String): DriveAbout? = runCatching {
        val tokenProvider = object : AccessTokenProvider {
            override suspend fun accessToken(): String = accessToken
            override suspend fun invalidate(token: String) = Unit
        }
        RestDriveService(http, tokenProvider, maxAttempts = 1).about()
    }.getOrNull()

    /** Compte connecté : la progression locale lui est rattachée. */
    suspend fun onSignedIn(email: String, name: String?) {
        settings.update { it.copy(accountEmail = email, accountName = name, demoMode = false, onboardingDone = true) }
        database.progressDao().migrateAccount(Accounts.LOCAL, email)
        settings.updateSyncState { it.copy(pageToken = null, lastFullInventoryAt = null) }
        scheduler.schedulePeriodic(settings.current())
        scheduler.requestSync(SyncTrigger.LAUNCH)
    }

    /** Mode démo : Drive simulé, aucune donnée ne quitte le téléphone. */
    suspend fun startDemo() {
        demoDrive.reset()
        settings.update { it.copy(demoMode = true, onboardingDone = true) }
        settings.updateSyncState { it.copy(pageToken = null, lastFullInventoryAt = null) }
        scheduler.schedulePeriodic(settings.current())
        scheduler.requestSync(SyncTrigger.LAUNCH)
    }

    /** Déconnexion ou sortie du mode démo : la bibliothèque locale est vidée, les fichiers aussi. */
    suspend fun signOut() {
        scheduler.cancelAll()
        player.stopAndClear()
        BookInUse.set(null)
        _selectedBookId.value = null
        auth.signOut()
        val tracks = database.trackDao().withLocalData()
        tracks.forEach { t ->
            t.localPath?.let { path ->
                File(path).delete()
                File("$path.part").delete()
            }
        }
        database.clearAllTables()
        settings.update { it.copy(accountEmail = null, accountName = null, demoMode = false, onboardingDone = false, lastPlayedBookId = null) }
        settings.updateSyncState { SyncState() }
        engine.ensureDefaultPairs()
    }
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
