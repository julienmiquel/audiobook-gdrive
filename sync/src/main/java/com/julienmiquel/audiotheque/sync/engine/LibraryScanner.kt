package com.julienmiquel.audiotheque.sync.engine

import androidx.room.withTransaction
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.PairMode
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.effectiveParentId
import com.julienmiquel.audiotheque.core.model.effectiveStatus
import com.julienmiquel.audiotheque.core.parse.FolderNameParser
import com.julienmiquel.audiotheque.core.parse.Mp3Probe
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.parse.TrackCandidate
import com.julienmiquel.audiotheque.core.parse.TrackNameParser
import com.julienmiquel.audiotheque.core.parse.TrackOrdering
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import com.julienmiquel.audiotheque.sync.drive.DriveFile
import com.julienmiquel.audiotheque.sync.drive.DriveNotFoundException
import com.julienmiquel.audiotheque.sync.drive.DriveService
import com.julienmiquel.audiotheque.sync.drive.readUpTo
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Détection des changements : inventaire complet à la première synchro puis une fois par
 * semaine, flux de modifications Drive (changes.list) entre-temps. Drive fait foi : un dossier
 * déplacé depuis le web ou par un autre membre de la famille met à jour le statut local.
 */
class LibraryScanner(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val history: HistoryLogger,
    private val localFiles: LocalFiles,
    private val storage: LocalStorage,
    private val clock: Clock,
) {
    private val books = db.bookDao()
    private val tracks = db.trackDao()
    private val pairsDao = db.syncPairDao()

    suspend fun scan(drive: DriveService, ctx: RunContext, forceFull: Boolean = false, progress: (String) -> Unit = {}) {
        val pairs = pairsDao.all()
        val account = settings.current().accountKey
        val state = settings.currentSyncState()
        val now = clock.now()
        val lastFull = state.lastFullInventoryAt
        val needFull = forceFull || state.pageToken == null || state.pageTokenAccount != account ||
            lastFull == null || now - lastFull >= FULL_INVENTORY_INTERVAL_MS
        if (needFull) {
            ctx.fullInventory = true
            ctx.initialInventory = books.count() == 0
            // Jeton pris avant l'inventaire : aucun changement intervenu pendant n'est perdu.
            val token = drive.startPageToken()
            for (pair in pairs.filter { it.enabled }) {
                progress("Inventaire de ${pair.name}")
                listPair(drive, pair, pairs, ctx)
            }
            // Inventaire hebdomadaire : les pistes de tous les livres synchronisés sont relistées.
            val metadataFolders = pairs.filter { it.enabled && it.mode != PairMode.ON_OPEN }.map { it.driveFolderId }
            if (metadataFolders.isNotEmpty()) books.invalidateTracksIn(metadataFolders)
            settings.updateSyncState {
                it.copy(pageToken = token, pageTokenAccount = account, lastFullInventoryAt = now)
            }
            if (ctx.initialInventory) {
                history.log(
                    type = SyncEventType.LIBRARY_CHANGE,
                    path = pairs.firstOrNull { it.status == BookStatus.UNSORTED }?.drivePath ?: "/AudioBook",
                    message = "Inventaire complet : ${books.count()} livres trouvés.",
                    runId = ctx.runId,
                )
            }
        } else {
            progress("Modifications Drive")
            var token = state.pageToken!!
            while (true) {
                val page = drive.changes(token)
                applyChanges(drive, page.changes.map { it.fileId to it }, pairs, ctx)
                val next = page.nextPageToken ?: page.newStartPageToken ?: break
                settings.updateSyncState { it.copy(pageToken = next) }
                if (page.nextPageToken == null) break
                token = next
            }
            // Filet de sécurité : le dossier en réception complète est relisté à chaque synchro.
            pairs.filter { it.enabled && it.mode == PairMode.FULL }.forEach { listPair(drive, it, pairs, ctx) }
        }
    }

    /** Inventaire des livres d'un dossier de statut. */
    suspend fun listPair(drive: DriveService, pair: SyncPairEntity, pairs: List<SyncPairEntity>, ctx: RunContext) {
        val pairFolders = pairs.map { it.driveFolderId }.toSet()
        val children = drive.listChildren(pair.driveFolderId)
        val present = HashSet<String>()
        for (child in children) {
            if (child.id in pairFolders) continue
            val kind = classify(child) ?: continue
            present += child.id
            upsertFromDrive(child, pair, kind, ctx)
        }
        for (book in books.byParent(pair.driveFolderId)) {
            if (book.driveId !in present) reconcileMissing(drive, book, pairs, ctx)
        }
        pairsDao.setLastInventory(pair.id, clock.now())
    }

    private suspend fun applyChanges(
        drive: DriveService,
        changes: List<Pair<String, com.julienmiquel.audiotheque.sync.drive.DriveChange>>,
        pairs: List<SyncPairEntity>,
        ctx: RunContext,
    ) {
        val pairByFolder = pairs.associateBy { it.driveFolderId }
        for ((fileId, change) in changes) {
            val file = change.file
            if (change.removed || file == null || file.trashed) {
                books.get(fileId)?.let {
                    removeBook(it, "supprimé de Drive", ctx)
                    continue
                }
                tracks.get(fileId)?.let { track ->
                    localFiles.deleteTrackFiles(track, updateDb = false)
                    tracks.delete(track.driveId)
                    books.invalidateTracks(listOf(track.bookId))
                }
                continue
            }
            if (file.id in pairByFolder) continue
            val pair = file.parents.firstNotNullOfOrNull { pairByFolder[it] }
            if (pair != null) {
                classify(file)?.let { upsertFromDrive(file, pair, it, ctx) }
                continue
            }
            val known = books.get(file.id)
            if (known != null) {
                removeBook(known, "déplacé hors de la bibliothèque", ctx)
                continue
            }
            val parentBook = file.parents.firstOrNull { books.get(it) != null }
            if (parentBook != null) {
                books.invalidateTracks(listOf(parentBook))
                continue
            }
            tracks.get(file.id)?.let { books.invalidateTracks(listOf(it.bookId)) }
        }
    }

    /** Crée ou met à jour un livre d'après Drive, sans jamais toucher aux gestes en attente. */
    private suspend fun upsertFromDrive(file: DriveFile, pair: SyncPairEntity, kind: BookKind, ctx: RunContext) {
        val existing = books.get(file.id)
        val now = clock.now()
        if (existing == null) {
            val parsed = FolderNameParser.parse(file.name, isFile = kind != BookKind.FOLDER)
            val book = BookEntity(
                driveId = file.id,
                name = file.name,
                parentId = pair.driveFolderId,
                status = pair.status,
                statusChangedAt = file.modifiedTime ?: now,
                kind = kind,
                parsedTitle = parsed.title,
                parsedAuthor = parsed.author,
                parsedNarrator = parsed.narrator,
                normalizedKey = TextNormalizer.key(parsed.title),
                sizeBytes = if (kind == BookKind.FOLDER) 0 else file.size ?: 0,
                trackCount = if (kind == BookKind.SINGLE_FILE) 1 else 0,
                addedAt = file.createdTime ?: now,
                modifiedAt = file.modifiedTime,
                tracksSyncedAt = if (kind == BookKind.FOLDER) null else now,
            )
            db.withTransaction {
                books.upsert(book)
                if (kind == BookKind.SINGLE_FILE) tracks.upsertAll(listOf(singleTrack(file)))
            }
            ctx.newBooks.incrementAndGet()
            if (!ctx.initialInventory) {
                history.log(
                    type = SyncEventType.LIBRARY_CHANGE,
                    path = "${pair.drivePath}/${file.name}",
                    book = book,
                    message = "Nouveau dans ${pair.name}",
                    runId = ctx.runId,
                )
            }
            return
        }
        if (existing.name != file.name) {
            val parsed = FolderNameParser.parse(file.name, isFile = kind != BookKind.FOLDER)
            books.updateDriveName(file.id, file.name, parsed.title, parsed.author, parsed.narrator, TextNormalizer.key(parsed.title))
        }
        if (existing.modifiedAt != file.modifiedTime) books.updateModifiedAt(file.id, file.modifiedTime)
        if (kind != BookKind.FOLDER && file.size != null && file.size != existing.sizeBytes) books.updateSize(file.id, file.size)
        if (existing.parentId != pair.driveFolderId) {
            val pairs = pairsDao.all()
            db.withTransaction {
                books.updateConfirmedLocation(file.id, pair.driveFolderId, pair.status, now)
                if (existing.pendingParentId == pair.driveFolderId) {
                    // Drive a déjà la valeur attendue par le geste en attente.
                    db.pendingOperationDao().deleteForBook(file.id)
                    books.setPending(file.id, null, null, null)
                }
            }
            history.log(
                type = SyncEventType.STATUS_FROM_DRIVE,
                path = "${OperationExecutor.pathOf(existing.parentId, pairs)} → ${pair.drivePath}",
                book = existing,
                message = "Déplacé dans Drive : ${OperationExecutor.nameOf(existing.parentId, pairs)} → ${pair.name}",
                runId = ctx.runId,
            )
            ctx.statusUpdates.incrementAndGet()
        }
    }

    private suspend fun reconcileMissing(drive: DriveService, book: BookEntity, pairs: List<SyncPairEntity>, ctx: RunContext) {
        try {
            val file = drive.getFile(book.driveId)
            if (file.trashed) {
                removeBook(book, "mis à la corbeille", ctx)
                return
            }
            val pair = pairs.firstOrNull { it.driveFolderId in file.parents }
            if (pair != null) upsertFromDrive(file, pair, book.kind, ctx) else removeBook(book, "déplacé hors de la bibliothèque", ctx)
        } catch (e: DriveNotFoundException) {
            removeBook(book, "supprimé de Drive", ctx)
        }
    }

    /** Livre disparu de Drive : on retire ses fichiers locaux ; sa progression est conservée. */
    suspend fun removeBook(book: BookEntity, reason: String, ctx: RunContext?) {
        var freed = 0L
        tracks.forBook(book.driveId).forEach { freed += localFiles.deleteTrackFiles(it, updateDb = false) }
        book.coverPath?.let { path -> if (path.startsWith(storage.coversDir().path)) File(path).delete() }
        db.withTransaction {
            db.pendingOperationDao().deleteForBook(book.driveId)
            books.delete(book.driveId)
        }
        history.log(
            type = SyncEventType.LIBRARY_CHANGE,
            path = book.name,
            book = book,
            sizeBytes = freed.takeIf { it > 0 },
            message = "Retiré de la bibliothèque : $reason",
            runId = ctx?.runId,
        )
    }

    companion object {
        const val FULL_INVENTORY_INTERVAL_MS = 7L * 24 * 3600 * 1000

        fun classify(file: DriveFile): BookKind? = when {
            file.isFolder -> BookKind.FOLDER
            file.isZip -> BookKind.ZIP
            file.isAudio -> BookKind.SINGLE_FILE
            else -> null
        }

        fun singleTrack(file: DriveFile): TrackEntity {
            val parsed = TrackNameParser.parse(file.name)
            return TrackEntity(
                driveId = file.id,
                bookId = file.id,
                name = file.name,
                relativePath = file.name,
                sortIndex = 0,
                number = parsed.number,
                part = parsed.part,
                chapter = parsed.chapter,
                title = parsed.title,
                sizeBytes = file.size ?: 0,
                md5 = file.md5,
                mimeType = file.mimeType,
                modifiedAt = file.modifiedTime,
            )
        }
    }
}

