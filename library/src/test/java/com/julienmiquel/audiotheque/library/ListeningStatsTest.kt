package com.julienmiquel.audiotheque.library

import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.ListeningSessionEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.settings.LibrarySort
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test

class ListeningStatsTest {

    private val zone = ZoneOffset.UTC

    private fun epochAtNoon(date: LocalDate): Long =
        date.atTime(12, 0).toInstant(zone).toEpochMilli()

    private fun makeItem(
        id: String,
        title: String,
        author: String? = null,
        narrator: String? = null,
        status: BookStatus = BookStatus.IN_PROGRESS,
        durationMs: Long = 3_600_000L,
        percent: Float? = null,
        finished: Boolean = false,
        presentTracks: Int = 1,
    ): LibraryItem {
        val book = BookEntity(
            driveId = id,
            parentId = "p1",
            status = status,
            kind = BookKind.FOLDER,
            name = if (author != null) "$title - $author" else title,
            parsedTitle = title,
            parsedAuthor = author,
            parsedNarrator = narrator,
            normalizedKey = TextNormalizer.key(title),
            authorKey = TextNormalizer.keyOrNull(author),
            localDirName = id,
            addedAt = 1_000L,
            statusChangedAt = 1_000L,
            durationMs = durationMs,
        )
        return LibraryItem(
            book = book,
            status = status,
            title = title,
            author = author,
            narrator = narrator,
            durationMs = durationMs,
            durationExact = true,
            sizeBytes = 10_000L,
            trackCount = 4,
            presentTracks = presentTracks,
            percent = percent,
            lastListenedAt = 2_000L,
            finished = finished,
            isDuplicate = false,
            pendingSync = false,
            cover = null,
        )
    }

    @Test
    fun `calcule les séries quotidiennes, les barres 7 et 30 jours et les classements`() {
        val today = LocalDate.of(2026, 10, 28)
        val nowMs = epochAtNoon(today)

        val sessions = listOf(
            // Série ancienne de 4 jours (il y a 15..12 jours)
            ListeningSessionEntity(bookId = "b1", account = "local", startedAt = epochAtNoon(today.minusDays(15)), endedAt = epochAtNoon(today.minusDays(15)) + 600_000L, durationMs = 600_000L),
            ListeningSessionEntity(bookId = "b1", account = "local", startedAt = epochAtNoon(today.minusDays(14)), endedAt = epochAtNoon(today.minusDays(14)) + 600_000L, durationMs = 600_000L),
            ListeningSessionEntity(bookId = "b1", account = "local", startedAt = epochAtNoon(today.minusDays(13)), endedAt = epochAtNoon(today.minusDays(13)) + 600_000L, durationMs = 600_000L),
            ListeningSessionEntity(bookId = "b1", account = "local", startedAt = epochAtNoon(today.minusDays(12)), endedAt = epochAtNoon(today.minusDays(12)) + 600_000L, durationMs = 600_000L),
            // Série active de 3 jours (avant-hier, hier, aujourd'hui)
            ListeningSessionEntity(bookId = "b1", account = "local", startedAt = epochAtNoon(today.minusDays(2)), endedAt = epochAtNoon(today.minusDays(2)) + 1_200_000L, durationMs = 1_200_000L),
            ListeningSessionEntity(bookId = "b2", account = "local", startedAt = epochAtNoon(today.minusDays(1)), endedAt = epochAtNoon(today.minusDays(1)) + 1_800_000L, durationMs = 1_800_000L),
            ListeningSessionEntity(bookId = "b2", account = "local", startedAt = epochAtNoon(today), endedAt = epochAtNoon(today) + 900_000L, durationMs = 900_000L),
        )

        val items = listOf(
            makeItem("b1", "Civilisations", author = "Laurent Binet", narrator = "Denis Podalydès", status = BookStatus.DONE, durationMs = 10_000_000L, finished = true),
            makeItem("b2", "Perspective(s)", author = "Laurent Binet", narrator = "Thierry Blanc", status = BookStatus.IN_PROGRESS, durationMs = 8_000_000L, percent = 50f),
        )

        val stats = ListeningStatsCalculator.compute(sessions = sessions, items = items, nowMs = nowMs, zoneId = zone)

        assertThat(stats.todayMs).isEqualTo(900_000L)
        assertThat(stats.last7DaysMs).isEqualTo(3_900_000L)
        assertThat(stats.last30DaysMs).isEqualTo(6_300_000L)
        assertThat(stats.recordedListeningMs).isEqualTo(6_300_000L)
        assertThat(stats.currentStreakDays).isEqualTo(3)
        assertThat(stats.bestStreakDays).isEqualTo(4)
        assertThat(stats.dailyBars7).hasSize(7)
        assertThat(stats.dailyBars30).hasSize(30)
        assertThat(stats.finishedBooksCount).isEqualTo(1)
        assertThat(stats.inProgressBooksCount).isEqualTo(1)
        assertThat(stats.catalogListenedMs).isEqualTo(14_000_000L)

        assertThat(stats.topAuthors.first().name).isEqualTo("Laurent Binet")
        assertThat(stats.topAuthors.first().booksCount).isEqualTo(2)
        assertThat(stats.topBooks.map { it.title }).containsExactly("Civilisations", "Perspective(s)").inOrder()
        assertThat(stats.topNarrators).hasSize(2)
    }

    @Test
    fun `la série reste active le matin si hier a été écouté`() {
        val today = LocalDate.of(2026, 10, 28)
        val sessions = listOf(
            ListeningSessionEntity(bookId = "b1", account = "local", startedAt = epochAtNoon(today.minusDays(2)), endedAt = epochAtNoon(today.minusDays(2)) + 120_000L, durationMs = 120_000L),
            ListeningSessionEntity(bookId = "b1", account = "local", startedAt = epochAtNoon(today.minusDays(1)), endedAt = epochAtNoon(today.minusDays(1)) + 120_000L, durationMs = 120_000L),
        )
        val stats = ListeningStatsCalculator.compute(sessions, emptyList(), epochAtNoon(today), zone)
        assertThat(stats.currentStreakDays).isEqualTo(2)
    }

    @Test
    fun `filtre hors ligne et tri par tome de série dans LibraryRepository`() {
        val t3 = makeItem("d3", "Dune - T.3", author = "Frank Herbert", status = BookStatus.NEW, presentTracks = 0)
        val t1 = makeItem("d1", "Dune - T.1", author = "Frank Herbert", status = BookStatus.NEW, presentTracks = 2)
        val t2 = makeItem("d2", "Dune - T.2", author = "Frank Herbert", status = BookStatus.NEW, presentTracks = 4)

        val sortedByTitle = LibraryRepository.filter(
            items = listOf(t3, t1, t2),
            status = BookStatus.NEW,
            sort = LibrarySort.TITLE_ASC,
        )
        assertThat(sortedByTitle.map { it.id }).containsExactly("d1", "d2", "d3").inOrder()

        val offlineOnly = LibraryRepository.filter(
            items = listOf(t3, t1, t2),
            status = BookStatus.NEW,
            offlineOnly = true,
            sort = LibrarySort.TITLE_ASC,
        )
        assertThat(offlineOnly.map { it.id }).containsExactly("d1", "d2").inOrder()
    }
}
