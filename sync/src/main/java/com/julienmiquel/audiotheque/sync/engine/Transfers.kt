package com.julienmiquel.audiotheque.sync.engine

import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
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
import com.julienmiquel.audiotheque.sync.drive.DriveRangeNotSatisfiableException
import com.julienmiquel.audiotheque.sync.drive.DriveService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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
                compareByDescending<Pair<BookEntity, com.julienmiquel.audiotheque.core.db.ProgressEntity?>> { it.second?.lastListenedAt ?: 0L }
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

    suspend fun run(drive: DriveService, ctx: RunContext, onSlot: suspend (remaining: Int) -> Unit = {}) = coroutineScope {
        val inFlight = ConcurrentHashMap.newKeySet<String>()
        val failures = ConcurrentHashMap<String, Int>()
        val blockedBooks = ConcurrentHashMap.newKeySet<String>()
        val checkedBooks = ConcurrentHashMap.newKeySet<String>()
        val lock = Mutex()

        suspend fun next(): Task? = lock.withLock {
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
                        download(drive, task, ctx)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DriveAuthException) {
                        throw e
                    } catch (e: Exception) {
                        val attempts = failures.merge(task.track.driveId, 1, Int::plus) ?: 1
                        val transientNetwork = e is IOException && e !is DriveHttpException && e !is IntegrityException
                        if (transientNetwork) failures[task.track.driveId] = MAX_ATTEMPTS
                        if (attempts >= MAX_ATTEMPTS || transientNetwork) {
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

    private suspend fun bookDir(book: BookEntity): File {
        val fresh = books.get(book.driveId) ?: book
        val name = fresh.localDirName ?: run {
            val base = LocalStorage.sanitize(fresh.name)
            val unique = if (books.countLocalDirName(base, fresh.driveId) > 0) "$base (${fresh.driveId.takeLast(6)})" else base
            books.setLocalDirName(fresh.driveId, unique)
            unique
        }
        return File(storage.root(), name)
    }

    suspend fun download(drive: DriveService, task: Task, ctx: RunContext) = withContext(Dispatchers.IO) {
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
                    FileOutputStream(part, start > 0).use { out ->
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
                            out.write(buffer, 0, n)
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
 * Nettoyage selon le statut : seul _En écoute garde ses pistes sur le téléphone ; _A écouter plus
 * tard les garde 7 jours, _Ecouté 24 h (délais réglables) ; les autres statuts ne gardent que
 * leurs métadonnées (sauf la première piste des _Nouveaux si « Aperçu »).
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
            val since = now - book.effectiveStatusChangedAt
            val keep = when (book.pendingStatus ?: book.status) {
                BookStatus.IN_PROGRESS -> true
                BookStatus.NEW -> s.previewNew && track.sortIndex == 0
                BookStatus.LATER -> since < s.laterRetentionDays * DAY_MS
                BookStatus.DONE -> since < s.doneRetentionHours * HOUR_MS
                BookStatus.COLLECTION -> pairMode == PairMode.FULL
                BookStatus.ARCHIVED, BookStatus.UNSORTED -> false
            }
            if (keep) continue
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
                    message = "Nettoyage : livre dans ${pairs.firstOrNull { it.driveFolderId == (book.pendingParentId ?: book.parentId) }?.name ?: "un autre statut"}",
                    runId = ctx?.runId,
                )
            }
        }
    }

    /** Prochaine échéance de nettoyage (pour planifier une synchro), ou null. */
    suspend fun nextDeadline(): Long? {
        val s = settings.current()
        val candidates = db.trackDao().withLocalData().mapNotNull { db.bookDao().get(it.bookId) }.distinctBy { it.driveId }
        return candidates.mapNotNull { b ->
            when (b.pendingStatus ?: b.status) {
                BookStatus.LATER -> b.effectiveStatusChangedAt + s.laterRetentionDays * DAY_MS
                BookStatus.DONE -> b.effectiveStatusChangedAt + s.doneRetentionHours * HOUR_MS
                else -> null
            }
        }.minOrNull()
    }

    companion object {
        const val HOUR_MS = 3600L * 1000
        const val DAY_MS = 24 * HOUR_MS
    }
}

/**
 * Progression par utilisateur dans l'espace caché de l'app sur le Drive de chaque compte
 * (appDataFolder) : elle suit l'utilisateur d'un téléphone à l'autre sans mélanger les positions
 * des membres de la famille. Le plus récent l'emporte.
 */
class ProgressSync(
    private val db: AppDatabase,
    private val clock: Clock,
    private val deviceName: String,
) {
    suspend fun run(drive: DriveService, account: String) {
        val dao = db.progressDao()
        val remote = drive.listAppData().filter { ProgressJson.bookIdFromFileName(it.name) != null }
        val remoteByBook = remote.associateBy { ProgressJson.bookIdFromFileName(it.name)!! }

        for (local in dao.dirty(account)) {
            val payload = ProgressJson(
                bookId = local.bookId,
                trackId = local.trackId,
                trackIndex = local.trackIndex,
                positionMs = local.positionMs,
                speed = local.speed,
                percent = local.percent,
                lastListenedAt = local.lastListenedAt,
                finished = local.finished,
                setAsideAtPercent = local.setAsideAtPercent,
                updatedAt = local.updatedAt,
                device = deviceName,
            )
            val id = local.remoteFileId ?: remoteByBook[local.bookId]?.id
            val file = if (id != null) {
                runCatching { drive.updateAppData(id, ProgressJson.encode(payload)) }.getOrElse { e ->
                    if (e is com.julienmiquel.audiotheque.sync.drive.DriveNotFoundException) {
                        drive.createAppData(ProgressJson.fileName(local.bookId), ProgressJson.encode(payload))
                    } else {
                        throw e
                    }
                }
            } else {
                drive.createAppData(ProgressJson.fileName(local.bookId), ProgressJson.encode(payload))
            }
            dao.markSynced(local.bookId, account, local.updatedAt, file.id, clock.now())
        }

        for ((bookId, file) in remoteByBook) {
            val local = dao.get(bookId, account)
            if (local?.dirty == true) continue
            val remoteModified = file.modifiedTime ?: Long.MAX_VALUE
            if (local != null && local.remoteSyncedAt != null && remoteModified <= local.remoteSyncedAt!!) continue
            val json = runCatching { ProgressJson.decode(drive.readAppData(file.id)) }.getOrNull() ?: continue
            if (local != null && local.updatedAt >= json.updatedAt) {
                dao.markSynced(bookId, account, local.updatedAt, file.id, clock.now())
                continue
            }
            dao.upsert(
                com.julienmiquel.audiotheque.core.db.ProgressEntity(
                    bookId = bookId,
                    account = account,
                    trackId = json.trackId,
                    trackIndex = json.trackIndex,
                    positionMs = json.positionMs,
                    speed = json.speed,
                    percent = json.percent,
                    lastListenedAt = json.lastListenedAt,
                    finished = json.finished,
                    setAsideAtPercent = json.setAsideAtPercent,
                    updatedAt = json.updatedAt,
                    dirty = false,
                    remoteFileId = file.id,
                    remoteSyncedAt = clock.now(),
                ),
            )
        }
    }
}
