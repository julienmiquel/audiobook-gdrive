package com.julienmiquel.audiotheque.core.parse

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** Métadonnées iTunes / ISO BMFF d'un fichier .m4b / .m4a / .mp4. */
data class Mp4Tags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val composer: String? = null,
    val narratorTag: String? = null,
    val comment: String? = null,
    val description: String? = null,
    val picture: ByteArray? = null,
    val pictureMime: String? = null,
) {
    fun narrator(): String? {
        narratorTag?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val luPar = Regex("""(?:lu|lue)\s+par\s*:?\s*([^,;/()\[\]]+)""", RegexOption.IGNORE_CASE)
        listOfNotNull(comment, description, artist, albumArtist, album, title).forEach { text ->
            luPar.find(text)?.let { return it.groupValues[1].trim().trimEnd('.') }
        }
        return composer?.trim()?.takeIf { it.isNotEmpty() && !it.equals(artist, ignoreCase = true) }
    }

    override fun equals(other: Any?): Boolean =
        other is Mp4Tags && title == other.title && artist == other.artist && album == other.album &&
            albumArtist == other.albumArtist && composer == other.composer && narratorTag == other.narratorTag &&
            comment == other.comment && description == other.description && pictureMime == other.pictureMime &&
            (picture?.contentEquals(other.picture ?: ByteArray(0)) ?: (other.picture == null))

    override fun hashCode(): Int =
        listOf(title, artist, album, albumArtist, composer, narratorTag, comment, description, pictureMime).hashCode()
}

/** Résultat de l'analyse d'un conteneur .m4b / .m4a / .mp4. */
data class Mp4Info(
    val durationMs: Long?,
    val tags: Mp4Tags?,
    val chapters: List<EmbeddedChapter>,
)

/**
 * Analyse sans dépendance des conteneurs MP4 / M4A / M4B (ISO BMFF) :
 * - durée exacte (`moov/mvhd`),
 * - étiquettes iTunes (`moov/udta/meta/ilst` : titre, auteur, voix `©nrt`/`©wrt`, pochette `covr`),
 * - chapitres Nero (`moov/udta/chpl`),
 * - chapitres QuickTime (`trak` texte référencé par `tref/chap`, tables `stts`/`stsc`/`stsz`/`stco`/`co64`).
 *
 * Fonctionne aussi bien sur un fichier local complet (même si `moov` est en fin de fichier après
 * un `mdat` de plusieurs centaines de Mo) que par plages HTTP `Range` sur Drive.
 */
object Mp4Probe {
    const val INITIAL_PROBE_BYTES = 256 * 1024
    const val MAX_MOOV_BYTES = 8 * 1024 * 1024
    const val MAX_CHAPTER_SAMPLE_READ_BYTES = 256 * 1024

