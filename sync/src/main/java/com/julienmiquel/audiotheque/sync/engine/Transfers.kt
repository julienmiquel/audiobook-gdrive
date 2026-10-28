package com.julienmiquel.audiotheque.sync.engine

import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.CleanupPolicy
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.PairMode
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.effectiveStatusChangedAt
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import com.julienmiquel.audiotheque.sync.drive.DriveHttpException
import com.julienmiquel.audiotheque.sync.drive.DriveNotFoundException
import com.julienmiquel.audiotheque.sync.drive.DriveRangeNotSatisfiableException
import com.julienmiquel.audiotheque.sync.drive.DriveService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Contrôle d'intégrité échoué (taille ou checksum Drive). */
class IntegrityException(message: String) : IOException(message)

/** Wi-Fi perdu pendant un transfert en mode « Wi-Fi uniquement » : le « .part » reste pour reprendre. */
class DownloadDeferredException : IOException("Téléchargement reporté au retour du Wi-Fi")

/** Écriture impossible sur le téléphone (stockage plein, accès retiré) : ce n'est pas le réseau. */
class LocalWriteException(file: File, cause: IOException) :
    IOException("Écriture impossible sur le téléphone (${file.name}) : ${cause.message ?: cause.javaClass.simpleName}", cause)

/** Fin d'une série de téléchargements. */
enum class DownloadStop {
    /** Plus rien à télécharger (ou seulement des pistes en échec). */
    DONE,

    /** Réseau mesuré en mode « Wi-Fi uniquement » : reprise au retour du Wi-Fi. */
    WAITING_FOR_WIFI,

    /** Réseau perdu : reprise à la prochaine synchro. */
    NETWORK_LOST,
}

/**
 * Téléchargement des pistes : 3 en parallèle, avec reprise après coupure (fichier « .part » et
 * en-tête Range). L'ordre part de la position d'écoute, pour écouter avant la fin du transfert.
 * Intégrité : contrôle de la taille et du checksum Drive, puis renommage. Le transfert d'un livre
 * ne démarre pas si l'espace libre est inférieur à sa taille plus 500 Mo.
 */
