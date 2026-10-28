package com.julienmiquel.audiotheque.player

import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.model.BookStatus
import org.junit.Test

/** Catalogue Android Auto et vignette de reprise : règles sans Android. */
class AutoCatalogTest {

    @Test
    fun `identifiants des onglets - un par statut proposé`() {
        assertThat(MediaIds.parseTab(MediaIds.tab(BookStatus.IN_PROGRESS))).isEqualTo(BookStatus.IN_PROGRESS)
        assertThat(MediaIds.parseTab(MediaIds.tab(BookStatus.LATER))).isEqualTo(BookStatus.LATER)
        assertThat(MediaIds.parseTab("tab:INCONNU")).isNull()
        assertThat(MediaIds.parseTab(MediaIds.bookRequest("civ"))).isNull()
        assertThat(MediaIds.parseBookRequest(MediaIds.tab(BookStatus.LATER))).isNull()
        assertThat(AutoCatalog.TAB_STATUSES).containsExactly(BookStatus.IN_PROGRESS, BookStatus.LATER).inOrder()
    }

    @Test
    fun `avancement d'un livre - pas commencé, en cours, terminé`() {
        assertThat(AutoCatalog.bookCompletion(null, finished = false)).isEqualTo(AutoCatalog.Completion(AutoCatalog.NOT_PLAYED, 0.0))
        assertThat(AutoCatalog.bookCompletion(0f, finished = false)).isEqualTo(AutoCatalog.Completion(AutoCatalog.NOT_PLAYED, 0.0))
        assertThat(AutoCatalog.bookCompletion(42f, finished = false)).isEqualTo(AutoCatalog.Completion(AutoCatalog.PARTIALLY_PLAYED, 0.42))
        assertThat(AutoCatalog.bookCompletion(42f, finished = true)).isEqualTo(AutoCatalog.Completion(AutoCatalog.FULLY_PLAYED, 1.0))
        assertThat(AutoCatalog.bookCompletion(250f, finished = false).fraction).isEqualTo(1.0)
    }

    @Test
    fun `avancement des chapitres - lus avant la position, en cours, à venir`() {
        assertThat(AutoCatalog.chapterCompletion(0, currentIndex = 2, positionMs = 0, durationMs = 60_000, bookFinished = false).status).isEqualTo(AutoCatalog.FULLY_PLAYED)
        val current = AutoCatalog.chapterCompletion(2, currentIndex = 2, positionMs = 30_000, durationMs = 60_000, bookFinished = false)
        assertThat(current).isEqualTo(AutoCatalog.Completion(AutoCatalog.PARTIALLY_PLAYED, 0.5))
        assertThat(AutoCatalog.chapterCompletion(2, currentIndex = 2, positionMs = 30_000, durationMs = null, bookFinished = false)).isEqualTo(AutoCatalog.Completion(AutoCatalog.PARTIALLY_PLAYED, 0.0))
        assertThat(AutoCatalog.chapterCompletion(3, currentIndex = 2, positionMs = 0, durationMs = 60_000, bookFinished = false).status).isEqualTo(AutoCatalog.NOT_PLAYED)
        assertThat(AutoCatalog.chapterCompletion(3, currentIndex = null, positionMs = 0, durationMs = 60_000, bookFinished = false).status).isEqualTo(AutoCatalog.NOT_PLAYED)
        assertThat(AutoCatalog.chapterCompletion(3, currentIndex = 0, positionMs = 0, durationMs = 60_000, bookFinished = true).status).isEqualTo(AutoCatalog.FULLY_PLAYED)
    }

