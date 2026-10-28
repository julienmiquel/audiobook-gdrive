package com.julienmiquel.audiotheque.core.parse

import java.nio.charset.Charset
import java.util.Locale

/** Chapitre embarqué dans les métadonnées d'un fichier audio (.m4b, .m4a ou ID3 CHAP). */
data class EmbeddedChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

/** Étiquettes ID3v2 utiles à l'app. */
data class Id3Tags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val composer: String? = null,
    val comment: String? = null,
    val genre: String? = null,
    /** Champs TXXX : description → valeur. */
    val userTexts: Map<String, String> = emptyMap(),
    val picture: ByteArray? = null,
    val pictureMime: String? = null,
    /** Chapitres ID3v2 (trames CHAP), triés par position de départ. */
    val chapters: List<EmbeddedChapter> = emptyList(),
) {
    /**
     * Voix du livre quand les tags la portent : champ TXXX dédié, mention « lu par » dans un
     * champ texte, ou à défaut le compositeur (convention fréquente des livres audio).
     */
    fun narrator(): String? {
        userTexts.entries.firstOrNull { (k, _) ->
            val key = k.lowercase(Locale.ROOT)
            key.contains("narrat") || key.contains("lecteur") || key.contains("reader") || key.contains("voix")
        }?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val luPar = Regex("""(?:lu|lue)\s+par\s*:?\s*([^,;/()\[\]]+)""", RegexOption.IGNORE_CASE)
        listOfNotNull(comment, artist, albumArtist, album, title).forEach { text ->
            luPar.find(text)?.let { return it.groupValues[1].trim().trimEnd('.') }
        }
        return composer?.trim()?.takeIf { it.isNotEmpty() && !it.equals(artist, ignoreCase = true) }
    }

    override fun equals(other: Any?): Boolean =
        other is Id3Tags && title == other.title && artist == other.artist && album == other.album &&
            albumArtist == other.albumArtist && composer == other.composer && comment == other.comment &&
            genre == other.genre && userTexts == other.userTexts && pictureMime == other.pictureMime &&
            chapters == other.chapters &&
            (picture?.contentEquals(other.picture ?: ByteArray(0)) ?: (other.picture == null))

    override fun hashCode(): Int = listOf(title, artist, album, composer, comment, genre, userTexts, chapters).hashCode()
}

/** Résultat de l'analyse des premiers Ko d'un mp3. */
data class Mp3Info(
    /** Taille totale de l'étiquette ID3v2 en tête de fichier (0 si absente). */
    val id3Size: Int,
    val tags: Id3Tags?,
    val bitrateKbps: Int?,
    val sampleRate: Int?,
    val channels: Int?,
    val samplesPerFrame: Int?,
    /** Nombre de trames annoncé par l'en-tête Xing/Info/VBRI (débit variable). */
    val vbrFrames: Long?,
    val vbrBytes: Long?,
) {
    val hasAudioHeader: Boolean get() = sampleRate != null

    /** Durée de ce fichier, connaissant sa taille. */
    fun durationMs(fileSize: Long): Long? {
        if (vbrFrames != null && vbrFrames > 0 && sampleRate != null && samplesPerFrame != null) {
            return vbrFrames * samplesPerFrame * 1000L / sampleRate
        }
        if (bitrateKbps != null && bitrateKbps > 0) {
            // octets × 8 = bits ; 1 kbit/s = 1 bit/ms.
            return (fileSize - id3Size).coerceAtLeast(0) * 8L / bitrateKbps
        }
        return null
    }

    /** Octets audio par seconde, pour estimer la durée des autres pistes à partir de leur taille. */
    fun bytesPerSecond(fileSize: Long): Double? {
        val duration = durationMs(fileSize) ?: return null
        if (duration <= 0) return null
        val audioBytes = vbrBytes ?: (fileSize - id3Size).coerceAtLeast(0)
        return audioBytes * 1000.0 / duration
    }
}

/**
 * Analyse sans dépendance des premiers octets d'un mp3 : étiquette ID3v2 (v2.2 à v2.4) et
 * première trame MPEG (débit, fréquence, en-tête Xing/Info/VBRI). Sert à estimer la durée
 * d'un livre non téléchargé et à lire la voix et la pochette dans les tags.
 */
object Mp3Probe {
    /** Premiers octets demandés à Drive pour connaître le débit. */
    const val PROBE_BYTES = 64 * 1024

    /** Au-delà, on ne télécharge pas l'étiquette entière pour sa pochette. */
    const val MAX_TAG_BYTES = 3 * 1024 * 1024

