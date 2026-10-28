package com.julienmiquel.audiotheque.sync.engine

import androidx.room.withTransaction
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.EmbeddedChapterEntity
import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.PairMode
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.effectiveParentId
import com.julienmiquel.audiotheque.core.model.effectiveStatus
import com.julienmiquel.audiotheque.core.model.expectedAuthorKey
import com.julienmiquel.audiotheque.core.parse.FolderNameParser
import com.julienmiquel.audiotheque.core.parse.Mp3Probe
import com.julienmiquel.audiotheque.core.parse.Mp4Probe
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.parse.TrackCandidate
import com.julienmiquel.audiotheque.core.parse.TrackNameParser
import com.julienmiquel.audiotheque.core.parse.TrackOrdering
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import com.julienmiquel.audiotheque.sync.drive.DriveChange
import com.julienmiquel.audiotheque.sync.drive.DriveFile
import com.julienmiquel.audiotheque.sync.drive.DriveHttpException
import com.julienmiquel.audiotheque.sync.drive.DriveNotFoundException
import com.julienmiquel.audiotheque.sync.drive.DriveService
import com.julienmiquel.audiotheque.sync.drive.readUpTo
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * Détection des changements : inventaire complet à la première synchro puis une fois par
 * semaine, flux de modifications Drive (changes.list) entre-temps. Drive fait foi : un dossier
 * déplacé depuis le web ou par un autre membre de la famille met à jour le statut local.
 *
 * Une paire désactivée est « en pause » : ses livres restent affichés tels quels, sans nouveaux
 * livres ni mises à jour, jusqu'à sa réactivation.
 */
