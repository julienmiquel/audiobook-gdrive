package com.julienmiquel.audiotheque.sync.demo

import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.sync.drive.ChangePage
import com.julienmiquel.audiotheque.sync.drive.DriveAbout
import com.julienmiquel.audiotheque.sync.drive.DriveChange
import com.julienmiquel.audiotheque.sync.drive.DriveContent
import com.julienmiquel.audiotheque.sync.drive.DriveFile
import com.julienmiquel.audiotheque.sync.drive.DriveHttpException
import com.julienmiquel.audiotheque.sync.drive.DriveNotFoundException
import com.julienmiquel.audiotheque.sync.drive.DriveRangeNotSatisfiableException
import com.julienmiquel.audiotheque.sync.drive.DriveService
import com.julienmiquel.audiotheque.sync.engine.ProgressJson
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Fournit les octets des mp3 de démonstration. */
fun interface DemoContent {
    fun bytes(asset: String): ByteArray
}

/** Actions de démonstration proposées dans les réglages. */
interface DemoControls {
    /** Simule un dossier déplacé depuis le web ou par un autre membre de la famille. */
    suspend fun simulateExternalMove(bookId: String, toFolderId: String)

    /** Remet la bibliothèque de démonstration dans son état initial. */
    suspend fun reset()
}

/**
 * Drive simulé, en mémoire, persistant ses changements (déplacements, espace caché) dans un
 * fichier JSON. Sert au mode démo et aux tests du moteur de synchro : même interface que
 * [com.julienmiquel.audiotheque.sync.drive.RestDriveService], avec latence et débit simulés.
 */
