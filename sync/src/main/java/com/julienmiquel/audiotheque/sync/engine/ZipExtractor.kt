package com.julienmiquel.audiotheque.sync.engine

import com.julienmiquel.audiotheque.core.parse.TrackCandidate
import com.julienmiquel.audiotheque.core.parse.TrackOrdering
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ExtractedZipTrack(
    val relativePath: String,
    val name: String,
    val file: File,
    val mimeType: String,
    val sortIndex: Int,
    val number: Int?,
    val part: Int?,
    val chapter: Int?,
    val title: String,
    val info: AudioFileInfo?,
)

data class ExtractedZip(
    val tracks: List<ExtractedZipTrack>,
    val coverBytes: ByteArray?,
    val coverExt: String?,
    val narrator: String?,
)

/**
 * Décompression sécurisée d'un livre livré en archive `.zip` :
 * - tolère les noms UTF-8 et les archives Windows/DOS accentuées (CP437 / Windows-1252 / ISO-8859-1) ;
 * - rejette toute tentative de sortie du dossier cible (Zip-Slip `..`) et ignore `__MACOSX` ;
 * - aplatit un éventuel dossier racine englobant unique ;
 * - ordonne les pistes extraites comme un dossier Drive ([TrackOrdering]) et lit durée, chapitres,
 *   voix et pochette via [AudioFileInspector].
 */
