package com.julienmiquel.audiotheque.sync.engine

import androidx.room.withTransaction
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.model.BookKind
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
 * Progression par utilisateur dans le dossier Drive de chaque livre : un petit fichier JSON par
 * compte (« .audiotheque-progress-<compte>.json »), à côté des pistes. Il suit l'utilisateur
 * d'un téléphone à l'autre sans mélanger les positions des membres de la famille (un fichier
 * par compte), et il suit le livre quand il change de statut (le dossier garde son ID).
 *
 * Règle unique, dans les deux sens : **la position enregistrée le plus récemment l'emporte**
 * (date d'enregistrement sur le téléphone, [ProgressJson.updatedAt]). Une position locale plus
 * ancienne n'écrase jamais celle qu'un autre téléphone vient d'écrire ; elle est remplacée.
 *
 * Trois usages : la passe complète en fin de synchro ([run]), et les échanges légers du lecteur
 * ([push] après une pause, [pull] juste avant une lecture). Tous passent par le même verrou :
 * jamais deux écritures croisées pour un même livre. Les dates « déjà vu » sont celles de Drive
 * (modifiedTime), jamais l'horloge du téléphone : un téléphone en retard ne manque rien.
 */
class ProgressSync(
    private val db: AppDatabase,
    private val history: HistoryLogger,
    private val clock: Clock,
    private val deviceName: String,
) {
    private val mutex = Mutex()
    private val progress get() = db.progressDao()
    private val books get() = db.bookDao()

    /** Dernière relecture Drive par livre (lecteur) : pas deux fois en moins de [PULL_COOLDOWN_MS]. */
    private val recentPulls = ConcurrentHashMap<String, Long>()

    /**
     * Fin de synchro (ou retour au premier plan) : tout ce qui a changé dans Drive est relu, tout
     * ce qui est à envoyer part. Une seule requête Drive liste les fichiers de progression du
     * compte dans tous les dossiers de livres. Retourne les positions reprises d'un autre appareil.
     */
    suspend fun run(drive: DriveService, account: String, runId: Long? = null): List<RemoteProgress> = mutex.withLock {
        val remote = remoteFolderFiles(drive, account)
        val dirtyIds = progress.dirty(account).map { it.bookId }.toSet()
        val adopted = mutableListOf<RemoteProgress>()
        for (id in remote.keys + dirtyIds) {
            val book = books.get(id) ?: continue
            if (book.kind == BookKind.ZIP) continue
            // Livre sans dossier : absent de la requête globale, localisé un par un, seulement s'il a quelque chose à envoyer.
            if (id !in remote && book.kind != BookKind.FOLDER && id !in dirtyIds) continue
            guarded(book, runId) { syncBook(drive, account, book, remote[id], write = true, runId = runId) }?.let { adopted += it }
        }
        adopted
    }

    /** Envoi léger (pause du lecteur, arrière-plan) : [bookId] seul, ou tout ce qui est à envoyer. */
    suspend fun push(drive: DriveService, account: String, bookId: String?) = mutex.withLock {
        for (local in progress.dirty(account)) {
            if (bookId != null && local.bookId != bookId) continue
            val book = books.get(local.bookId) ?: continue
            if (book.kind == BookKind.ZIP) continue
            guarded(book, null) { syncBook(drive, account, book, known = null, write = true, runId = null) }
        }
    }

    /** Livres dont Drive a refusé l'écriture (dossier en lecture seule…) : pas de nouvel essai avant le prochain lancement. */
    private val refused = ConcurrentHashMap.newKeySet<String>()

    /**
     * Un refus de Drive pour un livre (403, dossier d'un autre membre en lecture seule) ne doit
     * pas faire échouer toute la passe : journalisé une fois, livre suivant. Une erreur réseau,
     * elle, remonte : la synchro la signale et tout sera repris.
     */
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
     * téléphone en a enregistré une plus récente. Sans requête si la position locale vient
     * d'être enregistrée sur ce téléphone, ou si Drive a été relu il y a moins de 30 s.
     */
    suspend fun pull(drive: DriveService, account: String, bookId: String): RemoteProgress? = mutex.withLock {
        val book = books.get(bookId) ?: return@withLock null
        if (book.kind == BookKind.ZIP) return@withLock null
        val now = clock.now()
        val local = progress.get(bookId, account)
        if (local != null && local.dirty && now - local.updatedAt in 0 until LOCAL_FRESH_MS) return@withLock null
        if (now - (recentPulls[bookId] ?: Long.MIN_VALUE) in 0 until PULL_COOLDOWN_MS) return@withLock null
        val result = syncBook(drive, account, book, known = null, write = false, runId = null)
        recentPulls[bookId] = clock.now()
        result
    }

    // ------------------------------------------------------------------ un livre

    /**
     * Un livre : relit le fichier Drive s'il a changé depuis la dernière fois (ou s'il est
     * inconnu), tranche par la date d'enregistrement, puis envoie la position locale si elle
     * est à envoyer et [write]. Retourne la position adoptée depuis un autre appareil.
     */
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
            // Fichier localisé à l'instant, sinon nouveau fichier (l'ancien a été supprimé ou mis à la corbeille).
            val written = write(drive, account, book, file?.id, payload(local, book, account))
            // Sans effet si le lecteur a enregistré une position plus récente entre-temps.
            progress.markSynced(book.driveId, account, local.updatedAt, written.id, written.modifiedTime ?: clock.now())
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

    /** Fichier de progression d'un livre : dans son dossier, ou (piste isolée) dans son dossier de statut. */
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

    private fun payload(local: ProgressEntity, book: BookEntity, account: String) = ProgressJson(
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
        account = account,
        title = book.displayTitle,
    )

    /**
     * Tranche entre la position de Drive et la position locale : la plus récente l'emporte.
     * Relu dans une transaction : une position enregistrée par le lecteur pendant la lecture du
     * JSON est prise en compte. Retourne la position adoptée si elle change l'écoute.
     */
    private suspend fun reconcile(json: ProgressJson, file: DriveFile, account: String, book: BookEntity, runId: Long?): RemoteProgress? {
        val remoteModified = file.modifiedTime ?: 0L
        var adopted: RemoteProgress? = null
        db.withTransaction {
            val local = progress.get(book.driveId, account)
            when {
                local == null || json.updatedAt > local.updatedAt -> {
                    progress.upsert(
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
                            dirty = false,
                            remoteFileId = file.id,
                            remoteSyncedAt = remoteModified,
                        ),
                    )
                    if (local == null || movesListening(local, json)) {
                        adopted = RemoteProgress(book.driveId, json.device, json.percent, json.finished)
                    }
                }
                json.updatedAt == local.updatedAt ->
                    // Même enregistrement (le nôtre, relu) : rien à envoyer.
                    progress.markSynced(book.driveId, account, local.updatedAt, file.id, remoteModified)
                local.remoteFileId != file.id || !local.dirty ->
                    // Position locale plus récente que celle de Drive : à (ré)envoyer dans ce fichier.
                    progress.upsert(local.copy(dirty = true, remoteFileId = file.id))
                else -> Unit
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
