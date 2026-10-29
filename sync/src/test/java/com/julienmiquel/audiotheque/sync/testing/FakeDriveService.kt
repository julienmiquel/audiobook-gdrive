package com.julienmiquel.audiotheque.sync.testing

import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.sync.drive.ChangePage
import com.julienmiquel.audiotheque.sync.drive.DriveAbout
import com.julienmiquel.audiotheque.sync.drive.DriveAuthException
import com.julienmiquel.audiotheque.sync.drive.DriveChange
import com.julienmiquel.audiotheque.sync.drive.DriveContent
import com.julienmiquel.audiotheque.sync.drive.DriveFile
import com.julienmiquel.audiotheque.sync.drive.DriveHttpException
import com.julienmiquel.audiotheque.sync.drive.DriveNotFoundException
import com.julienmiquel.audiotheque.sync.drive.DriveRangeNotSatisfiableException
import com.julienmiquel.audiotheque.sync.drive.DriveService
import com.julienmiquel.audiotheque.sync.engine.ProgressJson
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Double de test de [DriveService], réservé aux tests unitaires du moteur (jamais livré dans
 * l'app, qui n'utilise que l'API Google Drive réelle) : arborescence en mémoire construite à
 * partir de [LibraryFixture], avec des interrupteurs pour les cas d'erreur (réseau coupé, 403,
 * 503, autorisation expirée).
 */
