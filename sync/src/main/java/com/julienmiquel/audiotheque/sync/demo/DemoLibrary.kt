package com.julienmiquel.audiotheque.sync.demo

import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults as D

/**
 * Bibliothèque de démonstration calquée sur l'inventaire du 25/10/2026 (cf. spécification) :
 * dossiers de statut, noms hétérogènes, doublons, zip, piste isolée, dossiers qui ne sont pas
 * des livres et statut faux (« Les guerriers de l'hiver » dans _Ecouté alors qu'il n'est pas fini).
 *
 * Les pistes pointent vers de courts mp3 de démonstration (voix de synthèse) livrés dans les
 * assets ; elles sont servies par [FakeDriveService] comme le ferait Drive.
 */
object DemoLibrary {

    sealed interface Entry {
        val name: String
    }

    /** Un livre = un dossier de pistes. */
    data class Folder(override val name: String, val tracks: List<Track>, val cover: String? = null) : Entry

    /** Une piste isolée ou un zip directement dans un dossier de statut. */
    data class File(override val name: String, val asset: String, val mimeType: String) : Entry

    data class Track(val fileName: String, val asset: String)

    private fun generic(prefix: (Int) -> String, count: Int = 3): List<Track> =
        (1..count).map { Track("${prefix(it)}.mp3", "chapitre_${(it - 1) % 3 + 1}.mp3") }

    private val numbered = generic({ "%02d - Chapitre %d".format(it, it) })

    val civilisations = Folder(
        name = "CIVILISATIONS - Laurent BINET",
        tracks = listOf(
            "001_P1_01_SAGA_DE_FREYDIS",
            "002_P1_02_SAGA_DE_FREYDIS",
            "003_P2_01_JOURNAL_DE_CHRISTOPHE_COLOMB",
            "004_P2_02_JOURNAL_DE_CHRISTOPHE_COLOMB",
            "005_P3_01_CHRONIQUES_D_ATAHUALPA",
            "006_P3_02_CHRONIQUES_D_ATAHUALPA",
            "007_P3_03_CHRONIQUES_D_ATAHUALPA",
            "008_P4_01_LES_AVENTURES_DE_CERVANTES",
            "009_P4_02_LES_AVENTURES_DE_CERVANTES",
            "010_P4_03_LES_AVENTURES_DE_CERVANTES",
        ).mapIndexed { i, n -> Track("$n.mp3", "civilisations_%02d.mp3".format(i + 1)) },
    )