/**
 * Métadonnées des livres non téléchargés : liste des pistes, durée estimée à partir des
 * premiers Ko de la première piste (débit), voix lue dans les tags ID3, pochette.
 */
class MetadataProber(
    private val db: AppDatabase,
    private val history: HistoryLogger,
    private val localFiles: LocalFiles,
    private val storage: LocalStorage,
    private val clock: Clock,
) {
    private val books = db.bookDao()
    private val tracks = db.trackDao()

    /** [onlyPair] : inventaire d'un dossier « à l'ouverture ». */
    suspend fun run(drive: DriveService, ctx: RunContext?, onlyPair: SyncPairEntity? = null, progress: (String) -> Unit = {}) {
        val pairs = db.syncPairDao().all()
        val eligible: Set<String> = if (onlyPair != null) {
            setOf(onlyPair.driveFolderId)
        } else {
            pairs.filter { it.enabled && it.mode != PairMode.ON_OPEN }.map { it.driveFolderId }.toSet()
        }
        fun isEligible(b: BookEntity) = b.parentId in eligible || b.effectiveParentId in eligible

        val toList = books.all().filter { b ->
            b.kind == BookKind.FOLDER && isEligible(b) &&
                (b.tracksSyncedAt == null || b.effectiveStatus == BookStatus.IN_PROGRESS)
        }
        toList.forEachIndexed { i, book ->
            progress("Pistes ${i + 1}/${toList.size} : ${book.displayTitle}")
            guarded(book, ctx) { inventoryTracks(drive, book) }
        }

        val toProbe = books.all().filter { b ->
            isEligible(b) && b.kind != BookKind.ZIP && b.probedAt == null && b.trackCount > 0 && !b.durationExact
        }
        toProbe.forEachIndexed { i, book ->
            progress("Durées ${i + 1}/${toProbe.size} : ${book.displayTitle}")
            guarded(book, ctx) { probe(drive, book) }
        }
    }

    private suspend fun guarded(book: BookEntity, ctx: RunContext?, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthException) {
            throw e
        } catch (e: java.io.IOException) {
            if (e !is com.julienmiquel.audiotheque.sync.drive.DriveHttpException) throw e // réseau : on arrête
            history.log(SyncEventType.ERROR, book.name, book, result = EventResult.ERROR, message = e.message, runId = ctx?.runId)
            ctx?.errors?.incrementAndGet()
        }
    }

    /** Liste les pistes d'un livre (sous-dossiers « CD1 », « CD2 »… compris). */
    suspend fun inventoryTracks(drive: DriveService, book: BookEntity) {
        val files = listRecursive(drive, book.driveId, "", 0)
        val audio = files.filter { it.second.isAudio }
        val fileById = audio.associate { it.second.id to it.second }
        val existing = tracks.forBook(book.driveId).associateBy { it.driveId }
        val sorted = TrackOrdering.sort(audio.map { TrackCandidate(it.second.id, it.first, it.second.name) })
        val entities = sorted.mapIndexed { index, (candidate, parsed) ->
            val f = fileById.getValue(candidate.id)
            val old = existing[candidate.id]
            val changed = old != null && ((f.md5 != null && old.md5 != null && old.md5 != f.md5) || old.sizeBytes != (f.size ?: 0))
            if (changed && old != null) localFiles.deleteTrackFiles(old, updateDb = false)
            val keep = old != null && !changed
            TrackEntity(
                driveId = candidate.id,
                bookId = book.driveId,
                name = f.name,
                relativePath = candidate.relativePath,
                sortIndex = index,
                number = parsed.number,
                part = parsed.part,
                chapter = parsed.chapter,
                title = parsed.title,
                durationMs = if (keep) old?.durationMs else null,
                durationExact = if (keep) old?.durationExact ?: false else false,
                sizeBytes = f.size ?: 0,
                md5 = f.md5,
                mimeType = f.mimeType,
                localPath = if (keep) old?.localPath else null,
                state = if (keep) old?.state ?: TrackState.ABSENT else TrackState.ABSENT,
                downloadedBytes = if (keep) old?.downloadedBytes ?: 0 else 0,
                modifiedAt = f.modifiedTime,
            )
        }
        existing.values.filter { it.driveId !in fileById }.forEach { localFiles.deleteTrackFiles(it, updateDb = false) }
        // Durées manquantes : proportionnelles à la taille, d'après les pistes déjà connues.
        val known = entities.filter { (it.durationMs ?: 0) > 0 }
        val msPerByte = if (known.isNotEmpty() && known.sumOf { it.sizeBytes } > 0) {
            known.sumOf { it.durationMs!! }.toDouble() / known.sumOf { it.sizeBytes }
        } else {
            null
        }
        val completed = entities.map { t ->
            if (t.durationMs == null && msPerByte != null) t.copy(durationMs = (t.sizeBytes * msPerByte).toLong()) else t
        }
        val totalDuration = completed.mapNotNull { it.durationMs }.sum().takeIf { completed.all { t -> t.durationMs != null } && completed.isNotEmpty() }
        db.withTransaction {
            tracks.upsertAll(completed)
            tracks.deleteMissing(book.driveId, completed.map { it.driveId })
            books.updateTrackStats(
                id = book.driveId,
                sizeBytes = completed.sumOf { it.sizeBytes },
                trackCount = completed.size,
                durationMs = totalDuration ?: book.durationMs,
                durationExact = completed.isNotEmpty() && completed.all { it.durationExact },
                syncedAt = clock.now(),
            )
        }
        val image = files.filter { it.second.isImage }.minByOrNull { coverRank(it.second.name) }?.second
        if (image != null && book.coverPath?.let { File(it).exists() } != true) {
            saveCover(drive, book, image)
        }
    }

    private suspend fun listRecursive(drive: DriveService, folderId: String, prefix: String, depth: Int): List<Pair<String, DriveFile>> {
        val result = mutableListOf<Pair<String, DriveFile>>()
        for (child in drive.listChildren(folderId)) {
            if (child.isFolder) {
                if (depth < 2) result += listRecursive(drive, child.id, "$prefix${child.name}/", depth + 1)
            } else {
                result += "$prefix${child.name}" to child
            }
        }
        return result
    }

    private fun coverRank(name: String): Int {
        val n = name.lowercase()
        return when {
            n.startsWith("cover") || n.startsWith("couverture") -> 0
            n.startsWith("folder") || n.startsWith("front") -> 1
            else -> 2
        }
    }

    private suspend fun saveCover(drive: DriveService, book: BookEntity, image: DriveFile) {
        if ((image.size ?: 0) > MAX_COVER_BYTES) return
        val bytes = drive.openContent(image.id).readUpTo(MAX_COVER_BYTES)
        val ext = image.name.substringAfterLast('.', "jpg").lowercase()
        val file = File(storage.coversDir(), "${book.driveId}.$ext")
        file.writeBytes(bytes)
        books.setCoverPath(book.driveId, file.path)
    }

    /** Lit les premiers Ko de la première piste pour connaître le débit, puis estime la durée. */
    suspend fun probe(drive: DriveService, book: BookEntity) {
        val bookTracks = tracks.forBook(book.driveId)
        val first = bookTracks.firstOrNull() ?: return
        val isMp3 = first.mimeType == "audio/mpeg" || first.name.endsWith(".mp3", ignoreCase = true)
        if (!isMp3) {
            books.markProbed(book.driveId, clock.now(), null)
            return
        }
        var bytes = drive.openContent(first.driveId, 0, Mp3Probe.PROBE_BYTES - 1L).readUpTo(Mp3Probe.PROBE_BYTES)
        val tagSize = Mp3Probe.id3TagSize(bytes)
        if (tagSize > bytes.size - 4096 && tagSize <= Mp3Probe.MAX_TAG_BYTES) {
            // Étiquette plus grande que l'échantillon (pochette intégrée) : on la lit entière.
            val wanted = tagSize + 8 * 1024
            bytes = drive.openContent(first.driveId, 0, wanted - 1L).readUpTo(wanted)
        }
        val info = Mp3Probe.parse(bytes)
        val bytesPerSecond = info.bytesPerSecond(first.sizeBytes)
        if (bytesPerSecond != null && bytesPerSecond > 0) {
            var total = 0L
            var allExact = true
            db.withTransaction {
                for (t in bookTracks) {
                    if (t.durationExact && t.durationMs != null) {
                        total += t.durationMs!!
                    } else {
                        val estimate = ((t.sizeBytes - if (t.driveId == first.driveId) info.id3Size else 0) * 1000.0 / bytesPerSecond).toLong()
                        tracks.updateEstimatedDuration(t.driveId, estimate.coerceAtLeast(0))
                        total += estimate.coerceAtLeast(0)
                        allExact = false
                    }
                }
                books.updateDuration(book.driveId, total, allExact)
            }
        }
        val picture = info.tags?.picture
        if (picture != null && book.coverPath?.let { File(it).exists() } != true) {
            val ext = if (info.tags?.pictureMime?.contains("png") == true) "png" else "jpg"
            val file = File(storage.coversDir(), "${book.driveId}.$ext")
            file.writeBytes(picture)
            books.setCoverPath(book.driveId, file.path)
        }
        books.markProbed(book.driveId, clock.now(), info.tags?.narrator())
    }

    companion object {
        const val MAX_COVER_BYTES = 3 * 1024 * 1024
    }
}
