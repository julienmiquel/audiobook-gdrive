package com.julienmiquel.audiotheque.sync.engine

import androidx.room.withTransaction
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.PendingOperationEntity
import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import com.julienmiquel.audiotheque.sync.drive.DriveHttpException
import com.julienmiquel.audiotheque.sync.drive.DriveNotFoundException
import com.julienmiquel.audiotheque.sync.drive.DriveService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Rejoue la file d'opérations : chaque geste de statut est un déplacement de dossier dans Drive
 * (files.update avec addParents / removeParents). Exécutée aussitôt si le réseau est là, sinon
 * au retour du réseau, avec des nouvelles tentatives espacées.
 *
 * Un déplacement en échec ne modifie pas l'état local confirmé : en cas de refus définitif
 * (droits, dossier supprimé), le geste est abandonné et le livre reste dans son statut Drive.
 */
class OperationExecutor(
    private val db: AppDatabase,
    private val history: HistoryLogger,
    private val clock: Clock,
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
                if (file.trashed) throw DriveNotFoundException("Le dossier est dans la corbeille")
                val otherStatusParents = file.parents.filter { it != op.toParentId && (it in pairFolders || it == op.fromParentId) }
                val alreadyDone = op.toParentId in file.parents && otherStatusParents.isEmpty()
                if (!alreadyDone) drive.move(op.bookId, op.toParentId, otherStatusParents)
                onSuccess(op, book, pairs, ctx, moved = !alreadyDone)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthException) {
                throw e
            } catch (e: DriveHttpException) {
                if (e.isTransient) {
                    reschedule(op, book, e, ctx)
                    return false
                }
                onPermanentFailure(op, book, pairs, e, ctx)
            } catch (e: IOException) {
                // Réseau indisponible : inutile d'essayer les opérations suivantes maintenant.
                reschedule(op, book, e, ctx)
                return false
            }
        }
        return ops.all().isEmpty()
    }

    private suspend fun onSuccess(
        op: PendingOperationEntity,
        book: BookEntity,
        pairs: List<SyncPairEntity>,
        ctx: RunContext?,
        moved: Boolean,
    ) {
        db.withTransaction {
            books.updateConfirmedLocation(op.bookId, op.toParentId, op.toStatus, op.createdAt)
            // Si le geste a été modifié pendant l'appel (annulation, autre statut), l'opération
            // reste en file avec sa nouvelle cible et le statut en attente est conservé.
            val deleted = ops.deleteIfTarget(op.id, op.toParentId)
            if (deleted > 0 && ops.latestForBook(op.bookId) == null) books.setPending(op.bookId, null, null, null)
        }
        if (moved && op.fromParentId != op.toParentId) {
            history.log(
                type = SyncEventType.DRIVE_MOVE,
                path = "${pathOf(op.fromParentId, pairs)} → ${pathOf(op.toParentId, pairs)}",
                book = book,
                message = "${nameOf(op.fromParentId, pairs)} → ${nameOf(op.toParentId, pairs)}",
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
        db.withTransaction {
            ops.delete(op.id)
            if (ops.latestForBook(op.bookId) == null) books.setPending(op.bookId, null, null, null)
        }
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

    private suspend fun reschedule(op: PendingOperationEntity, book: BookEntity, e: Exception, ctx: RunContext?) {
        val attempts = op.attempts + 1
        val delay = retryDelayMs(attempts)
        ops.update(op.copy(attempts = attempts, nextAttemptAt = clock.now() + delay, lastError = e.message))
        if (attempts == 1) {
            history.log(
                type = SyncEventType.ERROR,
                path = book.name,
                book = book,
                result = EventResult.ERROR,
                message = "Déplacement en attente (${e.message ?: "réseau"}) : nouvel essai dans ${Formats.elapsed(delay)}.",
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

/** Suppression des fichiers locaux d'une piste (fichier et « .part »). */
class LocalFiles(private val db: AppDatabase, private val storage: LocalStorage) {

    /** Retourne le nombre d'octets libérés. */
    suspend fun deleteTrackFiles(track: TrackEntity, updateDb: Boolean = true): Long {
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
