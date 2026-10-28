package com.julienmiquel.audiotheque.library

import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import org.junit.Test

class BookRecommenderTest {

    @Test
    fun `privilégie le même auteur et la même voix, exclut les livres déjà lus`() {
        val doneBinet = item(
            id = "done-1",
            title = "Civilisations",
            author = "Laurent Binet",
            narrator = "Sébastien Desjours",
            status = BookStatus.DONE,
            finished = true,
            durationMs = 11L * 3_600_000L,
        )
        val sameAuthorNew = item(
            id = "new-binet",
            title = "Perspective(s)",
            author = "Laurent Binet",
            narrator = "Autre Voix",
            status = BookStatus.NEW,
            durationMs = 9L * 3_600_000L,
            addedAt = 100L,
        )
        val sameNarratorNew = item(
            id = "new-narrator",
            title = "L'Anomalie",
            author = "Hervé Le Tellier",
            narrator = "Sébastien Desjours",
            status = BookStatus.NEW,
            durationMs = 8L * 3_600_000L,
            addedAt = 200L,
        )
        val duplicateOfDone = item(
            id = "dup-done",
            title = "Civilisations",
            author = "Laurent Binet",
            narrator = null,
            status = BookStatus.NEW,
            addedAt = 999L,
        )
        val unrelatedNew = item(
            id = "new-other",
            title = "Ravage",
            author = "René Barjavel",
            narrator = null,
            status = BookStatus.NEW,
            durationMs = 10L * 3_600_000L,
            addedAt = 300L,
        )

        val recs = BookRecommender.recommend(
            items = listOf(doneBinet, sameAuthorNew, sameNarratorNew, duplicateOfDone, unrelatedNew),
            referenceBookId = "done-1",
            limit = 5,
        )

        assertThat(recs.map { it.item.id }).containsExactly("new-binet", "new-narrator", "new-other").inOrder()
        assertThat(recs[0].reason).contains("Laurent Binet")
        assertThat(recs[1].reason).contains("Sébastien Desjours")
    }

    @Test
    fun `valorise un livre mis de côté déjà entamé`() {
        val startedLater = item(
            id = "later-1",
            title = "Le Horla",
            author = "Guy de Maupassant",
            narrator = null,
            status = BookStatus.LATER,
            percent = 42f,
            addedAt = 10L,
        )
        val plainNew = item(
            id = "new-1",
            title = "Inconnu",
            author = null,
            narrator = null,
            status = BookStatus.NEW,
            addedAt = 20L,
        )

        val recs = BookRecommender.recommend(listOf(startedLater, plainNew), limit = 3)
        assertThat(recs.first().item.id).isEqualTo("later-1")
        assertThat(recs.first().reason).contains("42 %")
    }

    private fun item(
        id: String,
        title: String,
        author: String?,
        narrator: String?,
        status: BookStatus,
        finished: Boolean = false,
        percent: Float? = null,
        durationMs: Long? = null,
        addedAt: Long = 1_000L,
    ): LibraryItem {
        val entity = BookEntity(
            driveId = id,
            name = title,
            parentId = "parent-$status",
            status = status,
            statusChangedAt = addedAt,
            parsedTitle = title,
            parsedAuthor = author,
            parsedNarrator = narrator,
            normalizedKey = TextNormalizer.key(title),
            authorKey = TextNormalizer.keyOrNull(author),
            durationMs = durationMs,
            addedAt = addedAt,
        )
        return LibraryItem(
            book = entity,
            status = status,
            title = title,
            author = author,
            narrator = narrator,
            durationMs = durationMs,
            durationExact = durationMs != null,
            sizeBytes = 0L,
            trackCount = 5,
            presentTracks = 0,
            percent = percent,
            lastListenedAt = null,
            finished = finished,
            isDuplicate = false,
            pendingSync = false,
            cover = null,
        )
    }
}
