package com.julienmiquel.audiotheque.sync.engine

import androidx.room.withTransaction
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.EmbeddedChapterEntity
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

    private sealed interface WorkItem {
        val id: String
        val book: BookEntity

        data class TrackWork(val task: Task) : WorkItem {
            override val id: String get() = task.track.driveId
            override val book: BookEntity get() = task.book
        }

        data class ZipWork(override val book: BookEntity) : WorkItem {
            override val id: String get() = zipTransferId(book.driveId)
        }
    }

    private suspend fun fullModeCandidates(s: com.julienmiquel.audiotheque.core.settings.AppSettings): List<BookEntity> {
        val pairs = db.syncPairDao().all()
        val inProgressPair = pairs.firstOrNull { it.status == BookStatus.IN_PROGRESS }
        val progressByBook = db.progressDao().allFor(s.accountKey).associateBy { it.bookId }
        val allBooks = books.all().filter { !it.trashPending }
        val candidates = mutableListOf<BookEntity>()

        // 1. Livres épinglés hors ligne sur ce téléphone (« Garder sur ce téléphone »).
        candidates += allBooks.filter { it.pinnedOffline }

        // 2. Livres « En écoute » pour l'auditeur actif (statut personnel familial ou dossier Drive).
        if (inProgressPair == null || (inProgressPair.enabled && inProgressPair.mode == PairMode.FULL)) {
            candidates += allBooks.filter { book ->
                val p = progressByBook[book.driveId]
                (book.pendingStatus ?: p?.personalStatus ?: book.status) == BookStatus.IN_PROGRESS
            }
        }

        // 3. Collections ajoutées en réception complète (paires sans limite).
        pairs.filter { it.enabled && it.mode == PairMode.FULL && it.status == BookStatus.COLLECTION }.forEach { pair ->
            candidates += allBooks.filter { it.parentId == pair.driveFolderId }
        }
        return candidates.distinctBy { it.driveId }
    }

    /** Livres livrés en archive `.zip` à télécharger et décompresser. */
    suspend fun zipPlan(): List<BookEntity> {
        val s = settings.current()
        val progressDao = db.progressDao()
        return fullModeCandidates(s)
            .filter { it.kind == BookKind.ZIP }
            .filter { book ->
                val bookTracks = tracks.forBook(book.driveId)
                bookTracks.isEmpty() || bookTracks.any { t ->
                    t.state != TrackState.PRESENT || t.localPath?.let { File(it).exists() } != true
                }
            }
            .map { it to progressDao.get(it.driveId, s.accountKey) }
            .sortedWith(
                compareByDescending<Pair<BookEntity, ProgressEntity?>> { it.second?.lastListenedAt ?: 0L }
                    .thenByDescending { (book, p) ->
                        book.pendingSince ?: p?.personalStatusChangedAt ?: book.statusChangedAt
                    },
            )
            .map { it.first }
    }

    /** Pistes à télécharger, dans l'ordre de priorité. */
    suspend fun plan(): List<Task> {
        val s = settings.current()
        val progressByBook = db.progressDao().allFor(s.accountKey).associateBy { it.bookId }
        val withProgress = fullModeCandidates(s).filter { it.kind != BookKind.ZIP }
            .map { it to progressByBook[it.driveId] }
            .sortedWith(
                compareByDescending<Pair<BookEntity, ProgressEntity?>> { it.second?.lastListenedAt ?: 0L }
                    .thenByDescending { (book, p) ->
                        book.pendingSince ?: p?.personalStatusChangedAt ?: book.statusChangedAt
                    },
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
            val newBooks = books.all().filter { book ->
                !book.trashPending && book.kind != BookKind.ZIP &&
                    (book.pendingStatus ?: progressByBook[book.driveId]?.personalStatus ?: book.status) == BookStatus.NEW
            }
            for (book in newBooks) {
                tracks.forBook(book.driveId).firstOrNull()?.takeIf { it.state != TrackState.PRESENT }?.let { result += Task(book, it) }
            }
        }
        return result
    }

    /**
     * Télécharge le plan, 3 éléments à la fois (pistes ou archives `.zip`). [canContinue] est
     * consulté avant chaque élément et pendant les transferts : faux en mode « Wi-Fi uniquement »
     * quand le réseau devient mesuré.
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

        suspend fun next(): WorkItem? = lock.withLock {
            if (stop.get() != null) return@withLock null
            if (!canContinue()) {
                stop.compareAndSet(null, DownloadStop.WAITING_FOR_WIFI)
                return@withLock null
            }
            val plannedTracks = plan()
                .map { WorkItem.TrackWork(it) }
                .filter { it.book.driveId !in blockedBooks && (failures[it.id] ?: 0) < MAX_ATTEMPTS }
            val plannedZips = zipPlan()
                .map { WorkItem.ZipWork(it) }
                .filter { it.book.driveId !in blockedBooks && (failures[it.id] ?: 0) < MAX_ATTEMPTS }
            val allPlanned: List<WorkItem> = plannedZips + plannedTracks
            onSlot(allPlanned.size)
            for (item in allPlanned) {
                if (item.id in inFlight) continue
                if (item.book.driveId !in checkedBooks) {
                    val roomOk = when (item) {
                        is WorkItem.TrackWork -> hasRoomFor(item.book, ctx)
                        is WorkItem.ZipWork -> hasRoomForZip(item.book, ctx)
                    }
                    if (!roomOk) {
                        blockedBooks += item.book.driveId
                        continue
                    }
                    checkedBooks += item.book.driveId
                }
                inFlight += item.id
                return@withLock item
            }
            null
        }

        repeat(parallelism) {
            launch {
                while (true) {
                    val item = next() ?: break
                    try {
                        when (item) {
                            is WorkItem.TrackWork -> download(drive, item.task, ctx, canContinue)
                            is WorkItem.ZipWork -> downloadAndExtractZip(drive, item.book, ctx, canContinue)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DriveAuthException) {
                        throw e
                    } catch (e: DownloadDeferredException) {
                        stop.compareAndSet(null, DownloadStop.WAITING_FOR_WIFI)
                    } catch (e: Exception) {
                        val transientNetwork = e is IOException && e !is DriveHttpException &&
                            e !is IntegrityException && e !is LocalWriteException &&
                            e !is java.util.zip.ZipException
                        if (transientNetwork) {
                            // Réseau perdu : inutile d'essayer les pistes suivantes, un seul message.
                            if (stop.compareAndSet(null, DownloadStop.NETWORK_LOST)) {
                                history.log(
                                    type = SyncEventType.ERROR,
                                    path = item.book.name,
                                    book = item.book,
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
                                if (item is WorkItem.TrackWork) books.invalidateTracks(listOf(item.book.driveId))
                                MAX_ATTEMPTS.also { failures[item.id] = it }
                            }
                            // Stockage plein ou accès retiré : réessayer tout de suite ne servirait à rien.
                            is LocalWriteException -> MAX_ATTEMPTS.also { failures[item.id] = it }
                            else -> failures.merge(item.id, 1, Int::plus) ?: 1
                        }
                        if (attempts >= MAX_ATTEMPTS) {
                            val path = when (item) {
                                is WorkItem.TrackWork -> "${item.book.name}/${item.task.track.relativePath}"
                                is WorkItem.ZipWork -> item.book.name
                            }
                            val size = when (item) {
                                is WorkItem.TrackWork -> item.task.track.sizeBytes
                                is WorkItem.ZipWork -> item.book.sizeBytes
                            }
                            history.log(
                                type = SyncEventType.ERROR,
                                path = path,
                                book = item.book,
                                sizeBytes = size,
                                result = EventResult.ERROR,
                                message = "Téléchargement interrompu : ${e.message ?: e.javaClass.simpleName}",
                                runId = ctx.runId,
                            )
                            ctx.errors.incrementAndGet()
                        }
                    } finally {
                        inFlight -= item.id
                        transfers.remove(item.id)
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

    /** Espace libre ≥ 2× l'archive `.zip` (archive temporaire + pistes extraites) + 500 Mo. */
    private suspend fun hasRoomForZip(book: BookEntity, ctx: RunContext): Boolean {
        val needed = (book.sizeBytes.coerceAtLeast(0L) * 2L)
        val free = storage.freeBytes(storage.root())
        if (free >= needed + SPACE_MARGIN_BYTES) return true
        history.log(
            type = SyncEventType.ERROR,
            path = book.name,
            book = book,
            sizeBytes = needed,
            result = EventResult.ERROR,
            message = "Espace insuffisant pour décompresser l'archive .zip : il faut ${Formats.size(needed)} + 500 Mo de marge, ${Formats.size(free)} libres.",
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

    suspend fun downloadAndExtractZip(
        drive: DriveService,
        targetBook: BookEntity,
        ctx: RunContext,
        canContinue: () -> Boolean = { true },
    ) = withContext(Dispatchers.IO) {
        val book = books.get(targetBook.driveId) ?: return@withContext
        val existingTracks = tracks.forBook(book.driveId)
        if (existingTracks.isNotEmpty() && existingTracks.all { it.state == TrackState.PRESENT && it.localPath?.let(::File)?.exists() == true }) {
            return@withContext
        }
        val remoteFile = drive.getFile(book.driveId)
        if (remoteFile.trashed) throw DriveNotFoundException("L'archive .zip est dans la corbeille")
        val expectedSize = remoteFile.size ?: book.sizeBytes
        val expectedMd5 = remoteFile.md5

        val dir = bookDir(book)
        dir.mkdirs()
        val archivePart = File(dir, "_archive.zip.part")
        val transferId = zipTransferId(book.driveId)

        var start = if (archivePart.exists()) archivePart.length() else 0L
        if (expectedSize in 1 until start) {
            archivePart.delete()
            start = 0
        }
        val title = book.displayTitle
        if (expectedSize <= 0 || start < expectedSize) {
            try {
                drive.openContent(book.driveId, start).use { content ->
                    if (start > 0 && !content.partial) {
                        archivePart.delete()
                        start = 0
                    }
                    val output = try {
                        FileOutputStream(archivePart, start > 0)
                    } catch (e: IOException) {
                        throw LocalWriteException(archivePart, e)
                    }
                    output.use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = start
                        var lastUi = 0L
                        var lastCheck = clock.now()
                        val began = System.nanoTime()
                        val startBytes = start
                        while (true) {
                            ensureActive()
                            val n = content.stream.read(buffer)
                            if (n < 0) break
                            try {
                                out.write(buffer, 0, n)
                            } catch (e: IOException) {
                                throw LocalWriteException(archivePart, e)
                            }
                            done += n
                            val now = clock.now()
                            if (now - lastUi >= 250) {
                                val seconds = ((System.nanoTime() - began) / 1e9).coerceAtLeast(0.001)
                                transfers.put(
                                    Transfer(
                                        trackId = transferId,
                                        bookId = book.driveId,
                                        bookTitle = title,
                                        fileName = book.name,
                                        totalBytes = expectedSize,
                                        doneBytes = done,
                                        bytesPerSecond = ((done - startBytes) / seconds).toLong(),
                                    ),
                                )
                                lastUi = now
                            }
                            if (now - lastCheck >= 2000) {
                                lastCheck = now
                                if (!canContinue()) throw DownloadDeferredException()
                            }
                        }
                    }
                }
            } catch (e: DriveRangeNotSatisfiableException) {
                // Le « .part » est peut-être déjà complet : vérifié ci-dessous.
            }
        }

        if (expectedSize > 0 && archivePart.length() != expectedSize) {
            if (archivePart.length() > expectedSize) archivePart.delete()
            throw IntegrityException("Taille inattendue pour l'archive .zip : ${archivePart.length()} octets au lieu de $expectedSize")
        }
        if (expectedMd5 != null && !archivePart.md5Hex().equals(expectedMd5, ignoreCase = true)) {
            archivePart.delete()
            throw IntegrityException("Checksum de l'archive .zip différent de celui de Drive")
        }

        transfers.put(
            Transfer(
                trackId = transferId,
                bookId = book.driveId,
                bookTitle = title,
                fileName = "Décompression de ${book.name}…",
                totalBytes = archivePart.length(),
                doneBytes = archivePart.length(),
                bytesPerSecond = 0L,
            ),
        )

        val archiveBytes = archivePart.length()
        val extracted = try {
            ZipExtractor.extract(archivePart, dir, inspector) { ensureActive() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalWriteException) {
            throw e
        } catch (e: IOException) {
            archivePart.delete()
            throw IntegrityException("Archive .zip invalide ou corrompue : ${e.message ?: book.name}")
        }
        archivePart.delete()
        transfers.remove(transferId)

        val freshBook = books.get(book.driveId) ?: return@withContext
        if (extracted.coverBytes != null && freshBook.coverPath?.let { File(it).exists() } != true) {
            val ext = extracted.coverExt ?: "jpg"
            val coverFile = File(storage.coversDir(), "${book.driveId}.$ext")
            coverFile.writeBytes(extracted.coverBytes)
            books.setCoverPath(book.driveId, coverFile.path)
        }

        val rawEntities = extracted.tracks.map { t ->
            val exactDur = t.info?.durationMs?.takeIf { it > 0 }
            val len = t.file.length()
            TrackEntity(
                driveId = zipTrackId(book.driveId, t.sortIndex),
                bookId = book.driveId,
                name = t.name,
                relativePath = t.relativePath,
                sortIndex = t.sortIndex,
                number = t.number,
                part = t.part,
                chapter = t.chapter,
                title = t.title,
                durationMs = exactDur,
                durationExact = exactDur != null,
                sizeBytes = len,
                md5 = null,
                mimeType = t.mimeType,
                localPath = t.file.path,
                state = TrackState.PRESENT,
                downloadedBytes = len,
                modifiedAt = remoteFile.modifiedTime,
            )
        }
        val known = rawEntities.filter { (it.durationMs ?: 0) > 0 && it.sizeBytes > 0 }
        val msPerByte = if (known.isNotEmpty()) {
            known.sumOf { it.durationMs!! }.toDouble() / known.sumOf { it.sizeBytes }
        } else {
            null
        }
        val completed = rawEntities.map { t ->
            if (t.durationMs == null && msPerByte != null) {
                t.copy(durationMs = (t.sizeBytes * msPerByte).toLong())
            } else {
                t
            }
        }
        val totalDuration = completed.mapNotNull { it.durationMs }.sum()
            .takeIf { completed.isNotEmpty() && completed.all { t -> t.durationMs != null } }
        val allExact = completed.isNotEmpty() && completed.all { it.durationExact }

        db.withTransaction {
            if (books.get(book.driveId) == null) return@withTransaction
            tracks.upsertAll(completed)
            tracks.deleteMissing(book.driveId, completed.map { it.driveId })
            extracted.tracks.forEach { t ->
                val chs = t.info?.chapters.orEmpty()
                val trackId = zipTrackId(book.driveId, t.sortIndex)
                if (chs.size > 1) {
                    db.embeddedChapterDao().replaceForTrack(
                        trackId,
                        chs.mapIndexed { idx, ch -> EmbeddedChapterEntity(book.driveId, trackId, idx, ch.title, ch.startMs, ch.endMs) },
                    )
                }
            }
            books.updateTrackStats(
                id = book.driveId,
                sizeBytes = if (expectedSize > 0) expectedSize else archiveBytes,
                trackCount = completed.size,
                durationMs = totalDuration ?: freshBook.durationMs,
                durationExact = allExact,
                syncedAt = clock.now(),
            )
            if (freshBook.tagNarrator == null && !extracted.narrator.isNullOrBlank()) {
                books.setTagNarrator(book.driveId, extracted.narrator)
            }
        }

        ctx.filesReceived.addAndGet(completed.size)
        ctx.bytesReceived.addAndGet(archiveBytes)
        val pair = db.syncPairDao().forFolder(book.parentId)
        history.log(
            type = SyncEventType.RECEIVED,
            path = "${pair?.drivePath ?: ""}/${book.name}",
            book = book,
            sizeBytes = archiveBytes,
            message = "Archive .zip téléchargée et décompressée (${completed.size} pistes)",
            runId = ctx.runId,
        )
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
        if (info != null && info.chapters.size > 1) {
            db.embeddedChapterDao().replaceForTrack(
                track.driveId,
                info.chapters.mapIndexed { idx, ch ->
                    EmbeddedChapterEntity(book.driveId, track.driveId, idx, ch.title, ch.startMs, ch.endMs)
                },
            )
        }
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

        fun zipTransferId(bookId: String): String = "zip_$bookId"

        /** ID synthétique sans `:` (séparateur réservé par `MediaIds`). */
        fun zipTrackId(bookId: String, sortIndex: Int): String =
            "zip_${bookId}_${sortIndex.toString().padStart(4, '0')}"
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
        val progressByBook = db.progressDao().allFor(s.accountKey).associateBy { it.bookId }
        val inProgressOnDevice = db.progressDao().booksInProgressOnDevice().toSet()
        for (track in db.trackDao().withLocalData()) {
            val book = db.bookDao().get(track.bookId) ?: continue
            if (isBookInUse(book.driveId) || book.driveId in inProgressOnDevice) continue
            if (track.state == TrackState.DOWNLOADING) continue
            val pairMode = pairs.firstOrNull { it.driveFolderId == book.parentId }?.mode
            val prog = progressByBook[book.driveId]
            val deadline = CleanupPolicy.deadline(
                book = book,
                trackSortIndex = track.sortIndex,
                pairMode = pairMode,
                settings = s,
                personalStatus = prog?.personalStatus,
                personalStatusChangedAt = prog?.personalStatusChangedAt,
            ) ?: continue
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
        val progressByBook = db.progressDao().allFor(s.accountKey).associateBy { it.bookId }
        val inProgressOnDevice = db.progressDao().booksInProgressOnDevice().toSet()
        val booksById = HashMap<String, BookEntity?>()
        return db.trackDao().withLocalData().mapNotNull { track ->
            val book = booksById.getOrPut(track.bookId) { db.bookDao().get(track.bookId) } ?: return@mapNotNull null
            if (isBookInUse(book.driveId) || book.driveId in inProgressOnDevice) return@mapNotNull null
            val pairMode = pairs.firstOrNull { it.driveFolderId == book.parentId }?.mode
            val prog = progressByBook[book.driveId]
            CleanupPolicy.deadline(
                book = book,
                trackSortIndex = track.sortIndex,
                pairMode = pairMode,
                settings = s,
                personalStatus = prog?.personalStatus,
                personalStatusChangedAt = prog?.personalStatusChangedAt,
            )?.takeIf { it > now }
        }.minOrNull()
    }
}