    fun isMp4File(name: String, mimeType: String? = null): Boolean {
        val m = mimeType?.lowercase(Locale.ROOT).orEmpty()
        if (m.contains("mp4") || m.contains("m4a") || m.contains("m4b")) return true
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".m4b") || lower.endsWith(".m4a") || lower.endsWith(".mp4")
    }

    /** Analyse un tampon en mémoire (utile aux tests ou quand `moov` est en tête). */
    fun parse(bytes: ByteArray): Mp4Info? =
        probe(bytes.size.toLong()) { offset, length ->
            if (offset >= bytes.size || offset < 0 || length <= 0) {
                ByteArray(0)
            } else {
                val end = minOf(bytes.size.toLong(), offset + length).toInt()
                bytes.copyOfRange(offset.toInt(), end)
            }
        }

    /** Analyse un fichier local sans charger les données audio (`mdat`) en mémoire. */
    fun parse(file: File): Mp4Info? = runCatching {
        if (!file.isFile || file.length() < 16) return null
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            probe(len) { offset, count ->
                if (offset < 0 || offset >= len || count <= 0) {
                    ByteArray(0)
                } else {
                    val toRead = minOf(count.toLong(), len - offset).toInt()
                    val buf = ByteArray(toRead)
                    raf.seek(offset)
                    var done = 0
                    while (done < toRead) {
                        val n = raf.read(buf, done, toRead - done)
                        if (n <= 0) break
                        done += n
                    }
                    if (done == toRead) buf else buf.copyOf(done)
                }
            }
        }
    }.getOrNull()

    /**
     * Parcourt les boîtes de premier niveau pour localiser `moov` (en tête ou en queue après `mdat`)
     * et, si nécessaire, lit les échantillons texte des chapitres QuickTime.
     */
    inline fun probe(fileSize: Long, readRange: (offset: Long, length: Int) -> ByteArray): Mp4Info? {
        if (fileSize < 16) return null
        val headLen = minOf(fileSize, INITIAL_PROBE_BYTES.toLong()).toInt()
        val head = readRange(0L, headLen)
        if (head.size < 8) return null
        val firstType = ascii(head, 4, 4)
        if (firstType !in TOP_LEVEL_STARTERS) return null

        var pos = 0L
        var moovBytes: ByteArray? = null
        var safety = 0
        while (pos + 8 <= fileSize && safety++ < 64) {
            val hdr = if (pos + 16 <= head.size) {
                head.copyOfRange(pos.toInt(), minOf(head.size, (pos + 16).toInt()))
            } else {
                readRange(pos, 16)
            }
            if (hdr.size < 8) break
            val size32 = uint32(hdr, 0)
            val type = ascii(hdr, 4, 4)
            val hdrSize: Int
            val boxSize: Long
            when (size32) {
                1L -> {
                    if (hdr.size < 16) break
                    hdrSize = 16
                    boxSize = int64(hdr, 8)
                }
                0L -> {
                    hdrSize = 8
                    boxSize = fileSize - pos
                }
                else -> {
                    hdrSize = 8
                    boxSize = size32
                }
            }
            if (boxSize < hdrSize) break
            if (type == "moov") {
                val payloadSize = (boxSize - hdrSize).coerceAtMost(fileSize - pos - hdrSize)
                if (payloadSize in 8..MAX_MOOV_BYTES.toLong()) {
                    val bodyStart = pos + hdrSize
                    moovBytes = if (bodyStart + payloadSize <= head.size) {
                        head.copyOfRange(bodyStart.toInt(), (bodyStart + payloadSize).toInt())
                    } else {
                        readRange(bodyStart, payloadSize.toInt())
                    }
                }
                break
            }
            pos += boxSize
        }

        val moov = moovBytes ?: return null
        val parsed = parseMoov(moov)
        val qtChapters = if (parsed.neroChapters.isEmpty() && parsed.qtSamples.isNotEmpty()) {
            readQtChapterTitles(parsed.qtSamples, fileSize) { off, len ->
                if (off >= 0 && off + len <= head.size) {
                    head.copyOfRange(off.toInt(), (off + len).toInt())
                } else {
                    readRange(off, len)
                }
            }
        } else {
            emptyList()
        }
        val rawChapters = parsed.neroChapters.ifEmpty { qtChapters }
        val chapters = Mp3Probe.normalizeChapters(rawChapters, parsed.durationMs)
        return Mp4Info(
            durationMs = parsed.durationMs,
            tags = parsed.tags,
            chapters = chapters,
        )
    }

    @PublishedApi
    internal val TOP_LEVEL_STARTERS = setOf("ftyp", "styp", "moov", "mdat", "free", "skip", "wide")

    @PublishedApi
    internal data class QtChapterSample(
        val offset: Long,
        val size: Int,
        val startMs: Long,
        val endMs: Long,
    )

    @PublishedApi
    internal data class ParsedMoov(
        val durationMs: Long?,
        val tags: Mp4Tags?,
        val neroChapters: List<EmbeddedChapter>,
        val qtSamples: List<QtChapterSample>,
    )

    @PublishedApi
    internal inline fun readQtChapterTitles(
        samples: List<QtChapterSample>,
        fileSize: Long,
        readRange: (offset: Long, length: Int) -> ByteArray,
    ): List<EmbeddedChapter> {
        val valid = samples.filter { it.offset >= 0 && it.size in 2..4096 && it.offset + it.size <= fileSize }
        if (valid.isEmpty()) return emptyList()
        // Regroupe les lectures contiguës ou proches (<= 32 Ko) pour limiter les requêtes réseau.
        val result = ArrayList<EmbeddedChapter>(valid.size)
        var i = 0
        var totalRead = 0
        var ranges = 0
        while (i < valid.size && totalRead < MAX_CHAPTER_SAMPLE_READ_BYTES && ranges < 12) {
            val first = valid[i]
            var j = i
            while (j + 1 < valid.size) {
                val span = (valid[j + 1].offset + valid[j + 1].size) - first.offset
                if (span !in 1..65_536) break
                j++
            }
            val last = valid[j]
            val windowLen = ((last.offset + last.size) - first.offset).toInt()
            val window = readRange(first.offset, windowLen)
            totalRead += window.size
            ranges++
            for (k in i..j) {
                val s = valid[k]
                val rel = (s.offset - first.offset).toInt()
                val title = if (rel >= 0 && rel + s.size <= window.size) {
                    decodeQtTextSample(window, rel, s.size)
                } else {
                    null
                }
                result += EmbeddedChapter(title = title.orEmpty(), startMs = s.startMs, endMs = s.endMs)
            }
            i = j + 1
        }
        return result
    }

    @PublishedApi
    internal fun decodeQtTextSample(buf: ByteArray, offset: Int, length: Int): String? {
        if (length < 2 || offset + 2 > buf.size) return null
        val declared = uint16(buf, offset)
        val strLen = declared.coerceIn(0, minOf(length - 2, buf.size - offset - 2))
        if (strLen <= 0) return null
        val start = offset + 2
        val charset = when {
            strLen >= 2 && buf[start] == 0xFE.toByte() && buf[start + 1] == 0xFF.toByte() -> Charsets.UTF_16BE
            strLen >= 2 && buf[start] == 0xFF.toByte() && buf[start + 1] == 0xFE.toByte() -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        val skipBom = if (charset != Charsets.UTF_8) 2 else 0
        return String(buf, start + skipBom, strLen - skipBom, charset)
            .trim('\u0000', ' ', '\r', '\n', '\t')
            .takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ moov

    @PublishedApi
    internal fun parseMoov(moov: ByteArray): ParsedMoov {
        var durationMs: Long? = null
        var tags: Mp4Tags? = null
        var neroChapters: List<EmbeddedChapter> = emptyList()
        val chapTrackIds = HashSet<Long>()
        val tracks = mutableListOf<TrakData>()

        forEachBox(moov, 0, moov.size) { type, start, end ->
            when (type) {
                "mvhd" -> durationMs = parseMvhd(moov, start, end)
                "udta" -> {
                    val (uTags, chpl) = parseUdta(moov, start, end)
                    if (uTags != null) tags = uTags
                    if (chpl.isNotEmpty()) neroChapters = chpl
                }
                "meta" -> if (tags == null) tags = parseMeta(moov, start, end)
                "trak" -> parseTrak(moov, start, end)?.let { trak ->
                    chapTrackIds += trak.referencedChapTrackIds
                    tracks += trak
                }
            }
        }

        val chapterTrak = tracks.firstOrNull { it.trackId in chapTrackIds && it.samples.isNotEmpty() }
            ?: tracks.firstOrNull { it.handlerType in TEXT_HANDLERS && it.samples.size in 2..1000 }
        return ParsedMoov(
            durationMs = durationMs,
            tags = tags,
            neroChapters = neroChapters,
            qtSamples = chapterTrak?.samples.orEmpty(),
        )
    }

    private val TEXT_HANDLERS = setOf("text", "sbtl", "subp")

    private fun parseMvhd(b: ByteArray, start: Int, end: Int): Long? {
        if (end - start < 20) return null
        val version = b[start].toInt() and 0xFF
        return if (version == 1) {
            if (end - start < 32) return null
            val scale = uint32(b, start + 20)
            val dur = int64(b, start + 24)
            if (scale > 0 && dur > 0) dur * 1000L / scale else null
        } else {
            val scale = uint32(b, start + 12)
            val dur = uint32(b, start + 16)
            if (scale > 0 && dur > 0 && dur != 0xFFFFFFFFL) dur * 1000L / scale else null
        }
    }

    private fun parseUdta(b: ByteArray, start: Int, end: Int): Pair<Mp4Tags?, List<EmbeddedChapter>> {
        var tags: Mp4Tags? = null
        var chapters: List<EmbeddedChapter> = emptyList()
        forEachBox(b, start, end) { type, s, e ->
            when (type) {
                "meta" -> tags = parseMeta(b, s, e)
                "chpl" -> chapters = parseChpl(b, s, e)
            }
        }
        return tags to chapters
    }

    /** Chapitres Nero (`chpl`) : horodatages en unités de 100 ns (10 000 par milliseconde). */
    private fun parseChpl(b: ByteArray, start: Int, end: Int): List<EmbeddedChapter> {
        if (end - start < 6) return emptyList()
        val version = b[start].toInt() and 0xFF
        var p: Int
        val count: Int
        if (version == 0) {
            count = b[start + 5].toInt() and 0xFF
            p = start + 6
        } else {
            if (end - start < 9) return emptyList()
            // Octets [4..8] : 1 octet réservé + uint32 (MP4v2) ou uint32(0) + uint8 (FFmpeg) — mêmes octets !
            count = uint32(b, start + 5).toInt().coerceIn(0, 2_000)
            p = start + 9
        }
        val list = ArrayList<EmbeddedChapter>(count.coerceAtMost(500))
        repeat(count) { idx ->
            if (p + 9 > end) return@repeat
            val ticks100ns = int64(b, p)
            val len = b[p + 8].toInt() and 0xFF
            p += 9
            if (p + len > end) return list
            val title = String(b, p, len, Charsets.UTF_8).trim('\u0000', ' ').ifEmpty { "Chapitre ${idx + 1}" }
            p += len
            list += EmbeddedChapter(title = title, startMs = (ticks100ns / 10_000L).coerceAtLeast(0L), endMs = 0L)
        }
        return list
    }

    private fun parseMeta(b: ByteArray, start: Int, end: Int): Mp4Tags? {
        if (end - start < 8) return null
        // La boîte `meta` commence normalement par 4 octets version/flags avant ses sous-boîtes.
        val childStart = if (start + 12 <= end && isPlausibleType(b, start + 8)) start + 4 else start
        var tags: Mp4Tags? = null
        forEachBox(b, childStart, end) { type, s, e ->
            if (type == "ilst") tags = parseIlst(b, s, e)
        }
        return tags
    }

    private fun parseIlst(b: ByteArray, start: Int, end: Int): Mp4Tags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var composer: String? = null
        var narrator: String? = null
        var comment: String? = null
        var description: String? = null
        var picture: ByteArray? = null
        var pictureMime: String? = null

        forEachBox(b, start, end) { type, s, e ->
            when (type) {
                "\u00A9nam" -> title = readDataText(b, s, e) ?: title
                "\u00A9ART" -> artist = readDataText(b, s, e) ?: artist
                "\u00A9alb" -> album = readDataText(b, s, e) ?: album
                "aART" -> albumArtist = readDataText(b, s, e) ?: albumArtist
                "\u00A9wrt" -> composer = readDataText(b, s, e) ?: composer
                "\u00A9nrt" -> narrator = readDataText(b, s, e) ?: narrator
                "\u00A9cmt" -> comment = readDataText(b, s, e) ?: comment
                "desc", "ldes" -> description = readDataText(b, s, e) ?: description
                "covr" -> if (picture == null) {
                    readDataBytes(b, s, e)?.let { (mime, bytes) ->
                        pictureMime = mime
                        picture = bytes
                    }
                }
                "----" -> {
                    val (freeName, freeVal) = parseFreeform(b, s, e)
                    if (freeName != null && freeVal != null && narrator == null) {
                        val k = freeName.lowercase(Locale.ROOT)
                        if (k.contains("narrat") || k.contains("reader") || k.contains("lecteur") || k.contains("voix")) {
                            narrator = freeVal
                        }
                    }
                }
            }
        }
        return Mp4Tags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            composer = composer,
            narratorTag = narrator,
            comment = comment,
            description = description,
            picture = picture,
            pictureMime = pictureMime,
        )
    }

    private fun readDataText(b: ByteArray, start: Int, end: Int): String? {
        var result: String? = null
        forEachBox(b, start, end) { type, s, e ->
            if (type == "data" && e - s > 8 && result == null) {
                val dataType = int32(b, s) and 0xFFFFFF
                val charset = if (dataType == 2) Charsets.UTF_16BE else Charsets.UTF_8
                result = String(b, s + 8, e - (s + 8), charset).trim('\u0000', ' ').takeIf { it.isNotEmpty() }
            }
        }
        return result
    }

    private fun readDataBytes(b: ByteArray, start: Int, end: Int): Pair<String, ByteArray>? {
        var result: Pair<String, ByteArray>? = null
        forEachBox(b, start, end) { type, s, e ->
            if (type == "data" && e - s > 8 && result == null) {
                val dataType = int32(b, s) and 0xFFFFFF
                val bytes = b.copyOfRange(s + 8, e)
                val mime = when {
                    dataType == 14 || (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) -> "image/png"
                    else -> "image/jpeg"
                }
                result = mime to bytes
            }
        }
        return result
    }

    private fun parseFreeform(b: ByteArray, start: Int, end: Int): Pair<String?, String?> {
        var name: String? = null
        var value: String? = null
        forEachBox(b, start, end) { type, s, e ->
            when (type) {
                "name" -> if (e - s > 4) name = String(b, s + 4, e - (s + 4), Charsets.UTF_8).trim('\u0000', ' ')
                "data" -> if (e - s > 8) value = String(b, s + 8, e - (s + 8), Charsets.UTF_8).trim('\u0000', ' ')
            }
        }
        return name to value
    }

    // ------------------------------------------------------------------ pistes & chapitres QuickTime

    private data class TrakData(
        val trackId: Long,
        val handlerType: String?,
        val referencedChapTrackIds: List<Long>,
        val samples: List<QtChapterSample>,
    )

    private fun parseTrak(b: ByteArray, start: Int, end: Int): TrakData? {
        var trackId = 0L
        var timescale = 0L
        var handlerType: String? = null
        val chapIds = mutableListOf<Long>()
        var stblRange: Pair<Int, Int>? = null

        forEachBox(b, start, end) { type, s, e ->
            when (type) {
                "tkhd" -> {
                    val v = if (s < e) b[s].toInt() and 0xFF else 0
                    val idOff = if (v == 1) s + 20 else s + 12
                    if (idOff + 4 <= e) trackId = uint32(b, idOff)
                }
                "tref" -> forEachBox(b, s, e) { refType, rs, re ->
                    if (refType == "chap") {
                        var p = rs
                        while (p + 4 <= re) {
                            chapIds += uint32(b, p)
                            p += 4
                        }
                    }
                }
                "mdia" -> forEachBox(b, s, e) { mType, ms, me ->
                    when (mType) {
                        "mdhd" -> {
                            val v = if (ms < me) b[ms].toInt() and 0xFF else 0
                            val scaleOff = if (v == 1) ms + 20 else ms + 12
                            if (scaleOff + 4 <= me) timescale = uint32(b, scaleOff)
                        }
                        "hdlr" -> if (ms + 12 <= me) handlerType = ascii(b, ms + 8, 4)
                        "minf" -> forEachBox(b, ms, me) { minfType, stS, stE ->
                            if (minfType == "stbl") stblRange = stS to stE
                        }
                    }
                }
            }
        }
        val samples = if (stblRange != null && timescale > 0 && handlerType in TEXT_HANDLERS) {
            parseStblSamples(b, stblRange!!.first, stblRange!!.second, timescale)
        } else {
            emptyList()
        }
        return TrakData(trackId, handlerType, chapIds, samples)
    }

    private data class StscEntry(val firstChunk: Int, val samplesPerChunk: Int)

    private fun parseStblSamples(b: ByteArray, start: Int, end: Int, timescale: Long): List<QtChapterSample> {
        val durations = mutableListOf<Long>()
        val sizes = mutableListOf<Int>()
        var defaultSampleSize = 0
        var sampleCountFromStsz = 0
        val stsc = mutableListOf<StscEntry>()
        val chunkOffsets = mutableListOf<Long>()

        forEachBox(b, start, end) { type, s, e ->
            when (type) {
                "stts" -> if (s + 8 <= e) {
                    val count = uint32(b, s + 4).toInt().coerceIn(0, 2_000)
                    var p = s + 8
                    repeat(count) {
                        if (p + 8 > e) return@repeat
                        val sc = uint32(b, p).toInt().coerceIn(0, 2_000)
                        val delta = uint32(b, p + 4)
                        p += 8
                        repeat(sc) { if (durations.size < 2_000) durations += delta }
                    }
                }
                "stsz" -> if (s + 12 <= e) {
                    defaultSampleSize = uint32(b, s + 4).toInt()
                    sampleCountFromStsz = uint32(b, s + 8).toInt().coerceIn(0, 2_000)
                    if (defaultSampleSize == 0) {
                        var p = s + 12
                        repeat(sampleCountFromStsz) {
                            if (p + 4 > e) return@repeat
                            sizes += uint32(b, p).toInt()
                            p += 4
                        }
                    }
                }
                "stsc" -> if (s + 8 <= e) {
                    val count = uint32(b, s + 4).toInt().coerceIn(0, 2_000)
                    var p = s + 8
                    repeat(count) {
                        if (p + 12 > e) return@repeat
                        stsc += StscEntry(uint32(b, p).toInt(), uint32(b, p + 4).toInt())
                        p += 12
                    }
                }
                "stco" -> if (s + 8 <= e) {
                    val count = uint32(b, s + 4).toInt().coerceIn(0, 2_000)
                    var p = s + 8
                    repeat(count) {
                        if (p + 4 > e) return@repeat
                        chunkOffsets += uint32(b, p)
                        p += 4
                    }
                }
                "co64" -> if (s + 8 <= e) {
                    val count = uint32(b, s + 4).toInt().coerceIn(0, 2_000)
                    var p = s + 8
                    repeat(count) {
                        if (p + 8 > e) return@repeat
                        chunkOffsets += int64(b, p)
                        p += 8
                    }
                }
            }
        }

        val totalSamples = if (defaultSampleSize > 0) sampleCountFromStsz else sizes.size
        if (totalSamples <= 0 || chunkOffsets.isEmpty() || durations.isEmpty()) return emptyList()

        val sampleOffsets = ArrayList<Long>(totalSamples)
        var sampleIdx = 0
        for (chunkZeroBased in chunkOffsets.indices) {
            val chunkNumber = chunkZeroBased + 1
            val perChunk = stsc.lastOrNull { it.firstChunk <= chunkNumber }?.samplesPerChunk
                ?: if (chunkOffsets.size == totalSamples) 1 else totalSamples
            var posInChunk = chunkOffsets[chunkZeroBased]
            repeat(perChunk) {
                if (sampleIdx >= totalSamples) return@repeat
                sampleOffsets += posInChunk
                val sz = if (defaultSampleSize > 0) defaultSampleSize else sizes[sampleIdx]
                posInChunk += sz
                sampleIdx++
            }
            if (sampleIdx >= totalSamples) break
        }

        val count = minOf(sampleOffsets.size, durations.size)
        val out = ArrayList<QtChapterSample>(count)
        var ticks = 0L
        for (i in 0 until count) {
            val startMs = ticks * 1000L / timescale
            ticks += durations[i]
            val endMs = ticks * 1000L / timescale
            val sz = if (defaultSampleSize > 0) defaultSampleSize else sizes[i]
            out += QtChapterSample(sampleOffsets[i], sz, startMs, endMs)
        }
        return out
    }

    // ------------------------------------------------------------------ bas niveau ISO BMFF

    private inline fun forEachBox(b: ByteArray, from: Int, to: Int, visit: (type: String, bodyStart: Int, bodyEnd: Int) -> Unit) {
        var p = from
        while (p + 8 <= to) {
            val size32 = uint32(b, p)
            val type = ascii(b, p + 4, 4)
            val hdr: Int
            val total: Long
            when (size32) {
                1L -> {
                    if (p + 16 > to) return
                    hdr = 16
                    total = int64(b, p + 8)
                }
                0L -> {
                    hdr = 8
                    total = (to - p).toLong()
                }
                else -> {
                    hdr = 8
                    total = size32
                }
            }
            if (total < hdr || p + total > to) return
            val end = (p + total).toInt()
            visit(type, p + hdr, end)
            p = end
        }
    }

    private fun isPlausibleType(b: ByteArray, offset: Int): Boolean {
        if (offset + 4 > b.size) return false
        for (i in 0 until 4) {
            val c = b[offset + i].toInt() and 0xFF
            if (c !in 0x20..0x7E && c != 0xA9) return false
        }
        return true
    }

    @PublishedApi
    internal fun ascii(b: ByteArray, offset: Int, len: Int): String =
        if (offset + len <= b.size) String(b, offset, len, Charsets.ISO_8859_1) else ""

    @PublishedApi
    internal fun uint16(b: ByteArray, o: Int): Int =
        if (o + 2 > b.size) 0 else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    @PublishedApi
    internal fun int32(b: ByteArray, o: Int): Int =
        if (o + 4 > b.size) 0 else ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    @PublishedApi
    internal fun uint32(b: ByteArray, o: Int): Long = int32(b, o).toLong() and 0xFFFFFFFFL

    @PublishedApi
    internal fun int64(b: ByteArray, o: Int): Long =
        if (o + 8 > b.size) 0L else (uint32(b, o) shl 32) or uint32(b, o + 4)
}