class FakeDriveService(
    private val content: DemoContent,
    private val stateFile: File? = null,
    private val clock: Clock = Clock.SYSTEM,
    private val latencyMs: Long = 60,
    private val bytesPerSecond: Long = 96 * 1024,
) : DriveService, DemoControls {

    private data class Node(
        val id: String,
        val name: String,
        val mimeType: String,
        var parents: List<String>,
        val asset: String? = null,
        val createdTime: Long,
        var modifiedTime: Long,
        var trashed: Boolean = false,
    )

    private class AppDataFile(val id: String, val name: String, var bytes: ByteArray, var modifiedTime: Long)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val nodes = LinkedHashMap<String, Node>()
    private val changeLog = ArrayList<String>()
    private val appData = LinkedHashMap<String, AppDataFile>()
    private val assetInfo = HashMap<String, Pair<Long, String>>()
    private var loaded = false

    /** Coupure réseau simulée (tests). */
    @Volatile
    var offline: Boolean = false

    /** Dossiers dont le déplacement est refusé (403), pour tester les échecs définitifs. */
    val forbiddenMoves: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Dossiers dont le déplacement échoue passagèrement (503), pour tester les nouveaux essais. */
    val busyMoves: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Autorisation Google expirée (tests) : chaque appel demande une reconnexion. */
    @Volatile
    var authExpired: Boolean = false

    // ------------------------------------------------------------------ DriveService

    override suspend fun listChildren(folderId: String): List<DriveFile> = op {
        nodes.values.filter { folderId in it.parents && !it.trashed }.map { it.toFile() }
    }

    override suspend fun getFile(fileId: String): DriveFile = op {
        (nodes[fileId] ?: throw DriveNotFoundException("Fichier introuvable : $fileId")).toFile()
    }

    override suspend fun startPageToken(): String = op { (changeLog.size + 1).toString() }

    override suspend fun changes(pageToken: String): ChangePage = op {
        val from = (pageToken.toIntOrNull() ?: 1).coerceAtLeast(1)
        val ids = changeLog.drop(from - 1).distinct()
        ChangePage(
            changes = ids.map { id ->
                val node = nodes[id]
                DriveChange(id, removed = node == null, file = node?.toFile())
            },
            nextPageToken = null,
            newStartPageToken = (changeLog.size + 1).toString(),
        )
    }

    override suspend fun openContent(fileId: String, fromByte: Long, toByteInclusive: Long?): DriveContent {
        val (bytes, isAppData) = op {
            val hidden = appData[fileId]
            if (hidden != null) {
                hidden.bytes to true
            } else {
                val node = nodes[fileId] ?: throw DriveNotFoundException("Fichier introuvable : $fileId")
                content.bytes(node.asset ?: throw DriveHttpException(403, "fileNotDownloadable", "Dossier")) to false
            }
        }
        if (fromByte >= bytes.size && bytes.isNotEmpty()) {
            throw DriveRangeNotSatisfiableException("Plage hors du fichier")
        }
        val end = ((toByteInclusive ?: (bytes.size - 1L)) + 1).coerceAtMost(bytes.size.toLong()).toInt()
        val start = fromByte.toInt()
        return DriveContent(
            stream = ThrottledInputStream(bytes, start, end, if (isAppData) 0 else bytesPerSecond),
            contentLength = (end - start).toLong(),
            partial = fromByte > 0 || toByteInclusive != null,
        )
    }

    override suspend fun move(fileId: String, addParent: String, removeParents: List<String>): DriveFile = op {
        if (fileId in forbiddenMoves) throw DriveHttpException(403, "insufficientFilePermissions", "Drive 403 : permission refusée")
        if (fileId in busyMoves) throw DriveHttpException(503, "backendError", "Drive 503 : service momentanément indisponible")
        val node = nodes[fileId] ?: throw DriveNotFoundException("Fichier introuvable : $fileId")
        node.parents = (node.parents - removeParents.toSet() + addParent).distinct()
        node.modifiedTime = clock.now()
        changeLog += fileId
        persist()
        node.toFile()
    }

    override suspend fun about(): DriveAbout = op {
        DriveAbout(
            email = "demo@audiotheque.local",
            displayName = "Compte de démonstration",
            quotaLimit = 2_000_000_000_000L,
            quotaUsage = 1_268_400_000_000L,
        )
    }

    override suspend fun listAppData(): List<DriveFile> = op {
        appData.values.map {
            DriveFile(it.id, it.name, "application/json", listOf("appDataFolder"), it.bytes.size.toLong(), modifiedTime = it.modifiedTime)
        }
    }

    override suspend fun readAppData(fileId: String): ByteArray = op {
        (appData[fileId] ?: throw DriveNotFoundException("Fichier introuvable : $fileId")).bytes
    }

    override suspend fun createAppData(name: String, content: ByteArray): DriveFile = op {
        val file = AppDataFile("appdata-${appData.size + 1}-${name.hashCode().toUInt()}", name, content, clock.now())
        appData[file.id] = file
        persist()
        DriveFile(file.id, name, "application/json", listOf("appDataFolder"), content.size.toLong(), modifiedTime = file.modifiedTime)
    }

    override suspend fun updateAppData(fileId: String, content: ByteArray): DriveFile = op {
        val file = appData[fileId] ?: throw DriveNotFoundException("Fichier introuvable : $fileId")
        file.bytes = content
        file.modifiedTime = clock.now()
        persist()
        DriveFile(file.id, file.name, "application/json", listOf("appDataFolder"), content.size.toLong(), modifiedTime = file.modifiedTime)
    }

    // ------------------------------------------------------------------ DemoControls

    override suspend fun simulateExternalMove(bookId: String, toFolderId: String) {
        mutex.withLock {
            ensureLoaded()
            val node = nodes[bookId] ?: return
            node.parents = listOf(toFolderId)
            node.modifiedTime = clock.now()
            changeLog += bookId
            persist()
        }
    }

    override suspend fun reset() {
        mutex.withLock {
            stateFile?.delete()
            nodes.clear()
            changeLog.clear()
            appData.clear()
            loaded = false
            ensureLoaded()
        }
    }

    /** ID d'un livre de démonstration d'après son dossier de statut et son nom (tests, réglages). */
    suspend fun findBookId(name: String): String? = mutex.withLock {
        ensureLoaded()
        nodes.values.firstOrNull { it.name == name }?.id
    }

    // ------------------------------------------------------------------ interne

    private suspend fun <T> op(block: suspend () -> T): T {
        if (latencyMs > 0) delay(latencyMs)
        if (offline) throw java.io.IOException("Réseau indisponible (simulé)")
        if (authExpired) throw com.julienmiquel.audiotheque.sync.drive.DriveAuthException("Autorisation Google Drive à renouveler")
        return mutex.withLock {
            ensureLoaded()
            block()
        }
    }

    private fun Node.toFile(): DriveFile {
        val info = asset?.let { assetInfo(it) }
        return DriveFile(
            id = id,
            name = name,
            mimeType = mimeType,
            parents = parents,
            size = info?.first,
            md5 = info?.second,
            createdTime = createdTime,
            modifiedTime = modifiedTime,
            trashed = trashed,
        )
    }

    private fun assetInfo(asset: String): Pair<Long, String> = assetInfo.getOrPut(asset) {
        val bytes = content.bytes(asset)
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        bytes.size.toLong() to md5
    }

    private fun ensureLoaded() {
        if (loaded) return
        seed()
        restore()
        loaded = true
    }

    private fun seed() {
        // Ajouts échelonnés dans le passé pour des dates d'ajout réalistes.
        var t = clock.now() - 400L * 24 * 3600 * 1000
        fun next(): Long {
            t += 2L * 24 * 3600 * 1000
            return t
        }
        val root = DriveLibraryDefaults.ROOT_ID
        nodes[root] = Node(root, "AudioBook", DriveFile.FOLDER_MIME, emptyList(), createdTime = next(), modifiedTime = t)
        DemoLibrary.statusFolders.forEach { (id, name) ->
            nodes[id] = Node(id, name, DriveFile.FOLDER_MIME, listOf(root), createdTime = next(), modifiedTime = t)
        }
        DemoLibrary.tree.forEach { (folderId, entries) ->
            entries.forEachIndexed { index, entry ->
                val path = "$folderId/$index/${entry.name}"
                when (entry) {
                    is DemoLibrary.Folder -> {
                        val bookId = stableId(path)
                        nodes[bookId] = Node(bookId, entry.name, DriveFile.FOLDER_MIME, listOf(folderId), createdTime = next(), modifiedTime = t)
                        entry.tracks.forEach { track ->
                            val trackId = stableId("$path/${track.fileName}")
                            nodes[trackId] = Node(
                                trackId, track.fileName, "audio/mpeg", listOf(bookId),
                                asset = track.asset, createdTime = t, modifiedTime = t,
                            )
                        }
                        entry.cover?.let { cover ->
                            val coverId = stableId("$path/cover.jpg")
                            nodes[coverId] = Node(coverId, "cover.jpg", "image/jpeg", listOf(bookId), asset = cover, createdTime = t, modifiedTime = t)
                        }
                    }
                    is DemoLibrary.File -> {
                        val id = stableId(path)
                        nodes[id] = Node(id, entry.name, entry.mimeType, listOf(folderId), asset = entry.asset, createdTime = next(), modifiedTime = t)
                    }
                }
            }
        }
        // Progression de démonstration dans l'espace caché : livre rangé à tort dans _Ecouté.
        nodes.values.firstOrNull { it.name == DemoLibrary.UNFINISHED_BOOK }?.let { book ->
            val firstTrack = nodes.values.filter { book.id in it.parents }.minByOrNull { it.name }
            val progress = ProgressJson(
                bookId = book.id,
                trackId = firstTrack?.id,
                trackIndex = DemoLibrary.UNFINISHED_TRACK_INDEX,
                positionMs = DemoLibrary.UNFINISHED_POSITION_MS,
                speed = 1.2f,
                percent = 46f,
                lastListenedAt = clock.now() - 20L * 24 * 3600 * 1000,
                finished = false,
                setAsideAtPercent = null,
                updatedAt = clock.now() - 20L * 24 * 3600 * 1000,
            )
            val name = ProgressJson.fileName(book.id)
            appData["appdata-seed"] = AppDataFile("appdata-seed", name, ProgressJson.encode(progress), progress.updatedAt)
        }
    }

    @Serializable
    private data class SavedState(
        val parents: Map<String, List<String>> = emptyMap(),
        val changeLog: List<String> = emptyList(),
        val appData: List<SavedAppData> = emptyList(),
    )

    @Serializable
    private data class SavedAppData(val id: String, val name: String, val text: String, val modifiedTime: Long)

    private fun restore() {
        val file = stateFile ?: return
        if (!file.exists()) return
        val saved = runCatching { json.decodeFromString(SavedState.serializer(), file.readText()) }.getOrNull() ?: return
        saved.parents.forEach { (id, parents) -> nodes[id]?.parents = parents }
        changeLog.clear()
        changeLog.addAll(saved.changeLog)
        appData.clear()
        saved.appData.forEach { appData[it.id] = AppDataFile(it.id, it.name, it.text.toByteArray(), it.modifiedTime) }
    }

    private fun persist() {
        val file = stateFile ?: return
        val state = SavedState(
            parents = nodes.values.filter { it.parents.isNotEmpty() }.associate { it.id to it.parents },
            changeLog = changeLog.toList(),
            appData = appData.values.map { SavedAppData(it.id, it.name, String(it.bytes), it.modifiedTime) },
        )
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(SavedState.serializer(), state))
            tmp.renameTo(file)
        }
    }

    private fun stableId(path: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
        return "1" + digest.take(20).joinToString("") { alphabet[(it.toInt() and 0x3F)].toString() } +
            digest.drop(8).take(12).joinToString("") { alphabet[((it.toInt() shr 2) and 0x3F)].toString() }
    }
}

/** Flux limité en débit, pour que les transferts de démonstration soient visibles. */
private class ThrottledInputStream(
    private val data: ByteArray,
    start: Int,
    private val end: Int,
    private val bytesPerSecond: Long,
) : InputStream() {
    private var position = start
    private var sent = 0L
    private val startedAt = System.nanoTime()

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position >= end) return -1
        val n = minOf(len, end - position, 8 * 1024)
        if (bytesPerSecond > 0) {
            val expectedNs = (sent + n) * 1_000_000_000L / bytesPerSecond
            val elapsedNs = System.nanoTime() - startedAt
            if (expectedNs > elapsedNs) Thread.sleep((expectedNs - elapsedNs) / 1_000_000)
        }
        System.arraycopy(data, position, b, off, n)
        position += n
        sent += n
        return n
    }

    override fun available(): Int = end - position
}