    @Test
    fun `bouton Vitesse - cycle 1,0 → 1,2 → 1,5 → 2,0 → 0,8, hors cycle repart à 1,0`() {
        assertThat(AutoCatalog.nextSpeed(1.0f)).isEqualTo(1.2f)
        assertThat(AutoCatalog.nextSpeed(1.2f)).isEqualTo(1.5f)
        assertThat(AutoCatalog.nextSpeed(1.5f)).isEqualTo(2.0f)
        assertThat(AutoCatalog.nextSpeed(2.0f)).isEqualTo(0.8f)
        assertThat(AutoCatalog.nextSpeed(0.8f)).isEqualTo(1.0f)
        assertThat(AutoCatalog.nextSpeed(1.3f)).isEqualTo(1.0f)
        AutoCatalog.SPEED_CYCLE.forEach {
            assertThat(it).isAtLeast(PlaybackService.MIN_SPEED)
            assertThat(it).isAtMost(PlaybackService.MAX_SPEED)
        }
    }

    @Test
    fun `vignette de reprise - piste de reprise et avancement, jamais l'auteur`() {
        assertThat(AutoCatalog.resumeSubtitle("Piste 4 / 51", 42.4f, finished = false)).isEqualTo("Piste 4 / 51 · 42\u00A0%")
        assertThat(AutoCatalog.resumeSubtitle(null, 42.4f, finished = false)).isEqualTo("42\u00A0%")
        assertThat(AutoCatalog.resumeSubtitle("Chapitre 4", 0f, finished = false)).isEqualTo("Chapitre 4")
        assertThat(AutoCatalog.resumeSubtitle("Piste 1 / 51", 100f, finished = true)).isEqualTo("Piste 1 / 51 · terminé")
        assertThat(AutoCatalog.resumeSubtitle(null, null, finished = false)).isEqualTo("Reprendre")
    }

    @Test
    fun `recherche vocale - titre, auteur, voix, mots de remplissage ignorés`() {
        fun score(query: String) = AutoCatalog.searchScore(query, "Civilisations", "Laurent Binet", "Thierry Blanc")
        assertThat(score("Civilisations")).isGreaterThan(0)
        assertThat(score("civilisation")).isGreaterThan(0) // sans le s : contenu dans le titre
        assertThat(score("écoute le livre Civilisations sur Audiothèque")).isGreaterThan(0)
        assertThat(score("Laurent Binet")).isGreaterThan(0)
        assertThat(score("lu par Thierry Blanc")).isGreaterThan(0)
        assertThat(score("La guerre des mondes")).isEqualTo(0)
        assertThat(score("")).isEqualTo(0)
        // Un titre exact l'emporte sur une correspondance partielle ; le titre compte plus que l'auteur.
        assertThat(score("Civilisations")).isGreaterThan(score("Civilisations de Laurent"))
        assertThat(AutoCatalog.searchScore("Binet", "Civilisations", "Laurent Binet", null))
            .isLessThan(AutoCatalog.searchScore("Binet", "Binet", "Inconnu", null))
    }

    @Test
    fun `recherche vocale - au moins la moitié des mots doivent correspondre`() {
        assertThat(AutoCatalog.searchScore("Civilisations tome deux", "Civilisations", "Laurent Binet", null)).isEqualTo(0)
        assertThat(AutoCatalog.searchScore("Civilisations Binet", "Civilisations", "Laurent Binet", null)).isGreaterThan(0)
        // Seulement des mots de remplissage : ils servent alors de requête.
        assertThat(AutoCatalog.searchScore("le livre", "Le livre des Baltimore", "Joël Dicker", null)).isGreaterThan(0)
    }

    @Test
    fun `requête vocale générique - « reprends mon livre » relance le dernier livre`() {
        assertThat(AutoCatalog.isGenericRequest("")).isTrue()
        assertThat(AutoCatalog.isGenericRequest("Reprends mon livre")).isTrue()
        assertThat(AutoCatalog.isGenericRequest("lance la lecture")).isTrue()
        assertThat(AutoCatalog.isGenericRequest("écoute un livre audio")).isTrue()
        assertThat(AutoCatalog.isGenericRequest("écoute Civilisations")).isFalse()
        assertThat(AutoCatalog.isGenericRequest("Laurent Binet")).isFalse()
    }
}
