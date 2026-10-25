package com.julienmiquel.audiotheque.core.model

import com.julienmiquel.audiotheque.core.db.TrackEntity

/**
 * Avancement calculé sur la durée totale des pistes (cf. spécification, « Avancement :
 * pourcentage et temps restant à la vitesse en cours »).
 */
object ProgressMath {

    /**
     * Durées utilisables pour chaque piste : exacte, sinon estimée ; à défaut, proportionnelle à
     * la taille du fichier en s'appuyant sur les pistes dont la durée est connue.
     */
    fun durations(tracks: List<TrackEntity>, bookDurationMs: Long? = null): List<Long> {
        val known = tracks.filter { (it.durationMs ?: 0) > 0 }
        val msPerByte: Double? = when {
            known.isNotEmpty() && known.sumOf { it.sizeBytes } > 0 ->
                known.sumOf { it.durationMs!! }.toDouble() / known.sumOf { it.sizeBytes }
            bookDurationMs != null && bookDurationMs > 0 && tracks.sumOf { it.sizeBytes } > 0 ->
                bookDurationMs.toDouble() / tracks.sumOf { it.sizeBytes }
            else -> null
        }
        return tracks.map { t ->
            t.durationMs?.takeIf { it > 0 } ?: msPerByte?.let { (t.sizeBytes * it).toLong() } ?: 0L
        }
    }

    fun total(durations: List<Long>): Long = durations.sum()

    /** Position globale dans le livre. */
    fun globalPosition(durations: List<Long>, trackIndex: Int, positionMs: Long): Long {
        if (durations.isEmpty()) return 0
        val index = trackIndex.coerceIn(0, durations.lastIndex)
        return durations.take(index).sum() + positionMs.coerceAtLeast(0)
    }

    fun percent(durations: List<Long>, trackIndex: Int, positionMs: Long): Float {
        val total = total(durations)
        if (total <= 0) return 0f
        return (globalPosition(durations, trackIndex, positionMs) * 100f / total).coerceIn(0f, 100f)
    }

    /** Temps restant à la vitesse en cours. */
    fun remainingMs(durations: List<Long>, trackIndex: Int, positionMs: Long, speed: Float): Long {
        val left = (total(durations) - globalPosition(durations, trackIndex, positionMs)).coerceAtLeast(0)
        return (left / speed.coerceAtLeast(0.1f)).toLong()
    }

    /** Piste et position dans la piste correspondant à une position globale. */
    fun locate(durations: List<Long>, globalMs: Long): Pair<Int, Long> {
        if (durations.isEmpty()) return 0 to 0L
        var remaining = globalMs.coerceAtLeast(0)
        durations.forEachIndexed { index, d ->
            if (remaining < d || index == durations.lastIndex) return index to remaining.coerceAtMost(d)
            remaining -= d
        }
        return durations.lastIndex to durations.last()
    }
}
