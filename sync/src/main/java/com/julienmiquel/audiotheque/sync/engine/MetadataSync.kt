package com.julienmiquel.audiotheque.sync.engine

import androidx.room.withTransaction
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import com.julienmiquel.audiotheque.sync.drive.DriveFile
import com.julienmiquel.audiotheque.sync.drive.DriveHttpException
import com.julienmiquel.audiotheque.sync.drive.DriveNotFoundException
import com.julienmiquel.audiotheque.sync.drive.DriveService
import com.julienmiquel.audiotheque.sync.drive.readSmallFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Corrections familiales partagées d'un livre : un petit fichier JSON (« .audiotheque-meta.json »)
 * dans le dossier Drive du livre (ou « .audiotheque-meta-<bookId>.json » dans le dossier parent
 * pour un fichier isolé / archive .zip).
 *
 * Lorsqu'un membre corrige un titre mal découpé, l'auteur, la voix, confirme le titre ou masque un
 * dossier parasite, tous les appareils et membres de la famille en bénéficient automatiquement
 * selon la règle *la correction la plus récente l'emporte* ([MetadataJson.updatedAt]).
 */
@Serializable
data class MetadataJson(
    val bookId: String,
    val customTitle: String? = null,
    val customAuthor: String? = null,
    val customNarrator: String? = null,
    val titleConfirmed: Boolean = false,
    val hidden: Boolean = false,
    val updatedAt: Long,
    val updatedBy: String? = null,
    val device: String? = null,
    val version: Int = 1,
) {
    companion object {
        const val MIME = "application/json"
        const val FOLDER_FILE_NAME = ".audiotheque-meta.json"
        private const val SINGLE_PREFIX = ".audiotheque-meta-"
        private const val SUFFIX = ".json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fileName(book: BookEntity): String =
            if (book.kind == BookKind.FOLDER) FOLDER_FILE_NAME else "$SINGLE_PREFIX${book.driveId}$SUFFIX"

        fun isMetadataFile(name: String): Boolean =
            name == FOLDER_FILE_NAME || (name.startsWith(SINGLE_PREFIX) && name.endsWith(SUFFIX))

        fun encode(meta: MetadataJson): ByteArray = json.encodeToString(serializer(), meta).toByteArray()

        fun decode(bytes: ByteArray): MetadataJson = json.decodeFromString(serializer(), String(bytes))
    }
}

