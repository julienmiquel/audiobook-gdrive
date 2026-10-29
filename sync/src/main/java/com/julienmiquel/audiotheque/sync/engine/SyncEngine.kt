package com.julienmiquel.audiotheque.sync.engine

import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.SyncRunEntity
import com.julienmiquel.audiotheque.core.model.Accounts
import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.SyncResult
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.BookInUse
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.core.sync.RemoteProgress
import com.julienmiquel.audiotheque.sync.drive.DriveAbout
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import com.julienmiquel.audiotheque.sync.drive.DriveFile
import com.julienmiquel.audiotheque.sync.drive.DriveService
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SyncPhase(val label: String) {
    IDLE("En attente"),
    OPERATIONS("Déplacements Drive"),
    CHANGES("Modifications Drive"),
    METADATA("Métadonnées"),
    DOWNLOADS("Téléchargements"),
    CLEANUP("Nettoyage"),
    PROGRESS("Progression"),
    FINISHING("Finalisation"),
}

/** État en direct du moteur (écran Synchro › Suivi). */
data class EngineStatus(
    val running: Boolean = false,
    val phase: SyncPhase = SyncPhase.IDLE,
    val detail: String? = null,
    val runId: Long? = null,
    val startedAt: Long? = null,
    val trigger: SyncTrigger? = null,
    val queuedTracks: Int = 0,
)

data class SyncOutcome(
    val result: SyncResult,
    val message: String? = null,
    val needsAuth: Boolean = false,
    /** Prochain essai de la file d'opérations, s'il reste des déplacements en attente. */
    val retryAtMs: Long? = null,
    /** Téléchargements ou métadonnées reportés : une synchro est à faire au retour du Wi-Fi. */
    val waitingForWifi: Boolean = false,
    /** Prochaine échéance de nettoyage (piste à supprimer du téléphone), ou null. */
    val nextCleanupAt: Long? = null,
)

/** Google Drive du compte connecté (API REST v3) ; null si personne n'est connecté. */
fun interface DriveProvider {
    suspend fun get(): DriveService?
}