    private val bitratesV1L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val bitratesV1L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val bitratesV1L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val bitratesV2L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val bitratesV2L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    /** Taille totale de l'étiquette ID3v2 annoncée par les 10 premiers octets, 0 si absente. */
    fun id3TagSize(b: ByteArray): Int {
        if (b.size < 10 || b[0] != 'I'.code.toByte() || b[1] != 'D'.code.toByte() || b[2] != '3'.code.toByte()) {
            return 0
        }
        val footer = (b[5].toInt() and 0x10) != 0
        return 10 + syncSafe(b, 6) + if (footer) 10 else 0
    }

    fun parse(bytes: ByteArray): Mp3Info {
        val tagSize = id3TagSize(bytes)
        val tags = if (tagSize > 0) runCatching { parseId3(bytes, tagSize) }.getOrNull() else null
        val frame = findFrame(bytes, tagSize)
        return Mp3Info(
            id3Size = tagSize,
            tags = tags,
            bitrateKbps = frame?.bitrateKbps,
            sampleRate = frame?.sampleRate,
            channels = frame?.channels,
            samplesPerFrame = frame?.samplesPerFrame,
            vbrFrames = frame?.vbrFrames,
            vbrBytes = frame?.vbrBytes,
        )
    }

    // ---------------------------------------------------------------- MPEG

    private data class Frame(
        val offset: Int,
        val versionId: Int,
        val layer: Int,
        val bitrateKbps: Int,
        val sampleRate: Int,
        val channels: Int,
        val samplesPerFrame: Int,
        val length: Int,
        val vbrFrames: Long? = null,
        val vbrBytes: Long? = null,
    )

    private fun findFrame(b: ByteArray, start: Int): Frame? {
        var i = start.coerceAtLeast(0)
        while (i <= b.size - 4) {
            if (b[i] == 0xFF.toByte() && (b[i + 1].toInt() and 0xE0) == 0xE0) {
                val frame = decodeHeader(b, i)
                if (frame != null) {
                    val next = frame.offset + frame.length
                    val confirmed = next + 1 >= b.size ||
                        (b[next] == 0xFF.toByte() && (b[next + 1].toInt() and 0xE0) == 0xE0)
                    if (confirmed) return withVbrHeader(b, frame)
                }
            }
            i++
        }
        return null
    }

