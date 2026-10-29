package com.julienmiquel.audiotheque.core.model

import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.settings.AppSettings

/**
 * Nettoyage selon le statut (cf. spécification, « Comportement par statut ») : seul _En écoute
 * garde toutes ses pistes ; _A écouter plus tard les garde 7 jours, _Ecouté 24 h (délais
 * réglables) ; les autres statuts ne gardent que les métadonnées, sauf la première piste des
 * _Nouveaux quand l'aperçu est activé.
 *
 * Seul le statut confirmé par Drive compte : tant qu'un déplacement est en attente (geste encore
 * annulable, réseau absent, nouvel essai programmé), rien n'est supprimé. Un déplacement en échec
 * n'a donc aucun effet sur les fichiers du téléphone. Un délai de grâce de 10 minutes après le
 * changement de statut évite aussi de re-télécharger un livre déplacé par erreur.
 */
object CleanupPolicy {
    const val GRACE_MS = 10L * 60 * 1000
    const val HOUR_MS = 3600L * 1000
    const val DAY_MS = 24 * HOUR_MS

    /**
     * Moment à partir duquel la piste n° [trackSortIndex] du livre peut être supprimée du
     * téléphone, ou null si elle est conservée.
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