class LibraryScanner(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val history: HistoryLogger,
    private val localFiles: LocalFiles,
    private val storage: LocalStorage,
    private val clock: Clock,
    private val isBookInUse: (String) -> Boolean = { false },
) {
    private val books = db.bookDao()
    private val tracks = db.trackDao()
    private val pairsDao = db.syncPairDao()

    /** Livres à retirer de la bibliothèque, gardés le temps de leur écoute ; revérifiés à chaque synchro. */
    private val deferredRemovals = ConcurrentHashMap.newKeySet<String>()

    suspend fun scan(drive: DriveService, ctx: RunContext, forceFull: Boolean = false, progress: (String) -> Unit = {}) {
        val pairs = pairsDao.all()
        val account = settings.current().accountKey
        val state = settings.currentSyncState()
        val now = clock.now()
        reconcileDeferred(drive, pairs, ctx)
        val lastFull = state.lastFullInventoryAt
        val needFull = forceFull || state.pageToken == null || state.pageTokenAccount != account ||
            lastFull == null || now - lastFull >= FULL_INVENTORY_INTERVAL_MS
        if (needFull) {
            ctx.fullInventory = true
            ctx.initialInventory = books.count() == 0
            // Jeton pris avant l'inventaire : aucun changement intervenu pendant n'est perdu.
            val token = drive.startPageToken()
            // Tous les dossiers actifs sont listés, « à l'ouverture » compris : une requête par
            // dossier suffit pour que la recherche couvre toute la bibliothèque. Les pistes, durées
            // et pochettes de ces dossiers ne sont lues qu'à leur ouverture (MetadataProber).
            for (pair in pairs.filter { it.enabled }) {
                progress("Inventaire de ${pair.name}")
                listPair(drive, pair, pairs, ctx)
            }
            // Livres rattachés à un dossier qui n'est plus synchronisé : vérifiés un par un.
            val pairFolders = pairs.map { it.driveFolderId }.toSet()
            books.all().filter { it.parentId !in pairFolders && it.effectiveParentId !in pairFolders }
                .forEach { reconcileMissing(drive, it, pairs, ctx) }
            // Inventaire hebdomadaire : les pistes de tous les livres synchronisés sont relistées.
            val metadataFolders = pairs.filter { it.enabled && it.mode != PairMode.ON_OPEN }.map { it.driveFolderId }
            if (metadataFolders.isNotEmpty()) books.invalidateTracksIn(metadataFolders)
            settings.updateSyncState {
                it.copy(pageToken = token, pageTokenAccount = account, lastFullInventoryAt = now)
            }
            if (ctx.initialInventory) {
                history.log(
                    type = SyncEventType.LIBRARY_CHANGE,
                    path = pairs.firstOrNull { it.status == BookStatus.UNSORTED }?.drivePath ?: DriveLibraryDefaults.ROOT_PATH,
                    message = "Inventaire complet : ${books.count()} livres trouvés.",
                    runId = ctx.runId,
                )
            }
        } else {
            progress("Modifications Drive")
            var token = state.pageToken!!
            while (true) {
                val page = drive.changes(token)
                applyChanges(drive, page.changes, pairs, ctx)
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
        val children = drive.listChildren(pair.driveFolderId)
        val present = HashSet<String>()
        for (child in children) {
            if (isStatusFolder(child, pair, pairs)) continue
            val kind = classify(child) ?: continue
            present += child.id
            upsertFromDrive(child, pair, kind, ctx)
        }
        for (book in books.byParent(pair.driveFolderId)) {
            if (book.driveId !in present) reconcileMissing(drive, book, pairs, ctx)
        }
        pairsDao.setLastInventory(pair.id, clock.now())
    }

    private suspend fun applyChanges(drive: DriveService, changes: List<DriveChange>, pairs: List<SyncPairEntity>, ctx: RunContext) {
        val pairByFolder = pairs.associateBy { it.driveFolderId }
        for (change in changes) {
            val fileId = change.fileId
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
            // Fichier de progression écrit par l'app (ce téléphone ou un autre) : relu par la
            // synchro de progression, pas un changement de la bibliothèque.
            if (ProgressJson.isProgressFile(file.name)) continue
            val pair = file.parents.firstNotNullOfOrNull { pairByFolder[it] }
            if (isStatusFolder(file, pair, pairs)) continue
            if (pair != null) {
                val known = books.get(file.id)
                // Dossier en pause : ni nouveau livre ni mise à jour. Seul un livre qui y arrive
                // depuis un autre statut voit son statut suivre Drive.
                if (!pair.enabled && (known == null || known.parentId == pair.driveFolderId)) continue
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
                authorKey = TextNormalizer.keyOrNull(parsed.author),
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
            books.updateDriveName(
                file.id, file.name, parsed.title, parsed.author, parsed.narrator,
                TextNormalizer.key(parsed.title), TextNormalizer.keyOrNull(parsed.author),
            )
        } else if (existing.authorKey != existing.expectedAuthorKey) {
            // Clé d'auteur absente (base antérieure) ou périmée.
            books.setAuthorKey(file.id, existing.expectedAuthorKey)
        }
        if (existing.modifiedAt != file.modifiedTime) books.updateModifiedAt(file.id, file.modifiedTime)
        if (kind != BookKind.FOLDER && file.size != null && file.size != existing.sizeBytes) books.updateSize(file.id, file.size)
        if (kind == BookKind.SINGLE_FILE) refreshSingleTrack(file)
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

    /** Piste isolée modifiée dans Drive (taille, checksum, nom) : l'ancienne copie locale est écartée. */
    private suspend fun refreshSingleTrack(file: DriveFile) {
        val fresh = singleTrack(file)
        val old = tracks.get(file.id)
        if (old == null) {
            tracks.upsertAll(listOf(fresh))
            return
        }
        val contentChanged = (file.md5 != null && old.md5 != null && old.md5 != file.md5) || old.sizeBytes != fresh.sizeBytes
        if (!contentChanged && old.md5 == fresh.md5 && old.name == fresh.name) return
        if (contentChanged) {
            localFiles.deleteTrackFiles(old, updateDb = false)
            tracks.update(fresh)
        } else {
            tracks.update(old.copy(name = fresh.name, title = fresh.title, md5 = fresh.md5 ?: old.md5, modifiedAt = fresh.modifiedAt))
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

    /** Retraits reportés pendant l'écoute : refaits dès que le livre n'est plus dans le lecteur. */
    private suspend fun reconcileDeferred(drive: DriveService, pairs: List<SyncPairEntity>, ctx: RunContext) {
        for (id in deferredRemovals.toList()) {
            if (isBookInUse(id)) continue
            deferredRemovals -= id
            books.get(id)?.let { reconcileMissing(drive, it, pairs, ctx) }
        }
    }

    /**
     * Livre disparu de Drive : on retire ses fichiers locaux ; sa progression est conservée. Le
     * livre en cours de lecture est gardé jusqu'à la fin de l'écoute (retrait reporté).
     */
    suspend fun removeBook(book: BookEntity, reason: String, ctx: RunContext?) {
        if (isBookInUse(book.driveId)) {
            if (deferredRemovals.add(book.driveId)) {
                history.log(
                    type = SyncEventType.LIBRARY_CHANGE,
                    path = book.name,
                    book = book,
                    message = "${reason.replaceFirstChar { it.uppercase() }} : livre gardé jusqu'à la fin de l'écoute en cours",
                    runId = ctx?.runId,
                )
            }
            return
        }
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

        /**
         * Dossier de statut ou de collection (« _Nouveaux », « _meditations »…) : jamais un livre,
         * même si sa paire a été retirée. À la racine, les dossiers « _… » sont réservés aux statuts.
         */
        fun isStatusFolder(file: DriveFile, parentPair: SyncPairEntity?, pairs: List<SyncPairEntity>): Boolean =
            pairs.any { it.driveFolderId == file.id } ||
                file.id in DriveLibraryDefaults.FOLDER_IDS ||
                (file.isFolder && file.name.startsWith("_") && parentPair?.status == BookStatus.UNSORTED)

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

/** Livres traités par une passe du [MetadataProber]. */
enum class ProbeScope {
    /** Livres à télécharger (_En écoute, collections en réception complète) : avant les transferts. */
    DOWNLOADS,

    /** Le reste de la bibliothèque, après les transferts. */
    LIBRARY,

    /** Tout (dossier « à l'ouverture »). */
    ALL,
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

    /**
     * [onlyPair] : inventaire d'un dossier « à l'ouverture ». [allowContent] faux (réseau mesuré
     * en mode « Wi-Fi uniquement ») : listes des pistes seulement, sans échantillon audio ni
     * pochette. Retourne vrai si du contenu reste à lire au retour du Wi-Fi.
     */
    suspend fun run(
        drive: DriveService,
        ctx: RunContext?,
        onlyPair: SyncPairEntity? = null,
        scope: ProbeScope = ProbeScope.ALL,
        allowContent: Boolean = true,
        progress: (String) -> Unit = {},
    ): Boolean {
        val pairs = db.syncPairDao().all()
        val eligible: Set<String> = if (onlyPair != null) {
            setOf(onlyPair.driveFolderId)
        } else {
            pairs.filter { it.enabled && it.mode != PairMode.ON_OPEN }.map { it.driveFolderId }.toSet()
        }
        val fullFolders = pairs.filter { it.enabled && it.mode == PairMode.FULL }.map { it.driveFolderId }.toSet()
        fun isEligible(b: BookEntity) = b.parentId in eligible || b.effectiveParentId in eligible
        fun isDownloaded(b: BookEntity) = b.effectiveParentId in fullFolders
        fun inScope(b: BookEntity) = when (scope) {
            ProbeScope.DOWNLOADS -> isDownloaded(b)
            ProbeScope.LIBRARY -> !isDownloaded(b)
            ProbeScope.ALL -> true
        }

        val toList = books.all().filter { b ->
            b.kind == BookKind.FOLDER && isEligible(b) && inScope(b) &&
                (b.tracksSyncedAt == null || b.effectiveStatus == BookStatus.IN_PROGRESS)
        }
        toList.forEachIndexed { i, book ->
            progress("Pistes ${i + 1}/${toList.size} : ${book.displayTitle}")
            guarded(book, ctx) { inventoryTracks(drive, book, withCover = allowContent) }
        }

        // Les livres téléchargés ont leur durée exacte à l'arrivée des pistes : pas d'échantillon.
        if (scope == ProbeScope.DOWNLOADS) return false
        val toProbe = books.all().filter { b ->
            isEligible(b) && inScope(b) && b.kind != BookKind.ZIP && b.probedAt == null && b.trackCount > 0 && !b.durationExact
        }
        if (!allowContent) return toProbe.isNotEmpty()
        toProbe.forEachIndexed { i, book ->
            progress("Durées ${i + 1}/${toProbe.size} : ${book.displayTitle}")
            guarded(book, ctx) { probe(drive, book) }
        }
        return false
    }

    private suspend fun guarded(book: BookEntity, ctx: RunContext?, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthException) {
            throw e
        } catch (e: IOException) {
            if (e !is DriveHttpException) throw e // réseau : on arrête
            history.log(SyncEventType.ERROR, book.name, book, result = EventResult.ERROR, message = e.message, runId = ctx?.runId)
            ctx?.errors?.incrementAndGet()
        }
    }

    /** Liste les pistes d'un livre (sous-dossiers « CD1 », « CD2 »… compris). */
    suspend fun inventoryTracks(drive: DriveService, book: BookEntity, withCover: Boolean = true) {
        val files = listRecursive(drive, book.driveId, "", 0)
        val audio = files.filter { it.second.isAudio }
        val fileById = audio.associate { it.second.id to it.second }
        val sorted = TrackOrdering.sort(audio.map { TrackCandidate(it.second.id, it.first, it.second.name) })
        val staleFiles = mutableListOf<TrackEntity>()
        db.withTransaction {
            // Pistes relues dans la transaction : un téléchargement ou une durée exacte enregistrés
            // pendant la lecture de Drive ne sont pas écrasés par une copie périmée.
            if (books.get(book.driveId) == null) return@withTransaction
            val existing = tracks.forBook(book.driveId).associateBy { it.driveId }
            val entities = sorted.mapIndexed { index, (candidate, parsed) ->
                val f = fileById.getValue(candidate.id)
                val old = existing[candidate.id]
                val changed = old != null && ((f.md5 != null && old.md5 != null && old.md5 != f.md5) || old.sizeBytes != (f.size ?: 0))
                if (changed && old != null) staleFiles += old
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
            existing.values.filter { it.driveId !in fileById }.forEach { staleFiles += it }
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
        // Fichiers périmés (piste remplacée ou retirée dans Drive) supprimés hors transaction.
        staleFiles.forEach { localFiles.deleteTrackFiles(it, updateDb = false) }
        if (withCover) {
            val image = files.filter { it.second.isImage }.minByOrNull { coverRank(it.second.name) }?.second
            if (image != null && book.coverPath?.let { File(it).exists() } != true) saveCover(drive, book, image)
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
        // Le dossier Drive a déjà une image : rien à y déposer.
        books.setCover(book.driveId, file.path, uploadPending = false)
    }

    /** Lit les premiers Ko (ou l'atome moov d'un .m4b/.m4a) pour connaître la durée, la voix, la pochette et les chapitres. */
    suspend fun probe(drive: DriveService, book: BookEntity) {
        // Pochette du dossier pas encore lue (liste faite sur réseau mesuré) : une requête légère.
        if (book.kind == BookKind.FOLDER && book.coverPath == null) {
            drive.listChildren(book.driveId).filter { it.isImage }.minByOrNull { coverRank(it.name) }?.let { saveCover(drive, book, it) }
        }
        val fresh = books.get(book.driveId) ?: return
        val bookTracks = tracks.forBook(book.driveId)
        val first = bookTracks.firstOrNull() ?: return
        if (Mp4Probe.isMp4File(first.name, first.mimeType) && first.sizeBytes > 16) {
            probeMp4(drive, fresh, bookTracks, first)
            return
        }
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
        var firstDuration: Long? = null
        if (bytesPerSecond != null && bytesPerSecond > 0) {
            var total = 0L
            var allExact = true
            db.withTransaction {
                for (t in tracks.forBook(book.driveId)) {
                    if (t.durationExact && t.durationMs != null) {
                        if (t.driveId == first.driveId) firstDuration = t.durationMs
                        total += t.durationMs!!
                    } else {
                        val estimate = ((t.sizeBytes - if (t.driveId == first.driveId) info.id3Size else 0) * 1000.0 / bytesPerSecond).toLong()
                        val clamped = estimate.coerceAtLeast(0)
                        if (t.driveId == first.driveId) firstDuration = clamped
                        tracks.updateEstimatedDuration(t.driveId, clamped)
                        total += clamped
                        allExact = false
                    }
                }
                books.updateDuration(book.driveId, total, allExact)
            }
        }
        val chapters = Mp3Probe.normalizeChapters(info.tags?.chapters.orEmpty(), firstDuration)
        if (chapters.size > 1) {
            db.embeddedChapterDao().replaceForTrack(
                first.driveId,
                chapters.mapIndexed { idx, ch -> EmbeddedChapterEntity(book.driveId, first.driveId, idx, ch.title, ch.startMs, ch.endMs) },
            )
        }
        val picture = info.tags?.picture
        if (picture != null && fresh.coverPath?.let { File(it).exists() } != true) {
            val ext = if (info.tags?.pictureMime?.contains("png") == true) "png" else "jpg"
            val file = File(storage.coversDir(), "${book.driveId}.$ext")
            file.writeBytes(picture)
            books.setCoverPath(book.driveId, file.path)
        }
        books.markProbed(book.driveId, clock.now(), info.tags?.narrator())
    }

    private suspend fun probeMp4(drive: DriveService, fresh: BookEntity, bookTracks: List<TrackEntity>, first: TrackEntity) {
        val mp4 = Mp4Probe.probe(first.sizeBytes) { off, len ->
            val endInclusive = (off + len - 1L).coerceAtMost(first.sizeBytes - 1L)
            drive.openContent(first.driveId, off, endInclusive).readUpTo(len)
        }
        val firstDuration = mp4?.durationMs?.takeIf { it > 0 }
        if (firstDuration != null) {
            db.withTransaction {
                tracks.setExactDuration(first.driveId, firstDuration)
                if (bookTracks.size == 1) {
                    books.updateDuration(fresh.driveId, firstDuration, true)
                } else if (first.sizeBytes > 0) {
                    val bytesPerSecond = first.sizeBytes * 1000.0 / firstDuration
                    var total = 0L
                    var allExact = true
                    for (t in tracks.forBook(fresh.driveId)) {
                        if (t.durationExact && t.durationMs != null) {
                            total += t.durationMs!!
                        } else {
                            val estimate = (t.sizeBytes * 1000.0 / bytesPerSecond).toLong().coerceAtLeast(0)
                            tracks.updateEstimatedDuration(t.driveId, estimate)
                            total += estimate
                            allExact = false
                        }
                    }
                    books.updateDuration(fresh.driveId, total, allExact)
                }
            }
        }
        if (mp4 != null && mp4.chapters.size > 1) {
            db.embeddedChapterDao().replaceForTrack(
                first.driveId,
                mp4.chapters.mapIndexed { idx, ch -> EmbeddedChapterEntity(fresh.driveId, first.driveId, idx, ch.title, ch.startMs, ch.endMs) },
            )
        }
        val picture = mp4?.tags?.picture
        if (picture != null && fresh.coverPath?.let { File(it).exists() } != true) {
            val ext = if (mp4.tags?.pictureMime?.contains("png") == true) "png" else "jpg"
            val file = File(storage.coversDir(), "${fresh.driveId}.$ext")
            file.writeBytes(picture)
            books.setCoverPath(fresh.driveId, file.path)
        }
        books.markProbed(fresh.driveId, clock.now(), mp4?.tags?.narrator())
    }

    companion object {
        const val MAX_COVER_BYTES = 3 * 1024 * 1024
    }
}