    private fun decodeHeader(b: ByteArray, i: Int): Frame? {
        val b1 = b[i + 1].toInt() and 0xFF
        val b2 = b[i + 2].toInt() and 0xFF
        val b3 = b[i + 3].toInt() and 0xFF
        val versionId = (b1 shr 3) and 0x3 // 0 = MPEG 2.5, 2 = MPEG 2, 3 = MPEG 1
        val layerBits = (b1 shr 1) and 0x3 // 1 = III, 2 = II, 3 = I
        if (versionId == 1 || layerBits == 0) return null
        val bitrateIndex = (b2 shr 4) and 0xF
        val sampleIndex = (b2 shr 2) and 0x3
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleIndex == 3) return null
        val padding = (b2 shr 1) and 0x1
        val layer = 4 - layerBits
        val mpeg1 = versionId == 3
        val bitrate = when {
            mpeg1 && layer == 1 -> bitratesV1L1
            mpeg1 && layer == 2 -> bitratesV1L2
            mpeg1 -> bitratesV1L3
            layer == 1 -> bitratesV2L1
            else -> bitratesV2L23
        }[bitrateIndex]
        val sampleRate = when (versionId) {
            3 -> intArrayOf(44100, 48000, 32000)
            2 -> intArrayOf(22050, 24000, 16000)
            else -> intArrayOf(11025, 12000, 8000)
        }[sampleIndex]
        val samplesPerFrame = when {
            layer == 1 -> 384
            layer == 2 -> 1152
            mpeg1 -> 1152
            else -> 576
        }
        val length = if (layer == 1) {
            (12 * bitrate * 1000 / sampleRate + padding) * 4
        } else {
            samplesPerFrame / 8 * bitrate * 1000 / sampleRate + padding
        }
        if (length < 24) return null
        val channels = if (((b3 shr 6) and 0x3) == 3) 1 else 2
        return Frame(i, versionId, layer, bitrate, sampleRate, channels, samplesPerFrame, length)
    }

    private fun withVbrHeader(b: ByteArray, f: Frame): Frame {
        val sideInfo = when {
            f.versionId == 3 -> if (f.channels == 1) 17 else 32
            else -> if (f.channels == 1) 9 else 17
        }
        val xing = f.offset + 4 + sideInfo
        if (xing + 16 <= b.size) {
            val tag = String(b, xing, 4, Charsets.ISO_8859_1)
            if (tag == "Xing" || tag == "Info") {
                val flags = int32(b, xing + 4)
                var pos = xing + 8
                var frames: Long? = null
                var bytes: Long? = null
                if (flags and 0x1 != 0 && pos + 4 <= b.size) {
                    frames = int32(b, pos).toLong() and 0xFFFFFFFFL
                    pos += 4
                }
                if (flags and 0x2 != 0 && pos + 4 <= b.size) {
                    bytes = int32(b, pos).toLong() and 0xFFFFFFFFL
                }
                return f.copy(vbrFrames = frames, vbrBytes = bytes)
            }
        }
        val vbri = f.offset + 4 + 32
        if (vbri + 18 <= b.size && String(b, vbri, 4, Charsets.ISO_8859_1) == "VBRI") {
            val bytes = int32(b, vbri + 10).toLong() and 0xFFFFFFFFL
            val frames = int32(b, vbri + 14).toLong() and 0xFFFFFFFFL
            return f.copy(vbrFrames = frames, vbrBytes = bytes)
        }
        return f
    }

    // ---------------------------------------------------------------- ID3v2

    private fun parseId3(b: ByteArray, tagSize: Int): Id3Tags {
        val major = b[3].toInt()
        val flags = b[5].toInt() and 0xFF
        var data = b.copyOfRange(10, minOf(tagSize, b.size))
        if (flags and 0x80 != 0 && major <= 3) data = removeUnsynchronisation(data)
        var pos = 0
        if (flags and 0x40 != 0 && major >= 3 && data.size >= 4) {
            pos = if (major == 4) syncSafe(data, 0) else int32(data, 0) + 4
        }
        val headerSize = if (major == 2) 6 else 10
        val texts = HashMap<String, String>()
        val userTexts = LinkedHashMap<String, String>()
        val rawChapters = mutableListOf<EmbeddedChapter>()
        var comment: String? = null
        var picture: ByteArray? = null
        var pictureMime: String? = null

        while (pos + headerSize <= data.size) {
            val id: String
            val size: Int
            if (major == 2) {
                id = String(data, pos, 3, Charsets.ISO_8859_1)
                size = int24(data, pos + 3)
            } else {
                id = String(data, pos, 4, Charsets.ISO_8859_1)
                size = if (major == 4) syncSafe(data, pos + 4) else int32(data, pos + 4)
            }
            if (id[0] == '\u0000' || size <= 0 || !id.all { it.isLetterOrDigit() }) break
            val bodyStart = pos + headerSize
            val complete = bodyStart + size <= data.size
            val body = data.copyOfRange(bodyStart, minOf(bodyStart + size, data.size))
            val key = if (major == 2) v22Ids[id] ?: id else id
            when {
                key == "TXXX" -> parseUserText(body)?.let { (d, v) -> userTexts[d] = v }
                key.startsWith("T") -> decodeText(body)?.let { texts[key] = it }
                key == "COMM" -> if (comment == null) comment = parseComment(body)
                key == "CHAP" && complete -> parseChap(body, major)?.let { rawChapters += it }
                key == "APIC" && complete && picture == null -> parsePicture(body, major == 2)?.let {
                    pictureMime = it.first
                    picture = it.second
                }
            }
            pos = bodyStart + size
        }
        val chapters = normalizeChapters(rawChapters)
        return Id3Tags(
            title = texts["TIT2"],
            artist = texts["TPE1"],
            album = texts["TALB"],
            albumArtist = texts["TPE2"],
            composer = texts["TCOM"],
            comment = comment,
            genre = texts["TCON"],
            userTexts = userTexts,
            picture = picture,
            pictureMime = pictureMime,
            chapters = chapters,
        )
    }

    private fun parseChap(body: ByteArray, major: Int): EmbeddedChapter? {
        val idEnd = terminator(body, 0, 0)
        var p = idEnd + 1
        if (p + 16 > body.size) return null
        val startMs = int32(body, p).toLong() and 0xFFFFFFFFL
        val rawEnd = int32(body, p + 4).toLong() and 0xFFFFFFFFL
        val endMs = if (rawEnd == 0xFFFFFFFFL) 0L else rawEnd
        p += 16
        var title: String? = null
        while (p + 10 <= body.size) {
            val subId = String(body, p, 4, Charsets.ISO_8859_1)
            val subSize = if (major == 4) syncSafe(body, p + 4) else int32(body, p + 4)
            if (subSize <= 0 || p + 10 + subSize > body.size || !subId.all { it.isLetterOrDigit() }) break
            if (subId == "TIT2" && title == null) {
                title = decodeText(body.copyOfRange(p + 10, p + 10 + subSize))
            }
            p += 10 + subSize
        }
        return EmbeddedChapter(title = title.orEmpty(), startMs = startMs, endMs = endMs)
    }

    fun normalizeChapters(raw: List<EmbeddedChapter>, totalDurationMs: Long? = null): List<EmbeddedChapter> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.sortedBy { it.startMs }.distinctBy { it.startMs }
        return sorted.mapIndexedNotNull { index, ch ->
            val nextStart = sorted.getOrNull(index + 1)?.startMs
            val resolvedEnd = when {
                ch.endMs > ch.startMs && (nextStart == null || ch.endMs <= nextStart) -> ch.endMs
                nextStart != null -> nextStart
                totalDurationMs != null && totalDurationMs > ch.startMs -> totalDurationMs
                else -> ch.endMs
            }
            val label = ch.title.trim().ifEmpty { "Chapitre ${index + 1}" }
            EmbeddedChapter(label, ch.startMs.coerceAtLeast(0L), resolvedEnd.coerceAtLeast(ch.startMs))
        }
    }

    private val v22Ids = mapOf(
        "TT2" to "TIT2", "TP1" to "TPE1", "TAL" to "TALB", "TP2" to "TPE2", "TCM" to "TCOM",
        "TCO" to "TCON", "TXX" to "TXXX", "COM" to "COMM", "PIC" to "APIC",
    )

    private fun charsetFor(encoding: Int): Charset = when (encoding) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun decodeText(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val text = String(body, 1, body.size - 1, charsetFor(body[0].toInt()))
        return text.split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" / ").takeIf { it.isNotEmpty() }
    }

    /** Fin d'une chaîne terminée par un zéro (deux zéros alignés en UTF-16). */
    private fun terminator(body: ByteArray, start: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < body.size) {
                if (body[i].toInt() == 0 && body[i + 1].toInt() == 0) return i
                i += 2
            }
            return body.size
        }
        var i = start
        while (i < body.size && body[i].toInt() != 0) i++
        return i
    }

    private fun terminatorLength(encoding: Int) = if (encoding == 1 || encoding == 2) 2 else 1

    private fun parseUserText(body: ByteArray): Pair<String, String>? {
        if (body.size < 2) return null
        val enc = body[0].toInt()
        val end = terminator(body, 1, enc)
        val description = String(body, 1, end - 1, charsetFor(enc)).trim()
        val valueStart = (end + terminatorLength(enc)).coerceAtMost(body.size)
        val value = String(body, valueStart, body.size - valueStart, charsetFor(enc)).trim('\u0000', ' ')
        return if (value.isEmpty()) null else description to value
    }

    private fun parseComment(body: ByteArray): String? {
        if (body.size < 5) return null
        val enc = body[0].toInt()
        val end = terminator(body, 4, enc)
        val textStart = (end + terminatorLength(enc)).coerceAtMost(body.size)
        return String(body, textStart, body.size - textStart, charsetFor(enc))
            .trim('\u0000', ' ').takeIf { it.isNotEmpty() }
    }

    private fun parsePicture(body: ByteArray, v22: Boolean): Pair<String, ByteArray>? {
        if (body.size < 6) return null
        val enc = body[0].toInt()
        val mime: String
        var pos: Int
        if (v22) {
            val format = String(body, 1, 3, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
            mime = if (format == "png") "image/png" else "image/jpeg"
            pos = 4
        } else {
            var end = 1
            while (end < body.size && body[end].toInt() != 0) end++
            mime = String(body, 1, end - 1, Charsets.ISO_8859_1).ifEmpty { "image/jpeg" }
            pos = end + 1
        }
        pos += 1 // type d'image
        if (pos >= body.size) return null
        pos = terminator(body, pos, enc) + terminatorLength(enc)
        if (pos >= body.size) return null
        return mime to body.copyOfRange(pos, body.size)
    }

    private fun removeUnsynchronisation(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            out.write(data[i].toInt())
            if (data[i] == 0xFF.toByte() && i + 1 < data.size && data[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    private fun syncSafe(b: ByteArray, o: Int): Int {
        if (o + 4 > b.size) return 0
        return ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)
    }

    private fun int32(b: ByteArray, o: Int): Int {
        if (o + 4 > b.size) return 0
        return ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    }

    private fun int24(b: ByteArray, o: Int): Int {
        if (o + 3 > b.size) return 0
        return ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)
    }
}
