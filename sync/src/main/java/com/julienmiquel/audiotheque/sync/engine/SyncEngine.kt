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
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
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
)

/** Service Drive du compte courant : réel, ou simulé en mode démo ; null si personne n'est connecté. */
fun interface DriveProvider {
    suspend fun get(): DriveService?
}

/**
 * Moteur de synchro : seul composant qui parle à Drive. Une exécution enchaîne la file de
 * déplacements, la détection des changements, les métadonnées, les téléchargements de
 * _En écoute, le nettoyage selon le statut et la progression dans l'espace caché.
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
    private val executor = OperationExecutor(db, history, clock)
    private val scanner = LibraryScanner(db, settings, history, localFiles, storage, clock)
    private val prober = MetadataProber(db, history, localFiles, storage, clock)
    private val downloader = Downloader(db, settings, storage, history, transfers, inspector, clock)
    private val cleaner = Cleaner(db, settings, localFiles, storage, history, clock, isBookInUse)
    private val progressSync = ProgressSync(db, clock, deviceName)

    private val mutex = Mutex()
    private val rerunRequested = AtomicBoolean(false)
    private val operationsPending = AtomicBoolean(false)

    @Volatile
    private var currentJob: Job? = null

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
    suspend fun lookupFolder(folderId: String): Result<com.julienmiquel.audiotheque.sync.drive.DriveFile> = runCatching {
        val drive = driveProvider.get() ?: throw DriveAuthException("Aucun compte connecté")
        val file = drive.getFile(folderId)
        require(file.isFolder) { "Ce lien ne désigne pas un dossier Drive" }
        require(!file.trashed) { "Ce dossier est dans la corbeille" }
        file
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
        outcome.copy(retryAtMs = db.pendingOperationDao().nextAttemptAt()?.takeIf { db.pendingOperationDao().all().isNotEmpty() })
    }

    /**
     * Métadonnées d'un dossier ouvert dans l'app (archives, méditations, racine). Léger et
     * indépendant d'une synchro en cours.
     */
    suspend fun refreshPair(pairId: Long): SyncOutcome {
        val pair = db.syncPairDao().get(pairId) ?: return SyncOutcome(SyncResult.SUCCESS)
        val drive = driveProvider.get() ?: return SyncOutcome(SyncResult.FAILED, "Aucun compte connecté", needsAuth = true)
        _refreshingPairs.update { it + pairId }
        return try {
            val ctx = RunContext(runId = 0, trigger = SyncTrigger.FOLDER_OPEN, startedAt = clock.now())
            scanner.listPair(drive, pair, db.syncPairDao().all(), ctx)
            prober.run(drive, null, onlyPair = pair)
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
        finish(ctx, result, message ?: ctx.note)
        return SyncOutcome(result, message, needsAuth)
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

        phase(SyncPhase.OPERATIONS)
        executor.flush(drive, ctx)

        phase(SyncPhase.CHANGES)
        scanner.scan(drive, ctx, forceFull) { detail(it) }
        flushIfRequested(drive, ctx)

        phase(SyncPhase.METADATA)
        prober.run(drive, ctx) { detail(it) }
        flushIfRequested(drive, ctx)

        val s = settings.current()
        val planned = downloader.plan()
        if (planned.isNotEmpty()) {
            if (s.wifiOnly && !network.isUnmetered()) {
                ctx.note = "Téléchargements en attente du Wi-Fi (${planned.size} pistes)"
            } else {
                onDownloadsStart()
                phase(SyncPhase.DOWNLOADS)
                downloader.run(drive, ctx) { remaining ->
                    _status.update { it.copy(queuedTracks = remaining) }
                    // Geste fait pendant les téléchargements : déplacement Drive sans attendre.
                    flushIfRequested(drive, ctx)
                }
            }
        }
        executor.flush(drive, ctx)

        phase(SyncPhase.CLEANUP)
        cleaner.run(ctx)

        if (s.accountKey != Accounts.LOCAL) {
            phase(SyncPhase.PROGRESS)
            progressSync.run(drive, s.accountKey)
        }

        phase(SyncPhase.FINISHING)
        runCatching { drive.about() }.getOrNull()?.let { about ->
            settings.updateSyncState {
                it.copy(driveQuotaUsed = about.quotaUsage, driveQuotaLimit = about.quotaLimit, quotaUpdatedAt = clock.now())
            }
            if (!s.demoMode && about.email != null && s.accountEmail.isNullOrBlank()) {
                settings.update { it.copy(accountEmail = about.email, accountName = about.displayName) }
            }
        }
    }
}