    /** Contenu de chaque dossier de statut, indexé par son ID Drive. */
    val tree: Map<String, List<Entry>> = mapOf(
        D.IN_PROGRESS_ID to listOf(civilisations),
        D.NEW_ID to listOf(
            Folder("Police - Hugo Boris", generic({ "Police_%02d".format(it) }), cover = "cover_police.jpg"),
            Folder("Avec les fées - Sylvain Tesson", numbered, cover = "cover_fees.jpg"),
            Folder("L'heure bleue - Paula Hawkins", numbered, cover = "cover_heure_bleue.jpg"),
            Folder("Solo - William Boyd", numbered),
            Folder("Le jeu de l'ange - Carlos Ruiz Zafón", generic({ "CD1_Piste%02d".format(it) })),
            Folder("Veiller sur elle - Jean-Baptiste ANDREA", numbered),
            Folder("Le mage du Kremlin - Giuliano da Empoli (lu par Thierry Blanc)", numbered),
            Folder("Yoga - Emmanuel Carrère", numbered),
            Folder("Les choses humaines - Karine Tuil", numbered),
            Folder("La vie devant soi - Romain Gary (lu par Thierry Blanc)", numbered),
            Folder("Sapiens - Yuval Noah Harari", generic({ "Partie 1 - Chapitre %d".format(it) })),
            Folder("L'anomalie - Hervé LE TELLIER", numbered),
            Folder("Chanson douce - Leïla Slimani", numbered),
            Folder("Petit pays - Gaël Faye", numbered),
            Folder("Les furtifs - Alain Damasio", numbered),
            Folder("Le grand Meaulnes - Alain-Fournier", numbered),
        ),
        D.LATER_ID to listOf(
            Folder("Abri de fortune", numbered),
            Folder("Abri de fortune", numbered),
            Folder("Le Comte de Monte-Cristo - Alexandre DUMAS", generic({ "Chapitre %d".format(it) })),
            Folder("Vingt mille lieues sous les mers - Jules Verne", numbered),
            Folder("Le Petit Prince - Antoine de Saint-Exupéry", numbered),
            Folder("Les misérables - Victor HUGO", numbered),
            File("George_Orwell_-_La_Ferme_des_Animaux_Chap01.mp3", "chapitre_1.mp3", "audio/mpeg"),
            File("La Guerre des mondes.zip", "chapitre_2.mp3", "application/zip"),
            File("La Machine à explorer le temps.zip", "chapitre_3.mp3", "application/zip"),
        ),
        D.DONE_ID to listOf(
            Folder("DUNES__LES_HERETIQUES_DE_DUNE", numbered),
            Folder("DUNES__LES_HERETIQUES_DE_DUNE", numbered),
            Folder("Les guerriers de l'hiver - Olivier Norek", numbered),
            Folder("L'étranger - Albert Camus", numbered),
            Folder("Laurent Binet - HHhH", numbered),
            Folder("Les pilliers de la terre", numbered),
            Folder("Kiffe kiffe demain - Faïza Guène", numbered),
            Folder("Le Horla - Guy de MAUPASSANT", numbered),
        ),
        D.ARCHIVES_ID to listOf(
            Folder("Madame Bovary - Gustave Flaubert", numbered),
            Folder("Germinal - Émile Zola", numbered),
            Folder("Candide - Voltaire", numbered),
            Folder("Bel-Ami - Guy de Maupassant", numbered),
            Folder("Le Rouge et le Noir - Stendhal", numbered),
            File("Bien dormir, ça s'apprend !.zip", "chapitre_1.mp3", "application/zip"),
        ),
        D.MEDITATIONS_ID to listOf(
            Folder("Respiration du matin", listOf(Track("meditation_01.mp3", "meditation.mp3"))),
            Folder("Scan corporel", listOf(Track("scan_corporel.mp3", "meditation.mp3"))),
            Folder("Pleine conscience - 10 minutes", listOf(Track("pleine_conscience.mp3", "meditation.mp3"))),
        ),
        D.ROOT_ID to listOf(
            Folder("Le charme discret de l'intestin", numbered),
            Folder("Le charme discret de l'intestin", numbered),
            Folder("LE_CHARME_DISCRET_DE_L_INTESTIN", numbered),
            Folder("Laurent Binet - PERSPECTIVE(S)", numbered),
            Folder("Ravage", numbered),
            Folder("Boris Cyrulnik", numbered),
            Folder("julien", numbered),
            Folder("TCC", numbered),
            Folder("21", numbered),
            Folder("Various Artists", numbered),
            Folder("AudioLib", numbered),
        ),
    )

    /** Noms des dossiers de statut, enfants de la racine /AudioBook. */
    val statusFolders: Map<String, String> = mapOf(
        D.NEW_ID to "_Nouveaux",
        D.IN_PROGRESS_ID to "_En écoute",
        D.LATER_ID to "_A écouter plus tard",
        D.DONE_ID to "_Ecouté",
        D.ARCHIVES_ID to "_archives",
        D.MEDITATIONS_ID to "_meditations",
    )

    /** Progression de démonstration : le livre classé à tort dans _Ecouté n'est pas fini. */
    const val UNFINISHED_BOOK = "Les guerriers de l'hiver - Olivier Norek"
    const val UNFINISHED_TRACK_INDEX = 1
    const val UNFINISHED_POSITION_MS = 9_000L
}
