package com.julienmiquel.audiotheque.sync.engine

import androidx.room.withTransaction
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.BookmarkEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.core.sync.RemoteProgress
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import com.julienmiquel.audiotheque.sync.drive.DriveFile
import com.julienmiquel.audiotheque.sync.drive.DriveHttpException
import com.julienmiquel.audiotheque.sync.drive.DriveNotFoundException
import com.julienmiquel.audiotheque.sync.drive.DriveService
import com.julienmiquel.audiotheque.sync.drive.readSmallFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/**
 * Progression, statut personnel et signets par utilisateur dans le dossier Drive de chaque livre :
 * un petit fichier JSON par compte ou profil (« .audiotheque-progress-<compte>.json »), à côté des
 * pistes. Il suit l'auditeur d'un téléphone à l'autre sans mélanger les positions, les statuts
 * personnels ni les signets des membres de la famille, et il suit le livre quand il change de
 * dossier Drive (le dossier garde son ID).
 *
 * Règle de fusion granulaire sans perte :
 * 1. **Position / vitesse / preset audio** : l'enregistrement le plus récent ([ProgressJson.updatedAt]) l'emporte.
 * 2. **Statut personnel (mode familial)** : fusionné indépendamment selon [ProgressJson.personalStatusChangedAt].
 * 3. **Signets** : union par `syncId`, le plus récent ([BookmarkJson.updatedAt]) l'emportant y compris
 *    les suppressions (pierres tombales `deleted = true`).
 */
