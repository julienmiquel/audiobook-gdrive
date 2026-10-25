package com.julienmiquel.audiotheque.core.parse

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Analyse des premiers Ko d'un vrai mp3 de démonstration (tags ID3, pochette, débit). */
class Mp3ProbeTest {

    private val asset = File("../app/src/main/assets/demo/civilisations_01.mp3")

    @Test
    fun `tags ID3, voix et pochette intégrée`() {
        assumeTrue("asset de démonstration absent", asset.exists())
        val bytes = asset.readBytes()
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
        assumeTrue("asset de démonstration absent", asset.exists())
        val bytes = asset.readBytes()
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
}
