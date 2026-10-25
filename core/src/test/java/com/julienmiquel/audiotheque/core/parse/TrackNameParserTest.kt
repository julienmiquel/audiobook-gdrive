package com.julienmiquel.audiotheque.core.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackNameParserTest {

    @Test
    fun `motif de la spécification - ordre global, partie, chapitre, titre`() {
        val t = TrackNameParser.parse("091_P4_04_LES_AVENTURES_DE_CERVANTES.mp3")
        assertThat(t.number).isEqualTo(91)
        assertThat(t.part).isEqualTo(4)
        assertThat(t.chapter).isEqualTo(4)
        assertThat(t.title).isEqualTo("Les aventures de cervantes")
    }

    @Test
    fun `numéro et chapitre en clair`() {
        val t = TrackNameParser.parse("01 - Chapitre 1.mp3")
        assertThat(t.number).isEqualTo(1)
        assertThat(t.chapter).isEqualTo(1)
        assertThat(t.title).isEqualTo("Chapitre 1")
    }

    @Test
    fun `partie et chapitre sans numéro global`() {
        val t = TrackNameParser.parse("Partie 1 - Chapitre 3.mp3")
        assertThat(t.part).isEqualTo(1)
        assertThat(t.chapter).isEqualTo(3)
    }

    @Test
    fun `disque et piste`() {
        val t = TrackNameParser.parse("CD1_Piste02.mp3")
        assertThat(t.part).isEqualTo(1)
    }

    @Test
    fun `ordre global quand toutes les pistes sont numérotées`() {
        val names = listOf("010_P4_03_X.mp3", "002_P1_02_X.mp3", "001_P1_01_X.mp3", "009_P4_02_X.mp3")
        val sorted = TrackOrdering.sort(names.mapIndexed { i, n -> TrackCandidate("id$i", n, n) })
        assertThat(sorted.map { it.first.name }).containsExactly("001_P1_01_X.mp3", "002_P1_02_X.mp3", "009_P4_02_X.mp3", "010_P4_03_X.mp3").inOrder()
    }

    @Test
    fun `sans motif reconnu, ordre alphabétique naturel`() {
        val names = listOf("Piste b.mp3", "intro.mp3", "Piste a.mp3")
        val sorted = TrackOrdering.sort(names.mapIndexed { i, n -> TrackCandidate("id$i", n, n) })
        assertThat(sorted.map { it.first.name }).containsExactly("intro.mp3", "Piste a.mp3", "Piste b.mp3").inOrder()
    }

    @Test
    fun `ordre naturel des nombres sans zéros`() {
        val names = listOf("10.mp3", "2.mp3", "1.mp3")
        val sorted = TrackOrdering.sort(names.mapIndexed { i, n -> TrackCandidate("id$i", n, n) })
        assertThat(sorted.map { it.first.name }).containsExactly("1.mp3", "2.mp3", "10.mp3").inOrder()
    }
}