class ProgressSync(
    private val db: AppDatabase,
    private val history: HistoryLogger,
    private val clock: Clock,
    private val deviceName: String,
) {
    private val mutex = Mutex()
    private val progress get() = db.progressDao()
    private val bookmarks get() = db.bookmarkDao()
    private val books get() = db.bookDao()

    /** Dernière relecture Drive par livre (lecteur) : pas deux fois en moins de [PULL_COOLDOWN_MS]. */
    private val recentPulls = ConcurrentHashMap<String, Long>()

    /**
     * Fin de synchro (ou retour au premier plan / changement de profil) : tout ce qui a changé
     * dans Drive est relu, tout ce qui est à envoyer part. Une seule requête Drive liste les
     * fichiers de progression du profil dans tous les dossiers de livres.
     */
    suspend fun run(drive: DriveService, account: String, runId: Long? = null): List<RemoteProgress> = mutex.withLock {
        val remote = remoteFolderFiles(drive, account)
        val dirtyIds = progress.dirty(account).map { it.bookId }.toSet()
        val adopted = mutableListOf<RemoteProgress>()
        for (id in remote.keys + dirtyIds) {
            val book = books.get(id) ?: continue
            // Livre sans dossier : absent de la requête globale, localisé un par un, seulement s'il a quelque chose à envoyer ou un fichier connu.
            if (id !in remote && book.kind != BookKind.FOLDER && id !in dirtyIds) continue
            guarded(book, runId) { syncBook(drive, account, book, remote[id], write = true, runId = runId) }?.let { adopted += it }
        }
        adopted
    }

    /** Envoi léger (pause du lecteur, signet, geste familial) : [bookId] seul, ou tout ce qui est à envoyer. */
    suspend fun push(drive: DriveService, account: String, bookId: String?) = mutex.withLock {
        for (local in progress.dirty(account)) {
            if (bookId != null && local.bookId != bookId) continue
            val book = books.get(local.bookId) ?: continue
            guarded(book, null) { syncBook(drive, account, book, known = null, write = true, runId = null) }
        }
    }

    /**
     * Découvre en une seule requête Drive les clés de comptes/profils ayant déjà enregistré une
     * progression dans la bibliothèque partagée `/AudioBook`.
     */
    suspend fun discoverProfiles(drive: DriveService): List<String> {
        val allBooks = books.all()
        val folderBookIds = allBooks.filter { it.kind == BookKind.FOLDER }.map { it.driveId }.toSet()
        val singleBookIds = allBooks.filter { it.kind != BookKind.FOLDER }.map { it.driveId }
        val discovered = linkedSetOf<String>()
        for (file in drive.findByNamePrefix(ProgressJson.PREFIX)) {
            val parentId = file.parents.firstOrNull()
            if (parentId != null && parentId in folderBookIds) {
                ProgressJson.extractFolderAccount(file.name)?.let(discovered::add)
                continue
            }
            val body = file.name.removePrefix(ProgressJson.PREFIX).removeSuffix(ProgressJson.SUFFIX)
            val matchedSingle = singleBookIds.firstOrNull { id -> body.endsWith("-$id") }
            if (matchedSingle != null) {
                body.removeSuffix("-$matchedSingle").takeIf { it.isNotBlank() }?.let(discovered::add)
            } else if (folderBookIds.isEmpty() && body.isNotBlank()) {
                discovered.add(body)
            }
        }
        return discovered.sorted()
    }

    /** Livres dont Drive a refusé l'écriture (dossier en lecture seule…) : pas de nouvel essai avant le prochain lancement. */
    private val refused = ConcurrentHashMap.newKeySet<String>()

    private suspend fun guarded(book: BookEntity, runId: Long?, block: suspend () -> RemoteProgress?): RemoteProgress? {
        if (book.driveId in refused) return null
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthException) {
            throw e
        } catch (e: DriveHttpException) {
            if (e.isTransient) throw e
            refused += book.driveId
            history.log(
                type = SyncEventType.ERROR,
                path = book.name,
                book = book,
                result = EventResult.ERROR,
                message = "Progression non écrite dans le dossier Drive du livre : ${e.message}",
                runId = runId,
            )
            null
        }
    }

    /**
     * Réception légère, juste avant une lecture : la position du livre dans Drive, si un autre
     * téléphone en a enregistré une plus récente.
     */
    suspend fun pull(drive: DriveService, account: String, bookId: String): RemoteProgress? = mutex.withLock {
        val book = books.get(bookId) ?: return@withLock null
        val now = clock.now()
        val local = progress.get(bookId, account)
        if (local != null && local.dirty && now - local.updatedAt in 0 until LOCAL_FRESH_MS) return@withLock null
        if (now - (recentPulls["$account:$bookId"] ?: Long.MIN_VALUE) in 0 until PULL_COOLDOWN_MS) return@withLock null
        val result = syncBook(drive, account, book, known = null, write = false, runId = null)
        recentPulls["$account:$bookId"] = clock.now()
        result
    }

    // ------------------------------------------------------------------ un livre

    private data class Snapshot(
        val progress: ProgressEntity,
        val bookmarks: List<BookmarkEntity>,
    ) {
        val maxBookmarkAt: Long get() = bookmarks.maxOfOrNull { it.updatedAt } ?: 0L
    }

    private suspend fun syncBook(
        drive: DriveService,
        account: String,
        book: BookEntity,
        known: DriveFile?,
        write: Boolean,
        runId: Long?,
    ): RemoteProgress? {
        var local = progress.get(book.driveId, account)
        val file = known ?: locate(drive, account, book, local)
        var adopted: RemoteProgress? = null
        if (file != null && !alreadySeen(local, file)) {
            val json = read(drive, file)
            if (json != null) adopted = reconcile(json, file, account, book, runId)
            local = progress.get(book.driveId, account)
        }
        if (write && local != null && local.dirty) {
            val snap = db.withTransaction {
                val p = progress.get(book.driveId, account) ?: return@withTransaction null
                if (!p.dirty) return@withTransaction null
                Snapshot(p, bookmarks.allIncludingDeleted(book.driveId, account))
            }
            if (snap != null) {
                val written = write(drive, account, book, file?.id ?: snap.progress.remoteFileId, payload(snap, book, account))
                val syncedAt = written.modifiedTime ?: clock.now()
                db.withTransaction {
                    val cur = progress.get(book.driveId, account) ?: return@withTransaction
                    val curMaxBm = bookmarks.allIncludingDeleted(book.driveId, account).maxOfOrNull { it.updatedAt } ?: 0L
                    if (cur.updatedAt == snap.progress.updatedAt &&
                        cur.personalStatusChangedAt == snap.progress.personalStatusChangedAt &&
                        curMaxBm == snap.maxBookmarkAt
                    ) {
                        progress.upsert(cur.copy(dirty = false, remoteFileId = written.id, remoteSyncedAt = syncedAt))
                    } else {
                        progress.upsert(cur.copy(dirty = true, remoteFileId = written.id))
                    }
                }
            }
        }
        return adopted
    }

    /** Le fichier Drive est celui déjà lu ou écrit, et il n'a pas bougé depuis. */
    private fun alreadySeen(local: ProgressEntity?, file: DriveFile): Boolean {
        if (local == null || local.remoteFileId != file.id) return false
        val modified = file.modifiedTime ?: return false
        val seen = local.remoteSyncedAt ?: return false
        return modified <= seen
    }

    /** Fichiers de progression du compte dans tous les dossiers de livres, le plus récent par dossier. */
    private suspend fun remoteFolderFiles(drive: DriveService, account: String): Map<String, DriveFile> {
        val byBook = HashMap<String, DriveFile>()
        for (file in drive.findByName(ProgressJson.fileName(account))) {
            val bookId = file.parents.firstOrNull() ?: continue
            val previous = byBook[bookId]
            if (previous == null || (file.modifiedTime ?: 0L) > (previous.modifiedTime ?: 0L)) byBook[bookId] = file
        }
        return byBook
    }

    /** Fichier de progression d'un livre : dans son dossier, ou (piste isolée / .zip) dans son dossier de statut. */
    private suspend fun locate(drive: DriveService, account: String, book: BookEntity, local: ProgressEntity?): DriveFile? {
        if (book.kind == BookKind.FOLDER) {
            return drive.findChildren(book.driveId, ProgressJson.fileName(account)).maxByOrNull { it.modifiedTime ?: 0L }
        }
        local?.remoteFileId?.let { id ->
            try {
                return drive.getFile(id).takeIf { !it.trashed }
            } catch (e: DriveNotFoundException) {
                // Supprimé : recherché, puis recréé.
            }
        }
        return drive.findChildren(book.parentId, ProgressJson.fileName(account, book.driveId)).maxByOrNull { it.modifiedTime ?: 0L }
    }

    private suspend fun read(drive: DriveService, file: DriveFile): ProgressJson? = try {
        ProgressJson.decode(drive.readSmallFile(file.id))
    } catch (e: CancellationException) {
        throw e
    } catch (e: DriveNotFoundException) {
        null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private suspend fun write(drive: DriveService, account: String, book: BookEntity, fileId: String?, payload: ProgressJson): DriveFile {
        val bytes = ProgressJson.encode(payload)
        if (fileId != null) {
            try {
                return drive.updateContent(fileId, bytes, ProgressJson.MIME)
            } catch (e: DriveNotFoundException) {
                // Fichier supprimé dans Drive : recréé ci-dessous.
            }
        }
        val (folderId, name) = if (book.kind == BookKind.FOLDER) {
            book.driveId to ProgressJson.fileName(account)
        } else {
            book.parentId to ProgressJson.fileName(account, book.driveId)
        }
        return drive.createFile(name, folderId, bytes, ProgressJson.MIME)
    }

    private fun BookmarkEntity.effectiveSyncId(): String =
        syncId.ifBlank { "$trackIndex:$positionMs:$createdAt" }

    private fun payload(snap: Snapshot, book: BookEntity, account: String): ProgressJson {
        val local = snap.progress
        return ProgressJson(
            bookId = local.bookId,
            trackId = local.trackId,
            trackIndex = local.trackIndex,
            positionMs = local.positionMs,
            speed = local.speed,
            percent = local.percent,
            lastListenedAt = local.lastListenedAt,
            finished = local.finished,
            setAsideAtPercent = local.setAsideAtPercent,
            audioPreset = local.audioPreset,
            personalStatus = local.personalStatus?.name,
            personalStatusChangedAt = local.personalStatusChangedAt,
            bookmarks = snap.bookmarks.map { bm ->
                BookmarkJson(
                    syncId = bm.effectiveSyncId(),
                    trackId = bm.trackId,
                    trackIndex = bm.trackIndex,
                    positionMs = bm.positionMs.coerceAtLeast(0L),
                    note = bm.note,
                    createdAt = bm.createdAt,
                    updatedAt = bm.updatedAt.takeIf { it > 0 } ?: bm.createdAt,
                    deleted = bm.deleted,
                )
            },
            updatedAt = local.updatedAt,
            device = deviceName,
            account = account,
            title = book.displayTitle,
        )
    }

    /**
     * Fusionne la progression de Drive et la progression locale dans une transaction unique :
     * - position/vitesse/preset selon `updatedAt`,
     * - statut personnel selon `personalStatusChangedAt`,
     * - signets par union sur `syncId` selon `BookmarkJson.updatedAt`.
     */
    private suspend fun reconcile(json: ProgressJson, file: DriveFile, account: String, book: BookEntity, runId: Long?): RemoteProgress? {
        val remoteModified = file.modifiedTime ?: 0L
        var adopted: RemoteProgress? = null
        db.withTransaction {
            val local = progress.get(book.driveId, account)
            val localBms = bookmarks.allIncludingDeleted(book.driveId, account)
            var needsWriteBack = false

            // 1. Fusion des signets par syncId
            val localBySync = localBms.associateBy { it.effectiveSyncId() }
            val remoteBySync = json.bookmarks.associateBy { it.syncId }
            for (syncId in localBySync.keys + remoteBySync.keys) {
                val l = localBySync[syncId]
                val r = remoteBySync[syncId]
                when {
                    l == null && r != null -> {
                        bookmarks.insert(
                            BookmarkEntity(
                                bookId = book.driveId,
                                account = account,
                                syncId = r.syncId,
                                trackId = r.trackId,
                                trackIndex = r.trackIndex,
                                positionMs = r.positionMs,
                                note = r.note,
                                createdAt = r.createdAt,
                                updatedAt = r.updatedAt,
                                deleted = r.deleted,
                            ),
                        )
                    }
                    l != null && r == null -> {
                        if (l.syncId.isBlank()) bookmarks.update(l.copy(syncId = syncId, updatedAt = l.updatedAt.takeIf { it > 0 } ?: l.createdAt))
                        needsWriteBack = true
                    }
                    l != null && r != null -> {
                        val localAt = l.updatedAt.takeIf { it > 0 } ?: l.createdAt
                        when {
                            r.updatedAt > localAt -> bookmarks.update(
                                l.copy(
                                    syncId = r.syncId,
                                    trackId = r.trackId,
                                    trackIndex = r.trackIndex,
                                    positionMs = r.positionMs,
                                    note = r.note,
                                    updatedAt = r.updatedAt,
                                    deleted = r.deleted,
                                ),
                            )
                            localAt > r.updatedAt -> needsWriteBack = true
                        }
                    }
                }
            }

            // 2. Fusion indépendante du statut personnel
            val remotePersonalStatus = json.personalStatus?.let { name -> BookStatus.entries.firstOrNull { it.name == name } }
            val remoteStatusAt = json.personalStatusChangedAt ?: 0L
            val localStatusAt = local?.personalStatusChangedAt ?: 0L
            val (mergedPersonalStatus, mergedStatusAt) = when {
                remoteStatusAt > localStatusAt -> remotePersonalStatus to json.personalStatusChangedAt
                localStatusAt > remoteStatusAt -> {
                    needsWriteBack = true
                    local?.personalStatus to local?.personalStatusChangedAt
                }
                else -> (local?.personalStatus ?: remotePersonalStatus) to (local?.personalStatusChangedAt ?: json.personalStatusChangedAt)
            }

            // 3. Fusion de la position d'écoute
            val remoteWinsPosition = local == null || json.updatedAt > local.updatedAt
            if (local != null && local.updatedAt > json.updatedAt) {
                needsWriteBack = true
            }

            val merged = if (remoteWinsPosition) {
                ProgressEntity(
                    bookId = book.driveId,
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
                    dirty = needsWriteBack,
                    remoteFileId = file.id,
                    remoteSyncedAt = if (needsWriteBack) local?.remoteSyncedAt else remoteModified,
                    audioPreset = json.audioPreset,
                    personalStatus = mergedPersonalStatus,
                    personalStatusChangedAt = mergedStatusAt,
                )
            } else {
                local!!.copy(
                    personalStatus = mergedPersonalStatus,
                    personalStatusChangedAt = mergedStatusAt,
                    dirty = needsWriteBack,
                    remoteFileId = file.id,
                    remoteSyncedAt = if (needsWriteBack) local.remoteSyncedAt else remoteModified,
                )
            }
            progress.upsert(merged)
            if (remoteWinsPosition && (local == null || movesListening(local, json))) {
                adopted = RemoteProgress(book.driveId, json.device, json.percent, json.finished)
            }
        }
        adopted?.let { r ->
            history.log(
                type = SyncEventType.PROGRESS,
                path = book.name,
                book = book,
                message = "Position reprise depuis ${json.device ?: "un autre appareil"} : ${r.percent.roundToInt()} %" +
                    if (r.finished) " (terminé)" else "",
                runId = runId,
            )
        }
        return adopted
    }

    /** La position de Drive change ce que le lecteur reprendrait (pas seulement la vitesse). */
    private fun movesListening(local: ProgressEntity, json: ProgressJson): Boolean =
        local.trackIndex != json.trackIndex || abs(local.positionMs - json.positionMs) >= 1_000 ||
            local.finished != json.finished || (local.trackId != null && json.trackId != null && local.trackId != json.trackId)

    companion object {
        /** Position enregistrée sur ce téléphone il y a moins d'une minute : pas de relecture avant de lire. */
        const val LOCAL_FRESH_MS = 60_000L

        /** Pas deux relectures du même livre en moins de 30 s (ouverture du lecteur puis lecture). */
        const val PULL_COOLDOWN_MS = 30_000L
    }
}
