package com.julienmiquel.audiotheque.sync.engine

import androidx.room.withTransaction
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.PendingOperationEntity
import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.OperationType
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.parse.FolderNameParser
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import com.julienmiquel.audiotheque.sync.drive.DriveFile
import com.julienmiquel.audiotheque.sync.drive.DriveHttpException
import com.julienmiquel.audiotheque.sync.drive.DriveNotFoundException
import com.julienmiquel.audiotheque.sync.drive.DriveService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rejoue la file d'opérations : chaque geste de statut est un déplacement de dossier dans Drive
 * (files.update avec addParents / removeParents) ; la suppression d'un doublon est une mise à la
 * corbeille Drive. Exécutée aussitôt si le réseau est là, sinon au retour du réseau, avec des
 * nouvelles tentatives espacées.
 *
 * Un déplacement en échec ne modifie pas l'état local confirmé : en cas de refus définitif
 * (droits, dossier supprimé), le geste est abandonné et le livre reste dans son statut Drive.
 */
class OperationExecutor(
    private val db: AppDatabase,
    private val history: HistoryLogger,
    private val clock: Clock,
    private val localFiles: LocalFiles,
) {
    private val ops = db.pendingOperationDao()
    private val books = db.bookDao()

    /** Retourne vrai si la file est vide à la fin. */
    suspend fun flush(drive: DriveService, ctx: RunContext?): Boolean {
        val pairs = db.syncPairDao().all()
        val pairFolders = pairs.map { it.driveFolderId }.toSet()
        for (op in ops.due(clock.now())) {
            val book = books.get(op.bookId)
            if (book == null) {
                ops.delete(op.id)
                continue
            }
            try {
                val file = drive.getFile(op.bookId)
                if (op.type == OperationType.TRASH_BOOK) {
                    executeTrash(drive, op, book, file, pairs, pairFolders, ctx)
                    continue
                }
                if (op.type == OperationType.RENAME_BOOK) {
                    executeRename(drive, op, book, file, pairs, ctx)
                    continue
                }
                if (file.trashed) throw DriveNotFoundException("Le dossier est dans la corbeille")
                val parents = file.parents
                val otherStatusParents = parents.filter { it != op.toParentId && (it in pairFolders || it == op.fromParentId) }
                when {
                    op.toParentId in parents && otherStatusParents.isEmpty() -> onSuccess(op, book, pairs, ctx, movedFrom = null)
                    // Drive fait foi : le dossier a été déplacé ailleurs depuis le geste (web, autre
                    // membre de la famille). On ne le déplace pas de force : le geste est abandonné.
                    op.fromParentId !in parents && op.toParentId !in parents -> onConflict(op, book, pairs, parents, ctx)
                    else -> {
                        drive.move(op.bookId, op.toParentId, otherStatusParents)
                        onSuccess(op, book, pairs, ctx, movedFrom = otherStatusParents.firstOrNull() ?: op.fromParentId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthException) {
                throw e
            } catch (e: DriveNotFoundException) {
                when (op.type) {
                    // Déjà disparu de Drive : la suppression est faite, le livre quitte la bibliothèque.
                    OperationType.TRASH_BOOK -> onTrashed(op, book, pairs, ctx, alreadyGone = true)
                    OperationType.RENAME_BOOK -> onRenameRefused(op, book, e, ctx)
                    OperationType.MOVE_BOOK -> onPermanentFailure(op, book, pairs, e, ctx)
                }
            } catch (e: DriveHttpException) {
                if (e.isTransient) {
                    reschedule(op, book, e, ctx)
                    return false
                }
                when (op.type) {
                    OperationType.TRASH_BOOK -> onTrashRefused(op, book, e, ctx)
                    OperationType.RENAME_BOOK -> onRenameRefused(op, book, e, ctx)
                    OperationType.MOVE_BOOK -> onPermanentFailure(op, book, pairs, e, ctx)
                }
            } catch (e: IOException) {
                // Réseau indisponible : inutile d'essayer les opérations suivantes maintenant.
                reschedule(op, book, e, ctx)
                return false
            }
        }
        return ops.all().isEmpty()
    }

    // ------------------------------------------------------------------ renommage Drive

    private suspend fun executeRename(
        drive: DriveService,
        op: PendingOperationEntity,
        book: BookEntity,
        file: DriveFile,
        pairs: List<SyncPairEntity>,
        ctx: RunContext?,
    ) {
        if (file.trashed) throw DriveNotFoundException("Le dossier est dans la corbeille")
        val targetName = op.targetName?.trim()?.takeIf { it.isNotEmpty() }
        if (targetName == null) {
            db.withTransaction {
                ops.delete(op.id)
                if (ops.latestOfType(op.bookId, OperationType.RENAME_BOOK) == null) {
                    books.setPendingName(op.bookId, null)
                }
            }
            return
        }
        val previousName = file.name
        val updatedFile = if (file.name == targetName) file else drive.rename(op.bookId, targetName)
        val parsed = FolderNameParser.parse(updatedFile.name, isFile = book.kind != BookKind.FOLDER)
        db.withTransaction {
            books.updateDriveName(
                op.bookId,
                updatedFile.name,
                parsed.title,
                parsed.author,
                parsed.narrator,
                TextNormalizer.key(parsed.title),
                TextNormalizer.keyOrNull(parsed.author),
            )
            books.updateModifiedAt(op.bookId, updatedFile.modifiedTime)
            val deleted = ops.deleteIfRenameTarget(op.id, targetName)
            if (deleted > 0 && ops.latestOfType(op.bookId, OperationType.RENAME_BOOK) == null) {
                books.setPendingName(op.bookId, null)
            }
        }
        if (previousName != updatedFile.name) {
            history.log(
                type = SyncEventType.LIBRARY_CHANGE,
                path = "${pathOf(book.parentId, pairs)}/${updatedFile.name}",
                book = book,
                message = "Renommé dans Drive : « $previousName » → « ${updatedFile.name} »",
                runId = ctx?.runId,
            )
            ctx?.moves?.incrementAndGet()
        }
    }

    private suspend fun onRenameRefused(op: PendingOperationEntity, book: BookEntity, e: DriveHttpException, ctx: RunContext?) {
        db.withTransaction {
            ops.delete(op.id)
            if (ops.latestOfType(op.bookId, OperationType.RENAME_BOOK) == null) {
                books.setPendingName(op.bookId, null)
            }
        }
        history.log(
            type = SyncEventType.ERROR,
            path = book.name,
            book = book,
            result = EventResult.ERROR,
            message = "Renommage refusé par Drive (${e.code}) : le livre conserve le nom « ${book.name} ».",
            runId = ctx?.runId,
        )
        ctx?.errors?.incrementAndGet()
    }

    // ------------------------------------------------------------------ corbeille (doublons)

    private suspend fun executeTrash(
        drive: DriveService,
        op: PendingOperationEntity,
        book: BookEntity,
        file: DriveFile,
        pairs: List<SyncPairEntity>,
        pairFolders: Set<String>,
        ctx: RunContext?,
    ) {
        when {
            file.trashed -> onTrashed(op, book, pairs, ctx, alreadyGone = true)
            // Drive fait foi : déplacé hors de la bibliothèque entre-temps (autre membre de la
            // famille) : on ne supprime pas un dossier qui n'est plus le nôtre.
            file.parents.none { it in pairFolders } -> {
                db.withTransaction {
                    ops.delete(op.id)
                    books.setTrashPending(op.bookId, false)
                }
                history.log(
                    type = SyncEventType.ERROR,
                    path = book.name,
                    book = book,
                    result = EventResult.ERROR,
                    message = "Déplacé hors de la bibliothèque entre-temps : suppression abandonnée, Drive fait foi.",
                    runId = ctx?.runId,
                )
                ctx?.errors?.incrementAndGet()
            }
            else -> {
                drive.trash(op.bookId)
                onTrashed(op, book, pairs, ctx, alreadyGone = false)
            }
        }
    }

    /** Dossier à la corbeille Drive : fichiers locaux supprimés, livre retiré de la base (la progression reste). */
    private suspend fun onTrashed(op: PendingOperationEntity, book: BookEntity, pairs: List<SyncPairEntity>, ctx: RunContext?, alreadyGone: Boolean) {
        val freed = localFiles.deleteBookFiles(book)
        db.withTransaction {
            ops.deleteForBook(op.bookId)
            books.delete(op.bookId)
        }
        if (freed > 0) {
            ctx?.filesDeleted?.incrementAndGet()
            ctx?.bytesDeleted?.addAndGet(freed)
        }
        history.log(
            type = SyncEventType.LIBRARY_CHANGE,
            path = "${pathOf(book.parentId, pairs)}/${book.name}",
            book = book,
            sizeBytes = freed.takeIf { it > 0 },
            message = if (alreadyGone) "Doublon déjà retiré de Drive : retiré de la bibliothèque" else "Doublon mis à la corbeille Drive (restaurable 30 jours depuis Drive)",
            runId = ctx?.runId,
        )
        ctx?.moves?.incrementAndGet()
    }

    /** Refus définitif de Drive : le livre réapparaît dans la bibliothèque. */
    private suspend fun onTrashRefused(op: PendingOperationEntity, book: BookEntity, e: DriveHttpException, ctx: RunContext?) {
        db.withTransaction {
            ops.delete(op.id)
            books.setTrashPending(op.bookId, false)
        }
        history.log(
            type = SyncEventType.ERROR,
            path = book.name,
            book = book,
            result = EventResult.ERROR,
            message = "Suppression refusée par Drive (${e.code}) : le livre reste dans la bibliothèque.",
            runId = ctx?.runId,
        )
        ctx?.errors?.incrementAndGet()
    }

    /** [movedFrom] : dossier quitté dans Drive, ou null si Drive avait déjà la valeur attendue. */
    private suspend fun onSuccess(
        op: PendingOperationEntity,
        book: BookEntity,
        pairs: List<SyncPairEntity>,
        ctx: RunContext?,
        movedFrom: String?,
    ) {
        db.withTransaction {
            // Statut confirmé (et sa date, base des délais de nettoyage) mis à jour seulement si
            // le dossier a vraiment changé : un geste annulé avant la synchro ne touche à rien.
            if (movedFrom != null || book.parentId != op.toParentId) {
                books.updateConfirmedLocation(op.bookId, op.toParentId, op.toStatus, op.createdAt)
            }
            val deleted = ops.deleteIfTarget(op.id, op.toParentId)
            if (deleted > 0) {
                if (ops.latestOfType(op.bookId, OperationType.MOVE_BOOK) == null) books.setPending(op.bookId, null, null, null)
            } else {
                // Geste modifié pendant l'appel (« Annuler », autre statut) : l'opération reste en
                // file avec sa nouvelle cible, au départ du dossier où le livre vient d'arriver.
                ops.get(op.id)?.let { ops.update(it.copy(fromParentId = op.toParentId, fromStatus = op.toStatus)) }
            }
        }
        if (movedFrom != null && movedFrom != op.toParentId) {
            history.log(
                type = SyncEventType.DRIVE_MOVE,
                path = "${pathOf(movedFrom, pairs)} → ${pathOf(op.toParentId, pairs)}",
                book = book,
                message = "${nameOf(movedFrom, pairs)} → ${nameOf(op.toParentId, pairs)}",
                runId = ctx?.runId,
            )
            ctx?.moves?.incrementAndGet()
        }
    }

    private suspend fun onPermanentFailure(
        op: PendingOperationEntity,
        book: BookEntity,
        pairs: List<SyncPairEntity>,
        e: DriveHttpException,
        ctx: RunContext?,
    ) {
        abandon(op)
        history.log(
            type = SyncEventType.ERROR,
            path = "${pathOf(op.fromParentId, pairs)} → ${pathOf(op.toParentId, pairs)}",
            book = book,
            result = EventResult.ERROR,
            message = "Déplacement refusé par Drive (${e.code}) : le livre reste dans ${nameOf(book.parentId, pairs)}.",
            runId = ctx?.runId,
        )
        ctx?.errors?.incrementAndGet()
    }

    private suspend fun onConflict(
        op: PendingOperationEntity,
        book: BookEntity,
        pairs: List<SyncPairEntity>,
        parents: List<String>,
        ctx: RunContext?,
    ) {
        abandon(op)
        val where = parents.firstNotNullOfOrNull { parent -> pairs.firstOrNull { it.driveFolderId == parent }?.name }
            ?: "un dossier hors de la bibliothèque"
        history.log(
            type = SyncEventType.ERROR,
            path = "${pathOf(op.fromParentId, pairs)} → ${pathOf(op.toParentId, pairs)}",
            book = book,
            result = EventResult.ERROR,
            message = "Déplacé entre-temps dans Drive (vers $where) : le geste vers ${nameOf(op.toParentId, pairs)} est abandonné, Drive fait foi.",
            runId = ctx?.runId,
        )
        ctx?.errors?.incrementAndGet()
    }

    /**
     * Geste abandonné : le livre garde son statut Drive et le geste n'a pas d'effet local. Un
     * « Marquer écouté » qui n'a pas abouti ne laisse pas « terminé » sur un livre pas fini.
     */
    private suspend fun abandon(op: PendingOperationEntity) {
        db.withTransaction {
            ops.delete(op.id)
            if (ops.latestOfType(op.bookId, OperationType.MOVE_BOOK) == null) books.setPending(op.bookId, null, null, null)
            if (op.toStatus == BookStatus.DONE) db.progressDao().clearFinishedUnlessAtEnd(op.bookId, clock.now())
        }
    }

    private suspend fun reschedule(op: PendingOperationEntity, book: BookEntity, e: Exception, ctx: RunContext?) {
        val attempts = op.attempts + 1
        val delay = retryDelayMs(attempts)
        ops.update(op.copy(attempts = attempts, nextAttemptAt = clock.now() + delay, lastError = e.message))
        if (attempts == 1) {
            val action = when (op.type) {
                OperationType.RENAME_BOOK -> "Renommage"
                OperationType.TRASH_BOOK -> "Suppression"
                OperationType.MOVE_BOOK -> "Déplacement"
            }
            history.log(
                type = SyncEventType.ERROR,
                path = book.name,
                book = book,
                result = EventResult.ERROR,
                message = "$action en attente (${e.message ?: "réseau"}) : nouvel essai dans ${Formats.elapsed(delay)}.",
                runId = ctx?.runId,
            )
        }
    }

    companion object {
        /** 30 s, 1 min, 2 min, 4 min… plafonné à 1 h. */
        fun retryDelayMs(attempts: Int): Long =
            (30_000L shl (attempts - 1).coerceIn(0, 7)).coerceAtMost(3_600_000L)

        fun pathOf(folderId: String, pairs: List<SyncPairEntity>): String =
            pairs.firstOrNull { it.driveFolderId == folderId }?.drivePath ?: folderId

        fun nameOf(folderId: String, pairs: List<SyncPairEntity>): String =
            pairs.firstOrNull { it.driveFolderId == folderId }?.name ?: "un autre dossier"
    }
}

/** Suppression des fichiers locaux d'une piste (fichier et « .part »), toujours hors du thread principal. */
class LocalFiles(private val db: AppDatabase, private val storage: LocalStorage) {

    /** Retourne le nombre d'octets libérés. */
    suspend fun deleteTrackFiles(track: TrackEntity, updateDb: Boolean = true): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        track.localPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                freed += file.length()
                file.delete()
            }
            val part = File("$path.part")
            if (part.exists()) {
                freed += part.length()
                part.delete()
            }
            file.parentFile?.let { deleteEmptyDirs(it) }
        }
        if (updateDb) db.trackDao().setLocalState(track.driveId, TrackState.ABSENT, null, 0)
        freed
    }

    /** Toutes les pistes d'un livre et sa pochette locale ; retourne les octets libérés (pistes). */
    suspend fun deleteBookFiles(book: BookEntity): Long {
        var freed = 0L
        db.trackDao().forBook(book.driveId).forEach { freed += deleteTrackFiles(it, updateDb = false) }
        book.coverPath?.let { path ->
            if (path.startsWith(storage.coversDir().path)) withContext(Dispatchers.IO) { File(path).delete() }
        }
        return freed
    }

    /** Remonte en supprimant les dossiers vides, sans jamais toucher à la racine Audiobooks. */
    suspend fun deleteEmptyDirs(start: File) {
        val roots = setOf(storage.root().canonicalPath, storage.privateRoot().canonicalPath)
        var dir: File? = start
        while (dir != null && dir.isDirectory && dir.canonicalPath !in roots && dir.list()?.isEmpty() == true) {
            dir.delete()
            dir = dir.parentFile
        }
    }
}
