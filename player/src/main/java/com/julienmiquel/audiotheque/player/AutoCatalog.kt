package com.julienmiquel.audiotheque.player

import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.util.Formats

/**
 * Règles du catalogue proposé aux navigateurs média (Android Auto, Assistant, vignette de
 * reprise du système) : onglets, avancement affiché, cycle de vitesse en voiture, recherche
 * vocale. Sans dépendance Android : testable à sec.
 */
object AutoCatalog {

    /** Onglets de la racine, dans l'ordre ; seuls les livres ayant des pistes sur le téléphone y figurent. */
    val TAB_STATUSES: List<BookStatus> = listOf(BookStatus.IN_PROGRESS, BookStatus.LATER)

    /** Valeurs de `MediaConstants.EXTRAS_KEY_COMPLETION_STATUS` (identiques aux constantes Media3). */
    const val NOT_PLAYED = 0
    const val PARTIALLY_PLAYED = 1
    const val FULLY_PLAYED = 2

    /** Avancement d'un élément : statut Android Auto et fraction (0..1) pour la barre. */
    data class Completion(val status: Int, val fraction: Double)

    /** Avancement d'un livre d'après sa progression enregistrée. */
    fun bookCompletion(percent: Float?, finished: Boolean): Completion = when {
        finished -> Completion(FULLY_PLAYED, 1.0)
        percent == null || percent <= 0f -> Completion(NOT_PLAYED, 0.0)
        else -> Completion(PARTIALLY_PLAYED, (percent / 100.0).coerceIn(0.0, 1.0))
    }

    /**
     * Avancement d'un chapitre : lus avant la position courante, en cours à la position courante
     * (fraction de sa durée si elle est connue), pas encore lus après.
     */
    fun chapterCompletion(trackIndex: Int, currentIndex: Int?, positionMs: Long, durationMs: Long?, bookFinished: Boolean): Completion = when {
        bookFinished || (currentIndex != null && trackIndex < currentIndex) -> Completion(FULLY_PLAYED, 1.0)
        currentIndex == null || trackIndex > currentIndex -> Completion(NOT_PLAYED, 0.0)
        durationMs == null || durationMs <= 0 -> Completion(PARTIALLY_PLAYED, 0.0)
        else -> Completion(PARTIALLY_PLAYED, (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0))
    }

    /** Vitesses proposées en voiture par le bouton « Vitesse » (un appui = la suivante). */
    val SPEED_CYCLE: List<Float> = listOf(1.0f, 1.2f, 1.5f, 2.0f, 0.8f)

    /** Vitesse suivante du cycle ; une vitesse hors cycle (1,3×) repart à 1,0×. */
    fun nextSpeed(current: Float): Float {
        val index = SPEED_CYCLE.indexOfFirst { kotlin.math.abs(it - current) < 0.05f }
        return if (index < 0) SPEED_CYCLE.first() else SPEED_CYCLE[(index + 1) % SPEED_CYCLE.size]
    }

    /** Deuxième ligne de la vignette de reprise : « Piste 4 / 51 · 42 % », « 42 % » ou « Reprendre ». */
    fun resumeSubtitle(trackLabel: String?, percent: Float?, finished: Boolean): String {
        val progress = when {
            finished -> "terminé"
            percent != null && percent >= 1f -> Formats.percent(percent)
            else -> null
        }
        return listOfNotNull(trackLabel?.takeIf { it.isNotBlank() }, progress).joinToString(" · ").ifEmpty { "Reprendre" }
    }

    /** Mots de remplissage des requêtes vocales (« écoute le livre … sur Audiothèque »), ignorés s'il reste autre chose. */
    private val STOPWORDS = setOf(
        "le", "la", "les", "un", "une", "des", "du", "de", "d", "l", "et", "en", "sur", "dans", "a", "au", "aux",
        "livre", "audio", "audiobook", "audiotheque", "ecoute", "ecouter", "lis", "lire", "lecture", "lecteur", "joue", "jouer",
        "lance", "lancer", "mets", "met", "mettre", "reprends", "reprendre", "continue", "continuer", "moi", "mon", "ma", "mes",
        "par", "lu", "the", "play", "book", "listen", "to", "on",
    )

    /**
     * Requête vocale sans autre contenu que des mots de remplissage (« reprends mon livre »,
     * « lance la lecture ») : c'est une demande de reprise, pas une recherche.
     */
    fun isGenericRequest(query: String): Boolean =
        TextNormalizer.key(query).split(' ').filter { it.isNotEmpty() }.all { it in STOPWORDS }

    /**
     * Pertinence d'un livre pour une requête (recherche Android Auto, « Écoute Civilisations »).
     * 0 si rien ne correspond. Les mots de la requête sont cherchés dans le titre, l'auteur et la
     * voix (sans accents ni ponctuation) ; au moins la moitié doivent s'y trouver, les mots du
     * titre comptent davantage, un titre exact l'emporte.
     */
    fun searchScore(query: String, title: String, author: String?, narrator: String?): Int {
        val all = TextNormalizer.key(query).split(' ').filter { it.isNotEmpty() }
        val words = all.filter { it !in STOPWORDS }.ifEmpty { all }
        if (words.isEmpty()) return 0
        val titleKey = TextNormalizer.key(title)
        val haystack = TextNormalizer.key(listOfNotNull(title, author, narrator).joinToString(" "))
        val inTitle = words.count { titleKey.contains(it) }
        val inAll = words.count { haystack.contains(it) }
        if (inAll == 0 || inAll * 2 < words.size) return 0
        val exact = titleKey == words.joinToString(" ")
        return inAll * 10 + inTitle * 5 + (if (exact) 50 else 0)
    }
}
