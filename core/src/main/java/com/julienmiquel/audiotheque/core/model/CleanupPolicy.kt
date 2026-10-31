package com.julienmiquel.audiotheque.core.model

import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.settings.AppSettings

/**
 * Nettoyage selon le statut et l'avancement d'écoute :
 * - Pour un livre « En écoute » (`IN_PROGRESS`) :
 *   - En **mode streaming** (par défaut), seules les pistes `N-1`, `N` et `N+1` autour de la piste
 *     en cours `N` sont téléchargées et conservées sur le disque.
 *   - Hors mode streaming, le reste à écouter (`>= N`) est téléchargé et chaque piste écoutée
 *     (`< N`, ou toutes lorsque le livre est terminé) est supprimée localement.
 *   - Un livre épinglé (« Garder sur ce téléphone ») conserve toutes ses pistes.
 * - Pour les autres statuts : `_A écouter plus tard` garde les pistes présentes 7 jours, `_Ecouté`
 *   24 h (délais réglables) ; les autres statuts ne gardent que les métadonnées, sauf la première
 *   piste des `_Nouveaux` quand l'aperçu est activé.
 *
 * Seul le statut confirmé par Drive compte : tant qu'un déplacement est en attente (geste encore
 * annulable, réseau absent, nouvel essai programmé), rien n'est supprimé par changement de statut.
 * Un délai de grâce de 10 minutes après le changement de statut évite aussi de re-télécharger un
 * livre déplacé par erreur.
 */
object CleanupPolicy {
    const val GRACE_MS = 10L * 60 * 1000
    const val HOUR_MS = 3600L * 1000
    const val DAY_MS = 24 * HOUR_MS

    /**
     * Fenêtre glissante du mode streaming autour de la piste active [currentTrackIndex] (`N`) :
     * pistes `N-1`, `N` et `N+1`, bornées à `0 until totalTracks`.
     */
    fun streamingWindow(currentTrackIndex: Int, totalTracks: Int): IntRange {
        if (totalTracks <= 0) return IntRange.EMPTY
        val n = currentTrackIndex.coerceIn(0, totalTracks - 1)
        return (n - 1).coerceAtLeast(0)..(n + 1).coerceAtMost(totalTracks - 1)
    }

    /**
     * Indices de pistes (`sortIndex`) à télécharger pour un livre « En écoute », dans l'ordre de
     * priorité :
     * - Si [keepAllTracks] (`pinnedOffline` ou collection complète) : toutes les pistes en partant de `N`.
     * - Si [finished] : aucune piste à télécharger.
     * - En **mode streaming** : uniquement `N`, puis `N+1`, puis `N-1`.
     * - Hors mode streaming : toutes les pistes non encore écoutées (`N .. lastIndex`).
     */
    fun orderedDownloadIndices(
        totalTracks: Int,
        currentTrackIndex: Int,
        finished: Boolean,
        streamingMode: Boolean,
        keepAllTracks: Boolean = false,
    ): List<Int> {
        if (totalTracks <= 0) return emptyList()
        val n = currentTrackIndex.coerceIn(0, totalTracks - 1)
        if (keepAllTracks) {
            return (n until totalTracks).toList() + (0 until n).reversed()
        }
        if (finished) return emptyList()
        return if (streamingMode) {
            buildList(3) {
                add(n)
                if (n + 1 < totalTracks) add(n + 1)
                if (n - 1 >= 0) add(n - 1)
            }
        } else {
            (n until totalTracks).toList()
        }
    }

    /**
     * Vrai si la piste [trackSortIndex] d'un livre « En écoute » doit être conservée sur le disque :
     * - Si [keepAllTracks] : toutes les pistes sont conservées.
     * - En **mode streaming** : seules `N-1`, `N` et `N+1` sont conservées (pour une archive `.zip`
     *   déjà extraite, les pistes futures `> N+1` sont aussi gardées afin de ne pas retélécharger
     *   l'archive entière à chaque piste).
     * - Hors mode streaming : toute piste écoutée (`< N`, ou toutes si [finished]) est supprimée.
     */
    fun shouldKeepInProgressTrack(
        trackSortIndex: Int,
        totalTracks: Int,
        currentTrackIndex: Int,
        finished: Boolean,
        streamingMode: Boolean,
        keepAllTracks: Boolean = false,
        isZip: Boolean = false,
    ): Boolean {
        if (totalTracks <= 0) return false
        if (keepAllTracks) return true
        val n = currentTrackIndex.coerceIn(0, totalTracks - 1)
        return when {
            streamingMode && isZip -> trackSortIndex >= (n - 1).coerceAtLeast(0)
            streamingMode -> trackSortIndex in streamingWindow(n, totalTracks)
            finished -> false
            else -> trackSortIndex >= n
        }
    }

    /**
     * Moment à partir duquel la piste n° [trackSortIndex] d'un livre hors « En écoute » peut être
     * supprimée du téléphone, ou null si elle est conservée par son statut.
     */
    fun deadline(
        book: BookEntity,
        trackSortIndex: Int,
        pairMode: PairMode?,
        settings: AppSettings,
        personalStatus: BookStatus? = null,
        personalStatusChangedAt: Long? = null,
    ): Long? {
        if (book.pinnedOffline) return null
        if (book.pendingStatus != null) return null
        if (book.status == BookStatus.COLLECTION && pairMode == PairMode.FULL) return null
        val status = personalStatus ?: book.status
        val changedAt = if (personalStatus != null && personalStatusChangedAt != null) {
            personalStatusChangedAt
        } else {
            book.statusChangedAt
        }
        val retentionMs = when (status) {
            BookStatus.IN_PROGRESS -> return null
            BookStatus.NEW -> if (settings.previewNew && trackSortIndex == 0) return null else 0L
            BookStatus.LATER -> settings.laterRetentionDays * DAY_MS
            BookStatus.DONE -> settings.doneRetentionHours * HOUR_MS
            BookStatus.COLLECTION -> if (pairMode == PairMode.FULL) return null else 0L
            BookStatus.ARCHIVED, BookStatus.UNSORTED -> 0L
        }
        return changedAt + maxOf(retentionMs, GRACE_MS)
    }

    /** Échéance des pistes du livre (hors première piste d'aperçu), pour la fiche livre. */
    fun bookDeadline(
        book: BookEntity,
        pairMode: PairMode?,
        settings: AppSettings,
        personalStatus: BookStatus? = null,
        personalStatusChangedAt: Long? = null,
    ): Long? = deadline(
        book = book,
        trackSortIndex = 1,
        pairMode = pairMode,
        settings = settings,
        personalStatus = personalStatus,
        personalStatusChangedAt = personalStatusChangedAt,
    )
}
