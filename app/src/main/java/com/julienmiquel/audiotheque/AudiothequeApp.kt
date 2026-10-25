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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            return AuthRenewal.Failed(consentFailureMessage(reason))
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
        settings.updateSyncState { it.copy(pageToken = null, lastFullInventoryAt = null, authRequired = false) }
        syncNotifications.dismissAuthRequired()
        scheduler.schedulePeriodic(settings.current())
        scheduler.requestSync(SyncTrigger.LAUNCH)
    }

    /**
     * Déconnexion : plus aucune tâche de fond, fin de la synchro en cours, progression écrite une
     * dernière fois dans l'espace caché du compte, puis bibliothèque locale et pistes effacées du
     * téléphone. La progression (propre à chaque compte) et les signets restent.
     */
    suspend fun signOut() = accountLock.withLock {
        scheduler.cancelAll()
        syncNotifications.dismissAuthRequired()
        player.stopAndClear()
        BookInUse.set(null)
        _selectedBookId.value = null
        withTimeoutOrNull(SIGN_OUT_WAIT_MS) { engine.pushProgressBeforeSignOut() }
        // Plus de compte : aucune synchro ne peut repartir pendant l'effacement.
        settings.update { it.copy(accountEmail = null, accountName = null, lastPlayedBookId = null) }
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
    }
}

/** Issue du renouvellement de l'autorisation Drive lancé depuis le bandeau. */
sealed interface AuthRenewal {
    data object Done : AuthRenewal

    /** Écran de consentement Google à ouvrir (choix du compte, autorisations drive et drive.appdata). */
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
