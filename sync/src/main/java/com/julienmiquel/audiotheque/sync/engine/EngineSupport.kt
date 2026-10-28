package com.julienmiquel.audiotheque.sync.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.StatFs
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.SyncEventEntity
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.parse.Mp3Probe
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.Clock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Compteurs d'une exécution du moteur (résumé « Dernières modifications »). */
class RunContext(val runId: Long, val trigger: SyncTrigger, val startedAt: Long) {
    val filesReceived = AtomicInteger()
    val bytesReceived = AtomicLong()
    val filesDeleted = AtomicInteger()
    val bytesDeleted = AtomicLong()
    val moves = AtomicInteger()
    val statusUpdates = AtomicInteger()
    val errors = AtomicInteger()
    val newBooks = AtomicInteger()

    @Volatile
    var fullInventory = false

    /** Première synchro : on ne journalise pas chaque livre découvert. */
    @Volatile
    var initialInventory = false

    /** Remarque affichée dans le résumé (« Téléchargements en attente du Wi-Fi »). */
    @Volatile
    var note: String? = null

    /** Transferts ou échantillons reportés : synchro à refaire au retour du Wi-Fi. */
    @Volatile
    var waitingForWifi = false
}

/** Journal des événements (réception, suppression locale, déplacement Drive, erreur). */
class HistoryLogger(private val db: AppDatabase, private val clock: Clock) {

    suspend fun log(
        type: SyncEventType,
        path: String,
        book: BookEntity? = null,
        sizeBytes: Long? = null,
        result: EventResult = EventResult.OK,
        message: String? = null,
        runId: Long? = null,
    ) {
        db.syncEventDao().insert(
            SyncEventEntity(
                timestamp = clock.now(),
                type = type,
                bookId = book?.driveId,
                bookTitle = book?.displayTitle,
                path = path,
                sizeBytes = sizeBytes,
                result = result,
                message = message,
                runId = runId,
            ),
        )
    }

    /** Historique conservé 30 jours. */
    suspend fun purge() {
        val before = clock.now() - RETENTION_MS
        db.syncEventDao().purgeOlderThan(before)
        db.syncRunDao().purgeOlderThan(before)
    }

    companion object {
        const val RETENTION_MS = 30L * 24 * 3600 * 1000
    }
}

/**
 * Emplacement des livres sur le téléphone : [Stockage interne]/Audiobooks/<nom du livre>/,
 * comme l'app actuelle, si l'accès à tous les fichiers est accordé ; sinon le dossier de l'app
 * sur le stockage externe (aucune autorisation nécessaire). Changer de statut ne déplace rien.
 */
open class LocalStorage(private val context: Context, private val settings: SettingsRepository) {

    open fun sharedStorageGranted(): Boolean = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    open suspend fun usesSharedStorage(): Boolean = settings.current().useSharedStorage && sharedStorageGranted()

    open suspend fun root(): File =
        if (usesSharedStorage()) File(Environment.getExternalStorageDirectory(), ROOT_NAME) else privateRoot()

    fun privateRoot(): File = File(context.getExternalFilesDir(null) ?: File(context.filesDir, "external"), ROOT_NAME)

    suspend fun displayRoot(): String = displayPath(root())

    /** Chemin lisible : « [Stockage interne]/Audiobooks/… ». */
    fun displayPath(file: File): String {
        val external = runCatching { Environment.getExternalStorageDirectory().path }.getOrNull()
        val path = file.path
        return if (external != null && path.startsWith(external)) "[Stockage interne]" + path.removePrefix(external) else path
    }

    open fun freeBytes(dir: File): Long {
        var existing: File? = dir
        while (existing != null && !existing.exists()) existing = existing.parentFile
        return runCatching { StatFs((existing ?: context.filesDir).path).availableBytes }.getOrDefault(Long.MAX_VALUE)
    }

    fun coversDir(): File = File(context.filesDir, "covers").apply { mkdirs() }

    companion object {
        const val ROOT_NAME = "Audiobooks"

        /** Nom de fichier sûr pour le stockage partagé (caractères interdits remplacés). */
        fun sanitize(name: String): String {
            val cleaned = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
                .trim().trimEnd('.').trim()
            val limited = if (cleaned.toByteArray().size > 200) cleaned.take(120).trim() else cleaned
            return limited.ifEmpty { "_" }
        }
    }
}

