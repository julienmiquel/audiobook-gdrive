package com.julienmiquel.audiotheque.sync.drive

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** Fichier ou dossier Drive (champs utiles à l'app). */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String> = emptyList(),
    val size: Long? = null,
    val md5: String? = null,
    val createdTime: Long? = null,
    val modifiedTime: Long? = null,
    val trashed: Boolean = false,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME
    val isGoogleDoc: Boolean get() = mimeType.startsWith("application/vnd.google-apps.") && !isFolder
    val isAudio: Boolean
        get() = !isFolder && !isGoogleDoc && (mimeType.startsWith("audio/") || AUDIO_EXTENSION.containsMatchIn(name))
    val isZip: Boolean
        get() = !isFolder && (mimeType in ZIP_MIMES || name.endsWith(".zip", ignoreCase = true))
    val isImage: Boolean
        get() = !isFolder && (mimeType.startsWith("image/") || IMAGE_EXTENSION.containsMatchIn(name))

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val ZIP_MIMES = setOf("application/zip", "application/x-zip-compressed", "application/x-zip")
        private val AUDIO_EXTENSION =
            Regex("""\.(mp3|m4a|m4b|aac|ogg|oga|opus|flac|wav|wma)$""", RegexOption.IGNORE_CASE)
        private val IMAGE_EXTENSION = Regex("""\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)
    }
}

data class DriveChange(val fileId: String, val removed: Boolean, val file: DriveFile?)

data class ChangePage(
    val changes: List<DriveChange>,
    val nextPageToken: String?,
    val newStartPageToken: String?,
)

data class DriveAbout(
    val email: String?,
    val displayName: String?,
    val quotaLimit: Long?,
    val quotaUsage: Long?,
)

/**
 * Contenu d'un fichier en cours de lecture. [partial] est vrai si Drive a honoré l'en-tête Range
 * (réponse 206) : on peut alors reprendre un transfert interrompu.
 */
class DriveContent(
    val stream: InputStream,
    /** Octets annoncés pour cette réponse (-1 si inconnu). */
    val contentLength: Long,
    val partial: Boolean,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { stream.close() }
        onClose()
    }
}

/** Erreur HTTP de l'API Drive. */
open class DriveHttpException(
    val code: Int,
    val reason: String?,
    message: String,
) : IOException(message) {
    /** Erreur passagère : quota de débit, serveur indisponible. */
    val isTransient: Boolean
        get() = code == 429 || code >= 500 || reason in RATE_LIMIT_REASONS

    companion object {
        val RATE_LIMIT_REASONS = setOf("userRateLimitExceeded", "rateLimitExceeded", "backendError")
    }
}

class DriveNotFoundException(message: String) : DriveHttpException(404, "notFound", message)

/** Plage demandée au-delà de la fin du fichier (le fichier « .part » est déjà complet). */
class DriveRangeNotSatisfiableException(message: String) : DriveHttpException(416, "rangeNotSatisfiable", message)

/** Jeton absent ou refusé : il faut se (re)connecter ou accorder l'accès à Drive. */
class DriveAuthException(message: String, val needsUserAction: Boolean = true) : IOException(message)

/** Fournit le jeton OAuth (autorisation drive). */
interface AccessTokenProvider {
    /** Lève [DriveAuthException] si un consentement de l'utilisateur est nécessaire. */
    suspend fun accessToken(): String

    /** Le jeton a été refusé (401) : le prochain appel doit en obtenir un nouveau. */
    suspend fun invalidate(token: String)
}

/**
 * Accès à Google Drive API v3. Seul le moteur de synchro l'utilise. L'app ne fait que lire,
 * télécharger, déplacer des dossiers de livres et déposer dans le dossier de chaque livre deux
 * petits fichiers : sa progression (un par compte) et, à défaut d'image, sa pochette.
 */
interface DriveService {
    /** files.list avec q = « '<ID du dossier>' in parents and trashed = false » (toutes les pages). */
    suspend fun listChildren(folderId: String): List<DriveFile>

    /** files.list avec q = « '<ID du dossier>' in parents and name = '<nom>' and trashed = false ». */
    suspend fun findChildren(folderId: String, name: String): List<DriveFile>

    /** files.list avec q = « name = '<nom>' and trashed = false » : les fichiers de ce nom de tous les dossiers, en une requête. */
    suspend fun findByName(name: String): List<DriveFile>

    /** files.list avec q = « name contains '<prefix>' and trashed = false » (filtré sur startsWith). */
    suspend fun findByNamePrefix(prefix: String): List<DriveFile> = emptyList()

    /** files.get (champs size et md5Checksum compris). */
    suspend fun getFile(fileId: String): DriveFile

    /** changes.getStartPageToken. */
    suspend fun startPageToken(): String

    /** changes.list. */
    suspend fun changes(pageToken: String): ChangePage

    /** files.get avec alt=media, en-tête Range pour reprendre un transfert. */
    suspend fun openContent(fileId: String, fromByte: Long = 0, toByteInclusive: Long? = null): DriveContent

    /** files.update avec addParents = nouveau statut, removeParents = ancien. */
    suspend fun move(fileId: String, addParent: String, removeParents: List<String>): DriveFile

    /** files.update avec name = nouveau nom (normalisation « Titre - AUTEUR »). */
    suspend fun rename(fileId: String, newName: String): DriveFile

    /** files.update avec trashed = true : corbeille Drive (restaurable 30 jours), jamais de suppression définitive. */
    suspend fun trash(fileId: String): DriveFile

    /** about.get : compte et champ storageQuota. */
    suspend fun about(): DriveAbout

    /** files.create (envoi multipart) : petit fichier dans un dossier (progression, pochette). */
    suspend fun createFile(name: String, parentId: String, content: ByteArray, mimeType: String): DriveFile

    /** files.update avec uploadType=media : remplace le contenu d'un fichier créé par l'app. */
    suspend fun updateContent(fileId: String, content: ByteArray, mimeType: String): DriveFile
}

/** Lit au plus [max] octets d'un contenu. */
fun DriveContent.readUpTo(max: Int): ByteArray = use { content ->
    val out = java.io.ByteArrayOutputStream(minOf(max, 1 shl 16))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (total < max) {
        val n = content.stream.read(buffer, 0, minOf(buffer.size, max - total))
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    out.toByteArray()
}

/** Contenu entier d'un petit fichier (progression JSON), plafonné à [max] octets. */
suspend fun DriveService.readSmallFile(fileId: String, max: Int = 256 * 1024): ByteArray = openContent(fileId).readUpTo(max)