/**
 * Moteur de synchro : seul composant qui parle à Drive. Une exécution enchaîne la file de
 * déplacements, la détection des changements, les pistes de _En écoute (listées puis
 * téléchargées en priorité), les métadonnées du reste de la bibliothèque, le dépôt des pochettes
 * téléchargées, le nettoyage selon le statut et la progression dans le dossier Drive de chaque
 * livre. Le lecteur dispose en plus d'échanges légers de progression ([pushProgress],
 * [pullProgress]) pour continuer un livre d'un téléphone à l'autre sans attendre une synchro.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val driveProvider: DriveProvider,
    private val storage: LocalStorage,
    private val network: NetworkStatus,
    inspector: AudioFileInspector,
    private val clock: Clock = Clock.SYSTEM,
    deviceName: String = "Android",
    isBookInUse: (String) -> Boolean = { BookInUse.isInUse(it) },
) {
    val transfers = TransferTracker()
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _refreshingPairs = MutableStateFlow<Set<Long>>(emptySet())

    /** Dossiers « à l'ouverture » en cours d'inventaire. */
    val refreshingPairs: StateFlow<Set<Long>> = _refreshingPairs.asStateFlow()

    private val history = HistoryLogger(db, clock)
    private val localFiles = LocalFiles(db, storage)
    private val executor = OperationExecutor(db, history, clock, localFiles)
    private val scanner = LibraryScanner(db, settings, history, localFiles, storage, clock, isBookInUse)
    private val prober = MetadataProber(db, settings, history, localFiles, storage, clock)
    private val downloader = Downloader(db, settings, storage, history, transfers, inspector, clock)
    private val cleaner = Cleaner(db, settings, localFiles, storage, history, clock, isBookInUse)
    private val progressSync = ProgressSync(db, history, clock, deviceName)
    private val metadataSync = MetadataSync(db, history, clock, deviceName)
    private val covers = CoverUploader(db, history)

    private val mutex = Mutex()
    private val rerunRequested = AtomicBoolean(false)
    private val operationsPending = AtomicBoolean(false)

    @Volatile
    private var currentJob: Job? = null

    /** Début de la dernière synchro : une demande antérieure est déjà satisfaite. */
    @Volatile
    var lastRunStartedAt: Long = 0L
        private set

    val isRunning: Boolean get() = _status.value.running

    /** Nouvelle demande pendant une synchro : file rejouée au plus tôt, puis nouveau passage. */
    fun requestRerun() {
        operationsPending.set(true)
        rerunRequested.set(true)
    }

    /** Bouton « Annuler » : les fichiers « .part » restent pour reprendre plus tard. */
    fun cancelCurrent() {
        currentJob?.cancel(CancellationException("Synchro annulée"))
    }

    suspend fun ensureDefaultPairs() {
        val pairsDao = db.syncPairDao()
        if (pairsDao.all().isEmpty()) pairsDao.insertAll(DriveLibraryDefaults.defaultPairs())
    }

    /** Vérifie un dossier Drive avant d'en faire une paire synchronisée (nom réel, type dossier). */
    suspend fun lookupFolder(folderId: String): Result<DriveFile> = try {
        val drive = driveProvider.get() ?: throw DriveAuthException("Aucun compte connecté")
        val file = drive.getFile(folderId)
        require(file.isFolder) { "Ce lien ne désigne pas un dossier Drive" }
        require(!file.trashed) { "Ce dossier est dans la corbeille" }
        Result.success(file)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * about.get au moment de la connexion : adresse et nom du compte qui vient d'accorder l'accès
     * à Drive, quand l'autorisation ne les donne pas. Seul appel Drive fait hors d'une synchro.
     */
    suspend fun identifyAccount(drive: DriveService): DriveAbout? = try {
        drive.about()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Retire une paire : ses livres quittent la bibliothèque locale ; rien n'est supprimé dans Drive. */
    suspend fun removePair(pairId: Long) {
        val pair = db.syncPairDao().get(pairId) ?: return
        val others = db.syncPairDao().all().filter { it.id != pairId }.map { it.driveFolderId }.toSet()
        for (book in db.bookDao().byParent(pair.driveFolderId)) {
            if (book.pendingParentId == null || book.pendingParentId !in others) scanner.removeBook(book, "paire « ${pair.name} » retirée", null)
        }
        db.syncPairDao().delete(pairId)
    }

    suspend fun sync(
        trigger: SyncTrigger,
        forceFull: Boolean = false,
        onDownloadsStart: suspend () -> Unit = {},
    ): SyncOutcome = mutex.withLock {
        lastRunStartedAt = clock.now()
        var currentTrigger = trigger
        var outcome: SyncOutcome
        var passes = 0
        do {
            rerunRequested.set(false)
            outcome = runGuarded(currentTrigger) { ctx, drive -> fullRun(ctx, drive, forceFull && passes == 0, onDownloadsStart) }
            currentTrigger = SyncTrigger.STATUS_CHANGE
            passes++
        } while (rerunRequested.get() && passes < 5 &&
            outcome.result != SyncResult.CANCELLED && outcome.result != SyncResult.FAILED
        )
        // Nouvel essai des déplacements restés en file, espacé (au moins 30 s). Aucun si
        // l'autorisation Google est à renouveler ou si l'utilisateur vient d'annuler.
        val retryAt = if (outcome.needsAuth || outcome.result == SyncResult.CANCELLED) {
            null
        } else {
            db.pendingOperationDao().nextAttemptAt()?.coerceAtLeast(clock.now() + MIN_RETRY_DELAY_MS)
        }
        outcome.copy(retryAtMs = retryAt, nextCleanupAt = cleaner.nextDeadline())
    }

    /**
     * Nettoyage à l'échéance d'un délai (7 jours, 24 h…), sans réseau. Sans effet si une synchro
     * tourne : elle nettoie elle-même. Retourne l'échéance suivante.
     */
    suspend fun cleanupNow(): Long? {
        if (!mutex.tryLock()) return null
        return try {
            cleaner.run(null)
            cleaner.nextDeadline()
        } finally {
            mutex.unlock()
        }
    }

    /** Déconnexion : attend la fin de la synchro en cours (annulée au préalable). */
    suspend fun awaitIdle() {
        mutex.withLock { }
    }

    /**
     * Déconnexion : dernière écriture de la progression et des corrections de métadonnées dans les
     * dossiers Drive des livres, une fois la synchro en cours terminée.
     */
    suspend fun pushProgressBeforeSignOut(): Boolean = mutex.withLock {
        val s = settings.current()
        if (s.accountKey == Accounts.LOCAL) return@withLock true
        val drive = driveProvider.get() ?: return@withLock false
        try {
            progressSync.push(drive, s.accountKey, bookId = null)
            metadataSync.push(drive, s.accountKey, bookId = null)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ progression & métadonnées partagées

    /**
     * Lecteur, signet ou geste familial : la progression du livre ([bookId], ou tout ce qui est à
     * envoyer) part dans son dossier Drive sans attendre une synchro complète.
     */
    suspend fun pushProgress(bookId: String?): Boolean = lightweight { drive, account ->
        progressSync.push(drive, account, bookId)
        true
    } ?: false

    /**
     * Corrections de métadonnées (titre, auteur, voix, masquage) partagées aussitôt avec la famille
     * dans « .audiotheque-meta.json ».
     */
    suspend fun pushMetadata(bookId: String?): Boolean = lightweight { drive, account ->
        metadataSync.push(drive, account, bookId)
        true
    } ?: false

    /** Découvre les profils d'auditeurs déjà présents dans `/AudioBook` sur Drive. */
    suspend fun discoverRemoteProfiles(): List<String> = lightweight { drive, _ ->
        progressSync.discoverProfiles(drive)
    }.orEmpty()

    /**
     * Lecteur, juste avant une lecture : relit la position du livre dans Drive. Retourne la
     * position adoptée si un autre téléphone en a enregistré une plus récente.
     */
    suspend fun pullProgress(bookId: String): RemoteProgress? = lightweight { drive, account ->
        progressSync.pull(drive, account, bookId)
    }

    /**
     * Retour de l'app au premier plan (ou changement de profil) : positions, statuts personnels,
     * signets et corrections partagées synchronisés en une passe légère.
     */
    suspend fun syncProgress(): List<RemoteProgress> = lightweight { drive, account ->
        metadataSync.run(drive, account)
        progressSync.run(drive, account)
    }.orEmpty()

    /** Pochettes téléchargées en ligne, déposées dans le dossier Drive de leur livre (pas sur réseau mesuré en « Wi-Fi uniquement »). */
    suspend fun uploadPendingCovers(): Int = lightweight { drive, _ ->
        if (contentAllowed()) covers.run(drive, null) else 0
    } ?: 0

    /** Appel Drive léger : compte connecté et réseau présents, erreurs avalées (la synchro complète les signalera). */
    private suspend fun <T> lightweight(block: suspend (DriveService, String) -> T): T? {
        val s = settings.current()
        if (!s.isConnected || s.accountKey == Accounts.LOCAL || !network.isConnected()) return null
        val drive = driveProvider.get() ?: return null
        return try {
            block(drive, s.accountKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthException) {
            null
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Métadonnées d'un dossier ouvert dans l'app (archives, méditations, racine). Léger et
     * indépendant d'une synchro en cours : les pistes sont fusionnées avec la base dans une
     * transaction, sans écraser ce qu'une synchro écrit au même moment.
     */
    suspend fun refreshPair(pairId: Long): SyncOutcome {
        val pair = db.syncPairDao().get(pairId) ?: return SyncOutcome(SyncResult.SUCCESS)
        val drive = driveProvider.get() ?: return SyncOutcome(SyncResult.FAILED, "Aucun compte connecté", needsAuth = true)
        _refreshingPairs.update { it + pairId }
        return try {
            val ctx = RunContext(runId = 0, trigger = SyncTrigger.FOLDER_OPEN, startedAt = clock.now())
            scanner.listPair(drive, pair, db.syncPairDao().all(), ctx)
            prober.run(drive, null, onlyPair = pair, allowContent = contentAllowed())
            SyncOutcome(SyncResult.SUCCESS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthException) {
            SyncOutcome(SyncResult.FAILED, e.message, needsAuth = true)
        } catch (e: Exception) {
            SyncOutcome(SyncResult.FAILED, e.message)
        } finally {
            _refreshingPairs.update { it - pairId }
        }
    }

    /** Contenu des fichiers (pistes, échantillons, pochettes) : pas sur réseau mesuré en mode « Wi-Fi uniquement ». */
    private suspend fun contentAllowed(): Boolean = !settings.current().wifiOnly || network.isUnmetered()

    private suspend fun runGuarded(trigger: SyncTrigger, block: suspend (RunContext, DriveService) -> Unit): SyncOutcome {
        val s = settings.current()
        if (!s.isConnected) return SyncOutcome(SyncResult.FAILED, "Aucun compte connecté", needsAuth = true)
        val drive = driveProvider.get() ?: return SyncOutcome(SyncResult.FAILED, "Aucun compte connecté", needsAuth = true)
        ensureDefaultPairs()
        val runs = db.syncRunDao()
        runs.closeDangling(clock.now())
        val startedAt = clock.now()
        val runId = runs.insert(SyncRunEntity(trigger = trigger, startedAt = startedAt))
        val ctx = RunContext(runId, trigger, startedAt)
        _status.value = EngineStatus(running = true, phase = SyncPhase.OPERATIONS, runId = runId, startedAt = startedAt, trigger = trigger)

        var result: SyncResult
        var message: String? = null
        var needsAuth = false
        try {
            var userCancelled = false
            coroutineScope {
                val job = launch { block(ctx, drive) }
                currentJob = job
                job.join()
                userCancelled = job.isCancelled
            }
            result = when {
                userCancelled -> SyncResult.CANCELLED
                ctx.errors.get() > 0 -> SyncResult.PARTIAL
                else -> SyncResult.SUCCESS
            }
            if (userCancelled) message = "Synchro annulée"
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finish(ctx, SyncResult.CANCELLED, "Synchro interrompue") }
            throw e
        } catch (e: DriveAuthException) {
            result = SyncResult.FAILED
            message = e.message
            needsAuth = true
        } catch (e: IOException) {
            result = SyncResult.FAILED
            message = "Réseau indisponible : ${e.message ?: e.javaClass.simpleName}"
        } catch (e: Exception) {
            result = SyncResult.FAILED
            message = e.message ?: e.javaClass.simpleName
        } finally {
            currentJob = null
        }
        if (result == SyncResult.FAILED) {
            history.log(SyncEventType.ERROR, path = "Synchro", result = EventResult.ERROR, message = message, runId = runId)
            ctx.errors.incrementAndGet()
        }
        // Autorisation Google à renouveler : affichée dans l'app jusqu'à une synchro réussie.
        val authRequired = when {
            needsAuth -> true
            result == SyncResult.SUCCESS || result == SyncResult.PARTIAL -> false
            else -> null
        }
        if (authRequired != null && settings.currentSyncState().authRequired != authRequired) {
            settings.updateSyncState { it.copy(authRequired = authRequired) }
        }
        finish(ctx, result, message ?: ctx.note)
        return SyncOutcome(result, message, needsAuth, waitingForWifi = ctx.waitingForWifi && result != SyncResult.CANCELLED)
    }

    private suspend fun finish(ctx: RunContext, result: SyncResult, message: String?) {
        db.trackDao().resetInterruptedDownloads()
        transfers.clear()
        db.syncRunDao().update(
            SyncRunEntity(
                id = ctx.runId,
                trigger = ctx.trigger,
                startedAt = ctx.startedAt,
                endedAt = clock.now(),
                result = result,
                filesReceived = ctx.filesReceived.get(),
                bytesReceived = ctx.bytesReceived.get(),
                filesDeleted = ctx.filesDeleted.get(),
                bytesDeleted = ctx.bytesDeleted.get(),
                moves = ctx.moves.get(),
                statusUpdates = ctx.statusUpdates.get(),
                errors = ctx.errors.get(),
                fullInventory = ctx.fullInventory,
                message = message,
            ),
        )
        _status.value = EngineStatus()
    }

    private fun phase(phase: SyncPhase, detail: String? = null) {
        _status.update { it.copy(phase = phase, detail = detail) }
    }

    private fun detail(text: String) {
        _status.update { it.copy(detail = text) }
    }

    private suspend fun flushIfRequested(drive: DriveService, ctx: RunContext) {
        if (operationsPending.getAndSet(false)) executor.flush(drive, ctx)
    }

    private suspend fun fullRun(ctx: RunContext, drive: DriveService, forceFull: Boolean, onDownloadsStart: suspend () -> Unit) {
        db.trackDao().resetInterruptedDownloads()
        history.purge()
        operationsPending.set(false)
        val s = settings.current()
        val wifiRuleOk = { !s.wifiOnly || network.isUnmetered() }

        phase(SyncPhase.OPERATIONS)
        executor.flush(drive, ctx)

        phase(SyncPhase.CHANGES)
        scanner.scan(drive, ctx, forceFull) { detail(it) }
        flushIfRequested(drive, ctx)

        // Métadonnées partagées (.audiotheque-meta.json) et progression / statut personnel / signets
        // (.audiotheque-progress-<profil>.json) lus avant l'inventaire des téléchargements : un livre
        // commencé sur un autre appareil ou en mode familial est immédiatement téléchargé à partir de
        // la bonne piste.
        if (s.accountKey != Accounts.LOCAL) {
            phase(SyncPhase.PROGRESS)
            metadataSync.run(drive, s.accountKey, ctx.runId)
            progressSync.run(drive, s.accountKey, ctx.runId)
        }

        // _En écoute et livres épinglés d'abord : leurs pistes sont listées puis téléchargées avant
        // les métadonnées du reste de la bibliothèque, pour écouter au plus vite.
        phase(SyncPhase.METADATA)
        prober.run(drive, ctx, scope = ProbeScope.DOWNLOADS, allowContent = wifiRuleOk()) { detail(it) }
        flushIfRequested(drive, ctx)

        var networkLost = false
        val planned = downloader.plan()
        val plannedZips = downloader.zipPlan()
        val totalPlanned = planned.size + plannedZips.size
        if (totalPlanned > 0) {
            if (!wifiRuleOk()) {
                val label = when {
                    planned.isNotEmpty() && plannedZips.isNotEmpty() -> "${planned.size} pistes, ${plannedZips.size} archives"
                    plannedZips.isNotEmpty() -> "${plannedZips.size} archive(s) .zip"
                    else -> "${planned.size} pistes"
                }
                ctx.note = "Téléchargements en attente du Wi-Fi ($label)"
                ctx.waitingForWifi = true
            } else {
                onDownloadsStart()
                phase(SyncPhase.DOWNLOADS)
                val stop = downloader.run(drive, ctx, canContinue = wifiRuleOk) { remaining ->
                    _status.update { it.copy(queuedTracks = remaining) }
                    // Geste fait pendant les téléchargements : déplacement Drive sans attendre.
                    flushIfRequested(drive, ctx)
                }
                when (stop) {
                    DownloadStop.WAITING_FOR_WIFI -> {
                        ctx.note = "Téléchargements en attente du Wi-Fi"
                        ctx.waitingForWifi = true
                    }
                    DownloadStop.NETWORK_LOST -> {
                        ctx.note = "Réseau perdu : téléchargements à reprendre"
                        networkLost = true
                    }
                    DownloadStop.DONE -> Unit
                }
            }
        }

        if (!networkLost) {
            phase(SyncPhase.METADATA)
            if (prober.run(drive, ctx, scope = ProbeScope.LIBRARY, allowContent = wifiRuleOk()) { detail(it) }) {
                ctx.waitingForWifi = true
            }
            // Pochettes téléchargées en ligne : déposées dans le dossier Drive du livre.
            if (wifiRuleOk()) covers.run(drive, ctx)
            executor.flush(drive, ctx)
        }

        phase(SyncPhase.CLEANUP)
        cleaner.run(ctx)
        if (networkLost) return

        if (s.accountKey != Accounts.LOCAL) {
            phase(SyncPhase.PROGRESS)
            metadataSync.push(drive, s.accountKey, null)
            progressSync.push(drive, s.accountKey, null)
        }

        phase(SyncPhase.FINISHING)
        try {
            val about = drive.about()
            settings.updateSyncState {
                it.copy(driveQuotaUsed = about.quotaUsage, driveQuotaLimit = about.quotaLimit, quotaUpdatedAt = clock.now())
            }
            if (about.email != null && s.accountEmail.isNullOrBlank()) {
                settings.update { it.copy(accountEmail = about.email, accountName = about.displayName) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Quota indisponible : sans conséquence, il sera relu à la prochaine synchro.
        }
    }

    companion object {
        /** Délai minimal avant un nouvel essai automatique de la file d'opérations. */
        const val MIN_RETRY_DELAY_MS = 30_000L
    }
}
