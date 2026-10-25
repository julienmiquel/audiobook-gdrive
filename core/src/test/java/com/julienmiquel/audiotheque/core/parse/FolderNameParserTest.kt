package com.julienmiquel.audiotheque.core.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Noms observés dans /AudioBook (cf. spécification, « Anomalies que l'app doit tolérer »). */
class FolderNameParserTest {

    @Test
    fun `Titre - AUTEUR, la convention la plus fréquente`() {
        val p = FolderNameParser.parse("CIVILISATIONS - Laurent BINET")
        assertThat(p.title).isEqualTo("Civilisations")
        assertThat(p.author).isEqualTo("Laurent Binet")
    }

    @Test
    fun `Auteur - Titre est reconnu`() {
        val p = FolderNameParser.parse("Laurent Binet - PERSPECTIVE(S)")
        assertThat(p.author).isEqualTo("Laurent Binet")
        assertThat(p.title).isEqualTo("Perspective(s)")
    }

    @Test
    fun `MAJUSCULES_SOULIGNÉES deviennent une phrase avec élision`() {
        val p = FolderNameParser.parse("LE_CHARME_DISCRET_DE_L_INTESTIN")
        assertThat(p.title).isEqualTo("Le charme discret de l'intestin")
        assertThat(p.author).isNull()
    }

    @Test
    fun `titre seul, auteur seul et coquille restent tels quels`() {
        assertThat(FolderNameParser.parse("Ravage")).isEqualTo(ParsedName("Ravage", null, null))
        assertThat(FolderNameParser.parse("Boris Cyrulnik").title).isEqualTo("Boris Cyrulnik")
        assertThat(FolderNameParser.parse("Les pilliers de la terre").title).isEqualTo("Les pilliers de la terre")
    }

    @Test
    fun `doubles soulignés séparent les parties`() {
        val p = FolderNameParser.parse("DUNES__LES_HERETIQUES_DE_DUNE")
        assertThat(p.title).isEqualTo("Dunes - Les heretiques de dune")
        assertThat(p.author).isNull()
    }

    @Test
    fun `piste isolée hors dossier`() {
        val p = FolderNameParser.parse("George_Orwell_-_La_Ferme_des_Animaux_Chap01.mp3", isFile = true)
        assertThat(p.author).isEqualTo("George Orwell")
        assertThat(p.title).isEqualTo("La Ferme des Animaux")
    }

    @Test
    fun `zip sans auteur`() {
        val p = FolderNameParser.parse("La Guerre des mondes.zip", isFile = true)
        assertThat(p.title).isEqualTo("La Guerre des mondes")
    }

    @Test
    fun `voix lue entre parenthèses`() {
        val p = FolderNameParser.parse("Le mage du Kremlin - Giuliano da Empoli (lu par Thierry Blanc)")
        assertThat(p.title).isEqualTo("Le mage du Kremlin")
        assertThat(p.author).isEqualTo("Giuliano da Empoli")
        assertThat(p.narrator).isEqualTo("Thierry Blanc")
    }

    @Test
    fun `auteur en majuscules avec particule`() {
        val p = FolderNameParser.parse("Le Horla - Guy de MAUPASSANT")
        assertThat(p.title).isEqualTo("Le Horla")
        assertThat(p.author).isEqualTo("Guy de Maupassant")
    }
}