/** Transfert en cours, pour l'écran Synchro › Suivi. */
data class Transfer(
    val trackId: String,
    val bookId: String,
    val bookTitle: String,
    val fileName: String,
    val totalBytes: Long,
    val doneBytes: Long,
    val bytesPerSecond: Long,
)

class TransferTracker {
    private val state = MutableStateFlow<Map<String, Transfer>>(emptyMap())
    val transfers: StateFlow<Map<String, Transfer>> = state.asStateFlow()

    fun put(transfer: Transfer) = state.update { it + (transfer.trackId to transfer) }
    fun remove(trackId: String) = state.update { it - trackId }
    fun clear() = state.update { emptyMap() }
}

/** État du réseau. */
interface NetworkStatus {
    fun isConnected(): Boolean
    fun isUnmetered(): Boolean
}

class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private fun caps(): NetworkCapabilities? = cm?.getNetworkCapabilities(cm.activeNetwork)

    override fun isConnected(): Boolean = caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun isUnmetered(): Boolean = caps()?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
    } == true
}

/** Ce qu'on lit dans un fichier audio téléchargé : durée exacte, voix, pochette. */
data class AudioFileInfo(val durationMs: Long?, val narrator: String?, val picture: ByteArray?)

fun interface AudioFileInspector {
    fun inspect(file: File): AudioFileInfo?
}

/** Lecture des métadonnées par MediaMetadataRetriever, complétée par les tags ID3 (TXXX). */
class MediaMetadataInspector : AudioFileInspector {
    override fun inspect(file: File): AudioFileInfo? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val head = file.inputStream().use { input ->
                val buffer = ByteArray(Mp3Probe.PROBE_BYTES)
                val n = input.read(buffer)
                if (n > 0) buffer.copyOf(n) else ByteArray(0)
            }
            val tags = Mp3Probe.parse(head).tags
            val narrator = tags?.narrator()
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                    ?.takeIf { it.isNotBlank() && !it.equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), true) }
            AudioFileInfo(duration, narrator, retriever.embeddedPicture ?: tags?.picture)
        } finally {
            retriever.release()
        }
    }.getOrNull()
}

/** Somme MD5 hexadécimale, comparée au champ md5Checksum de Drive. */
fun File.md5Hex(): String {
    val digest = MessageDigest.getInstance("MD5")
    inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Progression d'un livre telle qu'écrite dans son dossier Drive : un petit fichier JSON par
 * compte (« .audiotheque-progress-<compte>.json »), visible dans Drive à côté des pistes. Le
 * dossier garde son ID quand il change de statut : le fichier suit le livre. Un livre sans
 * dossier (piste isolée) a son fichier dans le dossier de statut, nommé d'après l'ID du livre.
 */
@Serializable
data class ProgressJson(
    val bookId: String,
    val trackId: String? = null,
    val trackIndex: Int = 0,
    val positionMs: Long = 0,
    val speed: Float = 1f,
    val percent: Float = 0f,
    val lastListenedAt: Long? = null,
    val finished: Boolean = false,
    val setAsideAtPercent: Float? = null,
    /** Date d'enregistrement sur le téléphone : la plus récente l'emporte entre deux appareils. */
    val updatedAt: Long,
    /** Appareil qui a écrit la position (« Google Pixel 9 »). */
    val device: String? = null,
    /** Compte propriétaire de la position (lisibilité dans Drive). */
    val account: String? = null,
    /** Titre affiché (lisibilité dans Drive). */
    val title: String? = null,
    val version: Int = 2,
) {
    companion object {
        const val MIME = "application/json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private const val PREFIX = ".audiotheque-progress-"
        private const val SUFFIX = ".json"

        /** Nom du fichier dans le dossier du livre : un par compte. */
        fun fileName(account: String): String = "$PREFIX${account.lowercase()}$SUFFIX"

        /** Livre sans dossier (piste isolée) : fichier dans le dossier de statut, nommé d'après le livre. */
        fun fileName(account: String, bookId: String): String = "$PREFIX${account.lowercase()}-$bookId$SUFFIX"

        /** Fichier écrit par l'app (jamais une piste ni une pochette). */
        fun isProgressFile(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(SUFFIX)

        fun encode(progress: ProgressJson): ByteArray = json.encodeToString(serializer(), progress).toByteArray()

        fun decode(bytes: ByteArray): ProgressJson = json.decodeFromString(serializer(), String(bytes))
    }
}
