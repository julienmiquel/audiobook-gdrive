package com.julienmiquel.audiotheque.library

import com.julienmiquel.audiotheque.core.db.ListeningSessionEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DayListening(
    val date: LocalDate,
    val shortLabel: String,
    val fullLabel: String,
    val durationMs: Long,
)

data class BookStatEntry(
    val bookId: String,
    val title: String,
    val author: String?,
    val cover: String?,
    val listenedMs: Long,
    val finished: Boolean,
)

data class CreatorStatEntry(
    val name: String,
    val booksCount: Int,
    val finishedCount: Int,
    val listenedMs: Long,
)

data class ListeningStatsSummary(
    val recordedListeningMs: Long,
    val todayMs: Long,
    val last7DaysMs: Long,
    val last30DaysMs: Long,
    val currentStreakDays: Int,
    val bestStreakDays: Int,
    val dailyBars7: List<DayListening>,
    val dailyBars30: List<DayListening>,
    val finishedBooksCount: Int,
    val inProgressBooksCount: Int,
    val catalogListenedMs: Long,
    val topBooks: List<BookStatEntry>,
    val topAuthors: List<CreatorStatEntry>,
    val topNarrators: List<CreatorStatEntry>,
)

/** Calcul pur des statistiques d'écoute à partir des sessions enregistrées et du catalogue. */
object ListeningStatsCalculator {
    /** Seuil minimal (1 min) pour qu'une journée compte dans la série quotidienne. */
    const val MIN_STREAK_DAY_MS = 60_000L

