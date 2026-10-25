package com.julienmiquel.audiotheque.core.model

import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity

/** Statut affiché : celui du geste en attente s'il y en a un, sinon celui confirmé par Drive. */
val BookEntity.effectiveStatus: BookStatus get() = pendingStatus ?: status

/** Dossier parent attendu une fois la file d'opérations rejouée. */
val BookEntity.effectiveParentId: String get() = pendingParentId ?: parentId

/** Date du dernier changement de statut (geste compris), base des délais de nettoyage. */
val BookEntity.effectiveStatusChangedAt: Long get() = pendingSince ?: statusChangedAt

val BookEntity.hasPendingMove: Boolean get() = pendingStatus != null

/** Titre affiché : correction de l'utilisateur, sinon découpage automatique du nom. */
val BookEntity.displayTitle: String
    get() = customTitle?.takeIf { it.isNotBlank() } ?: parsedTitle

/** Auteur affiché. Une correction vide efface l'auteur proposé. */
val BookEntity.displayAuthor: String?
    get() = (customAuthor ?: parsedAuthor)?.takeIf { it.isNotBlank() }

/** Voix : correction, sinon tags ID3, sinon nom du dossier (« lu par … »). */
val BookEntity.displayNarrator: String?
    get() = (customNarrator ?: tagNarrator ?: parsedNarrator)?.takeIf { it.isNotBlank() }

val BookEntity.isPlayableKind: Boolean get() = kind != BookKind.ZIP

/** « Partie 4 · chapitre 4 », « Chapitre 12 » ou « Piste 3 ». */
val TrackEntity.chapterLabel: String
    get() = when {
        part != null && chapter != null -> "Partie $part · chapitre $chapter"
        chapter != null -> "Chapitre $chapter"
        else -> "Piste ${sortIndex + 1}"
    }

/** Libellé de groupe pour la liste des chapitres (groupée par partie). */
val TrackEntity.partLabel: String?
    get() = part?.let { "Partie $it" }

/** Clés de compte pour la progression. */
object Accounts {
    /** Progression locale avant toute connexion Google. */
    const val LOCAL = "local"

    /** Mode démo (Drive simulé). */
    const val DEMO = "demo"
}