class FakeDriveService(
    private val content: (String) -> ByteArray,
    private val clock: Clock,
) : DriveService {

    private data class Node(
        val id: String,
        var name: String,
        val mimeType: String,
        var parents: List<String>,
        val fixture: String? = null,
        val createdTime: Long,
        var modifiedTime: Long,
        var trashed: Boolean = false,
        /** Contenu d'un fichier créé par l'app (progression, pochette), sans fixture. */
        var bytes: ByteArray? = null,
    )

    private val mutex = Mutex()
    private val nodes = LinkedHashMap<String, Node>()
    private val changeLog = ArrayList<String>()
    private val fixtureInfo = HashMap<String, Pair<Long, String>>()
    private var loaded = false
    private var created = 0

    /** Réseau coupé : chaque appel échoue comme sans connexion. */
    @Volatile
    var offline: Boolean = false

    /** Dossiers dont le déplacement est refusé (403), pour tester les échecs définitifs. */
    val forbiddenMoves: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Dossiers dont le déplacement échoue passagèrement (503), pour tester les nouveaux essais. */
    val busyMoves: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Autorisation Google expirée : chaque appel demande une reconnexion. */
    @Volatile
    var authExpired: Boolean = false

    // ------------------------------------------------------------------ DriveService

    override suspend fun listChildren(folderId: String): List<DriveFile> = op {
        nodes.values.filter { folderId in it.parents && !it.trashed }.map { it.toFile() }
    }

    override suspend fun findChildren(folderId: String, name: String): List<DriveFile> = op {
        nodes.values.filter { folderId in it.parents && it.name == name && !it.trashed }.map { it.toFile() }
    }

    override suspend fun findByName(name: String): List<DriveFile> = op {
        nodes.values.filter { it.name == name && !it.trashed }.map { it.toFile() }
    }

    override suspend fun findByNamePrefix(prefix: String): List<DriveFile> = op {
        nodes.values.filter { it.name.startsWith(prefix) && !it.trashed }.map { it.toFile() }
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
        val bytes = op {
            val node = nodes[fileId] ?: throw DriveNotFoundException("Fichier introuvable : $fileId")
            node.bytes ?: content(node.fixture ?: throw DriveHttpException(403, "fileNotDownloadable", "Dossier"))
        }
        if (fromByte >= bytes.size && bytes.isNotEmpty()) {
            throw DriveRangeNotSatisfiableException("Plage hors du fichier")
        }
        val end = ((toByteInclusive ?: (bytes.size - 1L)) + 1).coerceAtMost(bytes.size.toLong()).toInt()
        val start = fromByte.toInt()
        return DriveContent(
            stream = ByteArrayInputStream(bytes, start, end - start),
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
        node.toFile()
    }

    override suspend fun rename(fileId: String, newName: String): DriveFile = op {
        if (fileId in forbiddenMoves) throw DriveHttpException(403, "insufficientFilePermissions", "Drive 403 : permission refusée")
        if (fileId in busyMoves) throw DriveHttpException(503, "backendError", "Drive 503 : service momentanément indisponible")
        val node = nodes[fileId] ?: throw DriveNotFoundException("Fichier introuvable : $fileId")
        node.name = newName
        node.modifiedTime = clock.now()
        changeLog += fileId
        node.toFile()
    }

    override suspend fun trash(fileId: String): DriveFile = op {
        if (fileId in forbiddenMoves) throw DriveHttpException(403, "insufficientFilePermissions", "Drive 403 : permission refusée")
        val node = nodes[fileId] ?: throw DriveNotFoundException("Fichier introuvable : $fileId")
        // Dans Drive, le contenu d'un dossier mis à la corbeille disparaît avec lui des listes.
        fun trashTree(id: String) {
            nodes[id]?.let { n ->
                n.trashed = true
                n.modifiedTime = clock.now()
                changeLog += id
            }
            nodes.values.filter { id in it.parents }.forEach { trashTree(it.id) }
        }
        trashTree(fileId)
        node.toFile()
    }

    override suspend fun about(): DriveAbout = op {
        DriveAbout(
            email = ACCOUNT,
            displayName = "Compte de test",
            quotaLimit = 2_000_000_000_000L,
            quotaUsage = 1_268_400_000_000L,
        )
    }

    override suspend fun createFile(name: String, parentId: String, content: ByteArray, mimeType: String): DriveFile = op {
        if (nodes[parentId]?.mimeType != DriveFile.FOLDER_MIME) throw DriveNotFoundException("Dossier introuvable : $parentId")
        created++
        val node = Node("created-$created-${name.hashCode().toUInt()}", name, mimeType, listOf(parentId), createdTime = clock.now(), modifiedTime = clock.now(), bytes = content)
        nodes[node.id] = node
        changeLog += node.id
        node.toFile()
    }

    override suspend fun updateContent(fileId: String, content: ByteArray, mimeType: String): DriveFile = op {
        val node = nodes[fileId] ?: throw DriveNotFoundException("Fichier introuvable : $fileId")
        node.bytes = content
        node.modifiedTime = clock.now()
        changeLog += fileId
        node.toFile()
    }

    // ------------------------------------------------------------------ cas de test

    /** Dossier déplacé hors de l'app (depuis le web, ou par un autre membre de la famille). */
    suspend fun externalMove(bookId: String, toFolderId: String) {
        mutex.withLock {
            ensureLoaded()
            val node = nodes[bookId] ?: return
            node.parents = listOf(toFolderId)
            node.modifiedTime = clock.now()
            changeLog += bookId
        }
    }

    /** Contenu d'un fichier créé par l'app (progression, pochette). */
    suspend fun bytesOf(fileId: String): ByteArray = op {
        nodes[fileId]?.bytes ?: throw DriveNotFoundException("Fichier introuvable : $fileId")
    }

    /**
     * Un autre téléphone écrit la progression du compte dans le dossier du livre, à la date
     * [at] (modifiedTime Drive) : crée ou remplace le fichier.
     */
    suspend fun writeProgressFromOtherDevice(bookId: String, progress: ProgressJson, at: Long = clock.now()): DriveFile = mutex.withLock {
        ensureLoaded()
        val name = ProgressJson.fileName(ACCOUNT)
        val existing = nodes.values.firstOrNull { bookId in it.parents && it.name == name && !it.trashed }
        val node = existing ?: Node("other-${bookId.takeLast(6)}", name, ProgressJson.MIME, listOf(bookId), createdTime = at, modifiedTime = at).also { nodes[it.id] = it }
        node.bytes = ProgressJson.encode(progress)
        node.modifiedTime = at
        changeLog += node.id
        node.toFile()
    }

    // ------------------------------------------------------------------ interne

    private suspend fun <T> op(block: () -> T): T {
        if (offline) throw IOException("Réseau indisponible")
        if (authExpired) throw DriveAuthException("Autorisation Google Drive à renouveler")
        return mutex.withLock {
            ensureLoaded()
            block()
        }
    }

    private fun Node.toFile(): DriveFile {
        val info = fixture?.let { fixtureInfo(it) }
        return DriveFile(
            id = id,
            name = name,
            mimeType = mimeType,
            parents = parents,
            size = info?.first ?: bytes?.size?.toLong(),
            md5 = info?.second,
            createdTime = createdTime,
            modifiedTime = modifiedTime,
            trashed = trashed,
        )
    }

    private fun fixtureInfo(name: String): Pair<Long, String> = fixtureInfo.getOrPut(name) {
        val bytes = content(name)
        val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        bytes.size.toLong() to md5
    }

    private fun ensureLoaded() {
        if (loaded) return
        seed()
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
        LibraryFixture.statusFolders.forEach { (id, name) ->
            nodes[id] = Node(id, name, DriveFile.FOLDER_MIME, listOf(root), createdTime = next(), modifiedTime = t)
        }
        LibraryFixture.tree.forEach { (folderId, entries) ->
            entries.forEachIndexed { index, entry ->
                val path = "$folderId/$index/${entry.name}"
                when (entry) {
                    is LibraryFixture.Folder -> {
                        val bookId = stableId(path)
                        nodes[bookId] = Node(bookId, entry.name, DriveFile.FOLDER_MIME, listOf(folderId), createdTime = next(), modifiedTime = t)
                        entry.tracks.forEach { track ->
                            val trackId = stableId("$path/${track.fileName}")
                            nodes[trackId] = Node(
                                trackId, track.fileName, "audio/mpeg", listOf(bookId),
                                fixture = track.fixture, createdTime = t, modifiedTime = t,
                            )
                        }
                        entry.cover?.let { cover ->
                            val coverId = stableId("$path/cover.jpg")
                            nodes[coverId] = Node(coverId, "cover.jpg", "image/jpeg", listOf(bookId), fixture = cover, createdTime = t, modifiedTime = t)
                        }
                    }
                    is LibraryFixture.File -> {
                        val id = stableId(path)
                        nodes[id] = Node(id, entry.name, entry.mimeType, listOf(folderId), fixture = entry.fixture, createdTime = next(), modifiedTime = t)
                    }
                }
            }
        }
        // Progression déjà écrite par un autre téléphone dans le dossier du livre : livre rangé à
        // tort dans _Ecouté, pas fini.
        nodes.values.firstOrNull { it.name == LibraryFixture.UNFINISHED_BOOK }?.let { book ->
            val firstTrack = nodes.values.filter { book.id in it.parents }.minByOrNull { it.name }
            val at = clock.now() - 20L * 24 * 3600 * 1000
            val progress = ProgressJson(
                bookId = book.id,
                trackId = firstTrack?.id,
                trackIndex = LibraryFixture.UNFINISHED_TRACK_INDEX,
                positionMs = LibraryFixture.UNFINISHED_POSITION_MS,
                speed = 1.2f,
                percent = 46f,
                lastListenedAt = at,
                finished = false,
                setAsideAtPercent = null,
                updatedAt = at,
                device = OTHER_DEVICE,
                account = ACCOUNT,
            )
            val id = "progress-seed"
            nodes[id] = Node(id, ProgressJson.fileName(ACCOUNT), ProgressJson.MIME, listOf(book.id), createdTime = at, modifiedTime = at, bytes = ProgressJson.encode(progress))
        }
    }

    private fun stableId(path: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
        return "1" + digest.take(20).joinToString("") { alphabet[(it.toInt() and 0x3F)].toString() } +
            digest.drop(8).take(12).joinToString("") { alphabet[((it.toInt() shr 2) and 0x3F)].toString() }
    }

    companion object {
        /** Compte Google des tests. */
        const val ACCOUNT = "lecteur@example.com"

        /** L'autre téléphone du même compte. */
        const val OTHER_DEVICE = "Google Pixel 9"
    }
}