class Downloader(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val storage: LocalStorage,
    private val history: HistoryLogger,
    private val transfers: TransferTracker,
    private val inspector: AudioFileInspector,
    private val clock: Clock,
    private val parallelism: Int = 3,
) {
    private val books = db.bookDao()
    private val tracks = db.trackDao()

    data class Task(val book: BookEntity, val track: TrackEntity)

    /** Pistes à télécharger, dans l'ordre de priorité. */
    suspend fun plan(): List<Task> {
        val s = settings.current()
        val pairs = db.syncPairDao().all()
        val inProgressPair = pairs.firstOrNull { it.status == BookStatus.IN_PROGRESS }
        val candidates = mutableListOf<BookEntity>()
        if (inProgressPair == null || (inProgressPair.enabled && inProgressPair.mode == PairMode.FULL)) {
            candidates += books.byEffectiveStatus(BookStatus.IN_PROGRESS)
        }
        // Collections ajoutées en réception complète (paires sans limite).
        pairs.filter { it.enabled && it.mode == PairMode.FULL && it.status == BookStatus.COLLECTION }.forEach { pair ->
            candidates += books.byParent(pair.driveFolderId)
        }
        val progressDao = db.progressDao()
        val withProgress = candidates.filter { it.kind != BookKind.ZIP }.distinctBy { it.driveId }
            .map { it to progressDao.get(it.driveId, s.accountKey) }
            .sortedWith(
                compareByDescending<Pair<BookEntity, ProgressEntity?>> { it.second?.lastListenedAt ?: 0L }
                    .thenByDescending { it.first.effectiveStatusChangedAt },
            )
        val result = mutableListOf<Task>()
        for ((book, progress) in withProgress) {
            val bookTracks = tracks.forBook(book.driveId)
            val current = progress?.trackIndex?.coerceIn(0, (bookTracks.size - 1).coerceAtLeast(0)) ?: 0
            bookTracks.filter { it.state != TrackState.PRESENT }
                .sortedBy { if (it.sortIndex >= current) it.sortIndex - current else bookTracks.size + current - it.sortIndex }
                .forEach { result += Task(book, it) }
        }
        if (s.previewNew) {
            for (book in books.byEffectiveStatus(BookStatus.NEW).filter { it.kind != BookKind.ZIP }) {
                tracks.forBook(book.driveId).firstOrNull()?.takeIf { it.state != TrackState.PRESENT }?.let { result += Task(book, it) }
            }
        }
        return result
    }

    /**
     * Télécharge le plan, 3 pistes à la fois. [canContinue] est consulté avant chaque piste et
     * pendant les transferts : faux en mode « Wi-Fi uniquement » quand le réseau devient mesuré.
     */
    suspend fun run(
        drive: DriveService,
        ctx: RunContext,
        canContinue: () -> Boolean = { true },
        onSlot: suspend (remaining: Int) -> Unit = {},
    ): DownloadStop = coroutineScope {
        val inFlight = ConcurrentHashMap.newKeySet<String>()
        val failures = ConcurrentHashMap<String, Int>()
        val blockedBooks = ConcurrentHashMap.newKeySet<String>()
        val checkedBooks = ConcurrentHashMap.newKeySet<String>()
        val stop = AtomicReference<DownloadStop?>(null)
        val lock = Mutex()

        suspend fun next(): Task? = lock.withLock {
            if (stop.get() != null) return@withLock null
            if (!canContinue()) {
                stop.compareAndSet(null, DownloadStop.WAITING_FOR_WIFI)
                return@withLock null
            }
            val planned = plan().filter { it.book.driveId !in blockedBooks && (failures[it.track.driveId] ?: 0) < MAX_ATTEMPTS }
            onSlot(planned.size)
            for (task in planned) {
                if (task.track.driveId in inFlight) continue
                if (task.book.driveId !in checkedBooks) {
                    if (!hasRoomFor(task.book, ctx)) {
                        blockedBooks += task.book.driveId
                        continue
                    }
                    checkedBooks += task.book.driveId
                }
                inFlight += task.track.driveId
                return@withLock task
            }
            null
        }

        repeat(parallelism) {
            launch {
                while (true) {
                    val task = next() ?: break
                    try {
                        download(drive, task, ctx, canContinue)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DriveAuthException) {
                        throw e
                    } catch (e: DownloadDeferredException) {
                        stop.compareAndSet(null, DownloadStop.WAITING_FOR_WIFI)
                    } catch (e: Exception) {
                        val transientNetwork = e is IOException && e !is DriveHttpException &&
                            e !is IntegrityException && e !is LocalWriteException
                        if (transientNetwork) {
                            // Réseau perdu : inutile d'essayer les pistes suivantes, un seul message.
                            if (stop.compareAndSet(null, DownloadStop.NETWORK_LOST)) {
                                history.log(
                                    type = SyncEventType.ERROR,
                                    path = task.book.name,
                                    book = task.book,
                                    result = EventResult.ERROR,
                                    message = "Réseau perdu : téléchargements interrompus, reprise à la prochaine synchro (${e.message ?: e.javaClass.simpleName}).",
                                    runId = ctx.runId,
                                )
                                ctx.errors.incrementAndGet()
                            }
                            continue
                        }
                        val attempts = when (e) {
                            is DriveNotFoundException -> {
                                // Piste supprimée ou remplacée dans Drive : la liste sera relue.
                                books.invalidateTracks(listOf(task.book.driveId))
                                MAX_ATTEMPTS.also { failures[task.track.driveId] = it }
                            }
                            // Stockage plein ou accès retiré : réessayer tout de suite ne servirait à rien.
                            is LocalWriteException -> MAX_ATTEMPTS.also { failures[task.track.driveId] = it }
                            else -> failures.merge(task.track.driveId, 1, Int::plus) ?: 1
                        }
                        if (attempts >= MAX_ATTEMPTS) {
                            history.log(
                                type = SyncEventType.ERROR,
                                path = "${task.book.name}/${task.track.relativePath}",
                                book = task.book,
                                sizeBytes = task.track.sizeBytes,
                                result = EventResult.ERROR,
                                message = "Téléchargement interrompu : ${e.message ?: e.javaClass.simpleName}",
                                runId = ctx.runId,
                            )
                            ctx.errors.incrementAndGet()
                        }
                    } finally {
                        inFlight -= task.track.driveId
                        transfers.remove(task.track.driveId)
                    }
                }
            }
        }
        stop.get() ?: DownloadStop.DONE
    }

    /** Espace libre ≥ reste à télécharger du livre + 500 Mo. */
    private suspend fun hasRoomFor(book: BookEntity, ctx: RunContext): Boolean {
        val remaining = tracks.forBook(book.driveId).filter { it.state != TrackState.PRESENT }
            .sumOf { (it.sizeBytes - it.downloadedBytes).coerceAtLeast(0) }
        val free = storage.freeBytes(storage.root())
        if (free >= remaining + SPACE_MARGIN_BYTES) return true
        history.log(
            type = SyncEventType.ERROR,
            path = book.name,
            book = book,
            sizeBytes = remaining,
            result = EventResult.ERROR,
            message = "Espace insuffisant : il faut ${Formats.size(remaining)} + 500 Mo de marge, ${Formats.size(free)} libres.",
            runId = ctx.runId,
        )
        ctx.errors.incrementAndGet()
        return false
    }

    /** Verrou : deux livres de même nom téléchargés en même temps n'obtiennent pas le même dossier. */
    private val dirLock = Mutex()

    private suspend fun bookDir(book: BookEntity): File = dirLock.withLock {
        val fresh = books.get(book.driveId) ?: book
        val name = fresh.localDirName ?: run {
            val base = LocalStorage.sanitize(fresh.name)
            val unique = if (books.countLocalDirName(base, fresh.driveId) > 0) "$base (${fresh.driveId.takeLast(6)})" else base
            books.setLocalDirName(fresh.driveId, unique)
            unique
        }
        File(storage.root(), name)
    }

    suspend fun download(drive: DriveService, task: Task, ctx: RunContext, canContinue: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        val track = tracks.get(task.track.driveId) ?: return@withContext
        if (track.state == TrackState.PRESENT) return@withContext
        val book = books.get(track.bookId) ?: return@withContext
        val dir = bookDir(book)
        val target = File(dir, track.relativePath.split('/').joinToString("/") { LocalStorage.sanitize(it) })
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")

        // Fichier déjà complet (synchro précédente, ancienne app) : vérifié puis adopté.
        if (target.exists() && target.length() == track.sizeBytes && (track.md5 == null || target.md5Hex().equals(track.md5, true))) {
            markPresent(book, track, target, ctx, received = false)
            return@withContext
        }

        var start = if (part.exists()) part.length() else 0L
        if (track.sizeBytes in 1 until start) {
            part.delete()
            start = 0
        }
        tracks.setLocalState(track.driveId, TrackState.DOWNLOADING, target.path, start)
        val title = book.displayTitle
        if (track.sizeBytes <= 0 || start < track.sizeBytes) {
            try {
                drive.openContent(track.driveId, start).use { content ->
                    if (start > 0 && !content.partial) {
                        // Range ignoré : on repart de zéro.
                        part.delete()
                        start = 0
                    }
                    val output = try {
                        FileOutputStream(part, start > 0)
                    } catch (e: IOException) {
                        throw LocalWriteException(part, e)
                    }
                    output.use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = start
                        var lastUi = 0L
                        var lastDb = clock.now()
                        val began = System.nanoTime()
                        val startBytes = start
                        while (true) {
                            ensureActive()
                            val n = content.stream.read(buffer)
                            if (n < 0) break
                            try {
                                out.write(buffer, 0, n)
                            } catch (e: IOException) {
                                throw LocalWriteException(part, e)
                            }
                            done += n
                            val now = clock.now()
                            if (now - lastUi >= 250) {
                                val seconds = ((System.nanoTime() - began) / 1e9).coerceAtLeast(0.001)
                                transfers.put(
                                    Transfer(track.driveId, book.driveId, title, track.name, track.sizeBytes, done, ((done - startBytes) / seconds).toLong()),
                                )
                                lastUi = now
                            }
                            if (now - lastDb >= 2000) {
                                tracks.setDownloadedBytes(track.driveId, done)
                                lastDb = now
                                // Wi-Fi perdu en mode « Wi-Fi uniquement » : arrêt, reprise plus tard.
                                if (!canContinue()) throw DownloadDeferredException()
                            }
                        }
                    }
                }
            } catch (e: DriveRangeNotSatisfiableException) {
                // Le « .part » est peut-être déjà complet : vérifié ci-dessous.
            }
        }

        if (track.sizeBytes > 0 && part.length() != track.sizeBytes) {
            tracks.setDownloadedBytes(track.driveId, part.length())
            if (part.length() > track.sizeBytes) part.delete()
            throw IntegrityException("Taille inattendue : ${part.length()} octets au lieu de ${track.sizeBytes}")
        }
        val expectedMd5 = track.md5
        if (expectedMd5 != null && !part.md5Hex().equals(expectedMd5, ignoreCase = true)) {
            part.delete()
            tracks.setDownloadedBytes(track.driveId, 0)
            throw IntegrityException("Checksum différent de celui de Drive")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        markPresent(book, track, target, ctx, received = true)
    }

    private suspend fun markPresent(book: BookEntity, track: TrackEntity, file: File, ctx: RunContext, received: Boolean) {
        val info = inspector.inspect(file)
        tracks.setLocalState(track.driveId, TrackState.PRESENT, file.path, file.length())
        info?.durationMs?.takeIf { it > 0 }?.let { tracks.setExactDuration(track.driveId, it) }
        transfers.remove(track.driveId)
        if (received) {
            ctx.filesReceived.incrementAndGet()
            ctx.bytesReceived.addAndGet(file.length())
            val pair = db.syncPairDao().forFolder(book.parentId)
            history.log(
                type = SyncEventType.RECEIVED,
                path = "${pair?.drivePath ?: ""}/${book.name}/${track.relativePath}",
                book = book,
                sizeBytes = file.length(),
                runId = ctx.runId,
            )
        }
        val fresh = books.get(book.driveId) ?: return
        if (fresh.tagNarrator == null && !info?.narrator.isNullOrBlank()) books.setTagNarrator(book.driveId, info?.narrator)
        val picture = info?.picture
        if (picture != null && fresh.coverPath?.let { File(it).exists() } != true) {
            val cover = File(storage.coversDir(), "${book.driveId}.jpg")
            cover.writeBytes(picture)
            books.setCoverPath(book.driveId, cover.path)
        }
        val all = tracks.forBook(book.driveId)
        if (all.isNotEmpty() && all.all { it.durationMs != null }) {
            books.updateDuration(book.driveId, all.sumOf { it.durationMs!! }, all.all { it.durationExact })
        }
    }

    companion object {
        const val SPACE_MARGIN_BYTES = 500L * 1000 * 1000
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * Nettoyage selon le statut ([CleanupPolicy]) : seul _En écoute garde ses pistes sur le
 * téléphone ; _A écouter plus tard les garde 7 jours, _Ecouté 24 h (délais réglables) ; les autres
 * statuts ne gardent que leurs métadonnées (sauf la première piste des _Nouveaux si « Aperçu »).
 * Seul le statut confirmé par Drive compte : un geste encore annulable ou un déplacement pas
 * encore fait dans Drive ne supprime rien.
 */
class Cleaner(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val localFiles: LocalFiles,
    private val storage: LocalStorage,
    private val history: HistoryLogger,
    private val clock: Clock,
    private val isBookInUse: (String) -> Boolean,
) {
    suspend fun run(ctx: RunContext?) {
        val s = settings.current()
        val now = clock.now()
        val pairs = db.syncPairDao().all()
        for (track in db.trackDao().withLocalData()) {
            val book = db.bookDao().get(track.bookId) ?: continue
            if (isBookInUse(book.driveId)) continue
            if (track.state == TrackState.DOWNLOADING) continue
            val pairMode = pairs.firstOrNull { it.driveFolderId == book.parentId }?.mode
            val deadline = CleanupPolicy.deadline(book, track.sortIndex, pairMode, s) ?: continue
            if (now < deadline) continue
            val path = track.localPath
            val freed = localFiles.deleteTrackFiles(track)
            if (freed > 0 && path != null) {
                ctx?.filesDeleted?.incrementAndGet()
                ctx?.bytesDeleted?.addAndGet(freed)
                history.log(
                    type = SyncEventType.LOCAL_DELETE,
                    path = storage.displayPath(File(path)),
                    book = book,
                    sizeBytes = freed,
                    message = "Nettoyage : livre dans ${pairs.firstOrNull { it.driveFolderId == book.parentId }?.name ?: "un autre statut"}",
                    runId = ctx?.runId,
                )
            }
        }
    }

    /**
     * Prochaine échéance de nettoyage d'une piste encore sur le téléphone, postérieure à
     * maintenant (pour programmer le nettoyage à l'heure dite), ou null. Le livre en cours de
     * lecture est ignoré : il sera nettoyé à la synchro suivante.
     */
    suspend fun nextDeadline(): Long? {
        val s = settings.current()
        val now = clock.now()
        val pairs = db.syncPairDao().all()
        val booksById = HashMap<String, BookEntity?>()
        return db.trackDao().withLocalData().mapNotNull { track ->
            val book = booksById.getOrPut(track.bookId) { db.bookDao().get(track.bookId) } ?: return@mapNotNull null
            if (isBookInUse(book.driveId)) return@mapNotNull null
            val pairMode = pairs.firstOrNull { it.driveFolderId == book.parentId }?.mode
            CleanupPolicy.deadline(book, track.sortIndex, pairMode, s)?.takeIf { it > now }
        }.minOrNull()
    }
}
