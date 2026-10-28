package com.julienmiquel.audiotheque.core.parse

import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.model.BookKind
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

    @Test
    fun `nom normalisé Titre - AUTEUR pour dossier et fichier`() {
        assertThat(
            FolderNameParser.normalizedDriveName(
                title = "Perspective(s)",
                author = "Laurent Binet",
                originalName = "Laurent Binet - PERSPECTIVE(S)",
                kind = BookKind.FOLDER,
            ),
        ).isEqualTo("Perspective(s) - Laurent BINET")

        assertThat(
            FolderNameParser.normalizedDriveName(
                title = "Le Horla",
                author = "Guy de Maupassant",
                originalName = "Le Horla",
                kind = BookKind.FOLDER,
            ),
        ).isEqualTo("Le Horla - Guy de MAUPASSANT")

        val zipName = FolderNameParser.normalizedDriveName(
            title = "La Guerre des mondes",
            author = "H. G. Wells",
            originalName = "La Guerre des mondes.zip",
            kind = BookKind.ZIP,
        )
        assertThat(zipName).isEqualTo("La Guerre des mondes - H. G. WELLS.zip")
        val reparsed = FolderNameParser.parse(zipName, isFile = true)
        assertThat(reparsed.title).isEqualTo("La Guerre des mondes")
        assertThat(reparsed.author).isEqualTo("H. G. Wells")
    }

    @Test
    fun `détection automatique des séries et numéros de tome`() {
        assertThat(FolderNameParser.extractSeries("[Les Rois maudits 2] La Reine étranglée"))
            .isEqualTo(SeriesInfo("Les Rois maudits", 2))
        assertThat(FolderNameParser.extractSeries("Fondation (Le Cycle de Fondation, Tome 1)"))
            .isEqualTo(SeriesInfo("Le Cycle de Fondation", 1))
        assertThat(FolderNameParser.extractSeries("Harry Potter (Tome 4)"))
            .isEqualTo(SeriesInfo("Harry Potter", 4))
        assertThat(FolderNameParser.extractSeries("Les Rois maudits - Tome 2 : La Reine étranglée"))
            .isEqualTo(SeriesInfo("Les Rois maudits", 2))
        assertThat(FolderNameParser.extractSeries("Dune T01"))
            .isEqualTo(SeriesInfo("Dune", 1))
        assertThat(FolderNameParser.extractSeries("Dune - T.3"))
            .isEqualTo(SeriesInfo("Dune", 3))
        assertThat(FolderNameParser.extractSeries("Millénium, Vol. 3 - La Reine dans le palais"))
            .isEqualTo(SeriesInfo("Millénium", 3))
        assertThat(FolderNameParser.extractSeries("Le Seigneur des Anneaux - Livre 2 : Les Deux Tours"))
            .isEqualTo(SeriesInfo("Le Seigneur des Anneaux", 2))
        assertThat(FolderNameParser.extractSeries("Millénium 1 - Les Hommes qui n'aimaient pas les femmes"))
            .isEqualTo(SeriesInfo("Millénium", 1))

        assertThat(FolderNameParser.extractSeries("Civilisations")).isNull()
        assertThat(FolderNameParser.extractSeries("1984")).isNull()
        assertThat(FolderNameParser.extractSeries("Fahrenheit 451")).isNull()
        assertThat(FolderNameParser.extractSeries("2001 - L'Odyssée de l'espace")).isNull()
        assertThat(FolderNameParser.extractSeries("Partie 1 - Le départ")).isNull()
    }
}

