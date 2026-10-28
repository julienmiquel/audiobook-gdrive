package com.julienmiquel.audiotheque.core.parse

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Test

/** Analyse des premiers Ko d'un fichier mp3 de test (tags ID3, pochette, débit). */
class Mp3ProbeTest {

    private val mp3 = File("../testdata/audio/civilisations_01.mp3")

    @Before
    fun fixturePresent() {
        check(mp3.exists()) { "Fichier de test absent : ${mp3.absolutePath}" }
    }

    @Test
    fun `tags ID3, voix et pochette intégrée`() {
        val bytes = mp3.readBytes()
        val head = bytes.copyOf(minOf(bytes.size, Mp3Probe.MAX_TAG_BYTES))
        val info = Mp3Probe.parse(head)
        val tags = info.tags!!
        assertThat(tags.title).isEqualTo("Saga de Freydis (1)")
        assertThat(tags.artist).isEqualTo("Laurent Binet")
        assertThat(tags.album).isEqualTo("Civilisations")
        assertThat(tags.narrator()).isEqualTo("Audrey (voix de synthèse)")
        assertThat(tags.picture).isNotNull()
        assertThat(info.sampleRate).isEqualTo(22050)
    }

    @Test
    fun `durée estimée à partir des premiers Ko`() {
        val bytes = mp3.readBytes()
        val info = Mp3Probe.parse(bytes.copyOf(minOf(bytes.size, Mp3Probe.MAX_TAG_BYTES)))
        val duration = info.durationMs(bytes.size.toLong())!!
        // ffprobe : 27,44 s.
        assertThat(duration).isIn(com.google.common.collect.Range.closed(26_000L, 29_000L))
    }

    @Test
    fun `sans étiquette ID3`() {
        assertThat(Mp3Probe.id3TagSize(ByteArray(16))).isEqualTo(0)
    }

    @Test
    fun `voix lue dans un champ « lu par »`() {
        val tags = Id3Tags(comment = "Livre audio lu par Thierry Blanc.")
        assertThat(tags.narrator()).isEqualTo("Thierry Blanc")
    }

    @Test
    fun `compositeur pris comme voix sauf s'il est l'auteur`() {
        assertThat(Id3Tags(artist = "Romain Gary", composer = "Thierry Blanc").narrator()).isEqualTo("Thierry Blanc")
        assertThat(Id3Tags(artist = "Romain Gary", composer = "Romain Gary").narrator()).isNull()
    }

    @Test
    fun `chapitres ID3v2 CHAP avec sous-trames TIT2`() {
        fun tit2Frame(title: String): ByteArray {
            val payload = byteArrayOf(3) + title.toByteArray(Charsets.UTF_8) // encoding 3 = UTF-8
            val size = payload.size
            return "TIT2".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte(), 0, 0) +
                payload
        }

        fun chapFrame(id: String, startMs: Int, endMs: Int, title: String): ByteArray {
            val sub = tit2Frame(title)
            val body = java.io.ByteArrayOutputStream().also { out ->
                out.write(id.toByteArray(Charsets.ISO_8859_1))
                out.write(0)
                java.io.DataOutputStream(out).apply {
                    writeInt(startMs)
                    writeInt(endMs)
                    writeInt(-1)
                    writeInt(-1)
                }
                out.write(sub)
            }.toByteArray()
            val s = body.size
            return "CHAP".toByteArray(Charsets.ISO_8859_1) +
                byteArrayOf((s ushr 24).toByte(), (s ushr 16).toByte(), (s ushr 8).toByte(), s.toByte(), 0, 0) +
                body
        }

        val frames = chapFrame("chp2", 120_000, 300_000, "Chapitre 2") +
            chapFrame("chp1", 0, 120_000, "Prologue")
        val s = frames.size
        val syncSafe = byteArrayOf(
            ((s ushr 21) and 0x7F).toByte(),
            ((s ushr 14) and 0x7F).toByte(),
            ((s ushr 7) and 0x7F).toByte(),
            (s and 0x7F).toByte(),
        )
        val id3 = "ID3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0, 0) + syncSafe + frames
        val info = Mp3Probe.parse(id3)
        assertThat(info.tags?.chapters).containsExactly(
            EmbeddedChapter("Prologue", 0L, 120_000L),
            EmbeddedChapter("Chapitre 2", 120_000L, 300_000L),
        ).inOrder()
    }
}