class MetadataSync(
    private val db: AppDatabase,
    private val history: HistoryLogger,
    private val clock: Clock,
    private val deviceName: String,
) {
    private val mutex = Mutex()
    private val books get() = db.bookDao()
    private val refused = ConcurrentHashMap.newKeySet<String>()

    /**
     * Synchronise toutes les corrections de métadonnées partagées :
     * - 1 requête `findByName(".audiotheque-meta.json")` pour tous les dossiers de livres ;
     * - 1 requête `findByNamePrefix(".audiotheque-meta-")` s'il existe des livres isolés / `.zip` ;
     * - envoi des livres modifiés localement (`metadataDirty = true`).
     */
    suspend fun run(drive: DriveService, account: String?, runId: Long? = null): Int = mutex.withLock {
        val remoteByBook = remoteMetadataFiles(drive)
        val dirtyIds = books.dirtyMetadata().map { it.driveId }.toSet()
        var adoptedCount = 0
        for (id in remoteByBook.keys + dirtyIds) {
            val book = books.get(id) ?: continue
            val adopted = guarded(book, runId) {
                syncBook(drive, account, book, remoteByBook[id], write = true, runId = runId)
            }
            if (adopted == true) adoptedCount++
        }
        adoptedCount
    }

    /** Envoi léger immédiat après édition de la fiche d'un livre. */
    suspend fun push(drive: DriveService, account: String?, bookId: String?) = mutex.withLock {
        for (book in books.dirtyMetadata()) {
            if (bookId != null && book.driveId != bookId) continue
            guarded(book, null) { syncBook(drive, account, book, known = null, write = true, runId = null) }
        }
    }

    private suspend fun guarded(book: BookEntity, runId: Long?, block: suspend () -> Boolean): Boolean? {
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
                message = "Corrections de métadonnées non écrites dans Drive : ${e.message}",
                runId = runId,
            )
            null
        }
    }

    private suspend fun remoteMetadataFiles(drive: DriveService): Map<String, DriveFile> {
        val byBook = HashMap<String, DriveFile>()
        for (file in drive.findByName(MetadataJson.FOLDER_FILE_NAME)) {
            val bookId = file.parents.firstOrNull() ?: continue
            val prev = byBook[bookId]
            if (prev == null || (file.modifiedTime ?: 0L) > (prev.modifiedTime ?: 0L)) byBook[bookId] = file
        }
        val hasSingleBooks = books.all().any { it.kind != BookKind.FOLDER }
        if (hasSingleBooks) {
            for (file in drive.findByNamePrefix(".audiotheque-meta-")) {
                val bookId = file.name.removePrefix(".audiotheque-meta-").removeSuffix(".json").takeIf { it.isNotBlank() } ?: continue
                val prev = byBook[bookId]
                if (prev == null || (file.modifiedTime ?: 0L) > (prev.modifiedTime ?: 0L)) byBook[bookId] = file
            }
        }
        return byBook
    }

    private suspend fun locate(drive: DriveService, book: BookEntity): DriveFile? {
        book.metadataRemoteFileId?.let { id ->
            try {
                return drive.getFile(id).takeIf { !it.trashed }
            } catch (e: DriveNotFoundException) {
                // Supprimé : recherché ci-dessous.
            }
        }
        val folderId = if (book.kind == BookKind.FOLDER) book.driveId else book.parentId
        return drive.findChildren(folderId, MetadataJson.fileName(book)).maxByOrNull { it.modifiedTime ?: 0L }
    }

    private suspend fun read(drive: DriveService, file: DriveFile): MetadataJson? = try {
        MetadataJson.decode(drive.readSmallFile(file.id))
    } catch (e: CancellationException) {
        throw e
    } catch (e: DriveNotFoundException) {
        null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private suspend fun write(drive: DriveService, book: BookEntity, fileId: String?, payload: MetadataJson): DriveFile {
        val bytes = MetadataJson.encode(payload)
        if (fileId != null) {
            try {
                return drive.updateContent(fileId, bytes, MetadataJson.MIME)
            } catch (e: DriveNotFoundException) {
                // Recréé ci-dessous.
            }
        }
        val folderId = if (book.kind == BookKind.FOLDER) book.driveId else book.parentId
        return drive.createFile(MetadataJson.fileName(book), folderId, bytes, MetadataJson.MIME)
    }

    private suspend fun syncBook(
        drive: DriveService,
        account: String?,
        book: BookEntity,
        known: DriveFile?,
        write: Boolean,
        runId: Long?,
    ): Boolean {
        val file = known ?: locate(drive, book)
        var adopted = false
        if (file != null) {
            val remote = read(drive, file)
            if (remote != null) {
                adopted = reconcile(book.driveId, remote, file.id, runId)
            }
        }
        val fresh = books.get(book.driveId) ?: return adopted
        if (write && fresh.metadataDirty) {
            val payload = MetadataJson(
                bookId = fresh.driveId,
                customTitle = fresh.customTitle,
                customAuthor = fresh.customAuthor,
                customNarrator = fresh.customNarrator,
                titleConfirmed = fresh.titleConfirmed,
                hidden = fresh.hidden,
                updatedAt = fresh.metadataUpdatedAt.takeIf { it > 0 } ?: clock.now(),
                updatedBy = account,
                device = deviceName,
            )
            val written = write(drive, fresh, file?.id ?: fresh.metadataRemoteFileId, payload)
            books.markMetadataSynced(fresh.driveId, fresh.metadataUpdatedAt, written.id)
        }
        return adopted
    }

    private suspend fun reconcile(bookId: String, remote: MetadataJson, fileId: String, runId: Long?): Boolean {
        var adopted = false
        var adoptedBook: BookEntity? = null
        db.withTransaction {
            val local = books.get(bookId) ?: return@withTransaction
            when {
                remote.updatedAt > local.metadataUpdatedAt -> {
                    val shownTitle = remote.customTitle?.takeIf { it.isNotBlank() } ?: local.parsedTitle
                    val shownAuthor = (remote.customAuthor ?: local.parsedAuthor)?.takeIf { it.isNotBlank() }
                    books.applySyncedMetadata(
                        id = bookId,
                        title = remote.customTitle,
                        author = remote.customAuthor,
                        narrator = remote.customNarrator,
                        confirmed = remote.titleConfirmed,
                        hidden = remote.hidden,
                        key = TextNormalizer.key(shownTitle),
                        authorKey = TextNormalizer.keyOrNull(shownAuthor),
                        updatedAt = remote.updatedAt,
                        dirty = false,
                        remoteFileId = fileId,
                    )
                    adopted = true
                    adoptedBook = books.get(bookId)
                }
                remote.updatedAt == local.metadataUpdatedAt -> {
                    books.markMetadataSynced(bookId, local.metadataUpdatedAt, fileId)
                }
                else -> {
                    // Correction locale plus récente : à (ré)envoyer dans ce fichier.
                    books.applySyncedMetadata(
                        id = bookId,
                        title = local.customTitle,
                        author = local.customAuthor,
                        narrator = local.customNarrator,
                        confirmed = local.titleConfirmed,
                        hidden = local.hidden,
                        key = local.normalizedKey,
                        authorKey = local.authorKey,
                        updatedAt = local.metadataUpdatedAt,
                        dirty = true,
                        remoteFileId = fileId,
                    )
                }
            }
        }
        if (adopted && adoptedBook != null) {
            history.log(
                type = SyncEventType.LIBRARY_CHANGE,
                path = adoptedBook!!.name,
                book = adoptedBook,
                message = "Métadonnées partagées reprises depuis ${remote.device ?: remote.updatedBy ?: "Drive"}",
                runId = runId,
            )
        }
        return adopted
    }
}