    private val shortDayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.FRENCH)
    private val shortDateFormatter = DateTimeFormatter.ofPattern("dd/MM", Locale.FRENCH)
    private val fullDateFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)

    fun compute(
        sessions: List<ListeningSessionEntity>,
        items: List<LibraryItem>,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ListeningStatsSummary {
        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val byDate = HashMap<LocalDate, Long>()
        val byBookSessions = HashMap<String, Long>()
        var totalRecordedMs = 0L

        for (session in sessions) {
            val dur = session.durationMs.coerceAtLeast(0L)
            if (dur <= 0L) continue
            totalRecordedMs += dur
            val date = Instant.ofEpochMilli(session.startedAt).atZone(zoneId).toLocalDate()
            byDate[date] = (byDate[date] ?: 0L) + dur
            byBookSessions[session.bookId] = (byBookSessions[session.bookId] ?: 0L) + dur
        }

        val dailyBars7 = (6 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong())
            val rawShort = d.format(shortDayFormatter).trimEnd('.').replaceFirstChar { it.titlecase(Locale.FRENCH) }
            DayListening(
                date = d,
                shortLabel = rawShort.take(3),
                fullLabel = d.format(fullDateFormatter).replaceFirstChar { it.titlecase(Locale.FRENCH) },
                durationMs = byDate[d] ?: 0L,
            )
        }

        val dailyBars30 = (29 downTo 0).map { offset ->
            val d = today.minusDays(offset.toLong())
            DayListening(
                date = d,
                shortLabel = d.format(shortDateFormatter),
                fullLabel = d.format(fullDateFormatter).replaceFirstChar { it.titlecase(Locale.FRENCH) },
                durationMs = byDate[d] ?: 0L,
            )
        }

        val todayMs = byDate[today] ?: 0L
        val last7DaysMs = dailyBars7.sumOf { it.durationMs }
        val last30DaysMs = dailyBars30.sumOf { it.durationMs }

        val activeDates = byDate.entries
            .filter { it.value >= MIN_STREAK_DAY_MS }
            .map { it.key }
            .sorted()

        val bestStreakDays = computeBestStreak(activeDates)
        val currentStreakDays = computeCurrentStreak(activeDates.toSet(), today)

        // Déduplication des exemplaires multiples d'un même livre pour les totaux du catalogue.
        val visibleItems = items.filter { !it.book.hidden }
        val deduplicatedBooks = visibleItems
            .groupBy { "${it.book.normalizedKey}|${it.book.authorKey.orEmpty()}" }
            .values
            .map { copies ->
                copies.maxWith(
                    compareBy<LibraryItem> { it.finished || it.status == BookStatus.DONE }
                        .thenBy { byBookSessions[it.id] ?: 0L }
                        .thenBy { it.percent ?: 0f }
                        .thenBy { it.durationMs ?: 0L },
                )
            }

        var finishedCount = 0
        var inProgressCount = 0
        var catalogListenedMs = 0L

        val scoredBooks = deduplicatedBooks.map { item ->
            val isFinished = item.finished || item.status == BookStatus.DONE
            if (isFinished) finishedCount++
            if (item.status == BookStatus.IN_PROGRESS) inProgressCount++

            val estimatedMs = estimateBookListenedMs(item, isFinished)
            catalogListenedMs += estimatedMs

            val recordedForCopies = visibleItems
                .filter {
                    it.book.normalizedKey == item.book.normalizedKey &&
                        it.book.authorKey.orEmpty() == item.book.authorKey.orEmpty()
                }
                .sumOf { byBookSessions[it.id] ?: 0L }

            val effectiveMs = maxOf(recordedForCopies, estimatedMs)
            ScoredBook(item = item, effectiveMs = effectiveMs, isFinished = isFinished)
        }

        val topBooks = scoredBooks
            .filter { it.effectiveMs >= MIN_STREAK_DAY_MS }
            .sortedWith(
                compareByDescending<ScoredBook> { it.effectiveMs }
                    .thenByDescending { it.isFinished }
                    .thenBy { it.item.sortKey },
            )
            .take(8)
            .map { sb ->
                BookStatEntry(
                    bookId = sb.item.id,
                    title = sb.item.title,
                    author = sb.item.author,
                    cover = sb.item.cover,
                    listenedMs = sb.effectiveMs,
                    finished = sb.isFinished,
                )
            }

        val topAuthors = aggregateCreators(scoredBooks) { it.item.author }
        val topNarrators = aggregateCreators(scoredBooks) { it.item.narrator }

        return ListeningStatsSummary(
            recordedListeningMs = totalRecordedMs,
            todayMs = todayMs,
            last7DaysMs = last7DaysMs,
            last30DaysMs = last30DaysMs,
            currentStreakDays = currentStreakDays,
            bestStreakDays = bestStreakDays,
            dailyBars7 = dailyBars7,
            dailyBars30 = dailyBars30,
            finishedBooksCount = finishedCount,
            inProgressBooksCount = inProgressCount,
            catalogListenedMs = catalogListenedMs,
            topBooks = topBooks,
            topAuthors = topAuthors,
            topNarrators = topNarrators,
        )
    }

    private data class ScoredBook(
        val item: LibraryItem,
        val effectiveMs: Long,
        val isFinished: Boolean,
    )

    private fun estimateBookListenedMs(item: LibraryItem, isFinished: Boolean): Long {
        val dur = item.durationMs ?: return 0L
        if (dur <= 0L) return 0L
        return when {
            isFinished -> dur
            (item.percent ?: 0f) > 0f -> (dur * ((item.percent ?: 0f).coerceIn(0f, 100f) / 100.0)).toLong()
            else -> 0L
        }
    }

    private fun computeBestStreak(sortedDates: List<LocalDate>): Int {
        if (sortedDates.isEmpty()) return 0
        var best = 1
        var current = 1
        for (i in 1 until sortedDates.size) {
            if (sortedDates[i - 1].plusDays(1) == sortedDates[i]) {
                current++
                if (current > best) best = current
            } else if (sortedDates[i - 1] != sortedDates[i]) {
                current = 1
            }
        }
        return best
    }

    private fun computeCurrentStreak(activeSet: Set<LocalDate>, today: LocalDate): Int {
        val anchor = when {
            today in activeSet -> today
            today.minusDays(1) in activeSet -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        var cursor = anchor
        while (cursor in activeSet) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    private fun aggregateCreators(
        scoredBooks: List<ScoredBook>,
        extractor: (ScoredBook) -> String?,
    ): List<CreatorStatEntry> {
        val grouped = LinkedHashMap<String, MutableList<Pair<String, ScoredBook>>>()
        for (sb in scoredBooks) {
            val rawName = extractor(sb)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val key = TextNormalizer.key(rawName)
            if (key.isBlank()) continue
            grouped.getOrPut(key) { ArrayList() }.add(rawName to sb)
        }
        return grouped.values
            .map { entries ->
                val displayName = entries.first().first
                val listenedMs = entries.sumOf { it.second.effectiveMs }
                val finishedCount = entries.count { it.second.isFinished }
                CreatorStatEntry(
                    name = displayName,
                    booksCount = entries.size,
                    finishedCount = finishedCount,
                    listenedMs = listenedMs,
                )
            }
            .filter { it.listenedMs >= MIN_STREAK_DAY_MS || it.finishedCount > 0 }
            .sortedWith(
                compareByDescending<CreatorStatEntry> { it.listenedMs }
                    .thenByDescending { it.finishedCount }
                    .thenBy { TextNormalizer.key(it.name) },
            )
            .take(6)
    }
}
