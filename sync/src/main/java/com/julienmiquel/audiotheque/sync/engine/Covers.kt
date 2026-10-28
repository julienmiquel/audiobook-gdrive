package com.julienmiquel.audiotheque.sync.engine

import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import com.julienmiquel.audiotheque.sync.drive.DriveHttpException
import com.julienmiquel.audiotheque.sync.drive.DriveService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Pochettes téléchargées en ligne (Google Books, Open Library) : déposées dans le dossier Drive
 * du livre sous le nom « cover.jpg » (ou « cover.png »), pour les autres téléphones — qui la
 * reçoivent comme n'importe quelle image du dossier — et pour Drive lui-même. Jamais si le
 * dossier a déjà une image, jamais pour un livre sans dossier.
 */
class CoverUploader(
    private val db: AppDatabase,
    private val history: HistoryLogger,
) {
    private val books = db.bookDao()

    /** Dépose toutes les pochettes en attente ; retourne le nombre de fichiers créés. */
    suspend fun run(drive: DriveService, ctx: RunContext?): Int {
        var uploaded = 0
        for (book in books.withCoverToUpload()) {
            try {
                if (upload(drive, book, ctx)) uploaded++
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthException) {
                throw e
            } catch (e: IOException) {
                if (e !is DriveHttpException) throw e // réseau : on arrête, la pochette reste en attente
                // Refus de Drive (dossier en lecture seule…) : on n'insiste pas.
                books.setCoverUploadPending(book.driveId, false)
                history.log(SyncEventType.ERROR, book.name, book, result = EventResult.ERROR, message = "Pochette non déposée dans Drive : ${e.message}", runId = ctx?.runId)
                ctx?.errors?.incrementAndGet()
            }
        }
        return uploaded
    }

    /** Dépose la pochette locale du livre si son dossier Drive n'a pas d'image ; vrai si un fichier a été créé. */
    suspend fun upload(drive: DriveService, book: BookEntity, ctx: RunContext?): Boolean {
        val file = book.coverPath?.let(::File)
        if (book.kind != BookKind.FOLDER || file == null || !file.exists() || file.length() > MAX_COVER_BYTES || file.length() == 0L) {
            books.setCoverUploadPending(book.driveId, false)
            return false
        }
        if (drive.listChildren(book.driveId).any { it.isImage }) {
            books.setCoverUploadPending(book.driveId, false)
            return false
        }
        val bytes = file.readBytes()
        val png = isPng(bytes)
        val created = drive.createFile(if (png) "cover.png" else "cover.jpg", book.driveId, bytes, if (png) "image/png" else "image/jpeg")
        books.setCoverUploadPending(book.driveId, false)
        history.log(
            type = SyncEventType.LIBRARY_CHANGE,
            path = "${book.name}/${created.name}",
            book = book,
            sizeBytes = bytes.size.toLong(),
            message = "Pochette déposée dans le dossier Drive du livre",
            runId = ctx?.runId,
        )
        return true
    }

    companion object {
        const val MAX_COVER_BYTES = MetadataProber.MAX_COVER_BYTES

        fun isPng(bytes: ByteArray): Boolean =
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
    }
}
