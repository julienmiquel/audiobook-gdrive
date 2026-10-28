package com.julienmiquel.audiotheque.library

import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.util.Formats
import kotlin.math.abs
import kotlin.math.roundToInt

/** Suggestion « À écouter ensuite » accompagnée d'un motif lisible en français. */
data class BookRecommendation(
    val item: LibraryItem,
    val reason: String,
    val score: Int,
)

/**
 * Moteur local de recommandations (« À écouter ensuite »), sans appel réseau :
 * croise les auteurs et narrateurs déjà appréciés (ou ceux du livre qui vient de se terminer),
 * les livres mis de côté déjà entamés, la disponibilité hors ligne, la durée habituelle d'écoute
 * et la fraîcheur d'ajout.
 */
object BookRecommender {

    fun recommend(
        items: List<LibraryItem>,
        referenceBookId: String? = null,
        limit: Int = 5,
    ): List<BookRecommendation> {
        if (limit <= 0 || items.isEmpty()) return emptyList()

        val reference = referenceBookId?.let { refId -> items.firstOrNull { it.id == refId } }
        val listened = items.filter {
            !it.book.hidden && !it.book.trashPending && (
                it.status == BookStatus.DONE ||
                    it.finished ||
                    it.status == BookStatus.IN_PROGRESS ||
                    ((it.percent ?: 0f) >= 15f && it.lastListenedAt != null)
                )
        }

        val refAuthorKey = reference?.author?.let(TextNormalizer::keyOrNull)
        val refNarratorKey = reference?.narrator?.let(TextNormalizer::keyOrNull)

        val authorCounts = HashMap<String, Int>()
        val narratorCounts = HashMap<String, Int>()
        for (b in listened) {
            TextNormalizer.keyOrNull(b.author)?.let { k -> authorCounts[k] = (authorCounts[k] ?: 0) + 1 }
            TextNormalizer.keyOrNull(b.narrator)?.let { k -> narratorCounts[k] = (narratorCounts[k] ?: 0) + 1 }
        }

        val knownDurations = listened.mapNotNull { it.durationMs?.takeIf { d -> d > 0L } }.sorted()
        val medianDurationMs: Long? = if (knownDurations.isNotEmpty()) {
            knownDurations[knownDurations.size / 2]
        } else {
            reference?.durationMs?.takeIf { it > 0L }
        }

        // Clés de titres déjà lus ou en cours d'écoute : évite de proposer un doublon déjà écouté.
        val activeOrDone = items.filter {
            !it.book.trashPending && (it.status == BookStatus.IN_PROGRESS || it.status == BookStatus.DONE || it.finished)
        }

        val candidates = items.filter { candidate ->
            !candidate.book.hidden &&
                !candidate.book.trashPending &&
                !candidate.finished &&
                candidate.id != referenceBookId &&
                (candidate.status == BookStatus.NEW || candidate.status == BookStatus.LATER) &&
                activeOrDone.none { done ->
                    done.book.normalizedKey == candidate.book.normalizedKey &&
                        (done.book.authorKey == null || candidate.book.authorKey == null || done.book.authorKey == candidate.book.authorKey)
                }
        }
        if (candidates.isEmpty()) return emptyList()

        val byRecency = candidates.sortedByDescending { it.book.addedAt }
        val recencyBonus = HashMap<String, Int>(byRecency.size)
        byRecency.forEachIndexed { index, item ->
            recencyBonus[item.id] = (10 - index).coerceAtLeast(1)
        }

        return candidates
            .map { candidate -> scoreCandidate(candidate, refAuthorKey, refNarratorKey, authorCounts, narratorCounts, medianDurationMs, recencyBonus[candidate.id] ?: 1) }
            .groupBy { it.item.book.normalizedKey }
            .values
            .map { group -> group.maxWith(compareBy<BookRecommendation> { it.score }.thenBy { it.item.book.addedAt }) }
            .sortedWith(compareByDescending<BookRecommendation> { it.score }.thenByDescending { it.item.book.addedAt })
            .take(limit)
    }

    private fun scoreCandidate(
        candidate: LibraryItem,
        refAuthorKey: String?,
        refNarratorKey: String?,
        authorCounts: Map<String, Int>,
        narratorCounts: Map<String, Int>,
        medianDurationMs: Long?,
        recentPoints: Int,
    ): BookRecommendation {
        var score = recentPoints
        val reasons = ArrayList<Pair<Int, String>>(4)

        val authorKey = TextNormalizer.keyOrNull(candidate.author)
        if (authorKey != null) {
            if (authorKey == refAuthorKey) {
                score += 55
                reasons += 55 to "Du même auteur (${candidate.author})"
            } else {
                val count = authorCounts[authorKey] ?: 0
                if (count > 0) {
                    val pts = 40 + (count * 3).coerceAtMost(12)
                    score += pts
                    reasons += pts to "Même auteur : ${candidate.author}"
                }
            }
        }

        val narratorKey = TextNormalizer.keyOrNull(candidate.narrator)
        if (narratorKey != null) {
            if (narratorKey == refNarratorKey) {
                score += 35
                reasons += 35 to "Même voix (${candidate.narrator})"
            } else {
                val count = narratorCounts[narratorKey] ?: 0
                if (count > 0) {
                    val pts = 25 + (count * 2).coerceAtMost(8)
                    score += pts
                    reasons += pts to "Lu par ${candidate.narrator}"
                }
            }
        }

        val pct = candidate.percent ?: 0f
        if (candidate.status == BookStatus.LATER && pct in 2f..92f) {
            score += 28
            reasons += 28 to "Déjà entamé (${pct.roundToInt()} %)"
        }

        if (candidate.isDownloaded) {
            score += 15
            reasons += 15 to "Prêt hors ligne"
        } else if (candidate.presentTracks > 0) {
            score += 8
            reasons += 8 to "Aperçu hors ligne prêt"
        }

        val dur = candidate.durationMs
        if (dur != null && dur > 0L) {
            if (medianDurationMs != null && medianDurationMs > 0L && abs(dur - medianDurationMs) <= medianDurationMs * 0.35) {
                score += 12
                reasons += 12 to "Format habituel (${Formats.duration(dur)})"
            } else if (dur <= 4L * 3_600_000L) {
                score += 6
                reasons += 6 to "Écoute courte (${Formats.duration(dur)})"
            }
        }

        if (candidate.book.titleConfirmed) score += 5

        val bestReason = reasons.maxByOrNull { it.first }?.second
            ?: if (candidate.status == BookStatus.LATER) "Mis de côté à reprendre" else "Ajouté récemment"

        return BookRecommendation(item = candidate, reason = bestReason, score = score)
    }
}