object ZipExtractor {
    private val AUDIO_EXTENSION =
        Regex("""\.(mp3|m4a|m4b|aac|ogg|oga|opus|flac|wav|wma)$""", RegexOption.IGNORE_CASE)
    private val IMAGE_EXTENSION =
        Regex("""\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)

    fun extract(
        zipFile: File,
        targetDir: File,
        inspector: AudioFileInspector,
        ensureActive: () -> Unit = {},
    ): ExtractedZip {
        return useReadableZip(zipFile) { zf, rawEntries ->
            data class CleanEntry(val entry: ZipEntry, val segments: List<String>)

            val validEntries = mutableListOf<CleanEntry>()
            for (entry in rawEntries) {
                ensureActive()
                val normalized = entry.name.replace('\\', '/').trim()
                val rawSegments = normalized.split('/').filter { it.isNotEmpty() }
                if (rawSegments.any { it == ".." }) {
                    throw IOException("Chemin d'archive non sûr refusé : ${entry.name}")
                }
                if (entry.isDirectory) continue
                val segments = rawSegments.filter { it != "." }
                if (segments.isEmpty()) continue
                if (segments.any { it.equals("__MACOSX", ignoreCase = true) || it.startsWith("._") }) continue
                validEntries += CleanEntry(entry, segments)
            }

            // Si toutes les entrées partagent un unique dossier englobant, on l'aplatit.
            val stripOuterFolder = validEntries.isNotEmpty() &&
                validEntries.all { it.segments.size >= 2 } &&
                validEntries.map { it.segments.first() }.distinct().size == 1

            data class MappedEntry(val entry: ZipEntry, val relativeSegments: List<String>, val fileName: String)

            val mapped = validEntries.map { ce ->
                val rel = if (stripOuterFolder) ce.segments.drop(1) else ce.segments
                MappedEntry(ce.entry, rel, rel.last())
            }

            val audioEntries = mapped.filter { AUDIO_EXTENSION.containsMatchIn(it.fileName) }
            if (audioEntries.isEmpty()) {
                throw IOException("Aucune piste audio trouvée dans l'archive ${zipFile.name}")
            }

            targetDir.mkdirs()
            val canonicalRoot = targetDir.canonicalPath + File.separator

            data class ExtractedRaw(
                val id: String,
                val relativePath: String,
                val name: String,
                val file: File,
            )

            val extractedRaw = audioEntries.mapIndexed { idx, item ->
                ensureActive()
                val sanitizedRel = item.relativeSegments.joinToString("/") { LocalStorage.sanitize(it) }
                val outFile = File(targetDir, sanitizedRel)
                if (!outFile.canonicalPath.startsWith(canonicalRoot)) {
                    throw IOException("Chemin d'extraction hors du dossier du livre : ${item.entry.name}")
                }
                outFile.parentFile?.mkdirs()
                zf.getInputStream(item.entry).use { input ->
                    copyToFile(input, outFile, ensureActive)
                }
                ExtractedRaw(
                    id = idx.toString(),
                    relativePath = sanitizedRel,
                    name = item.fileName,
                    file = outFile,
                )
            }

            // Meilleure image présente dans le .zip (cover.jpg, folder.jpg, etc.).
            var coverBytes: ByteArray? = null
            var coverExt: String? = null
            val bestImage = mapped
                .filter { IMAGE_EXTENSION.containsMatchIn(it.fileName) }
                .filter { it.entry.size <= MetadataProber.MAX_COVER_BYTES }
                .minByOrNull { coverRank(it.fileName) }
            if (bestImage != null) {
                val bytes = zf.getInputStream(bestImage.entry).use { readUpTo(it, MetadataProber.MAX_COVER_BYTES) }
                if (bytes.isNotEmpty() && bytes.size <= MetadataProber.MAX_COVER_BYTES) {
                    coverBytes = bytes
                    coverExt = bestImage.fileName.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
                }
            }

            val rawById = extractedRaw.associateBy { it.id }
            val ordered = TrackOrdering.sort(
                extractedRaw.map { TrackCandidate(id = it.id, relativePath = it.relativePath, name = it.name) },
            )

            var narrator: String? = null
            val tracks = ordered.mapIndexed { sortIndex, (candidate, parsed) ->
                ensureActive()
                val raw = rawById.getValue(candidate.id)
                val info = inspector.inspect(raw.file)
                if (narrator == null && !info?.narrator.isNullOrBlank()) {
                    narrator = info?.narrator
                }
                if (coverBytes == null && info?.picture != null) {
                    coverBytes = info.picture
                    coverExt = if (CoverUploader.isPng(info.picture)) "png" else "jpg"
                }
                ExtractedZipTrack(
                    relativePath = raw.relativePath,
                    name = raw.name,
                    file = raw.file,
                    mimeType = mimeTypeForName(raw.name),
                    sortIndex = sortIndex,
                    number = parsed.number,
                    part = parsed.part,
                    chapter = parsed.chapter,
                    title = parsed.title,
                    info = info,
                )
            }

            ExtractedZip(
                tracks = tracks,
                coverBytes = coverBytes,
                coverExt = coverExt,
                narrator = narrator,
            )
        }
    }

    private fun <T> useReadableZip(zipFile: File, block: (ZipFile, List<ZipEntry>) -> T): T {
        val charsets = buildList {
            add(Charsets.UTF_8)
            runCatching { Charset.forName("CP437") }.getOrNull()?.let(::add)
            runCatching { Charset.forName("IBM437") }.getOrNull()?.let(::add)
            runCatching { Charset.forName("windows-1252") }.getOrNull()?.let(::add)
            add(Charsets.ISO_8859_1)
        }.distinct()

        var lastError: Throwable? = null
        for (charset in charsets) {
            var zf: ZipFile? = null
            val entries: List<ZipEntry>? = try {
                val candidate = ZipFile(zipFile, charset)
                zf = candidate
                val list = ArrayList<ZipEntry>()
                val enumeration = candidate.entries()
                while (enumeration.hasMoreElements()) {
                    list += enumeration.nextElement()
                }
                list
            } catch (e: Exception) {
                runCatching { zf?.close() }
                if (e is IllegalArgumentException || e is java.util.zip.ZipException) {
                    lastError = e
                    null
                } else {
                    throw e
                }
            }
            if (zf != null && entries != null) {
                return zf.use { block(it, entries) }
            }
        }
        throw IOException("Archive .zip illisible (${lastError?.message ?: zipFile.name})", lastError)
    }

    private fun copyToFile(input: InputStream, target: File, ensureActive: () -> Unit) {
        val output = try {
            FileOutputStream(target)
        } catch (e: IOException) {
            throw LocalWriteException(target, e)
        }
        output.use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                try {
                    out.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw LocalWriteException(target, e)
                }
            }
        }
    }

    private fun readUpTo(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total <= maxBytes) {
            val n = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - total))
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return if (total > maxBytes) ByteArray(0) else out.toByteArray()
    }

    private fun coverRank(name: String): Int {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.startsWith("cover") || n.startsWith("couverture") -> 0
            n.startsWith("folder") || n.startsWith("front") -> 1
            else -> 2
        }
    }

    fun mimeTypeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "m4a", "m4b", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "wma" -> "audio/x-ms-wma"
        else -> "audio/mpeg"
    }
}
